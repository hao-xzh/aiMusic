package app.pipo.nativeapp.playback

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 同进程后台 Agent 提交队列后的续播 owner。
 *
 * 这个 source 是运行时对象，不承诺在 Service/进程重建后恢复；它只避免后台任务绕过
 * PlayerViewModel 时，让旧的前台 source 继续给新队列续杯。
 */
@UnstableApi
internal object BackgroundAgentContinuation {
    private var scope: CoroutineScope? = null
    private var player: Player? = null
    private var resolver: PlaybackUrlResolver? = null
    private var mediaFactory: PlayerMediaFactory? = null
    private var source: ContinuousQueueSource? = null
    private var goal: MusicGoal? = null
    private var generation = 0L
    private var appendJob: Job? = null
    private var ownsQueue = false
    private var retryNotBeforeMs = 0L

    fun bind(
        scope: CoroutineScope,
        player: Player,
        resolver: PlaybackUrlResolver,
        mediaFactory: PlayerMediaFactory,
    ) {
        requireMainThread()
        this.scope = scope
        this.player = player
        this.resolver = resolver
        this.mediaFactory = mediaFactory
    }

    /** BackgroundPlayerAgentExecutor 在成功替换主队列前调用；null 清掉 source 但保留队列所有权。 */
    fun install(source: ContinuousQueueSource?, goal: MusicGoal) {
        requireMainThread()
        generation += 1L
        appendJob?.cancel()
        appendJob = null
        this.source = source
        this.goal = goal
        ownsQueue = true
        retryNotBeforeMs = 0L
        if (source == null) return
        scope?.launch { maybeExtend() }
    }

    /** 前台成功接管一个新主队列后调用，禁止旧后台 source 再追加。 */
    fun clear() {
        requireMainThread()
        generation += 1L
        appendJob?.cancel()
        appendJob = null
        source = null
        goal = null
        ownsQueue = false
        retryNotBeforeMs = 0L
    }

    /** source 为空的后台主队列仍归 Service 所有，不能让 VM 的旧 source 接管。 */
    fun isActive(): Boolean = ownsQueue

    fun onPlayerEvent() {
        requireMainThread()
        maybeExtend()
    }

    fun release() {
        requireMainThread()
        clear()
        scope = null
        player = null
        resolver = null
        mediaFactory = null
    }

    private fun maybeExtend() {
        if (android.os.SystemClock.elapsedRealtime() < retryNotBeforeMs) return
        val activeSource = source ?: return
        val livePlayer = player ?: return
        val activeResolver = resolver ?: return
        val factory = mediaFactory ?: return
        if (appendJob?.isActive == true) return
        val currentIndex = livePlayer.currentMediaItemIndex
        if (currentIndex !in 0 until livePlayer.mediaItemCount) return
        val remaining = livePlayer.mediaItemCount - currentIndex - 1
        if (remaining > EXTEND_THRESHOLD) return

        val expectedGeneration = generation
        val timelineIds = timelineIds(livePlayer)
        val excludedNeteaseIds = timelineIds.mapNotNull(String::toLongOrNull).toSet()
        val currentGoal = goal
        val workerScope = scope ?: return
        appendJob = workerScope.launch {
            try {
                val candidates = withContext(Dispatchers.IO) {
                    activeSource.fetchMore(excludedNeteaseIds)
                }
                val resolved = withContext(Dispatchers.IO) {
                    activeResolver.resolvePlayableQueue(candidates)
                }
                val append = dedupeNew(resolved, timelineIds, activeSource)
                if (append.isEmpty()) {
                    retryNotBeforeMs = android.os.SystemClock.elapsedRealtime() + EXHAUSTED_BACKOFF_MS
                    DiagnosticsLogStore.record(
                        area = "background_agent_continuation",
                        event = "source_exhausted",
                        fields = mapOf(
                            "generation" to expectedGeneration,
                            "strict" to !activeSource.permitsDefaultFallback(),
                            "goal" to currentGoal.toString(),
                            "retryAfterMs" to EXHAUSTED_BACKOFF_MS,
                        ),
                    )
                    return@launch
                }
                if (generation != expectedGeneration || source !== activeSource ||
                    player !== livePlayer || timelineIds(livePlayer) != timelineIds
                ) return@launch
                livePlayer.addMediaItems(append.map(factory::toMediaItem))
                retryNotBeforeMs = 0L
                DiagnosticsLogStore.record(
                    area = "background_agent_continuation",
                    event = "append",
                    fields = mapOf("generation" to expectedGeneration, "appendCount" to append.size),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                retryNotBeforeMs = android.os.SystemClock.elapsedRealtime() + FAILURE_BACKOFF_MS
                DiagnosticsLogStore.record(
                    area = "background_agent_continuation",
                    event = "fetch_failed",
                    fields = mapOf(
                        "generation" to expectedGeneration,
                        "errorType" to error::class.java.simpleName,
                        "retryAfterMs" to FAILURE_BACKOFF_MS,
                    ),
                )
            } finally {
                if (generation == expectedGeneration) appendJob = null
            }
        }
    }

    private fun timelineIds(player: Player): List<String> =
        List(player.mediaItemCount) { index -> player.getMediaItemAt(index).mediaId }

    private fun dedupeNew(
        tracks: List<NativeTrack>,
        existingIds: List<String>,
        activeSource: ContinuousQueueSource,
    ): List<NativeTrack> {
        val seenIds = existingIds.toMutableSet()
        val seenNeteaseIds = existingIds.mapNotNull(String::toLongOrNull).toMutableSet()
        return tracks.filter { track ->
            track.streamUrl.isNotBlank() &&
                activeSource.acceptsResolved(track) &&
                seenIds.add(track.id) &&
                (track.neteaseId == null || seenNeteaseIds.add(track.neteaseId))
        }
    }

    private const val EXTEND_THRESHOLD = 3
    private const val EXHAUSTED_BACKOFF_MS = 60_000L
    private const val FAILURE_BACKOFF_MS = 15_000L

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "BackgroundAgentContinuation must be called on the main thread"
        }
    }
}
