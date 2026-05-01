package app.pipo.nativeapp.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 听歌行为日志 —— 镜像 src/lib/behavior-log.ts 的 readBehaviorLog + summarize。
 *
 * 持久化：SharedPreferences 单 key 存 JSON 数组（cap 1000 条，落盘前按时间倒序裁切）。
 * 用 Room 更"正"，但 1000 条 JSON 大约 80KB，单 read/write 微秒级，不值得加 Room 依赖。
 *
 * 事件类型：
 *   - PlayStarted：进入新曲（自动接 / 主动跳到 都算）
 *   - Completed：曲尾自然结束（auto 转场触发，且离 duration 末端不远）
 *   - Skipped：用户主动 next 且当前进度 < 50%
 *   - ManualCut：用户主动 next/prev 且当前进度 ≥ 50%
 */
enum class BehaviorType { PlayStarted, Completed, Skipped, ManualCut }

data class BehaviorEvent(
    val type: BehaviorType,
    val trackId: String,
    val neteaseId: Long?,
    val title: String,
    val artist: String,
    val tsMs: Long,
    val completionPct: Float = 0f,
)

data class BehaviorSummary(
    val total: Int,
    val completed: Int,
    val skipped: Int,
    val manualCuts: Int,
    val completionRate: Float,
    /** 反复完整听过的艺人（按完成数降序） */
    val loveArtists: List<String>,
    /** 反复跳过的艺人（按 skip 数降序） */
    val skipHotArtists: List<String>,
)

class BehaviorLogContext(val log: BehaviorLog)

class BehaviorLog(context: Context) {
    /** 24h / 7d 内播放过的 neteaseId 集合，用于 ranker 的 recent play penalty */
    data class RecentPlay(val last24hTrackIds: Set<Long>, val last7dTrackIds: Set<Long>)

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun recentPlay(): RecentPlay {
        val events = readAll()
        val now = System.currentTimeMillis()
        val day = HashSet<Long>()
        val week = HashSet<Long>()
        for (e in events) {
            if (e.type != BehaviorType.PlayStarted && e.type != BehaviorType.Completed) continue
            val ne = e.neteaseId ?: continue
            val age = now - e.tsMs
            if (age <= 24L * 3600 * 1000) day.add(ne)
            if (age <= 7L * 24 * 3600 * 1000) week.add(ne)
        }
        return RecentPlay(day, week)
    }

    suspend fun log(event: BehaviorEvent) = mutex.withLock {
        val arr = readArrayLocked()
        arr.put(eventToJson(event))
        // cap 1000：超出时按时间排序裁掉最早的
        val capped = if (arr.length() > MAX_EVENTS) {
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }
                .sortedBy { it.optLong("ts") }
                .takeLast(MAX_EVENTS)
            JSONArray().apply { list.forEach { put(it) } }
        } else arr
        prefs.edit().putString(KEY, capped.toString()).apply()
    }

    suspend fun readAll(): List<BehaviorEvent> = mutex.withLock {
        val arr = readArrayLocked()
        (0 until arr.length()).mapNotNull { jsonToEvent(arr.optJSONObject(it)) }
    }

    suspend fun summary(): BehaviorSummary {
        val events = readAll()
        if (events.isEmpty()) return EMPTY
        val total = events.size
        val completed = events.count { it.type == BehaviorType.Completed }
        val skipped = events.count { it.type == BehaviorType.Skipped }
        val manualCuts = events.count { it.type == BehaviorType.ManualCut }
        val started = events.count { it.type == BehaviorType.PlayStarted }
        val completionRate = if (started > 0) completed.toFloat() / started else 0f

        val completedByArtist = events
            .filter { it.type == BehaviorType.Completed && it.artist.isNotBlank() }
            .groupingBy { it.artist }
            .eachCount()
        val skippedByArtist = events
            .filter { it.type == BehaviorType.Skipped && it.artist.isNotBlank() }
            .groupingBy { it.artist }
            .eachCount()

        // "反复完整听过" = 完成 ≥ 2 次
        val loveArtists = completedByArtist.entries
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(12)

        // "反复跳过" = skip ≥ 2 次且不在 love 集合里
        val skipHotArtists = skippedByArtist.entries
            .filter { it.value >= 2 && it.key !in loveArtists }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(12)

        return BehaviorSummary(
            total = total,
            completed = completed,
            skipped = skipped,
            manualCuts = manualCuts,
            completionRate = completionRate,
            loveArtists = loveArtists,
            skipHotArtists = skipHotArtists,
        )
    }

    private fun readArrayLocked(): JSONArray {
        val raw = prefs.getString(KEY, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun eventToJson(e: BehaviorEvent): JSONObject = JSONObject().apply {
        put("type", e.type.name)
        put("trackId", e.trackId)
        e.neteaseId?.let { put("ne", it) }
        put("title", e.title)
        put("artist", e.artist)
        put("ts", e.tsMs)
        put("pct", e.completionPct.toDouble())
    }

    private fun jsonToEvent(o: JSONObject?): BehaviorEvent? {
        if (o == null) return null
        return try {
            BehaviorEvent(
                type = BehaviorType.valueOf(o.getString("type")),
                trackId = o.getString("trackId"),
                neteaseId = if (o.has("ne")) o.getLong("ne") else null,
                title = o.optString("title"),
                artist = o.optString("artist"),
                tsMs = o.optLong("ts"),
                completionPct = o.optDouble("pct", 0.0).toFloat(),
            )
        } catch (_: Exception) { null }
    }

    companion object {
        private const val PREFS_NAME = "claudio_behavior"
        private const val KEY = "events_v1"
        private const val MAX_EVENTS = 1000

        private val EMPTY = BehaviorSummary(
            total = 0, completed = 0, skipped = 0, manualCuts = 0,
            completionRate = 0f, loveArtists = emptyList(), skipHotArtists = emptyList(),
        )
    }
}
