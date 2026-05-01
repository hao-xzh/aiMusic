package app.pipo.nativeapp.data

/**
 * 全库本地聚合 —— 不叫 AI，统计全部 5000 首歌的画像基础数据。
 *
 * 核心想法：DistillEngine 的 prompt 不能只塞 480 首样本然后让 AI 凭样本断口味
 * （那是 10% 切片，剩下 90% 永远不进 prompt）。改成：
 *
 *   1) 这一层在本地把全库 5000 首扫一遍，做艺人 / 语言 / 年代 / 行为 等统计
 *   2) AudioFeatures / TrackSemanticProfile 已索引多少用多少（不强制 LLM 全标）
 *   3) 把统计结果写成 prompt block，喂给 AI 当"全库事实背景"
 *   4) AI 看到的画像基于这些聚合（覆盖 100%），样本只是"感受味道"用
 *
 * 全程零 AI 调用，几十毫秒搞完。
 */
object LibraryAggregator {

    data class Aggregate(
        val totalTracks: Int,
        val analyzedTracks: Int,         // 已经跑过 Symphonia 分析的曲数
        val semanticTagged: Int,          // 已经 LLM 标过的曲数
        val ruleSemanticTagged: Int,      // 规则版语义档案数（≥ semanticTagged）
        val topArtists: List<Pair<String, Int>>,        // 频次 top
        val topAlbums: List<Pair<String, Int>>,
        val languageDist: List<Pair<String, Float>>,    // 占比
        val regionDist: List<Pair<String, Float>>,
        val genreDist: List<Pair<String, Float>>,
        val moodDist: List<Pair<String, Float>>,
        val sceneDist: List<Pair<String, Float>>,
        val eraDist: List<Pair<String, Float>>,         // 1990s / 2000s / ...
        val bpmHist: BpmHistogram?,                     // 仅基于已分析曲
        val energyHist: EnergyHistogram?,
        val avgDynamicRangeDb: Double?,
        val behavior: BehaviorAggregate?,
    )

    data class BpmHistogram(
        val slow: Int,    // < 90
        val medium: Int,  // 90-130
        val fast: Int,    // 130+
        val avg: Double,
    )

    data class EnergyHistogram(
        val low: Int,
        val mid: Int,
        val high: Int,
        val avg: Double,
    )

    data class BehaviorAggregate(
        val totalEvents: Int,
        val completionRate: Float,
        val loveArtists: List<String>,
        val skipHotArtists: List<String>,
    )

    fun aggregate(
        library: List<NativeTrack>,
        featuresStore: AudioFeaturesStore,
        semanticStore: TrackSemanticStore,
        ruleSemanticIndexer: SemanticIndexer,    // 用它做规则版兜底标签
        behaviorSummary: BehaviorSummary?,
    ): Aggregate {
        val total = library.size
        if (total == 0) return EMPTY

        // ---- 艺人 / 专辑频次 ----
        val artistCounts = HashMap<String, Int>()
        val albumCounts = HashMap<String, Int>()
        for (t in library) {
            t.artist.split("/", "&", ",").forEach { a ->
                val name = a.trim()
                if (name.isNotBlank()) artistCounts[name] = (artistCounts[name] ?: 0) + 1
            }
            if (t.album.isNotBlank()) {
                albumCounts[t.album] = (albumCounts[t.album] ?: 0) + 1
            }
        }
        val topArtists = artistCounts.entries.sortedByDescending { it.value }.take(30)
            .map { it.key to it.value }
        val topAlbums = albumCounts.entries.sortedByDescending { it.value }.take(20)
            .map { it.key to it.value }

        // ---- 语义聚合：LLM 标过的优先用 LLM 标签，没标过的临时跑规则版 ----
        // 注意规则版 buildRuleBasedProfile 是纯本地启发式，零 AI 调用。
        var semanticTagged = 0
        var ruleSemanticTagged = 0
        val langCounts = HashMap<String, Int>()
        val regionCounts = HashMap<String, Int>()
        val genreCounts = HashMap<String, Int>()
        val moodCounts = HashMap<String, Int>()
        val sceneCounts = HashMap<String, Int>()
        val eraCounts = HashMap<String, Int>()
        for (t in library) {
            val cached = semanticStore.get(t.id)
            val profile = if (cached != null && cached.sourceLlm) {
                semanticTagged++
                cached
            } else {
                ruleSemanticTagged++
                cached ?: ruleSemanticIndexer.buildRuleBasedProfile(t, featuresStore.get(t.id))
            }
            langCounts[profile.language.key] = (langCounts[profile.language.key] ?: 0) + 1
            regionCounts[profile.region.key] = (regionCounts[profile.region.key] ?: 0) + 1
            profile.genres.forEach { g -> genreCounts[g] = (genreCounts[g] ?: 0) + 1 }
            profile.moods.forEach { m -> moodCounts[m] = (moodCounts[m] ?: 0) + 1 }
            profile.scenes.forEach { s -> sceneCounts[s] = (sceneCounts[s] ?: 0) + 1 }
            profile.decade?.takeIf { it != "unknown" }?.let { d ->
                eraCounts[d] = (eraCounts[d] ?: 0) + 1
            }
        }
        ruleSemanticTagged += semanticTagged

        // ---- 音频指纹聚合（仅已分析曲）----
        var analyzed = 0
        var bpmSum = 0.0; var bpmCount = 0
        var slowBpm = 0; var medBpm = 0; var fastBpm = 0
        var energySum = 0.0; var energyCount = 0
        var lowE = 0; var midE = 0; var highE = 0
        var drSum = 0.0; var drCount = 0
        for (t in library) {
            val f = featuresStore.get(t.id) ?: continue
            analyzed++
            f.bpm?.let { bpm ->
                bpmSum += bpm; bpmCount++
                when {
                    bpm < 90 -> slowBpm++
                    bpm < 130 -> medBpm++
                    else -> fastBpm++
                }
            }
            val eMid = (f.introEnergy + f.outroEnergy) / 2.0
            energySum += eMid; energyCount++
            when {
                eMid < 0.3 -> lowE++
                eMid < 0.6 -> midE++
                else -> highE++
            }
            drSum += f.dynamicRangeDb; drCount++
        }
        val bpmHist = if (bpmCount >= 20) BpmHistogram(slowBpm, medBpm, fastBpm, bpmSum / bpmCount) else null
        val energyHist = if (energyCount >= 20) EnergyHistogram(lowE, midE, highE, energySum / energyCount) else null
        val avgDr = if (drCount >= 20) drSum / drCount else null

        // ---- 行为聚合 ----
        val behavior = behaviorSummary?.takeIf { it.total >= 10 }?.let {
            BehaviorAggregate(
                totalEvents = it.total,
                completionRate = it.completionRate,
                loveArtists = it.loveArtists.take(8),
                skipHotArtists = it.skipHotArtists.take(8),
            )
        }

        return Aggregate(
            totalTracks = total,
            analyzedTracks = analyzed,
            semanticTagged = semanticTagged,
            ruleSemanticTagged = ruleSemanticTagged,
            topArtists = topArtists,
            topAlbums = topAlbums,
            languageDist = ratios(langCounts, total),
            regionDist = ratios(regionCounts, total),
            genreDist = ratios(genreCounts, total).take(10),
            moodDist = ratios(moodCounts, total).take(10),
            sceneDist = ratios(sceneCounts, total).take(10),
            eraDist = ratios(eraCounts, total).take(8),
            bpmHist = bpmHist,
            energyHist = energyHist,
            avgDynamicRangeDb = avgDr,
            behavior = behavior,
        )
    }

    private fun ratios(counts: Map<String, Int>, total: Int): List<Pair<String, Float>> {
        if (total == 0) return emptyList()
        return counts.entries.sortedByDescending { it.value }
            .map { it.key to it.value.toFloat() / total }
    }

    /** 写成给 AI prompt 用的 Markdown-ish block。AI 看这个就知道全库长什么样。 */
    fun toPromptBlock(a: Aggregate): String = buildString {
        append("# 你的全库（${a.totalTracks} 首）\n")

        if (a.topArtists.isNotEmpty()) {
            append("\n## Top 艺人（频次降序）\n")
            append(a.topArtists.take(20).joinToString("、") { "${it.first}(${it.second})" })
            append("\n")
        }

        if (a.languageDist.isNotEmpty()) {
            append("\n## 语言分布\n")
            append(a.languageDist.take(5).joinToString("、") { "${it.first} ${pct(it.second)}" })
            append("\n")
        }
        if (a.regionDist.isNotEmpty()) {
            append("\n## 地区分布\n")
            append(a.regionDist.take(5).joinToString("、") { "${it.first} ${pct(it.second)}" })
            append("\n")
        }
        if (a.genreDist.isNotEmpty()) {
            append("\n## 流派分布（推断 +/- 用户实际听）\n")
            append(a.genreDist.joinToString("、") { "${it.first} ${pct(it.second)}" })
            append("\n")
            if (a.semanticTagged > 0) {
                append("（其中 ${a.semanticTagged} 首 LLM 精标过；剩余 ${a.totalTracks - a.semanticTagged} 首走元数据规则推断，可能粗）\n")
            }
        }
        if (a.moodDist.isNotEmpty()) {
            append("\n## 情绪标签分布\n")
            append(a.moodDist.joinToString("、") { "${it.first} ${pct(it.second)}" })
            append("\n")
        }
        if (a.sceneDist.isNotEmpty()) {
            append("\n## 场景标签分布\n")
            append(a.sceneDist.joinToString("、") { "${it.first} ${pct(it.second)}" })
            append("\n")
        }
        if (a.eraDist.isNotEmpty()) {
            append("\n## 年代分布\n")
            append(a.eraDist.joinToString("、") { "${it.first} ${pct(it.second)}" })
            append("\n")
        }

        if (a.bpmHist != null) {
            append("\n## BPM 分布（${a.analyzedTracks}/${a.totalTracks} 首已分析）\n")
            append("慢 (<90) ${a.bpmHist.slow}、中 (90-130) ${a.bpmHist.medium}、")
            append("快 (130+) ${a.bpmHist.fast}、平均 ${a.bpmHist.avg.toInt()}\n")
        }
        if (a.energyHist != null) {
            append("\n## 能量分布\n")
            append("低 ${a.energyHist.low}、中 ${a.energyHist.mid}、高 ${a.energyHist.high}、")
            append("平均 ${"%.2f".format(a.energyHist.avg)}\n")
        }
        if (a.avgDynamicRangeDb != null) {
            append("\n## 平均动态范围 ${"%.1f".format(a.avgDynamicRangeDb)} dB\n")
        }

        if (a.behavior != null) {
            append("\n## 听歌行为（最近 ${a.behavior.totalEvents} 条）\n")
            append("完成率 ${(a.behavior.completionRate * 100).toInt()}%。\n")
            if (a.behavior.loveArtists.isNotEmpty()) {
                append("反复完整听过：${a.behavior.loveArtists.joinToString("、")}\n")
            }
            if (a.behavior.skipHotArtists.isNotEmpty()) {
                append("反复跳过：${a.behavior.skipHotArtists.joinToString("、")}\n")
            }
        }

        append("\n→ 以上分布覆盖整个 ${a.totalTracks} 首库。下面 sample 只是给 AI 闻味用，画像必须基于这一段统计。\n")
    }

    private fun pct(v: Float): String = "${(v * 100).toInt()}%"

    private val EMPTY = Aggregate(
        totalTracks = 0, analyzedTracks = 0, semanticTagged = 0, ruleSemanticTagged = 0,
        topArtists = emptyList(), topAlbums = emptyList(),
        languageDist = emptyList(), regionDist = emptyList(),
        genreDist = emptyList(), moodDist = emptyList(),
        sceneDist = emptyList(), eraDist = emptyList(),
        bpmHist = null, energyHist = null, avgDynamicRangeDb = null,
        behavior = null,
    )
}
