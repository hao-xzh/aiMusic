package app.pipo.nativeapp.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.playback.orchestrator.TransitionMode
import app.pipo.nativeapp.playback.orchestrator.TransitionResult
import kotlin.math.cos
import kotlin.math.sin

/**
 * 实时双 player crossfade(阶段2,**seek 接管版**)。对标 Apple Music:对该叠加的歌对做真·叠加,
 * 而不是顺序淡变(响度凹陷)。
 *
 * - [mainPlayer](A):MediaSession 播放权威,持完整队列与 AudioFocus,复用现有全部 listener。
 *   crossfade 期间播当前曲尾(等功率淡出)。
 * - [auxPlayer](B):临时淡入出声器(handleAudioFocus=false),播下一首头段。
 *
 * **接管用 `seekTo`,不用 gapless**:crossfade 结束时 A 主动 `seekTo` 到接续点(B 播到的位置)、停 B。
 * 这是现有 main-only 验证过、不会重播的方式 —— 旧的"`replaceMediaItem(裁剪版)` + 等 A gapless 自然
 * 进入"在真机上会因 gapless 预加载残留让下一首开头被多播 ~160ms(用户报告的"同一句听到两遍")。
 * seek 强制定位,无残留,代价是接管处可能有极短 buffer(下一首深位置)——用 prewarm 缓解。
 */
@UnstableApi
internal class CrossfadeController(
    private val mainPlayer: ExoPlayer,
    private val auxPlayer: ExoPlayer,
    private val auxGain: PlaybackGain,
    private val onResult: (TransitionResult) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private var active: Active? = null

    val isRunning: Boolean get() = active != null
    val hasActiveAuxPlayback: Boolean
        get() = auxPlayer.isPlaying

    private class Active(
        val pairKey: String,
        val queueVersion: Long,
        val nextId: String,
        val nextIndex: Int,
        val nextStartMs: Long,
        val resumePositionMs: Long,
        val crossfadeMs: Long,
        val startedAtMs: Long,
        /** 主队列里 next 条目的头裁剪量（源坐标）：接管 seek 前必须换算，否则跳位。 */
        val nextItemClipStartMs: Long = 0L,
        var auxReadyDelayMs: Long? = null,
        var fadeStartedAtMs: Long? = null,
        var takingOver: Boolean = false,
    )

    private val mainListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val a = active ?: return
            if (mediaItem?.mediaId == a.nextId) {
                // A 比 crossfade 早一步自然(gapless)进了 next(当前曲时长不准时会发生):
                // 立即接管 + seek 修正到接续点,把"从头多播"压到最小。
                takeover(a, "early-transition-r$reason")
            } else {
                cancel("transition-r$reason")
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (active != null && !playWhenReady) cancel("main-paused")
        }
    }

    /**
     * @param nextStartMs 下一首进入点(entryCue,跳过头静音/前奏后)
     * @param crossfadeMs 叠加时长(= 调用时当前曲剩余)
     * @param beatmatchSpeed 下一首变速比(对齐 BPM,Sonic 保音高);1f 不变速
     * @param nextGainLinear 下一首响度对齐线性增益(≤1)
     */
    fun start(
        nextMediaItem: MediaItem,
        nextIndex: Int,
        nextStartMs: Long,
        crossfadeMs: Long,
        beatmatchSpeed: Float,
        nextGainLinear: Float,
        queueVersion: Long,
        pairKey: String,
    ) {
        if (active != null || crossfadeMs <= 0L) return
        if (!PlaybackSessionClock.isCurrent(queueVersion)) {
            DiagnosticsLogStore.record(
                area = "transition",
                event = "stale_transition_cancel",
                fields = mapOf(
                    "pairKey" to pairKey,
                    "planQueueVersion" to queueVersion,
                    "currentQueueVersion" to PlaybackSessionClock.currentQueueVersion(),
                    "stage" to "crossfade_start",
                ),
            )
            return
        }
        val resumeMs = (nextStartMs + crossfadeMs).coerceAtLeast(0L)
        // B 播下一首 [nextStart, resume+pad] 头段(留点余量,避免接管前 B 提前 ended 静音)。
        val auxItem = nextMediaItem.buildUpon()
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(nextStartMs.coerceAtLeast(0L))
                    .setEndPositionMs(resumeMs + AUX_TAIL_PAD_MS)
                    .build(),
            )
            .build()
        val auxStarted = runCatching {
            auxGain.setLinear(nextGainLinear)
            auxPlayer.setPlaybackParameters(PlaybackParameters(beatmatchSpeed))
            auxPlayer.setMediaItem(auxItem)
            auxPlayer.volume = 0f
            auxPlayer.prepare()
            auxPlayer.playWhenReady = true
            true
        }.getOrDefault(false)
        if (!auxStarted) return

        mainPlayer.addListener(mainListener)
        active = Active(
            pairKey = pairKey,
            queueVersion = queueVersion,
            nextId = nextMediaItem.mediaId,
            nextIndex = nextIndex,
            nextStartMs = nextStartMs.coerceAtLeast(0L),
            resumePositionMs = resumeMs,
            crossfadeMs = crossfadeMs,
            startedAtMs = SystemClock.elapsedRealtime(),
            // nextMediaItem 是主队列里的原条目（带头裁剪）；aux 用 buildUpon 覆盖了
            // 裁剪所以播的是源坐标，主播放器接管时必须减回这个差值。
            nextItemClipStartMs = nextMediaItem.clippingConfiguration.startPositionMs.coerceAtLeast(0L),
        )
        DiagnosticsLogStore.record(
            area = "automix",
            event = "realtime_crossfade_start",
            fields = mapOf(
                "nextId" to nextMediaItem.mediaId,
                "pairKey" to pairKey,
                "queueVersion" to queueVersion,
                "crossfadeMs" to crossfadeMs,
                "nextStartMs" to nextStartMs,
                "resumeMs" to resumeMs,
                "beatmatchSpeed" to "%.4f".format(beatmatchSpeed),
                "nextGain" to "%.3f".format(nextGainLinear),
            ),
        )
        scheduleTick()
    }

    private val tickRunnable = Runnable { tick() }
    private fun scheduleTick() {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    private fun tick() {
        val a = active ?: return
        if (a.takingOver) return
        if (!PlaybackSessionClock.isCurrent(a.queueVersion)) {
            DiagnosticsLogStore.record(
                area = "transition",
                event = "stale_transition_cancel",
                fields = mapOf(
                    "pairKey" to a.pairKey,
                    "planQueueVersion" to a.queueVersion,
                    "currentQueueVersion" to PlaybackSessionClock.currentQueueVersion(),
                    "stage" to "crossfade_tick",
                ),
            )
            cancel("stale-transition-plan")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val fadeStartedAtMs = a.fadeStartedAtMs
        if (fadeStartedAtMs == null) {
            val auxReady = auxPlayer.playbackState == Player.STATE_READY &&
                (auxPlayer.isPlaying || auxPlayer.currentPosition > 0L || auxPlayer.playWhenReady)
            if (!auxReady) {
                if (now - a.startedAtMs >= AUX_READY_TIMEOUT_MS) {
                    cancel("aux-ready-timeout")
                    return
                }
                scheduleTick()
                return
            }
            a.auxReadyDelayMs = now - a.startedAtMs
            a.fadeStartedAtMs = now
            DiagnosticsLogStore.record(
                area = "automix",
                event = "realtime_crossfade_aux_ready",
                fields = mapOf(
                    "nextId" to a.nextId,
                    "pairKey" to a.pairKey,
                    "queueVersion" to a.queueVersion,
                    "auxReadyDelayMs" to a.auxReadyDelayMs,
                ),
            )
            scheduleTick()
            return
        }
        val elapsed = now - fadeStartedAtMs
        val p = (elapsed.toFloat() / a.crossfadeMs.toFloat()).coerceIn(0f, 1f)
        // 等功率:A(当前曲尾)淡出 cos,B(下一曲头)淡入 sin
        val theta = p * (Math.PI / 2.0)
        mainPlayer.volume = cos(theta).toFloat()
        auxPlayer.volume = sin(theta).toFloat()
        if (p >= 1f) {
            takeover(a, "complete")
            return
        }
        scheduleTick()
    }

    /** 接管:先让 A 静音 seek/ready,B 继续出声;A 接上后再断 B。 */
    private fun takeover(a: Active, reason: String) {
        if (a.takingOver) return
        a.takingOver = true
        handler.removeCallbacks(tickRunnable)
        val handoffStartedAtMs = SystemClock.elapsedRealtime()
        val actualResumeMs = actualResumePositionMs(a)
        // actualResumeMs 是源坐标（aux 覆盖裁剪后从源位置播）；主队列条目带头裁剪
        //（条目 0 点 = 源 clipStart），不换算就 seek 会让交接瞬间内容前跳 clipStart。
        val mainSeekMs = (actualResumeMs - a.nextItemClipStartMs).coerceAtLeast(0L)
        runCatching {
            mainPlayer.volume = 0f
            if (mainPlayer.currentMediaItem?.mediaId == a.nextId) {
                // A 已在 next(early-transition):同 item 内直接 seek 到接续点
                mainPlayer.seekTo(mainSeekMs)
            } else {
                // 常规:A 还在当前曲,主动跳到 next 的接续点(跳过当前曲剩余的极小尾巴)
                mainPlayer.seekTo(a.nextIndex, mainSeekMs)
            }
            if (mainPlayer.playbackState == Player.STATE_IDLE || mainPlayer.playbackState == Player.STATE_ENDED) {
                mainPlayer.prepare()
            }
            mainPlayer.play()
            waitForMainReadyThenFinish(a, reason, actualResumeMs, handoffStartedAtMs)
        }.onFailure { err ->
            finishHandoff(
                a = a,
                reason = reason,
                actualResumeMs = actualResumeMs,
                handoffStartedAtMs = handoffStartedAtMs,
                success = false,
                failureReason = "main-seek-failed:${err::class.java.simpleName}",
            )
        }
    }

    private fun waitForMainReadyThenFinish(
        a: Active,
        reason: String,
        actualResumeMs: Long,
        handoffStartedAtMs: Long,
    ) {
        if (active !== a) return
        val now = SystemClock.elapsedRealtime()
        val ready = mainPlayer.currentMediaItem?.mediaId == a.nextId &&
            mainPlayer.playbackState == Player.STATE_READY
        if (!ready && now - handoffStartedAtMs < HANDOFF_READY_TIMEOUT_MS) {
            handler.postDelayed(
                { waitForMainReadyThenFinish(a, reason, actualResumeMs, handoffStartedAtMs) },
                TICK_MS,
            )
            return
        }
        fadeMainInAuxOut(
            a = a,
            reason = reason,
            actualResumeMs = actualResumeMs,
            handoffStartedAtMs = handoffStartedAtMs,
            success = ready,
            fadeStartedAtMs = now,
        )
    }

    private fun fadeMainInAuxOut(
        a: Active,
        reason: String,
        actualResumeMs: Long,
        handoffStartedAtMs: Long,
        success: Boolean,
        fadeStartedAtMs: Long,
    ) {
        if (active !== a) return
        val elapsed = SystemClock.elapsedRealtime() - fadeStartedAtMs
        val p = (elapsed.toFloat() / HANDOFF_FADE_MS.toFloat()).coerceIn(0f, 1f)
        mainPlayer.volume = p
        auxPlayer.volume = 1f - p
        if (p < 1f) {
            handler.postDelayed(
                { fadeMainInAuxOut(a, reason, actualResumeMs, handoffStartedAtMs, success, fadeStartedAtMs) },
                TICK_MS,
            )
            return
        }
        finishHandoff(
            a = a,
            reason = reason,
            actualResumeMs = actualResumeMs,
            handoffStartedAtMs = handoffStartedAtMs,
            success = success,
            failureReason = if (success) null else "main-ready-timeout",
        )
    }

    private fun finishHandoff(
        a: Active,
        reason: String,
        actualResumeMs: Long,
        handoffStartedAtMs: Long,
        success: Boolean,
        failureReason: String?,
    ) {
        handler.removeCallbacks(tickRunnable)
        runCatching { mainPlayer.removeListener(mainListener) }
        restorePlayers()
        runCatching { auxPlayer.stop() }
        runCatching { auxPlayer.clearMediaItems() }
        active = null
        val fadeStart = a.fadeStartedAtMs ?: a.startedAtMs
        val now = SystemClock.elapsedRealtime()
        DiagnosticsLogStore.record(
            area = "automix",
            event = "realtime_crossfade_takeover",
            fields = mapOf(
                "nextId" to a.nextId,
                "pairKey" to a.pairKey,
                "queueVersion" to a.queueVersion,
                "reason" to reason,
                "resumeMs" to a.resumePositionMs,
                "actualResumeMs" to actualResumeMs,
                "mainMediaId" to mainPlayer.currentMediaItem?.mediaId,
                "success" to success,
                "failureReason" to failureReason,
            ),
        )
        onResult(
            TransitionResult(
                pairKey = a.pairKey,
                sessionId = PlaybackSessionClock.sessionId,
                queueVersion = a.queueVersion,
                mode = TransitionMode.RealtimeCrossfade,
                success = success,
                completedReason = reason.takeIf { success },
                failureReason = failureReason,
                auxReadyDelayMs = a.auxReadyDelayMs,
                actualOverlapMs = (handoffStartedAtMs - fadeStart).coerceAtLeast(0L),
                handoffGapMs = (now - handoffStartedAtMs).coerceAtLeast(0L),
                resumeDriftMs = kotlin.math.abs(actualResumeMs - a.resumePositionMs),
                actualResumePositionMs = actualResumeMs,
            ),
        )
    }

    private fun actualResumePositionMs(a: Active): Long {
        val auxPosition = auxPlayer.currentPosition.coerceAtLeast(0L)
        return if (auxPosition >= a.nextStartMs) {
            auxPosition
        } else {
            a.nextStartMs + auxPosition
        }.coerceAtLeast(a.nextStartMs)
    }

    /** 被打断(手动切歌/暂停/seek/错误)时:停 B、恢复音量。A 队列未被改动,无需还原。 */
    fun cancel(reason: String) {
        val a = active ?: return
        handler.removeCallbacks(tickRunnable)
        runCatching { mainPlayer.removeListener(mainListener) }
        restorePlayers()
        runCatching { auxPlayer.stop() }
        runCatching { auxPlayer.clearMediaItems() }
        active = null
        DiagnosticsLogStore.record(
            area = "automix",
            event = "realtime_crossfade_cancel",
            fields = mapOf(
                "nextId" to a.nextId,
                "pairKey" to a.pairKey,
                "queueVersion" to a.queueVersion,
                "reason" to reason,
            ),
        )
        onResult(
            TransitionResult(
                pairKey = a.pairKey,
                sessionId = PlaybackSessionClock.sessionId,
                queueVersion = a.queueVersion,
                mode = TransitionMode.RealtimeCrossfade,
                success = false,
                failureReason = reason,
                auxReadyDelayMs = a.auxReadyDelayMs,
            ),
        )
    }

    private fun restorePlayers() {
        mainPlayer.volume = 1f
        auxPlayer.volume = 0f
        runCatching { mainPlayer.setPlaybackParameters(PlaybackParameters(1f)) }
        runCatching { auxPlayer.setPlaybackParameters(PlaybackParameters(1f)) }
        runCatching { auxGain.setLinear(1f) }
    }

    fun release() {
        cancel("release")
        runCatching { auxPlayer.release() }
    }

    private companion object {
        private const val TICK_MS = 33L
        private const val AUX_TAIL_PAD_MS = 600L
        private const val AUX_READY_TIMEOUT_MS = 1_200L
        private const val HANDOFF_READY_TIMEOUT_MS = 900L
        private const val HANDOFF_FADE_MS = 180L
    }
}
