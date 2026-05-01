package app.pipo.nativeapp.data

/**
 * 接歌美学判断 —— 镜像 src/lib/transition-judge.ts 的本地分支。
 *
 * 不再请求 AI（播放链路要求零 AI 调用）；纯靠 audio features 给出 4 种风格：
 *   - hard_cut: 同专辑边界干净 → 0ms
 *   - silence_breath: 能量 / 人声反差大 → 900ms 短停顿
 *   - tight: BPM 接近 → 3200ms + EQ ducking
 *   - soft (默认): 慢溶 3600-4800ms
 */
enum class TransitionStyle { Soft, Tight, HardCut, SilenceBreath }

data class TransitionJudgment(
    val style: TransitionStyle,
    val durationMs: Long,
    val eqDuck: Boolean,
    val rationale: String,
) {
    companion object {
        val Default = TransitionJudgment(
            style = TransitionStyle.Soft,
            durationMs = 4_000L,
            eqDuck = false,
            rationale = "默认慢溶",
        )
    }
}

object TransitionJudge {
    /**
     * @param fromAlbum 上一首专辑（用来判断同专辑无缝接）
     * @param toAlbum 下一首专辑
     * @param fromFeatures 上一首 Symphonia 特征
     * @param toFeatures 下一首 Symphonia 特征
     */
    fun judge(
        fromAlbum: String?,
        toAlbum: String?,
        fromFeatures: AudioFeatures?,
        toFeatures: AudioFeatures?,
    ): TransitionJudgment {
        if (fromFeatures == null || toFeatures == null) return TransitionJudgment.Default

        val sameAlbum = !fromAlbum.isNullOrBlank() && !toAlbum.isNullOrBlank() &&
            fromAlbum.trim().equals(toAlbum.trim(), ignoreCase = true)

        val energyDelta = kotlin.math.abs(fromFeatures.outroEnergy - toFeatures.introEnergy)

        val bpmDelta = if (fromFeatures.bpm != null && toFeatures.bpm != null) {
            kotlin.math.abs(fromFeatures.bpm - toFeatures.bpm)
        } else Double.POSITIVE_INFINITY

        val bpmReliable = fromFeatures.bpm != null && toFeatures.bpm != null &&
            fromFeatures.bpmConfidence > 0.25 &&
            toFeatures.bpmConfidence > 0.25

        // 同专辑且边界干净 → hard cut
        if (sameAlbum && energyDelta <= 0.24) {
            return TransitionJudgment(
                style = TransitionStyle.HardCut,
                durationMs = 0L,
                eqDuck = false,
                rationale = "同专辑边界干净",
            )
        }
        // 能量反差大 → 短停顿呼吸
        if (energyDelta >= 0.34) {
            return TransitionJudgment(
                style = TransitionStyle.SilenceBreath,
                durationMs = 900L,
                eqDuck = false,
                rationale = "能量反差大",
            )
        }
        // BPM 接近 → tight + EQ duck
        if (bpmReliable && bpmDelta <= 7 && energyDelta <= 0.22) {
            return TransitionJudgment(
                style = TransitionStyle.Tight,
                durationMs = 3_200L,
                eqDuck = true,
                rationale = "BPM 接近 ${bpmDelta.toInt()}",
            )
        }
        // 默认慢溶
        return TransitionJudgment(
            style = TransitionStyle.Soft,
            durationMs = if (energyDelta <= 0.16) 4_800L else 3_600L,
            eqDuck = false,
            rationale = "默认慢溶",
        )
    }
}
