package app.pipo.nativeapp.data

/**
 * 节拍 / 小节 / phrase 边界工具 —— 镜像 src/lib/beat-grid.ts。
 *
 * Android `AudioFeatures` 当前没有 `firstBeatS` 字段；在它就位前，
 * 调用方应传 0.0（等价于"以 0 为基准对齐"），听感仍然比无对齐好。
 */
object BeatGrid {
    fun beatLengthS(bpm: Double): Double = 60.0 / bpm
    fun phraseLengthS(bpm: Double, phraseBeats: Int = 16): Double = beatLengthS(bpm) * phraseBeats

    private fun valid(timeS: Double, firstBeatS: Double, bpm: Double): Boolean =
        timeS.isFinite() && firstBeatS.isFinite() && bpm.isFinite() && bpm > 0

    fun nearestBeatBoundary(timeS: Double, firstBeatS: Double, bpm: Double, phraseBeats: Int = 16): Double {
        if (!valid(timeS, firstBeatS, bpm)) return timeS
        val phraseS = phraseLengthS(bpm, phraseBeats)
        val n = kotlin.math.round((timeS - firstBeatS) / phraseS)
        return firstBeatS + n * phraseS
    }

    fun previousBeatBoundary(timeS: Double, firstBeatS: Double, bpm: Double, phraseBeats: Int = 16): Double {
        if (!valid(timeS, firstBeatS, bpm)) return timeS
        val phraseS = phraseLengthS(bpm, phraseBeats)
        val n = kotlin.math.floor((timeS - firstBeatS) / phraseS)
        return firstBeatS + n * phraseS
    }

    fun nextBeatBoundary(timeS: Double, firstBeatS: Double, bpm: Double, phraseBeats: Int = 16): Double {
        if (!valid(timeS, firstBeatS, bpm)) return timeS
        val phraseS = phraseLengthS(bpm, phraseBeats)
        val n = kotlin.math.ceil((timeS - firstBeatS) / phraseS)
        return firstBeatS + n * phraseS
    }
}
