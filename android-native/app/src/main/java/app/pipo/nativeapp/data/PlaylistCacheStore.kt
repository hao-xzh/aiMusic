package app.pipo.nativeapp.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 歌单列表 + 每张歌单 tracks 的磁盘持久化。
 *
 * 当前 RustBridgeRepository 只在进程内存里缓存，冷启动后重新走网。
 * 这层让冷启动直接看到上次的歌单（stale-while-revalidate）：
 *
 *   - load(userId)：app 启动时同步读，喂给 playlistState / tracksMemoryCache。
 *   - save(...)：每次 refreshPlaylists / tracksForPlaylist 网络成功后异步落盘。
 *   - clear(userId)：换账号 / 退登时清掉旧数据。
 *
 * userId key：避免 A 账号的歌单显示给 B 账号。userId 不匹配时直接忽略 cache。
 *
 * TTL：不强失效，只在 cache age > 24h 时让调用方知道"该后台 refresh 了"。
 * 用户的歌单列表本身变化不频繁，这层主要解决"打开 app 黑屏一秒"。
 */
class PlaylistCacheStore(context: Context) {

    private val file: File = File(context.applicationContext.filesDir, FILE_NAME)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Snapshot(
        val userId: Long,
        val savedAtMs: Long,
        val playlists: List<PipoPlaylist>,
        /** playlistId → tracks。可能为空 map（首次只缓存了列表，没缓存 tracks） */
        val tracks: Map<Long, List<NativeTrack>>,
    ) {
        val ageMs: Long get() = System.currentTimeMillis() - savedAtMs
        val isStale: Boolean get() = ageMs > STALE_AFTER_MS
    }

    /** 同步读 —— 在 RustBridgeRepository.init 调用，必须秒回。 */
    fun load(): Snapshot? {
        if (!file.exists()) return null
        return try {
            val raw = file.readText()
            val obj = JSONObject(raw)
            if (obj.optInt("v") != VERSION) return null
            val userId = obj.optLong("userId", 0L).takeIf { it != 0L } ?: return null
            val savedAtMs = obj.optLong("savedAtMs", 0L)
            val plArr = obj.optJSONArray("playlists") ?: return null
            val playlists = ArrayList<PipoPlaylist>(plArr.length())
            for (i in 0 until plArr.length()) {
                val p = plArr.optJSONObject(i) ?: continue
                playlists.add(decodePlaylist(p) ?: continue)
            }
            val tracksObj = obj.optJSONObject("tracks")
            val tracksMap = HashMap<Long, List<NativeTrack>>()
            if (tracksObj != null) {
                tracksObj.keys().forEach { k ->
                    val pid = k.toLongOrNull() ?: return@forEach
                    val arr = tracksObj.optJSONArray(k) ?: return@forEach
                    val list = ArrayList<NativeTrack>(arr.length())
                    for (i in 0 until arr.length()) {
                        val t = arr.optJSONObject(i) ?: continue
                        list.add(decodeTrack(t) ?: continue)
                    }
                    tracksMap[pid] = list
                }
            }
            Snapshot(userId, savedAtMs, playlists, tracksMap)
        } catch (_: Exception) {
            // 坏文件直接清 —— 别让坏 JSON 永久卡住启动
            runCatching { file.delete() }
            null
        }
    }

    /**
     * 异步落盘。playlists 必传；tracks 可以是当前内存里所有累计的 playlistId→tracks。
     * 调用方传啥就写啥，不增量 merge —— 让最新一次 save 当 source of truth。
     */
    fun save(userId: Long, playlists: List<PipoPlaylist>, tracks: Map<Long, List<NativeTrack>>) {
        if (userId == 0L || playlists.isEmpty()) return
        // 主线程只做引用快照（List/Map 不可变下的 immutable copy 太贵就跳过），
        // 实际 JSON 序列化在 IO 线程
        val plSnapshot = playlists.toList()
        val tracksSnapshot = HashMap(tracks)
        ioScope.launch { writeToDisk(userId, plSnapshot, tracksSnapshot) }
    }

    fun clear() {
        ioScope.launch { runCatching { file.delete() } }
    }

    private fun writeToDisk(userId: Long, playlists: List<PipoPlaylist>, tracks: Map<Long, List<NativeTrack>>) {
        val plArr = JSONArray()
        playlists.forEach { plArr.put(encodePlaylist(it)) }
        val tracksObj = JSONObject()
        tracks.forEach { (pid, list) ->
            val arr = JSONArray()
            list.forEach { arr.put(encodeTrack(it)) }
            tracksObj.put(pid.toString(), arr)
        }
        val obj = JSONObject().apply {
            put("v", VERSION)
            put("userId", userId)
            put("savedAtMs", System.currentTimeMillis())
            put("playlists", plArr)
            put("tracks", tracksObj)
        }
        runCatching {
            // 写到 .tmp 再 rename：避免写到一半被 kill 留下半截 JSON
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(obj.toString())
            tmp.renameTo(file)
        }
    }

    private fun encodePlaylist(p: PipoPlaylist): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("trackCount", p.trackCount)
        p.coverUrl?.let { put("coverUrl", it) }
        p.userId?.let { put("userId", it) }
        p.updateTime?.let { put("updateTime", it) }
    }

    private fun decodePlaylist(o: JSONObject): PipoPlaylist? = try {
        PipoPlaylist(
            id = o.optLong("id"),
            name = o.optString("name"),
            trackCount = o.optInt("trackCount"),
            coverUrl = o.optString("coverUrl").takeIf { it.isNotBlank() },
            userId = if (o.has("userId")) o.optLong("userId") else null,
            updateTime = if (o.has("updateTime")) o.optLong("updateTime") else null,
        )
    } catch (_: Exception) { null }

    private fun encodeTrack(t: NativeTrack): JSONObject = JSONObject().apply {
        put("id", t.id)
        t.neteaseId?.let { put("ne", it) }
        put("title", t.title)
        put("artist", t.artist)
        put("album", t.album)
        // ⚠️ 故意不持久化 streamUrl —— netease 直链是时效签名 URL（几小时~1天就过期），
        //    存盘下次启动用旧 URL 进 ExoPlayer 会 403/404 没声。让 resolvePlayableQueue
        //    每次冷启动通过 neteaseId 重新拉直链。
        t.artworkUrl?.let { put("artworkUrl", it) }
        put("durationMs", t.durationMs)
    }

    private fun decodeTrack(o: JSONObject): NativeTrack? = try {
        NativeTrack(
            id = o.optString("id").ifEmpty { return null },
            neteaseId = if (o.has("ne")) o.optLong("ne") else null,
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            streamUrl = "",  // 见 encodeTrack 注释：从盘恢复时 streamUrl 必须空，强制重新签名
            artworkUrl = o.optString("artworkUrl").takeIf { it.isNotBlank() },
            durationMs = o.optLong("durationMs", 0L),
        )
    } catch (_: Exception) { null }

    companion object {
        private const val FILE_NAME = "playlist_cache_v1.json"
        private const val VERSION = 1
        private const val STALE_AFTER_MS = 24L * 3600 * 1000  // 24h
    }
}
