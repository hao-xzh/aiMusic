package app.pipo.nativeapp.data

/**
 * 平滑歌单排序 —— 镜像 src/lib/smooth-queue.ts。
 *
 * 贪心局部排序：固定起点，从剩余里挑最适合 AutoMix 的下一首。
 *
 * 排序目标不是做 DJ set，而是竞品式智能过渡：优先把 BPM / 能量 / 头尾边界更能混的
 * 歌排到一起；没有足够分析数据时保留原顺序，避免盲排。
 * 分析覆盖 < 3 首时直接原顺序返回（盲排比不排还差）。
 */
object SmoothQueue {

    enum class Mode { Discovery, Library }

    fun smooth(
        tracks: List<NativeTrack>,
        featuresStore: AudioFeaturesStore,
        startTrackId: String? = null,
        mode: Mode = Mode.Library,
        force: Boolean = false,
    ): List<NativeTrack> {
        if (tracks.size <= 2) return tracks

        data class Enriched(val track: NativeTrack, val features: AudioFeatures?, val originalIndex: Int)
        val enriched = tracks.mapIndexed { i, t -> Enriched(t, featuresStore.get(t.id), i) }
        val analyzed = enriched.count { it.features != null }
        if (!force && analyzed < 3) return tracks

        val startIndex = if (startTrackId != null) {
            enriched.indexOfFirst { it.track.id == startTrackId }.coerceAtLeast(0)
        } else 0

        val ordered = mutableListOf(enriched[startIndex])
        val remaining = enriched.toMutableList().apply { removeAt(startIndex) }
        val sameArtistPenalty = when (mode) { Mode.Discovery, Mode.Library -> 0.0 }

        while (remaining.isNotEmpty()) {
            val current = ordered.last()
            var bestIdx = 0
            var bestScore = Double.POSITIVE_INFINITY
            for (i in remaining.indices) {
                val cand = remaining[i]
                val fit = TransitionScore.fitScore(
                    TransitionScore.Scored(current.track, current.features),
                    TransitionScore.Scored(cand.track, cand.features),
                )
                val risk = TransitionScore.risk(
                    TransitionScore.Scored(current.track, current.features),
                    TransitionScore.Scored(cand.track, cand.features),
                    sameArtistPenalty,
                )
                val autoMixReady = autoMixReadiness(current.features, cand.features, fit)
                val drift = TransitionScore.orderDriftPenalty(current.originalIndex, cand.originalIndex, tracks.size)
                val score = risk * 1.05 -
                    fit.score * 1.05 -
                    autoMixReady * 0.85 +
                    drift * 0.18
                if (score < bestScore) { bestScore = score; bestIdx = i }
            }
            ordered.add(remaining.removeAt(bestIdx))
        }
        return ordered.map { it.track }
    }

    private fun autoMixReadiness(
        from: AudioFeatures?,
        to: AudioFeatures?,
        fit: TransitionScore.FitScore,
    ): Double {
        if (from == null || to == null) return 0.0
        if (fit.style == TransitionScore.TransitionStyle.SilenceBreath) return 0.0
        val bpmA = from.bpm
        val bpmB = to.bpm
        val bpmReliable = bpmA != null && bpmB != null &&
            from.bpmConfidence >= 0.32 &&
            to.bpmConfidence >= 0.32
        val bpmDelta = if (bpmReliable) kotlin.math.abs(bpmA!! - bpmB!!) else 10.0
        val energyDelta = kotlin.math.abs(from.outroEnergy - to.introEnergy)
        val boundaryScore = when {
            from.tailSilenceS <= 0.45 && to.headSilenceS <= 0.45 -> 0.25
            from.tailSilenceS <= 0.8 || to.headSilenceS <= 0.8 -> 0.12
            else -> 0.0
        }
        val bpmScore = if (bpmReliable) (1.0 - (bpmDelta / 8.0)).coerceIn(0.0, 1.0) * 0.35 else 0.08
        val energyScore = (1.0 - (energyDelta / 0.26)).coerceIn(0.0, 1.0) * 0.25
        return (fit.score * 0.15 + bpmScore + energyScore + boundaryScore).coerceIn(0.0, 1.0)
    }
}
