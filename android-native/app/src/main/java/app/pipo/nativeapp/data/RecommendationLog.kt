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

    data class Event(val trackId: Long, val tsSec: Long, val source: Source)

    data class RecentContext(
        val last24hTrackIds: Set<Long>,
        val last7dTrackIds: Set<Long>,
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
                    out.add(Event(o.optLong("trackId"), o.optLong("ts"), src))
                }
            }
        }
        buffer = out
        return out
    }

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

    @Synchronized
    private fun flush() {
        val buf = buffer ?: return
        val trimmed = if (buf.size > MAX_EVENTS) buf.takeLast(MAX_EVENTS).toMutableList() else buf
        buffer = trimmed
        val arr = JSONArray()
        trimmed.forEach {
            arr.put(JSONObject().apply {
                put("trackId", it.trackId); put("ts", it.tsSec); put("source", it.source.key)
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
        for (e in events) {
            val age = now - e.tsSec
            if (age <= DAY_S) day.add(e.trackId)
            if (age <= 7 * DAY_S) week.add(e.trackId)
        }
        return RecentContext(day, week)
    }

    companion object {
        private const val PREFS_NAME = "claudio_recommendation_log"
        private const val KEY = "v1"
        private const val MAX_EVENTS = 800
        private const val DAY_S = 24 * 3600L
    }
}
