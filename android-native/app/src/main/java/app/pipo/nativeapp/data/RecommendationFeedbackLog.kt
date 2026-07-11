package app.pipo.nativeapp.data

import android.content.Context
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import app.pipo.nativeapp.data.agent.normalize.MusicSemanticSignals
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户在当前 AI 推荐语义下手动删歌的反馈。
 *
 * 这是 context-level negative feedback：同类需求短期内避开这首歌，但不把它全局拉黑。
 */
class RecommendationFeedbackLog(context: Context) {
    data class RejectedContext(
        val trackIds: Set<Long>,
        val songKeys: Set<String>,
        val trackRefs: Set<String> = emptySet(),
    ) {
        fun contains(track: NativeTrack): Boolean {
            val id = track.neteaseId
            return (id != null && id in trackIds) ||
                (track.id in trackRefs || TrackDedupe.idKey(track)?.let { it in trackRefs } == true) ||
                TrackDedupe.compatibleKeys(track).any { it in songKeys }
        }
    }

    private data class Event(
        val contextKey: String,
        val trackId: Long?,
        val trackRef: String,
        val songKey: String,
        val tsSec: Long,
        val sourceText: String,
    )

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var buffer: MutableList<Event>? = null

    @Synchronized
    fun rejectForContext(track: NativeTrack, sourceText: String) {
        val contextKey = contextKeyForUserText(sourceText)
        if (contextKey.isBlank()) return
        val now = System.currentTimeMillis() / 1000
        val event = Event(
            contextKey = contextKey,
            trackId = track.neteaseId,
            trackRef = TrackDedupe.idKey(track) ?: track.id,
            songKey = TrackDedupe.songKey(track),
            tsSec = now,
            sourceText = sourceText.take(180),
        )
        val buf = ensureBuffer()
            .filterNot {
                it.contextKey == event.contextKey &&
                    it.songKey == event.songKey &&
                    it.trackId == event.trackId
            }
            .toMutableList()
        buf.add(event)
        buffer = trimFresh(buf, now)
        flush()
    }

    /** Manual removal is a durable, context-independent dislike. */
    @Synchronized
    fun reject(track: NativeTrack) {
        val now = System.currentTimeMillis() / 1000
        val event = Event(
            contextKey = GLOBAL_CONTEXT,
            trackId = track.neteaseId,
            trackRef = TrackDedupe.idKey(track) ?: track.id,
            songKey = TrackDedupe.songKey(track),
            tsSec = now,
            sourceText = "manual_queue_remove",
        )
        val buf = ensureBuffer()
            .filterNot { it.contextKey == GLOBAL_CONTEXT &&
                (it.trackRef == event.trackRef || it.songKey == event.songKey ||
                    (it.trackId != null && it.trackId == event.trackId)) }
            .toMutableList()
        buf.add(event)
        buffer = trimFresh(buf, now)
        flush()
    }

    /** Global hard negatives, used by every recommendation entry point. */
    @Synchronized
    fun globallyRejected(): RejectedContext {
        val now = System.currentTimeMillis() / 1000
        val matched = trimFresh(ensureBuffer(), now).filter { it.contextKey == GLOBAL_CONTEXT }
        return RejectedContext(
            trackIds = matched.mapNotNullTo(HashSet()) { it.trackId },
            songKeys = matched.mapTo(HashSet()) { it.songKey },
            trackRefs = matched.mapTo(HashSet()) { it.trackRef },
        )
    }

    @Synchronized
    fun rejectedForIntent(intent: PetIntent): RejectedContext {
        val contextKey = contextKeyForIntent(intent)
        if (contextKey.isBlank()) return RejectedContext(emptySet(), emptySet())
        val now = System.currentTimeMillis() / 1000
        val matched = trimFresh(ensureBuffer(), now).filter { event ->
            contextMatches(event.contextKey, contextKey)
        }
        return RejectedContext(
            trackIds = matched.mapNotNullTo(HashSet()) { it.trackId },
            songKeys = matched.mapTo(HashSet()) { it.songKey },
            trackRefs = matched.mapTo(HashSet()) { it.trackRef },
        )
    }

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
                    val key = o.optString("contextKey")
                    val songKey = o.optString("songKey")
                    if (key.isBlank() || songKey.isBlank()) continue
                    out.add(
                        Event(
                            contextKey = key,
                            trackId = if (o.has("trackId")) o.optLong("trackId") else null,
                            trackRef = o.optString("trackRef", ""),
                            songKey = songKey,
                            tsSec = o.optLong("ts"),
                            sourceText = o.optString("sourceText"),
                        ),
                    )
                }
            }
        }
        buffer = trimFresh(out, System.currentTimeMillis() / 1000).toMutableList()
        return buffer!!
    }

    @Synchronized
    private fun flush() {
        val arr = JSONArray()
        ensureBuffer().forEach { event ->
            arr.put(JSONObject().apply {
                put("contextKey", event.contextKey)
                event.trackId?.let { put("trackId", it) }
                if (event.trackRef.isNotBlank()) put("trackRef", event.trackRef)
                put("songKey", event.songKey)
                put("ts", event.tsSec)
                put("sourceText", event.sourceText)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun trimFresh(events: List<Event>, nowSec: Long): MutableList<Event> {
        val fresh = events.filter {
            // Manual deletion is an explicit durable preference and does not expire.
            // Contextual rejections remain soft and may age out as the user's mood changes.
            it.contextKey == GLOBAL_CONTEXT || nowSec - it.tsSec <= SOFT_TTL_SEC
        }.sortedBy { it.tsSec }
        val hard = fresh.filter { it.contextKey == GLOBAL_CONTEXT }.takeLast(MAX_HARD_EVENTS)
        val soft = fresh.filter { it.contextKey != GLOBAL_CONTEXT }
            .takeLast((MAX_EVENTS - hard.size).coerceAtLeast(0))
        val kept = (soft + hard).sortedBy { it.tsSec }.takeLast(MAX_EVENTS)
        return kept.toMutableList()
    }

    companion object {
        private const val PREFS_NAME = "claudio_recommendation_feedback"
        private const val KEY = "context_rejections_v1"
        private const val MAX_EVENTS = 500
        private const val MAX_HARD_EVENTS = 300
        private const val SOFT_TTL_SEC = 30L * 24 * 3600
        private const val GLOBAL_CONTEXT = "__global_manual_rejection__"

        fun contextKeyForIntent(intent: PetIntent): String {
            val tokens = buildList {
                addAll(intent.hardLanguages.map { "lang:${normalize(it)}" })
                addAll(intent.hardGenres.map { "genre:${normalize(it)}" })
                addAll(intent.softMoods.map { "mood:${normalize(it)}" })
                addAll(intent.softScenes.map { "scene:${normalize(it)}" })
                addAll(intent.refStyles.map { "style:${normalize(it)}" })
                addAll(intent.aiMainStyles.map { "style:${normalize(it)}" })
                addAll(intent.hardArtists.map { "artist:${normalize(it)}" })
                addAll(intent.textArtists.map { "artist:${normalize(it)}" })
            }.filter { !it.endsWith(":") }
            return tokens.distinct().sorted().joinToString("|").ifBlank {
                normalize(intent.queryText).take(80)
            }
        }

        fun contextKeyForUserText(text: String): String {
            val semantics = MusicSemanticSignals.extract(text)
            val tokens = buildList {
                addAll((CommandTextSignals.languageIncludes(text) + semantics.languages).map { "lang:${normalize(it)}" })
                addAll(semantics.genres.map { "genre:${normalize(it)}" })
                addAll(semantics.moods.map { "mood:${normalize(it)}" })
                addAll(semantics.scenes.map { "scene:${normalize(it)}" })
                addAll(semantics.aiMainStyles.map { "style:${normalize(it)}" })
                addAll(CommandTextSignals.primaryArtistHints(text).map { "artist:${normalize(it)}" })
            }.filter { !it.endsWith(":") }
            return tokens.distinct().sorted().joinToString("|").ifBlank {
                normalize(text).take(80)
            }
        }

        private fun contextMatches(eventKey: String, currentKey: String): Boolean {
            if (eventKey == currentKey) return true
            val eventTokens = eventKey.split('|').filter { it.isNotBlank() }.toSet()
            val currentTokens = currentKey.split('|').filter { it.isNotBlank() }.toSet()
            if (eventTokens.isEmpty() || currentTokens.isEmpty()) return false
            return eventTokens.all { it in currentTokens }
        }

        private fun normalize(value: String): String =
            value.lowercase()
                .replace(Regex("[\\s'\"`·・\\-－—_,，。.、!?！？]+"), "")
    }
}
