package app.pipo.nativeapp.data.agent.session

import android.content.Context
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.context.StyleCapsule
import app.pipo.nativeapp.data.agent.context.StyleSource
import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.TrackPlacement
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.intent.ArtistDistribution
import app.pipo.nativeapp.data.agent.intent.IntentSource
import app.pipo.nativeapp.data.agent.intent.MusicIntent
import app.pipo.nativeapp.playback.orchestrator.EnergyCurve
import app.pipo.nativeapp.playback.orchestrator.MixMode
import app.pipo.nativeapp.playback.orchestrator.MixPolicy
import app.pipo.nativeapp.playback.orchestrator.MixPriority
import app.pipo.nativeapp.playback.orchestrator.QueueHardConstraints
import app.pipo.nativeapp.playback.orchestrator.QueueSoftPreferences
import app.pipo.nativeapp.playback.orchestrator.TransitionFeel
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PlaybackIntentSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun active(): PlaybackIntentSession? {
        val raw = prefs.getString(KEY_ACTIVE, null) ?: return null
        val session = runCatching { readSession(JSONObject(raw)) }.getOrNull() ?: return null
        return session.takeIf { it.isActive() } ?: run {
            if (session.status == SessionStatus.Active) save(session.copy(status = SessionStatus.Expired, updatedAtMs = System.currentTimeMillis()))
            null
        }
    }

    @Synchronized
    fun create(
        origin: SessionOrigin,
        rootUserText: String,
        lastUserText: String,
        activeIntent: MusicIntent,
        continuationPolicy: ContinuationPolicy,
        queuePolicy: QueuePolicy,
        mixPolicy: MixPolicy,
        hardConstraints: QueueHardConstraints,
        softPreferences: QueueSoftPreferences,
        styleAnchor: StyleCapsule? = null,
        trackAnchor: NativeTrack? = null,
        queueAnchorIds: List<String> = emptyList(),
    ): PlaybackIntentSession {
        active()?.let { save(it.copy(status = SessionStatus.Superseded, updatedAtMs = System.currentTimeMillis())) }
        val now = System.currentTimeMillis()
        val session = PlaybackIntentSession(
            sessionId = UUID.randomUUID().toString(),
            generation = nextGeneration(),
            status = SessionStatus.Active,
            origin = origin,
            rootUserText = rootUserText,
            lastUserText = lastUserText,
            activeIntent = activeIntent,
            activeIntentHash = activeIntent.stableHash(),
            continuationPolicy = continuationPolicy,
            queuePolicy = queuePolicy,
            mixPolicy = mixPolicy,
            hardConstraints = hardConstraints,
            softPreferences = softPreferences,
            styleAnchor = styleAnchor,
            trackAnchor = trackAnchor,
            queueAnchorIds = queueAnchorIds,
            createdAtMs = now,
            updatedAtMs = now,
        )
        save(session)
        return session
    }

    @Synchronized
    fun update(
        activeIntent: MusicIntent,
        lastUserText: String,
        continuationPolicy: ContinuationPolicy? = null,
        styleAnchor: StyleCapsule? = null,
        trackAnchor: NativeTrack? = null,
        queueAnchorIds: List<String>? = null,
    ): PlaybackIntentSession? {
        val current = active() ?: return null
        val now = System.currentTimeMillis()
        val next = current.copy(
            generation = nextGeneration(),
            lastUserText = lastUserText,
            activeIntent = activeIntent,
            activeIntentHash = activeIntent.stableHash(),
            continuationPolicy = continuationPolicy ?: current.continuationPolicy,
            styleAnchor = styleAnchor ?: current.styleAnchor,
            trackAnchor = trackAnchor ?: current.trackAnchor,
            queueAnchorIds = queueAnchorIds ?: current.queueAnchorIds,
            updatedAtMs = now,
        )
        save(next)
        return next
    }

    @Synchronized
    fun pauseActive(): PlaybackIntentSession? {
        val current = active() ?: return null
        val paused = current.copy(status = SessionStatus.Paused, updatedAtMs = System.currentTimeMillis())
        save(paused)
        return paused
    }

    @Synchronized
    fun supersedeActive(): PlaybackIntentSession? {
        val current = active() ?: return null
        val next = current.copy(status = SessionStatus.Superseded, updatedAtMs = System.currentTimeMillis())
        save(next)
        return next
    }

    fun isCurrent(sessionId: String, generation: Long, activeIntentHash: String): Boolean {
        val current = active() ?: return false
        return current.sessionId == sessionId &&
            current.generation == generation &&
            current.activeIntentHash == activeIntentHash &&
            current.continuationPolicy.enabled
    }

    @Synchronized
    private fun save(session: PlaybackIntentSession) {
        prefs.edit()
            .putString(KEY_ACTIVE, writeSession(session).toString())
            .putLong(KEY_GENERATION, session.generation)
            .apply()
    }

    private fun nextGeneration(): Long = prefs.getLong(KEY_GENERATION, 0L) + 1L

    private fun writeSession(session: PlaybackIntentSession): JSONObject =
        JSONObject()
            .put("sessionId", session.sessionId)
            .put("generation", session.generation)
            .put("status", session.status.name)
            .put("origin", session.origin.name)
            .put("rootUserText", session.rootUserText)
            .put("lastUserText", session.lastUserText)
            .put("activeIntent", writeIntent(session.activeIntent))
            .put("activeIntentHash", session.activeIntentHash)
            .put("continuationPolicy", writeContinuationPolicy(session.continuationPolicy))
            .put("queuePolicy", JSONObject().put("preserveCurrentTrack", session.queuePolicy.preserveCurrentTrack)
                .put("defaultDesiredCount", session.queuePolicy.defaultDesiredCount)
                .put("allowOnlineBackfill", session.queuePolicy.allowOnlineBackfill))
            .put("mixPolicy", writeMixPolicy(session.mixPolicy))
            .put("hardConstraints", writeHardConstraints(session.hardConstraints))
            .put("softPreferences", writeSoftPreferences(session.softPreferences))
            .put("styleAnchor", session.styleAnchor?.let(::writeStyleCapsule))
            .put("trackAnchor", session.trackAnchor?.let(::writeTrack))
            .put("queueAnchorIds", JSONArray(session.queueAnchorIds))
            .put("createdAtMs", session.createdAtMs)
            .put("updatedAtMs", session.updatedAtMs)
            .put("expiresAtMs", session.expiresAtMs ?: JSONObject.NULL)

    private fun readSession(obj: JSONObject): PlaybackIntentSession =
        PlaybackIntentSession(
            sessionId = obj.optString("sessionId"),
            generation = obj.optLong("generation"),
            status = enumValue(obj.optString("status"), SessionStatus.Active),
            origin = enumValue(obj.optString("origin"), SessionOrigin.AgentInstruction),
            rootUserText = obj.optString("rootUserText"),
            lastUserText = obj.optString("lastUserText"),
            activeIntent = readIntent(obj.optJSONObject("activeIntent")),
            activeIntentHash = obj.optString("activeIntentHash"),
            continuationPolicy = readContinuationPolicy(obj.optJSONObject("continuationPolicy")),
            queuePolicy = obj.optJSONObject("queuePolicy")?.let {
                QueuePolicy(
                    preserveCurrentTrack = it.optBoolean("preserveCurrentTrack"),
                    defaultDesiredCount = it.optInt("defaultDesiredCount", 12),
                    allowOnlineBackfill = it.optBoolean("allowOnlineBackfill", true),
                )
            } ?: QueuePolicy(),
            mixPolicy = readMixPolicy(obj.optJSONObject("mixPolicy")),
            hardConstraints = readHardConstraints(obj.optJSONObject("hardConstraints")),
            softPreferences = readSoftPreferences(obj.optJSONObject("softPreferences")),
            styleAnchor = obj.optJSONObject("styleAnchor")?.let(::readStyleCapsule),
            trackAnchor = obj.optJSONObject("trackAnchor")?.let(::readTrack),
            queueAnchorIds = stringList(obj.optJSONArray("queueAnchorIds")),
            createdAtMs = obj.optLong("createdAtMs"),
            updatedAtMs = obj.optLong("updatedAtMs"),
            expiresAtMs = obj.optLong("expiresAtMs").takeIf { it > 0L },
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
            artistScope = enumValue(obj.optString("artistScope"), ArtistScope.Focus),
            artistDistribution = enumValue(obj.optString("artistDistribution"), ArtistDistribution.PrimaryDominant),
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
            source = enumValue(obj.optString("source"), IntentSource.UserCommand),
        )
    }

    private fun writeContinuationPolicy(policy: ContinuationPolicy): JSONObject =
        JSONObject()
            .put("enabled", policy.enabled)
            .put("mode", policy.mode.name)
            .put("inheritHardConstraints", policy.inheritHardConstraints)
            .put("inheritSoftPreferences", policy.inheritSoftPreferences)
            .put("inheritMixPolicy", policy.inheritMixPolicy)
            .put("desiredBatchSize", policy.desiredBatchSize)
            .put("maxTotalQueueSize", policy.maxTotalQueueSize)
            .put("expireOnManualReplace", policy.expireOnManualReplace)
            .put("expireOnNewAgentSession", policy.expireOnNewAgentSession)

    private fun readContinuationPolicy(obj: JSONObject?): ContinuationPolicy =
        ContinuationPolicy(
            enabled = obj?.optBoolean("enabled", false) ?: false,
            mode = enumValue(obj?.optString("mode").orEmpty(), ContinuationMode.Off),
            inheritHardConstraints = obj?.optBoolean("inheritHardConstraints", true) ?: true,
            inheritSoftPreferences = obj?.optBoolean("inheritSoftPreferences", true) ?: true,
            inheritMixPolicy = obj?.optBoolean("inheritMixPolicy", true) ?: true,
            desiredBatchSize = obj?.optInt("desiredBatchSize", 8) ?: 8,
            maxTotalQueueSize = obj?.optInt("maxTotalQueueSize", 80) ?: 80,
            expireOnManualReplace = obj?.optBoolean("expireOnManualReplace", true) ?: true,
            expireOnNewAgentSession = obj?.optBoolean("expireOnNewAgentSession", true) ?: true,
        )

    private fun writeMixPolicy(policy: MixPolicy): JSONObject =
        JSONObject()
            .put("enabled", policy.enabled)
            .put("mode", policy.mode.name)
            .put("priority", policy.priority.name)
            .put("transitionFeel", policy.transitionFeel.name)
            .put("allowReorderSoftSlots", policy.allowReorderSoftSlots)
            .put("maxReorderDistance", policy.maxReorderDistance)
            .put("preferCachedNext", policy.preferCachedNext)
            .put("preferAnalyzedFeatures", policy.preferAnalyzedFeatures)
            .put("allowOnlineBackfill", policy.allowOnlineBackfill)

    private fun readMixPolicy(obj: JSONObject?): MixPolicy =
        MixPolicy(
            enabled = obj?.optBoolean("enabled", true) ?: true,
            mode = enumValue(obj?.optString("mode").orEmpty(), MixMode.Smart),
            priority = enumValue(obj?.optString("priority").orEmpty(), MixPriority.Normal),
            transitionFeel = enumValue(obj?.optString("transitionFeel").orEmpty(), TransitionFeel.Natural),
            allowReorderSoftSlots = obj?.optBoolean("allowReorderSoftSlots", true) ?: true,
            maxReorderDistance = obj?.optInt("maxReorderDistance", 4) ?: 4,
            preferCachedNext = obj?.optBoolean("preferCachedNext", true) ?: true,
            preferAnalyzedFeatures = obj?.optBoolean("preferAnalyzedFeatures", true) ?: true,
            allowOnlineBackfill = obj?.optBoolean("allowOnlineBackfill", true) ?: true,
        )

    private fun writeHardConstraints(value: QueueHardConstraints): JSONObject =
        JSONObject()
            .put("preserveUserOrder", value.preserveUserOrder)
            .put("firstTrack", value.firstTrack?.let(::writeRequirement))
            .put("nextTrack", value.nextTrack?.let(::writeRequirement))
            .put("endingTrack", value.endingTrack?.let(::writeRequirement))
            .put("mustIncludeTracks", writeRequirements(value.mustIncludeTracks))
            .put("requiredArtists", JSONArray(value.requiredArtists))
            .put("artistScope", value.artistScope.name)
            .put("excludedArtists", JSONArray(value.excludedArtists))
            .put("excludedLanguages", JSONArray(value.excludedLanguages))
            .put("excludedTrackIds", JSONArray(value.excludedTrackIds))

    private fun readHardConstraints(obj: JSONObject?): QueueHardConstraints {
        if (obj == null) return QueueHardConstraints()
        return QueueHardConstraints(
            preserveUserOrder = obj.optBoolean("preserveUserOrder"),
            firstTrack = obj.optJSONObject("firstTrack")?.let(::readRequirement),
            nextTrack = obj.optJSONObject("nextTrack")?.let(::readRequirement),
            endingTrack = obj.optJSONObject("endingTrack")?.let(::readRequirement),
            mustIncludeTracks = readRequirements(obj.optJSONArray("mustIncludeTracks")),
            requiredArtists = stringList(obj.optJSONArray("requiredArtists")),
            artistScope = enumValue(obj.optString("artistScope"), ArtistScope.Focus),
            excludedArtists = stringList(obj.optJSONArray("excludedArtists")),
            excludedLanguages = stringList(obj.optJSONArray("excludedLanguages")),
            excludedTrackIds = stringList(obj.optJSONArray("excludedTrackIds")),
        )
    }

    private fun writeSoftPreferences(value: QueueSoftPreferences): JSONObject =
        JSONObject()
            .put("preferredLanguages", JSONArray(value.preferredLanguages))
            .put("preferredMoods", JSONArray(value.preferredMoods))
            .put("preferredGenres", JSONArray(value.preferredGenres))
            .put("energyCurve", value.energyCurve.name)
            .put("avoidRecentPlayed", value.avoidRecentPlayed)
            .put("maxSameArtistInWindow", value.maxSameArtistInWindow)
            .put("transitionFriendly", value.transitionFriendly)

    private fun readSoftPreferences(obj: JSONObject?): QueueSoftPreferences {
        if (obj == null) return QueueSoftPreferences()
        return QueueSoftPreferences(
            preferredLanguages = stringList(obj.optJSONArray("preferredLanguages")),
            preferredMoods = stringList(obj.optJSONArray("preferredMoods")),
            preferredGenres = stringList(obj.optJSONArray("preferredGenres")),
            energyCurve = enumValue(obj.optString("energyCurve"), EnergyCurve.Smooth),
            avoidRecentPlayed = obj.optBoolean("avoidRecentPlayed", true),
            maxSameArtistInWindow = obj.optInt("maxSameArtistInWindow", 2),
            transitionFriendly = obj.optBoolean("transitionFriendly", true),
        )
    }

    private fun writeStyleCapsule(value: StyleCapsule): JSONObject =
        JSONObject()
            .put("capsuleId", value.capsuleId)
            .put("source", value.source.name)
            .put("trackId", value.trackId ?: JSONObject.NULL)
            .put("title", value.title)
            .put("artist", value.artist)
            .put("genres", JSONArray(value.genres))
            .put("moods", JSONArray(value.moods))
            .put("scenes", JSONArray(value.scenes))
            .put("textures", JSONArray(value.textures))
            .put("energy", value.energy)
            .put("tempoFeel", value.tempoFeel)
            .put("summary", value.summary)
            .put("createdAtMs", value.createdAtMs)

    private fun readStyleCapsule(obj: JSONObject): StyleCapsule =
        StyleCapsule(
            capsuleId = obj.optString("capsuleId"),
            source = enumValue(obj.optString("source"), StyleSource.PlannerExplanation),
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

    private fun writeRequirements(values: List<TrackRequirement>): JSONArray =
        JSONArray().also { arr -> values.forEach { arr.put(writeRequirement(it)) } }

    private fun writeRequirement(value: TrackRequirement): JSONObject =
        JSONObject()
            .put("title", value.title)
            .put("artist", value.artist ?: JSONObject.NULL)
            .put("placement", value.placement.name)
            .put("index", value.index ?: JSONObject.NULL)

    private fun readRequirements(arr: JSONArray?): List<TrackRequirement> {
        if (arr == null) return emptyList()
        val out = ArrayList<TrackRequirement>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(readRequirement(it)) }
        }
        return out
    }

    private fun readRequirement(obj: JSONObject): TrackRequirement =
        TrackRequirement(
            title = obj.optString("title"),
            artist = obj.optString("artist").takeIf { it.isNotBlank() && it != "null" },
            placement = enumValue(obj.optString("placement"), TrackPlacement.MustInclude),
            index = obj.optInt("index").takeIf { obj.has("index") && !obj.isNull("index") && it >= 0 },
        )

    private inline fun <reified T : Enum<T>> enumValue(raw: String, fallback: T): T =
        runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)

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
        const val PREFS_NAME = "claudio_playback_intent_session"
        const val KEY_ACTIVE = "active"
        const val KEY_GENERATION = "generation"
    }
}
