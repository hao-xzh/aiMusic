package app.pipo.nativeapp.data

import android.content.Context
import app.pipo.nativeapp.DiagnosticsLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.File

/**
 * 从 AMLL TTML 数据库（amll-dev/amll-ttml-db）拉网易云逐字歌词，按需缓存到本地。
 *
 * 成功缓存七天，未收录记录一天；网络失败尝试作者镜像并保留已验证缓存。
 * 旧版无期限的空 .404 哨兵会失效，避免数据库补录后仍永久使用回退歌词。
 */
class AmllLyricsSource(private val context: Context) {

    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
    }

    /**
     * 尝试拿到 trackId 对应的 AMLL 歌词。
     *   · 返回非空 list = 命中并解析成功
     *   · 返回 null = 没命中（404 / 非数字 ID / 网络失败 / 解析失败），调用方应回落到原源
     */
    suspend fun lyricsForTrack(trackId: String): List<PipoLyricLine>? {
        if (trackId.length !in 1..24 || trackId.any { !it.isDigit() }) {
            log(trackId, "skip_non_numeric", lineCount = null)
            return null
        }
        return withContext(Dispatchers.IO) {
            val cacheFile = File(cacheDir, "$trackId$TTML_SUFFIX")
            val missFile = File(cacheDir, "$trackId$MISS_SUFFIX")

            val now = System.currentTimeMillis()
            val cached = if (cacheFile.length() in 1..MAX_CACHE_FILE_BYTES) {
                runCatching { AmllTtmlParser.parse(cacheFile.readText(Charsets.UTF_8)) }
                    .getOrNull()?.takeIf { OnlineLyricSupport.validTimings(it, 0L) }
            } else null
            if (cached != null && now - cacheFile.lastModified() in 0 until CACHE_TTL_MS) {
                log(trackId, "hit_cache", cached.size)
                return@withContext cached
            }
            val missUntil = runCatching { missFile.readText().toLongOrNull() }.getOrNull()
            if (cached == null && missUntil != null && missUntil > now && missUntil - now <= MISS_TTL_MS) {
                log(trackId, "miss_cached_404", lineCount = null)
                return@withContext null
            }
            missFile.delete()
            val result = fetchOnce(trackId)
            if (result == null) {
                log(trackId, if (cached != null) "hit_stale_cache" else "miss_network_error", cached?.size)
                return@withContext cached
            }
            when (result) {
                FetchResult.NotFound -> {
                    cacheFile.delete()
                    runCatching { missFile.writeText((now + MISS_TTL_MS).toString()) }
                    trimCache()
                    log(trackId, "miss_404", lineCount = null)
                    null
                }
                is FetchResult.Ok -> {
                    val parsed = runCatching { AmllTtmlParser.parse(result.body) }.getOrNull()
                    if (parsed.isNullOrEmpty() || !OnlineLyricSupport.validTimings(parsed, 0L)) {
                        log(trackId, "parse_failed", lineCount = null)
                        cached
                    } else {
                        writeCache(cacheFile, result.body)
                        log(trackId, "hit_${result.origin}", lineCount = parsed.size)
                        parsed
                    }
                }
            }
        }
    }

    private fun log(trackId: String, result: String, lineCount: Int?) {
        // 走 area="lyrics" / event="amll_resolve" —— 用户从设置里分享诊断日志时，
        // 一眼能看出每首歌走的是 AMLL（字级）还是 yrc/lrc（回落）。
        val fields = mutableMapOf<String, Any?>(
            "trackId" to trackId,
            "result" to result,
        )
        if (lineCount != null) fields["lineCount"] = lineCount
        DiagnosticsLogStore.record(
            area = "lyrics",
            event = "amll_resolve",
            fields = fields,
        )
    }

    private suspend fun fetchOnce(trackId: String): FetchResult? {
        val urls = listOf("$BASE_URL$trackId$TTML_SUFFIX", "$MIRROR_URL$trackId")
        for ((index, url) in urls.withIndex()) {
            try {
                val body = withTimeoutOrNull(4_000L) {
                    OnlineLyricSupport.requestText(
                        Request.Builder().url(url).header("User-Agent", USER_AGENT)
                            .header("Accept", "application/ttml+xml,application/xml,text/xml,*/*").build(),
                    )
                }
                if (!body.isNullOrBlank()) return FetchResult.Ok(body, if (index == 0) "network" else "mirror")
            } catch (e: CancellationException) {
                throw e
            } catch (e: LyricHttpException) {
                // Only the canonical repository can establish that the song is not indexed.
                if (index == 0 && e.statusCode == 404) return FetchResult.NotFound
            } catch (_: Exception) {
                // The mirror is a fallback for transport failure, never a reason to cache a miss.
            }
        }
        return null
    }

    private fun writeCache(target: File, body: String) {
        runCatching {
            val temporary = File.createTempFile("amll-", ".tmp", cacheDir)
            try {
                temporary.writeText(body, Charsets.UTF_8)
                temporary.renameTo(target)
            } finally {
                temporary.delete()
            }
            trimCache()
        }
    }

    private fun trimCache() {
        cacheDir.listFiles()?.filter { it.extension == "ttml" || it.extension == "404" }
            ?.sortedByDescending { it.lastModified() }?.drop(MAX_CACHE_FILES)?.forEach { it.delete() }
    }

    private sealed class FetchResult {
        object NotFound : FetchResult()
        data class Ok(val body: String, val origin: String) : FetchResult()
    }

    companion object {
        private const val BASE_URL = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/ncm-lyrics/"
        private const val MIRROR_URL = "https://amll-ttml-db.stevexmh.net/ncm/"
        private const val TTML_SUFFIX = ".ttml"
        private const val MISS_SUFFIX = ".404"
        private const val CACHE_SUBDIR = "amll-lyrics"
        private const val CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        private const val MISS_TTL_MS = 24 * 60 * 60 * 1000L
        private const val MAX_CACHE_FILE_BYTES = 2 * 1024 * 1024L
        private const val MAX_CACHE_FILES = 256
        private const val USER_AGENT = "Pipo-Android/AMLL-fetch"
    }
}
