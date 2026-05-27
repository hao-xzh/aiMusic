package app.pipo.nativeapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 推荐日志 —— 镜像 src/lib/recommendation-log.ts。
 *
 * 让 AI 知道"24h 内推过的歌别再推"，避免 candidate-recall 重复同一批结果。
 * 每次 AI 出了 batch 都 log 一次（source=pet）；自动播放推荐用 source=auto；电台 radio。
 */
class RecommendationLog(context: Context) {
    enum class Source(val key: String) { Pet("pet"), Auto("auto"), Radio("radio"), Search("search") }

    data class Event(
        val trackId: Long,
        val tsSec: Long,
        val source: Source,
        /** 主艺人名 —— v2 新增,做 artist-level fatigue;老事件没这字段当 null */
        val artist: String? = null,
    )

    data class RecentContext(
        val last24hTrackIds: Set<Long>,
        val last7dTrackIds: Set<Long>,
        /** 近 24h 每个艺人(normalize 后)被推荐的次数 */
        val last24hArtistCounts: Map<String, Int> = emptyMap(),
        /** 近 7d 同上 */
        val last7dArtistCounts: Map<String, Int> = emptyMap(),
    )

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var buffer: MutableList<Event>? = null

    @Synchronized
    private fun ensureBuffer(): MutableList<Event> {
        buffer?.let { return it }
        val raw = prefs.getString(KEY, null)
        val out = mutableListOf<Event>()
        if (raw != null) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val srcKey = o.optString("source")
                    val src = Source.entries.firstOrNull { it.key == srcKey } ?: Source.Pet
                    val artistField = o.optString("artist", "")
                    val artist = if (artistField.isBlank()) null else artistField
                    out.add(Event(o.optLong("trackId"), o.optLong("ts"), src, artist))
                }
            }
        }
        buffer = out
        return out
    }

    @Synchronized
    fun log(trackIds: List<Long>, source: Source = Source.Pet) {
        if (trackIds.isEmpty()) return
        val buf = ensureBuffer()
        val now = System.currentTimeMillis() / 1000
        val seen = HashSet<Long>()
        for (id in trackIds) {
            if (!seen.add(id)) continue
            buf.add(Event(id, now, source))
        }
        flush()
    }

    /**
     * v2:支持传 NativeTrack[],把主艺人名一起写进日志,后续 fatigue 才能按艺人聚合。
     * 新代码请尽量用这个;老的 log(List<Long>) 保留兼容。
     */
    @Synchronized
    fun logTracks(tracks: List<NativeTrack>, source: Source = Source.Pet) {
        if (tracks.isEmpty()) return
        val buf = ensureBuffer()
        val now = System.currentTimeMillis() / 1000
        val seen = HashSet<Long>()
        for (t in tracks) {
            val id = t.neteaseId ?: continue
            if (!seen.add(id)) continue
            val artist = t.artist.split('/', '&', ',', '、').firstOrNull()?.trim()
            buf.add(Event(id, now, source, artist))
        }
        flush()
    }

    @Synchronized
    private fun flush() {
        val buf = buffer ?: return
        val trimmed = if (buf.size > MAX_EVENTS) buf.takeLast(MAX_EVENTS).toMutableList() else buf
        buffer = trimmed
        val arr = JSONArray()
        trimmed.forEach {
            arr.put(JSONObject().apply {
                put("trackId", it.trackId); put("ts", it.tsSec); put("source", it.source.key)
                if (!it.artist.isNullOrBlank()) put("artist", it.artist)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun readAll(): List<Event> = ensureBuffer().toList()

    fun recentContext(): RecentContext {
        val events = readAll()
        val now = System.currentTimeMillis() / 1000
        val day = mutableSetOf<Long>()
        val week = mutableSetOf<Long>()
        val dayArtist = HashMap<String, Int>()
        val weekArtist = HashMap<String, Int>()
        for (e in events) {
            val age = now - e.tsSec
            val in7d = age <= 7 * DAY_S
            val in24h = age <= DAY_S
            if (in24h) day.add(e.trackId)
            if (in7d) week.add(e.trackId)
            val artist = e.artist ?: continue
            val key = normalizeArtistKey(artist)
            if (key.isEmpty()) continue
            if (in24h) dayArtist[key] = (dayArtist[key] ?: 0) + 1
            if (in7d) weekArtist[key] = (weekArtist[key] ?: 0) + 1
        }
        return RecentContext(day, week, dayArtist, weekArtist)
    }

    private fun normalizeArtistKey(s: String): String =
        s.lowercase().replace(Regex("[\\s'\"`·・\\-－—_,，。.、!?！？]+"), "")

    companion object {
        private const val PREFS_NAME = "claudio_recommendation_log"
        private const val KEY = "v1"
        private const val MAX_EVENTS = 800
        private const val DAY_S = 24 * 3600L
    }
}
