package app.pipo.nativeapp.playback

import android.os.SystemClock
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
    private data class ListeningSession(
        val track: NativeTrack,
        var durationMs: Long?,
        var listenedMs: Long = 0L,
        var playingStartedAtElapsedMs: Long? = null,
    )

    private var activeSession: ListeningSession? = null
    private var lastClosedSession: ListeningSession? = null

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
        val session = ensureCurrentSession(player, state)
        closeActiveInterval()
        log(track, type, completionPctOverride ?: currentCompletionPct(player), session?.snapshot())
        // A previous tap may replay the same item without a media-item transition. Start a fresh
        // measured segment so it keeps counting and the just-recorded listening is not counted twice.
        if (type != BehaviorType.PlayStarted) session?.listenedMs = 0L
        if (player.isPlaying) session?.startIfNeeded()
    }

    fun logPrevious(
        type: BehaviorType,
        completionPctOverride: Float? = null,
    ) {
        // 自动转场后的 index 在 shuffle/wrap 中不一定与旧曲相邻，以实际关闭的 session 为准。
        val session = lastClosedSession ?: return
        log(session.track, type, completionPctOverride ?: 1f, session.snapshot())
    }

    /** 只在 Media3 报告实际播放时开始累计；暂停、缓冲和 seek 都不会推进。 */
    fun onIsPlayingChanged(player: Player, state: PlayerUiState, isPlaying: Boolean) {
        // Preserve the old item for its transition callback if playback state arrives first.
        if (activeSession != null && activeSession?.track?.id != player.currentMediaItem?.mediaId) {
            closeActiveInterval()
            return
        }
        if (isPlaying) {
            ensureCurrentSession(player, state)?.startIfNeeded()
        } else {
            closeActiveInterval()
        }
    }

    /**
     * Media3 回调此方法时 current item 已是新曲，旧曲的 session 仍保留其开始时的时长。
     */
    fun onMediaItemTransition(player: Player, state: PlayerUiState) {
        closeActiveInterval()
        lastClosedSession = null
        activeSession?.let { lastClosedSession = it }
        activeSession = null
        if (player.isPlaying) ensureCurrentSession(player, state)?.startIfNeeded()
    }

    fun previousReachedCompletionThreshold(): Boolean {
        val session = lastClosedSession ?: return false
        val duration = session.durationMs ?: return false
        if (duration <= 0L) return false
        return session.listenedMs.toDouble() / duration >= AUTO_COMPLETE_MIN_LISTEN_RATIO
    }

    private fun log(
        track: NativeTrack,
        type: BehaviorType,
        completionPct: Float,
        measurement: ListeningMeasurement?,
    ) {
        val recordedAtMs = System.currentTimeMillis()
        scope.launch {
            behaviorLog.log(
                BehaviorEvent(
                    type = type,
                    trackId = track.id,
                    neteaseId = track.neteaseId,
                    title = track.title,
                    artist = track.artist,
                    tsMs = recordedAtMs,
                    completionPct = completionPct,
                    listenedMs = measurement?.listenedMs,
                    durationMs = measurement?.durationMs,
                ),
            )
        }
    }

    private fun ensureCurrentSession(player: Player, state: PlayerUiState): ListeningSession? {
        val track = currentTrackFor(player, state) ?: return null
        activeSession?.let { active ->
            if (active.track.id == track.id) {
                // A duration can be unknown until the stream becomes ready.
                if (active.durationMs == null) active.durationMs = player.duration.takeIf { it > 0L }
                    ?: track.durationMs.takeIf { it > 0L }
                return active
            }
            closeActiveInterval()
            lastClosedSession = active
        }
        return ListeningSession(
            track = track,
            durationMs = player.duration.takeIf { it > 0L } ?: track.durationMs.takeIf { it > 0L },
        ).also { activeSession = it }
    }

    private fun ListeningSession.startIfNeeded() {
        if (playingStartedAtElapsedMs == null) {
            playingStartedAtElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    private fun ListeningSession.snapshot() = ListeningMeasurement(
        listenedMs = listenedMs,
        durationMs = durationMs,
    )

    private fun closeActiveInterval() {
        val session = activeSession ?: return
        val startedAt = session.playingStartedAtElapsedMs ?: return
        session.listenedMs += (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        session.playingStartedAtElapsedMs = null
    }

    private fun currentTrackFor(player: Player, state: PlayerUiState): NativeTrack? {
        val mediaId = player.currentMediaItem?.mediaId
        return if (!mediaId.isNullOrBlank()) state.queue.firstOrNull { it.id == mediaId }
            else state.queue.getOrNull(player.currentMediaItemIndex.coerceAtLeast(0))
    }

    private companion object {
        private const val AUTO_COMPLETE_MIN_LISTEN_RATIO = 0.80
    }

    private data class ListeningMeasurement(
        val listenedMs: Long,
        val durationMs: Long?,
    )
}
