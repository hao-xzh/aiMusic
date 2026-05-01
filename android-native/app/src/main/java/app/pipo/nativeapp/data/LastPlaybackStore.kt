package app.pipo.nativeapp.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 持久化最后一次播放的快照。镜像 src/lib/player-state.tsx 里
 * LAST_TRACK_KEY / LAST_POSITION_KEY / LAST_QUEUE_KEY 的语义。
 *
 *   - 杀掉冷启动黑屏 + 跳到 playlist[0] 的尴尬
 *   - 节流 ~3s 写一次磁盘，避免每个进度 tick 都 sync
 *   - load() 同步返回，PlayerViewModel.init 直接用做初始 state
 */
class LastPlaybackStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    /** 200 首 queue 的 JSON 序列化能到 ~10ms，不能跑在主线程 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastSavedAtMs = 0L

    data class Snapshot(
        val queue: List<NativeTrack>,
        val currentIndex: Int,
        val positionMs: Long,
    )

    fun load(): Snapshot? {
        val raw = prefs.getString(KEY, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("queue") ?: return null
            val queue = mutableListOf<NativeTrack>()
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                queue.add(decodeTrack(t) ?: continue)
            }
            if (queue.isEmpty()) return null
            val idx = obj.optInt("currentIndex", 0).coerceIn(0, queue.size - 1)
            val pos = obj.optLong("positionMs", 0L).coerceAtLeast(0L)
            Snapshot(queue = queue, currentIndex = idx, positionMs = pos)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 节流 ≥ THROTTLE_MS 落盘一次，且 JSON 构建 + prefs 写都丢到 IO 调度器。
     * 调用方（syncFrom）在每个 player tick 都可以 fire-and-forget。
     */
    fun saveThrottled(queue: List<NativeTrack>, currentIndex: Int, positionMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastSavedAtMs < THROTTLE_MS) return
        lastSavedAtMs = now
        // 主线程只做一次 list 浅拷贝（List 不可变，元素是 data class，安全）
        val snap = queue.take(MAX_QUEUE)
        ioScope.launch { writeSnapshot(snap, currentIndex, positionMs) }
    }

    /** 强制落盘（用户主动切歌 / 应用进入后台时调）—— 同样异步，不阻塞调用方 */
    fun save(queue: List<NativeTrack>, currentIndex: Int, positionMs: Long) {
        if (queue.isEmpty()) return
        val snap = queue.take(MAX_QUEUE)
        ioScope.launch { writeSnapshot(snap, currentIndex, positionMs) }
    }

    private fun writeSnapshot(queue: List<NativeTrack>, currentIndex: Int, positionMs: Long) {
        val arr = JSONArray()
        queue.forEach { arr.put(encodeTrack(it)) }
        val obj = JSONObject()
            .put("queue", arr)
            .put("currentIndex", currentIndex.coerceAtLeast(0))
            .put("positionMs", positionMs.coerceAtLeast(0))
            .put("savedAtMs", System.currentTimeMillis())
        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun encodeTrack(t: NativeTrack): JSONObject = JSONObject().apply {
        put("id", t.id)
        t.neteaseId?.let { put("neteaseId", it) }
        put("title", t.title)
        put("artist", t.artist)
        put("album", t.album)
        // ⚠️ 故意不持久化 streamUrl —— netease 直链是时效签名 URL（几小时~1天就过期），
        //    冷启动用旧 URL 给 ExoPlayer 会 403/404 没声。让 resolvePlayableQueue
        //    通过 neteaseId 重新拉直链。
        t.artworkUrl?.let { put("artworkUrl", it) }
        put("durationMs", t.durationMs)
    }

    private fun decodeTrack(o: JSONObject): NativeTrack? = try {
        NativeTrack(
            id = o.optString("id").ifEmpty { return null },
            neteaseId = if (o.has("neteaseId")) o.optLong("neteaseId") else null,
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            streamUrl = "",  // 见 encodeTrack 注释：恢复时强制空，让上层重新签名
            artworkUrl = o.optString("artworkUrl").takeIf { it.isNotBlank() },
            durationMs = o.optLong("durationMs", 0L),
        )
    } catch (_: Exception) { null }

    companion object {
        private const val PREFS_NAME = "claudio_last_playback"
        private const val KEY = "v1"
        private const val THROTTLE_MS = 3000L
        private const val MAX_QUEUE = 200
    }
}
