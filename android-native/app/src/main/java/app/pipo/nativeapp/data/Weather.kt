package app.pipo.nativeapp.data

import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 轻量天气 —— wttr.in 免 key、根据 IP 自动定位。镜像 src/lib/weather.ts。
 *
 * 1 小时内存缓存。失败返回 null，AI prompt 那边 fallback 不提天气。
 */
object Weather {
    data class Snapshot(val summary: String, val tempC: Int)

    @Volatile private var cached: Pair<Long, Snapshot?>? = null
    private const val MEMO_TTL_MS = 60L * 60 * 1000

    suspend fun get(): Snapshot? {
        val now = System.currentTimeMillis()
        cached?.let { (at, data) ->
            if (now - at < MEMO_TTL_MS) return data
        }
        val data = withTimeoutOrNull(4000L) {
            runCatching { fetchOnce() }.getOrNull()
        }
        cached = now to data
        return data
    }

    private fun fetchOnce(): Snapshot? {
        val url = URL("https://wttr.in/?format=j1&lang=zh-cn")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val arr = json.optJSONArray("current_condition") ?: return null
            val cur = arr.optJSONObject(0) ?: return null
            val summary = cur.optJSONArray("lang_zh_cn")?.optJSONObject(0)?.optString("value")?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: cur.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value")?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "未知"
            val tempC = cur.optString("temp_C").toIntOrNull() ?: return null
            return Snapshot(summary, tempC)
        } finally {
            conn.disconnect()
        }
    }
}
