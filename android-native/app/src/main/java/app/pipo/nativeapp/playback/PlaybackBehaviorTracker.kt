package app.pipo.nativeapp.playback

import androidx.media3.common.Player
import app.pipo.nativeapp.data.BehaviorEvent
import app.pipo.nativeapp.data.BehaviorLog
import app.pipo.nativeapp.data.BehaviorType
import app.pipo.nativeapp.data.NativeTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class PlaybackBehaviorTracker(
    private val behaviorLog: BehaviorLog,
    private val scope: CoroutineScope,
) {
    fun currentCompletionPct(player: Player): Float {
        val dur = player.duration.takeIf { it > 0 } ?: return 0f
        return (player.currentPosition.toFloat() / dur).coerceIn(0f, 1f)
    }

    fun logCurrent(
        player: Player,
        state: PlayerUiState,
        type: BehaviorType,
        completionPctOverride: Float? = null,
    ) {
        val track = currentTrackFor(player, state) ?: return
        log(track, type, completionPctOverride ?: currentCompletionPct(player))
    }

    fun logPrevious(
        player: Player,
        state: PlayerUiState,
        type: BehaviorType,
        completionPctOverride: Float? = null,
    ) {
        val queue = state.queue
        if (queue.isEmpty()) return
        val prevIdx = player.currentMediaItemIndex - 1
        val prev = if (prevIdx >= 0) queue.getOrNull(prevIdx) else queue.last()
        prev ?: return
        log(prev, type, completionPctOverride ?: 1f)
    }

    private fun log(track: NativeTrack, type: BehaviorType, completionPct: Float) {
        scope.launch {
            behaviorLog.log(
                BehaviorEvent(
                    type = type,
                    trackId = track.id,
                    neteaseId = track.neteaseId,
                    title = track.title,
                    artist = track.artist,
                    tsMs = System.currentTimeMillis(),
                    completionPct = completionPct,
                ),
            )
        }
    }

    private fun currentTrackFor(player: Player, state: PlayerUiState): NativeTrack? {
        val mediaId = player.currentMediaItem?.mediaId
        return mediaId?.let { id -> state.queue.firstOrNull { it.id == id } }
            ?: state.queue.getOrNull(player.currentMediaItemIndex.coerceAtLeast(0))
    }
}
