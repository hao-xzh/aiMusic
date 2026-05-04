package app.pipo.nativeapp.data

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
 * SYSTEM_PROMPT 完全对齐 src/lib/music-intent.ts，DeepSeek prompt cache 跨平台命中。
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

    enum class Action { Chat, Play }

    data class AgentResponse(
        val reply: String,
        val action: Action,
        val initialBatch: List<NativeTrack>,
        val continuous: ContinuousQueueSource?,
    )

    suspend fun chat(
        userText: String,
        history: List<Pair<Boolean, String>>,
        currentTrack: NativeTrack?,
        userFacts: String,
    ): AgentResponse {
        val raw = runCatching {
            repository.aiChat(
                system = SYSTEM_PROMPT,
                user = buildUserMessage(userText, history, currentTrack, userFacts),
                temperature = 0.75f,
                maxTokens = 4000,
            )
        }.getOrNull().orEmpty()

        val parsed = parseIntent(raw)
            ?: return AgentResponse(
                reply = if (raw.isBlank()) "我这边断线了，再说一次？" else raw.take(60),
                action = Action.Chat,
                initialBatch = emptyList(),
                continuous = null,
            )

        if (parsed.action != Action.Play) {
            return AgentResponse(
                reply = parsed.reply,
                action = Action.Chat,
                initialBatch = emptyList(),
                continuous = null,
            )
        }

        val intent = parsed.intent
        val desired = intent.desiredCount.coerceIn(8, 60)

        // 1) 本地库 → 多路召回 → 排序
        val localTracks = runCatching { library.library() }.getOrDefault(emptyList())

        // 1a) 命名歌曲 PIN —— 用户在话里明确点过的歌（"放七百年后"、"陈奕迅 浮夸"），
        //     直接钉到队首；recall 只是"补差"，不是"覆盖用户明示意图"。
        //     之前 hardTracks 字段在 recall 里完全没人用，textTracks 也只是 +0.6 分
        //     完全可能被 audio/semantic/behavior 几路压下去 —— 这是 bug 不是 feature。
        val pinnedNamed = resolvePinnedTracks(intent, localTracks)
        val behaviorEvents = runCatching { behaviorLog.readAll() }.getOrDefault(emptyList())
        val recentPlay = runCatching { behaviorLog.recentPlay() }.getOrDefault(BehaviorLog.RecentPlay(emptySet(), emptySet()))
        val recentRec = runCatching { recommendationLog.recentContext() }.getOrDefault(RecommendationLog.RecentContext(emptySet(), emptySet()))
        val tasteProfile = tasteProfileStore.flow.value

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
            // smooth-queue 平滑接歌
            val asTracks = ranked.map { it.candidate.track }
            SmoothQueue.smooth(
                tracks = asTracks,
                featuresStore = featuresStore,
                mode = SmoothQueue.Mode.Discovery,
            ).take(desired)
        } else emptyList()

        // 2) 命名歌曲补全 —— 用户点过名但本地没有的，去网易兜一首
        val pinnedOnline = resolvePinnedFromOnline(intent, pinnedNamed)
        val pinnedAll = mergeUnique(pinnedNamed, pinnedOnline)

        // 3) 本地不够 → netease 搜索补
        val recallMerged = if (localRanked.size >= 6) {
            localRanked
        } else {
            val queries = intent.toSearchQueries(userText)
            val online = neteaseSearch(queries)
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

        // 4) 把 pinned 钉到队首，然后是 recall 结果（去重）
        val final = if (pinnedAll.isEmpty()) {
            recallMerged.take(desired)
        } else {
            val pinnedKeys = pinnedAll.mapTo(HashSet()) { TrackDedupe.songKey(it) }
            val rest = recallMerged.filter { TrackDedupe.songKey(it) !in pinnedKeys }
            (pinnedAll + rest).take(desired)
        }

        if (final.isEmpty()) {
            return AgentResponse(
                reply = "${parsed.reply}（这次库里真没贴的，换个说法？）",
                action = Action.Chat,
                initialBatch = emptyList(),
                continuous = null,
            )
        }

        // 3) 记 recommendation log（防 24h 内重复）
        runCatching {
            recommendationLog.log(final.mapNotNull { it.neteaseId }, RecommendationLog.Source.Pet)
        }

        // 4) 拆 initialBatch + reservoir
        val initialBatch = final.take(12)
        val reservoir = final.drop(12)

        return AgentResponse(
            reply = parsed.reply,
            action = Action.Play,
            initialBatch = initialBatch,
            continuous = makeContinuousSource(reservoir, intent.toSearchQueries(userText)),
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
     * 在本地库里找用户点名的歌。匹配优先级：
     *   1) 标题归一化完全相等 + 任一艺人也命中（陈奕迅 + 七百年后 → 钉那一首）
     *   2) 标题归一化完全相等（同名翻唱也认）
     *   3) 标题归一化包含/被包含（"七百年后" ↔ "七百年后 (Live)"）
     * 返回结果按用户在话里的顺序，去重。
     */
    private fun resolvePinnedTracks(intent: PetIntent, library: List<NativeTrack>): List<NativeTrack> {
        if (library.isEmpty()) return emptyList()
        val titles = (intent.hardTracks + intent.textTracks).map { it.trim() }.filter { it.isNotEmpty() }
        if (titles.isEmpty()) return emptyList()
        val artistKeys = (intent.hardArtists + intent.textArtists)
            .map(::normalizeForMatch).filter { it.isNotEmpty() }.toSet()

        val out = LinkedHashMap<String, NativeTrack>()
        for (rawTitle in titles.distinct()) {
            val titleKey = normalizeForMatch(rawTitle)
            if (titleKey.isEmpty()) continue

            // 第 1 档：标题精确 + 艺人命中
            val exactWithArtist = if (artistKeys.isEmpty()) null else library.firstOrNull { t ->
                normalizeForMatch(t.title) == titleKey &&
                    t.artist.split('/', '&', ',', '、').any { a ->
                        normalizeForMatch(a) in artistKeys
                    }
            }
            // 第 2 档：标题精确（无艺人约束 / 艺人不命中也接受）
            val exactOnly = library.firstOrNull { normalizeForMatch(it.title) == titleKey }
            // 第 3 档：标题包含/被包含
            val partial = library.firstOrNull {
                val tk = normalizeForMatch(it.title)
                tk.isNotEmpty() && (titleKey in tk || tk in titleKey)
            }

            val pick = exactWithArtist ?: exactOnly ?: partial ?: continue
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
        val titles = (intent.hardTracks + intent.textTracks).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (titles.isEmpty()) return emptyList()

        val pinnedTitleKeys = alreadyPinned.mapTo(HashSet()) { normalizeForMatch(it.title) }
        val missing = titles.filter { normalizeForMatch(it) !in pinnedTitleKeys }
        if (missing.isEmpty()) return emptyList()

        val artistHint = (intent.hardArtists + intent.textArtists).firstOrNull()?.trim().orEmpty()
        return coroutineScope {
            val deferred = missing.map { title ->
                async {
                    val q = if (artistHint.isNotEmpty()) "$title $artistHint" else title
                    runCatching { repository.searchTracks(q, limit = 3) }.getOrDefault(emptyList())
                        .firstOrNull { hit ->
                            // 只接受标题 fuzzy 命中的，避免搜索引擎乱推不相干的
                            val tk = normalizeForMatch(hit.title)
                            val want = normalizeForMatch(title)
                            tk.isNotEmpty() && (tk == want || want in tk || tk in want)
                        }
                }
            }
            deferred.mapNotNull { it.await() }
        }
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
    private suspend fun neteaseSearch(queries: List<String>): List<NativeTrack> {
        if (queries.isEmpty()) return emptyList()
        return coroutineScope {
            val deferred = queries.map { q ->
                async { runCatching { repository.searchTracks(q, limit = 4) }.getOrDefault(emptyList()) }
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
     * 续杯 source：先消费 reservoir，空了之后再发一次"放宽"搜索补一批。
     */
    private fun makeContinuousSource(
        reservoir: List<NativeTrack>,
        seedQueries: List<String>,
    ): ContinuousQueueSource {
        val pool = reservoir.toMutableList()
        val consumed = mutableSetOf<Long>().apply {
            reservoir.forEach { it.neteaseId?.let(::add) }
        }
        // 同一首歌的 Live/Karaoke/Acoustic 多版本归一到同 songKey —— 续杯不能再灌进重复版本
        val consumedSongKeys = HashSet<String>().apply {
            reservoir.forEach { add(TrackDedupe.songKey(it)) }
        }
        var refillTried = false

        return ContinuousQueueSource { excludeIds ->
            val drained = mutableListOf<NativeTrack>()
            val iter = pool.iterator()
            while (iter.hasNext() && drained.size < 8) {
                val t = iter.next()
                val id = t.neteaseId
                iter.remove()
                if (id == null || id in excludeIds) continue
                val k = TrackDedupe.songKey(t)
                if (k in consumedSongKeys) continue
                consumedSongKeys.add(k)
                drained.add(t)
            }
            if (drained.isNotEmpty()) {
                runCatching { recommendationLog.log(drained.mapNotNull { it.neteaseId }, RecommendationLog.Source.Pet) }
                return@ContinuousQueueSource drained
            }

            if (!refillTried && seedQueries.isNotEmpty()) {
                refillTried = true
                val collected = mutableListOf<NativeTrack>()
                for (q in seedQueries) {
                    val hits = runCatching { repository.searchTracks(q, limit = 8) }.getOrDefault(emptyList())
                    for (t in hits) {
                        val id = t.neteaseId ?: continue
                        if (id in excludeIds || id in consumed) continue
                        val k = TrackDedupe.songKey(t)
                        if (k in consumedSongKeys) continue
                        consumed.add(id)
                        consumedSongKeys.add(k)
                        collected.add(t)
                        if (collected.size >= 12) break
                    }
                    if (collected.size >= 12) break
                }
                val refill = collected.take(8)
                runCatching { recommendationLog.log(refill.mapNotNull { it.neteaseId }, RecommendationLog.Source.Pet) }
                return@ContinuousQueueSource refill
            }
            emptyList()
        }
    }

    // ---------- USER message ----------

    private suspend fun buildUserMessage(
        userText: String,
        history: List<Pair<Boolean, String>>,
        currentTrack: NativeTrack?,
        userFacts: String,
    ): String {
        val ctxLines = mutableListOf<String>()
        val weather = runCatching { Weather.get() }.getOrNull()
        ctxLines.add("时段：${AppContext.describe(weather)}")
        AppContext.memoryDigest(userFacts)?.let { ctxLines.add("TA 的人:$it") }
        currentTrack?.let { ctxLines.add("在播：${it.title} — ${it.artist}") }
        val tail = history.takeLast(4)
        if (tail.isNotEmpty()) {
            val block = tail.joinToString("\n") { (fromUser, text) ->
                "${if (fromUser) "U" else "C"}：$text"
            }
            ctxLines.add("最近：\n$block")
        }
        val prefix = if (ctxLines.isNotEmpty()) ctxLines.joinToString("\n") + "\n\n" else ""
        return prefix + "用户：$userText"
    }

    // ---------- 解析 ----------

    private data class Parsed(
        val reply: String,
        val action: Action,
        val intent: PetIntent,
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
            Parsed(reply, action, intent)
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

    companion object {
        /**
         * SYSTEM_PROMPT —— 完全对齐 src/lib/music-intent.ts:255。
         * 跨平台共享同一份 system 字符串 → DeepSeek prompt cache 命中。
         */
        private val SYSTEM_PROMPT: String = """
你是 Claudio —— 一只幽默抽象的音乐宠物。你既会接话，也会顺手放歌；这两件事不是互斥的。

# 任务
用户每说一句话，你输出一个 JSON。reply 是你以人格说出来的话；其他字段告诉本地音乐引擎要不要放歌、放什么。

# 人格调性（reply 字段）
- 永远短，中文一般 ≤24 字，最多两句。
- 抽象的比喻 OK："打工是吧""老天爷在哭""把音量旋大点""上班就是吃公司的电压"。
- 关系熟到不用客气的朋友：随便、懒、偶尔损一下。
- 决定放歌就直接说"放着""听着""点火"，不要问"要不要"。
- 绝不要：客服腔（"好的""为您"）/ 鸡汤（"加油"）/ 双形容词对仗（既…又…）/ 三连问 / 感叹号 / emoji / "嘿"起手 / "这首歌很适合你"这种夸奖。

# action 怎么判
- play：用户表达任何想听歌的信号（说情绪/累/烦/开心、点名艺人、催促"放歌啊"）
- chat：纯打招呼/问名字/感谢/闲聊
- 模糊就偏 play —— 这是音乐宠物，沉默是失败。

# 字段约定（用得上才填，默认值都 OK）
- queryText: 用户原句
- textHints.{artists,tracks,albums}: 句子里直接点名的（不要瞎补）
- musicHints.{moods,scenes,genres,avoid,energy,transitionStyle}
- hardConstraints: 用户明确说死的语言/地区/流派/排除项
- softPreferences.{moods,scenes,textures,energy,tempoFeel,eras,qualityWords}
- queueIntent.orderStyle: 默认 smooth；激情用 energy_up；派对 party；睡眠 sleep
- desiredCount: 1-60，默认 30

# 输出格式
严格只输出一个 JSON 对象，不要 markdown，不要 ```，不要解释。

# 当下上下文怎么用
USER 部分会带"时段"、可能含"明天就周末"/"再 3 天就国庆"/"今天下雨"这种锚点。
如果命中 TA 的话题或当下情绪,reply 里可以借力一下("周末已经在门口""假期心情先到");
不要硬塞,挑相关的一个用就行。

# 示例
1) "今天好累" →
{"reply":"打工是吧。来点能把电量充满的。","action":"play","queryText":"今天好累","softPreferences":{"moods":["uplifting","punchy"],"energy":"mid_high"},"hardConstraints":{"excludeTags":["sad","mellow"]},"queueIntent":{"orderStyle":"energy_up","transitionStyle":"tight"}}

2) "下雨了，放点好听的" →
{"reply":"老天爷在哭。我配点。","action":"play","queryText":"下雨了，放点好听的","softPreferences":{"scenes":["rainy day","night"],"moods":["melancholic","calm","atmospheric"],"energy":"mid_low"},"queueIntent":{"orderStyle":"smooth"}}

3) "我刚分手了" →
{"reply":"那你需要点猛的。","action":"play","queryText":"我刚分手了","softPreferences":{"moods":["cathartic","defiant"],"energy":"high"},"queueIntent":{"orderStyle":"energy_up","transitionStyle":"tight"}}

4) "你叫什么" →
{"reply":"Claudio。一只放歌的。","action":"chat","queryText":"你叫什么"}

5) "你知道火星哥吗" →
{"reply":"Bruno Mars。给你来一组。","action":"play","queryText":"你知道火星哥吗","textHints":{"artists":["Bruno Mars"]},"hardConstraints":{"artists":["Bruno Mars"]}}

5b) "想听陈奕迅，从七百年后开始" →
{"reply":"行。先开七百年后。","action":"play","queryText":"想听陈奕迅，从七百年后开始","textHints":{"artists":["陈奕迅"],"tracks":["七百年后"]},"hardConstraints":{"artists":["陈奕迅"],"tracks":["七百年后"]}}

5c) "放浮夸" →
{"reply":"开浮夸。","action":"play","queryText":"放浮夸","textHints":{"tracks":["浮夸"]},"hardConstraints":{"tracks":["浮夸"]}}

6) "谢谢" →
{"reply":"嗯。","action":"chat","queryText":"谢谢"}

7) "我饿了" →
{"reply":"吃饭跟听歌一起。下饭的来一组。","action":"play","queryText":"我饿了","softPreferences":{"scenes":["dining"],"moods":["mellow","groovy"]},"queueIntent":{"orderStyle":"smooth"}}
""".trim()
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
