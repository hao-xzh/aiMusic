package app.pipo.nativeapp.playback

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 由 Service 在持有主播放器的线程上发布播放余量；后台预热只读这个快照，绝不跨线程触碰
 * ExoPlayer。任何非 READY 实播、暂停或余量跌破水位都会立即让预热任务归还网络带宽。
 */
object PlaybackPreparationGate {
    data class Snapshot(
        val playWhenReady: Boolean = false,
        val isPlaying: Boolean = false,
        val playbackState: Int = Player.STATE_IDLE,
        val bufferedAheadMs: Long = 0L,
        val remainingMs: Long = 0L,
    ) {
        val canSeedNextTrack: Boolean
            get() = playWhenReady &&
                isPlaying &&
                playbackState == Player.STATE_READY &&
                remainingMs >= MIN_REMAINING_TO_PREPARE_MS &&
                bufferedAheadMs >= minOf(MIN_BUFFERED_AHEAD_TO_SEED_MS, remainingMs)

        val canFillNextTrack: Boolean
            get() = canSeedNextTrack &&
                bufferedAheadMs >= minOf(MIN_BUFFERED_AHEAD_TO_FILL_MS, remainingMs)
    }

    private val mutableSnapshot = MutableStateFlow(Snapshot())
    val snapshots: StateFlow<Snapshot> = mutableSnapshot

    /**
     * 只允许 Service 从它已拥有的主 Player 提取参数后调用；这里不保存也不读取 Player。
     * duration 未知时 remainingMs 传 Long.MAX_VALUE。
     */
    fun update(
        playWhenReady: Boolean,
        isPlaying: Boolean,
        playbackState: Int,
        bufferedAheadMs: Long,
        remainingMs: Long,
    ) {
        mutableSnapshot.value = Snapshot(
            playWhenReady = playWhenReady,
            isPlaying = isPlaying,
            playbackState = playbackState,
            bufferedAheadMs = bufferedAheadMs.coerceAtLeast(0L),
            remainingMs = remainingMs.coerceAtLeast(0L),
        )
    }

    fun reset() {
        mutableSnapshot.value = Snapshot()
    }

    fun canSeedNextTrack(): Boolean = snapshots.value.canSeedNextTrack

    fun canFillNextTrack(): Boolean = snapshots.value.canFillNextTrack

    private const val MIN_BUFFERED_AHEAD_TO_SEED_MS = 12_000L
    private const val MIN_BUFFERED_AHEAD_TO_FILL_MS = 25_000L
    private const val MIN_REMAINING_TO_PREPARE_MS = 8_000L
}
