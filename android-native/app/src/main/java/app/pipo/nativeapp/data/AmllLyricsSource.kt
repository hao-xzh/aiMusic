package app.pipo.nativeapp.data

import android.content.Context
import app.pipo.nativeapp.DiagnosticsLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 AMLL TTML 数据库（amll-dev/amll-ttml-db）拉网易云逐字歌词，按需缓存到本地。
 *
 * 接入策略：
 *   1. trackId 必须是纯网易云数字 ID（非数字直接放弃，落回原 yrc/lrc）
 *   2. 命中本地 .ttml 缓存 → 解析返回
 *   3. 命中本地 .404 哨兵 → 直接返回 null（不重复打网络）
 *   4. 无缓存 → HTTP GET raw.githubusercontent.com
 *      · 200：写 .ttml 缓存，解析返回
 *      · 404：写 .404 哨兵，返回 null
 *      · 其他错误（网络断 / 超时 / 5xx）：不写缓存，返回 null，下次再试
 *
 * 缓存目录用 `Context.cacheDir/amll-lyrics/`，OS 在空间紧张时可清理；
 * 单文件 ~10KB，重度用户播 1000 首也才 ~10MB，不需要主动 evict。
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
        if (trackId.isBlank() || trackId.any { !it.isDigit() }) {
            log(trackId, "skip_non_numeric", lineCount = null)
            return null
        }
        return withContext(Dispatchers.IO) {
            val cacheFile = File(cacheDir, "$trackId$TTML_SUFFIX")
            val missFile = File(cacheDir, "$trackId$MISS_SUFFIX")

            // 1. 之前已经确认 404 过 → 直接放弃
            if (missFile.exists()) {
                log(trackId, "miss_cached_404", lineCount = null)
                return@withContext null
            }

            // 2. 本地缓存命中
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                val cached = runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull()
                if (!cached.isNullOrBlank()) {
                    val parsed = runCatching { AmllTtmlParser.parse(cached) }.getOrNull()
                    if (!parsed.isNullOrEmpty()) {
                        log(trackId, "hit_cache", lineCount = parsed.size)
                        return@withContext parsed
                    }
                    // 文件存在但解析空 → 当作脏缓存删了，下次重拉
                    cacheFile.delete()
                }
            }

            // 3. 走网拉
            val result = fetchOnce(trackId)
            if (result == null) {
                log(trackId, "miss_network_error", lineCount = null)
                return@withContext null
            }
            when (result) {
                FetchResult.NotFound -> {
                    runCatching { missFile.writeBytes(ByteArray(0)) }
                    log(trackId, "miss_404", lineCount = null)
                    null
                }
                is FetchResult.Ok -> {
                    val parsed = runCatching { AmllTtmlParser.parse(result.body) }.getOrNull()
                    if (parsed.isNullOrEmpty()) {
                        // 拿到了但解析失败 —— 不写 404 哨兵（数据有效，是我们的 parser 有问题，
                        // 留着 .ttml 给开发可见，但本次返回 null）
                        runCatching { cacheFile.writeText(result.body, Charsets.UTF_8) }
                        log(trackId, "parse_failed", lineCount = null)
                        null
                    } else {
                        runCatching { cacheFile.writeText(result.body, Charsets.UTF_8) }
                        log(trackId, "hit_network", lineCount = parsed.size)
                        parsed
                    }
                }
            }
        }
    }

    private fun log(trackId: String, result: String, lineCount: Int?) {
        // 走 area="lyrics" / event="amll_resolve" —— 用户从设置里分享诊断日志时，
        // 一眼能看出每首歌走的是 AMLL（字级）还是 yrc/lrc（回落）。
        // result 取值：hit_cache / hit_network / miss_cached_404 / miss_404 /
        //             miss_network_error / parse_failed / skip_non_numeric
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

    private suspend fun fetchOnce(trackId: String): FetchResult? = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL$trackId$TTML_SUFFIX")
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/ttml+xml,application/xml,text/xml,*/*")
            }
            val code = conn.responseCode
            when {
                code == HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                    if (body.isBlank()) null else FetchResult.Ok(body)
                }
                code == HttpURLConnection.HTTP_NOT_FOUND -> FetchResult.NotFound
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private sealed class FetchResult {
        object NotFound : FetchResult()
        data class Ok(val body: String) : FetchResult()
    }

    companion object {
        // raw.githubusercontent.com 在国内访问偶尔不稳；如果将来想换镜像就只改这里。
        // amll-ttml-db.stevexmh.net 是作者镜像，URL 形如 https://amll-ttml-db.stevexmh.net/ncm/<id>
        // 但本地无法直接拿到 .ttml 文件名，得改抓接口；先走 raw + 缓存兜底，命中率足够。
        private const val BASE_URL = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/ncm-lyrics/"
        private const val TTML_SUFFIX = ".ttml"
        private const val MISS_SUFFIX = ".404"
        private const val CACHE_SUBDIR = "amll-lyrics"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 6_000
        private const val USER_AGENT = "Claudio-Android/AMLL-fetch"
    }
}
