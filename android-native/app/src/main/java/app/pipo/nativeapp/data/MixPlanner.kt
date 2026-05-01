package app.pipo.nativeapp.data

/**
 * 接歌混音计划 —— 镜像 src/lib/mix-planner.ts 的核心逻辑。
 *
 * 输出给 ExoPlayer:
 *   - clipping: 当前曲尾要切多少（避开静音 / 副歌 outro 起点之前）
 *   - clippingNext: 下一曲头要切多少（避开静音 / 等到主奏出来）
 *   - crossfadeMs: 重叠多久淡入淡出（0 = hard cut；>0 = 实际淡 X 毫秒）
 *
 * 实际 ExoPlayer 1.5 的 gapless 是基于 MediaItem.ClippingConfiguration + LoadControl
 * 的预缓冲，**没有原生 crossfade**。crossfadeMs 这个数字仅供 PlayerViewModel
 * 决定"是否在 next 临近时降 volume + 提前 prepare 下一首"。
 */
data class MixPlan(
    val style: TransitionStyle,
    val crossfadeMs: Long,
    /** 当前曲尾要切的 ms（默认从 features.tailSilence 拿） */
    val clippingTailMs: Long,
    /** 下一曲头要切的 ms（默认从 features.headSilence 拿） */
    val clippingNextHeadMs: Long,
    val eqDuck: Boolean,
    val rationale: String,
)

object MixPlanner {
    fun plan(
        judgment: TransitionJudgment,
        fromFeatures: AudioFeatures?,
        toFeatures: AudioFeatures?,
    ): MixPlan {
        val tailSilence = ((fromFeatures?.tailSilenceS ?: 0.0) * 1000).toLong()
        val headSilence = ((toFeatures?.headSilenceS ?: 0.0) * 1000).toLong()

        // crossfade 长度直接来自 judgment；hard cut 强制 0
        val cross = when (judgment.style) {
            TransitionStyle.HardCut -> 0L
            else -> judgment.durationMs
        }

        return MixPlan(
            style = judgment.style,
            crossfadeMs = cross,
            clippingTailMs = tailSilence,
            clippingNextHeadMs = headSilence,
            eqDuck = judgment.eqDuck,
            rationale = judgment.rationale,
        )
    }
}
