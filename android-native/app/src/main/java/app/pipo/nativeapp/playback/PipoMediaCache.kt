package app.pipo.nativeapp.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@UnstableApi
object PipoMediaCache {
    private const val DEFAULT_MAX_BYTES = 2L * 1024L * 1024L * 1024L
    private const val PREFS = "pipo-media-cache"
    private const val KEY_MAX_BYTES = "max-bytes"

    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        return cache ?: synchronized(this) {
            cache ?: SimpleCache(
                cacheDir(context),
                LeastRecentlyUsedCacheEvictor(maxBytes(context)),
                StandaloneDatabaseProvider(context),
            ).also { cache = it }
        }
    }

    fun stats(context: Context): MediaCacheStats {
        val active = cache
        if (active != null) {
            return MediaCacheStats(
                totalBytes = active.cacheSpace,
                count = countFiles(cacheDir(context)),
                maxBytes = maxBytes(context),
            )
        }
        val dir = cacheDir(context)
        return MediaCacheStats(
            totalBytes = sumFiles(dir),
            count = countFiles(dir),
            maxBytes = maxBytes(context),
        )
    }

    fun setMaxBytes(context: Context, bytes: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_MAX_BYTES, bytes.coerceAtLeast(64L * 1024L * 1024L))
            .apply()
    }

    fun clear(context: Context) {
        synchronized(this) {
            val active = cache
            if (active != null) {
                active.keys.toList().forEach { key ->
                    runCatching { active.removeResource(key) }
                }
            } else {
                cacheDir(context).deleteRecursively()
            }
        }
    }

    private fun maxBytes(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_MAX_BYTES, DEFAULT_MAX_BYTES)
            .coerceAtLeast(64L * 1024L * 1024L)
    }

    private fun cacheDir(context: Context): File = File(context.cacheDir, "media3-audio")

    private fun sumFiles(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown()
            .filter { it.isFile }
            .fold(0L) { acc, file -> acc + file.length() }
    }

    private fun countFiles(dir: File): Int {
        if (!dir.exists()) return 0
        return dir.walkTopDown().count { it.isFile }
    }
}

data class MediaCacheStats(
    val totalBytes: Long,
    val count: Int,
    val maxBytes: Long,
)
