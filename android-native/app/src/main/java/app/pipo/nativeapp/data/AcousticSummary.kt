package app.pipo.nativeapp.data

import kotlin.math.roundToInt

/**
 * 声学指纹聚合 —— 镜像 src/lib/acoustic-summary.ts。
 *
 * 把一组曲目的 AudioFeatures（BPM / 响度 / 动态范围 / 谱重心 / 头尾静默）压成
 * 几行人话喂给 distill 的 AI prompt，让画像反映用户的"物理音乐口味"。
 */
data class AcousticMetrics(
    val bpmMedian: Double?,
    val bpmMean: Double?,
    val bpmDistribution: Triple<Float, Float, Float>,    // slow / mid / fast 比例
    val rmsDbMean: Double,
    val dynamicRangeDbMean: Double,
    val centroidMean: Double,
    val centroidDistribution: Triple<Float, Float, Float>, // dark / neutral / bright 比例
    val headSilenceMean: Double,
    val tailSilenceMean: Double,
)

data class AcousticSummary(
    val analyzed: Int,
    val promptBlock: String,
    val metrics: AcousticMetrics,
)

object AcousticSummarizer {

    fun summarize(features: List<AudioFeatures?>): AcousticSummary {
        val valid = features.filterNotNull()
        if (valid.isEmpty()) return empty()

        // BPM 仅看 confidence > 0.3 的有效项
        val bpms = valid.filter { it.bpm != null && it.bpmConfidence > 0.3 }.mapNotNull { it.bpm }
        val bpmMedian = if (bpms.isNotEmpty()) median(bpms) else null
        val bpmMean = if (bpms.isNotEmpty()) bpms.average() else null
        val slow = bpms.count { it < 90.0 }
        val mid = bpms.count { it in 90.0..130.0 }
        val fast = bpms.count { it > 130.0 }
        val bpmDist = if (bpms.isEmpty()) Triple(0f, 0f, 0f) else Triple(
            slow.toFloat() / bpms.size,
            mid.toFloat() / bpms.size,
            fast.toFloat() / bpms.size,
        )

        val rmsDbMean = valid.map { it.rmsDb }.average()
        val dynamicRangeMean = valid.map { it.dynamicRangeDb }.average()

        val centroids = valid.map { it.spectralCentroidHz }
        val centroidMean = centroids.average()
        val dark = centroids.count { it < 1500.0 }
        val bright = centroids.count { it > 3000.0 }
        val neutral = centroids.size - dark - bright
        val centroidDist = Triple(
            dark.toFloat() / centroids.size,
            neutral.toFloat() / centroids.size,
            bright.toFloat() / centroids.size,
        )

        val headSilenceMean = valid.map { it.headSilenceS }.average()
        val tailSilenceMean = valid.map { it.tailSilenceS }.average()

        val metrics = AcousticMetrics(
            bpmMedian = bpmMedian,
            bpmMean = bpmMean,
            bpmDistribution = bpmDist,
            rmsDbMean = rmsDbMean,
            dynamicRangeDbMean = dynamicRangeMean,
            centroidMean = centroidMean,
            centroidDistribution = centroidDist,
            headSilenceMean = headSilenceMean,
            tailSilenceMean = tailSilenceMean,
        )

        val lines = mutableListOf<String>()
        lines.add("声学指纹（基于 ${valid.size} 首已分析曲目）：")
        if (bpmMedian != null) {
            lines.add(
                "- BPM 中位 ${bpmMedian.roundToInt()}，分布 慢(<90) ${pct(bpmDist.first)} " +
                    "/ 中(90-130) ${pct(bpmDist.second)} / 快(>130) ${pct(bpmDist.third)}"
            )
        } else {
            lines.add("- BPM：大部分曲目检测置信度低（可能是 ambient / 民谣 / 抽象类）")
        }
        lines.add(
            "- 平均响度 ${"%.1f".format(rmsDbMean)} dBFS，" +
                "动态范围 ${"%.1f".format(dynamicRangeMean)} dB（${dynamicLabel(dynamicRangeMean)}）"
        )
        lines.add(
            "- 音色：暗(<1.5kHz) ${pct(centroidDist.first)} " +
                "/ 中 ${pct(centroidDist.second)} / 亮(>3kHz) ${pct(centroidDist.third)} " +
                "· 谱重心均值 ${"%.1f".format(centroidMean / 1000)}kHz"
        )
        if (headSilenceMean > 0.5 || tailSilenceMean > 0.5) {
            val tail = if (headSilenceMean > 1.5) "（普遍带 fade-in）" else ""
            lines.add(
                "- 头/尾静默均值 ${"%.1f".format(headSilenceMean)}s / " +
                    "${"%.1f".format(tailSilenceMean)}s$tail"
            )
        }

        return AcousticSummary(
            analyzed = valid.size,
            promptBlock = lines.joinToString("\n"),
            metrics = metrics,
        )
    }

    private fun empty() = AcousticSummary(
        analyzed = 0,
        promptBlock = "",
        metrics = AcousticMetrics(
            bpmMedian = null,
            bpmMean = null,
            bpmDistribution = Triple(0f, 0f, 0f),
            rmsDbMean = 0.0,
            dynamicRangeDbMean = 0.0,
            centroidMean = 0.0,
            centroidDistribution = Triple(0f, 0f, 0f),
            headSilenceMean = 0.0,
            tailSilenceMean = 0.0,
        ),
    )

    private fun median(xs: List<Double>): Double {
        val sorted = xs.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun pct(x: Float): String = "${(x * 100f).roundToInt()}%"

    private fun dynamicLabel(dr: Double): String = when {
        dr < 6 -> "高度压缩，主流商业流行"
        dr < 10 -> "轻度压缩，主流流行/独立"
        dr < 14 -> "保留动态，独立/民谣常见"
        else -> "动态宽广，古典/原声/氛围常见"
    }
}
