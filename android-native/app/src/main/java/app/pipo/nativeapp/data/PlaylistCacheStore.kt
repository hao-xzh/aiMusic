package app.pipo.nativeapp.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.LinkedHashMap

/**
 * 歌单列表 + 每张歌单 tracks 的磁盘持久化。
 *
 * 当前 RustBridgeRepository 只在进程内存里缓存，冷启动后重新走网。
 * 这层让冷启动直接看到上次的歌单（stale-while-revalidate）：
 *
 *   - load()：app 启动后的 IO 恢复，喂给 playlistState / tracksMemoryCache。
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
    private val tempFile: File = File(file.absolutePath + ".tmp")
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Any()
    private val pendingWrites = ArrayDeque<WriteOperation>()
    private var writerRunning = false

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

    /** 只能从 IO coroutine 调用；超限文件在读入/解析前直接废弃。 */
    fun load(): Snapshot? {
        if (!file.exists()) return null
        return try {
            if (file.length() > MAX_CACHE_BYTES) {
                deleteCacheFiles()
                return null
            }
            val raw = file.readText()
            val obj = JSONObject(raw)
            if (obj.optInt("v") != VERSION) return null
            val userId = obj.optLong("userId", 0L).takeIf { it != 0L } ?: return null
            val savedAtMs = obj.optLong("savedAtMs", 0L)
            // 主动废弃 7 天以上的 cache —— 这种"用户长期不开 app"的场景,旧快照里
            // 大概率有歌单已经被改/删了,先显示 7+ 天前的旧列表然后被网络覆盖会 flash
            // 一下,体验比直接显示空 EmptyState 还差。直接当作无 cache 让用户看到
            // "正在加载"几百 ms 更干净。
            if (savedAtMs > 0 && System.currentTimeMillis() - savedAtMs > MAX_AGE_MS) {
                deleteCacheFiles()
                return null
            }
            val plArr = obj.optJSONArray("playlists") ?: return null
            val playlists = ArrayList<PipoPlaylist>(plArr.length())
            for (i in 0 until plArr.length()) {
                val p = plArr.optJSONObject(i) ?: continue
                playlists.add(decodePlaylist(p) ?: continue)
            }
            val tracksObj = obj.optJSONObject("tracks")
            // JSON 键的落盘顺序就是 LRU 从旧到新的顺序，恢复时必须保留它。
            val tracksMap = LinkedHashMap<Long, List<NativeTrack>>()
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
            deleteCacheFiles()
            null
        }
    }

    /**
     * 异步落盘。playlists 必传；tracks 可以是当前内存里所有累计的 playlistId→tracks。
     * 调用方传啥就写啥，不增量 merge —— 让最新一次 save 当 source of truth。
     */
    fun save(userId: Long, playlists: List<PipoPlaylist>, tracks: Map<Long, List<NativeTrack>>) {
        if (userId == 0L || playlists.isEmpty()) return
        // 主线程只复制集合边界；实际 JSON 序列化只在单一 IO writer 中进行。
        val plSnapshot = playlists.toList()
        val tracksSnapshot = LinkedHashMap(tracks)
        synchronized(writeLock) {
            val request = SaveRequest(userId, plSnapshot, tracksSnapshot)
            // 连续保存只保留最新全量快照；clear 是严格边界，不能跨过它合并。
            if (pendingWrites.lastOrNull() is SaveRequest) {
                pendingWrites.removeLast()
            }
            pendingWrites.addLast(request)
            startWriterLocked()
        }
    }

    fun clear(): Deferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        synchronized(writeLock) {
            // 排队中的旧快照全部失效；正在执行的旧写完成后，clear 会作为下一项删除它。
            pendingWrites.removeAll { it is SaveRequest }
            pendingWrites.addLast(ClearRequest(completion))
            startWriterLocked()
        }
        return completion
    }

    private fun startWriterLocked() {
        if (writerRunning) return
        writerRunning = true
        ioScope.launch { drainWrites() }
    }

    private fun drainWrites() {
        try {
            while (true) {
                val request = synchronized(writeLock) { pendingWrites.pollFirst() } ?: return
                when (request) {
                    // 编码失败只放弃当前快照，不能让 writer 停在 running 状态而卡住后续 clear。
                    is SaveRequest -> runCatching {
                        writeToDisk(request.userId, request.playlists, request.tracks)
                    }
                    is ClearRequest -> {
                        try {
                            deleteCacheFiles()
                        } finally {
                            request.completion.complete(Unit)
                        }
                    }
                }
            }
        } finally {
            synchronized(writeLock) {
                writerRunning = false
                if (pendingWrites.isNotEmpty()) {
                    startWriterLocked()
                }
            }
        }
    }

    private fun writeToDisk(userId: Long, playlists: List<PipoPlaylist>, tracks: Map<Long, List<NativeTrack>>) {
        val savedAtMs = System.currentTimeMillis()
        val playlistJson = JSONArray().also { array -> playlists.forEach { array.put(encodePlaylist(it)) } }.toString()
        val prefix = "{\"v\":$VERSION,\"userId\":$userId,\"savedAtMs\":$savedAtMs,\"playlists\":$playlistJson,\"tracks\":{"
        val entries = tracks.map { (playlistId, list) ->
            val tracksJson = JSONArray().also { array -> list.forEach { array.put(encodeTrack(it)) } }.toString()
            EncodedTracks(playlistId, tracksJson, "\"$playlistId\":$tracksJson".toByteArray(Charsets.UTF_8).size)
        }.toMutableList()
        var payloadBytes = prefix.toByteArray(Charsets.UTF_8).size + TRACKS_SUFFIX_BYTES
        // 到达磁盘上限时仅整张丢弃最久未访问的曲目列表，绝不截短一张歌单。
        entries.forEachIndexed { index, entry ->
            payloadBytes += entry.byteCount + if (index == 0) 0 else 1
        }
        while (payloadBytes > MAX_CACHE_BYTES && entries.isNotEmpty()) {
            val removed = entries.removeAt(0)
            payloadBytes -= removed.byteCount
            if (entries.isNotEmpty()) payloadBytes -= 1
        }
        if (payloadBytes > MAX_CACHE_BYTES) {
            deleteCacheFiles()
            return
        }
        val payload = buildString(payloadBytes) {
            append(prefix)
            entries.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                append('"').append(entry.playlistId).append("\":").append(entry.tracksJson)
            }
            append("}}")
        }
        try {
            tempFile.writeText(payload, Charsets.UTF_8)
            // 同目录 rename 才是原子替换；不能把 rename 失败当作保存成功。
            if (!tempFile.renameTo(file)) {
                tempFile.delete()
            }
        } catch (_: Exception) {
            tempFile.delete()
        }
    }

    private fun deleteCacheFiles() {
        runCatching { file.delete() }
        runCatching { tempFile.delete() }
    }

    private sealed class WriteOperation

    private class SaveRequest(
        val userId: Long,
        val playlists: List<PipoPlaylist>,
        val tracks: Map<Long, List<NativeTrack>>,
    ) : WriteOperation()

    private class ClearRequest(
        val completion: CompletableDeferred<Unit>,
    ) : WriteOperation()

    private class EncodedTracks(
        val playlistId: Long,
        val tracksJson: String,
        val byteCount: Int,
    )

    private fun encodePlaylist(p: PipoPlaylist): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("trackCount", p.trackCount)
        p.coverUrl?.let { put("coverUrl", it) }
        p.userId?.let { put("userId", it) }
        p.updateTime?.let { put("updateTime", it) }
        put("specialType", p.specialType)
    }

    private fun decodePlaylist(o: JSONObject): PipoPlaylist? = try {
        PipoPlaylist(
            id = o.optLong("id"),
            name = o.optString("name"),
            trackCount = o.optInt("trackCount"),
            coverUrl = o.optString("coverUrl").takeIf { it.isNotBlank() },
            userId = if (o.has("userId")) o.optLong("userId") else null,
            updateTime = if (o.has("updateTime")) o.optLong("updateTime") else null,
            specialType = o.optInt("specialType", 0),
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
        private const val STALE_AFTER_MS = 24L * 3600 * 1000  // 24h:isStale 阈值
        private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000  // 7d:超过这个直接废弃 cache
        private const val MAX_CACHE_BYTES = 8L * 1024 * 1024
        private const val TRACKS_SUFFIX_BYTES = 2
    }
}
