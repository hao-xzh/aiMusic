package app.pipo.nativeapp.data

/**
 * 平滑歌单排序 —— 镜像 src/lib/smooth-queue.ts。
 *
 * 贪心局部排序：固定起点，从剩余里挑接续风险 + 顺序漂移惩罚最低的接上。
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
                val score = TransitionScore.risk(
                    TransitionScore.Scored(current.track, current.features),
                    TransitionScore.Scored(cand.track, cand.features),
                    sameArtistPenalty,
                ) * 1.35 - fit.score * 0.55 +
                    TransitionScore.orderDriftPenalty(current.originalIndex, cand.originalIndex, tracks.size) * 0.35
                if (score < bestScore) { bestScore = score; bestIdx = i }
            }
            ordered.add(remaining.removeAt(bestIdx))
        }
        return ordered.map { it.track }
    }
}
