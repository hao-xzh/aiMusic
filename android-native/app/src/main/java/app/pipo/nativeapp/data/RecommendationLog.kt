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
    enum class Source(val key: String) { Pet("pet"), Auto("auto"), Radio("radio"), Search("search"), Home("home") }
    enum class HomeGroup { FamiliarTrack, FamiliarArtist, NewArtist }

    data class Event(
        /** Only a confirmed NetEase numeric ID is stored here. */
        val trackId: Long?,
        val tsSec: Long,
        val source: Source,
        /** 主艺人名 —— v2 新增,做 artist-level fatigue;老事件没这字段当 null */
        val artist: String? = null,
        /** Classification when this batch was generated; null for older/non-home records. */
        val isDiscovery: Boolean? = null,
        val userId: Long? = null,
        val songKey: String? = null,
        /** Opaque provider key; preserve media-ID casing for QQ. */
        val sourceKey: String? = null,
        /** Cross-provider recording mapping, when catalog metadata confirmed it. */
        val recordingId: String? = null,
        val homeGroup: HomeGroup? = null,
        /** Includes credited collaborators so rotating the lead artist cannot bypass fatigue. */
        val artistKeys: Set<String> = emptySet(),
    )

    data class FavoriteEvent(
        val trackId: Long,
        val tsMs: Long,
        val userId: Long,
        val liked: Boolean,
    )

    data class RecentContext(
        val last24hTrackIds: Set<Long>,
        val last7dTrackIds: Set<Long>,
        /** 近 24h 每个艺人(normalize 后)被推荐的次数 */
        val last24hArtistCounts: Map<String, Int> = emptyMap(),
        /** 近 7d 同上 */
        val last7dArtistCounts: Map<String, Int> = emptyMap(),
        val last24hSourceKeys: Set<String> = emptySet(),
        val last7dSourceKeys: Set<String> = emptySet(),
        val last24hRecordingIds: Set<String> = emptySet(),
        val last7dRecordingIds: Set<String> = emptySet(),
        val last3dTrackIds: Set<Long> = emptySet(),
        val last3dSongKeys: Set<String> = emptySet(),
        val last3dSourceKeys: Set<String> = emptySet(),
        val last3dRecordingIds: Set<String> = emptySet(),
        val last48hArtistCounts: Map<String, Int> = emptyMap(),
        val last14dArtistCounts: Map<String, Int> = emptyMap(),
        val last14dSongCounts: Map<String, Int> = emptyMap(),
    )

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var buffer: MutableList<Event>? = null

    @Volatile
    private var favoriteBuffer: MutableList<FavoriteEvent>? = null

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
                    val homeGroup = o.optString("homeGroup", "")
                        .takeIf { it.isNotBlank() }
                        ?.let { key -> HomeGroup.entries.firstOrNull { it.name == key } }
                    out.add(Event(
                        trackId = o.optLong("trackId").takeIf { o.has("trackId") && !o.isNull("trackId") && it > 0L },
                        tsSec = o.optLong("ts"), source = src, artist = artist,
                        isDiscovery = if (o.has("discovery") && !o.isNull("discovery")) o.optBoolean("discovery") else null,
                        userId = if (o.has("userId") && !o.isNull("userId")) o.optLong("userId") else null,
                        songKey = o.optString("songKey", "").takeIf { it.isNotBlank() },
                        sourceKey = o.optString("sourceKey", "").takeIf { it.isNotBlank() },
                        recordingId = o.optString("recordingId", "").takeIf { it.isNotBlank() },
                        homeGroup = homeGroup,
                        artistKeys = o.optJSONArray("artistKeys")?.let { keys ->
                            (0 until keys.length()).mapNotNull { index ->
                                keys.optString(index).takeIf { it.isNotBlank() }
                            }.toSet()
                        }.orEmpty(),
                    ))
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
    fun logTracks(
        tracks: List<NativeTrack>, source: Source = Source.Pet,
        discoveryIds: Set<Long>? = null, userId: Long? = null,
        homeGroups: Map<Long, HomeGroup>? = null,
        discoverySourceKeys: Set<String>? = null,
        homeGroupsBySource: Map<String, HomeGroup>? = null,
    ) {
        if (tracks.isEmpty()) return
        val buf = ensureBuffer()
        val now = System.currentTimeMillis() / 1000
        val seen = HashSet<String>()
        for (t in tracks) {
            val sourceKey = t.sourceKey
            if (!seen.add(sourceKey)) continue
            val id = t.neteaseId?.takeIf { it > 0L }
            val artist = t.artist.split('/', '&', ',', '、').firstOrNull()?.trim()
            buf.add(Event(
                trackId = id, tsSec = now, source = source, artist = artist,
                isDiscovery = discoverySourceKeys?.contains(sourceKey) ?: discoveryIds?.contains(id),
                userId = userId,
                songKey = if (discoveryIds != null || discoverySourceKeys != null) TrackDedupe.songKey(t) else null,
                sourceKey = sourceKey,
                recordingId = t.recordingId?.takeIf { it.isNotBlank() },
                homeGroup = homeGroupsBySource?.get(sourceKey) ?: id?.let { homeGroups?.get(it) },
                artistKeys = recommendationArtistKeys(t.artist),
            ))
        }
        flush()
    }

    @Synchronized
    private fun ensureFavoriteBuffer(): MutableList<FavoriteEvent> {
        favoriteBuffer?.let { return it }
        val raw = prefs.getString(FAVORITES_KEY, null)
        val out = mutableListOf<FavoriteEvent>()
        if (raw != null) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (!o.has("tsMs") || o.isNull("tsMs")) continue
                    out.add(
                        FavoriteEvent(
                            trackId = o.optLong("trackId"),
                            tsMs = o.optLong("tsMs"),
                            userId = o.optLong("userId"),
                            liked = o.optBoolean("liked"),
                        ),
                    )
                }
            }
        }
        val trimmed = if (out.size > MAX_EVENTS) out.takeLast(MAX_EVENTS).toMutableList() else out
        favoriteBuffer = trimmed
        return trimmed
    }

    @Synchronized
    fun logFavorite(trackId: Long, userId: Long, liked: Boolean) {
        ensureFavoriteBuffer().add(
            FavoriteEvent(trackId, System.currentTimeMillis(), userId, liked),
        )
        flushFavorites()
    }

    @Synchronized
    fun readFavorites(): List<FavoriteEvent> = ensureFavoriteBuffer().toList()

    @Synchronized
    private fun flush() {
        val buf = buffer ?: return
        // Radio/chat traffic must not evict the home exposure history used for rotation.
        val since = System.currentTimeMillis() / 1000 - HOME_HISTORY_DAYS * DAY_S
        val home = buf.filter { it.source == Source.Home && it.tsSec >= since }.takeLast(MAX_HOME_EVENTS)
        val other = buf.filter { it.source != Source.Home }.takeLast(MAX_EVENTS)
        val trimmed = (home + other).sortedBy { it.tsSec }.toMutableList()
        buffer = trimmed
        val arr = JSONArray()
        trimmed.forEach {
            arr.put(JSONObject().apply {
                it.trackId?.let { trackId -> put("trackId", trackId) }
                put("ts", it.tsSec); put("source", it.source.key)
                if (!it.artist.isNullOrBlank()) put("artist", it.artist)
                it.isDiscovery?.let { discovery -> put("discovery", discovery) }
                it.userId?.let { userId -> put("userId", userId) }
                it.songKey?.let { songKey -> put("songKey", songKey) }
                it.sourceKey?.let { sourceKey -> put("sourceKey", sourceKey) }
                it.recordingId?.let { recordingId -> put("recordingId", recordingId) }
                it.homeGroup?.let { homeGroup -> put("homeGroup", homeGroup.name) }
                if (it.artistKeys.isNotEmpty()) put("artistKeys", JSONArray(it.artistKeys.toList()))
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    private fun flushFavorites() {
        val buf = favoriteBuffer ?: return
        val trimmed = if (buf.size > MAX_EVENTS) buf.takeLast(MAX_EVENTS).toMutableList() else buf
        favoriteBuffer = trimmed
        val arr = JSONArray()
        trimmed.forEach {
            arr.put(JSONObject().apply {
                put("trackId", it.trackId)
                put("tsMs", it.tsMs)
                put("userId", it.userId)
                put("liked", it.liked)
            })
        }
        prefs.edit().putString(FAVORITES_KEY, arr.toString()).apply()
    }

    @Synchronized
    fun readAll(): List<Event> = ensureBuffer().toList()

    fun recentContext(): RecentContext = recentContext(readAll().takeLast(MAX_EVENTS), includeCollaborators = false)

    /** Home exposure is account-scoped; explicit requests do not consume discovery slots. */
    fun recentHomeContext(userId: Long?): RecentContext = recentContext(readAll().filter {
        it.source == Source.Home && it.userId == userId
    })

    private fun recentContext(events: List<Event>, includeCollaborators: Boolean = true): RecentContext {
        val now = System.currentTimeMillis() / 1000
        val day = mutableSetOf<Long>()
        val week = mutableSetOf<Long>()
        val daySources = mutableSetOf<String>()
        val weekSources = mutableSetOf<String>()
        val dayRecordings = mutableSetOf<String>()
        val weekRecordings = mutableSetOf<String>()
        val threeDayTracks = mutableSetOf<Long>()
        val threeDaySongs = mutableSetOf<String>()
        val threeDaySources = mutableSetOf<String>()
        val threeDayRecordings = mutableSetOf<String>()
        val dayArtist = HashMap<String, Int>()
        val weekArtist = HashMap<String, Int>()
        val twoDayArtist = HashMap<String, Int>()
        val fortnightArtist = HashMap<String, Int>()
        val fortnightSongs = HashMap<String, Int>()
        for (e in events) {
            val age = now - e.tsSec
            if (age < 0) continue
            val in7d = age <= 7 * DAY_S
            val in3d = age <= 3 * DAY_S
            val in24h = age <= DAY_S
            if (in24h) {
                e.trackId?.let(day::add)
                e.sourceKey?.let(daySources::add)
                e.recordingId?.let(dayRecordings::add)
            }
            if (in7d) {
                e.trackId?.let(week::add)
                e.sourceKey?.let(weekSources::add)
                e.recordingId?.let(weekRecordings::add)
            }
            if (in3d) {
                e.trackId?.let(threeDayTracks::add)
                e.songKey?.let(threeDaySongs::add)
                e.sourceKey?.let(threeDaySources::add)
                e.recordingId?.let(threeDayRecordings::add)
            }
            if (age <= 14 * DAY_S) e.songKey?.let { key ->
                fortnightSongs[key] = (fortnightSongs[key] ?: 0) + 1
            }
            val artistKeys = if (includeCollaborators) {
                e.artistKeys.ifEmpty { e.artist?.let(::recommendationArtistKeys).orEmpty() }
            } else e.artist?.let(::normalizeArtistKey)?.takeIf { it.isNotEmpty() }?.let(::setOf).orEmpty()
            for (key in artistKeys) {
                if (in24h) dayArtist[key] = (dayArtist[key] ?: 0) + 1
                if (in7d) weekArtist[key] = (weekArtist[key] ?: 0) + 1
                if (age <= 2 * DAY_S) twoDayArtist[key] = (twoDayArtist[key] ?: 0) + 1
                if (age <= 14 * DAY_S) fortnightArtist[key] = (fortnightArtist[key] ?: 0) + 1
            }
        }
        return RecentContext(day, week, dayArtist, weekArtist, daySources, weekSources,
            dayRecordings, weekRecordings, threeDayTracks, threeDaySongs, threeDaySources, threeDayRecordings,
            twoDayArtist, fortnightArtist, fortnightSongs)
    }

    private fun normalizeArtistKey(s: String): String =
        s.lowercase().replace(Regex("[\\s'\"`·・\\-－—_,，。.、!?！？]+"), "")

    companion object {
        private const val PREFS_NAME = "claudio_recommendation_log"
        private const val KEY = "v1"
        private const val FAVORITES_KEY = "favorites_v1"
        private const val MAX_EVENTS = 800
        private const val MAX_HOME_EVENTS = 8_000
        private const val HOME_HISTORY_DAYS = 28
        private const val DAY_S = 24 * 3600L
    }
}
