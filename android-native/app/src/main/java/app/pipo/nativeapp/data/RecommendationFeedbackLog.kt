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
    ) {
        fun contains(track: NativeTrack): Boolean {
            val id = track.neteaseId
            return (id != null && id in trackIds) || TrackDedupe.songKey(track) in songKeys
        }
    }

    private data class Event(
        val contextKey: String,
        val trackId: Long?,
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
                put("songKey", event.songKey)
                put("ts", event.tsSec)
                put("sourceText", event.sourceText)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun trimFresh(events: List<Event>, nowSec: Long): MutableList<Event> {
        val fresh = events.filter { nowSec - it.tsSec <= TTL_SEC }
            .sortedBy { it.tsSec }
            .takeLast(MAX_EVENTS)
        return fresh.toMutableList()
    }

    companion object {
        private const val PREFS_NAME = "claudio_recommendation_feedback"
        private const val KEY = "context_rejections_v1"
        private const val MAX_EVENTS = 500
        private const val TTL_SEC = 30L * 24 * 3600

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
