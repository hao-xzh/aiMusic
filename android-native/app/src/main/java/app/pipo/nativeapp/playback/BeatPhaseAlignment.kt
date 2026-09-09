package app.pipo.nativeapp.playback

import app.pipo.nativeapp.data.AudioFeatures
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/** 源音频时间上的拍网格；只用于可信分析，缺少首拍或需要大幅变速时保留普通淡变。 */
internal data class BeatPhaseAlignment private constructor(
    val currentFirstBeatMs: Double,
    val currentBeatMs: Double,
    val nextFirstBeatMs: Double,
    val nextBeatMs: Double,
) {
    val nextTempoRatio: Float get() = (nextBeatMs / currentBeatMs).toFloat()

    /**
     * 不移动下一首的入口（尤其不跳过人声起音），而把开播时刻放到上一首的同一拍相位。
     * 入口可以在首拍之前：负拍数按 floor 取相位，仍保留完整的弱起/前奏。
     */
    fun startPositionMs(notBeforeSourceMs: Long, nextEntrySourceMs: Long): Long {
        val nextPhase = fractionalBeat((nextEntrySourceMs - nextFirstBeatMs) / nextBeatMs)
        val currentBeat = (notBeforeSourceMs - currentFirstBeatMs) / currentBeatMs
        val cycle = ceil(currentBeat - nextPhase)
        return (currentFirstBeatMs + (cycle + nextPhase) * currentBeatMs).roundToLong()
    }

    /** 正值表示下一首拍点超前；单位是实际播放毫秒，而不是原始音频毫秒。 */
    fun phaseErrorMs(currentSourceMs: Long, nextSourceMs: Long, currentSpeed: Float): Double {
        val currentBeat = (currentSourceMs - currentFirstBeatMs) / currentBeatMs
        val nextBeat = (nextSourceMs - nextFirstBeatMs) / nextBeatMs
        val delta = nextBeat - currentBeat
        val wrapped = delta - floor(delta + 0.5)
        return wrapped * currentBeatMs / currentSpeed.coerceAtLeast(0.1f)
    }

    private fun fractionalBeat(value: Double): Double = value - floor(value)

    companion object {
        fun from(current: AudioFeatures?, next: AudioFeatures?): BeatPhaseAlignment? {
            val a = current ?: return null
            val b = next ?: return null
            val bpmA = a.bpm?.takeIf { it.isFinite() && it in 45.0..220.0 } ?: return null
            val bpmB = b.bpm?.takeIf { it.isFinite() && it in 45.0..220.0 } ?: return null
            if (!a.bpmConfidence.isFinite() || !b.bpmConfidence.isFinite() ||
                a.bpmConfidence < MIN_CONFIDENCE || b.bpmConfidence < MIN_CONFIDENCE
            ) return null
            val firstA = a.firstBeatS?.takeIf { it.isFinite() && it >= 0.0 && it < a.durationS }
                ?: return null
            val firstB = b.firstBeatS?.takeIf { it.isFinite() && it >= 0.0 && it < b.durationS }
                ?: return null
            // 超过温和变速范围就不宣称对拍，不能钳住速度后仍假定拍点不会漂移。
            if (bpmA / bpmB !in MIN_TEMPO_RATIO..MAX_TEMPO_RATIO) return null
            return BeatPhaseAlignment(firstA * 1000.0, 60_000.0 / bpmA, firstB * 1000.0, 60_000.0 / bpmB)
        }

        const val MIN_TEMPO_RATIO = 0.965
        const val MAX_TEMPO_RATIO = 1.035
        private const val MIN_CONFIDENCE = 0.45
    }
}
