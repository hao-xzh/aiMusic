package app.pipo.nativeapp.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.NativeTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

@UnstableApi
class NextTrackPrewarmer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val activeWriter = AtomicReference<CacheWriter?>(null)

    suspend fun prewarm(track: NativeTrack): Boolean = withContext(Dispatchers.IO) {
        val url = track.streamUrl.takeIf { it.isNotBlank() } ?: return@withContext false
        val dataSource = PipoMediaDataSources.cacheFactory(appContext).createDataSourceForDownloading()
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setKey(PlaybackCacheKeys.forTrack(track) ?: url)
            .setPosition(0L)
            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION or DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED)
            .build()
        val writer = CacheWriter(dataSource, dataSpec, ByteArray(CacheWriter.DEFAULT_BUFFER_SIZE_BYTES), null)
        activeWriter.getAndSet(writer)?.cancel()
        try {
            if (!cache(writer)) return@withContext false
            currentCoroutineContext().ensureActive()
            DiagnosticsLogStore.record(
                area = "playback",
                event = "prewarm_completed",
                fields = mapOf(
                    "trackId" to track.id,
                    "neteaseId" to track.neteaseId,
                    "title" to track.title,
                    "artist" to track.artist,
                    "mode" to "full",
                ),
            )
            true
        } catch (e: CancellationException) {
            writer.cancel()
            throw e
        } catch (e: Exception) {
            DiagnosticsLogStore.record(
                area = "playback",
                event = "prewarm_failed",
                fields = mapOf(
                    "trackId" to track.id,
                    "neteaseId" to track.neteaseId,
                    "title" to track.title,
                    "artist" to track.artist,
                    "errorType" to e::class.java.simpleName,
                    "message" to e.message,
                ),
            )
            false
        } finally {
            activeWriter.compareAndSet(writer, null)
        }
    }

    private suspend fun cache(writer: CacheWriter): Boolean = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                activeWriter.compareAndSet(writer, null)
                writer.cancel()
            }
            try {
                writer.cache()
                continuation.resume(activeWriter.compareAndSet(writer, null))
            } catch (error: Exception) {
                continuation.resumeWith(Result.failure(error))
            }
        }
    }

    fun cancel() {
        activeWriter.getAndSet(null)?.cancel()
    }

}
