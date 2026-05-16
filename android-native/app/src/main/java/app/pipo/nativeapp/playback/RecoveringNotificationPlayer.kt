package app.pipo.nativeapp.playback

import android.os.SystemClock
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Media3 默认通知策略会在 Player.STATE_IDLE 时直接取消媒体通知。
 *
 * 网络抖动 / URL 过期是可恢复错误，但 ExoPlayer 会短暂进入 IDLE。这个 wrapper 只面向
 * MediaSession 对外状态：恢复窗口内把 "playWhenReady + 有队列 + IDLE" 暂时呈现为
 * BUFFERING，让系统媒体通知保持为活跃会话。真实播放控制仍全部转发给 ExoPlayer。
 */
@UnstableApi
internal class RecoveringNotificationPlayer(
    player: Player,
) : ForwardingPlayer(player) {
    private var recoveryUntilMs: Long = 0L

    fun armRecoveryWindow() {
        recoveryUntilMs = SystemClock.elapsedRealtime() + RECOVERY_WINDOW_MS
    }

    fun clearRecoveryWindow() {
        recoveryUntilMs = 0L
    }

    fun isRecovering(): Boolean = SystemClock.elapsedRealtime() <= recoveryUntilMs

    override fun getPlaybackState(): Int {
        val state = super.getPlaybackState()
        return if (shouldMaskIdle(state)) Player.STATE_BUFFERING else state
    }

    override fun isLoading(): Boolean {
        return shouldMaskIdle(super.getPlaybackState()) || super.isLoading()
    }

    override fun getPlayerError(): PlaybackException? {
        return if (shouldMaskIdle(super.getPlaybackState())) null else super.getPlayerError()
    }

    private fun shouldMaskIdle(state: Int): Boolean {
        return state == Player.STATE_IDLE &&
            isRecovering() &&
            super.getPlayWhenReady() &&
            super.getMediaItemCount() > 0
    }

    private companion object {
        private const val RECOVERY_WINDOW_MS = 25_000L
    }
}
