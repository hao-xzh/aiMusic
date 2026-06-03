package app.pipo.nativeapp.data

import app.pipo.nativeapp.DiagnosticsLogStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 宠物的对话 + 放歌一体流水线 —— 镜像 src/lib/pet-agent.ts + src/lib/music-intent.ts。
 *
 *   1) AI 解析意图 → PetIntent + reply
 *   2) 走本地多路召回 (CandidateRecall.recall)
 *   3) 排序 (CandidateRanker.rank)，应用 recent play / recent recommendation 惩罚
 *   4) 平滑队列 (SmoothQueue) 让接歌曲风衔接自然
 *   5) 本地 candidates 不够 → 用 netease lexical 搜索补
 *   6) 记录到 RecommendationLog 防止 24h 内重复
 *
 * 主对话 SYSTEM_PROMPT 来自 PetPersona —— 用户选定的人格决定语气。
 * 默认人格 (PetPersona.TOXIC) 对齐 src/lib/music-intent.ts，DeepSeek prompt cache 跨平台命中。
 */
class PetAgent(
    private val repository: PipoRepository,
    private val featuresStore: AudioFeaturesStore = PipoGraph.audioFeaturesStore,
    private val semanticStore: TrackSemanticStore = PipoGraph.trackSemanticStore,
    private val indexer: SemanticIndexer = PipoGraph.semanticIndexer,
    private val behaviorLog: BehaviorLog = PipoGraph.behaviorLog,
    private val tasteProfileStore: TasteProfileStore = PipoGraph.tasteProfileStore,
    private val recommendationLog: RecommendationLog = PipoGraph.recommendationLog,
    private val library: LibraryLoader = PipoGraph.library,
    private val embeddingStore: EmbeddingStore = PipoGraph.embeddingStore,
    private val embeddingIndexer: EmbeddingIndexer = PipoGraph.embeddingIndexer,
    private val preferenceEngine: BehaviorPreferenceEngine = PipoGraph.behaviorPreference,
) {

    /**
     * agent 一轮对话产出的**有序动作列表** —— 一次发话可链式落多个写动作
     * （"收藏这首再放点类似的" → [Like, Play]）。reply 是人格化那句话。
     * 纯聊天 / explain 时 actions 为空，只有 reply。
     */
    data class AgentResponse(
        val reply: String,
        val actions: List<AgentAction>,
        val musicReferences: List<PetMemory.MusicReference> = emptyList(),
    ) {
        /** UI / 自动纠偏链路常用：取第一个放歌动作。 */
        fun firstPlay(): AgentAction.Play? = actions.filterIsInstance<AgentAction.Play>().firstOrNull()
    }

    /** 可被 UI 按序执行的写动作。读工具（查历史/搜索/查歌单）不在此列——它们只喂给模型。 */
    sealed class AgentAction {
        /**
         * 放歌。[insert]=true 表示插一首到下一首（不毁队列）；false 表示替换整列。
         * [similar]=true 仅作 UI 提示（"配同款"），召回路径和普通 Play 相同。
         */
        data class Play(
            val initialBatch: List<NativeTrack>,
            val continuous: ContinuousQueueSource?,
            val insert: Boolean = false,
            val similar: Boolean = false,
        ) : AgentAction()
        /** 跳过当前歌。 */
        object Skip : AgentAction()
        /** 收藏 / 取消收藏当前歌（[like]=false 即取消）。 */
        data class Like(val like: Boolean) : AgentAction()
        /** 把当前歌加入 / 移出指定歌单（[add]=false 即移出）。[name] 原样回传，UI 端模糊匹配。 */
        data class Playlist(val add: Boolean, val name: String) : AgentAction()
    }

    suspend fun chat(
        userText: String,
        history: List<PetMemory.ConversationTurn>,
        historySummary: String = "",
        musicReferences: List<PetMemory.MusicReference> = emptyList(),
        currentTrack: NativeTrack?,
        userFacts: String,
        persona: PetPersona = PetPersona.DEFAULT,
    ): AgentResponse {
        val providerId = activeProviderId()
        val toolsSupported = providerId == "deepseek" || providerId == "openai"
        val toolsJson = if (toolsSupported) AGENT_TOOLS else "[]"
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "chat_start",
            fields = mapOf(
                "inputLen" to userText.length,
                "historyCount" to history.size,
                "historySummaryLen" to historySummary.length,
                "hasCurrentTrack" to (currentTrack != null),
                "userFactsLen" to userFacts.length,
                "persona" to persona.id,
                "provider" to providerId,
                "toolsSupported" to toolsSupported,
                "toolCount" to if (toolsSupported) AGENT_TOOL_COUNT else 0,
            ),
        )

        val behaviorEvents = runCatching { behaviorLog.readAll() }.getOrDefault(emptyList())
        val behaviorPreference = runCatching { preferenceEngine.current() }
            .getOrDefault(BehaviorPreferenceSnapshot.Empty)
        deterministicActionResponse(userText, currentTrack, persona)?.let { response ->
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "deterministic_action",
                fields = mapOf(
                    "actionCount" to response.actions.size,
                    "hasCurrentTrack" to (currentTrack != null),
                    "persona" to persona.id,
                ),
            )
            return response
        }
        val messages = JSONArray()
        messages.put(chatMsg("system", persona.toolChatSystemPrompt))
        if (historySummary.isNotBlank()) {
            messages.put(chatMsg("system", "早前对话摘要（只作上下文，不要逐字引用）：\n${historySummary.take(900)}"))
        }
        appendHistoryMessages(messages, history)
        messages.put(chatMsg("user", buildUserMessage(
            userText = userText,
            currentTrack = currentTrack,
            userFacts = userFacts,
            behaviorPreference = behaviorPreference,
            musicReferences = musicReferences,
        )))

        val actions = mutableListOf<AgentAction>()
        val replyParts = LinkedHashSet<String>()
        val newMusicReferences = mutableListOf<PetMemory.MusicReference>()
        var lastListedPlaylists: List<PipoPlaylist> = emptyList()

        for (round in 0 until MAX_STEPS) {
            var raw = ""
            for (attempt in 0..AGENT_CALL_RETRY_COUNT) {
                raw = try {
                    repository.aiChatTools(messages.toString(), toolsJson, 0.7f, 2000)
                } catch (e: Exception) {
                    DiagnosticsLogStore.record(
                        area = "ai_agent",
                        event = "agent_call_failed",
                        fields = errorFields(e) + mapOf("round" to round, "attempt" to attempt),
                    )
                    ""
                }
                if (raw.isNotBlank()) {
                    if (attempt > 0) {
                        DiagnosticsLogStore.record(
                            area = "ai_agent",
                            event = "agent_call_retry_recovered",
                            fields = mapOf("round" to round, "attempt" to attempt),
                        )
                    }
                    break
                }
                DiagnosticsLogStore.record(
                    area = "ai_agent",
                    event = "agent_blank_response",
                    fields = mapOf("round" to round, "attempt" to attempt),
                )
                if (attempt < AGENT_CALL_RETRY_COUNT) {
                    delay(AGENT_CALL_RETRY_DELAYS_MS.getOrElse(attempt) { AGENT_CALL_RETRY_DELAYS_MS.last() })
                }
            }
            if (raw.isBlank()) {
                fallbackPlayResponse(
                    userText = userText,
                    replyCandidate = "",
                    currentTrack = currentTrack,
                    behaviorEvents = behaviorEvents,
                    behaviorPreference = behaviorPreference,
                    persona = persona,
                    reason = "agent_blank_response",
                    playlistContext = lastListedPlaylists,
                )?.let { return it }
                return finalizeAgent(replyParts, actions, fallback = "我这边刚刚断了一下，再说一次。", musicReferences = newMusicReferences)
            }
            val assistant = runCatching { JSONObject(raw) }.getOrNull()
            if (assistant == null) {
                fallbackPlayResponse(
                    userText = userText,
                    replyCandidate = raw.take(MAX_REPLY_CHARS),
                    currentTrack = currentTrack,
                    behaviorEvents = behaviorEvents,
                    behaviorPreference = behaviorPreference,
                    persona = persona,
                    reason = "non_json_text",
                    playlistContext = lastListedPlaylists,
                )?.let { return it }
                return finalizeAgent(replyParts, actions, fallback = raw.take(60), musicReferences = newMusicReferences)
            }
            // 把 assistant 消息原样回灌 —— 下一轮带工具结果再发时，协议要求保留 tool_calls。
            messages.put(assistant)

            val content = assistantContent(assistant)
            val toolCalls = assistant.optJSONArray("tool_calls")
            val calls = if (toolCalls == null) emptyList()
                else (0 until toolCalls.length()).mapNotNull { parseToolCall(toolCalls.optJSONObject(it)) }

            if (calls.isEmpty()) {
                // 纯文本 —— 收尾（纯聊天 / explain / MiMo 降级都走这）。
                // content 只在还没有任何工具 reply 时兜底：读工具轮(查历史/搜歌)不产出
                // reply,这里 content 才是真正答案;但若前面动作工具已给过 reply,这段
                // content 多半是同义旁白,再拼上去就成了重复回答。
                fallbackPlayResponse(
                    userText = userText,
                    replyCandidate = content,
                    currentTrack = currentTrack,
                    behaviorEvents = behaviorEvents,
                    behaviorPreference = behaviorPreference,
                    persona = persona,
                    reason = "text_without_tool",
                    playlistContext = lastListedPlaylists,
                )?.let { return it }
                if (replyParts.isEmpty() && content.isNotBlank()) replyParts.add(content)
                DiagnosticsLogStore.record(
                    area = "ai_agent",
                    event = "agent_finish",
                    fields = mapOf("round" to round, "reason" to "text", "actionCount" to actions.size),
                )
                return finalizeAgent(replyParts, actions, fallback = "嗯。", musicReferences = newMusicReferences)
            }

            val hasReadTool = calls.any { it.name in READ_TOOLS }
            if (!hasReadTool) {
                // 仅动作工具 —— 就地结算并收尾，不再发请求（也就无需回传 tool 结果）。常见请求 1 个往返。
                for (c in calls) {
                    newMusicReferences.addAll(musicReferencesFromArgs(c.args))
                    val (action, reply) = executeActionTool(
                        call = c,
                        userText = userText,
                        currentTrack = currentTrack,
                        behaviorEvents = behaviorEvents,
                        behaviorPreference = behaviorPreference,
                    )
                    if (action != null) actions.add(action)
                    if (reply.isNotBlank()) replyParts.add(reply)
                    DiagnosticsLogStore.record(
                        area = "ai_agent",
                        event = "agent_tool_call",
                        fields = mapOf("round" to round, "tool" to c.name, "kind" to "action"),
                    )
                }
                // say-misfire 兜底：模型只说了话（常见是误用 say）却没产生任何放歌动作，但用户
                // 原话明显是点歌 → 补一次 fallback 放歌，堵住"说了话不干活"。已产生动作(like/skip
                // 等)则尊重模型不覆盖；问当前歌/纯闲聊不会命中 looksLikeMusicPlayRequest，安全。
                if (actions.isEmpty() && looksLikeMusicPlayRequest(userText)) {
                    fallbackPlayResponse(
                        userText = userText,
                        replyCandidate = replyParts.joinToString(" "),
                        currentTrack = currentTrack,
                        behaviorEvents = behaviorEvents,
                        behaviorPreference = behaviorPreference,
                        persona = persona,
                        reason = "action_tool_no_play",
                        playlistContext = lastListedPlaylists,
                    )?.let { return it }
                }
                // 动作工具的 reply 字段才是规范出口。模型经常把同一句话既填进 reply
                // 又写进 content(义同形不同,LinkedHashSet 去不掉),拼起来就成了
                // "深夜配点 R&B… 深夜安静下来配点…" 的重复。content 只在工具一个
                // reply 都没给时兜底。
                if (replyParts.isEmpty() && content.isNotBlank()) replyParts.add(content)
                DiagnosticsLogStore.record(
                    area = "ai_agent",
                    event = "agent_finish",
                    fields = mapOf("round" to round, "reason" to "action", "actionCount" to actions.size),
                )
                return finalizeAgent(replyParts, actions, fallback = "嗯。", musicReferences = newMusicReferences)
            }

            // 含读工具 —— 协议要求**每个** tool_call 都回一条 tool 结果，然后继续下一轮。
            // 动作工具若混在同轮：就地结算 + 回乐观 ack（模型看到 ack 不会重复下手）。
            for (c in calls) {
                newMusicReferences.addAll(musicReferencesFromArgs(c.args))
                val resultText = if (c.name in READ_TOOLS) {
                    if (c.name == "list_playlists") {
                        lastListedPlaylists = playlistsForAgent()
                        formatPlaylistList(lastListedPlaylists)
                    } else {
                        executeReadTool(c, behaviorEvents)
                    }
                } else {
                    val (action, reply) = executeActionTool(
                        call = c,
                        userText = userText,
                        currentTrack = currentTrack,
                        behaviorEvents = behaviorEvents,
                        behaviorPreference = behaviorPreference,
                    )
                    if (action != null) actions.add(action)
                    if (reply.isNotBlank()) replyParts.add(reply)
                    "ok"
                }
                messages.put(toolResultMsg(c.id, resultText))
                DiagnosticsLogStore.record(
                    area = "ai_agent",
                    event = "agent_tool_call",
                    fields = mapOf("round" to round, "tool" to c.name, "kind" to if (c.name in READ_TOOLS) "read" else "action"),
                )
            }
        }

        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "agent_finish",
            fields = mapOf("reason" to "max_steps", "actionCount" to actions.size),
        )
        return finalizeAgent(replyParts, actions, fallback = "想太久了，再说一次？", musicReferences = newMusicReferences)
    }

    private fun finalizeAgent(
        replyParts: Set<String>,
        actions: List<AgentAction>,
        fallback: String,
        musicReferences: List<PetMemory.MusicReference> = emptyList(),
    ): AgentResponse {
        val reply = replyParts.joinToString(" ").trim().ifBlank { fallback }
        return AgentResponse(
            reply = reply.take(MAX_REPLY_CHARS),
            actions = actions.toList(),
            musicReferences = musicReferences.distinctBy { referenceKey(it) }.takeLast(8),
        )
    }

    private suspend fun fallbackPlayResponse(
        userText: String,
        replyCandidate: String,
        currentTrack: NativeTrack?,
        behaviorEvents: List<BehaviorEvent>,
        behaviorPreference: BehaviorPreferenceSnapshot,
        persona: PetPersona,
        reason: String,
        playlistContext: List<PipoPlaylist> = emptyList(),
    ): AgentResponse? {
        fallbackExistingPlaylistResponse(
            userText = userText,
            replyCandidate = replyCandidate,
            reason = reason,
            playlistContext = playlistContext,
        )?.let { return it }
        if (!looksLikeMusicPlayRequest(userText)) return null
        val key = normalizeCommandText(userText)
        val profile = generatedPlaylistProfile(key)
        val intent = if (profile != null) {
            intentFromGeneratedPlaylistProfile(profile, userText)
        } else {
            PetIntent(
                queryText = userText,
                aiExploration = "balanced",
                desiredCount = 30,
            )
        }
        return when (val outcome = buildPlayOutcome(
            requestedIntent = intent,
            queueAction = "replace",
            currentTrack = currentTrack,
            behaviorEvents = behaviorEvents,
            behaviorPreference = behaviorPreference,
        )) {
            is PlayOutcome.Replace -> {
                DiagnosticsLogStore.record(
                    area = "ai_agent",
                    event = "fallback_play",
                    fields = mapOf(
                        "reason" to reason,
                        "queryText" to userText.take(80),
                        "initialCount" to outcome.initialBatch.size,
                        "hasProfile" to (profile != null),
                    ),
                )
                AgentResponse(
                    reply = fallbackPlayReply(replyCandidate, persona),
                    actions = listOf(AgentAction.Play(outcome.initialBatch, outcome.continuous, insert = false, similar = false)),
                )
            }
            is PlayOutcome.Insert -> AgentResponse(
                reply = fallbackPlayReply(replyCandidate, persona),
                actions = listOf(AgentAction.Play(listOf(outcome.track), null, insert = true, similar = false)),
            )
            is PlayOutcome.Empty -> AgentResponse(outcome.note, emptyList())
        }
    }

    private sealed class PlaylistFallbackResult {
        data class Found(val playlist: PipoPlaylist) : PlaylistFallbackResult()
        data class Ambiguous(val candidates: List<PipoPlaylist>) : PlaylistFallbackResult()
        data class Missing(val name: String) : PlaylistFallbackResult()
        object NotPlaylist : PlaylistFallbackResult()
    }

    private suspend fun fallbackExistingPlaylistResponse(
        userText: String,
        replyCandidate: String,
        reason: String,
        playlistContext: List<PipoPlaylist>,
    ): AgentResponse? {
        return when (val result = resolveExistingPlaylistFallback(userText, playlistContext)) {
            is PlaylistFallbackResult.Found -> {
                val tracks = runCatching { repository.tracksForPlaylist(result.playlist.id) }.getOrDefault(emptyList())
                    .filter { it.streamUrl.isNotBlank() || it.neteaseId != null }
                    .take(DEFAULT_PLAYLIST_LIMIT)
                if (tracks.isEmpty()) {
                    AgentResponse("「${result.playlist.name}」是空的。", emptyList())
                } else {
                    DiagnosticsLogStore.record(
                        area = "ai_agent",
                        event = "playlist_fallback_play",
                        fields = mapOf(
                            "reason" to reason,
                            "playlistId" to result.playlist.id,
                            "playlistName" to result.playlist.name,
                            "trackCount" to tracks.size,
                            "queryText" to userText.take(80),
                        ),
                    )
                    AgentResponse(
                        reply = spokenPlayReply(
                            replyCandidate,
                            alignedPlayReply(tracks, insert = false, similar = false, playlistName = result.playlist.name),
                        ),
                        actions = listOf(AgentAction.Play(tracks, continuous = null, insert = false, similar = false)),
                    )
                }
            }
            is PlaylistFallbackResult.Ambiguous -> {
                val choices = playlistChoiceText(result.candidates)
                val reply = if (choices.isBlank()) {
                    "我没抓准你指哪张歌单，说下名字我就放。"
                } else {
                    "我没抓准你指哪张歌单：$choices。"
                }
                AgentResponse(reply, emptyList())
            }
            is PlaylistFallbackResult.Missing -> AgentResponse("没找到歌单「${result.name}」。", emptyList())
            PlaylistFallbackResult.NotPlaylist -> null
        }
    }

    private suspend fun resolveExistingPlaylistFallback(
        userText: String,
        playlistContext: List<PipoPlaylist>,
    ): PlaylistFallbackResult {
        val key = normalizeCommandText(userText)
        if ("歌单" !in key) return PlaylistFallbackResult.NotPlaylist

        val hasPlayVerb = looksLikePlaylistPlayVerb(key)
        val hasSpecificCue = looksLikeSpecificPlaylistReference(key)
        val generatedProfile = generatedPlaylistProfile(key) != null
        val generatedCue = looksLikeGeneratedPlaylistCue(key)
        if (generatedCue && !hasPlayVerb && !hasSpecificCue) return PlaylistFallbackResult.NotPlaylist

        val playlists = if (playlistContext.isNotEmpty()) playlistContext else playlistsForAgent()
        val mentioned = matchPlaylistMention(playlists, key)
        if (mentioned != null && (hasPlayVerb || hasSpecificCue || !generatedCue)) {
            return PlaylistFallbackResult.Found(mentioned)
        }

        val nameGuess = extractRequestedPlaylistName(userText)
        val pronounRequest = looksLikePlaylistPronounRequest(key)
        if (pronounRequest) {
            if (playlistContext.size == 1) return PlaylistFallbackResult.Found(playlistContext.first())
            return PlaylistFallbackResult.Ambiguous(playlistContext.ifEmpty { playlists }.take(5))
        }

        if (hasSpecificCue && nameGuess.isNotBlank()) {
            return PlaylistFallbackResult.Missing(nameGuess)
        }
        if (hasPlayVerb && !generatedProfile) {
            if (nameGuess.isNotBlank()) return PlaylistFallbackResult.Missing(nameGuess)
            return PlaylistFallbackResult.Ambiguous(playlists.take(5))
        }
        return PlaylistFallbackResult.NotPlaylist
    }

    private fun looksLikePlaylistPlayVerb(compact: String): Boolean =
        listOf("播放", "打开", "播", "放", "听").any { it in compact }

    private fun looksLikeSpecificPlaylistReference(compact: String): Boolean =
        listOf(
            "这个", "那个", "这张", "那张", "刚才", "刚刚", "上面", "下面",
            "我的", "我那个", "已有", "现有", "收藏", "网易云", "云盘",
            "歌单里", "歌单里面", "列表里", "里面的",
        ).any { it in compact }

    private fun looksLikePlaylistPronounRequest(compact: String): Boolean =
        listOf("这个歌单", "那个歌单", "这张歌单", "那张歌单", "刚才那个歌单", "刚刚那个歌单").any { it in compact }

    private fun looksLikeGeneratedPlaylistCue(compact: String): Boolean =
        listOf(
            "来个", "来一个", "来份", "来点", "排个", "排一个", "编个", "编一个",
            "安排", "整一个", "整点", "搞个", "给我来", "推荐", "随便",
        ).any { it in compact }

    private fun matchPlaylistMention(playlists: List<PipoPlaylist>, compactText: String): PipoPlaylist? =
        playlists
            .mapNotNull { playlist ->
                val key = normalizeForMatch(playlist.name)
                if (key.isNotEmpty() && key in compactText) playlist to key.length else null
            }
            .maxByOrNull { it.second }
            ?.first

    private fun extractRequestedPlaylistName(text: String): String {
        val beforePlaylist = normalizeCommandText(text).substringBefore("歌单", "")
        if (beforePlaylist.isBlank()) return ""
        var name = beforePlaylist
        listOf("帮我", "给我", "请", "麻烦", "播放", "打开", "播一下", "放一下", "听一下", "播", "放", "听").forEach {
            name = name.removePrefix(it)
        }
        listOf("那个", "这个", "这张", "那张", "刚才", "刚刚", "上面", "下面", "的", "一下", "吧").forEach {
            name = name.removeSuffix(it)
        }
        return name.takeIf { it !in setOf("我的", "已有", "现有", "收藏", "网易云", "云盘") }.orEmpty()
    }

    private fun playlistChoiceText(candidates: List<PipoPlaylist>): String =
        candidates
            .filter { it.name.isNotBlank() }
            .take(5)
            .joinToString("、") { "「${it.name}」" }

    private fun looksLikeMusicPlayRequest(text: String): Boolean {
        val key = normalizeCommandText(text)
        if (key.isBlank()) return false
        if (looksLikeQuestionAboutCurrentTrack(key)) return false
        if (listOf("哪些歌单", "有什么歌单", "列出歌单", "查看歌单", "歌单列表").any { it in key }) return false
        if (looksLikeKnownArtistPlayRequest(key)) return true
        return listOf(
            "来点", "来些", "来首", "来一首", "放点", "放首", "播点", "播首",
            "听点", "听首", "推荐", "安排", "整点", "排个", "编个", "歌单",
            "心动模式", "随便来", "换一批", "类似",
        ).any { it in key }
    }

    private fun looksLikeKnownArtistPlayRequest(compact: String): Boolean {
        if (listOf("是谁", "什么", "介绍", "知道", "了解", "吗", "?", "？").any { it in compact }) return false
        return ARTIST_ALIAS_COMPACT_KEYS.any { alias ->
            compact == alias ||
                compact == "${alias}吧" ||
                compact == "${alias}啊" ||
                compact == "${alias}呀" ||
                compact == "${alias}呗" ||
                (compact.contains(alias) && compact.length <= alias.length + 4)
        }
    }

    private fun fallbackPlayReply(replyCandidate: String, persona: PetPersona): String {
        val cleaned = replyCandidate.trim()
        val tooThin = cleaned.isBlank() ||
            normalizeCommandText(cleaned) in setOf("好", "好的", "行", "安排", "ok") ||
            looksLikeFormulaReply(cleaned)
        if (!tooThin) return cleaned.take(MAX_REPLY_CHARS)
        return when (persona) {
            PetPersona.TOXIC -> "行，直接放。"
            PetPersona.FRIENDLY -> "好，直接放。"
            PetPersona.COLD -> "开始播放。"
            PetPersona.KITTY -> "直接开播喵。"
            PetPersona.JIANGHU -> "走着，直接开播。"
        }
    }

    private suspend fun activeProviderId(): String {
        var providerId = runCatching { repository.aiConfig.first().activeProvider }
            .getOrDefault("")
            .trim()
        if (providerId.isBlank()) {
            runCatching { repository.refreshAiConfig() }
            providerId = runCatching { repository.aiConfig.first().activeProvider }
                .getOrDefault("")
                .trim()
        }
        return providerId.ifBlank { "deepseek" }
    }

    private enum class DirectActionKind {
        Like,
        Unlike,
        DislikeAndSkip,
        Skip,
        NoCurrentTrack,
    }

    /**
     * 本地确定性动作路由。
     *
     * 只处理播放器里“用户自然预期就是立刻执行”的短命令：
     * 加心/取消收藏/不喜欢并跳过/下一首。带“类似、再放、歌单”等组合诉求时
     * 仍交给模型，让它可以一次产出 Like + Play 或 Playlist 这类链式动作。
     */
    private fun deterministicActionResponse(
        userText: String,
        currentTrack: NativeTrack?,
        persona: PetPersona,
    ): AgentResponse? {
        val compact = normalizeCommandText(userText)
        if (compact.isBlank() || looksLikeCompoundMusicRequest(compact)) return null
        if (looksLikeQuestionAboutCurrentTrack(compact)) return null

        val actions = mutableListOf<AgentAction>()
        val kind = when {
            isCancelFavoriteCommand(compact) -> {
                if (currentTrack == null) DirectActionKind.NoCurrentTrack
                else {
                    actions += AgentAction.Like(like = false)
                    DirectActionKind.Unlike
                }
            }
            isDislikeAndSkipCommand(compact) -> {
                if (currentTrack == null) DirectActionKind.NoCurrentTrack
                else {
                    actions += AgentAction.Like(like = false)
                    actions += AgentAction.Skip
                    DirectActionKind.DislikeAndSkip
                }
            }
            isLikeCurrentCommand(compact) -> {
                if (currentTrack == null) DirectActionKind.NoCurrentTrack
                else {
                    actions += AgentAction.Like(like = true)
                    DirectActionKind.Like
                }
            }
            isSkipCommand(compact) -> {
                if (currentTrack == null) DirectActionKind.NoCurrentTrack
                else {
                    actions += AgentAction.Skip
                    DirectActionKind.Skip
                }
            }
            else -> return null
        }

        return AgentResponse(
            reply = directActionReply(persona, kind, currentTrack),
            actions = actions,
        )
    }

    private fun normalizeCommandText(text: String): String =
        text.lowercase()
            .replace(Regex("[\\s，。,.!！?？、~～]+"), "")
            .trim()

    private fun looksLikeCompoundMusicRequest(compact: String): Boolean {
        if (compact.length > 24) return true
        return listOf(
            "再放", "再来", "类似", "一样", "同款", "接着", "然后", "顺便",
            "加到", "歌单", "播放", "放点", "来点", "推荐",
        ).any { it in compact }
    }

    private fun looksLikeQuestionAboutCurrentTrack(compact: String): Boolean =
        compact.endsWith("吗") ||
            "好听吗" in compact ||
            "你喜欢" in compact ||
            "怎么样" in compact ||
            "什么歌" in compact

    private fun isLikeCurrentCommand(compact: String): Boolean =
        listOf(
            "喜欢这首", "我喜欢这首", "很喜欢这首", "爱这首", "太喜欢这首",
            "这首好听", "这歌好听", "这首真好听", "这歌真好听",
            "收藏这首", "加心", "点红心", "红心", "加入我喜欢",
        ).any { it in compact }

    private fun isCancelFavoriteCommand(compact: String): Boolean =
        listOf("取消收藏", "取消红心", "取消加心", "移出我喜欢", "从我喜欢移除")
            .any { it in compact }

    private fun isDislikeAndSkipCommand(compact: String): Boolean =
        listOf("不喜欢这首", "这首不喜欢", "这歌不喜欢", "别放这首", "不要这首")
            .any { it in compact }

    private fun isSkipCommand(compact: String): Boolean =
        listOf("下一首", "下首", "跳过", "切歌", "换一首", "换首", "不想听这个")
            .any { it in compact }

    private fun directActionReply(
        persona: PetPersona,
        kind: DirectActionKind,
        track: NativeTrack?,
    ): String {
        if (kind == DirectActionKind.NoCurrentTrack) {
            return when (persona) {
                PetPersona.TOXIC -> "现在没歌，手伸早了。"
                PetPersona.FRIENDLY -> "现在没在放歌，先放一首再说。"
                PetPersona.COLD -> "当前无歌曲。"
                PetPersona.KITTY -> "现在没有歌可以操作喵。"
                PetPersona.JIANGHU -> "现在没歌，兄弟，先开一首。"
            }
        }
        val title = track?.title.orEmpty().takeIf { it.isNotBlank() } ?: "这首"
        return when (persona) {
            PetPersona.TOXIC -> when (kind) {
                DirectActionKind.Like -> "这首确实能打，我来加心。"
                DirectActionKind.Unlike -> "行，我来摘红心。"
                DirectActionKind.DislikeAndSkip -> "懂，我来撤红心再换。"
                DirectActionKind.Skip -> "换，放过耳朵。"
                DirectActionKind.NoCurrentTrack -> "现在没歌。"
            }
            PetPersona.FRIENDLY -> when (kind) {
                DirectActionKind.Like -> "我来把《${title}》加心。"
                DirectActionKind.Unlike -> "好，我来取消收藏。"
                DirectActionKind.DislikeAndSkip -> "明白，我先撤掉这首，再换下一首。"
                DirectActionKind.Skip -> "好，换下一首。"
                DirectActionKind.NoCurrentTrack -> "现在没在放歌。"
            }
            PetPersona.COLD -> when (kind) {
                DirectActionKind.Like -> "加心。"
                DirectActionKind.Unlike -> "取消收藏。"
                DirectActionKind.DislikeAndSkip -> "移除，下一首。"
                DirectActionKind.Skip -> "下一首。"
                DirectActionKind.NoCurrentTrack -> "无当前歌曲。"
            }
            PetPersona.KITTY -> when (kind) {
                DirectActionKind.Like -> "我来把这首收进红心喵。"
                DirectActionKind.Unlike -> "我来把红心摘掉喵。"
                DirectActionKind.DislikeAndSkip -> "不合口味，我来换掉喵。"
                DirectActionKind.Skip -> "换一首喵。"
                DirectActionKind.NoCurrentTrack -> "没有歌喵。"
            }
            PetPersona.JIANGHU -> when (kind) {
                DirectActionKind.Like -> "这首有味，我来收。"
                DirectActionKind.Unlike -> "行，我来撤红心。"
                DirectActionKind.DislikeAndSkip -> "不对味，我来撤了换。"
                DirectActionKind.Skip -> "走，下一首。"
                DirectActionKind.NoCurrentTrack -> "没歌可动。"
            }
        }
    }

    private fun appendHistoryMessages(messages: JSONArray, history: List<PetMemory.ConversationTurn>) {
        history.takeLast(MAX_HISTORY_MESSAGES).forEach { turn ->
            val role = when (turn.role) {
                PetMemory.ROLE_USER -> "user"
                PetMemory.ROLE_ASSISTANT -> "assistant"
                else -> return@forEach
            }
            val text = cleanHistoryText(turn.text)
            if (text.isNotBlank()) messages.put(chatMsg(role, text))
        }
    }

    private fun cleanHistoryText(text: String): String =
        text.replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_HISTORY_TEXT_CHARS)

    // ---------- 工具调用解析 / 执行 ----------

    private data class ToolCall(val id: String, val name: String, val args: JSONObject)

    private fun parseToolCall(obj: JSONObject?): ToolCall? {
        if (obj == null) return null
        val fn = obj.optJSONObject("function") ?: return null
        val name = fn.optString("name").trim()
        if (name.isEmpty()) return null
        val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }
            .getOrDefault(JSONObject())
        return ToolCall(id = obj.optString("id"), name = name, args = args)
    }

    private fun assistantContent(message: JSONObject): String =
        jsonString(message, "content")

    private fun jsonString(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        return obj.optString(key).trim().takeUnless { it == "null" }.orEmpty()
    }

    private fun chatMsg(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private fun toolResultMsg(toolCallId: String, content: String): JSONObject =
        JSONObject().put("role", "tool").put("tool_call_id", toolCallId).put("content", content)

    /** 读工具：返回喂回给模型的文本，不产生任何副作用。 */
    private suspend fun executeReadTool(call: ToolCall, behaviorEvents: List<BehaviorEvent>): String =
        runCatching {
            when (call.name) {
                "search_catalog" -> {
                    val q = call.args.optString("query").trim()
                    if (q.isBlank()) return@runCatching "需要 query"
                    val limit = call.args.optInt("limit", 8).coerceIn(1, 20)
                    val hits = repository.searchTracks(q, limit)
                    if (hits.isEmpty()) "没搜到「$q」"
                    else hits.joinToString("\n") { "${it.title} — ${it.artist}" }
                }
                "identify_lyrics" -> identifyLyrics(call.args)
                "get_play_history" -> summarizeHistory(behaviorEvents)
                "list_playlists" -> {
                    val pls = playlistsForAgent()
                    formatPlaylistList(pls)
                }
                "get_playlist_tracks" -> {
                    val name = call.args.optString("name").trim()
                    val pls = playlistsForAgent()
                    val target = matchPlaylistByName(pls, name)
                        ?: return@runCatching "没找到歌单「$name」"
                    val limit = call.args.optInt("limit", 20).coerceIn(1, 50)
                    val tracks = runCatching { repository.tracksForPlaylist(target.id) }.getOrDefault(emptyList())
                    if (tracks.isEmpty()) "「${target.name}」是空的"
                    else "「${target.name}」：\n" + tracks.take(limit).joinToString("\n") { "${it.title} — ${it.artist}" }
                }
                "get_taste_profile" -> summarizeTaste()
                else -> "未知读工具：${call.name}"
            }
        }.getOrElse { "（${call.name} 出错：${it.message ?: it::class.java.simpleName}）" }

    private data class LyricCandidate(
        val track: NativeTrack,
        val score: Double,
        val matchedLine: String,
    )

    private suspend fun identifyLyrics(args: JSONObject): String {
        val line = args.optString("line").ifBlank { args.optString("query") }.trim()
        if (line.isBlank()) return "需要歌词片段"
        val artistHint = args.optString("artist").trim()
        val limit = args.optInt("limit", 8).coerceIn(3, 12)
        val queries = lyricSearchQueries(line, artistHint)
        if (queries.isEmpty()) return "需要更长一点的歌词片段"

        val candidates = LinkedHashMap<Long, NativeTrack>()
        for (q in queries) {
            val hits = runCatching { repository.searchTracks(q, limit) }
                .getOrDefault(emptyList())
            for (hit in hits) {
                val id = hit.neteaseId ?: continue
                if (!candidates.containsKey(id)) candidates[id] = hit
                if (candidates.size >= limit) break
            }
            if (candidates.size >= limit) break
        }
        if (candidates.isEmpty()) {
            return "没搜到歌词候选「${line.take(40)}」。不要说已找到，也不要播放。"
        }

        val matches = ArrayList<LyricCandidate>()
        for (track in candidates.values) {
            val id = track.neteaseId ?: continue
            val lyrics = runCatching { repository.lyricsForTrack(id.toString()) }
                .getOrDefault(emptyList())
            var bestScore = 0.0
            var bestLine = ""
            for (lyric in flattenLyricLines(lyrics)) {
                val score = lyricMatchScore(line, lyric)
                if (score > bestScore) {
                    bestScore = score
                    bestLine = lyric
                }
            }
            if (bestScore > 0.18) {
                matches += LyricCandidate(track, bestScore, bestLine)
            }
        }
        val ranked = matches.sortedByDescending { it.score }.take(5)
        if (ranked.isEmpty()) {
            val hint = candidates.values.take(3).joinToString("；") { "${it.title} — ${it.artist}" }
            return "搜到候选但歌词没对上。候选：$hint。不要断言是哪首，向用户要更多歌词。"
        }

        val highConfidence = ranked.first().score >= 0.78
        val header = if (highConfidence) {
            "高置信歌词候选。可以播放第 1 首；若用户只是问歌名，直接回答。"
        } else {
            "低置信歌词候选。不要断言，先把候选给用户确认。"
        }
        return header + "\n" + ranked.joinToString("\n") { c ->
            val score = "%.2f".format(java.util.Locale.US, c.score)
            "${c.track.title} — ${c.track.artist} | score=$score | 命中: ${c.matchedLine.take(64)}"
        }
    }

    private fun lyricSearchQueries(line: String, artistHint: String): List<String> {
        val cleaned = line
            .replace(Regex("[\"“”‘’《》<>]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.length < 3) return emptyList()
        val compact = normalizeLyricText(cleaned)
        val keywords = if (compact.length > 16 && cleaned.any { it.isWhitespace() }) {
            cleaned.split(Regex("\\s+"))
                .filter { it.length >= 3 }
                .take(6)
                .joinToString(" ")
        } else cleaned.take(36)

        val out = LinkedHashSet<String>()
        if (artistHint.isNotBlank()) out.add("$artistHint $keywords")
        out.add(keywords)
        if (cleaned != keywords) out.add(cleaned.take(48))
        return out.filter { it.length >= 2 }.take(4)
    }

    private fun flattenLyricLines(lines: List<PipoLyricLine>): List<String> {
        val out = ArrayList<String>()
        fun addLine(line: PipoLyricLine) {
            if (line.text.isNotBlank()) out.add(line.text.trim())
            line.companionLines.forEach(::addLine)
        }
        lines.forEach(::addLine)
        return out
    }

    private fun lyricMatchScore(query: String, line: String): Double {
        val q = normalizeLyricText(query)
        val l = normalizeLyricText(line)
        if (q.length < 3 || l.length < 3) return 0.0
        if (q in l || l in q) {
            val ratio = minOf(q.length, l.length).toDouble() / maxOf(q.length, l.length).toDouble()
            return 0.82 + ratio * 0.18
        }
        val qTokens = lyricTokens(query)
        val lTokens = lyricTokens(line)
        if (qTokens.isNotEmpty() && lTokens.isNotEmpty()) {
            val overlap = qTokens.count { it in lTokens }.toDouble() / qTokens.size
            if (overlap > 0.0) return overlap * 0.72
        }
        val qChars = q.toSet()
        val lChars = l.toSet()
        if (qChars.isEmpty()) return 0.0
        return (qChars.count { it in lChars }.toDouble() / qChars.size) * 0.42
    }

    private fun normalizeLyricText(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    private fun lyricTokens(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()

    private fun inferQueueActionForNamedTrack(rawQueueAction: String, intent: PetIntent): String {
        if (rawQueueAction == "insert") return "insert"
        val titles = namedTrackTitles(intent)
        if (titles.size != 1) return "replace"
        val q = intent.queryText.lowercase()
        val asksForQueue = Regex("从.{1,20}开始").containsMatchIn(q) || listOf(
            "来一组", "来几首", "放点", "听点", "一些", "类似",
            "同款", "歌手", "专辑", "artist", "album", "similar",
        ).any { it in q }
        return if (asksForQueue) "replace" else "insert"
    }

    /** 动作工具：累积成 AgentAction（UI 后续按序执行），返回 (action?, 人格 reply)。 */
    private suspend fun executeActionTool(
        call: ToolCall,
        userText: String,
        currentTrack: NativeTrack?,
        behaviorEvents: List<BehaviorEvent>,
        behaviorPreference: BehaviorPreferenceSnapshot,
    ): Pair<AgentAction?, String> {
        val reply = jsonString(call.args, "reply")
        return when (call.name) {
            "play_queue", "play_similar" -> {
                val similar = call.name == "play_similar"
                val intentObj = call.args.optJSONObject("intent") ?: JSONObject()
                val intent = parseIntentFromArgs(intentObj, fallbackQuery = userText.ifBlank { reply })
                // 指代承接("听这个/那首")完全交给模型:它从对话 + music_references 里认出具体
                // 歌名填进 intent,并自行决定 queue_action(单首→insert)。不再用关键词正则兜底。
                val rawQueueAction = call.args.optString("queue_action").trim().lowercase()
                    .ifBlank { intentObj.optJSONObject("queueIntent")?.optString("action")?.lowercase().orEmpty() }
                val queueAction = inferQueueActionForNamedTrack(rawQueueAction, intent)
                when (val outcome = buildPlayOutcome(intent, queueAction, currentTrack, behaviorEvents, behaviorPreference)) {
                    is PlayOutcome.Replace ->
                        AgentAction.Play(outcome.initialBatch, outcome.continuous, insert = false, similar = similar) to
                            spokenPlayReply(reply, alignedPlayReply(outcome.initialBatch, insert = false, similar = similar))
                    is PlayOutcome.Insert ->
                        AgentAction.Play(listOf(outcome.track), null, insert = true, similar = false) to
                            spokenPlayReply(reply, alignedPlayReply(listOf(outcome.track), insert = true, similar = false))
                    is PlayOutcome.Empty ->
                        // 没找到任何曲目 —— 模型的乐观回复（"好，来原版《September》"）已经不成立，
                        // 不能再和"没找到"拼一起（就成了"来原版…（这首我没找到）"的自相矛盾）。只回 note。
                        null to outcome.note
                }
            }
            "play_playlist" -> {
                val name = call.args.optString("name").trim()
                val limit = call.args.optInt("limit", 80).coerceIn(1, 160)
                if (name.isBlank()) return null to "说个歌单名。"
                val generatedIntent = generatedPlaylistIntent(name, userText, reply)
                if (generatedIntent != null) {
                    DiagnosticsLogStore.record(
                        area = "ai_agent",
                        event = "playlist_phrase_as_queue",
                        fields = mapOf(
                            "name" to name.take(40),
                            "queryText" to generatedIntent.queryText.take(80),
                            "orderStyle" to generatedIntent.orderStyle,
                        ),
                    )
                    return when (val outcome = buildPlayOutcome(
                        requestedIntent = generatedIntent,
                        queueAction = "replace",
                        currentTrack = currentTrack,
                        behaviorEvents = behaviorEvents,
                        behaviorPreference = behaviorPreference,
                    )) {
                        is PlayOutcome.Replace ->
                            AgentAction.Play(outcome.initialBatch, outcome.continuous, insert = false, similar = false) to
                                spokenPlayReply(reply, alignedPlayReply(outcome.initialBatch, insert = false, similar = false))
                        is PlayOutcome.Insert ->
                            AgentAction.Play(listOf(outcome.track), null, insert = true, similar = false) to
                                spokenPlayReply(reply, alignedPlayReply(listOf(outcome.track), insert = true, similar = false))
                        is PlayOutcome.Empty ->
                            null to outcome.note
                    }
                }
                val playlists = playlistsForAgent()
                val target = matchPlaylistByName(playlists, name)
                    ?: return null to "没找到歌单「$name」。"
                val tracks = runCatching { repository.tracksForPlaylist(target.id) }.getOrDefault(emptyList())
                    .filter { it.streamUrl.isNotBlank() || it.neteaseId != null }
                    .take(limit)
                if (tracks.isEmpty()) {
                    null to "「${target.name}」是空的。"
                } else {
                    DiagnosticsLogStore.record(
                        area = "ai_agent",
                        event = "playlist_ready",
                        fields = mapOf(
                            "playlistId" to target.id,
                            "playlistName" to target.name,
                            "trackCount" to tracks.size,
                        ),
                    )
                    AgentAction.Play(tracks, continuous = null, insert = false, similar = false) to
                        spokenPlayReply(reply, alignedPlayReply(tracks, insert = false, similar = false, playlistName = target.name))
                }
            }
            "skip" -> {
                if (currentTrack == null) null to "现在没在放歌，跳不了。"
                else AgentAction.Skip to "换下一首。"
            }
            "like" -> {
                if (currentTrack == null) null to "现在没在放歌，没法加心。"
                else AgentAction.Like(like = true) to "我来给${formatTrackForReply(currentTrack)}加心。"
            }
            "unlike" -> {
                if (currentTrack == null) null to "现在没在放歌，没法取消收藏。"
                else AgentAction.Like(like = false) to "我来取消${formatTrackForReply(currentTrack)}的收藏。"
            }
            "add_to_playlist" -> {
                val name = call.args.optString("playlist_name").trim()
                when {
                    currentTrack == null -> null to "现在没在放歌，没法加歌单。"
                    name.isEmpty() -> null to "说个歌单名。"
                    isCloudDiskName(name) -> null to "「我的网盘」是网易云上传的歌，没法在这儿往里加歌哦。"
                    else -> {
                        val playlists = playlistsForAgent()
                        val target = matchPlaylistByName(playlists, name)
                            ?: return null to "没找到歌单「$name」。"
                        AgentAction.Playlist(add = true, name = target.name) to "我来把当前这首加到「${target.name}」。"
                    }
                }
            }
            "remove_from_playlist" -> {
                val name = call.args.optString("playlist_name").trim()
                when {
                    currentTrack == null -> null to "现在没在放歌，没法从歌单移除。"
                    name.isEmpty() -> null to "说个歌单名。"
                    isCloudDiskName(name) -> null to "「我的网盘」不是普通歌单，没法从这儿删歌。"
                    else -> {
                        val playlists = playlistsForAgent()
                        val target = matchPlaylistByName(playlists, name)
                            ?: return null to "没找到歌单「$name」。"
                        AgentAction.Playlist(add = false, name = target.name) to "我来把当前这首从「${target.name}」移出。"
                    }
                }
            }
            "say" -> null to reply
            else -> null to reply
        }
    }

    private fun alignedPlayReply(
        tracks: List<NativeTrack>,
        insert: Boolean,
        similar: Boolean,
        playlistName: String? = null,
    ): String {
        if (tracks.isEmpty()) return if (insert) "下一首接上。" else "开始播放。"
        return when {
            insert -> "下一首接上。"
            playlistName != null -> "打开「$playlistName」。"
            similar -> "接几首同款。"
            else -> "放着。"
        }
    }

    /**
     * 放歌成功时优先用模型那句**人格化** reply（毒舌/亲和/高冷/小猫咪/江湖各不相同）；
     * 只有它空着或太敷衍（"好/行/安排"）才退回 alignedPlayReply 的对齐模板。
     * 之前无论人格一律用模板（"现在放这组，先放《X》"），等于把人格在最高频的放歌
     * 动作上抹平了——这是"说话不自然、人格没区别"的主因。失败(Empty)仍只回 note，不在此列。
     */
    private fun spokenPlayReply(modelReply: String, template: String): String {
        val cleaned = modelReply.trim()
        val tooThin = cleaned.isBlank() ||
            normalizeCommandText(cleaned) in setOf("好", "好的", "行", "安排", "ok", "嗯", "play") ||
            looksLikeFormulaReply(cleaned)
        return if (tooThin) template else cleaned.take(MAX_REPLY_CHARS)
    }

    private fun looksLikeFormulaReply(reply: String): Boolean {
        val compact = normalizeCommandText(reply)
        if (compact.isBlank()) return false
        val catalogue = Regex("从.{1,32}到.{1,32}").containsMatchIn(compact) ||
            Regex("涵盖.{1,32}到.{1,32}").containsMatchIn(compact) ||
            listOf("包括", "覆盖").any { it in compact } && listOf("风格", "类型", "曲风", "范围").any { it in compact }
        if (catalogue) return true
        return listOf(
            "现在放这组",
            "开始放这组",
            "这组先",
            "已为你",
            "根据你的需求",
            "为你推荐",
            "我给你安排一组",
            "给你安排一组",
        ).any { compact.startsWith(it) }
    }

    private fun formatTrackForReply(track: NativeTrack): String {
        val title = track.title.trim().ifBlank { "这首歌" }
        val artist = track.artist.trim()
        return if (artist.isBlank()) "《$title》" else "《$title》-$artist"
    }

    private data class GeneratedPlaylistProfile(
        val moods: List<String>,
        val scenes: List<String>,
        val textures: List<String>,
        val genres: List<String>,
        val mainStyles: List<String>,
        val adjacentStyles: List<String>,
        val surpriseStyles: List<String>,
        val avoidStyles: List<String> = emptyList(),
        val energy: String = "mid",
        val tempoFeel: String = "any",
        val transitionStyle: String = "soft",
        val orderStyle: String = "smooth",
        val exploration: String = "balanced",
    )

    /**
     * “来个晚安歌单”里的“歌单”是自然语言里的编排单位，不一定是用户已有歌单。
     * 模型误调 play_playlist 时，在执行层转回 play_queue，避免多问“选哪个歌单”。
     */
    private fun generatedPlaylistIntent(name: String, userText: String, reply: String): PetIntent? {
        val nameKey = normalizeCommandText(name)
        if (nameKey.isBlank()) return null
        val textKey = normalizeCommandText(userText)
        val explicitlyExisting = listOf(
            "我的", "我那个", "已有", "现有", "收藏", "网易云", "云盘",
            "这个", "那个", "这张", "那张", "刚才", "刚刚", "播放", "打开",
            "歌单里", "歌单里面", "列表里", "里面的",
        ).any { it in textKey }
        if (explicitlyExisting) return null

        val generatedCue = listOf(
            "来个", "来一个", "来份", "来点", "排个", "排一个", "编个", "编一个",
            "安排", "整一个", "整点", "搞个", "给我来", "推荐", "随便",
        ).any { it in textKey }
        val bareVibePlaylist = textKey == "${nameKey}歌单" ||
            (textKey.endsWith("${nameKey}歌单") && textKey.length <= nameKey.length + 6)
        val profile = generatedPlaylistProfile("$nameKey$textKey")
        if (!generatedCue && !bareVibePlaylist && profile == null) return null

        val p = profile ?: GeneratedPlaylistProfile(
            moods = listOf(name),
            scenes = emptyList(),
            textures = emptyList(),
            genres = emptyList(),
            mainStyles = listOf(name),
            adjacentStyles = emptyList(),
            surpriseStyles = emptyList(),
        )
        val query = userText.ifBlank { reply.ifBlank { "来个${name}歌单" } }
        return intentFromGeneratedPlaylistProfile(p, query)
    }

    private fun intentFromGeneratedPlaylistProfile(
        profile: GeneratedPlaylistProfile,
        queryText: String,
    ): PetIntent {
        return PetIntent(
            queryText = queryText,
            softMoods = profile.moods,
            softScenes = profile.scenes,
            softTextures = profile.textures,
            softEnergy = profile.energy,
            softTempoFeel = profile.tempoFeel,
            musicHintsMoods = profile.moods,
            musicHintsScenes = profile.scenes,
            musicHintsGenres = profile.genres,
            musicHintsEnergy = profile.energy,
            musicHintsTransitionStyle = profile.transitionStyle,
            refStyles = profile.mainStyles,
            aiMainStyles = profile.mainStyles,
            aiAdjacentStyles = profile.adjacentStyles,
            aiSurpriseStyles = profile.surpriseStyles,
            aiAvoidStyles = profile.avoidStyles,
            aiExploration = profile.exploration,
            orderStyle = profile.orderStyle,
            desiredCount = 30,
        )
    }

    private fun generatedPlaylistProfile(key: String): GeneratedPlaylistProfile? {
        fun containsAny(vararg words: String): Boolean = words.any { it in key }
        return when {
            containsAny("晚安", "睡前", "睡觉", "睡眠", "助眠", "入眠", "失眠", "夜深", "夜晚") ->
                GeneratedPlaylistProfile(
                    moods = listOf("calm", "night", "soothing"),
                    scenes = listOf("night", "sleep"),
                    textures = listOf("soft", "warm", "minimal"),
                    genres = listOf("pop", "folk", "r&b"),
                    mainStyles = listOf("night", "calm", "soft", "warm", "acoustic"),
                    adjacentStyles = listOf("folk", "r&b", "jazz", "ambient"),
                    surpriseStyles = listOf("dream pop", "city pop"),
                    avoidStyles = listOf("party", "noisy", "aggressive", "rap-heavy"),
                    energy = "low",
                    tempoFeel = "slow",
                    orderStyle = "sleep",
                    exploration = "safe",
                )
            containsAny("学习", "工作", "写代码", "专注", "办公") ->
                GeneratedPlaylistProfile(
                    moods = listOf("calm", "focus"),
                    scenes = listOf("focus", "coding"),
                    textures = listOf("minimal", "smooth"),
                    genres = listOf("pop", "electronic", "jazz"),
                    mainStyles = listOf("focus", "calm", "minimal", "smooth"),
                    adjacentStyles = listOf("ambient", "lofi", "jazz"),
                    surpriseStyles = listOf("neo soul"),
                    avoidStyles = listOf("noisy", "aggressive"),
                    energy = "mid",
                    tempoFeel = "medium",
                )
            containsAny("通勤", "开车", "路上", "地铁") ->
                GeneratedPlaylistProfile(
                    moods = listOf("smooth", "uplifting"),
                    scenes = listOf("driving", "city walk"),
                    textures = listOf("smooth", "bright"),
                    genres = listOf("pop", "rock", "r&b"),
                    mainStyles = listOf("driving", "city pop", "smooth", "uplifting"),
                    adjacentStyles = listOf("rock", "r&b", "electronic"),
                    surpriseStyles = listOf("funk"),
                    energy = "mid",
                    tempoFeel = "medium",
                )
            containsAny("跑步", "健身", "运动") ->
                GeneratedPlaylistProfile(
                    moods = listOf("energetic", "uplifting"),
                    scenes = listOf("workout"),
                    textures = listOf("bright", "punchy"),
                    genres = listOf("pop", "electronic", "hip-hop"),
                    mainStyles = listOf("energetic", "dance", "workout", "fast"),
                    adjacentStyles = listOf("electronic", "hip-hop", "rock"),
                    surpriseStyles = listOf("funk"),
                    energy = "high",
                    tempoFeel = "fast",
                    transitionStyle = "party",
                    orderStyle = "energy_up",
                    exploration = "adventurous",
                )
            containsAny("派对", "蹦迪", "酒吧", "嗨") ->
                GeneratedPlaylistProfile(
                    moods = listOf("energetic", "party"),
                    scenes = listOf("party"),
                    textures = listOf("bright", "punchy"),
                    genres = listOf("electronic", "pop", "hip-hop"),
                    mainStyles = listOf("party", "dance", "high energy"),
                    adjacentStyles = listOf("electronic", "funk", "hip-hop"),
                    surpriseStyles = listOf("remix"),
                    energy = "high",
                    tempoFeel = "fast",
                    transitionStyle = "party",
                    orderStyle = "party",
                    exploration = "adventurous",
                )
            containsAny("早安", "起床", "清晨", "早上") ->
                GeneratedPlaylistProfile(
                    moods = listOf("uplifting", "fresh"),
                    scenes = listOf("morning"),
                    textures = listOf("bright", "warm"),
                    genres = listOf("pop", "folk", "r&b"),
                    mainStyles = listOf("morning", "warm", "uplifting", "bright"),
                    adjacentStyles = listOf("folk", "city pop", "soul"),
                    surpriseStyles = listOf("funk"),
                    energy = "mid",
                    tempoFeel = "medium",
                    orderStyle = "energy_up",
                )
            containsAny("下雨", "雨天", "雨夜") ->
                GeneratedPlaylistProfile(
                    moods = listOf("melancholic", "calm", "rainy"),
                    scenes = listOf("rainy day", "night"),
                    textures = listOf("soft", "atmospheric"),
                    genres = listOf("pop", "folk", "r&b"),
                    mainStyles = listOf("rainy", "melancholic", "soft", "night"),
                    adjacentStyles = listOf("folk", "jazz", "r&b"),
                    surpriseStyles = listOf("dream pop"),
                    avoidStyles = listOf("party", "noisy"),
                    energy = "low",
                    tempoFeel = "slow",
                    exploration = "safe",
                )
            else -> null
        }
    }

    private fun musicReferencesFromArgs(args: JSONObject): List<PetMemory.MusicReference> {
        val arr = args.optJSONArray("music_references") ?: return emptyList()
        val out = ArrayList<PetMemory.MusicReference>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = jsonString(item, "title")
            if (title.isBlank()) continue
            out.add(
                PetMemory.MusicReference(
                    title = title,
                    artist = jsonString(item, "artist"),
                    reason = jsonString(item, "reason"),
                )
            )
        }
        return out
    }

    private fun referenceKey(ref: PetMemory.MusicReference): String =
        normalizeForMatch("${ref.title}|${ref.artist}")

    /**
     * 给 AI 看的歌单列表 = 真实歌单 + "我的网盘"。网盘是 LibraryPage.CloudDisk 特殊页，
     * 不在 repository.playlists 里（sentinel id），不补的话 AI 根本不知道它存在、也没法
     * list / 播 / 看它。登录了或本地已缓存到网盘曲目时才补。tracksForPlaylist 已把 sentinel
     * 路由到 cloudDiskTracks，所以 play_playlist / get_playlist_tracks 拿它和普通歌单一样。
     */
    private suspend fun playlistsForAgent(): List<PipoPlaylist> {
        val real = runCatching { repository.playlists.first() }.getOrDefault(emptyList())
        val cloudCount = runCatching { repository.cloudTracks.first().size }.getOrDefault(0)
        val loggedIn = runCatching { repository.account.first() != null }.getOrDefault(false)
        if (!loggedIn && cloudCount == 0) return real
        val cloud = PipoPlaylist(id = CLOUD_DISK_PLAYLIST_ID, name = "我的网盘", trackCount = cloudCount)
        return listOf(cloud) + real
    }

    private fun formatPlaylistList(playlists: List<PipoPlaylist>): String =
        if (playlists.isEmpty()) {
            "（没有歌单）"
        } else {
            playlists.joinToString("\n") { "${it.name}（${it.trackCount} 首）" }
        }

    /** 网盘是网易云上传曲目，不是可增删的普通歌单。 */
    private fun isCloudDiskName(name: String): Boolean {
        val key = normalizeForMatch(name)
        val cloud = normalizeForMatch("我的网盘")
        return key.isNotEmpty() && (key == cloud || key in cloud || cloud in key)
    }

    private fun matchPlaylistByName(playlists: List<PipoPlaylist>, name: String): PipoPlaylist? {
        val key = normalizeForMatch(name)
        if (key.isEmpty()) return null
        return playlists.firstOrNull { normalizeForMatch(it.name) == key }
            ?: playlists.firstOrNull {
                val n = normalizeForMatch(it.name)
                n.isNotEmpty() && (key in n || n in key)
            }
    }

    private fun summarizeHistory(events: List<BehaviorEvent>): String {
        val played = events
            .filter { it.type == BehaviorType.PlayStarted || it.type == BehaviorType.Completed }
            .sortedByDescending { it.tsMs }
            .distinctBy { "${it.title}|${it.artist}" }
            .take(10)
        val skipped = events
            .filter { it.type == BehaviorType.Skipped || (it.type == BehaviorType.ManualCut && it.completionPct < 0.6f) }
            .sortedByDescending { it.tsMs }
            .distinctBy { "${it.title}|${it.artist}" }
            .take(5)
        val sb = StringBuilder()
        if (played.isNotEmpty()) {
            sb.append("最近听过：\n")
            sb.append(played.joinToString("\n") { "${it.title} — ${it.artist}" })
        } else {
            sb.append("还没听歌记录")
        }
        if (skipped.isNotEmpty()) {
            sb.append("\n最近跳过（负反馈）：\n")
            sb.append(skipped.joinToString("\n") { "${it.title} — ${it.artist}" })
        }
        return sb.toString()
    }

    private fun summarizeTaste(): String {
        val tp = tasteProfileStore.flow.value ?: return "还没有口味画像（可以让 TA 去蒸馏歌单）"
        val genres = tp.genres.take(6).joinToString("、") { it.tag }
        val artists = tp.topArtists.take(8).joinToString("、") { it.name }
        return buildString {
            if (tp.summary.isNotBlank()) appendLine(tp.summary)
            if (genres.isNotBlank()) appendLine("常听风格：$genres")
            if (artists.isNotBlank()) append("常听艺人：$artists")
        }.trim()
    }

    // ---------- 放歌召回 pipeline（被 play_queue / play_similar 复用）----------

    private sealed class PlayOutcome {
        /** 替换整列。 */
        data class Replace(val initialBatch: List<NativeTrack>, val continuous: ContinuousQueueSource?) : PlayOutcome()
        /** 插一首。 */
        data class Insert(val track: NativeTrack) : PlayOutcome()
        /** 没找到任何曲目；[note] 给模型/用户的一句解释。 */
        data class Empty(val note: String) : PlayOutcome()
    }

    private suspend fun buildPlayOutcome(
        requestedIntent: PetIntent,
        queueAction: String,
        currentTrack: NativeTrack?,
        behaviorEvents: List<BehaviorEvent>,
        behaviorPreference: BehaviorPreferenceSnapshot,
    ): PlayOutcome {
        // 先用用户原话补齐模型漏填的硬约束/软偏好，再把"安静/嗨/深夜"这类能量诉求补进
        // softEnergy/musicHintsEnergy。模型偶发只写一句泛泛 reply 或 recommendationPlan，
        // 这里必须兜住，避免点歌退回"按历史口味随便推"。
        val intent = normalizeUserIntent(requestedIntent)
        val desired = intent.desiredCount.coerceIn(8, 60)
        val requestedArtistKeys = requestedArtistKeys(intent)
        val tasteProfile = tasteProfileStore.flow.value
        val artistSearchQueries = artistFirstSearchQueries(intent, intent.queryText)
        val behaviorSearchQueries = if (requestedArtistKeys.isEmpty()) behaviorPreference.onlineSeeds(maxItems = 4) else emptyList()
        val styleSearchQueries = if (requestedArtistKeys.isEmpty()) {
            heartModeStyleSearchQueries(intent, tasteProfile, behaviorPreference)
        } else {
            emptyList()
        }
        val onlineSeedQueries = if (requestedArtistKeys.isEmpty() && !hasSpecificSearchIntent(intent)) {
            mergeSearchQueries(behaviorSearchQueries + styleSearchQueries, artistSearchQueries, maxItems = 10)
        } else {
            mergeSearchQueries(artistSearchQueries, behaviorSearchQueries + styleSearchQueries, maxItems = 10)
        }
        // 字面撞名防护：纯口味驱动（没点名艺人、没具体意图）时，种子词是画像拼出的通用短语
        // （如 "pop night"）。网易云按歌名字面命中，会搜回一堆标题恰好==种子词的同名歌。
        // 把这些"标题==整条种子词"的命中丢掉——它们是字面撞名，不是真的贴合口味。
        // 仅在纯口味场景启用：用户点名要某首歌/某歌手时绝不动（那时本就该字面命中）。
        val dropTitleKeys: Set<String> =
            if (requestedArtistKeys.isEmpty() && !hasSpecificSearchIntent(intent)) {
                (behaviorSearchQueries + styleSearchQueries).mapNotNull { q ->
                    TrackDedupe.normalizeTitle(q).takeIf { it.isNotEmpty() }
                }.toSet()
            } else {
                emptySet()
            }

        // 1) 本地库 → 多路召回 → 排序
        val localTracks = runCatching { library.library() }.getOrDefault(emptyList())
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "intent_play",
            fields = mapOf(
                "desired" to desired,
                "queueAction" to queueAction,
                "libraryCount" to localTracks.size,
                "hardTrackCount" to intent.hardTracks.size,
                "hardArtistCount" to intent.hardArtists.size,
                "promptArtistCount" to requestedArtistKeys.size,
                "behaviorPreferenceConfidence" to behaviorPreference.confidence,
                "behaviorSeedCount" to behaviorSearchQueries.size,
                "aiPlanMainCount" to intent.aiMainStyles.size,
                "aiPlanAdjacentCount" to intent.aiAdjacentStyles.size,
                "aiPlanSurpriseCount" to intent.aiSurpriseStyles.size,
                "aiExploration" to intent.aiExploration,
            ),
        )

        // 1a) 命名歌曲 PIN —— 用户在话里明确点过的歌（"放七百年后"、"陈奕迅 浮夸"），
        //     直接钉到队首；recall 只是"补差"，不是"覆盖用户明示意图"。
        //     之前 hardTracks 字段在 recall 里完全没人用，textTracks 也只是 +0.6 分
        //     完全可能被 audio/semantic/behavior 几路压下去 —— 这是 bug 不是 feature。
        val pinnedNamed = resolvePinnedTracks(intent, localTracks)

        // 1b) Insert 模式 —— 用户只想插一首（"放浮夸"、"来一首XX"），不毁掉当前队列。
        //     不跑后续召回，只解析命名歌；命中就走 Insert action 让上层 insertNext。
        //     如果命名歌完全找不到（本地没有 + 在线也搜不到），走 chat 礼貌拒绝。
        if (queueAction == "insert") {
            val pinnedOnlineOnly = resolvePinnedFromOnline(intent, pinnedNamed)
            val toInsert = mergeUnique(pinnedNamed, pinnedOnlineOnly).take(1)
            if (toInsert.isEmpty()) {
                DiagnosticsLogStore.record(
                    area = "ai_agent",
                    event = "insert_not_found",
                    fields = mapOf(
                        "localPinned" to pinnedNamed.size,
                        "trackHintCount" to (intent.hardTracks.size + intent.textTracks.size),
                    ),
                )
                return PlayOutcome.Empty("这首我没找到，换个名字？")
            }
            runCatching {
                recommendationLog.logTracks(toInsert, RecommendationLog.Source.Pet)
            }
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "insert_ready",
                fields = mapOf(
                    "trackCount" to toInsert.size,
                    "neteaseId" to toInsert.firstOrNull()?.neteaseId,
                ),
            )
            return PlayOutcome.Insert(toInsert.first())
        }
        val recentPlay = runCatching { behaviorLog.recentPlay() }.getOrDefault(BehaviorLog.RecentPlay(emptySet(), emptySet()))
        val recentRec = runCatching { recommendationLog.recentContext() }.getOrDefault(RecommendationLog.RecentContext(emptySet(), emptySet()))

        // 保留 ranked 记录(含 bucket / sourceScores) → composeOpening 拿 bucket
        // 标记探索曲, rankRefill 也能复用同份 candidate 形态。
        var localRankedRecord: List<CandidateRanker.Ranked> = emptyList()
        val localRanked: List<NativeTrack> = if (localTracks.isNotEmpty()) {
            // 用户原话向量化（OpenAI 才支持；DeepSeek/MiMo 失败时返回 null，自动 fallback lexical）
            val queryVector = if (embeddingStore.count() > 0) {
                runCatching { embeddingIndexer.embedQuery(intent.queryText) }.getOrNull()
            } else null

            val candidates = CandidateRecall.recall(
                intent = intent,
                library = localTracks,
                featuresStore = featuresStore,
                semanticStore = semanticStore,
                indexer = indexer,
                tasteProfile = tasteProfile,
                behaviorEvents = behaviorEvents,
                behaviorPreference = behaviorPreference,
                currentTrack = currentTrack,
                limit = 200,
                queryVector = queryVector,
                embeddingStore = if (queryVector != null) embeddingStore else null,
            )
            val ranked = CandidateRanker.rank(
                candidates = candidates,
                intent = intent,
                options = CandidateRanker.Options(
                    topN = desired * 3,
                    recentPlay = recentPlay,
                    recentRecommendation = recentRec,
                    behaviorPreference = behaviorPreference,
                ),
            )
            localRankedRecord = ranked
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "local_ranked",
                fields = mapOf(
                    "libraryCount" to localTracks.size,
                    "candidateCount" to candidates.size,
                    "rankedCount" to ranked.size,
                    "queryVector" to (queryVector != null),
                    "queueSelection" to "intent_locked_window_smooth",
                ),
            )
            val asTracks = ranked.map { it.candidate.track }
            // 全池 artist 多样性:用户没点名艺人时,把 ranker 输出按"同 artist ≤ cap" 切
            // primary + overflow(round-robin),保证 reservoir 也是混合的,续杯不会扎堆。
            val diversified = if (requestedArtistKeys.isNotEmpty()) asTracks
                else diversifyByArtist(asTracks)
            val promptScopedTracks = prioritizeRequestedArtists(
                tracks = diversified,
                artistKeys = requestedArtistKeys,
                desired = desired * 3,
            )
            // PetAgent 只负责语义排序：用户意图 / 口味 / 探索强度决定“该听什么”。
            // 具体接歌顺序交给 PlayerViewModel 的智能接歌逻辑，避免两套 smooth 打架。
            promptScopedTracks.take(desired)
        } else emptyList()

        // 2) 命名歌曲补全 —— 用户点过名但本地没有的，去网易兜一首
        val pinnedOnline = resolvePinnedFromOnline(intent, pinnedNamed)
        val pinnedAll = mergeUnique(pinnedNamed, pinnedOnline)
        if (namedTrackTitles(intent).isNotEmpty() && pinnedAll.isEmpty()) {
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "named_track_not_found",
                fields = mapOf(
                    "trackHintCount" to namedTrackTitles(intent).size,
                    "artistHintCount" to requestedArtistKeys.size,
                ),
            )
            return PlayOutcome.Empty("这首我没抓准，别硬放错。换个歌名或歌手？")
        }

        // 3) 本地不够 → netease 搜索补
        val localArtistCount = localRanked.count { matchesRequestedArtist(it, requestedArtistKeys) }
        val needsArtistBackfill = requestedArtistKeys.isNotEmpty() && localArtistCount < desired
        val recallMerged = if (localRanked.size >= 6 && !needsArtistBackfill) {
            localRanked
        } else {
            val queries = onlineSeedQueries
            val onlineRaw = neteaseSearch(
                queries = queries,
                limitPerQuery = if (requestedArtistKeys.isNotEmpty()) 12 else 4,
                dropTitleKeys = dropTitleKeys,
            )
            val onlineRanked = rankOnlineBackfill(
                tracks = onlineRaw,
                intent = intent,
                tasteProfile = tasteProfile,
                recentPlay = recentPlay,
                recentRec = recentRec,
                behaviorPreference = behaviorPreference,
                target = desired,
            )
            val online = prioritizeRequestedArtists(
                tracks = onlineRanked,
                artistKeys = requestedArtistKeys,
                desired = desired,
            )
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "online_backfill",
                fields = mapOf(
                    "queryCount" to queries.size,
                    "localRankedCount" to localRanked.size,
                    "localArtistCount" to localArtistCount,
                    "onlineCount" to online.size,
                    "promptArtistCount" to requestedArtistKeys.size,
                    "behaviorSeedCount" to behaviorSearchQueries.size,
                ),
            )
            // 合并：本地优先 + 在线兜底
            val seenKeys = HashSet<String>()
            val merged = ArrayList<NativeTrack>(localRanked.size + online.size)
            for (t in localRanked) {
                if (seenKeys.add(TrackDedupe.songKey(t))) merged.add(t)
            }
            for (t in online) {
                if (seenKeys.add(TrackDedupe.songKey(t))) merged.add(t)
            }
            merged
        }
        val promptScoped = prioritizeRequestedArtists(
            tracks = recallMerged,
            artistKeys = requestedArtistKeys,
            desired = desired,
        )
        val artistBalanced = balanceHeartModeArtists(
            tracks = promptScoped,
            tasteProfile = tasteProfile,
            intent = intent,
            desired = desired,
        )
        val freshScoped = rotateRecentlyRecommended(
            tracks = artistBalanced,
            recentRec = recentRec,
            desired = desired,
        )

        // 4) 把 pinned 钉到队首，然后是 recall 结果（去重）
        // capSameTitle：把"不同艺人撞同名"收敛到每个标题 1 首，避免《Pop Night》那种一列同名歌。
        // 只作用在非 pinned 部分 —— 用户点名要的歌（pinned）哪怕同名也照常钉，不误伤。
        val pinnedKeys = pinnedAll.mapTo(HashSet()) { TrackDedupe.songKey(it) }
        val final = if (pinnedAll.isEmpty()) {
            TrackDedupe.capSameTitle(freshScoped).take(desired)
        } else {
            val rest = TrackDedupe.capSameTitle(
                freshScoped.filter { TrackDedupe.songKey(it) !in pinnedKeys },
            )
            (pinnedAll + rest).take(desired)
        }

        if (final.isEmpty()) {
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "queue_empty",
                fields = mapOf(
                    "localRankedCount" to localRanked.size,
                    "pinnedCount" to pinnedAll.size,
                ),
            )
            return PlayOutcome.Empty("这次库里真没贴的，换个说法？")
        }

        // 4) 拆 initialBatch + reservoir
        //    - initialTarget 动态: min(12, max(6, desired/2)) —— 小曲库不会被抽干
        //    - composeOpening 把开场重排成 [head 熟悉 + mid 主推 + tail 探索],
        //      让"第一耳朵"命中已知偏好, 再渐进过渡到探索曲
        val initialTarget = minOf(12, maxOf(6, desired / 2))
        val rankedByKey = localRankedRecord.associateBy { TrackDedupe.songKey(it.candidate.track) }
        val opening = composeOpening(
            final = final,
            rankedByKey = rankedByKey,
            intent = intent,
            recentPlay = recentPlay,
            recentRec = recentRec,
            pinnedKeys = pinnedKeys,
            target = initialTarget,
        )
        // 心动模式"发现新歌手"：discovery(没点名歌手/歌)时，初始队列约一半换成"在线同风格、
        // 但不在你 top 歌手里"的新面孔 —— 本地召回只能翻你自己的库(全是熟人)，这步才真发现。
        val isDiscovery = requestedArtistKeys.isEmpty() && namedTrackTitles(intent).isEmpty()
        val discoveryFresh = if (isDiscovery) {
            fetchDiscoveryFreshTracks(
                styleQueries = styleSearchQueries,
                intent = intent,
                tasteProfile = tasteProfile,
                recentPlay = recentPlay,
                recentRec = recentRec,
                behaviorPreference = behaviorPreference,
                excludeKeys = final.mapTo(HashSet()) { TrackDedupe.songKey(it) },
                dropTitleKeys = dropTitleKeys,
                target = initialTarget,
            )
        } else emptyList()
        val initialBatch = if (discoveryFresh.isEmpty()) opening.tracks
            else blendFreshIntoOpening(opening.tracks, discoveryFresh, initialTarget)
        val initialKeys = initialBatch.mapTo(HashSet()) { TrackDedupe.songKey(it) }
        // 3) 记 recommendation log（防 24h 内重复）—— 只记 initialBatch；reservoir
        //    在 makeContinuousSource 真被 drain 时才记，避免双重计数让 recencyPen 翻倍
        runCatching {
            recommendationLog.logTracks(initialBatch, RecommendationLog.Source.Pet)
        }
        // reservoir：没用上的在线新面孔排前面(让续杯也保持发现感) + 本地剩余
        val leftoverFresh = discoveryFresh.filterNot { TrackDedupe.songKey(it) in initialKeys }
        val localReservoir = final.filterNot { TrackDedupe.songKey(it) in initialKeys }
        val reservoir = mergeUnique(leftoverFresh, localReservoir)
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "queue_ready",
            fields = mapOf(
                "initialCount" to initialBatch.size,
                "reservoirCount" to reservoir.size,
                "discoveryFreshCount" to discoveryFresh.size,
                "isDiscovery" to isDiscovery,
                "pinnedCount" to pinnedAll.size,
                "promptArtistCount" to requestedArtistKeys.size,
                "finalArtistCount" to final.count { matchesRequestedArtist(it, requestedArtistKeys) },
                "behaviorPreferenceConfidence" to behaviorPreference.confidence,
                "desired" to desired,
                "heartAnchorCount" to opening.anchorCount,
                "heartAdjacentCount" to opening.adjacentCount,
                "heartSurpriseCount" to opening.surpriseCount,
                "heartPinnedCount" to opening.pinnedCount,
                "aiPlanMainCount" to intent.aiMainStyles.size,
                "aiPlanAdjacentCount" to intent.aiAdjacentStyles.size,
                "aiPlanSurpriseCount" to intent.aiSurpriseStyles.size,
                "aiExploration" to intent.aiExploration,
            ),
        )

        return PlayOutcome.Replace(
            initialBatch = initialBatch,
            continuous = makeContinuousSource(
                reservoir = reservoir,
                seedQueries = onlineSeedQueries,
                requestedArtistKeys = requestedArtistKeys,
                intent = intent,
                recentPlay = recentPlay,
                recentRec = recentRec,
                behaviorPreference = behaviorPreference,
                dropTitleKeys = dropTitleKeys,
            ),
        )
    }

    // ---------- 命名歌曲解析 ----------

    /**
     * 把全/半角空格、各种连接符、标点都擦掉。"七百年后"、"七 百 年 後"、"七百年-后"
     * 都归一到同一个 key。注意保留中文字符本身的差异（後 ≠ 后）—— 这是用户书写差异
     * 不是本质相同。
     */
    private fun normalizeForMatch(s: String): String =
        s.lowercase().replace(Regex("[\\s'\"`·・\\-－—_,，。.、!?！？()（）\\[\\]【】《》<>&/]+"), "")

    /**
     * 同名多版本曲目的"挑选偏好":值越小越优先。
     * 用户说"放浮夸"默认想要的是录音棚版,不是 Live / Remix / 伴奏 / 翻唱。
     * 之前 firstOrNull 选库里第一个,完全看入库顺序常常选错版本。
     *
     * 计权策略:
     *   - 基础:标题长度(短的通常没附加标注 = studio 主版本)
     *   - 加权:括号附加里包含"Live/Remix/Acoustic/Demo/Cover/伴奏/Remaster"等关键字时往后排
     */
    private fun trackVariantWeight(title: String): Int {
        val lower = title.lowercase()
        var w = title.length
        if ("live" in lower || "现场" in lower || "演唱会" in lower) w += 1000
        if ("伴奏" in lower || "instrumental" in lower || "karaoke" in lower) w += 1000
        if ("cover" in lower || "翻唱" in lower) w += 800
        if ("remix" in lower || "混音" in lower) w += 700
        if ("acoustic" in lower || "unplugged" in lower) w += 600
        if ("demo" in lower) w += 500
        if ("remaster" in lower || "重制" in lower) w += 300
        return w
    }

    /**
     * 在本地库里找用户点名的歌。匹配优先级：
     *   1) 标题归一化完全相等 + 任一艺人也命中（陈奕迅 + 七百年后 → 钉那一首）
     *   2) 标题归一化完全相等（同名翻唱也认）
     *   3) 标题归一化包含/被包含（"七百年后" ↔ "七百年后 (Live)"）
     * 返回结果按用户在话里的顺序，去重。
     */
    private fun resolvePinnedTracks(intent: PetIntent, library: List<NativeTrack>): List<NativeTrack> {
        if (library.isEmpty()) return emptyList()
        val titles = namedTrackTitles(intent)
        if (titles.isEmpty()) return emptyList()
        val artistKeys = (intent.hardArtists + intent.textArtists)
            .map(::normalizeForMatch).filter { it.isNotEmpty() }.toSet()

        val out = LinkedHashMap<String, NativeTrack>()
        for (rawTitle in titles.distinct()) {
            val titleKey = normalizeForMatch(rawTitle)
            if (titleKey.isEmpty()) continue

            val pick = if (artistKeys.isNotEmpty()) {
                // 用户给了 artist 约束（"陈奕迅 浮夸"）→ 必须艺人命中。
                // 两档都要艺人对得上，否则**宁可不 pin** 也不能挂错人的歌
                //   1) 标题精确 + 艺人命中
                //   2) 艺人命中 + 标题包含/被包含（"七百年后" ↔ "七百年后 (Live)"）
                // 同档内多版本时按 trackVariantWeight 排,优先 studio 版,避免选 Live/Remix。
                // artist 命中判定走 matchesRequestedArtist —— 同理修「Earth, Wind & Fire」这类
                // 用 & / , 连起来的乐队名被自己当分隔符拆碎、配不上整串 key 的问题（本地库同样会踩）。
                fun artistOk(t: NativeTrack) = matchesRequestedArtist(t, artistKeys)
                val exactWithArtist = library.filter { t ->
                    normalizeForMatch(t.title) == titleKey && artistOk(t)
                }
                val partialWithArtist = library.filter { t ->
                    val tk = normalizeForMatch(t.title)
                    tk.isNotEmpty() && tk != titleKey && (titleKey in tk || tk in titleKey) && artistOk(t)
                }
                (exactWithArtist + partialWithArtist).minByOrNull { trackVariantWeight(it.title) }
            } else {
                // 没艺人约束（"放浮夸"）→ 标题精确优先；包含次之；都不挑剔艺人。
                // 同档内多版本(浮夸 / 浮夸 (Live Ver.) / 浮夸 (伴奏)) 按 trackVariantWeight
                // 排:无括号附加的 studio 版本最优,Live/Remix/伴奏 等都往后排。
                // 之前直接 firstOrNull 选库里第一个匹配,完全看入库顺序,常常选到 Live 版。
                val exact = library.filter { normalizeForMatch(it.title) == titleKey }
                val partial = library.filter {
                    val tk = normalizeForMatch(it.title)
                    tk.isNotEmpty() && tk != titleKey && (titleKey in tk || tk in titleKey)
                }
                (exact + partial).minByOrNull { trackVariantWeight(it.title) }
            } ?: continue

            val key = TrackDedupe.songKey(pick)
            if (key !in out) out[key] = pick
        }
        return out.values.toList()
    }

    /**
     * 用户点名的歌本地没有 → 网易搜「title artist」补一首。
     * 仅给"已点名但 pin 不上"的标题做补，避免把用户根本没点的歌灌进队首。
     */
    private suspend fun resolvePinnedFromOnline(
        intent: PetIntent,
        alreadyPinned: List<NativeTrack>,
    ): List<NativeTrack> {
        val titles = namedTrackTitles(intent)
        if (titles.isEmpty()) return emptyList()

        val missing = titles.filter { title ->
            alreadyPinned.none { pinned -> titleMatchesRequest(pinned.title, title) }
        }
        if (missing.isEmpty()) return emptyList()

        // 多 artist 时只用第一个不靠谱（"陈奕迅 浮夸 + 周杰伦 七里香" → "七里香 陈奕迅"）。
        // 唯一 artist 时安全；多于 1 个时不带 artist hint，让搜索引擎自己定位
        val artistHints = (intent.hardArtists + intent.textArtists)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val singleArtistHint = if (artistHints.size == 1) artistHints[0] else ""

        return coroutineScope {
            val deferred = missing.map { title ->
                async {
                    val q = if (singleArtistHint.isNotEmpty()) "$title $singleArtistHint" else title
                    runCatching { repository.searchTracks(q, limit = 3) }.getOrDefault(emptyList())
                        .filter { hit ->
                            // 只接受标题 fuzzy 命中的，避免搜索引擎乱推不相干的
                            if (!titleMatchesRequest(hit.title, title)) return@filter false
                            // 单 artist hint 时也校验 artist 命中（防搜索引擎乱推同名）。
                            // 必须用 matchesRequestedArtist：它把 hit 的 artist 按 / & , 、feat 拆段
                            // 逐段比，并兜底「整串 artist 含 key」。否则像「Earth, Wind & Fire」这种
                            // 用 & 和 , 连起来的乐队名，会被当成分隔符拆碎，永远配不上整串 hint，
                            // 网易明明有 September 也被这层过滤掉 —— 这就是「搜不到」的真凶。
                            if (singleArtistHint.isNotEmpty()) {
                                matchesRequestedArtist(hit, setOf(normalizeForMatch(singleArtistHint)))
                            } else true
                        }
                        .minByOrNull { hit -> trackVariantWeight(hit.title) }
                }
            }
            deferred.mapNotNull { it.await() }
        }
    }

    private fun namedTrackTitles(intent: PetIntent): List<String> {
        return (intent.hardTracks + intent.textTracks)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { normalizeForMatch(it) }
    }

    private fun titleMatchesRequest(actualTitle: String, requestedTitle: String): Boolean {
        val actual = normalizeForMatch(actualTitle)
        val requested = normalizeForMatch(requestedTitle)
        return actual.isNotEmpty() &&
            requested.isNotEmpty() &&
            (actual == requested || requested in actual || actual in requested)
    }

    /** 按 songKey 去重保序合并 */
    private fun mergeUnique(a: List<NativeTrack>, b: List<NativeTrack>): List<NativeTrack> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val seen = HashSet<String>()
        val out = ArrayList<NativeTrack>(a.size + b.size)
        for (t in a) if (seen.add(TrackDedupe.songKey(t))) out.add(t)
        for (t in b) if (seen.add(TrackDedupe.songKey(t))) out.add(t)
        return out
    }

    /**
     * 用一组查询词跑并行 netease 搜索，去重后返回。
     * [dropTitleKeys]：标题归一后命中其中之一的曲目直接丢弃 —— 防通用种子词字面撞名（见 buildPlayOutcome）。
     */
    private suspend fun neteaseSearch(
        queries: List<String>,
        limitPerQuery: Int = 4,
        dropTitleKeys: Set<String> = emptySet(),
    ): List<NativeTrack> {
        if (queries.isEmpty()) return emptyList()
        return coroutineScope {
            val deferred = queries.map { q ->
                async { runCatching { repository.searchTracks(q, limit = limitPerQuery) }.getOrDefault(emptyList()) }
            }
            val seen = LinkedHashMap<Long, NativeTrack>()
            deferred.forEach { d ->
                d.await().forEach { t ->
                    val id = t.neteaseId ?: return@forEach
                    if (dropTitleKeys.isNotEmpty() && TrackDedupe.normalizeTitle(t.title) in dropTitleKeys) return@forEach
                    if (!seen.containsKey(id)) seen[id] = t
                }
            }
            seen.values.toList()
        }
    }

    /**
     * 续杯 source —— 长会话不断流的关键链路。
     *
     * 三件事:
     * 1) 优先 drain reservoir(本地排序好的池子);每次 drain 前根据本会话最近 10 分钟
     *    的 Skipped/ManualCut 反馈把 pool 里同 artist 的歌往后挤,让"刚跳过的歌
     *    还会被推"的尴尬立刻收敛。
     * 2) reservoir 空了 → 在线 seedQueries 搜 raw → 过 CandidateRanker(对齐
     *    initialBatch 的口味打分) → 接到队尾,听感分布跟首批一致。
     * 3) refill 不再是一次性 latch —— 旧版 refillTried=true 之后任何 fetchMore 都
     *    返回空,叠加上层 PlayerViewModel 看到空就 continuousSource=null 的设计
     *    会让长会话(超过 reservoir 容量 + 8)彻底断流。改成"每 6 首允许再 refill",
     *    既挡住"空 refill 反复打"又保证有歌就有得续。
     */
    private fun makeContinuousSource(
        reservoir: List<NativeTrack>,
        seedQueries: List<String>,
        requestedArtistKeys: Set<String>,
        intent: PetIntent,
        recentPlay: BehaviorLog.RecentPlay,
        recentRec: RecommendationLog.RecentContext,
        behaviorPreference: BehaviorPreferenceSnapshot,
        dropTitleKeys: Set<String> = emptySet(),
    ): ContinuousQueueSource {
        val pool = reservoir.toMutableList()
        val consumed = mutableSetOf<Long>()
        // 同一首歌的 Live/Karaoke/Acoustic 多版本归一到同 songKey —— 续杯不能再灌进重复版本
        val consumedSongKeys = HashSet<String>()
        var drainedTotal = 0
        // 上次成功 refill 之后, 至少再 drain 这么多首才允许再 refill,
        // 把"空 refill 反复触发"压成"每 6 首才允许尝试一次"。
        var lastRefillDrainMark = -1000
        val refillCooldown = 6

        return ContinuousQueueSource { excludeIds ->
            // 1) 本会话 skip 反馈: 命中"最近 10 分钟被跳的 artist"的歌往后挤
            val freshSkipArtists = recentSessionSkipArtists(windowMs = 10L * 60_000L)
            if (freshSkipArtists.isNotEmpty() && pool.size > 1) {
                val resorted = pool.sortedBy { t ->
                    if (firstArtistKey(t.artist) in freshSkipArtists) 1 else 0
                }
                pool.clear()
                pool.addAll(resorted)
            }

            // 2) drain
            val drained = mutableListOf<NativeTrack>()
            var index = 0
            while (index < pool.size && drained.size < 8) {
                val t = pool[index]
                if (requestedArtistKeys.isNotEmpty() && !matchesRequestedArtist(t, requestedArtistKeys)) {
                    index += 1
                    continue
                }
                val id = t.neteaseId
                pool.removeAt(index)
                if (id == null || id in excludeIds || id in consumed) continue
                val k = TrackDedupe.songKey(t)
                if (k in consumedSongKeys) continue
                consumed.add(id)
                consumedSongKeys.add(k)
                drained.add(t)
            }
            if (drained.isNotEmpty()) {
                drainedTotal += drained.size
                runCatching { recommendationLog.logTracks(drained, RecommendationLog.Source.Pet) }
                DiagnosticsLogStore.record(
                    area = "ai_agent",
                    event = "continuous_drain",
                    fields = mapOf(
                        "count" to drained.size,
                        "poolLeft" to pool.size,
                        "drainedTotal" to drainedTotal,
                        "skipArtistCount" to freshSkipArtists.size,
                        "promptArtistCount" to requestedArtistKeys.size,
                    ),
                )
                return@ContinuousQueueSource drained
            }

            // 3) reservoir 空了 → refill(cooldown 控制, 不再是一次性 latch)
            val cooldownPassed = drainedTotal - lastRefillDrainMark >= refillCooldown
            if (!cooldownPassed || seedQueries.isEmpty()) {
                DiagnosticsLogStore.record(
                    area = "ai_agent",
                    event = if (seedQueries.isEmpty()) "continuous_empty" else "continuous_cooldown",
                    fields = mapOf(
                        "seedQueryCount" to seedQueries.size,
                        "drainedTotal" to drainedTotal,
                        "lastRefillDrainMark" to lastRefillDrainMark,
                    ),
                )
                return@ContinuousQueueSource emptyList()
            }

            val collected = LinkedHashMap<String, NativeTrack>()
            // 续杯同样要按标题封顶：通用种子词（如画像拼出的 "pop night"）在网易云按歌名字面
            // 命中，会搜回一堆不同艺人的同名歌。每个标题只收 1 首，避免续杯灌出一串同名。
            val collectedTitles = HashSet<String>()
            for (q in seedQueries) {
                val hits = runCatching {
                    repository.searchTracks(q, limit = if (requestedArtistKeys.isNotEmpty()) 16 else 8)
                }.getOrDefault(emptyList())
                for (t in hits) {
                    if (requestedArtistKeys.isNotEmpty() && !matchesRequestedArtist(t, requestedArtistKeys)) continue
                    val id = t.neteaseId ?: continue
                    if (id in excludeIds || id in consumed) continue
                    val k = TrackDedupe.songKey(t)
                    if (k in consumedSongKeys || k in collected) continue
                    val titleKey = TrackDedupe.normalizeTitle(t.title)
                    if (titleKey.isNotEmpty() && titleKey in dropTitleKeys) continue  // 字面撞名：标题==通用种子词
                    if (titleKey.isNotEmpty() && !collectedTitles.add(titleKey)) continue
                    collected[k] = t
                    if (collected.size >= 18) break  // 多收点给 ranker 选
                }
                if (collected.size >= 18) break
            }
            if (collected.isEmpty()) {
                lastRefillDrainMark = drainedTotal  // 入 cooldown, 防反复空 refill
                DiagnosticsLogStore.record(
                    area = "ai_agent",
                    event = "continuous_refill_empty",
                    fields = mapOf("queryCount" to seedQueries.size),
                )
                return@ContinuousQueueSource emptyList()
            }

            // refill 也走 ranker —— raw search hits 直接接到队尾会有"前 12 是精选,
            // 后面是搜索原始结果"的听感断崖。让同一份 intent / recentPlay / recentRec
            // 打分, 质量分布跟 initialBatch 对齐。
            val refill = rankRefill(
                tracks = collected.values.toList(),
                intent = intent,
                recentPlay = recentPlay,
                recentRec = recentRec,
                behaviorPreference = behaviorPreference,
                target = 8,
            )
            if (refill.isEmpty()) {
                lastRefillDrainMark = drainedTotal
                DiagnosticsLogStore.record(
                    area = "ai_agent",
                    event = "continuous_refill_filtered",
                    fields = mapOf("collected" to collected.size),
                )
                return@ContinuousQueueSource emptyList()
            }
            for (t in refill) {
                val id = t.neteaseId ?: continue
                consumed.add(id)
                consumedSongKeys.add(TrackDedupe.songKey(t))
            }
            drainedTotal += refill.size
            lastRefillDrainMark = drainedTotal
            runCatching { recommendationLog.logTracks(refill, RecommendationLog.Source.Pet) }
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "continuous_refill",
                fields = mapOf(
                    "queryCount" to seedQueries.size,
                    "collected" to collected.size,
                    "returned" to refill.size,
                    "drainedTotal" to drainedTotal,
                ),
            )
            refill
        }
    }

    private enum class HeartSlot { Anchor, Adjacent, Surprise }

    private data class HeartOpening(
        val tracks: List<NativeTrack>,
        val anchorCount: Int,
        val adjacentCount: Int,
        val surpriseCount: Int,
        val pinnedCount: Int,
    )

    private data class HeartCandidate(
        val track: NativeTrack,
        val slot: HeartSlot,
        val pinned: Boolean,
    )

    /**
     * AI 编排心动开场：AI 负责把本轮意图拆成 main / adjacent / surprise 语义，
     * 这里负责把候选稳定落成队列：第一耳朵稳，随后相邻探索，少量惊喜放后段。
     */
    private fun composeOpening(
        final: List<NativeTrack>,
        rankedByKey: Map<String, CandidateRanker.Ranked>,
        intent: PetIntent,
        recentPlay: BehaviorLog.RecentPlay,
        recentRec: RecommendationLog.RecentContext,
        pinnedKeys: Set<String>,
        target: Int,
    ): HeartOpening {
        if (final.isEmpty() || target <= 0) return HeartOpening(emptyList(), 0, 0, 0, 0)
        val cappedTarget = minOf(target, final.size)
        val classified = final.map { track ->
            HeartCandidate(
                track = track,
                slot = heartSlotFor(track, rankedByKey, intent, recentPlay, recentRec, pinnedKeys),
                pinned = TrackDedupe.songKey(track) in pinnedKeys,
            )
        }

        val tightlyScoped = namedTrackTitles(intent).isNotEmpty() || requestedArtistKeys(intent).isNotEmpty()
        val baseSurpriseCap = when (intent.aiExploration) {
            "safe" -> if (cappedTarget >= 10) 1 else 0
            "adventurous" -> maxOf(1, cappedTarget / 4)
            else -> if (cappedTarget >= 8) maxOf(1, cappedTarget / 6) else 0
        }
        val surpriseCap = if (tightlyScoped) 0 else minOf(baseSurpriseCap, maxOf(0, cappedTarget - 4))
        val adjacentCap = when (intent.aiExploration) {
            "safe" -> maxOf(1, cappedTarget / 5)
            "adventurous" -> maxOf(2, cappedTarget / 3)
            else -> maxOf(1, cappedTarget / 4)
        }.coerceAtMost(maxOf(0, cappedTarget - surpriseCap - 1))
        val anchorTarget = maxOf(1, cappedTarget - adjacentCap - surpriseCap)

        val taken = HashSet<String>()
        val out = ArrayList<HeartCandidate>(cappedTarget)

        fun addFrom(items: List<HeartCandidate>, limit: Int = Int.MAX_VALUE) {
            if (limit <= 0) return
            var added = 0
            for (item in items) {
                if (out.size >= cappedTarget || added >= limit) break
                val key = TrackDedupe.songKey(item.track)
                if (!taken.add(key)) continue
                out.add(item)
                added += 1
            }
        }

        val pinned = classified.filter { it.pinned }
        val anchors = classified.filter { !it.pinned && it.slot == HeartSlot.Anchor }
        val adjacent = classified.filter { !it.pinned && it.slot == HeartSlot.Adjacent }
        val surprise = classified.filter { !it.pinned && it.slot == HeartSlot.Surprise }

        addFrom(pinned)
        addFrom(anchors, maxOf(0, anchorTarget - out.count { it.slot == HeartSlot.Anchor || it.pinned }))
        addFrom(adjacent, adjacentCap)
        addFrom(anchors)
        // 惊喜只放后段：前几首先建立“这就是懂我”的信任感。
        if (out.size >= minOf(4, cappedTarget)) addFrom(surprise, surpriseCap)
        addFrom(classified.filter { it.slot != HeartSlot.Surprise })
        addFrom(surprise)

        val tracks = out.take(cappedTarget).map { it.track }
        return HeartOpening(
            tracks = tracks,
            anchorCount = out.count { it.slot == HeartSlot.Anchor },
            adjacentCount = out.count { it.slot == HeartSlot.Adjacent },
            surpriseCount = out.count { it.slot == HeartSlot.Surprise },
            pinnedCount = out.count { it.pinned },
        )
    }

    private fun heartSlotFor(
        track: NativeTrack,
        rankedByKey: Map<String, CandidateRanker.Ranked>,
        intent: PetIntent,
        recentPlay: BehaviorLog.RecentPlay,
        recentRec: RecommendationLog.RecentContext,
        pinnedKeys: Set<String>,
    ): HeartSlot {
        val key = TrackDedupe.songKey(track)
        if (key in pinnedKeys) return HeartSlot.Anchor
        val id = track.neteaseId
        val ranked = rankedByKey[key]
        val profile = openingSemanticProfile(track, ranked)
        val knownRecent = id != null && id in recentPlay.last7dTrackIds
        val recentlyRecommended = id != null && id in recentRec.last7dTrackIds

        if (profile != null && intent.aiSurpriseStyles.isNotEmpty() &&
            styleFitsProfile(intent.aiSurpriseStyles, profile) && !knownRecent
        ) {
            return HeartSlot.Surprise
        }
        if (profile != null && intent.aiAdjacentStyles.isNotEmpty() &&
            styleFitsProfile(intent.aiAdjacentStyles, profile)
        ) {
            return HeartSlot.Adjacent
        }
        if (knownRecent || ranked?.bucket == 0) return HeartSlot.Anchor
        if (profile != null && styleFitsProfile(mainAnchorStyles(intent), profile)) return HeartSlot.Anchor
        if (!recentlyRecommended && ranked?.bucket == 3) return HeartSlot.Surprise
        return if (ranked?.bucket == 1 || ranked?.bucket == 2) HeartSlot.Adjacent else HeartSlot.Anchor
    }

    private fun openingSemanticProfile(
        track: NativeTrack,
        ranked: CandidateRanker.Ranked?,
    ): TrackSemanticProfile? {
        ranked?.candidate?.semanticProfile?.let { return it }
        return runCatching {
            semanticStore.get(track.id) ?: indexer.buildRuleBasedProfile(track, featuresStore.get(track.id))
        }.getOrNull()
    }

    private fun styleFitsProfile(styles: List<String>, profile: TrackSemanticProfile): Boolean {
        if (styles.isEmpty()) return false
        val bag = profile.genres + profile.subGenres + profile.styleAnchors +
            profile.moods + profile.scenes + profile.textures + profile.energyWords +
            profile.tempoFeel + profile.vocalDelivery +
            listOf(profile.language.key, profile.region.key, profile.vocalType.key)
        return matchesAnyStyle(styles, bag)
    }

    private fun mainAnchorStyles(intent: PetIntent): List<String> =
        intent.aiMainStyles + intent.hardGenres + intent.musicHintsGenres + intent.refStyles +
            intent.softMoods + intent.musicHintsMoods + intent.softScenes + intent.musicHintsScenes

    /**
     * 拿最近 windowMs 内 Skipped / ManualCut 事件的 artist 集合(normalize 过的 key)。
     */
    private suspend fun recentSessionSkipArtists(windowMs: Long): Set<String> {
        val now = System.currentTimeMillis()
        val events = runCatching { behaviorLog.readAll() }.getOrDefault(emptyList())
        val out = HashSet<String>()
        for (e in events) {
            if (e.type != BehaviorType.Skipped && e.type != BehaviorType.ManualCut) continue
            if (now - e.tsMs > windowMs) continue
            val key = firstArtistKey(e.artist)
            if (key.isNotEmpty()) out.add(key)
        }
        return out
    }

    /** "Artist1 / Artist2 & Artist3" → normalize 后的第一位 artist key, 空串表无名 */
    private fun firstArtistKey(raw: String): String {
        val first = raw.split('/', '&', ',', '、').firstOrNull().orEmpty().trim()
        return normalizeForMatch(first)
    }

    /**
     * 续杯 refill 走 ranker —— 跟 initialBatch 同一套口味打分,
     * 让"前 12 精选 / 后续粗放"的听感断崖消失。
     *
     * raw 列表里有的歌没 features / 没 semanticProfile, ranker 自己会 fallback
     * 到 0 分不会崩;真没分的(全 0)在 finalScore<=0 自然被过滤掉。
     */
    private fun rankRefill(
        tracks: List<NativeTrack>,
        intent: PetIntent,
        recentPlay: BehaviorLog.RecentPlay,
        recentRec: RecommendationLog.RecentContext,
        behaviorPreference: BehaviorPreferenceSnapshot,
        target: Int,
    ): List<NativeTrack> {
        if (tracks.isEmpty()) return emptyList()
        val candidates = tracks.map { t ->
            val features = runCatching { featuresStore.get(t.id) }.getOrNull()
            val semanticProfile = runCatching {
                semanticStore.get(t.id) ?: indexer.buildRuleBasedProfile(t, features)
            }.getOrNull()
            CandidateRecall.Candidate(
                track = t,
                features = features,
                semanticProfile = semanticProfile,
                sources = mutableListOf(CandidateRecall.Source.Text),
                sourceScores = mutableMapOf(CandidateRecall.Source.Text to 0.45),
            )
        }
        val ranked = CandidateRanker.rank(
            candidates = candidates,
            intent = intent,
            options = CandidateRanker.Options(
                topN = target * 2,
                recentPlay = recentPlay,
                recentRecommendation = recentRec,
                behaviorPreference = behaviorPreference,
            ),
        )
        return ranked.map { it.candidate.track }.take(target)
    }

    private fun rankOnlineBackfill(
        tracks: List<NativeTrack>,
        intent: PetIntent,
        tasteProfile: TasteProfile?,
        recentPlay: BehaviorLog.RecentPlay,
        recentRec: RecommendationLog.RecentContext,
        behaviorPreference: BehaviorPreferenceSnapshot,
        target: Int,
    ): List<NativeTrack> {
        if (tracks.isEmpty()) return emptyList()
        val candidates = tracks.map { track ->
            val features = runCatching { featuresStore.get(track.id) }.getOrNull()
            val semanticProfile = runCatching {
                semanticStore.get(track.id) ?: indexer.buildRuleBasedProfile(track, features)
            }.getOrNull()
            val sourceScores = mutableMapOf<CandidateRecall.Source, Double>(
                CandidateRecall.Source.Text to 0.36,
            )
            if (semanticProfile != null) {
                val intentFit = semanticIntentFit(intent, semanticProfile)
                if (intentFit > 0.0) sourceScores[CandidateRecall.Source.Tag] = intentFit
                val tasteFit = tasteStyleFit(tasteProfile, semanticProfile)
                if (tasteFit > 0.0) sourceScores[CandidateRecall.Source.ProfileTags] = tasteFit
            }
            CandidateRecall.Candidate(
                track = track,
                features = features,
                semanticProfile = semanticProfile,
                sources = sourceScores.keys.toMutableList(),
                sourceScores = sourceScores,
            )
        }
        val ranked = CandidateRanker.rank(
            candidates = candidates,
            intent = intent,
            options = CandidateRanker.Options(
                topN = target * 2,
                recentPlay = recentPlay,
                recentRecommendation = recentRec,
                behaviorPreference = behaviorPreference,
            ),
        )
        return ranked.map { it.candidate.track }.take(target)
    }

    private fun semanticIntentFit(intent: PetIntent, profile: TrackSemanticProfile): Double {
        var score = 0.0
        score += overlapScore(
            intent.hardGenres + intent.musicHintsGenres + intent.refStyles,
            profile.genres + profile.subGenres + profile.styleAnchors,
            0.70,
        )
        val styleBag = profile.genres + profile.subGenres + profile.styleAnchors +
            profile.moods + profile.scenes + profile.textures
        score += overlapScore(intent.aiMainStyles, styleBag, 0.56)
        score += overlapScore(intent.aiAdjacentStyles, styleBag, 0.32)
        score += overlapScore(intent.aiSurpriseStyles, styleBag, 0.16)
        score += overlapScore(
            intent.softMoods + intent.musicHintsMoods,
            profile.moods + profile.textures,
            0.34,
        )
        score += overlapScore(
            intent.softScenes + intent.musicHintsScenes,
            profile.scenes,
            0.28,
        )
        score += overlapScore(intent.hardLanguages, listOf(profile.language.key), 0.45)
        score += overlapScore(intent.hardRegions, listOf(profile.region.key), 0.35)
        score += overlapScore(intent.hardVocalTypes, listOf(profile.vocalType.key), 0.25)
        return score.coerceAtMost(1.6)
    }

    private fun tasteStyleFit(tasteProfile: TasteProfile?, profile: TrackSemanticProfile): Double {
        if (tasteProfile == null) return 0.0
        var score = 0.0
        val trackStyles = profile.genres + profile.subGenres + profile.styleAnchors
        for (genre in tasteProfile.genres.take(8)) {
            if (matchesAnyStyle(listOf(genre.tag), trackStyles)) {
                score += 0.46 * genre.weight
                break
            }
        }
        if (matchesAnyStyle(tasteProfile.moods.take(6), profile.moods + profile.textures)) score += 0.30
        if (matchesAnyStyle(tasteProfile.culturalContext.take(4), listOf(profile.region.key, profile.language.key))) score += 0.24
        val decade = profile.decade
        if (!decade.isNullOrBlank() && matchesAnyStyle(tasteProfile.eras.map { it.label }, listOf(decade))) score += 0.18
        return score.coerceAtMost(1.4)
    }

    private fun overlapScore(needles: List<String>, haystack: List<String>, weight: Double): Double {
        if (needles.isEmpty() || haystack.isEmpty()) return 0.0
        val hits = needles.count { needle -> matchesAnyStyle(listOf(needle), haystack) }
        return (hits.toDouble() / needles.size) * weight
    }

    private fun matchesAnyStyle(needles: List<String>, haystack: List<String>): Boolean {
        val hs = haystack.map(::styleKey).filter { it.isNotBlank() }
        if (hs.isEmpty()) return false
        return needles.map(::styleKey).filter { it.isNotBlank() }.any { n ->
            hs.any { h -> h == n || (h.length >= 3 && n.length >= 3 && (h in n || n in h)) }
        }
    }

    private fun styleKey(value: String): String =
        value.lowercase()
            .replace("节奏布鲁斯", "r&b")
            .replace("rnb", "r&b")
            .replace(Regex("[\\s'\"`·・\\-－—_,，。.、!?！？/]+"), "")

    private fun requestedArtistKeys(intent: PetIntent): Set<String> =
        requestedArtistNames(intent).map(::normalizeForMatch).filter { it.isNotEmpty() }.toSet()

    private fun requestedArtistNames(intent: PetIntent): List<String> {
        val out = LinkedHashMap<String, String>()
        for (raw in intent.hardArtists + intent.textArtists) {
            val name = raw.trim()
            val key = normalizeForMatch(name)
            if (name.isNotEmpty() && key.isNotEmpty() && key !in out) out[key] = name
        }
        return out.values.toList()
    }

    private fun artistFirstSearchQueries(intent: PetIntent, rawText: String): List<String> {
        val artistNames = requestedArtistNames(intent)
        if (artistNames.isEmpty()) return intent.toSearchQueries(rawText)
        val queries = LinkedHashSet<String>()
        artistNames.take(3).forEach { artist ->
            queries.add(artist)
            (intent.hardTracks + intent.textTracks).take(2).forEach { track ->
                if (track.isNotBlank()) queries.add("$artist ${track.trim()}")
            }
            (intent.musicHintsGenres + intent.hardGenres).take(2).forEach { genre ->
                if (genre.isNotBlank()) queries.add("$artist ${genre.trim()}")
            }
            (intent.softMoods + intent.musicHintsMoods).take(2).forEach { mood ->
                if (mood.isNotBlank()) queries.add("$artist ${mood.trim()}")
            }
        }
        intent.toSearchQueries(rawText).forEach { query ->
            if (query.isBlank()) return@forEach
            queries.add(query.trim())
            artistNames.take(2).forEach { artist ->
                if (!query.contains(artist, ignoreCase = true)) queries.add("$artist ${query.trim()}")
            }
        }
        return queries.toList().take(10)
    }

    /**
     * 心动模式式搜索种子：先贴合用户画像和当下诉求，再轻量展开相邻风格。
     * 不把“没听过”当目标；新歌只是风格匹配后的自然结果。
     */
    private fun heartModeStyleSearchQueries(
        intent: PetIntent,
        tasteProfile: TasteProfile?,
        behaviorPreference: BehaviorPreferenceSnapshot,
    ): List<String> {
        val out = LinkedHashSet<String>()
        val intentGenres = (intent.hardGenres + intent.musicHintsGenres + intent.refStyles)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(4)
        val aiMainStyles = intent.aiMainStyles
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(5)
        val aiAdjacentStyles = intent.aiAdjacentStyles
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(4)
        val aiSurpriseStyles = intent.aiSurpriseStyles
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)
        val intentMoods = (intent.softMoods + intent.musicHintsMoods + intent.softTextures)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(4)

        val tasteGenres = tasteProfile?.genres
            ?.sortedByDescending { it.weight }
            ?.map { it.tag.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.take(5)
            .orEmpty()
        val tasteMoods = tasteProfile?.moods
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.take(4)
            .orEmpty()
        val cultures = tasteProfile?.culturalContext
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.take(3)
            .orEmpty()
        val eras = tasteProfile?.eras
            ?.sortedByDescending { it.weight }
            ?.map { it.label.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.take(2)
            .orEmpty()

        val genres = when {
            intentGenres.isNotEmpty() -> intentGenres
            aiMainStyles.isNotEmpty() -> aiMainStyles
            else -> tasteGenres
        }
        val moods = if (intentMoods.isNotEmpty()) intentMoods else tasteMoods

        fun add(vararg parts: String?) {
            val q = parts.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
                .joinToString(" ")
                .trim()
            if (q.length >= 2) out.add(q)
        }

        for (main in aiMainStyles.take(3)) {
            add(cultures.firstOrNull(), main)
            add(main, moods.firstOrNull())
        }
        for (adjacent in aiAdjacentStyles.take(3)) {
            add(adjacent, moods.firstOrNull())
            add(cultures.firstOrNull(), adjacent)
        }
        if (intent.aiExploration != "safe") {
            for (surprise in aiSurpriseStyles.take(2)) {
                add(surprise, moods.firstOrNull() ?: genres.firstOrNull())
            }
        }
        for (culture in cultures.take(2)) {
            for (genre in genres.take(2)) add(culture, genre)
            if (genres.isEmpty()) add(culture)
        }
        for (genre in genres.take(4)) {
            add(genre, moods.firstOrNull())
            add(genre)
        }
        for (mood in moods.take(3)) {
            add(genres.firstOrNull(), mood)
            add(mood)
        }
        for (era in eras) add(era, genres.firstOrNull() ?: moods.firstOrNull())
        behaviorPreference.onlineSeeds(maxItems = 3).forEach { add(it) }

        return out.toList().take(8)
    }

    private fun mergeSearchQueries(
        primary: List<String>,
        preference: List<String>,
        maxItems: Int,
    ): List<String> {
        val out = LinkedHashSet<String>()
        (primary + preference).forEach { query ->
            val q = query.trim()
            if (q.isNotBlank()) out.add(q)
        }
        return out.toList().take(maxItems)
    }

    private fun hasSpecificSearchIntent(intent: PetIntent): Boolean =
        intent.hardTracks.isNotEmpty() ||
            intent.textTracks.isNotEmpty() ||
            intent.hardGenres.isNotEmpty() ||
            intent.hardLanguages.isNotEmpty() ||
            intent.hardRegions.isNotEmpty() ||
            intent.softMoods.isNotEmpty() ||
            intent.softScenes.isNotEmpty() ||
            intent.musicHintsMoods.isNotEmpty() ||
            intent.musicHintsGenres.isNotEmpty() ||
            intent.orderStyle != "smooth"

    private fun normalizeUserIntent(intent: PetIntent): PetIntent =
        enrichEnergyDirection(enrichTextConstraints(intent))

    /**
     * 用户原话兜底 —— 模型漏填 intent 字段时，把最常见的"硬要求"补回结构化字段。
     * 只做低风险、多字词规则：语言/地区/风格/男女声/排除词/场景；艺人只补显式维护的别名。
     */
    private fun enrichTextConstraints(intent: PetIntent): PetIntent {
        // 这里只信用户原话。模型已经填进来的 musicHints/recommendationPlan 会在后续打分中使用，
        // 但不能反过来被当作"用户说死了"，否则模型误判会被本地硬约束放大。
        val q = intent.queryText.lowercase()
        if (q.isBlank()) return intent

        var hardLanguages = intent.hardLanguages
        var hardRegions = intent.hardRegions
        var hardGenres = intent.hardGenres
        var hardSubGenres = intent.hardSubGenres
        var hardVocalTypes = intent.hardVocalTypes
        var excludeLanguages = intent.excludeLanguages
        var excludeGenres = intent.excludeGenres
        var excludeVocalTypes = intent.excludeVocalTypes
        var excludeTags = intent.excludeTags
        var avoidWords = intent.avoidWords
        var softMoods = intent.softMoods
        var softScenes = intent.softScenes
        var softTextures = intent.softTextures
        var softEnergy = intent.softEnergy
        var softTempoFeel = intent.softTempoFeel
        var musicHintsGenres = intent.musicHintsGenres
        var musicHintsMoods = intent.musicHintsMoods
        var musicHintsScenes = intent.musicHintsScenes
        var musicHintsEnergy = intent.musicHintsEnergy
        var musicHintsTransitionStyle = intent.musicHintsTransitionStyle
        var aiAvoidStyles = intent.aiAvoidStyles
        var orderStyle = intent.orderStyle
        var hardArtists = intent.hardArtists
        var textArtists = intent.textArtists

        fun has(pattern: String): Boolean = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(q)
        fun add(list: List<String>, value: String): List<String> = uniqueStrings(list + value)
        fun addAll(list: List<String>, values: List<String>): List<String> = uniqueStrings(list + values)

        val aliasArtists = artistAliasesIn(q)
        if (aliasArtists.isNotEmpty()) {
            hardArtists = addAll(hardArtists, aliasArtists)
            textArtists = addAll(textArtists, aliasArtists)
        }

        val noMandarin = has("不要国语|别放国语|不要中文|别放中文|不要华语|别放华语")
        val noCantonese = has("不要粤语|别放粤语")
        val noJapanese = has("不要日语|别放日语|不要日本|别放日本")
        val noKorean = has("不要韩语|别放韩语|不要韩国|别放韩国")
        val noEnglish = has("不要英文|别放英文|不要英语|别放英语|不要欧美|别放欧美")
        val noHipHop = has("不要说唱|别放说唱|不要\\s*rap|别\\s*rap|不要hip.?hop|别放hip.?hop")
        val noElectronic = has("不要电子|别放电子|不要edm|别放edm|不要蹦迪|别蹦迪")
        val noRock = has("不要摇滚|别放摇滚|不要rock|别放rock")
        val noFemale = has("不要女声|别放女声|不要女歌手|别放女歌手")
        val noMale = has("不要男声|别放男声|不要男歌手|别放男歌手")

        if (noMandarin) excludeLanguages = add(excludeLanguages, "mandarin")
        if (noCantonese) excludeLanguages = add(excludeLanguages, "cantonese")
        if (noJapanese) excludeLanguages = add(excludeLanguages, "japanese")
        if (noKorean) excludeLanguages = add(excludeLanguages, "korean")
        if (noEnglish) excludeLanguages = add(excludeLanguages, "english")
        if (noHipHop) excludeGenres = add(excludeGenres, "hip-hop")
        if (noElectronic) excludeGenres = add(excludeGenres, "electronic")
        if (noRock) excludeGenres = add(excludeGenres, "rock")
        if (noFemale) excludeVocalTypes = add(excludeVocalTypes, "female")
        if (noMale) excludeVocalTypes = add(excludeVocalTypes, "male")

        if (!noEnglish && has("欧美|western|euro|america|american|英美")) {
            hardRegions = add(hardRegions, "western")
            hardLanguages = add(hardLanguages, "english")
        }
        if (!noEnglish && has("英文|英语|english")) hardLanguages = add(hardLanguages, "english")
        if (!noMandarin && has("国语|中文|华语|mandarin")) {
            hardLanguages = add(hardLanguages, "mandarin")
            hardRegions = add(hardRegions, "chinese")
        }
        if (!noCantonese && has("粤语|cantonese")) {
            hardLanguages = add(hardLanguages, "cantonese")
            hardRegions = add(hardRegions, "chinese")
        }
        if (!noJapanese && has("日语|日本|japanese|日系")) {
            hardLanguages = add(hardLanguages, "japanese")
            hardRegions = add(hardRegions, "japanese_korean")
        }
        if (!noKorean && has("韩语|韩国|korean|韩系")) {
            hardLanguages = add(hardLanguages, "korean")
            hardRegions = add(hardRegions, "japanese_korean")
        }

        fun addGenre(genre: String, vararg aliases: String) {
            hardGenres = add(hardGenres, genre)
            musicHintsGenres = addAll(musicHintsGenres, listOf(genre) + aliases)
        }

        if (has("r&b|rnb|节奏布鲁斯")) addGenre("r&b", "smooth", "late-night r&b")
        if (has("neo soul|neosoul|灵魂乐|soul")) addGenre("soul", "neo soul")
        if (!noHipHop && has("hip.?hop|说唱|\\brap\\b")) addGenre("hip-hop")
        if (has("jazz|爵士|bossa|swing")) addGenre("jazz")
        if (has("folk|民谣|acoustic|不插电|unplugged")) addGenre("folk", "acoustic")
        if (!noElectronic && has("electronic|电子|edm|techno|house|dance")) addGenre("electronic")
        if (!noRock && has("rock|摇滚|britpop")) addGenre("rock")
        if (has("city\\s*pop|城市流行")) {
            hardSubGenres = add(hardSubGenres, "city pop")
            musicHintsGenres = add(musicHintsGenres, "city pop")
        }

        if (!noFemale && has("女声|female|女歌手")) hardVocalTypes = add(hardVocalTypes, "female")
        if (!noMale && has("男声|male|男歌手")) hardVocalTypes = add(hardVocalTypes, "male")
        if (has("纯音乐|器乐|instrumental")) {
            hardVocalTypes = add(hardVocalTypes, "instrumental")
        }

        if (has("不要太吵|别太吵|不吵|安静|别炸|不要炸")) {
            excludeTags = addAll(excludeTags, listOf("noisy", "aggressive", "party"))
            aiAvoidStyles = addAll(aiAvoidStyles, listOf("noisy", "aggressive", "party"))
            if (softEnergy == "any") softEnergy = "mid_low"
            if (musicHintsEnergy == "any") musicHintsEnergy = "low"
        }
        if (has("土嗨|抖音神曲|口水")) {
            excludeTags = addAll(excludeTags, listOf("tiktok", "commercial", "cheesy"))
            aiAvoidStyles = addAll(aiAvoidStyles, listOf("tiktok", "commercial", "cheesy"))
            avoidWords = addAll(avoidWords, listOf("土嗨", "抖音神曲", "口水"))
        }
        if (has("苦情|太丧|太emo|太\\s*emo")) {
            excludeTags = add(excludeTags, "overly sad")
            aiAvoidStyles = add(aiAvoidStyles, "overly sad")
        }

        if (has("写代码|coding|code|编程|学习|工作|专注|办公")) {
            softScenes = addAll(softScenes, listOf("coding", "focus"))
            musicHintsScenes = addAll(musicHintsScenes, listOf("coding", "focus"))
            softMoods = addAll(softMoods, listOf("focused", "calm"))
            musicHintsMoods = addAll(musicHintsMoods, listOf("focused", "calm"))
        }
        if (has("晚上|夜里|深夜|夜晚|night")) {
            softScenes = add(softScenes, "night")
            musicHintsScenes = add(musicHintsScenes, "night")
            musicHintsMoods = add(musicHintsMoods, "night")
        }
        if (has("开车|drive|driving|通勤|地铁|路上")) {
            softScenes = add(softScenes, "driving")
            musicHintsScenes = add(musicHintsScenes, "driving")
        }
        if (has("下雨|雨天|雨夜|rain")) {
            softScenes = addAll(softScenes, listOf("rainy day", "night"))
            musicHintsScenes = addAll(musicHintsScenes, listOf("rainy day", "night"))
            softMoods = addAll(softMoods, listOf("melancholic", "calm", "atmospheric"))
            musicHintsMoods = addAll(musicHintsMoods, listOf("melancholic", "calm", "atmospheric"))
        }
        if (has("慢歌|慢一点|慢节奏|slow")) softTempoFeel = "slow"
        if (has("快歌|快一点|快节奏|fast")) softTempoFeel = "fast"
        if (has("越来越带感|递进|越放越")) orderStyle = "energy_up"
        if (has("睡觉|睡眠|入睡|助眠|想睡|sleep")) {
            orderStyle = "sleep"
            softEnergy = "low"
            musicHintsEnergy = "low"
            softScenes = add(softScenes, "sleep")
            musicHintsScenes = add(musicHintsScenes, "sleep")
        }
        if (has("派对|蹦迪|酒吧|party")) {
            musicHintsTransitionStyle = "party"
            if (softEnergy == "any") softEnergy = "high"
            if (musicHintsEnergy == "any") musicHintsEnergy = "high"
        }

        val enriched = intent.copy(
            hardArtists = uniqueStrings(hardArtists),
            textArtists = uniqueStrings(textArtists),
            hardLanguages = uniqueStrings(hardLanguages),
            hardRegions = uniqueStrings(hardRegions),
            hardGenres = uniqueStrings(hardGenres),
            hardSubGenres = uniqueStrings(hardSubGenres),
            hardVocalTypes = uniqueStrings(hardVocalTypes),
            excludeLanguages = uniqueStrings(excludeLanguages),
            excludeGenres = uniqueStrings(excludeGenres),
            excludeVocalTypes = uniqueStrings(excludeVocalTypes),
            excludeTags = uniqueStrings(excludeTags),
            avoidWords = uniqueStrings(avoidWords),
            softMoods = uniqueStrings(softMoods),
            softScenes = uniqueStrings(softScenes),
            softTextures = uniqueStrings(softTextures),
            softEnergy = softEnergy,
            softTempoFeel = softTempoFeel,
            musicHintsMoods = uniqueStrings(musicHintsMoods),
            musicHintsScenes = uniqueStrings(musicHintsScenes),
            musicHintsGenres = uniqueStrings(musicHintsGenres),
            musicHintsEnergy = musicHintsEnergy,
            musicHintsTransitionStyle = musicHintsTransitionStyle,
            aiAvoidStyles = uniqueStrings(aiAvoidStyles),
            orderStyle = orderStyle,
        )

        if (enriched != intent) {
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "intent_text_enriched",
                fields = mapOf(
                    "queryText" to enriched.queryText.take(60),
                    "hardArtists" to enriched.hardArtists.joinToString(",").take(80),
                    "hardLanguages" to enriched.hardLanguages.joinToString(",").take(80),
                    "hardGenres" to enriched.hardGenres.joinToString(",").take(80),
                    "excludeLanguages" to enriched.excludeLanguages.joinToString(",").take(80),
                    "excludeGenres" to enriched.excludeGenres.joinToString(",").take(80),
                    "softScenes" to enriched.softScenes.joinToString(",").take(80),
                    "energy" to "${enriched.softEnergy}/${enriched.musicHintsEnergy}",
                ),
            )
        }
        return enriched
    }

    private fun artistAliasesIn(text: String): List<String> {
        val compact = normalizeCommandText(text)
        if (compact.isBlank()) return emptyList()
        return ARTIST_ALIASES.mapNotNull { alias ->
            alias.canonical.takeIf { alias.aliases.any { key -> compact.contains(normalizeCommandText(key)) } }
        }.distinct()
    }

    private fun uniqueStrings(values: List<String>): List<String> {
        val out = ArrayList<String>(values.size)
        val seen = HashSet<String>()
        for (value in values) {
            val cleaned = value.trim()
            if (cleaned.isEmpty()) continue
            val key = cleaned.lowercase()
            if (seen.add(key)) out.add(cleaned)
        }
        return out
    }

    /**
     * 能量方向兜底 —— 用户说"安静/轻柔/深夜""嗨/燃/动感"时，模型经常只把方向写进
     * recommendationPlan.mainStyles，没填 softPreferences.energy / musicHints.energy。
     * 那样 rankWeights 落进口味主导分支(taste 0.40)、recallByAudio 能量过滤也不触发，于是
     * "来点安静的"被最常听的吵歌碾过去。这里从原话 + 已有风格词里推断能量方向，只在模型没
     * 明确给能量时补上 softEnergy/musicHintsEnergy + 一个 mood，让能量诉求真正进到召回和排序。
     * 没有能量线索、或同时命中冷/热（罕见）就不动；模型已明确给了能量也不覆盖。
     */
    private fun enrichEnergyDirection(intent: PetIntent): PetIntent {
        if (intent.softEnergy != "any" && intent.musicHintsEnergy != "any") return intent
        val bag = (listOf(intent.queryText) + intent.aiMainStyles + intent.aiAdjacentStyles +
            intent.softMoods + intent.musicHintsMoods + intent.softScenes + intent.softTextures)
            .joinToString(" ").lowercase()
        if (bag.isBlank()) return intent
        val calm = CALM_ENERGY_CUES.any { it in bag }
        val hype = HYPE_ENERGY_CUES.any { it in bag }
        val inferred = when {
            calm && !hype -> "low"
            hype && !calm -> "high"
            else -> null
        } ?: return intent
        val softEnergy = if (intent.softEnergy != "any") intent.softEnergy else inferred
        val musicEnergy = if (intent.musicHintsEnergy != "any") intent.musicHintsEnergy else inferred
        val moods = if (intent.softMoods.isNotEmpty()) intent.softMoods
            else if (inferred == "low") listOf("calm", "mellow") else listOf("energetic", "upbeat")
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "intent_energy_enriched",
            fields = mapOf(
                "inferred" to inferred,
                "queryText" to intent.queryText.take(40),
                "hadSoftEnergy" to (intent.softEnergy != "any"),
                "hadMusicEnergy" to (intent.musicHintsEnergy != "any"),
            ),
        )
        return intent.copy(softEnergy = softEnergy, musicHintsEnergy = musicEnergy, softMoods = moods)
    }

    /**
     * 心动模式"发现新歌手"：用风格种子在线搜，过滤掉你的 top 歌手、已在库的、最近推过的，
     * 留下"同风格但你没怎么听过的人"，再用 ranker 按意图/口味打分挑贴合的。本地召回只能翻你
     * 自己的库(全是熟人)，这步是唯一能引入新面孔的来源。没风格种子/搜不到就回空，优雅退回纯本地。
     */
    private suspend fun fetchDiscoveryFreshTracks(
        styleQueries: List<String>,
        intent: PetIntent,
        tasteProfile: TasteProfile?,
        recentPlay: BehaviorLog.RecentPlay,
        recentRec: RecommendationLog.RecentContext,
        behaviorPreference: BehaviorPreferenceSnapshot,
        excludeKeys: Set<String>,
        dropTitleKeys: Set<String>,
        target: Int,
    ): List<NativeTrack> {
        if (styleQueries.isEmpty() || target <= 0) return emptyList()
        val topArtistKeys = tasteProfile?.topArtists
            ?.sortedByDescending { it.affinity }
            ?.take(20)
            ?.map { normalizeForMatch(it.name) }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        val raw = neteaseSearch(
            queries = styleQueries.take(6),
            limitPerQuery = 6,
            dropTitleKeys = dropTitleKeys,
        )
        val filtered = raw.filter { t ->
            if (TrackDedupe.songKey(t) in excludeKeys) return@filter false
            val id = t.neteaseId
            if (id != null && (id in recentRec.last7dTrackIds || id in recentPlay.last7dTrackIds)) return@filter false
            // 关键：避开你的 top 歌手 —— 发现的是"新的人"，不是把熟人换个壳
            val artistKey = firstArtistKey(t.artist)
            artistKey.isEmpty() || artistKey !in topArtistKeys
        }
        if (filtered.isEmpty()) {
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "discovery_fresh_empty",
                fields = mapOf("rawCount" to raw.size, "queryCount" to styleQueries.size),
            )
            return emptyList()
        }
        // 同风格相关性打分，避免在线搜回一堆字面命中但不贴的；新面孔之间也按 artist 多样化
        val ranked = rankOnlineBackfill(
            tracks = filtered,
            intent = intent,
            tasteProfile = tasteProfile,
            recentPlay = recentPlay,
            recentRec = recentRec,
            behaviorPreference = behaviorPreference,
            target = target * 2,
        )
        val out = diversifyByArtist(ranked).take(target)
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "discovery_fresh",
            fields = mapOf(
                "rawCount" to raw.size,
                "filteredCount" to filtered.size,
                "returned" to out.size,
                "topArtistFilter" to topArtistKeys.size,
                "queryCount" to styleQueries.size,
            ),
        )
        return out
    }

    /**
     * 把"在线新面孔"和"本地贴合"按约 1:1 交错成初始队列。首位仍留给本地熟悉感(第一耳朵稳)，
     * 之后 fresh / local 交替，直到 target；去重保序。
     */
    private fun blendFreshIntoOpening(
        local: List<NativeTrack>,
        fresh: List<NativeTrack>,
        target: Int,
    ): List<NativeTrack> {
        if (fresh.isEmpty()) return local.take(target)
        val out = ArrayList<NativeTrack>(target)
        val seen = HashSet<String>()
        val localQ = ArrayDeque(local)
        val freshQ = ArrayDeque(fresh)
        fun push(t: NativeTrack) {
            if (seen.add(TrackDedupe.songKey(t))) out.add(t)
        }
        // 第一首给本地熟悉感
        if (localQ.isNotEmpty()) push(localQ.removeFirst())
        var takeFresh = true
        while (out.size < target && (localQ.isNotEmpty() || freshQ.isNotEmpty())) {
            val q = if (takeFresh && freshQ.isNotEmpty()) freshQ
                else if (localQ.isNotEmpty()) localQ
                else freshQ
            if (q.isEmpty()) break
            push(q.removeFirst())
            takeFresh = !takeFresh
        }
        return out.take(target)
    }

    private fun prioritizeRequestedArtists(
        tracks: List<NativeTrack>,
        artistKeys: Set<String>,
        desired: Int,
    ): List<NativeTrack> {
        if (artistKeys.isEmpty() || tracks.isEmpty()) return tracks
        val matching = ArrayList<NativeTrack>(tracks.size)
        val fallback = ArrayList<NativeTrack>(tracks.size)
        for (track in tracks) {
            if (matchesRequestedArtist(track, artistKeys)) matching.add(track) else fallback.add(track)
        }
        if (matching.isEmpty()) return tracks
        val enoughForQueue = matching.size >= minOf(6, desired)
        return if (enoughForQueue) {
            matching.take(desired)
        } else {
            (matching + fallback).take(desired)
        }
    }

    /**
     * 心动模式不是 top artist 电台。自由推荐时，画像艺人只保留少量锚点，
     * 其余空间让给同风格/同情绪/相邻探索的歌。
     */
    private fun balanceHeartModeArtists(
        tracks: List<NativeTrack>,
        tasteProfile: TasteProfile?,
        intent: PetIntent,
        desired: Int,
    ): List<NativeTrack> {
        if (tracks.size <= 1 || tasteProfile == null) return tracks
        val hasExplicitArtist = requestedArtistKeys(intent).isNotEmpty()
        val hasExplicitTrack = namedTrackTitles(intent).isNotEmpty()
        if (hasExplicitArtist || hasExplicitTrack) return tracks

        val topArtistKeys = tasteProfile.topArtists
            .sortedByDescending { it.affinity }
            .take(10)
            .map { normalizeForMatch(it.name) }
            .filter { it.isNotEmpty() }
            .toSet()
        if (topArtistKeys.isEmpty()) return tracks

        val topCap = maxOf(1, desired / 10)
        val normalCap = maxOf(2, desired / 8)
        val counts = HashMap<String, Int>()
        val primary = ArrayList<NativeTrack>(tracks.size)
        val overflow = LinkedHashMap<String, MutableList<NativeTrack>>()

        for (track in tracks) {
            val artistKey = firstArtistKey(track.artist)
            if (artistKey.isEmpty()) {
                primary.add(track)
                continue
            }
            val cap = if (artistKey in topArtistKeys) topCap else normalCap
            val count = counts[artistKey] ?: 0
            if (count >= cap) {
                overflow.getOrPut(artistKey) { mutableListOf() }.add(track)
            } else {
                counts[artistKey] = count + 1
                primary.add(track)
            }
        }
        if (overflow.isEmpty()) return tracks

        val overflowOut = ArrayList<NativeTrack>()
        val buckets = overflow.values.map { it.toMutableList() }
        while (buckets.any { it.isNotEmpty() }) {
            for (bucket in buckets) {
                if (bucket.isNotEmpty()) overflowOut.add(bucket.removeAt(0))
            }
        }
        return primary + overflowOut
    }

    /**
     * 同一类需求重复触发时，口味方向保持，但最近已经由宠物排过的歌往后放。
     * 不是硬过滤：曲库太小或约束太紧时仍允许回落，避免“为了新鲜而乱播”。
     */
    private fun rotateRecentlyRecommended(
        tracks: List<NativeTrack>,
        recentRec: RecommendationLog.RecentContext,
        desired: Int,
    ): List<NativeTrack> {
        if (tracks.size <= 1) return tracks
        val fresh = ArrayList<NativeTrack>(tracks.size)
        val recent24h = ArrayList<NativeTrack>()
        val recent7d = ArrayList<NativeTrack>()
        for (track in tracks) {
            val id = track.neteaseId
            when {
                id != null && id in recentRec.last24hTrackIds -> recent24h.add(track)
                id != null && id in recentRec.last7dTrackIds -> recent7d.add(track)
                else -> fresh.add(track)
            }
        }
        val enoughFresh = fresh.size >= minOf(desired, 6)
        return if (enoughFresh) fresh + recent7d + recent24h else tracks
    }

    private fun matchesRequestedArtist(track: NativeTrack, artistKeys: Set<String>): Boolean {
        if (artistKeys.isEmpty()) return false
        val artistParts = track.artist
            .split("/", "&", ",", "、", " feat.", " feat ", "feat.", "feat ", " featuring ", " ft.", " ft ")
            .map(::normalizeForMatch)
            .filter { it.isNotEmpty() }
        val wholeArtist = normalizeForMatch(track.artist)
        return artistKeys.any { key ->
            artistParts.any { part -> artistKeyMatches(part, key) } ||
                (key.length >= 3 && wholeArtist.contains(key))
        }
    }

    private fun artistKeyMatches(part: String, requested: String): Boolean {
        if (part == requested) return true
        if (requested.length < 3 || part.length < 3) return false
        return requested in part || part in requested
    }

    /**
     * 全池 artist 多样性:同 artist 上限按池大小动态算 max(2, len/8),溢出按 artist
     * 轮转(round-robin)拼到尾部。镜像 Web 端 diversifyTrackInfos —— 保证 reservoir
     * 里也是不同艺人混合,续杯不再 8 首全是 Taylor。
     */
    private fun diversifyByArtist(items: List<NativeTrack>): List<NativeTrack> {
        if (items.isEmpty()) return items
        val seenSongs = HashSet<String>()
        val artistCounts = HashMap<String, Int>()
        val cap = maxOf(2, items.size / 8)
        val primary = ArrayList<NativeTrack>(items.size)
        val overflow = LinkedHashMap<String, MutableList<NativeTrack>>()
        for (t in items) {
            val sk = TrackDedupe.songKey(t)
            if (!seenSongs.add(sk)) continue
            val ak = normalizeForMatch(t.artist.split('/', '&', ',', '、').firstOrNull().orEmpty())
            val count = artistCounts[ak] ?: 0
            if (ak.isNotEmpty() && count >= cap) {
                overflow.getOrPut(ak) { mutableListOf() }.add(t)
                continue
            }
            if (ak.isNotEmpty()) artistCounts[ak] = count + 1
            primary.add(t)
        }
        // round-robin overflow
        val overflowOut = ArrayList<NativeTrack>()
        val buckets = overflow.values.map { it.toMutableList() }
        while (buckets.any { it.isNotEmpty() }) {
            for (b in buckets) {
                if (b.isNotEmpty()) overflowOut.add(b.removeAt(0))
            }
        }
        return primary + overflowOut
    }

    private fun errorFields(error: Throwable): Map<String, Any?> =
        mapOf(
            "errorType" to error::class.java.simpleName,
            "errorMessage" to error.message.orEmpty().take(180),
        )

    // ---------- USER message ----------

    private suspend fun buildUserMessage(
        userText: String,
        currentTrack: NativeTrack?,
        userFacts: String,
        behaviorPreference: BehaviorPreferenceSnapshot,
        musicReferences: List<PetMemory.MusicReference>,
    ): String {
        val ctxLines = mutableListOf<String>()
        val weather = runCatching { Weather.get() }.getOrNull()
        ctxLines.add("时段：${AppContext.describe(weather)}")
        AppContext.memoryDigest(userFacts)?.let { ctxLines.add("TA 的人:$it") }
        behaviorPreference.brief(maxItems = 4)?.let { ctxLines.add("近期口味变化：$it") }
        formatMusicReferences(musicReferences)?.let { ctxLines.add(it) }
        
        currentTrack?.let { track ->
            val feat = featuresStore.get(track.id)
            val featText = if (feat != null) {
                val bpmText = if (feat.bpm != null && feat.bpmConfidence > 0.3) "BPM ${feat.bpm.toInt()}" else null
                val energyText = when {
                    feat.introEnergy > 0.7 -> "高能量/动感"
                    feat.introEnergy < 0.3 -> "低能量/安静"
                    else -> "中能量"
                }
                val dynamicText = when {
                    feat.dynamicRangeDb < 6.0 -> "主流商业压缩"
                    feat.dynamicRangeDb > 12.0 -> "高动态/原声/古典"
                    else -> "中等动态"
                }
                val toneText = when {
                    feat.spectralCentroidHz > 3000.0 -> "明亮高亢"
                    feat.spectralCentroidHz < 1500.0 -> "温和/低沉暗淡"
                    else -> "中性音色"
                }
                val list = listOfNotNull(bpmText, energyText, dynamicText, toneText)
                if (list.isNotEmpty()) " (声学特征: ${list.joinToString(", ")})" else ""
            } else ""
            ctxLines.add("在播：${track.title} — ${track.artist}$featText")
        }

        // 不再把"最近播放历史/最近被切"常驻塞进每一轮 prompt —— 那会逼着宠物每次回答都
        // 复读"你之前听过 X"。回忆类问题(我刚才听啥/上一首)让模型按需调 get_play_history；
        // 跳过的负反馈已在召回排序层(behaviorEvents → CandidateRanker)生效，不必嘴上再念。
        val prefix = if (ctxLines.isNotEmpty()) ctxLines.joinToString("\n") + "\n\n" else ""
        return prefix + "用户：$userText"
    }

    private fun formatMusicReferences(references: List<PetMemory.MusicReference>): String? {
        val recent = references
            .filter { it.title.isNotBlank() }
            .takeLast(5)
        if (recent.isEmpty()) return null
        return "可执行音乐指代（用户说 那首/它/刚才说的 时优先承接）：\n" +
            recent.joinToString("\n") { ref ->
                val artist = ref.artist.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                val reason = ref.reason.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                "- ${ref.title}$artist$reason"
            }
    }

    // ---------- 解析 ----------

    /**
     * 从 play_queue / play_similar 工具的 `intent` 参数对象构造 PetIntent。
     * 字段结构沿用旧 JSON 协议（hardConstraints / textHints / musicHints /
     * softPreferences / references / emotionalGoal / queueIntent），读取逻辑与早先
     * 的 parseIntent 一致；差别只是数据来自工具参数对象而非裸 JSON 文本。
     */
    private fun parseIntentFromArgs(obj: JSONObject, fallbackQuery: String): PetIntent {
        val hard = obj.optJSONObject("hardConstraints")
        val text = obj.optJSONObject("textHints")
        val music = obj.optJSONObject("musicHints")
        val soft = obj.optJSONObject("softPreferences")
        val refs = obj.optJSONObject("references")
        val plan = obj.optJSONObject("recommendationPlan")
            ?: obj.optJSONObject("heartModePlan")
        val emo = obj.optJSONObject("emotionalGoal")
        val queue = obj.optJSONObject("queueIntent")
        return PetIntent(
            queryText = obj.optString("queryText").ifBlank { fallbackQuery },
            hardArtists = jsonStringList(hard, "artists"),
            hardTracks = jsonStringList(hard, "tracks"),
            hardGenres = jsonStringList(hard, "genres"),
            hardSubGenres = jsonStringList(hard, "subGenres"),
            hardLanguages = jsonStringList(hard, "languages"),
            hardRegions = jsonStringList(hard, "regions"),
            hardVocalTypes = jsonStringList(hard, "vocalTypes"),
            excludeLanguages = jsonStringList(hard, "excludeLanguages"),
            excludeRegions = jsonStringList(hard, "excludeRegions"),
            excludeGenres = jsonStringList(hard, "excludeGenres"),
            excludeVocalTypes = jsonStringList(hard, "excludeVocalTypes"),
            excludeTags = jsonStringList(hard, "excludeTags"),
            excludeArtists = jsonStringList(hard, "excludeArtists"),
            avoidWords = jsonStringList(music, "avoid"),
            textArtists = jsonStringList(text, "artists"),
            textTracks = jsonStringList(text, "tracks"),
            textAlbums = jsonStringList(text, "albums"),
            softMoods = jsonStringList(soft, "moods"),
            softScenes = jsonStringList(soft, "scenes"),
            softTextures = jsonStringList(soft, "textures"),
            softQualityWords = jsonStringList(soft, "qualityWords"),
            softEnergy = soft?.optString("energy")?.takeIf { it.isNotBlank() } ?: "any",
            softTempoFeel = soft?.optString("tempoFeel")?.takeIf { it.isNotBlank() } ?: "any",
            musicHintsMoods = jsonStringList(music, "moods"),
            musicHintsScenes = jsonStringList(music, "scenes"),
            musicHintsGenres = jsonStringList(music, "genres"),
            musicHintsEnergy = music?.optString("energy")?.takeIf { it.isNotBlank() } ?: "any",
            musicHintsTransitionStyle = music?.optString("transitionStyle")?.takeIf { it.isNotBlank() } ?: "soft",
            refStyles = jsonStringList(refs, "styles"),
            refArtists = jsonStringList(refs, "artists"),
            aiMainStyles = jsonStringList(plan, "mainStyles"),
            aiAdjacentStyles = jsonStringList(plan, "adjacentStyles"),
            aiSurpriseStyles = jsonStringList(plan, "surpriseStyles"),
            aiAvoidStyles = jsonStringList(plan, "avoidStyles"),
            aiExploration = plan?.optString("exploration")?.lowercase()?.takeIf { it.isNotBlank() } ?: "balanced",
            emotionalDirection = emo?.optString("direction")?.takeIf { it.isNotBlank() },
            orderStyle = queue?.optString("orderStyle")?.takeIf { it.isNotBlank() } ?: "smooth",
            desiredCount = obj.optInt("desiredCount", 30).coerceIn(1, 60),
        )
    }

    private fun jsonStringList(obj: JSONObject?, key: String): List<String> {
        if (obj == null) return emptyList()
        val arr = obj.optJSONArray(key) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    companion object {
        private data class ArtistAlias(val canonical: String, val aliases: List<String>)

        /** agent 循环最多轮数（含读工具往返）。动作请求通常 1 轮收尾，多步组合 2–3 轮。 */
        private const val MAX_STEPS = 5
        private const val AGENT_CALL_RETRY_COUNT = 2
        private val AGENT_CALL_RETRY_DELAYS_MS = longArrayOf(350L, 900L)
        private const val MAX_HISTORY_MESSAGES = 16
        private const val MAX_HISTORY_TEXT_CHARS = 260
        private const val MAX_REPLY_CHARS = 420
        private const val DEFAULT_PLAYLIST_LIMIT = 80
        private val ARTIST_ALIASES = listOf(
            ArtistAlias("The Weeknd", listOf("盆栽", "The Weeknd", "TheWeeknd", "The Weekend", "Weeknd", "威肯", "威坤")),
        )
        private val ARTIST_ALIAS_COMPACT_KEYS = ARTIST_ALIASES
            .flatMap { it.aliases }
            .map { it.lowercase().replace(Regex("[\\s，。,.!！?？、~～]+"), "").trim() }
            .filter { it.isNotBlank() }
            .toSet()

        /** 安静/低能量诉求线索（用多字词，避开"轻/快/慢"这类会撞"年轻/轻快"的单字误判）。 */
        private val CALM_ENERGY_CUES = listOf(
            "安静", "轻柔", "轻音乐", "温柔", "柔和", "舒缓", "慢歌", "慢一点", "慢节奏", "睡", "助眠",
            "深夜", "放松", "平静", "治愈", "小声", "别吵", "清净", "calm", "quiet", "mellow", "soft",
            "slow", "sleep", "chill", "relax", "ambient", "acoustic", "gentle", "soothing", "lofi",
            "lo-fi", "bedroom", "downtempo",
        )

        /** 嗨/高能量诉求线索。 */
        private val HYPE_ENERGY_CUES = listOf(
            "嗨", "燃", "炸", "动感", "激烈", "带劲", "够劲", "蹦迪", "派对", "狂", "上头", "快歌",
            "快一点", "快节奏", "high", "energetic", "hype", "party", "dance", "banger", "workout",
            "upbeat", "intense", "fast", "aggressive", "pump",
        )

        /** 读工具名集合：执行后把结果喂回模型、继续循环；其余视为动作工具（结算成 AgentAction）。 */
        private val READ_TOOLS = setOf(
            "search_catalog",
            "identify_lyrics",
            "get_play_history",
            "list_playlists",
            "get_playlist_tracks",
            "get_taste_profile",
        )

        /**
         * function-calling 工具定义（OpenAI 兼容）。system prompt + 这份 schema 跨请求
         * 保持稳定 —— 利于 DeepSeek 前缀缓存命中。注意：本串内不要出现 `$`（Kotlin
         * 三引号字符串模板）和三连双引号。
         */
        private val AGENT_TOOLS: String = """
[
  {"type":"function","function":{
	    "name":"play_queue",
	    "description":"放歌：把队列换成新的一组，或插一首。用户表达任何想听歌的信号（说情绪/累/烦/开心、点名艺人或歌、描述场景、催促放歌）都用它。用户说 来个/排个/编个/安排 X 歌单（如晚安歌单、通勤歌单）也是现场编排，用 play_queue，不是播放已有歌单；但 播放/打开这个歌单、那个歌单、XX那个歌单 是播放已有歌单，必须用 play_playlist。reply 用当前人格自然说话，可以顺带讲一句为什么这样放。表达情绪/能量(安静/嗨/深夜/专注/想睡)时务必填 softPreferences.energy(low/mid/high) + moods 和 musicHints.energy，别只写进 recommendationPlan；reply 里点名的歌手/歌必须同时进 textHints，否则只描述风格别点名。",
    "parameters":{"type":"object","properties":{
	      "reply":{"type":"string","description":"你以当前人格说出来的话。先接用户原话里的对象、情绪或语气，再自然确认动作；不要套固定模板。动作确认可短；如果用户表达情绪、场景或问为什么，可以 1-3 句讲清楚音乐选择。不要写“从 X 到 Y / 涵盖 X 到 Y / 包括 A、B、C”这种目录式介绍。"},
      "queue_action":{"type":"string","enum":["replace","insert"],"description":"replace=换整列（指定艺人/情绪/场景探索，默认）；insert=只插一首（用户只点名一首歌、不想毁掉当前队列）。不确定就 replace。"},
      "intent":{"type":"object","description":"放歌意图，只填用得上的字段。","properties":{
        "queryText":{"type":"string","description":"用户原句"},
        "hardConstraints":{"type":"object","description":"用户明确说死的硬约束","properties":{"artists":{"type":"array","items":{"type":"string"}},"tracks":{"type":"array","items":{"type":"string"}},"genres":{"type":"array","items":{"type":"string"}},"languages":{"type":"array","items":{"type":"string"}},"regions":{"type":"array","items":{"type":"string"}},"excludeArtists":{"type":"array","items":{"type":"string"}},"excludeGenres":{"type":"array","items":{"type":"string"}},"excludeTags":{"type":"array","items":{"type":"string"}}}},
        "textHints":{"type":"object","description":"句子里直接点名的，或从可执行音乐指代承接来的那首歌；用户说 那首/它/刚才说的 时，把指代里的 title 放进 tracks、artist 放进 artists。","properties":{"artists":{"type":"array","items":{"type":"string"}},"tracks":{"type":"array","items":{"type":"string"}},"albums":{"type":"array","items":{"type":"string"}}}},
        "musicHints":{"type":"object","properties":{"moods":{"type":"array","items":{"type":"string"}},"scenes":{"type":"array","items":{"type":"string"}},"genres":{"type":"array","items":{"type":"string"}},"energy":{"type":"string"},"transitionStyle":{"type":"string"},"avoid":{"type":"array","items":{"type":"string"}}}},
        "softPreferences":{"type":"object","properties":{"moods":{"type":"array","items":{"type":"string"}},"scenes":{"type":"array","items":{"type":"string"}},"textures":{"type":"array","items":{"type":"string"}},"energy":{"type":"string"},"tempoFeel":{"type":"string"},"qualityWords":{"type":"array","items":{"type":"string"}}}},
        "recommendationPlan":{"type":"object","description":"AI 编排心动模式/自由推荐时填写。mainStyles 是稳准主锚点，adjacentStyles 是相邻探索，surpriseStyles 是少量惊喜边缘；基于用户画像、当前歌、场景和原话，不要为了没听过而牺牲贴合。这里只表达音乐意图，播放器会根据智能接歌做声学顺序微调。","properties":{"mainStyles":{"type":"array","items":{"type":"string"}},"adjacentStyles":{"type":"array","items":{"type":"string"}},"surpriseStyles":{"type":"array","items":{"type":"string"}},"avoidStyles":{"type":"array","items":{"type":"string"}},"exploration":{"type":"string","enum":["safe","balanced","adventurous"]}}},
        "queueIntent":{"type":"object","properties":{"orderStyle":{"type":"string","description":"smooth 默认 / energy_up / party / sleep"}}},
        "desiredCount":{"type":"integer","description":"想要几首，1-60，默认 30"}
      }}
    },"required":["reply","intent"]}
  }},
	  {"type":"function","function":{
	    "name":"play_similar",
		    "description":"基于当前在播曲找类似（用户说 再来几首类似的 / 跟这首一样的 / 类似但更慢）。可选 intent 微调方向（更慢、更燃）。reply 可以提到当前歌的某个听感锚点。",
	    "parameters":{"type":"object","properties":{
			      "reply":{"type":"string","description":"当前人格的自然回复。先接用户原话，不套固定模板；可以说明抓住了当前歌的什么感觉，如节奏、声线、氛围或能量。不要写“从 X 到 Y / 涵盖 X 到 Y”这种介绍稿口吻。"},
	      "intent":{"type":"object","description":"可选微调，字段同 play_queue 的 intent。"}
	    },"required":["reply"]}
	  }},
	  {"type":"function","function":{
	    "name":"play_playlist",
		    "description":"只播放用户已有歌单。用户说 播放/打开这个歌单、那个歌单、XX那个歌单、我的XX歌单、收藏歌单、XX歌单里的歌、已有歌单 时使用；来个/排个/编个 X 歌单属于现场编排，必须用 play_queue。如果歌单名不明确，先用 list_playlists，拿到列表后必须继续 play_playlist，不要只回文字。reply 用当前人格自然回应。",
	    "parameters":{"type":"object","properties":{
			      "reply":{"type":"string","description":"你以当前人格说出来的话。先接用户原话，不套固定模板；可以短，也可以提一句这个歌单接下来会是什么气氛。不要写“从 X 到 Y / 涵盖 X 到 Y”这种介绍稿口吻。"},
	      "name":{"type":"string","description":"用户说的歌单名，原样给，app 端模糊匹配。"},
	      "limit":{"type":"integer","description":"最多装入多少首，默认80，1-160。"}
	    },"required":["reply","name"]}
	  }},
	  {"type":"function","function":{"name":"skip","description":"跳过当前歌（用户说 下一首 / 跳过 / 换一首 / 不想听这个）。","parameters":{"type":"object","properties":{"reply":{"type":"string"}},"required":["reply"]}}},
  {"type":"function","function":{"name":"like","description":"收藏当前歌到 我喜欢的音乐（用户说 收藏这首 / 加心 / 喜欢这首）。","parameters":{"type":"object","properties":{"reply":{"type":"string"}},"required":["reply"]}}},
  {"type":"function","function":{"name":"unlike","description":"取消收藏当前歌（用户说 取消收藏 / 不喜欢这首）。","parameters":{"type":"object","properties":{"reply":{"type":"string"}},"required":["reply"]}}},
  {"type":"function","function":{"name":"add_to_playlist","description":"把当前歌加到指定歌单（加到 XX 歌单 / 丢进 XX / 保存到 XX）。","parameters":{"type":"object","properties":{"reply":{"type":"string"},"playlist_name":{"type":"string","description":"用户说的歌单名，原样给，app 端模糊匹配。"}},"required":["reply","playlist_name"]}}},
  {"type":"function","function":{"name":"remove_from_playlist","description":"把当前歌从指定歌单移除（从 XX 删了 / 把这首从 XX 拿出来）。","parameters":{"type":"object","properties":{"reply":{"type":"string"},"playlist_name":{"type":"string"}},"required":["reply","playlist_name"]}}},
	  {"type":"function","function":{"name":"say","description":"只说话不放歌：纯打招呼/问名字/感谢/闲聊，或回答关于当前歌/听歌历史/音乐知识的问题。这里不需要很短；要像懂音乐、懂用户语境的助手。回答某歌手成名曲/代表作/推荐某首具体歌时，必须把那首歌写进 music_references，方便用户下一句说 那首/它 时直接播放。","parameters":{"type":"object","properties":{"reply":{"type":"string","description":"当前人格的自然回复。聊天、解释、音乐问题可以 2-5 句；要具体、有听感、有承接，不要客服腔。"},"music_references":{"type":"array","description":"本轮回复里提到、后续可用 那首/它 执行播放的具体歌曲。闲聊没有就给空数组或省略。","items":{"type":"object","properties":{"title":{"type":"string"},"artist":{"type":"string"},"reason":{"type":"string","description":"为什么提到它，如 成名曲/代表作/推荐"}},"required":["title"]}}},"required":["reply"]}}},
  {"type":"function","function":{"name":"search_catalog","description":"在线搜曲库找具体歌或艺人，确认是否存在或拿候选。只查歌名/艺人/专辑；按一句歌词识别歌曲时必须用 identify_lyrics。","parameters":{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"}},"required":["query"]}}},
  {"type":"function","function":{"name":"identify_lyrics","description":"按用户给的一句歌词识别歌曲。工具会拉候选歌词并返回置信度；高置信才可断言/播放，低置信只能给候选让用户确认。","parameters":{"type":"object","properties":{"line":{"type":"string","description":"用户给的歌词片段，原样传入。"},"artist":{"type":"string","description":"如果用户同时提到歌手，可传入缩小范围。"},"limit":{"type":"integer","description":"候选数，默认8。"}},"required":["line"]}}},
  {"type":"function","function":{"name":"get_play_history","description":"查用户最近听过和跳过的歌。回答 我刚才听啥 / 上一首 / 之前那首，或处理 像我最近常听的 这类要参考历史的请求前先调。","parameters":{"type":"object","properties":{}}}},
  {"type":"function","function":{"name":"list_playlists","description":"列出用户的歌单（名字+数量）。需要按歌单操作或挑歌单前先调。","parameters":{"type":"object","properties":{}}}},
  {"type":"function","function":{"name":"get_playlist_tracks","description":"看某个歌单里的歌，按名字模糊匹配。","parameters":{"type":"object","properties":{"name":{"type":"string"},"limit":{"type":"integer"}},"required":["name"]}}},
  {"type":"function","function":{"name":"get_taste_profile","description":"读用户长期口味画像（常听风格/艺人/总结）。当用户说 你懂我 / 随便来点我爱听的 时可参考——这是参考不是硬过滤，别把它变成只推同几个艺人。","parameters":{"type":"object","properties":{}}}}
	]
	""".trim()
        private val AGENT_TOOL_COUNT = runCatching { JSONArray(AGENT_TOOLS).length() }.getOrDefault(0)
    }

}

/**
 * 顶层扩展：把 PetIntent 转成 netease 搜索词。
 * 单独成 extension 方便单测，也让 PetAgent 主类不再持有 toSearchQueries。
 */
fun PetIntent.toSearchQueries(rawText: String): List<String> {
    val queries = LinkedHashSet<String>()
    (hardTracks + textTracks).take(3).forEach { if (it.isNotBlank()) queries.add(it.trim()) }
    (hardArtists + textArtists).distinct().take(3).forEach { if (it.isNotBlank()) queries.add(it.trim()) }

    val langOrRegion = (hardLanguages + hardRegions).firstOrNull()
    hardGenres.take(3).forEach { g ->
        val q = if (langOrRegion != null) "$langOrRegion $g" else g
        queries.add(q.trim())
    }
    (aiMainStyles + aiAdjacentStyles).take(4).forEach { if (it.isNotBlank()) queries.add(it.trim()) }
    if (aiExploration != "safe") {
        aiSurpriseStyles.take(2).forEach { if (it.isNotBlank()) queries.add(it.trim()) }
    }
    val moods = softMoods.take(3)
    val scenes = softScenes.take(2)
    for (s in scenes) {
        for (m in moods) {
            queries.add("$s $m".trim())
            if (queries.size >= 8) break
        }
        if (queries.size >= 8) break
    }
    if (queries.size < 4) {
        scenes.forEach { queries.add(it.trim()) }
        moods.forEach { queries.add(it.trim()) }
    }
    when (orderStyle) {
        "sleep" -> queries.add("睡眠 ambient")
        "party" -> queries.add("party dance")
        "energy_up" -> queries.add("uplifting energy")
        else -> {}
    }
    if (queries.isEmpty() && rawText.isNotBlank()) queries.add(rawText.trim().take(20))
    return queries.toList().take(8)
}
