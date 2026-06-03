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
) {
    private val handler = Handler(Looper.getMainLooper())
    private var active: Active? = null

    val isRunning: Boolean get() = active != null

    private class Active(
        val nextId: String,
        val nextIndex: Int,
        val resumePositionMs: Long,
        val crossfadeMs: Long,
        val startedAtMs: Long,
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
    ) {
        if (active != null || crossfadeMs <= 0L) return
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
            nextId = nextMediaItem.mediaId,
            nextIndex = nextIndex,
            resumePositionMs = resumeMs,
            crossfadeMs = crossfadeMs,
            startedAtMs = SystemClock.elapsedRealtime(),
        )
        DiagnosticsLogStore.record(
            area = "automix",
            event = "realtime_crossfade_start",
            fields = mapOf(
                "nextId" to nextMediaItem.mediaId,
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
        val elapsed = SystemClock.elapsedRealtime() - a.startedAtMs
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

    /** 接管:停 B;A 主动 seekTo 到接续点(不靠 gapless → 不重播)。 */
    private fun takeover(a: Active, reason: String) {
        if (a.takingOver) return
        a.takingOver = true
        handler.removeCallbacks(tickRunnable)
        auxPlayer.volume = 0f
        runCatching { auxPlayer.stop() }
        runCatching { auxPlayer.clearMediaItems() }
        runCatching {
            if (mainPlayer.currentMediaItem?.mediaId == a.nextId) {
                // A 已在 next(early-transition):同 item 内直接 seek 到接续点
                mainPlayer.seekTo(a.resumePositionMs)
            } else {
                // 常规:A 还在当前曲,主动跳到 next 的接续点(跳过当前曲剩余的极小尾巴)
                mainPlayer.seekTo(a.nextIndex, a.resumePositionMs)
            }
            if (mainPlayer.playbackState == Player.STATE_IDLE || mainPlayer.playbackState == Player.STATE_ENDED) {
                mainPlayer.prepare()
            }
            mainPlayer.play()
        }
        mainPlayer.volume = 1f
        runCatching { mainPlayer.removeListener(mainListener) }
        active = null
        DiagnosticsLogStore.record(
            area = "automix",
            event = "realtime_crossfade_takeover",
            fields = mapOf(
                "nextId" to a.nextId,
                "reason" to reason,
                "resumeMs" to a.resumePositionMs,
                "mainMediaId" to mainPlayer.currentMediaItem?.mediaId,
            ),
        )
    }

    /** 被打断(手动切歌/暂停/seek/错误)时:停 B、恢复音量。A 队列未被改动,无需还原。 */
    fun cancel(reason: String) {
        val a = active ?: return
        handler.removeCallbacks(tickRunnable)
        runCatching { mainPlayer.removeListener(mainListener) }
        mainPlayer.volume = 1f
        auxPlayer.volume = 0f
        runCatching { auxPlayer.stop() }
        runCatching { auxPlayer.clearMediaItems() }
        active = null
        DiagnosticsLogStore.record(
            area = "automix",
            event = "realtime_crossfade_cancel",
            fields = mapOf("nextId" to a.nextId, "reason" to reason),
        )
    }

    fun release() {
        cancel("release")
        runCatching { auxPlayer.release() }
    }

    private companion object {
        private const val TICK_MS = 33L
        private const val AUX_TAIL_PAD_MS = 600L
    }
}
