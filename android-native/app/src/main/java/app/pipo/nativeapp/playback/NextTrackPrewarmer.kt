package app.pipo.nativeapp.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.NativeTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

enum class NextTrackPrewarmPriority {
    FAR,
    NEAR,
}

enum class NextTrackPrewarmOutcome {
    FULL,
    SEEDED,
    /** 主曲需要带宽、暂停，或等待水位超时；不是网络失败。 */
    YIELDED,
    /** 已有同等或更高优先级的其它曲目在预热。 */
    DEFERRED,
    FAILED,
}

/**
 * 下一首缓存调度器。
 *
 * 同一 cache key 合并为一个 worker；NEAR 可以提升 FAR worker，FAR 不能打断 NEAR。
 * worker 不从 ViewModel job 继承取消，因此取消一个低优先级调用不会撤掉后来合并的近曲任务。
 */
@UnstableApi
class NextTrackPrewarmer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var active: Work? = null

    suspend fun prewarm(
        track: NativeTrack,
        priority: NextTrackPrewarmPriority = NextTrackPrewarmPriority.NEAR,
    ): NextTrackPrewarmOutcome {
        val url = track.streamUrl.takeIf { it.isNotBlank() } ?: return NextTrackPrewarmOutcome.FAILED
        val key = PlaybackCacheKeys.forTrack(track) ?: url
        val work = synchronized(lock) {
            val running = active
            when {
                running == null -> start(Work(track, url, key, priority))
                running.key == key -> {
                    running.promote(priority)
                    running
                }
                priority.ordinal > running.priority.get().ordinal -> {
                    cancelLocked(running)
                    start(Work(track, url, key, priority))
                }
                else -> return NextTrackPrewarmOutcome.DEFERRED
            }
        }
        return work.outcome.await()
    }

    /** 只取消不高于 caller 的任务，低优先级取消不能撤掉近曲 writer。 */
    fun cancel(priority: NextTrackPrewarmPriority, key: String? = null) {
        synchronized(lock) {
            active?.takeIf {
                it.priority.get().ordinal <= priority.ordinal && (key == null || it.key == key)
            }?.let(::cancelLocked)
        }
    }

    fun cancelAll() {
        synchronized(lock) {
            active?.let(::cancelLocked)
        }
    }

    private fun start(work: Work): Work {
        active = work
        val job = workerScope.launch {
            var outcome = NextTrackPrewarmOutcome.YIELDED
            try {
                outcome = runWork(work)
            } finally {
                finish(work, outcome)
            }
        }
        work.job = job
        // Job 可能在 body 调度前已被 cancel；completion handler 仍会补齐所有等待者。
        job.invokeOnCompletion { cause ->
            if (cause != null) finish(work, NextTrackPrewarmOutcome.YIELDED)
        }
        return work
    }

    private fun finish(work: Work, outcome: NextTrackPrewarmOutcome) {
        synchronized(lock) {
            if (active === work) active = null
        }
        work.outcome.complete(outcome)
    }

    private fun cancelLocked(work: Work) {
        if (active === work) active = null
        work.cancelled.set(true)
        work.writer.getAndSet(null)?.cancel()
        work.job?.cancel()
    }

    private suspend fun runWork(work: Work): NextTrackPrewarmOutcome {
        var seeded = false
        return try {
            withTimeoutOrNull(PREWARM_TIMEOUT_MS) {
                if (work.priority.get() == NextTrackPrewarmPriority.FAR &&
                    !PlaybackPreparationGate.canSeedNextTrack()
                ) {
                    return@withTimeoutOrNull NextTrackPrewarmOutcome.YIELDED
                }
                PlaybackPreparationGate.snapshots.first { it.canSeedNextTrack }
                if (work.cancelled.get()) return@withTimeoutOrNull NextTrackPrewarmOutcome.YIELDED

                when (cachePart(work, STARTUP_BYTES)) {
                    CacheResult.CACHED -> Unit
                    CacheResult.YIELDED -> return@withTimeoutOrNull NextTrackPrewarmOutcome.YIELDED
                    CacheResult.FAILED -> return@withTimeoutOrNull NextTrackPrewarmOutcome.FAILED
                }
                seeded = true
                recordCompleted(work, "startup")
                if (!work.wantsFull.get()) return@withTimeoutOrNull NextTrackPrewarmOutcome.SEEDED

                PlaybackPreparationGate.snapshots.first { it.canFillNextTrack }
                if (work.cancelled.get()) return@withTimeoutOrNull NextTrackPrewarmOutcome.YIELDED
                when (cachePart(work, length = null)) {
                    CacheResult.CACHED -> {
                        // 不按请求长度判定成功：短资源遇到 EOF 是 CacheWriter 的正常完成，
                        // 同 key 的头段也会由 CacheWriter 自动跳过，只补还未缓存的 range。
                        recordCompleted(work, "full")
                        NextTrackPrewarmOutcome.FULL
                    }
                    CacheResult.YIELDED -> NextTrackPrewarmOutcome.SEEDED
                    CacheResult.FAILED -> NextTrackPrewarmOutcome.FAILED
                }
            } ?: if (seeded) {
                // 头段已落 cache 后只是在等更高的整曲水位，不能把这类让路计为网络失败。
                NextTrackPrewarmOutcome.SEEDED
            } else if (PlaybackPreparationGate.canSeedNextTrack()) {
                NextTrackPrewarmOutcome.FAILED
            } else {
                NextTrackPrewarmOutcome.YIELDED
            }
        } catch (_: CancellationException) {
            NextTrackPrewarmOutcome.YIELDED
        } catch (error: Exception) {
            recordFailure(work, error)
            NextTrackPrewarmOutcome.FAILED
        }
    }

    private suspend fun cachePart(work: Work, length: Long?): CacheResult = suspendCancellableCoroutine { continuation ->
        val canContinue = if (length == null) {
            PlaybackPreparationGate::canFillNextTrack
        } else {
            PlaybackPreparationGate::canSeedNextTrack
        }
        if (!canContinue() || work.cancelled.get()) {
            continuation.resume(CacheResult.YIELDED)
            return@suspendCancellableCoroutine
        }
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(work.url))
            .setKey(work.key)
            .setPosition(0L)
            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
            .apply { if (length != null) setLength(length) }
            .build()
        val dataSource = PipoMediaDataSources.cacheFactory(appContext).createDataSourceForDownloading()
        val writer = CacheWriter(dataSource, dataSpec, ByteArray(CacheWriter.DEFAULT_BUFFER_SIZE_BYTES), null)
        work.writer.set(writer)
        val gateWatcher = workerScope.launch {
            PlaybackPreparationGate.snapshots.first {
                if (length == null) !it.canFillNextTrack else !it.canSeedNextTrack
            }
            work.yieldedByGate.set(true)
            writer.cancel()
        }
        val cacheJob = workerScope.launch {
            val result = try {
                writer.cache()
                if (work.cancelled.get() || !canContinue()) CacheResult.YIELDED else CacheResult.CACHED
            } catch (error: Exception) {
                when {
                    error is CancellationException -> CacheResult.YIELDED
                    work.cancelled.get() || work.yieldedByGate.get() ||
                        !canContinue() -> CacheResult.YIELDED
                    else -> {
                        recordFailure(work, error)
                        CacheResult.FAILED
                    }
                }
            } finally {
                gateWatcher.cancel()
                work.writer.compareAndSet(writer, null)
            }
            if (continuation.isActive) continuation.resume(result)
        }
        continuation.invokeOnCancellation {
            // withTimeout / caller cancel 必须真正中断阻塞的 CacheWriter，不能留下下载协程。
            gateWatcher.cancel()
            cacheJob.cancel()
            work.writer.compareAndSet(writer, null)
            writer.cancel()
        }
    }

    private fun recordCompleted(work: Work, mode: String) {
        DiagnosticsLogStore.record(
            area = "playback",
            event = "prewarm_completed",
            fields = mapOf(
                "trackId" to work.track.id,
                "neteaseId" to work.track.neteaseId,
                "title" to work.track.title,
                "artist" to work.track.artist,
                "mode" to mode,
                "priority" to work.priority.get().name.lowercase(),
            ),
        )
    }

    private fun recordFailure(work: Work, error: Exception) {
        DiagnosticsLogStore.record(
            area = "playback",
            event = "prewarm_failed",
            fields = mapOf(
                "trackId" to work.track.id,
                "neteaseId" to work.track.neteaseId,
                "title" to work.track.title,
                "artist" to work.track.artist,
                "errorType" to error::class.java.simpleName,
                "message" to error.message,
            ),
        )
    }

    private class Work(
        val track: NativeTrack,
        val url: String,
        val key: String,
        initialPriority: NextTrackPrewarmPriority,
    ) {
        val priority = AtomicReference(initialPriority)
        val wantsFull = AtomicBoolean(initialPriority == NextTrackPrewarmPriority.NEAR)
        val cancelled = AtomicBoolean(false)
        val yieldedByGate = AtomicBoolean(false)
        val writer = AtomicReference<CacheWriter?>(null)
        val outcome = CompletableDeferred<NextTrackPrewarmOutcome>()

        @Volatile
        var job: Job? = null

        fun promote(requested: NextTrackPrewarmPriority) {
            if (requested.ordinal > priority.get().ordinal) priority.set(requested)
            if (requested == NextTrackPrewarmPriority.NEAR) wantsFull.set(true)
        }
    }

    private enum class CacheResult {
        CACHED,
        YIELDED,
        FAILED,
    }

    private companion object {
        private const val STARTUP_BYTES = 1_500L * 1024L
        private const val PREWARM_TIMEOUT_MS = 60_000L
    }
}
