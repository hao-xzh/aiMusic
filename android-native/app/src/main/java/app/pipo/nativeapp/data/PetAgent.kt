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

        // 2) 本地不够 → netease 搜索补
        val final = if (localRanked.size >= 6) {
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
            merged.take(desired)
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
        var refillTried = false

        return ContinuousQueueSource { excludeIds ->
            val drained = mutableListOf<NativeTrack>()
            val iter = pool.iterator()
            while (iter.hasNext() && drained.size < 8) {
                val t = iter.next()
                val id = t.neteaseId
                iter.remove()
                if (id == null || id in excludeIds) continue
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
                        consumed.add(id)
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
