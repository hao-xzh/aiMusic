package app.pipo.nativeapp.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.max
import kotlin.math.round
import kotlin.random.Random

/**
 * 蒸馏引擎 —— 镜像 src/lib/taste-profile.ts distillTaste()。
 *
 * 流程：
 *   1) 拉每张选中歌单的 tracks（走 RustBridgeRepository 的 tracksMemoryCache，命中即跳）
 *   2) 分层采样：按歌单曲目数比例分配 quota（避免大歌单淹没小歌单）
 *   3) 拼 prompt：playlist 名 / 总曲目数 / 采样曲目（序号. id. 歌名 — 艺人 · 专辑）+
 *      详尽的"独立唱片店老板"语气要求
 *   4) AI 单次调用，severity 0.5（要稳定，不像旁白那样飘）
 *   5) 解析 JSON / 兜底本地统计
 *   6) 持久化到 TasteProfileStore
 *
 * 跟 React 完整版的差距：
 *   - 没有 audio.getCachedFeaturesBulk（Symphonia 声学指纹聚合）
 *   - 没有 readBehaviorLog / summarize（行为日志统计）
 *   这两块上层都是 prompt 增强，缺了画像质量略降，主流程还是完整的。
 */
sealed class DistillProgress {
    data class LoadingTracks(val done: Int, val total: Int) : DistillProgress()
    object Sampling : DistillProgress()
    object CallingAi : DistillProgress()
    /** 单曲语义标签批量进度（蒸馏后立刻顺手跑） */
    data class TaggingTracks(val done: Int, val total: Int) : DistillProgress()
    /** 向量索引批量进度（语义标过的才有资格 embed） */
    data class EmbeddingTracks(val done: Int, val total: Int) : DistillProgress()
    object Done : DistillProgress()
}

class DistillEngine(
    private val repository: PipoRepository,
    private val store: TasteProfileStore,
    private val featuresStore: AudioFeaturesStore,
    private val behaviorLog: BehaviorLog,
) {
    suspend fun distill(
        playlists: List<PipoPlaylist>,
        sampleSize: Int = 200,
        temperature: Float = 0.5f,
        onProgress: (DistillProgress) -> Unit = {},
    ): TasteProfile {
        require(playlists.isNotEmpty()) { "没有选中歌单" }

        // ---- 1) 并行拉 tracks（仓库内存缓存命中时秒回） ----
        val details = coroutineScope {
            val deferred = playlists.mapIndexed { i, p ->
                async {
                    onProgress(DistillProgress.LoadingTracks(i, playlists.size))
                    val tracks = runCatching { repository.tracksForPlaylist(p.id) }
                        .getOrDefault(emptyList())
                    Triple(p.id, p.name, tracks)
                }
            }
            deferred.map { it.await() }
        }
        onProgress(DistillProgress.LoadingTracks(playlists.size, playlists.size))

        val totalTracks = details.sumOf { it.third.size }

        // 全库去重平铺 —— LibraryAggregator 在这上面做统计，覆盖 100%
        val fullLibrary = details.flatMap { it.third }.distinctBy { it.id }
        val behaviorSummary = behaviorLog.summary()

        // ---- 2) 行为加权 + 艺人天花板的样本采样 ----
        onProgress(DistillProgress.Sampling)
        val sample = behaviorWeightedSample(
            details = details.map { it.second to it.third },
            target = sampleSize,
            behaviorSummary = behaviorSummary,
        )

        // sourceHash：所有曲目 neteaseId 排序后前 1000 拼接
        val allIds = details.flatMap { it.third.mapNotNull { t -> t.neteaseId } }
            .sorted()
            .take(1000)
            .joinToString(",")
        val sourceHash = quickHash(allIds)

        // ---- 3) 拼 prompt ----
        val playlistList = details.joinToString("、") { (_, name, tracks) ->
            "「$name」（${tracks.size} 首）"
        }

        val lines = sample.mapIndexed { i, t ->
            val album = if (t.album.isNotBlank()) " · ${t.album}" else ""
            "${i + 1}. ${t.neteaseId ?: t.id}. ${t.title} — ${t.artist}$album"
        }.joinToString("\n")

        // ---- 全库聚合（零 AI 调用）：覆盖整个 5000+ 首库的统计 ----
        // 这是关键：以前 prompt 只塞 sample，AI 凭 10% 切片猜口味。
        // 现在前置全库聚合（艺人 / 流派 / 语言 / 年代 / BPM / 行为）作为"事实背景"，
        // sample 列表只是给 AI 闻味，不是数据来源。规则版语义档案对没标过的曲也算上。
        val aggregate = LibraryAggregator.aggregate(
            library = fullLibrary,
            featuresStore = featuresStore,
            semanticStore = PipoGraph.trackSemanticStore,
            ruleSemanticIndexer = PipoGraph.semanticIndexer,
            behaviorSummary = behaviorSummary,
        )
        val aggregateBlock = LibraryAggregator.toPromptBlock(aggregate)

        val user = buildString {
            append("用户挑了 ${details.size} 张歌单：$playlistList\n")
            append("合计 $totalTracks 首，sample 抽了 ${sample.size} 首（行为加权 + 艺人天花板，避免大歌单淹没小歌单）。\n\n")
            append(aggregateBlock)
            append("\n## Sample（${sample.size} 首代表曲目，给你闻味用——画像必须反映上面的全库分布，不能只看这 ${sample.size} 首）\n")
            append(lines)
            append("\n\n")
            append("任务：基于上面的全库统计 + 这 ${sample.size} 首样本，画一份这个用户的\"音乐口味画像\"。\n")
            append("要求：\n")
            append("1) 不写官话套话，不堆形容词，像独立唱片店老板对客人聊天那种语气。\n")
            append("2) 必须从曲目里\"看\"出风格，不要光按歌名臆测；可以承认\"看不太出来\"。\n")
            append("3) genres 至少给 4 个具体子流派（不要\"流行\"\"摇滚\"这种空洞的），按权重降序，每个带 2~3 首列表里出现过的代表曲。\n")
            append("4) eras 用具体十年段（\"1990s\" / \"2000s\" / \"2010s\" / \"2020-now\"），权重 0~1。\n")
            append("5) moods 给 3~6 个有质感的形容词或短语，比如\"午夜独白\"\"带潮汐感\"\"克制忧郁\"。\n")
            append("6) topArtists 选 6~10 个，affinity 0~1。\n")
            append("7) culturalContext 给 2~4 个文化坐标，比如\"华语独立\"\"日系 city pop\"\"indie America\"。\n")
            append("8) taglines 给 1~3 句一句话人设。\n")
            append("9) summary：≤30 个汉字的总结，不要\"这是一位...\"这种套话。\n\n")
            append("严格只输出一行 JSON：\n")
            append("{\"genres\":[{\"tag\":\"...\",\"weight\":0.42,\"examples\":[\"...\",\"...\"]}],")
            append("\"eras\":[{\"label\":\"2010s\",\"weight\":0.55}],")
            append("\"moods\":[\"...\"],")
            append("\"topArtists\":[{\"name\":\"...\",\"affinity\":0.9}],")
            append("\"culturalContext\":[\"...\"],")
            append("\"taglines\":[\"...\"],")
            append("\"summary\":\"...\"}\n")
            append("不要 markdown，不要解释，不要代码块包裹，只一行 JSON。")
        }

        // ---- 4) AI 调用 ----
        onProgress(DistillProgress.CallingAi)
        val raw = runCatching {
            repository.aiChat(
                system = "你是 Claudio 的口味蒸馏器。听过比客人多的曲库 + 像独立唱片店老板。只输出 JSON，不要解释。",
                user = user,
                temperature = temperature,
                maxTokens = 1400,
            )
        }.getOrDefault("")

        // ---- 5) 解析（AI 失败走本地兜底） ----
        val parsed = TasteProfileSerde.parseAiBody(raw) ?: buildFallbackBody(sample, details)

        val profile = TasteProfile(
            version = 1,
            derivedAt = System.currentTimeMillis() / 1000,
            sourcePlaylistCount = details.size,
            sampledTrackCount = sample.size,
            totalTrackCount = totalTracks,
            sourcePlaylistIds = details.map { it.first },
            sourceHash = sourceHash,
            genres = parsed.genres,
            eras = parsed.eras,
            moods = parsed.moods,
            topArtists = parsed.topArtists,
            culturalContext = parsed.culturalContext,
            taglines = parsed.taglines,
            summary = parsed.summary,
        )

        store.save(profile)

        // ⚠️ 故意不在这里 burn 200 次单曲 LLM 标签 + embedding。
        //   - 之前那版 distill 后置批量索引每张 sample 都跑一次 chat → 480 次 AI 调用，
        //     用户反馈"成本浪费"。
        //   - 现在 LibraryAggregator 已经在 prompt 里给了全库聚合（覆盖 100% 5000 首），
        //     主 distill 一次 AI 调用就够。
        //   - CandidateRecall / SemanticRecall 当 sourceLlm=false 时自动用规则版兜底，
        //     播放体验不掉。向量召回路径不激活，也不影响功能（lexical fallback）。
        //   - 真要给某首歌精细标签，按需用 PipoGraph.semanticIndexer.indexOne(track) 单独叫。

        onProgress(DistillProgress.Done)
        return profile
    }
}

// ---------- 行为加权 + 艺人天花板的样本 ----------

/**
 * 升级版 sample 选择 —— 镜像 sample 真正"代表"用户口味，不再是均匀随机：
 *
 *   1) 按歌单分配配额（保留原 stratified 思路：大歌单多分、小歌单不被淹没）
 *   2) 每首歌算 weight：
 *      - 行为命中（PlayStarted / Completed 越多权重越高，Skipped 减分）
 *      - 默认 1.0（库里有但没听过——仍可能被采样，只是优先级低）
 *   3) 歌单内按 weight 加权随机抽（不是 shuffled().take 这种无差别）
 *   4) 全局 dedup + 艺人天花板：单艺人在 sample 里 ≤ 5%（最少 8 首）
 *      — 防止某个 200 首的偶像歌单让采样 80% 都是同一人
 *
 * targetTotal 默认 200（之前 480）—— LibraryAggregator 已覆盖全库统计，sample
 * 只是给 AI 闻味用，不再需要 480 那么大。
 */
private fun behaviorWeightedSample(
    details: List<Pair<String, List<NativeTrack>>>,
    target: Int,
    behaviorSummary: BehaviorSummary,
): List<NativeTrack> {
    if (details.isEmpty()) return emptyList()
    val totalRaw = details.sumOf { it.second.size }
    if (totalRaw <= target) {
        return details.flatMap { it.second }.distinctBy { it.id }
    }

    // ---- 1) 歌单配额 ----
    val quotas = details.map { (_, tracks) ->
        val ratio = tracks.size.toFloat() / totalRaw
        max(1, round(ratio * target).toInt())
    }.toMutableList()
    var sumQuota = quotas.sum()
    while (sumQuota > target && quotas.any { it > 1 }) {
        val maxIdx = quotas.indices.maxBy { quotas[it] }
        quotas[maxIdx] = max(1, quotas[maxIdx] - 1)
        sumQuota = quotas.sum()
    }

    // ---- 2) 艺人 → 行为加权 ----
    val loveSet = behaviorSummary.loveArtists.map { it.lowercase().trim() }.toSet()
    val skipSet = behaviorSummary.skipHotArtists.map { it.lowercase().trim() }.toSet()
    fun trackWeight(t: NativeTrack): Double {
        val firstArtist = t.artist.split("/", "&", ",").firstOrNull()?.trim()?.lowercase().orEmpty()
        if (firstArtist.isBlank()) return 1.0
        return when {
            firstArtist in loveSet -> 2.5  // 反复完整听过：高权重
            firstArtist in skipSet -> 0.3  // 反复跳过：低权重（不是 0，留个出现机会）
            else -> 1.0
        }
    }

    // ---- 3) 加权随机抽 ----
    val picked = mutableListOf<NativeTrack>()
    val seen = HashSet<String>()
    val rng = Random.Default
    details.forEachIndexed { i, (_, tracks) ->
        val unique = tracks.filter { seen.add(it.id) }
        val drawn = weightedDraw(unique, quotas[i], rng) { trackWeight(it) }
        picked.addAll(drawn)
    }

    // ---- 4) 艺人天花板（单艺人 ≤ 5%，最少 8 首） ----
    val artistCap = max(8, (target * 0.05).toInt())
    val byArtist = HashMap<String, Int>()
    val capped = ArrayList<NativeTrack>(picked.size)
    val overflow = ArrayList<NativeTrack>()
    for (t in picked) {
        val a = t.artist.split("/", "&", ",").firstOrNull()?.trim()?.lowercase().orEmpty()
        val n = byArtist[a] ?: 0
        if (n < artistCap || a.isBlank()) {
            byArtist[a] = n + 1
            capped.add(t)
        } else {
            overflow.add(t)
        }
    }
    // 如果 cap 砍太多导致总数不够，从其他艺人池中补——但仍然不超 cap
    if (capped.size < target) {
        // 把全库剩余非 picked 的曲目按 weight 再补一轮
        val pickedIds = capped.map { it.id }.toSet()
        val pool = details.flatMap { it.second }.distinctBy { it.id }.filter { it.id !in pickedIds }
        val extra = weightedDraw(pool, target - capped.size, rng) { trackWeight(it) }
        for (t in extra) {
            val a = t.artist.split("/", "&", ",").firstOrNull()?.trim()?.lowercase().orEmpty()
            val n = byArtist[a] ?: 0
            if (n < artistCap || a.isBlank()) {
                byArtist[a] = n + 1
                capped.add(t)
                if (capped.size >= target) break
            }
        }
    }
    return capped.take(target)
}

/** 加权无放回抽样：按 weight 概率分布抽 k 个。weights 都 ≤0 时退化成均匀随机。 */
private fun <T> weightedDraw(
    items: List<T>,
    k: Int,
    rng: Random,
    weight: (T) -> Double,
): List<T> {
    if (items.isEmpty() || k <= 0) return emptyList()
    if (items.size <= k) return items
    // 简化版 reservoir A-Res：每个 item 算 key = rand^(1/weight)，取 top-k
    return items.asSequence()
        .map { it to {
            val w = weight(it).coerceAtLeast(1e-6)
            // U^(1/w) 越大越被选中
            Math.pow(rng.nextDouble(), 1.0 / w)
        }() }
        .sortedByDescending { it.second }
        .take(k)
        .map { it.first }
        .toList()
}

// djb2 变种 hash（不依赖 crypto）
private fun quickHash(input: String): String {
    var h = 5381L
    for (c in input) h = (((h shl 5) + h) + c.code.toLong()) and 0xFFFFFFFFL
    return h.toString(36)
}

// ---------- 本地兜底画像 ----------
//
// AI JSON 解析失败时拿统计学画像兜底，至少不让用户卡在"无画像"状态。
private fun buildFallbackBody(
    sample: List<NativeTrack>,
    details: List<Triple<Long, String, List<NativeTrack>>>,
): TasteProfileSerde.ParsedAiBody {
    val artistCounts = mutableMapOf<String, Int>()
    var chineseLike = 0
    var latinLike = 0
    var japaneseLike = 0

    for (t in sample) {
        val text = "${t.title} ${t.album} ${t.artist}"
        if (text.any { it in '一'..'鿿' }) chineseLike++
        if (text.any { it in 'a'..'z' || it in 'A'..'Z' }) latinLike++
        if (text.any { it in '぀'..'ヿ' }) japaneseLike++
        if (t.artist.isNotBlank()) {
            artistCounts[t.artist] = (artistCounts[t.artist] ?: 0) + 1
        }
    }
    val topArtists = artistCounts.entries
        .sortedByDescending { it.value }
        .take(10)
        .map { (name, count) ->
            ArtistAffinity(
                name = name,
                affinity = (count.toFloat() / max(3f, sample.size * 0.04f)).coerceIn(0f, 1f),
            )
        }

    val total = max(1, sample.size).toFloat()
    val contexts = listOf(
        if (chineseLike / total > 0.35f) "华语音乐" else null,
        if (latinLike / total > 0.35f) "欧美/英语语境" else null,
        if (japaneseLike / total > 0.08f) "日系音乐" else null,
        if (details.any { (_, name, _) -> name.contains("喜欢") || name.contains("收藏", true) }) "私人常听收藏" else null,
    ).filterNotNull()

    fun trackLabel(t: NativeTrack) = if (t.artist.isNotBlank()) "${t.title} - ${t.artist}" else t.title

    return TasteProfileSerde.ParsedAiBody(
        genres = listOf(
            GenreTag("旋律向流行", 0.72f, sample.take(3).map(::trackLabel)),
            GenreTag("私人收藏精选", 0.62f, sample.drop(3).take(3).map(::trackLabel)),
            GenreTag("情绪化人声作品", 0.52f, sample.drop(6).take(3).map(::trackLabel)),
            GenreTag("跨语种流行/独立", 0.42f, sample.drop(9).take(3).map(::trackLabel)),
        ),
        eras = listOf(EraSlice("mixed", 1f)),
        moods = listOf("松弛", "旋律感", "私人化", "适合连续播放"),
        topArtists = topArtists,
        culturalContext = if (contexts.isNotEmpty()) contexts else listOf("私人曲库混合口味"),
        taglines = listOf("先按你的收藏重心排歌，再让后续播放慢慢学习。"),
        summary = "私人曲库混合口味",
    )
}
