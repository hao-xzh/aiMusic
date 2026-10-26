package app.pipo.nativeapp.data

import app.pipo.nativeapp.data.agent.domain.AgentTurnInput
import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.CatalogConstraint
import app.pipo.nativeapp.data.agent.domain.ContinuationMode
import app.pipo.nativeapp.data.agent.domain.ContinuationPolicy
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.MusicSelectionMode
import app.pipo.nativeapp.data.agent.domain.MusicStyleProfile
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.ReferenceContext
import app.pipo.nativeapp.data.agent.domain.TrackPlacement
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.resolve.MusicResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** 保存 AI 选歌或首页账号约束，供播放器进程重建后的续播继续使用原意图。 */
data class AiQueueContinuationSnapshot(
    val goal: MusicGoal,
    val userText: String,
    val plannerRaw: String,
    val homeAccountKey: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("goal", goal.toJson())
        .put("userText", userText)
        .put("plannerRaw", plannerRaw)
        .also { if (homeAccountKey != null) it.put("homeAccountKey", homeAccountKey) }

    fun restoreSource(
        repository: PipoRepository,
        currentTrack: NativeTrack?,
        queue: List<NativeTrack>,
    ): ContinuousQueueSource = if (homeAccountKey != null) {
        HomeRecommendationQueueSource(homeAccountKey, queue, anchor = currentTrack ?: queue.lastOrNull())
    } else RestoredContinuousQueueSource(this, repository, currentTrack, queue)

    private class RestoredContinuousQueueSource(
        private val snapshot: AiQueueContinuationSnapshot,
        private val repository: PipoRepository,
        private val currentTrack: NativeTrack?,
        private val queue: List<NativeTrack>,
    ) : ContinuousQueueSource {
        @Volatile
        private var source: ContinuousQueueSource? = null
        private val sourceMutex = Mutex()

        override suspend fun fetchMore(excludeIds: Set<Long>): List<NativeTrack> = source().fetchMore(excludeIds)

        override fun pendingTracks(): List<NativeTrack> = source?.pendingTracks().orEmpty()

        override fun startsAutomatically(): Boolean =
            snapshot.goal.continuationPolicy.mode !in setOf(ContinuationMode.Disabled, ContinuationMode.SingleLoop)

        override fun repeatsCurrentTrack(): Boolean =
            snapshot.goal.continuationPolicy.mode == ContinuationMode.SingleLoop

        /** 尚未重建 source 前也不能退化成通用发现流。 */
        override fun permitsDefaultFallback(): Boolean = false

        override fun acceptsResolved(track: NativeTrack): Boolean = source?.acceptsResolved(track) ?: false

        override fun snapshot(): AiQueueContinuationSnapshot = snapshot

        private suspend fun source(): ContinuousQueueSource {
            source?.let { return it }
            return sourceMutex.withLock {
                source ?: requireNotNull(
                    MusicResolver(repository).continuousSourceFor(
                        action = PlannedAction.PlayRequest(
                            actionId = "restore_ai_continuation",
                            mode = PlayMode.ReplaceQueue,
                            primaryGoal = snapshot.goal,
                        ),
                        plan = MusicTurnPlan(
                            turnId = "restore_ai_continuation",
                            userText = snapshot.userText,
                            actions = emptyList(),
                            plannerRaw = snapshot.plannerRaw,
                        ),
                        input = AgentTurnInput(
                            userText = snapshot.userText,
                            history = emptyList(),
                            currentTrack = currentTrack,
                            currentQueue = queue,
                            userFacts = "",
                        ),
                    ),
                ).also { source = it }
            }
        }
    }

    companion object {
        private const val VERSION = 1

        fun fromJson(obj: JSONObject): AiQueueContinuationSnapshot? = runCatching {
            if (obj.requiredInt("version") != VERSION) return@runCatching null
            val userText = obj.requiredString("userText") ?: return@runCatching null
            val homeAccountKey = if (obj.has("homeAccountKey")) {
                obj.requiredString("homeAccountKey")?.takeIf { it.isNotBlank() } ?: return@runCatching null
            } else {
                if (userText == "manual_home_recommendations") return@runCatching null
                null
            }
            AiQueueContinuationSnapshot(
                goal = obj.requiredObject("goal")?.toMusicGoal() ?: return@runCatching null,
                userText = userText,
                plannerRaw = obj.requiredString("plannerRaw") ?: return@runCatching null,
                homeAccountKey = homeAccountKey,
            )
        }.getOrNull()

        private fun MusicGoal.toJson(): JSONObject = JSONObject()
            .put("primaryArtists", primaryArtists.toJson())
            .put("artistScope", artistScope.name)
            .put("playlistName", playlistName)
            .put("primaryTracks", primaryTracks.toJsonTracks())
            .put("mustInclude", mustInclude.toJsonTracks())
            .put("closer", closer?.toJson() ?: JSONObject.NULL)
            .put("excludeTerms", excludeTerms.toJson())
            .put("hardGenres", hardGenres.toJson())
            .put("hardLanguages", hardLanguages.toJson())
            .put("hardVocalTypes", hardVocalTypes.toJson())
            .put("softMoods", softMoods.toJson())
            .put("softScenes", softScenes.toJson())
            .put("softTextures", softTextures.toJson())
            .put("softQualityWords", softQualityWords.toJson())
            .put("softEnergy", softEnergy)
            .put("softTempoFeel", softTempoFeel)
            .put("refStyles", refStyles.toJson())
            .put("aiMainStyles", aiMainStyles.toJson())
            .put("aiAdjacentStyles", aiAdjacentStyles.toJson())
            .put("aiAvoidStyles", aiAvoidStyles.toJson())
            .put("searchSeeds", searchSeeds.toJson())
            .put("selectionMode", selectionMode.name)
            .put("catalogConstraint", catalogConstraint.toJson())
            .put("useCurrentStyleAnchor", useCurrentStyleAnchor)
            .put("continuationKey", continuationKey)
            .put("styleProfile", styleProfile.toJson())
            .put("referenceContext", referenceContext.name)
            .put("continuationPolicy", continuationPolicy.toJson())
            .put("includeArtists", includeArtists.toJson())

        private fun CatalogConstraint.toJson(): JSONObject = JSONObject()
            .put("name", name)
            .put("aliases", aliases.toJson())
            .put("searchQueries", searchQueries.toJson())

        private fun MusicStyleProfile.toJson(): JSONObject = JSONObject()
            .put("semanticQuery", semanticQuery)
            .put("energy", energy)
            .put("moods", moods.toJson())
            .put("scenes", scenes.toJson())
            .put("genres", genres.toJson())
            .put("textures", textures.toJson())
            .put("qualityWords", qualityWords.toJson())
            .put("languages", languages.toJson())
            .put("vocalTypes", vocalTypes.toJson())
            .put("refStyles", refStyles.toJson())
            .put("avoidTags", avoidTags.toJson())
            .put("transitionStyle", transitionStyle)
            .put("exploration", exploration)

        private fun ContinuationPolicy.toJson(): JSONObject = JSONObject()
            .put("mode", mode.name)
            .put("preserveWhenInserting", preserveWhenInserting)
            .put("invalidatePreviousOnReplace", invalidatePreviousOnReplace)

        private fun TrackRequirement.toJson(): JSONObject = JSONObject()
            .put("title", title)
            .put("artist", artist ?: JSONObject.NULL)
            .put("placement", placement.name)

        private fun List<String>.toJson(): JSONArray = JSONArray(this)

        private fun List<TrackRequirement>.toJsonTracks(): JSONArray = JSONArray().also { out ->
            forEach { out.put(it.toJson()) }
        }

        private fun JSONObject.toMusicGoal(): MusicGoal? {
            val closer = when {
                !has("closer") -> return null
                isNull("closer") -> null
                else -> (opt("closer") as? JSONObject)?.toTrackRequirement() ?: return null
            }
            return MusicGoal(
                primaryArtists = requiredStrings("primaryArtists") ?: return null,
                artistScope = requiredEnum<ArtistScope>("artistScope") ?: return null,
                playlistName = requiredString("playlistName") ?: return null,
                primaryTracks = requiredTracks("primaryTracks") ?: return null,
                mustInclude = requiredTracks("mustInclude") ?: return null,
                closer = closer,
                excludeTerms = requiredStrings("excludeTerms") ?: return null,
                hardGenres = requiredStrings("hardGenres") ?: return null,
                hardLanguages = requiredStrings("hardLanguages") ?: return null,
                hardVocalTypes = requiredStrings("hardVocalTypes") ?: return null,
                softMoods = requiredStrings("softMoods") ?: return null,
                softScenes = requiredStrings("softScenes") ?: return null,
                softTextures = requiredStrings("softTextures") ?: return null,
                softQualityWords = requiredStrings("softQualityWords") ?: return null,
                softEnergy = requiredString("softEnergy") ?: return null,
                softTempoFeel = requiredString("softTempoFeel") ?: return null,
                refStyles = requiredStrings("refStyles") ?: return null,
                aiMainStyles = requiredStrings("aiMainStyles") ?: return null,
                aiAdjacentStyles = requiredStrings("aiAdjacentStyles") ?: return null,
                aiAvoidStyles = requiredStrings("aiAvoidStyles") ?: return null,
                searchSeeds = requiredStrings("searchSeeds") ?: return null,
                selectionMode = requiredEnum<MusicSelectionMode>("selectionMode") ?: return null,
                catalogConstraint = requiredObject("catalogConstraint")?.toCatalogConstraint() ?: return null,
                useCurrentStyleAnchor = requiredBoolean("useCurrentStyleAnchor") ?: return null,
                continuationKey = requiredString("continuationKey") ?: return null,
                styleProfile = requiredObject("styleProfile")?.toMusicStyleProfile() ?: return null,
                referenceContext = requiredEnum<ReferenceContext>("referenceContext") ?: return null,
                continuationPolicy = requiredObject("continuationPolicy")?.toContinuationPolicy() ?: return null,
                includeArtists = requiredStrings("includeArtists") ?: return null,
            )
        }

        private fun JSONObject.toCatalogConstraint(): CatalogConstraint? {
            return CatalogConstraint(
                name = requiredString("name") ?: return null,
                aliases = requiredStrings("aliases") ?: return null,
                searchQueries = requiredStrings("searchQueries") ?: return null,
            )
        }

        private fun JSONObject.toMusicStyleProfile(): MusicStyleProfile? {
            return MusicStyleProfile(
                semanticQuery = requiredString("semanticQuery") ?: return null,
                energy = requiredString("energy") ?: return null,
                moods = requiredStrings("moods") ?: return null,
                scenes = requiredStrings("scenes") ?: return null,
                genres = requiredStrings("genres") ?: return null,
                textures = requiredStrings("textures") ?: return null,
                qualityWords = requiredStrings("qualityWords") ?: return null,
                languages = requiredStrings("languages") ?: return null,
                vocalTypes = requiredStrings("vocalTypes") ?: return null,
                refStyles = requiredStrings("refStyles") ?: return null,
                avoidTags = requiredStrings("avoidTags") ?: return null,
                transitionStyle = requiredString("transitionStyle") ?: return null,
                exploration = requiredString("exploration") ?: return null,
            )
        }

        private fun JSONObject.toContinuationPolicy(): ContinuationPolicy? {
            return ContinuationPolicy(
                mode = requiredEnum<ContinuationMode>("mode") ?: return null,
                preserveWhenInserting = requiredBoolean("preserveWhenInserting") ?: return null,
                invalidatePreviousOnReplace = requiredBoolean("invalidatePreviousOnReplace") ?: return null,
            )
        }

        private fun JSONObject.requiredTracks(name: String): List<TrackRequirement>? {
            val array = requiredArray(name) ?: return null
            return buildList {
                for (index in 0 until array.length()) {
                    add(array.optJSONObject(index)?.toTrackRequirement() ?: return null)
                }
            }
        }

        private fun JSONObject.toTrackRequirement(): TrackRequirement? {
            val artist = when {
                !has("artist") -> return null
                isNull("artist") -> null
                else -> requiredString("artist") ?: return null
            }
            return TrackRequirement(
                title = requiredString("title") ?: return null,
                artist = artist,
                placement = requiredEnum<TrackPlacement>("placement") ?: return null,
            )
        }

        private fun JSONObject.requiredString(name: String): String? =
            takeIf { has(name) && !isNull(name) }?.opt(name) as? String

        private fun JSONObject.requiredBoolean(name: String): Boolean? =
            takeIf { has(name) && !isNull(name) }?.opt(name) as? Boolean

        private fun JSONObject.requiredInt(name: String): Int? =
            takeIf { has(name) && !isNull(name) }?.opt(name) as? Int

        private fun JSONObject.requiredObject(name: String): JSONObject? =
            takeIf { has(name) && !isNull(name) }?.opt(name) as? JSONObject

        private fun JSONObject.requiredArray(name: String): JSONArray? =
            takeIf { has(name) && !isNull(name) }?.opt(name) as? JSONArray

        private fun JSONObject.requiredStrings(name: String): List<String>? {
            val array = requiredArray(name) ?: return null
            return buildList {
                for (index in 0 until array.length()) {
                    add(array.opt(index) as? String ?: return null)
                }
            }
        }

        private inline fun <reified T : Enum<T>> JSONObject.requiredEnum(name: String): T? {
            val raw = requiredString(name) ?: return null
            return enumValues<T>().firstOrNull { it.name == raw }
        }
    }
}
