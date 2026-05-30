package app.pipo.nativeapp.data

import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 轻量天气 —— wttr.in 免 key、根据 IP 自动定位。镜像 src/lib/weather.ts。
 *
 * 1 小时内存缓存。失败返回 null，AI prompt 那边 fallback 不提天气。
 */
object Weather {
    data class Snapshot(val summary: String, val tempC: Int)

    @Volatile private var cached: Pair<Long, Snapshot?>? = null
    private const val MEMO_TTL_MS = 60L * 60 * 1000

    // 复用全 app 共享连接池,超时与旧值一致(连接 3s / 读 3s)。
    private val http by lazy {
        PipoHttp.client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

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
        val request = Request.Builder()
            .url("https://wttr.in/?format=j1&lang=zh-cn")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
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
        }
    }
}
