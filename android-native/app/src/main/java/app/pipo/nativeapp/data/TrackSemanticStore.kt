package app.pipo.nativeapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 单曲语义档案存储 —— 内存层 ConcurrentHashMap + 持久层 SharedPreferences JSON。
 *
 *   - put / get：跟 AudioFeaturesStore 同模式，每首歌单独成 entry
 *   - 上限 5000 条，FIFO 淘汰最旧的（按 updatedAtMs）
 *   - LLM 调用很贵，所以这层 cache 永远先查内存再查盘
 */
class TrackSemanticStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val memory = ConcurrentHashMap<String, TrackSemanticProfile>()

    init {
        loadAll().forEach { (id, p) -> memory[id] = p }
    }

    fun get(trackId: String): TrackSemanticProfile? = memory[trackId]

    fun getMany(trackIds: Collection<String>): Map<String, TrackSemanticProfile> {
        val out = LinkedHashMap<String, TrackSemanticProfile>(trackIds.size)
        for (id in trackIds) memory[id]?.let { out[id] = it }
        return out
    }

    fun has(trackId: String): Boolean = memory.containsKey(trackId)

    fun put(profile: TrackSemanticProfile) {
        memory[profile.trackId] = profile
        persistMerge(profile)
    }

    fun putAll(profiles: Collection<TrackSemanticProfile>) {
        if (profiles.isEmpty()) return
        profiles.forEach { memory[it.trackId] = it }
        // 一次落盘，避免多次 commit
        val current = readJsonMap()
        profiles.forEach { current.put(it.trackId, encode(it)) }
        capAndWrite(current)
    }

    fun count(): Int = memory.size

    private fun persistMerge(profile: TrackSemanticProfile) {
        val current = readJsonMap()
        current.put(profile.trackId, encode(profile))
        capAndWrite(current)
    }

    private fun readJsonMap(): JSONObject {
        val raw = prefs.getString(KEY, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
    }

    private fun capAndWrite(obj: JSONObject) {
        if (obj.length() > MAX_ENTRIES) {
            // 淘汰最旧的；只在超出时才触发
            val keys = obj.keys().asSequence().toList()
            val byAge = keys.sortedBy { obj.optJSONObject(it)?.optLong("updatedAtMs", 0L) ?: 0L }
            val drop = byAge.take(obj.length() - MAX_ENTRIES)
            drop.forEach { obj.remove(it) }
        }
        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    private fun loadAll(): Map<String, TrackSemanticProfile> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = mutableMapOf<String, TrackSemanticProfile>()
            obj.keys().forEach { k ->
                val o = obj.optJSONObject(k) ?: return@forEach
                decode(k, o)?.let { out[k] = it }
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun encode(p: TrackSemanticProfile): JSONObject = JSONObject().apply {
        put("v", p.version)
        put("title", p.title)
        put("artists", JSONArray(p.artists))
        p.album?.let { put("album", it) }
        p.year?.let { put("year", it) }
        put("language", p.language.key)
        put("languageConfidence", p.languageConfidence)
        put("region", p.region.key)
        put("regionConfidence", p.regionConfidence)
        put("genres", JSONArray(p.genres))
        put("subGenres", JSONArray(p.subGenres))
        put("styleAnchors", JSONArray(p.styleAnchors))
        put("moods", JSONArray(p.moods))
        put("scenes", JSONArray(p.scenes))
        put("textures", JSONArray(p.textures))
        put("energyWords", JSONArray(p.energyWords))
        put("tempoFeel", JSONArray(p.tempoFeel))
        put("vocalType", p.vocalType.key)
        put("vocalDelivery", JSONArray(p.vocalDelivery))
        p.decade?.let { put("decade", it) }
        p.bpm?.let { put("bpm", it) }
        p.energy?.let { put("energy", it) }
        put("negativeTags", JSONArray(p.negativeTags))
        put("summary", p.summary)
        put("embeddingText", p.embeddingText)
        put("confidence", p.confidence)
        put("sourceMetadata", p.sourceMetadata)
        put("sourceLyrics", p.sourceLyrics)
        put("sourceAudio", p.sourceAudio)
        put("sourceLlm", p.sourceLlm)
        put("updatedAtMs", p.updatedAtMs)
    }

    private fun decode(key: String, o: JSONObject): TrackSemanticProfile? = try {
        if (o.optInt("v", 0) != TRACK_SEMANTIC_VERSION) null
        else TrackSemanticProfile(
            trackId = key,
            version = o.optInt("v"),
            title = o.optString("title"),
            artists = jsonArrayToStringList(o.optJSONArray("artists")),
            album = o.optString("album").takeIf { it.isNotBlank() },
            year = if (o.has("year")) o.optInt("year") else null,
            language = TrackLanguage.from(o.optString("language")),
            languageConfidence = o.optDouble("languageConfidence", 0.5),
            region = TrackRegion.from(o.optString("region")),
            regionConfidence = o.optDouble("regionConfidence", 0.5),
            genres = jsonArrayToStringList(o.optJSONArray("genres")),
            subGenres = jsonArrayToStringList(o.optJSONArray("subGenres")),
            styleAnchors = jsonArrayToStringList(o.optJSONArray("styleAnchors")),
            moods = jsonArrayToStringList(o.optJSONArray("moods")),
            scenes = jsonArrayToStringList(o.optJSONArray("scenes")),
            textures = jsonArrayToStringList(o.optJSONArray("textures")),
            energyWords = jsonArrayToStringList(o.optJSONArray("energyWords")),
            tempoFeel = jsonArrayToStringList(o.optJSONArray("tempoFeel")),
            vocalType = VocalType.from(o.optString("vocalType")),
            vocalDelivery = jsonArrayToStringList(o.optJSONArray("vocalDelivery")),
            decade = o.optString("decade").takeIf { it.isNotBlank() },
            bpm = if (o.has("bpm")) o.optDouble("bpm") else null,
            energy = if (o.has("energy")) o.optDouble("energy") else null,
            negativeTags = jsonArrayToStringList(o.optJSONArray("negativeTags")),
            summary = o.optString("summary"),
            embeddingText = o.optString("embeddingText"),
            confidence = o.optDouble("confidence", 0.5),
            sourceMetadata = o.optBoolean("sourceMetadata", true),
            sourceLyrics = o.optBoolean("sourceLyrics", false),
            sourceAudio = o.optBoolean("sourceAudio", false),
            sourceLlm = o.optBoolean("sourceLlm", false),
            updatedAtMs = o.optLong("updatedAtMs", 0L),
        )
    } catch (_: Exception) { null }

    private fun jsonArrayToStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    companion object {
        private const val PREFS_NAME = "claudio_track_semantic"
        private const val KEY = "v1"
        private const val MAX_ENTRIES = 5000
    }
}
