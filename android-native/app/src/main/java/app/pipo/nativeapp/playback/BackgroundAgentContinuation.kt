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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 同进程后台 Agent 提交队列后的续播 owner。
 *
 * 原选歌条件随播放快照持久化；Service 重建时恢复 source，前台退出时可交接给这里。
 * 它也避免后台任务绕过 PlayerViewModel 时，让旧的前台 source 继续给新队列续杯。
 */
@UnstableApi
internal object BackgroundAgentContinuation {
    private var scope: CoroutineScope? = null
    private var player: Player? = null
    private var resolver: PlaybackUrlResolver? = null
    private var mediaFactory: PlayerMediaFactory? = null
    private var source: ContinuousQueueSource? = null
    private var enabled = false
    private var goal: MusicGoal? = null
    private var generation = 0L
    private var appendJob: Job? = null
    private var preparationJob: Job? = null
    private var retryJob: Job? = null
    private var ownsQueue = false
    private var retryNotBeforeMs = 0L
    private val pendingRetryNotBeforeMs = mutableMapOf<String, Long>()

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
        preparationJob?.cancel()
        preparationJob = scope.launch {
            PlaybackPreparationGate.snapshots
                .map { it.canSeedNextTrack }
                .distinctUntilChanged()
                .collect { ready -> if (ready) maybeExtend() }
        }
    }

    /** BackgroundPlayerAgentExecutor 在成功替换主队列前调用；null 清掉 source 但保留队列所有权。 */
    fun install(source: ContinuousQueueSource?, goal: MusicGoal) {
        requireMainThread()
        generation += 1L
        appendJob?.cancel()
        appendJob = null
        retryJob?.cancel()
        retryJob = null
        this.source = source
        enabled = source?.startsAutomatically() == true
        this.goal = goal
        ownsQueue = true
        retryNotBeforeMs = 0L
        pendingRetryNotBeforeMs.clear()
        if (!enabled) return
        scope?.launch { maybeExtend() }
    }

    /** 播放列表中的模式开关同时控制后台 owner；关闭时保留本轮对话来源。 */
    fun setEnabled(value: Boolean): Boolean {
        requireMainThread()
        if (!ownsQueue || (value && source == null)) return false
        generation += 1L
        appendJob?.cancel()
        appendJob = null
        retryJob?.cancel()
        retryJob = null
        enabled = value
        retryNotBeforeMs = 0L
        pendingRetryNotBeforeMs.clear()
        if (value) scope?.launch { maybeExtend() }
        return true
    }

    /** 前台成功接管一个新主队列后调用，禁止旧后台 source 再追加。 */
    fun clear() {
        requireMainThread()
        generation += 1L
        appendJob?.cancel()
        appendJob = null
        retryJob?.cancel()
        retryJob = null
        source = null
        enabled = false
        goal = null
        ownsQueue = false
        retryNotBeforeMs = 0L
        pendingRetryNotBeforeMs.clear()
    }

    /** source 为空的后台主队列仍归 Service 所有，不能让 VM 的旧 source 接管。 */
    fun isActive(): Boolean = ownsQueue

    fun currentSource(): ContinuousQueueSource? = source.takeIf { ownsQueue }

    /** 前台重连时以后台正在执行的模式更新显示，避免仍显示旧队列的模式。 */
    fun playbackMode(): PlaybackQueueMode? = when {
        !ownsQueue -> null
        player?.repeatMode == Player.REPEAT_MODE_ONE -> PlaybackQueueMode.SingleLoop
        player?.repeatMode == Player.REPEAT_MODE_OFF -> PlaybackQueueMode.OrderOnce
        enabled -> PlaybackQueueMode.AiRadio
        else -> null
    }

    fun onPlayerEvent() {
        requireMainThread()
        maybeExtend()
    }

    fun release() {
        requireMainThread()
        preparationJob?.cancel()
        preparationJob = null
        clear()
        scope = null
        player = null
        resolver = null
        mediaFactory = null
    }

    private fun maybeExtend() {
        if (!enabled) return
        if (android.os.SystemClock.elapsedRealtime() < retryNotBeforeMs) return
        val activeSource = source ?: return
        val livePlayer = player ?: return
        val activeResolver = resolver ?: return
        val factory = mediaFactory ?: return
        if (appendJob?.isActive == true) return
        // 后台接管的 AI 队列也要给切歌缓冲让路，不能与新主曲同时批量取流。
        if (!livePlayer.isPlaying || livePlayer.playbackState != Player.STATE_READY ||
            !PlaybackPreparationGate.canSeedNextTrack()
        ) return
        val currentIndex = livePlayer.currentMediaItemIndex
        if (currentIndex !in 0 until livePlayer.mediaItemCount) return
        val remaining = livePlayer.mediaItemCount - currentIndex - 1
        val timelineIds = timelineIds(livePlayer)
        val excludedNeteaseIds = timelineIds.mapNotNull(String::toLongOrNull).toSet()
        val nowMs = android.os.SystemClock.elapsedRealtime()
        pendingRetryNotBeforeMs.entries.removeAll { it.value <= nowMs }
        val pending = activeSource.pendingTracks().filter {
            it.id !in timelineIds && it.id !in pendingRetryNotBeforeMs &&
                (it.neteaseId == null || it.neteaseId !in excludedNeteaseIds)
        }
        if (remaining > EXTEND_THRESHOLD && pending.isEmpty()) return

        val expectedGeneration = generation
        val currentGoal = goal
        val workerScope = scope ?: return
        appendJob = workerScope.launch {
            try {
                val resolvedPending = if (pending.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
                    activeResolver.resolvePlayableQueue(pending)
                }
                if (generation != expectedGeneration || source !== activeSource || player !== livePlayer) return@launch
                deferPendingRetry(pending)
                var append = dedupeNew(resolvedPending, timelineIds, activeSource)
                // 无法播放的待入队曲目不能一直挡住新的推荐召回。
                if (append.isEmpty() && remaining <= EXTEND_THRESHOLD) {
                    val candidates = withContext(Dispatchers.IO) {
                        activeSource.fetchMore(excludedNeteaseIds)
                    }
                    if (generation != expectedGeneration || source !== activeSource || player !== livePlayer) return@launch
                    val resolved = withContext(Dispatchers.IO) {
                        activeResolver.resolvePlayableQueue(candidates)
                    }
                    if (generation != expectedGeneration || source !== activeSource || player !== livePlayer) return@launch
                    append = dedupeNew(resolved, timelineIds, activeSource)
                }
                if (append.isEmpty()) {
                    scheduleRetry(EXHAUSTED_BACKOFF_MS, expectedGeneration, activeSource, livePlayer)
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
                if (timelineIds(livePlayer) != timelineIds) {
                    scheduleRetry(FAILURE_BACKOFF_MS, expectedGeneration, activeSource, livePlayer)
                    return@launch
                }
                livePlayer.addMediaItems(append.map(factory::toMediaItem))
                retryJob?.cancel()
                retryJob = null
                retryNotBeforeMs = 0L
                DiagnosticsLogStore.record(
                    area = "background_agent_continuation",
                    event = "append",
                    fields = mapOf("generation" to expectedGeneration, "appendCount" to append.size),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation != expectedGeneration || source !== activeSource || player !== livePlayer) return@launch
                deferPendingRetry(pending)
                scheduleRetry(FAILURE_BACKOFF_MS, expectedGeneration, activeSource, livePlayer)
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

    private fun deferPendingRetry(tracks: List<NativeTrack>) {
        val retryAtMs = android.os.SystemClock.elapsedRealtime() + PENDING_RETRY_BACKOFF_MS
        // 已成功入队的曲目会被 timeline 排除，其余候选稍后再试，给新推荐留出机会。
        tracks.forEach { pendingRetryNotBeforeMs[it.id] = retryAtMs }
    }

    private fun scheduleRetry(delayMs: Long, expectedGeneration: Long, activeSource: ContinuousQueueSource, livePlayer: Player) {
        retryNotBeforeMs = android.os.SystemClock.elapsedRealtime() + delayMs
        retryJob?.cancel()
        retryJob = scope?.launch {
            delay(delayMs)
            retryJob = null
            if (enabled && generation == expectedGeneration && source === activeSource && player === livePlayer) {
                maybeExtend()
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
    private const val PENDING_RETRY_BACKOFF_MS = 300_000L

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "BackgroundAgentContinuation must be called on the main thread"
        }
    }
}
