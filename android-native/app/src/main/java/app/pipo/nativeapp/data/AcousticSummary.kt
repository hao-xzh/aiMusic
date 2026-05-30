package app.pipo.nativeapp.data

/**
 * 声学指纹聚合。
 */
data class AcousticMetrics(
    val bpmMedian: Double?,
    val bpmMean: Double?,
    val rmsDbMean: Double,
    val centroidMean: Double,
)

data class AcousticSummary(
    val analyzed: Int,
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

        val rmsDbMean = valid.map { it.rmsDb }.average()
        val centroidMean = valid.map { it.spectralCentroidHz }.average()

        val metrics = AcousticMetrics(
            bpmMedian = bpmMedian,
            bpmMean = bpmMean,
            rmsDbMean = rmsDbMean,
            centroidMean = centroidMean,
        )

        return AcousticSummary(
            analyzed = valid.size,
            metrics = metrics,
        )
    }

    private fun empty() = AcousticSummary(
        analyzed = 0,
        metrics = AcousticMetrics(
            bpmMedian = null,
            bpmMean = null,
            rmsDbMean = 0.0,
            centroidMean = 0.0,
        ),
    )

    private fun median(xs: List<Double>): Double {
        val sorted = xs.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }
}
