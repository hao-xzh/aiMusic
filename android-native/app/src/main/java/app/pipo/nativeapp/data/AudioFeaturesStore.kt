package app.pipo.nativeapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Symphonia AudioFeatures 跨进程内存 + 持久缓存。
 *
 *   - 内存层有界 LRU map：PlayerViewModel.startFeaturePrefetch 写入，
 *     applyTransitionFade（gapless 头尾静音裁切）与 DistillEngine（acoustic
 *     summary 聚合）读取。
 *   - 持久层 SharedPreferences：每首独立 entry，跨 session 复用上次分析过的 feature。
 *     旧版整段 JSON 只在初始化读取一次，迁移落盘在后台完成。
 */
class AudioFeaturesStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 用 trackId（String）当 key —— PlayerViewModel 缓存也是按 trackId 索引。
    // 内存按读取/写入 LRU 淘汰；持久层则按写入顺序淘汰，两者都限制在 1000 首。
    private val memory = LinkedHashMap<String, AudioFeatures>(MAX_ENTRIES, 0.75f, true)
    private val memoryLock = Any()
    private val pending = LinkedHashMap<String, AudioFeatures>()
    private var flushScheduled = false

    // 所有写入都走同一个后台 writer。put 在 250ms 内合并为一笔 XML 写入，
    // 迁移和后续批次保持顺序，不会有旧快照覆盖较晚写入的问题。
    private val writer = Executors.newSingleThreadScheduledExecutor()
    private val persistedOrder = LinkedHashSet<String>()

    init {
        // 保留既有的有界启动预热，避免首播把已缓存特征误判为空并重新联网分析。
        // 只读一次，后续 put 不再解析旧 JSON；迁移写入仍交给后台 writer。
        loadAndMigrate()
    }

    fun get(trackId: String): AudioFeatures? = synchronized(memoryLock) { memory[trackId] }

    /** 批量查 —— 缺的返回 null（DistillEngine 会过滤掉） */
    fun getMany(trackIds: List<String>): List<AudioFeatures?> = synchronized(memoryLock) {
        trackIds.map { memory[it] }
    }

    fun put(trackId: String, features: AudioFeatures) {
        synchronized(memoryLock) {
            putMemoryLocked(trackId, features)
            // 同一把锁内覆盖同曲的较早 pending 值，再安排一次批量写入。
            // 因此并发 put 不会出现内存较新、磁盘较旧的反向提交。
            putRecent(pending, trackId, features)
            cap(pending)
            scheduleFlushLocked()
        }
    }

    private fun loadAndMigrate() {
        val indexed = loadIndexedEntries()
        val hasLegacy = prefs.contains(KEY)
        // 旧整 JSON 只在这里解析一次；新 entry 后加入 map，因此永远优先。
        val merged = LinkedHashMap<String, AudioFeatures>()
        if (hasLegacy) merged.putAll(loadLegacyEntries())
        indexed.forEach { (id, features) -> putRecent(merged, id, features) }
        // put 可在初始化尚未完成时发生；内存中的值是当前进程的最新值。
        synchronized(memoryLock) {
            memory.forEach { (id, features) -> putRecent(merged, id, features) }
            cap(merged)
            persistedOrder.clear()
            persistedOrder.addAll(merged.keys)
            // 合并、淘汰、替换保持在同一临界区，不能在回填间隙覆盖新的 put。
            memory.clear()
            memory.putAll(merged)
        }

        if (hasLegacy) writer.execute {
            val previousKeys = indexed.keys
            val editor = prefs.edit()
            merged.forEach { (id, features) ->
                editor.putString(entryKey(id), encode(features).toString())
            }
            previousKeys.filterNot { it in merged }.forEach { editor.remove(entryKey(it)) }
            editor
                .putString(INDEX_KEY, JSONArray(persistedOrder).toString())
                .remove(KEY)
                .apply()
        }
    }

    private fun scheduleFlushLocked() {
        if (flushScheduled) return
        flushScheduled = true
        writer.schedule(::flushPending, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun flushPending() {
        val batch = synchronized(memoryLock) {
            flushScheduled = false
            if (pending.isEmpty()) return
            LinkedHashMap(pending).also { pending.clear() }
        }
        batch.keys.forEach { putRecent(persistedOrder, it) }
        val evicted = cap(persistedOrder)
        val editor = prefs.edit().putString(INDEX_KEY, JSONArray(persistedOrder).toString())
        batch.forEach { (trackId, features) ->
            editor.putString(entryKey(trackId), encode(features).toString())
        }
        evicted.forEach { editor.remove(entryKey(it)) }
        editor.apply()
    }

    private fun loadIndexedEntries(): LinkedHashMap<String, AudioFeatures> {
        val rawIndex = prefs.getString(INDEX_KEY, null) ?: return LinkedHashMap()
        val ids = runCatching { JSONArray(rawIndex) }.getOrNull() ?: return LinkedHashMap()
        return LinkedHashMap<String, AudioFeatures>().apply {
            for (index in 0 until ids.length()) {
                val id = ids.optString(index).takeIf { it.isNotBlank() } ?: continue
                val raw = prefs.getString(entryKey(id), null) ?: continue
                val features = runCatching { JSONObject(raw) }.getOrNull()?.let(::decode) ?: continue
                putRecent(this, id, features)
            }
            cap(this)
        }
    }

    private fun loadLegacyEntries(): LinkedHashMap<String, AudioFeatures> {
        val raw = prefs.getString(KEY, null) ?: return LinkedHashMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return LinkedHashMap()
        return LinkedHashMap<String, AudioFeatures>().apply {
            obj.keys().forEach { id ->
                obj.optJSONObject(id)?.let(::decode)?.let { putRecent(this, id, it) }
            }
            cap(this)
        }
    }

    private fun putMemoryLocked(trackId: String, features: AudioFeatures) {
        putRecent(memory, trackId, features)
        cap(memory)
    }

    private fun <V> putRecent(map: MutableMap<String, V>, key: String, value: V) {
        map.remove(key)
        map[key] = value
    }

    private fun putRecent(set: LinkedHashSet<String>, value: String) {
        set.remove(value)
        set.add(value)
    }

    private fun <V> cap(map: MutableMap<String, V>) {
        while (map.size > MAX_ENTRIES) map.remove(map.entries.first().key)
    }

    private fun cap(set: LinkedHashSet<String>): List<String> {
        val evicted = mutableListOf<String>()
        while (set.size > MAX_ENTRIES) {
            set.firstOrNull()?.let {
                set.remove(it)
                evicted += it
            }
        }
        return evicted
    }

    private fun encode(f: AudioFeatures): JSONObject = JSONObject().apply {
        put("trackId", f.trackId)
        put("durationS", f.durationS)
        f.bpm?.let { put("bpm", it) }
        put("bpmConfidence", f.bpmConfidence)
        f.firstBeatS?.let { put("firstBeatS", it) }
        put("rmsDb", f.rmsDb)
        put("peakDb", f.peakDb)
        put("dynamicRangeDb", f.dynamicRangeDb)
        put("introEnergy", f.introEnergy)
        put("outroEnergy", f.outroEnergy)
        put("introLowEnergy", f.introLowEnergy)
        put("outroLowEnergy", f.outroLowEnergy)
        put("introVocalDensity", f.introVocalDensity)
        put("outroVocalDensity", f.outroVocalDensity)
        f.drumEntryS?.let { put("drumEntryS", it) }
        f.vocalEntryS?.let { put("vocalEntryS", it) }
        f.outroStartS?.let { put("outroStartS", it) }
        put("spectralCentroidHz", f.spectralCentroidHz)
        f.tonalKey?.let { put("tonalKey", it) }
        put("tonalConfidence", f.tonalConfidence)
        put("headSilenceS", f.headSilenceS)
        put("tailSilenceS", f.tailSilenceS)
    }

    private fun decode(o: JSONObject): AudioFeatures? = try {
        AudioFeatures(
            trackId = o.optLong("trackId"),
            durationS = o.optDouble("durationS"),
            bpm = if (o.has("bpm")) o.optDouble("bpm") else null,
            bpmConfidence = o.optDouble("bpmConfidence"),
            firstBeatS = if (o.has("firstBeatS")) o.optDouble("firstBeatS") else null,
            rmsDb = o.optDouble("rmsDb"),
            peakDb = o.optDouble("peakDb"),
            dynamicRangeDb = o.optDouble("dynamicRangeDb"),
            introEnergy = o.optDouble("introEnergy"),
            outroEnergy = o.optDouble("outroEnergy"),
            introLowEnergy = o.optDouble("introLowEnergy", 0.0),
            outroLowEnergy = o.optDouble("outroLowEnergy", 0.0),
            introVocalDensity = o.optDouble("introVocalDensity", 0.0),
            outroVocalDensity = o.optDouble("outroVocalDensity", 0.0),
            drumEntryS = if (o.has("drumEntryS")) o.optDouble("drumEntryS") else null,
            vocalEntryS = if (o.has("vocalEntryS")) o.optDouble("vocalEntryS") else null,
            outroStartS = if (o.has("outroStartS")) o.optDouble("outroStartS") else null,
            spectralCentroidHz = o.optDouble("spectralCentroidHz"),
            tonalKey = if (o.has("tonalKey")) o.optInt("tonalKey") else null,
            tonalConfidence = o.optDouble("tonalConfidence", 0.0),
            headSilenceS = o.optDouble("headSilenceS"),
            tailSilenceS = o.optDouble("tailSilenceS"),
        )
    } catch (_: Exception) { null }

    companion object {
        private const val PREFS_NAME = "claudio_audio_features"
        private const val KEY = "v2"
        private const val INDEX_KEY = "v3_index"
        private const val ENTRY_PREFIX = "v3_feature:"
        private const val MAX_ENTRIES = 1000
        private const val FLUSH_DELAY_MS = 250L

        private fun entryKey(trackId: String): String = "$ENTRY_PREFIX$trackId"
    }
}
