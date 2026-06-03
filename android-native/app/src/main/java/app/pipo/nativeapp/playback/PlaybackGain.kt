package app.pipo.nativeapp.playback

import kotlin.math.pow

/**
 * Per-player 响度对齐增益(loudness normalization)。
 *
 * Service 在换曲时按"当前轨整曲 rmsDb"算好衰减增益写入,[LoudnessGainProcessor] 在 audio
 * 渲染线程读取并缩放 PCM。采用**衰减式**(gainLinear ≤ 1.0):只把更响的歌压到统一目标响度,
 * 从不放大 → 零削顶风险。镜像 Apple Sound Check 的思路,消除"下一首突然更大声"的突兀感。
 *
 * 与 SmartAutoMixer 的 `player.volume` 淡变**正交**:一个在 PCM 链、一个在 player 标量,
 * 互不干扰 —— 因此双 player crossfade 时各自的响度归一不会被等功率淡变曲线破坏。
 *
 * 每个 ExoPlayer 实例配一个独立 PlaybackGain(阶段2 双 player 各持各的)。
 */
class PlaybackGain {
    @Volatile
    var gainLinear: Float = 1f
        private set

    /** 直接设衰减增益(dB,≤0);正值会被钳到 0(不放大)。 */
    fun setTrackGainDb(db: Float) {
        val clamped = db.coerceIn(MAX_CUT_DB, 0f)
        gainLinear = 10.0.pow(clamped / 20.0).toFloat().coerceIn(MIN_LINEAR, 1f)
    }

    /** 按当前轨整曲 rmsDb 设增益;null(如 transition clip 已内部对齐)→ 中性 1.0。 */
    fun applyForRms(rmsDb: Double?) {
        setTrackGainDb(gainDbForRms(rmsDb))
    }

    /** 直接设线性增益(双 player crossfade 时,把上游算好的 next 响度增益设给辅助 player)。 */
    fun setLinear(linear: Float) {
        gainLinear = linear.coerceIn(MIN_LINEAR, 1f)
    }

    fun reset() {
        gainLinear = 1f
    }

    companion object {
        /** 目标响度(dBFS RMS)。取偏低值,保证基本只衰减不放大。 */
        const val TARGET_RMS_DB = -16.0

        /** 最大衰减量,避免个别超响母带把整体压得太死。 */
        const val MAX_CUT_DB = -12f

        private const val MIN_LINEAR = 0.05f

        /**
         * 整曲 rmsDb → 衰减增益(dB,≤0)。rmsDb 越大(越响)衰减越多;偏轻的歌返回 0(不放大)。
         * rmsDb 是 dBFS,正常为负值;异常值(null/NaN/≥0)按不处理返回 0。
         */
        fun gainDbForRms(rmsDb: Double?): Float {
            if (rmsDb == null || rmsDb.isNaN() || rmsDb >= 0.0) return 0f
            return (TARGET_RMS_DB - rmsDb).coerceAtMost(0.0).toFloat()
        }

        /** 整曲 rmsDb → 线性增益(≤1.0),供 transition clip 渲染按响度对齐缩放两轨。 */
        fun linearForRms(rmsDb: Double?): Float {
            return 10.0.pow(gainDbForRms(rmsDb) / 20.0).toFloat().coerceIn(MIN_LINEAR, 1f)
        }
    }
}
