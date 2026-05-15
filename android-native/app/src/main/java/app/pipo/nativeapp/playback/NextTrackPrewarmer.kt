package app.pipo.nativeapp.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import app.pipo.nativeapp.data.NativeTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

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
            .setKey(url)
            .setPosition(0L)
            .setLength(PREWARM_BYTES)
            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION or DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED)
            .build()
        val writer = CacheWriter(dataSource, dataSpec, ByteArray(CacheWriter.DEFAULT_BUFFER_SIZE_BYTES), null)
        activeWriter.getAndSet(writer)?.cancel()
        try {
            writer.cache()
            Log.d("PipoPrewarm", "prewarmed ${track.title}")
            true
        } catch (e: Exception) {
            Log.w("PipoPrewarm", "prewarm failed for ${track.title}", e)
            false
        } finally {
            activeWriter.compareAndSet(writer, null)
        }
    }

    fun cancel() {
        activeWriter.getAndSet(null)?.cancel()
    }

    companion object {
        private const val PREWARM_BYTES = 3L * 1024L * 1024L
    }
}
