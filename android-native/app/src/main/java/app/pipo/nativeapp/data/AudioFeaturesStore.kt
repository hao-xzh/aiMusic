package app.pipo.nativeapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Symphonia AudioFeatures 跨进程内存 + 持久缓存。
 *
 *   - 内存层 ConcurrentHashMap：PlayerViewModel.startFeaturePrefetch 写入，
 *     applyTransitionFade（gapless 头尾静音裁切）与 DistillEngine（acoustic
 *     summary 聚合）读取。
 *   - 持久层 SharedPreferences JSON：跨 session 复用上次分析过的 feature。
 *     ~500 首 × 100B = 50KB，单 read/write 微秒级。
 */
class AudioFeaturesStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 用 trackId（String）当 key —— PlayerViewModel 缓存也是按 trackId 索引
    private val memory = ConcurrentHashMap<String, AudioFeatures>()

    init {
        // 启动时把磁盘缓存预热到内存
        loadAll().forEach { (id, f) -> memory[id] = f }
    }

    fun get(trackId: String): AudioFeatures? = memory[trackId]

    /** 批量查 —— 缺的返回 null（DistillEngine 会过滤掉） */
    fun getMany(trackIds: List<String>): List<AudioFeatures?> = trackIds.map { memory[it] }

    fun put(trackId: String, features: AudioFeatures) {
        memory[trackId] = features
        // 写盘：合并到 JSON map
        val current = prefs.getString(KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
        current.put(trackId, encode(features))
        // cap 1000 条
        if (current.length() > MAX_ENTRIES) {
            val keys = current.keys().asSequence().toList().take(current.length() - MAX_ENTRIES)
            keys.forEach { current.remove(it) }
        }
        prefs.edit().putString(KEY, current.toString()).apply()
    }

    private fun loadAll(): Map<String, AudioFeatures> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = mutableMapOf<String, AudioFeatures>()
            obj.keys().forEach { k ->
                val o = obj.optJSONObject(k) ?: return@forEach
                decode(k, o)?.let { out[k] = it }
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun encode(f: AudioFeatures): JSONObject = JSONObject().apply {
        put("trackId", f.trackId)
        put("durationS", f.durationS)
        f.bpm?.let { put("bpm", it) }
        put("bpmConfidence", f.bpmConfidence)
        put("rmsDb", f.rmsDb)
        put("peakDb", f.peakDb)
        put("dynamicRangeDb", f.dynamicRangeDb)
        put("introEnergy", f.introEnergy)
        put("outroEnergy", f.outroEnergy)
        put("spectralCentroidHz", f.spectralCentroidHz)
        put("headSilenceS", f.headSilenceS)
        put("tailSilenceS", f.tailSilenceS)
    }

    private fun decode(@Suppress("UNUSED_PARAMETER") key: String, o: JSONObject): AudioFeatures? = try {
        AudioFeatures(
            trackId = o.optLong("trackId"),
            durationS = o.optDouble("durationS"),
            bpm = if (o.has("bpm")) o.optDouble("bpm") else null,
            bpmConfidence = o.optDouble("bpmConfidence"),
            rmsDb = o.optDouble("rmsDb"),
            peakDb = o.optDouble("peakDb"),
            dynamicRangeDb = o.optDouble("dynamicRangeDb"),
            introEnergy = o.optDouble("introEnergy"),
            outroEnergy = o.optDouble("outroEnergy"),
            spectralCentroidHz = o.optDouble("spectralCentroidHz"),
            headSilenceS = o.optDouble("headSilenceS"),
            tailSilenceS = o.optDouble("tailSilenceS"),
        )
    } catch (_: Exception) { null }

    companion object {
        private const val PREFS_NAME = "claudio_audio_features"
        private const val KEY = "v1"
        private const val MAX_ENTRIES = 1000
    }
}
