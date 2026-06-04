package app.pipo.nativeapp.data.agent.memory

import android.content.Context
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.context.AgentReference
import app.pipo.nativeapp.data.agent.context.StyleCapsule
import app.pipo.nativeapp.data.agent.context.StyleSource
import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.TrackPlacement
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.intent.ArtistDistribution
import app.pipo.nativeapp.data.agent.intent.IntentSource
import app.pipo.nativeapp.data.agent.intent.MusicIntent
import org.json.JSONArray
import org.json.JSONObject

class AgentReferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun recent(limit: Int = MAX_REFS): List<AgentReference> {
        val refs = readAllReferences().filterNot(::isExpired)
        persist(refs.takeLast(MAX_REFS))
        return refs.takeLast(limit)
    }

    @Synchronized
    fun record(reference: AgentReference) {
        recordAll(listOf(reference))
    }

    @Synchronized
    fun recordAll(references: List<AgentReference>) {
        record(references)
    }

    @Synchronized
    fun record(references: List<AgentReference>) {
        if (references.isEmpty()) return
        val merged = LinkedHashMap<String, AgentReference>()
        readAllReferences().filterNot(::isExpired).forEach { merged[it.refId] = it }
        references.filterNot(::isExpired).forEach { merged[it.refId] = it }
        persist(merged.values.toList().takeLast(MAX_REFS))
    }

    @Synchronized
    fun clearExpired() {
        persist(readAllReferences().filterNot(::isExpired).takeLast(MAX_REFS))
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_REFS).apply()
    }

    private fun readAllReferences(): List<AgentReference> {
        val arr = JSONArray(prefs.getString(KEY_REFS, "[]").orEmpty().ifBlank { "[]" })
        val out = ArrayList<AgentReference>()
        for (i in 0 until arr.length()) {
            readReference(arr.optJSONObject(i) ?: continue)?.let { out.add(it) }
        }
        return out
    }

    private fun persist(references: List<AgentReference>) {
        val arr = JSONArray()
        references.forEach { arr.put(writeReference(it)) }
        prefs.edit().putString(KEY_REFS, arr.toString()).apply()
    }

    private fun isExpired(ref: AgentReference, nowMs: Long = System.currentTimeMillis()): Boolean {
        val ttl = when (ref) {
            is AgentReference.TrackRef -> TTL_TRACK_MS
            is AgentReference.ArtistRef -> TTL_ARTIST_MS
            is AgentReference.StyleRef -> TTL_STYLE_MS
            is AgentReference.QueueRef -> TTL_QUEUE_MS
            is AgentReference.IntentRef -> TTL_INTENT_MS
        }
        return nowMs - ref.createdAtMs > ttl
    }

    private fun writeReference(ref: AgentReference): JSONObject {
        val obj = JSONObject()
            .put("refId", ref.refId)
            .put("label", ref.label)
            .put("createdAtMs", ref.createdAtMs)
        when (ref) {
            is AgentReference.TrackRef -> obj
                .put("type", "track")
                .put("track", writeTrack(ref.track))
                .put("reason", ref.reason)
            is AgentReference.ArtistRef -> obj
                .put("type", "artist")
                .put("artist", ref.artist)
                .put("reason", ref.reason)
            is AgentReference.StyleRef -> obj
                .put("type", "style")
                .put("capsule", writeStyle(ref.capsule))
                .put("reason", ref.reason)
            is AgentReference.QueueRef -> obj
                .put("type", "queue")
                .put("trackIds", JSONArray(ref.trackIds))
                .put("reason", ref.reason)
            is AgentReference.IntentRef -> obj
                .put("type", "intent")
                .put("intent", writeIntent(ref.intent))
                .put("intentHash", ref.intentHash)
                .put("reason", ref.reason)
        }
        return obj
    }

    private fun readReference(obj: JSONObject): AgentReference? {
        val refId = obj.optString("refId")
        val label = obj.optString("label")
        val created = obj.optLong("createdAtMs")
        return when (obj.optString("type")) {
            "track" -> obj.optJSONObject("track")?.let {
                AgentReference.TrackRef(refId, label, readTrack(it), obj.optString("reason"), created)
            }
            "artist" -> AgentReference.ArtistRef(refId, label, obj.optString("artist"), obj.optString("reason"), created)
            "style" -> obj.optJSONObject("capsule")?.let {
                AgentReference.StyleRef(refId, label, readStyle(it), obj.optString("reason"), created)
            }
            "queue" -> AgentReference.QueueRef(refId, label, stringList(obj.optJSONArray("trackIds")), obj.optString("reason"), created)
            "intent" -> AgentReference.IntentRef(
                refId = refId,
                label = label,
                intent = readIntent(obj.optJSONObject("intent")),
                intentHash = obj.optString("intentHash"),
                reason = obj.optString("reason"),
                createdAtMs = created,
            )
            else -> null
        }
    }

    private fun writeTrack(track: NativeTrack): JSONObject =
        JSONObject()
            .put("id", track.id)
            .put("neteaseId", track.neteaseId ?: JSONObject.NULL)
            .put("title", track.title)
            .put("artist", track.artist)
            .put("album", track.album)
            .put("streamUrl", track.streamUrl)
            .put("streamCacheKey", track.streamCacheKey ?: JSONObject.NULL)
            .put("artworkUrl", track.artworkUrl ?: JSONObject.NULL)
            .put("durationMs", track.durationMs)

    private fun readTrack(obj: JSONObject): NativeTrack =
        NativeTrack(
            id = obj.optString("id"),
            neteaseId = obj.optLong("neteaseId").takeIf { it > 0L },
            title = obj.optString("title"),
            artist = obj.optString("artist"),
            album = obj.optString("album"),
            streamUrl = obj.optString("streamUrl"),
            streamCacheKey = obj.optString("streamCacheKey").takeIf { it.isNotBlank() && it != "null" },
            artworkUrl = obj.optString("artworkUrl").takeIf { it.isNotBlank() && it != "null" },
            durationMs = obj.optLong("durationMs"),
        )

    private fun writeStyle(style: StyleCapsule): JSONObject =
        JSONObject()
            .put("capsuleId", style.capsuleId)
            .put("source", style.source.name)
            .put("trackId", style.trackId ?: JSONObject.NULL)
            .put("title", style.title)
            .put("artist", style.artist)
            .put("genres", JSONArray(style.genres))
            .put("moods", JSONArray(style.moods))
            .put("scenes", JSONArray(style.scenes))
            .put("textures", JSONArray(style.textures))
            .put("energy", style.energy)
            .put("tempoFeel", style.tempoFeel)
            .put("summary", style.summary)
            .put("createdAtMs", style.createdAtMs)

    private fun readStyle(obj: JSONObject): StyleCapsule =
        StyleCapsule(
            capsuleId = obj.optString("capsuleId"),
            source = runCatching { enumValueOf<StyleSource>(obj.optString("source")) }.getOrDefault(StyleSource.PlannerExplanation),
            trackId = obj.optString("trackId").takeIf { it.isNotBlank() && it != "null" },
            title = obj.optString("title"),
            artist = obj.optString("artist"),
            genres = stringList(obj.optJSONArray("genres")),
            moods = stringList(obj.optJSONArray("moods")),
            scenes = stringList(obj.optJSONArray("scenes")),
            textures = stringList(obj.optJSONArray("textures")),
            energy = obj.optString("energy", "any"),
            tempoFeel = obj.optString("tempoFeel", "any"),
            summary = obj.optString("summary"),
            createdAtMs = obj.optLong("createdAtMs"),
        )

    private fun writeIntent(intent: MusicIntent): JSONObject =
        JSONObject()
            .put("queryText", intent.queryText)
            .put("primaryArtists", JSONArray(intent.primaryArtists))
            .put("artistScope", intent.artistScope.name)
            .put("artistDistribution", intent.artistDistribution.name)
            .put("primaryTracks", writeRequirements(intent.primaryTracks))
            .put("mustIncludeTracks", writeRequirements(intent.mustIncludeTracks))
            .put("excludeTerms", JSONArray(intent.excludeTerms))
            .put("softMoods", JSONArray(intent.softMoods))
            .put("softScenes", JSONArray(intent.softScenes))
            .put("softTextures", JSONArray(intent.softTextures))
            .put("softEnergy", intent.softEnergy)
            .put("softTempoFeel", intent.softTempoFeel)
            .put("refStyles", JSONArray(intent.refStyles))
            .put("refArtists", JSONArray(intent.refArtists))
            .put("aiMainStyles", JSONArray(intent.aiMainStyles))
            .put("aiAdjacentStyles", JSONArray(intent.aiAdjacentStyles))
            .put("aiAvoidStyles", JSONArray(intent.aiAvoidStyles))
            .put("searchQueries", JSONArray(intent.searchQueries))
            .put("emotionalDirection", intent.emotionalDirection ?: JSONObject.NULL)
            .put("desiredCount", intent.desiredCount)
            .put("source", intent.source.name)

    private fun readIntent(obj: JSONObject?): MusicIntent {
        if (obj == null) return MusicIntent()
        return MusicIntent(
            queryText = obj.optString("queryText"),
            primaryArtists = stringList(obj.optJSONArray("primaryArtists")),
            artistScope = runCatching { enumValueOf<ArtistScope>(obj.optString("artistScope")) }.getOrDefault(ArtistScope.Focus),
            artistDistribution = runCatching { enumValueOf<ArtistDistribution>(obj.optString("artistDistribution")) }
                .getOrDefault(ArtistDistribution.PrimaryDominant),
            primaryTracks = readRequirements(obj.optJSONArray("primaryTracks")),
            mustIncludeTracks = readRequirements(obj.optJSONArray("mustIncludeTracks")),
            excludeTerms = stringList(obj.optJSONArray("excludeTerms")),
            softMoods = stringList(obj.optJSONArray("softMoods")),
            softScenes = stringList(obj.optJSONArray("softScenes")),
            softTextures = stringList(obj.optJSONArray("softTextures")),
            softEnergy = obj.optString("softEnergy", "any"),
            softTempoFeel = obj.optString("softTempoFeel", "any"),
            refStyles = stringList(obj.optJSONArray("refStyles")),
            refArtists = stringList(obj.optJSONArray("refArtists")),
            aiMainStyles = stringList(obj.optJSONArray("aiMainStyles")),
            aiAdjacentStyles = stringList(obj.optJSONArray("aiAdjacentStyles")),
            aiAvoidStyles = stringList(obj.optJSONArray("aiAvoidStyles")),
            searchQueries = stringList(obj.optJSONArray("searchQueries")),
            emotionalDirection = obj.optString("emotionalDirection").takeIf { it.isNotBlank() && it != "null" },
            desiredCount = obj.optInt("desiredCount", 12),
            source = runCatching { enumValueOf<IntentSource>(obj.optString("source")) }.getOrDefault(IntentSource.UserCommand),
        )
    }

    private fun writeRequirements(items: List<TrackRequirement>): JSONArray {
        val arr = JSONArray()
        items.forEach { requirement ->
            arr.put(
                JSONObject()
                    .put("title", requirement.title)
                    .put("artist", requirement.artist ?: JSONObject.NULL)
                    .put("placement", requirement.placement.name)
                    .put("index", requirement.index ?: JSONObject.NULL),
            )
        }
        return arr
    }

    private fun readRequirements(arr: JSONArray?): List<TrackRequirement> {
        if (arr == null) return emptyList()
        val out = ArrayList<TrackRequirement>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val title = obj.optString("title").trim()
            if (title.isBlank()) continue
            out.add(
                TrackRequirement(
                    title = title,
                    artist = obj.optString("artist").takeIf { it.isNotBlank() && it != "null" },
                    placement = runCatching { enumValueOf<TrackPlacement>(obj.optString("placement")) }
                        .getOrDefault(TrackPlacement.MustInclude),
                    index = obj.optInt("index").takeIf { obj.has("index") && !obj.isNull("index") && it >= 0 },
                ),
            )
        }
        return out
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val value = arr.optString(i).trim()
            if (value.isNotBlank()) out.add(value)
        }
        return out
    }

    private companion object {
        const val PREFS_NAME = "claudio_agent_references"
        const val KEY_REFS = "refs"
        const val MAX_REFS = 24
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val TTL_TRACK_MS = 7L * DAY_MS
        const val TTL_ARTIST_MS = 14L * DAY_MS
        const val TTL_STYLE_MS = 14L * DAY_MS
        const val TTL_INTENT_MS = 3L * DAY_MS
        const val TTL_QUEUE_MS = 1L * DAY_MS
    }
}
