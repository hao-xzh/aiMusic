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
    private val onPlayWhenReadyCommand: (Boolean) -> Unit = {},
    private val onStopCommand: () -> Unit = {},
) : ForwardingPlayer(player) {
    private var recoveryUntilMs: Long = 0L
    private var playWhenReadyCommandDepth = 0

    fun armRecoveryWindow() {
        recoveryUntilMs = SystemClock.elapsedRealtime() + RECOVERY_WINDOW_MS
    }

    fun clearRecoveryWindow() {
        recoveryUntilMs = 0L
    }

    fun isRecovering(): Boolean = SystemClock.elapsedRealtime() <= recoveryUntilMs

    /**
     * MediaSession 的控制命令会先经过这个 wrapper，而服务内部直接监听的仍是底层
     * ExoPlayer。把显式 play/pause 命令单独上报，服务才能区分“用户真的暂停”与
     * “避让算法内部暂停”，避免内部暂停后的自动恢复覆盖用户意图。
     */
    override fun play() {
        dispatchPlayWhenReadyCommand(true) { super.play() }
    }

    override fun pause() {
        dispatchPlayWhenReadyCommand(false) { super.pause() }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        dispatchPlayWhenReadyCommand(playWhenReady) { super.setPlayWhenReady(playWhenReady) }
    }

    override fun stop() {
        val shouldNotify = playWhenReadyCommandDepth == 0
        playWhenReadyCommandDepth += 1
        try {
            if (shouldNotify) onStopCommand()
            // Player.stop() 默认会保留 playWhenReady；对显式控制器 Stop 来说这会留下
            // 隐形续播意图。先清掉它，避免后续 prepare/quiet probe 又自动响起来。
            super.setPlayWhenReady(false)
            super.stop()
        } finally {
            playWhenReadyCommandDepth -= 1
        }
    }

    private fun dispatchPlayWhenReadyCommand(playWhenReady: Boolean, command: () -> Unit) {
        val shouldNotify = playWhenReadyCommandDepth == 0
        playWhenReadyCommandDepth += 1
        try {
            if (shouldNotify) onPlayWhenReadyCommand(playWhenReady)
            command()
        } finally {
            playWhenReadyCommandDepth -= 1
        }
    }

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
