package app.pipo.nativeapp.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
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

    /** AtomicLong + CAS:saveThrottled 在 player tick 上跑,主线程 + IO 完成回调可能并发。
     *  之前裸 `var` 会让两个线程同时通过 `now - lastSavedAtMs < THROTTLE` 判断,都赋值同一个 now,
     *  都 launch 写盘 → 浪费一次 IO。CAS 保证只有"第一个抢到节流窗口的"会写。 */
    private val lastSavedAtMs = AtomicLong(0L)
    private val writeRevision = AtomicLong(0L)
    private val writeLock = Any()
    private var metadataLoaded = false
    private var continuationJson: String? = null
    private var playbackMode: String? = null

    data class Snapshot(
        val queue: List<NativeTrack>,
        val currentIndex: Int,
        val positionMs: Long,
    )

    @Synchronized
    private fun loadMetadata() {
        if (metadataLoaded) return
        val saved = runCatching { JSONObject(prefs.getString(KEY, "{}").orEmpty()) }.getOrNull()
        continuationJson = saved?.opt("continuation")?.takeUnless { it == JSONObject.NULL }?.toString()
        playbackMode = saved?.optString("playbackMode")?.takeIf { it.isNotBlank() }
        metadataLoaded = true
    }

    @Synchronized
    fun continuationSnapshot(): AiQueueContinuationSnapshot? {
        loadMetadata()
        return continuationJson?.let { raw ->
            runCatching { AiQueueContinuationSnapshot.fromJson(JSONObject(raw)) }.getOrNull()
        }
    }

    /** A damaged scoped request must not silently become unrestricted recommendations. */
    @Synchronized
    fun hasContinuation(): Boolean {
        loadMetadata()
        return continuationJson != null
    }

    fun restoreContinuation(
        repository: PipoRepository, currentTrack: NativeTrack?, queue: List<NativeTrack>,
    ): ContinuousQueueSource? {
        if (!hasContinuation()) return null
        continuationSnapshot()?.let { return it.restoreSource(repository, currentTrack, queue) }
        return object : ContinuousQueueSource {
            override suspend fun fetchMore(excludeIds: Set<Long>): List<NativeTrack> =
                error("原来的选歌条件暂时无法恢复，请重新选择播放列表")
            override fun permitsDefaultFallback() = false
            override fun startsAutomatically() = false
            override fun acceptsResolved(track: NativeTrack) = false
        }
    }

    @Synchronized
    fun savedPlaybackMode(): String? {
        loadMetadata()
        return playbackMode
    }

    /** Called only when a new main queue takes ownership; insertions keep its scope. */
    @Synchronized
    fun setContinuation(snapshot: AiQueueContinuationSnapshot?) {
        loadMetadata()
        continuationJson = snapshot?.toJson()?.toString()
    }

    @Synchronized
    fun setPlaybackMode(mode: String) {
        loadMetadata()
        playbackMode = mode
    }

    @Synchronized
    private fun metadata(): Pair<String?, String?> {
        loadMetadata()
        return continuationJson to playbackMode
    }

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
        val prev = lastSavedAtMs.get()
        if (now - prev < THROTTLE_MS) return
        // CAS:只有第一个跨越节流窗口的调用会通过;其它在窗口期内的并发调用全 skip
        if (!lastSavedAtMs.compareAndSet(prev, now)) return
        // 主线程只做一次窗口浅拷贝：长歌单保留当前曲附近，而不是永远截前 200 首。
        val (snap, snapIndex) = snapshotWindow(queue, currentIndex)
        enqueueSnapshot(snap, snapIndex, positionMs)
    }

    /** 强制落盘（用户主动切歌 / 应用进入后台时调）—— 同样异步，不阻塞调用方 */
    fun save(queue: List<NativeTrack>, currentIndex: Int, positionMs: Long) {
        if (queue.isEmpty()) return
        val (snap, snapIndex) = snapshotWindow(queue, currentIndex)
        enqueueSnapshot(snap, snapIndex, positionMs)
    }

    @Synchronized
    private fun enqueueSnapshot(queue: List<NativeTrack>, currentIndex: Int, positionMs: Long) {
        val metadata = metadata()
        val revision = writeRevision.incrementAndGet()
        ioScope.launch { writeSnapshot(queue, currentIndex, positionMs, metadata, revision) }
    }

    private fun writeSnapshot(
        queue: List<NativeTrack>, currentIndex: Int, positionMs: Long,
        metadata: Pair<String?, String?>, revision: Long,
    ) {
        val arr = JSONArray()
        queue.forEach { arr.put(encodeTrack(it)) }
        val safeIndex = if (queue.isEmpty()) 0 else currentIndex.coerceIn(0, queue.size - 1)
        val obj = JSONObject()
            .put("queue", arr)
            .put("currentIndex", safeIndex)
            .put("positionMs", positionMs.coerceAtLeast(0))
            .put("savedAtMs", System.currentTimeMillis())
        metadata.first?.let { obj.put("continuation", it) }
        metadata.second?.let { obj.put("playbackMode", it) }
        synchronized(writeLock) {
            if (writeRevision.get() != revision) return
            prefs.edit().putString(KEY, obj.toString()).apply()
        }
    }

    private fun snapshotWindow(queue: List<NativeTrack>, currentIndex: Int): Pair<List<NativeTrack>, Int> {
        if (queue.isEmpty()) return emptyList<NativeTrack>() to 0
        val safeIndex = currentIndex.coerceIn(0, queue.size - 1)
        if (queue.size <= MAX_QUEUE) return queue.toList() to safeIndex
        val start = (safeIndex - MAX_QUEUE / 2).coerceIn(0, queue.size - MAX_QUEUE)
        val snap = queue.subList(start, start + MAX_QUEUE).toList()
        return snap to (safeIndex - start)
    }

    @Synchronized
    fun clear() {
        continuationJson = null
        playbackMode = null
        metadataLoaded = true
        synchronized(writeLock) {
            writeRevision.incrementAndGet()
            prefs.edit().remove(KEY).apply()
        }
    }

    // 故意不持久化 streamUrl：直链有时效，恢复后必须由上层重新签名。
    private fun encodeTrack(t: NativeTrack): JSONObject = NativeTrackJson.encode(t)

    private fun decodeTrack(o: JSONObject): NativeTrack? = NativeTrackJson.decode(o)

    companion object {
        private const val PREFS_NAME = "claudio_last_playback"
        private const val KEY = "v1"
        private const val THROTTLE_MS = 3000L
        private const val MAX_QUEUE = 200
    }
}
