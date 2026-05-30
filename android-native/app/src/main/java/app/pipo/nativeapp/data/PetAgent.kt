package app.pipo.nativeapp.data

import app.pipo.nativeapp.DiagnosticsLogStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
            behaviorEvents = behaviorEvents,
            behaviorPreference = behaviorPreference,
            musicReferences = musicReferences,
        )))

        val actions = mutableListOf<AgentAction>()
        val replyParts = LinkedHashSet<String>()
        val newMusicReferences = mutableListOf<PetMemory.MusicReference>()

        for (round in 0 until MAX_STEPS) {
            val raw = try {
                repository.aiChatTools(messages.toString(), toolsJson, 0.7f, 2000)
            } catch (e: Exception) {
                DiagnosticsLogStore.record(
                    area = "ai_agent",
                    event = "agent_call_failed",
                    fields = errorFields(e) + mapOf("round" to round),
                )
                return finalizeAgent(replyParts, actions, fallback = "我这边刚刚断了一下，再说一次。", musicReferences = newMusicReferences)
            }
            if (raw.isBlank()) return finalizeAgent(replyParts, actions, fallback = "断线了。", musicReferences = newMusicReferences)
            val assistant = runCatching { JSONObject(raw) }.getOrNull()
                ?: return finalizeAgent(replyParts, actions, fallback = raw.take(60), musicReferences = newMusicReferences)
            // 把 assistant 消息原样回灌 —— 下一轮带工具结果再发时，协议要求保留 tool_calls。
            messages.put(assistant)

            val content = assistantContent(assistant)
            val toolCalls = assistant.optJSONArray("tool_calls")
            val calls = if (toolCalls == null) emptyList()
                else (0 until toolCalls.length()).mapNotNull { parseToolCall(toolCalls.optJSONObject(it)) }

            if (calls.isEmpty()) {
                // 纯文本 —— 收尾（纯聊天 / explain / MiMo 降级都走这）。
                if (content.isNotBlank()) replyParts.add(content)
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
                if (content.isNotBlank()) replyParts.add(content)
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
                    executeReadTool(c, behaviorEvents)
                } else {
                    val (action, reply) = executeActionTool(
                        call = c,
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
            reply = reply.take(160),
            actions = actions.toList(),
            musicReferences = musicReferences.distinctBy { referenceKey(it) }.takeLast(8),
        )
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
                "get_play_history" -> summarizeHistory(behaviorEvents)
                "list_playlists" -> {
                    val pls = runCatching { repository.playlists.first() }.getOrDefault(emptyList())
                    if (pls.isEmpty()) "（没有歌单）"
                    else pls.joinToString("\n") { "${it.name}（${it.trackCount} 首）" }
                }
                "get_playlist_tracks" -> {
                    val name = call.args.optString("name").trim()
                    val pls = runCatching { repository.playlists.first() }.getOrDefault(emptyList())
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

    /** 动作工具：累积成 AgentAction（UI 后续按序执行），返回 (action?, 人格 reply)。 */
    private suspend fun executeActionTool(
        call: ToolCall,
        currentTrack: NativeTrack?,
        behaviorEvents: List<BehaviorEvent>,
        behaviorPreference: BehaviorPreferenceSnapshot,
    ): Pair<AgentAction?, String> {
        val reply = jsonString(call.args, "reply")
        return when (call.name) {
            "play_queue", "play_similar" -> {
                val similar = call.name == "play_similar"
                val intentObj = call.args.optJSONObject("intent") ?: JSONObject()
                val intent = parseIntentFromArgs(intentObj, fallbackQuery = reply)
                // 指代承接("听这个/那首")完全交给模型:它从对话 + music_references 里认出具体
                // 歌名填进 intent,并自行决定 queue_action(单首→insert)。不再用关键词正则兜底。
                val rawQueueAction = call.args.optString("queue_action").trim().lowercase()
                    .ifBlank { intentObj.optJSONObject("queueIntent")?.optString("action")?.lowercase().orEmpty() }
                val queueAction = if (rawQueueAction == "insert") "insert" else "replace"
                when (val outcome = buildPlayOutcome(intent, queueAction, currentTrack, behaviorEvents, behaviorPreference)) {
                    is PlayOutcome.Replace ->
                        AgentAction.Play(outcome.initialBatch, outcome.continuous, insert = false, similar = similar) to reply
                    is PlayOutcome.Insert ->
                        AgentAction.Play(listOf(outcome.track), null, insert = true, similar = false) to reply
                    is PlayOutcome.Empty ->
                        null to (if (reply.isBlank()) outcome.note else "$reply（${outcome.note}）")
                }
            }
            "play_playlist" -> {
                val name = call.args.optString("name").trim()
                val limit = call.args.optInt("limit", 80).coerceIn(1, 160)
                if (name.isBlank()) return null to "说个歌单名。"
                val playlists = runCatching { repository.playlists.first() }.getOrDefault(emptyList())
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
                    AgentAction.Play(tracks, continuous = null, insert = false, similar = false) to reply
                }
            }
            "skip" -> AgentAction.Skip to reply
            "like" -> AgentAction.Like(like = true) to reply
            "unlike" -> AgentAction.Like(like = false) to reply
            "add_to_playlist" -> {
                val name = call.args.optString("playlist_name").trim()
                (if (name.isNotEmpty()) AgentAction.Playlist(add = true, name = name) else null) to reply
            }
            "remove_from_playlist" -> {
                val name = call.args.optString("playlist_name").trim()
                (if (name.isNotEmpty()) AgentAction.Playlist(add = false, name = name) else null) to reply
            }
            "say" -> null to reply
            else -> null to reply
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
        intent: PetIntent,
        queueAction: String,
        currentTrack: NativeTrack?,
        behaviorEvents: List<BehaviorEvent>,
        behaviorPreference: BehaviorPreferenceSnapshot,
    ): PlayOutcome {
        val desired = intent.desiredCount.coerceIn(8, 60)
        val requestedArtistKeys = requestedArtistKeys(intent)
        val artistSearchQueries = artistFirstSearchQueries(intent, intent.queryText)
        val behaviorSearchQueries = if (requestedArtistKeys.isEmpty()) behaviorPreference.onlineSeeds(maxItems = 4) else emptyList()
        val onlineSeedQueries = if (requestedArtistKeys.isEmpty() && !hasSpecificSearchIntent(intent)) {
            mergeSearchQueries(behaviorSearchQueries, artistSearchQueries, maxItems = 10)
        } else {
            mergeSearchQueries(artistSearchQueries, behaviorSearchQueries, maxItems = 10)
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
        val tasteProfile = tasteProfileStore.flow.value

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
                ),
            )
            // smooth-queue 平滑接歌
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
            SmoothQueue.smooth(
                tracks = promptScopedTracks,
                featuresStore = featuresStore,
                mode = SmoothQueue.Mode.Discovery,
            ).take(desired)
        } else emptyList()

        // 2) 命名歌曲补全 —— 用户点过名但本地没有的，去网易兜一首
        val pinnedOnline = resolvePinnedFromOnline(intent, pinnedNamed)
        val pinnedAll = mergeUnique(pinnedNamed, pinnedOnline)

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
            )
            val online = prioritizeRequestedArtists(
                tracks = onlineRaw,
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

        // 4) 把 pinned 钉到队首，然后是 recall 结果（去重）
        val final = if (pinnedAll.isEmpty()) {
            promptScoped.take(desired)
        } else {
            val pinnedKeys = pinnedAll.mapTo(HashSet()) { TrackDedupe.songKey(it) }
            val rest = promptScoped.filter { TrackDedupe.songKey(it) !in pinnedKeys }
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
        val initialBatch = composeOpening(
            final = final,
            rankedByKey = rankedByKey,
            recentPlay = recentPlay,
            target = initialTarget,
        )
        val initialKeys = initialBatch.mapTo(HashSet()) { TrackDedupe.songKey(it) }
        // 3) 记 recommendation log（防 24h 内重复）—— 只记 initialBatch；reservoir
        //    在 makeContinuousSource 真被 drain 时才记，避免双重计数让 recencyPen 翻倍
        runCatching {
            recommendationLog.logTracks(initialBatch, RecommendationLog.Source.Pet)
        }
        val reservoir = final.filterNot { TrackDedupe.songKey(it) in initialKeys }
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "queue_ready",
            fields = mapOf(
                "initialCount" to initialBatch.size,
                "reservoirCount" to reservoir.size,
                "pinnedCount" to pinnedAll.size,
                "promptArtistCount" to requestedArtistKeys.size,
                "finalArtistCount" to final.count { matchesRequestedArtist(it, requestedArtistKeys) },
                "behaviorPreferenceConfidence" to behaviorPreference.confidence,
                "desired" to desired,
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
                fun artistOk(t: NativeTrack) = t.artist.split('/', '&', ',', '、').any { a ->
                    normalizeForMatch(a) in artistKeys
                }
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
                            // 单 artist hint 时也校验 artist 命中（防搜索引擎乱推同名）
                            if (singleArtistHint.isNotEmpty()) {
                                val artistKey = normalizeForMatch(singleArtistHint)
                                hit.artist.split('/', '&', ',', '、').any {
                                    normalizeForMatch(it) == artistKey
                                }
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

    /** 用一组查询词跑并行 netease 搜索，去重后返回 */
    private suspend fun neteaseSearch(queries: List<String>, limitPerQuery: Int = 4): List<NativeTrack> {
        if (queries.isEmpty()) return emptyList()
        return coroutineScope {
            val deferred = queries.map { q ->
                async { runCatching { repository.searchTracks(q, limit = limitPerQuery) }.getOrDefault(emptyList()) }
            }
            val seen = LinkedHashMap<Long, NativeTrack>()
            deferred.forEach { d ->
                d.await().forEach { t ->
                    val id = t.neteaseId ?: return@forEach
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

    /**
     * 三段开场: [head 熟悉(7d 听过) + mid 主推 + tail 探索(ranker bucket=3)]。
     *
     * head/tail 各取 ≤2 首避免"前 3 全是听过的卡碟"或"末 3 都太冷"。
     * 没有 ranker bucket(localRankedRecord 空, 即纯在线分支)时 tail 段自然为空,
     * 退化为"head + mid", 不影响行为正确性。
     */
    private fun composeOpening(
        final: List<NativeTrack>,
        rankedByKey: Map<String, CandidateRanker.Ranked>,
        recentPlay: BehaviorLog.RecentPlay,
        target: Int,
    ): List<NativeTrack> {
        if (final.size <= target) return final.take(target)
        val headCap = if (target >= 9) 2 else 1
        val tailCap = if (target >= 9) 2 else 1
        val taken = HashSet<String>()

        val familiar = final.asSequence()
            .filter { t ->
                val id = t.neteaseId ?: return@filter false
                id in recentPlay.last7dTrackIds
            }
            .take(headCap)
            .toList()
        familiar.forEach { taken.add(TrackDedupe.songKey(it)) }

        val explorers = final.asSequence()
            .filterNot { TrackDedupe.songKey(it) in taken }
            .filter { rankedByKey[TrackDedupe.songKey(it)]?.bucket == 3 }
            .take(tailCap)
            .toList()
        explorers.forEach { taken.add(TrackDedupe.songKey(it)) }

        val midNeeded = target - familiar.size - explorers.size
        val mid = final.asSequence()
            .filterNot { TrackDedupe.songKey(it) in taken }
            .take(midNeeded)
            .toList()

        return (familiar + mid + explorers).take(target)
    }

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
        behaviorEvents: List<BehaviorEvent>,
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

        val recentSkips = behaviorEvents
            .filter { it.type == BehaviorType.Skipped || (it.type == BehaviorType.ManualCut && it.completionPct < 0.6f) }
            .sortedByDescending { it.tsMs }
            .take(3)
        if (recentSkips.isNotEmpty()) {
            val skipBlock = recentSkips.joinToString("\n") { e ->
                "- ${e.title} — ${e.artist} (已听 ${(e.completionPct * 100).toInt()}%)"
            }
            ctxLines.add("最近被切的歌(负反馈)：\n$skipBlock")
        }

        // 最近播放历史 —— 让 AI 能回答"我刚才在听啥""上一首叫啥""昨天听的那首"这类回忆问题。
        // 取 PlayStarted/Completed 两种事件，按曲名去重，最多 5 条。
        val recentPlayed = behaviorEvents
            .filter { it.type == BehaviorType.PlayStarted || it.type == BehaviorType.Completed }
            .sortedByDescending { it.tsMs }
            .distinctBy { "${it.title}|${it.artist}" }
            .take(5)
        if (recentPlayed.isNotEmpty()) {
            val dateFmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            val playedBlock = recentPlayed.joinToString("\n") { e ->
                val tsStr = dateFmt.format(java.util.Date(e.tsMs))
                "- $tsStr ${e.title} — ${e.artist}"
            }
            ctxLines.add("最近播放历史：\n$playedBlock")
        }

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
        /** agent 循环最多轮数（含读工具往返）。动作请求通常 1 轮收尾，多步组合 2–3 轮。 */
        private const val MAX_STEPS = 5
        private const val MAX_HISTORY_MESSAGES = 16
        private const val MAX_HISTORY_TEXT_CHARS = 260

        /** 读工具名集合：执行后把结果喂回模型、继续循环；其余视为动作工具（结算成 AgentAction）。 */
        private val READ_TOOLS = setOf(
            "search_catalog",
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
    "description":"放歌：把队列换成新的一组，或插一首。用户表达任何想听歌的信号（说情绪/累/烦/开心、点名艺人或歌、描述场景、催促放歌）都用它。reply 用当前人格语气说一句。",
    "parameters":{"type":"object","properties":{
      "reply":{"type":"string","description":"你以当前人格说出来的那句话，短、口语、符合人格调性。"},
      "queue_action":{"type":"string","enum":["replace","insert"],"description":"replace=换整列（指定艺人/情绪/场景探索，默认）；insert=只插一首（用户只点名一首歌、不想毁掉当前队列）。不确定就 replace。"},
      "intent":{"type":"object","description":"放歌意图，只填用得上的字段。","properties":{
        "queryText":{"type":"string","description":"用户原句"},
        "hardConstraints":{"type":"object","description":"用户明确说死的硬约束","properties":{"artists":{"type":"array","items":{"type":"string"}},"tracks":{"type":"array","items":{"type":"string"}},"genres":{"type":"array","items":{"type":"string"}},"languages":{"type":"array","items":{"type":"string"}},"regions":{"type":"array","items":{"type":"string"}},"excludeArtists":{"type":"array","items":{"type":"string"}},"excludeGenres":{"type":"array","items":{"type":"string"}},"excludeTags":{"type":"array","items":{"type":"string"}}}},
        "textHints":{"type":"object","description":"句子里直接点名的，或从可执行音乐指代承接来的那首歌；用户说 那首/它/刚才说的 时，把指代里的 title 放进 tracks、artist 放进 artists。","properties":{"artists":{"type":"array","items":{"type":"string"}},"tracks":{"type":"array","items":{"type":"string"}},"albums":{"type":"array","items":{"type":"string"}}}},
        "musicHints":{"type":"object","properties":{"moods":{"type":"array","items":{"type":"string"}},"scenes":{"type":"array","items":{"type":"string"}},"genres":{"type":"array","items":{"type":"string"}},"energy":{"type":"string"},"transitionStyle":{"type":"string"},"avoid":{"type":"array","items":{"type":"string"}}}},
        "softPreferences":{"type":"object","properties":{"moods":{"type":"array","items":{"type":"string"}},"scenes":{"type":"array","items":{"type":"string"}},"textures":{"type":"array","items":{"type":"string"}},"energy":{"type":"string"},"tempoFeel":{"type":"string"},"qualityWords":{"type":"array","items":{"type":"string"}}}},
        "queueIntent":{"type":"object","properties":{"orderStyle":{"type":"string","description":"smooth 默认 / energy_up / party / sleep"}}},
        "desiredCount":{"type":"integer","description":"想要几首，1-60，默认 30"}
      }}
    },"required":["reply","intent"]}
  }},
	  {"type":"function","function":{
	    "name":"play_similar",
	    "description":"基于当前在播曲找类似（用户说 再来几首类似的 / 跟这首一样的 / 类似但更慢）。可选 intent 微调方向（更慢、更燃）。",
	    "parameters":{"type":"object","properties":{
	      "reply":{"type":"string"},
	      "intent":{"type":"object","description":"可选微调，字段同 play_queue 的 intent。"}
	    },"required":["reply"]}
	  }},
	  {"type":"function","function":{
	    "name":"play_playlist",
	    "description":"播放用户已有歌单（用户说 放我的XX歌单 / 播XX歌单 / 来点收藏歌单里的歌）。如果歌单名不明确，先用 list_playlists。reply 用当前人格说一句。",
	    "parameters":{"type":"object","properties":{
	      "reply":{"type":"string","description":"你以当前人格说出来的那句话，短、口语。"},
	      "name":{"type":"string","description":"用户说的歌单名，原样给，app 端模糊匹配。"},
	      "limit":{"type":"integer","description":"最多装入多少首，默认80，1-160。"}
	    },"required":["reply","name"]}
	  }},
	  {"type":"function","function":{"name":"skip","description":"跳过当前歌（用户说 下一首 / 跳过 / 换一首 / 不想听这个）。","parameters":{"type":"object","properties":{"reply":{"type":"string"}},"required":["reply"]}}},
  {"type":"function","function":{"name":"like","description":"收藏当前歌到 我喜欢的音乐（用户说 收藏这首 / 加心 / 喜欢这首）。","parameters":{"type":"object","properties":{"reply":{"type":"string"}},"required":["reply"]}}},
  {"type":"function","function":{"name":"unlike","description":"取消收藏当前歌（用户说 取消收藏 / 不喜欢这首）。","parameters":{"type":"object","properties":{"reply":{"type":"string"}},"required":["reply"]}}},
  {"type":"function","function":{"name":"add_to_playlist","description":"把当前歌加到指定歌单（加到 XX 歌单 / 丢进 XX / 保存到 XX）。","parameters":{"type":"object","properties":{"reply":{"type":"string"},"playlist_name":{"type":"string","description":"用户说的歌单名，原样给，app 端模糊匹配。"}},"required":["reply","playlist_name"]}}},
  {"type":"function","function":{"name":"remove_from_playlist","description":"把当前歌从指定歌单移除（从 XX 删了 / 把这首从 XX 拿出来）。","parameters":{"type":"object","properties":{"reply":{"type":"string"},"playlist_name":{"type":"string"}},"required":["reply","playlist_name"]}}},
  {"type":"function","function":{"name":"say","description":"只说话不放歌：纯打招呼/问名字/感谢/闲聊，或回答关于当前歌/听歌历史/音乐知识的问题。回答某歌手成名曲/代表作/推荐某首具体歌时，必须把那首歌写进 music_references，方便用户下一句说 那首/它 时直接播放。","parameters":{"type":"object","properties":{"reply":{"type":"string"},"music_references":{"type":"array","description":"本轮回复里提到、后续可用 那首/它 执行播放的具体歌曲。闲聊没有就给空数组或省略。","items":{"type":"object","properties":{"title":{"type":"string"},"artist":{"type":"string"},"reason":{"type":"string","description":"为什么提到它，如 成名曲/代表作/推荐"}},"required":["title"]}}},"required":["reply"]}}},
  {"type":"function","function":{"name":"search_catalog","description":"在线搜曲库找具体歌或艺人，确认是否存在或拿候选。用于 有没有 XX / 找一首歌词是 XX 的 这类需要先查的请求。","parameters":{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"}},"required":["query"]}}},
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
