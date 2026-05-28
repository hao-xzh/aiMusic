package app.pipo.nativeapp.data

import app.pipo.nativeapp.DiagnosticsLogStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
) {

    enum class Action {
        Chat,
        /** 替换整个队列（"听陈奕迅"、"放点振奋的"、"想听 X 从 Y 开始"） */
        Play,
        /** 插队到当前队列下一首（"放浮夸"、"来一首XX"——只想听这一首，别毁掉当前列表） */
        Insert,
        /** 跳过当前歌（"下一首""跳过""换"）—— 不走召回 */
        Skip,
        /** 解释当前歌 / 推荐理由（"这首啥意思""为啥推这首"）—— reply 字段放宽到 ≤120 字，不走召回 */
        Explain,
        /** 基于当前曲找类似（"再来几首类似的""类似但更慢"）—— 走 Play 召回路径但 action 保留 Similar */
        Similar,
        /** 收藏当前曲到"我喜欢的音乐"红心歌单 */
        Like,
        /** 取消收藏 */
        Unlike,
        /** 加到指定歌单 —— trackOp.playlistName 给名字（UI 端模糊匹配 playlist id） */
        AddToPlaylist,
        /** 从指定歌单移除 */
        RemoveFromPlaylist,
    }

    /**
     * 写动作的参数。like/unlike 不需要 playlistName，
     * add_to_playlist / remove_from_playlist 必须有。
     */
    data class TrackOpRequest(
        val kind: String,
        val playlistName: String? = null,
    )

    data class AgentResponse(
        val reply: String,
        val action: Action,
        val initialBatch: List<NativeTrack>,
        val continuous: ContinuousQueueSource?,
        /** Like/Unlike/AddToPlaylist/RemoveFromPlaylist 时 UI 端用来执行写操作 */
        val trackOp: TrackOpRequest? = null,
    )

    suspend fun chat(
        userText: String,
        history: List<Pair<Boolean, String>>,
        currentTrack: NativeTrack?,
        userFacts: String,
        persona: PetPersona = PetPersona.DEFAULT,
    ): AgentResponse {
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "chat_start",
            fields = mapOf(
                "inputLen" to userText.length,
                "historyCount" to history.size,
                "hasCurrentTrack" to (currentTrack != null),
                "userFactsLen" to userFacts.length,
                "persona" to persona.id,
            ),
        )
        val behaviorEvents = runCatching { behaviorLog.readAll() }.getOrDefault(emptyList())
        val aiResult = runCatching {
            repository.aiChat(
                system = persona.chatSystemPrompt,
                user = buildUserMessage(userText, history, currentTrack, userFacts, behaviorEvents),
                temperature = 0.75f,
                maxTokens = 4000,
            )
        }
        aiResult.onFailure { error ->
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "ai_chat_failed",
                fields = errorFields(error) + mapOf("inputLen" to userText.length),
            )
        }
        val raw = aiResult.getOrNull().orEmpty()

        val parsed = parseIntent(raw)
            ?: run {
                DiagnosticsLogStore.record(
                    area = "ai_agent",
                    event = if (raw.isBlank()) "intent_empty" else "intent_parse_failed",
                    fields = mapOf(
                        "rawChars" to raw.length,
                        "inputLen" to userText.length,
                    ),
                )
                return AgentResponse(
                    reply = if (raw.isBlank()) "断线了。" else raw.take(60),
                    action = Action.Chat,
                    initialBatch = emptyList(),
                    continuous = null,
                )
            }

        if (parsed.action != Action.Play && parsed.action != Action.Similar) {
            // 非召回类 action —— 直接返回带 reply（可能附 trackOp）。
            // NativeAiPet 按 action 决定后续：
            //   Skip → viewModel.next()
            //   Explain / Chat → 只展示 reply
            //   Like / Unlike → repository.likeSong
            //   AddToPlaylist / RemoveFromPlaylist → repository.playlistModifyTracks（含模糊匹配 playlist）
            val trackOp = when (parsed.action) {
                Action.Like -> TrackOpRequest(kind = "like")
                Action.Unlike -> TrackOpRequest(kind = "unlike")
                Action.AddToPlaylist -> TrackOpRequest(kind = "add_to_playlist", playlistName = parsed.playlistName)
                Action.RemoveFromPlaylist -> TrackOpRequest(kind = "remove_from_playlist", playlistName = parsed.playlistName)
                else -> null
            }
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "intent_${parsed.action.name.lowercase()}",
                fields = mapOf(
                    "replyLen" to parsed.reply.length,
                    "rawAction" to parsed.action.name,
                    "playlistName" to (parsed.playlistName ?: ""),
                ),
            )
            return AgentResponse(
                reply = parsed.reply,
                action = parsed.action,
                initialBatch = emptyList(),
                continuous = null,
                trackOp = trackOp,
            )
        }

        val intent = parsed.intent
        val desired = intent.desiredCount.coerceIn(8, 60)
        val requestedArtistKeys = requestedArtistKeys(intent)
        val artistSearchQueries = artistFirstSearchQueries(intent, userText)

        // 1) 本地库 → 多路召回 → 排序
        val localTracks = runCatching { library.library() }.getOrDefault(emptyList())
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "intent_play",
            fields = mapOf(
                "desired" to desired,
                "queueAction" to parsed.queueAction,
                "libraryCount" to localTracks.size,
                "hardTrackCount" to intent.hardTracks.size,
                "hardArtistCount" to intent.hardArtists.size,
                "promptArtistCount" to requestedArtistKeys.size,
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
        if (parsed.queueAction == "insert") {
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
                return AgentResponse(
                    reply = "${parsed.reply}（这首我没找到，换个名字？）",
                    action = Action.Chat,
                    initialBatch = emptyList(),
                    continuous = null,
                )
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
            return AgentResponse(
                reply = parsed.reply,
                action = Action.Insert,
                initialBatch = toInsert,
                continuous = null,
            )
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
                runCatching { embeddingIndexer.embedQuery(userText) }.getOrNull()
            } else null

            val candidates = CandidateRecall.recall(
                intent = intent,
                library = localTracks,
                featuresStore = featuresStore,
                semanticStore = semanticStore,
                indexer = indexer,
                tasteProfile = tasteProfile,
                behaviorEvents = behaviorEvents,
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
            val queries = artistSearchQueries
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
            return AgentResponse(
                reply = "${parsed.reply}（这次库里真没贴的，换个说法？）",
                action = Action.Chat,
                initialBatch = emptyList(),
                continuous = null,
            )
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
                "desired" to desired,
            ),
        )

        return AgentResponse(
            reply = parsed.reply,
            action = parsed.action,  // Play 或 Similar —— Similar 走同样召回但 UI 可区分提示
            initialBatch = initialBatch,
            continuous = makeContinuousSource(
                reservoir = reservoir,
                seedQueries = artistSearchQueries,
                requestedArtistKeys = requestedArtistKeys,
                intent = intent,
                recentPlay = recentPlay,
                recentRec = recentRec,
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
        history: List<Pair<Boolean, String>>,
        currentTrack: NativeTrack?,
        userFacts: String,
        behaviorEvents: List<BehaviorEvent>,
    ): String {
        val ctxLines = mutableListOf<String>()
        val weather = runCatching { Weather.get() }.getOrNull()
        ctxLines.add("时段：${AppContext.describe(weather)}")
        AppContext.memoryDigest(userFacts)?.let { ctxLines.add("TA 的人:$it") }
        
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

        val tail = history.takeLast(4)
        if (tail.isNotEmpty()) {
            val block = tail.joinToString("\n") { (fromUser, text) ->
                "${if (fromUser) "U" else "C"}：$text"
            }
            ctxLines.add("最近对话：\n$block")
        }
        val prefix = if (ctxLines.isNotEmpty()) ctxLines.joinToString("\n") + "\n\n" else ""
        return prefix + "用户：$userText"
    }

    // ---------- 解析 ----------

    private data class Parsed(
        val reply: String,
        val action: Action,
        val intent: PetIntent,
        /** 来自 LLM queueIntent.action："insert" / "replace"，缺省 "replace" */
        val queueAction: String,
        /** AddToPlaylist/RemoveFromPlaylist 时 AI 给的歌单名（原样回传，UI 端做模糊匹配） */
        val playlistName: String? = null,
    )

    private fun parseIntent(raw: String): Parsed? {
        if (raw.isBlank()) return null
        val cleaned = raw.let { s ->
            val a = s.indexOf('{'); val b = s.lastIndexOf('}')
            if (a >= 0 && b > a) s.substring(a, b + 1) else s
        }
        return try {
            val obj = JSONObject(cleaned)
            val reply = obj.optString("reply").trim().ifBlank { "嗯。" }
            val actionStr = obj.optString("action").lowercase()
            val action = when (actionStr) {
                "play", "recommend", "continue", "adjust_queue" -> Action.Play
                "skip", "next" -> Action.Skip
                "explain", "info", "about", "why" -> Action.Explain
                "similar", "more_like_this", "more" -> Action.Similar
                "like", "favorite" -> Action.Like
                "unlike", "dislike", "unfavorite" -> Action.Unlike
                "add_to_playlist", "add" -> Action.AddToPlaylist
                "remove_from_playlist", "remove" -> Action.RemoveFromPlaylist
                else -> Action.Chat
            }
            val hard = obj.optJSONObject("hardConstraints")
            val text = obj.optJSONObject("textHints")
            val music = obj.optJSONObject("musicHints")
            val soft = obj.optJSONObject("softPreferences")
            val refs = obj.optJSONObject("references")
            val emo = obj.optJSONObject("emotionalGoal")
            val queue = obj.optJSONObject("queueIntent")

            val intent = PetIntent(
                queryText = obj.optString("queryText").ifBlank { "" },
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
            val queueAction = queue?.optString("action")?.lowercase()?.trim().orEmpty()
                .let { if (it == "insert") "insert" else "replace" }
            val playlistName = obj.optString("playlistName").trim().ifBlank { null }
            Parsed(reply, action, intent, queueAction, playlistName)
        } catch (_: Exception) { null }
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
