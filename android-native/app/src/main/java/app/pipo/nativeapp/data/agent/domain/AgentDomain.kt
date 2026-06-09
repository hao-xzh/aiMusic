package app.pipo.nativeapp.data.agent.domain

import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PetMemory
import app.pipo.nativeapp.data.PetPersona

data class AgentTurnInput(
    val userText: String,
    val history: List<PetMemory.ConversationTurn>,
    val historySummary: String = "",
    val musicReferences: List<PetMemory.MusicReference> = emptyList(),
    val currentTrack: NativeTrack?,
    val currentQueue: List<NativeTrack> = emptyList(),
    val userFacts: String,
    val persona: PetPersona = PetPersona.DEFAULT,
)

data class MusicTurnPlan(
    val turnId: String,
    val userText: String,
    val actions: List<PlannedAction>,
    val isRepair: Boolean = false,
    val repairTargetTurnId: String? = null,
    val confidence: Double = 1.0,
    val replyHint: String = "",
    val plannerRaw: String = "",
    val musicReferences: List<PetMemory.MusicReference> = emptyList(),
)

sealed class PlannedAction {
    abstract val actionId: String

    data class PlayRequest(
        override val actionId: String,
        val mode: PlayMode,
        val primaryGoal: MusicGoal = MusicGoal(),
        val target: TrackRequirement? = null,
        val desiredCount: Int = 12,
        val similar: Boolean = false,
        val jumpToInserted: Boolean = false,
    ) : PlannedAction()

    data class PlayTracks(
        override val actionId: String,
        val mode: PlayMode,
        val tracks: List<NativeTrack>,
        val continuous: ContinuousQueueSource?,
        val primaryGoal: MusicGoal = MusicGoal(),
        val target: TrackRequirement? = null,
        val similar: Boolean = false,
        val jumpToInserted: Boolean = false,
    ) : PlannedAction()

    data class PlayPlaylist(
        override val actionId: String,
        val name: String,
        val tracks: List<NativeTrack>,
    ) : PlannedAction()

    data class LikeCurrent(
        override val actionId: String,
        val like: Boolean,
    ) : PlannedAction()

    data class LikeTrack(
        override val actionId: String,
        val like: Boolean,
        val target: TrackRequirement,
    ) : PlannedAction()

    data class ModifyPlaylist(
        override val actionId: String,
        val add: Boolean,
        val playlistName: String,
    ) : PlannedAction()

    data class SkipCurrent(
        override val actionId: String,
    ) : PlannedAction()

    data class Say(
        override val actionId: String,
        val text: String,
    ) : PlannedAction()

    data class Clarify(
        override val actionId: String,
        val question: String,
    ) : PlannedAction()
}

enum class PlayMode {
    ReplaceQueue,
    InsertNext,
    PlayNow,
}

enum class ArtistScope {
    Strict,
    Focus,
    Similar,
}

data class MusicGoal(
    val primaryArtists: List<String> = emptyList(),
    val artistScope: ArtistScope = ArtistScope.Strict,
    val playlistName: String = "",
    val primaryTracks: List<TrackRequirement> = emptyList(),
    val mustInclude: List<TrackRequirement> = emptyList(),
    val closer: TrackRequirement? = null,
    val excludeTerms: List<String> = emptyList(),
    val hardGenres: List<String> = emptyList(),
    val hardLanguages: List<String> = emptyList(),
    val hardVocalTypes: List<String> = emptyList(),
    val softMoods: List<String> = emptyList(),
    val softScenes: List<String> = emptyList(),
    val softTextures: List<String> = emptyList(),
    val softQualityWords: List<String> = emptyList(),
    val softEnergy: String = "any",
    val softTempoFeel: String = "any",
    val refStyles: List<String> = emptyList(),
    val aiMainStyles: List<String> = emptyList(),
    val aiAdjacentStyles: List<String> = emptyList(),
    val aiAvoidStyles: List<String> = emptyList(),
    val searchSeeds: List<String> = emptyList(),
    val useCurrentStyleAnchor: Boolean = false,
    val continuationKey: String = "",
    /**
     * V2 Agent 的主语义槽。
     *
     * 旧链路只能把用户话压成歌手/歌名/歌单，
     * “嗨一点 / 忧郁一点 / 当前风格多来几首 / 开车听”会被丢掉。
     * 这里把模糊风格、情绪、场景、语言、人声、避开条件都保留下来，
     * Resolver、SemanticRecall、QueueCompiler、续播 source 共用这一份结构。
     */
    val styleProfile: MusicStyleProfile = MusicStyleProfile(),
    val referenceContext: ReferenceContext = ReferenceContext.None,
    val continuationPolicy: ContinuationPolicy = ContinuationPolicy.Default,
    /** 用户要求“中间加一首周杰伦 / 夹一点女歌手 / 加一个别的歌手”时的艺人槽。 */
    val includeArtists: List<String> = emptyList(),
)

data class MusicStyleProfile(
    val semanticQuery: String = "",
    val energy: String = "any",
    val moods: List<String> = emptyList(),
    val scenes: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val textures: List<String> = emptyList(),
    val qualityWords: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val vocalTypes: List<String> = emptyList(),
    val refStyles: List<String> = emptyList(),
    val avoidTags: List<String> = emptyList(),
    val transitionStyle: String = "soft",
    val exploration: String = "balanced",
) {
    val hasSignal: Boolean
        get() = semanticQuery.isNotBlank() ||
            energy != "any" ||
            moods.isNotEmpty() ||
            scenes.isNotEmpty() ||
            genres.isNotEmpty() ||
            textures.isNotEmpty() ||
            qualityWords.isNotEmpty() ||
            languages.isNotEmpty() ||
            vocalTypes.isNotEmpty() ||
            refStyles.isNotEmpty() ||
            avoidTags.isNotEmpty() ||
            transitionStyle != "soft" ||
            exploration != "balanced"

    fun mergedWith(other: MusicStyleProfile): MusicStyleProfile = MusicStyleProfile(
        semanticQuery = semanticQuery.ifBlank { other.semanticQuery },
        energy = if (energy != "any") energy else other.energy,
        moods = mergeDistinct(moods, other.moods),
        scenes = mergeDistinct(scenes, other.scenes),
        genres = mergeDistinct(genres, other.genres),
        textures = mergeDistinct(textures, other.textures),
        qualityWords = mergeDistinct(qualityWords, other.qualityWords),
        languages = mergeDistinct(languages, other.languages),
        vocalTypes = mergeDistinct(vocalTypes, other.vocalTypes),
        refStyles = mergeDistinct(refStyles, other.refStyles),
        avoidTags = mergeDistinct(avoidTags, other.avoidTags),
        transitionStyle = if (transitionStyle != "soft") transitionStyle else other.transitionStyle,
        exploration = if (exploration != "balanced") exploration else other.exploration,
    )

    companion object {
        private fun mergeDistinct(first: List<String>, second: List<String>): List<String> {
            val out = ArrayList<String>(first.size + second.size)
            val seen = HashSet<String>()
            for (value in first + second) {
                val trimmed = value.trim()
                val key = trimmed.lowercase()
                if (trimmed.isNotBlank() && seen.add(key)) out.add(trimmed)
            }
            return out
        }
    }
}

enum class ReferenceContext {
    None,
    CurrentTrack,
    CurrentStyle,
    CurrentQueue,
    PreviousIntent,
    MentionedTrack,
}

data class ContinuationPolicy(
    val mode: ContinuationMode = ContinuationMode.Default,
    val preserveWhenInserting: Boolean = true,
    val invalidatePreviousOnReplace: Boolean = true,
) {
    companion object {
        val Default = ContinuationPolicy()
    }
}

enum class ContinuationMode {
    Default,
    Disabled,
    SameIntent,
    SameStyle,
    SameQueue,
}

data class TrackRequirement(
    val title: String,
    val artist: String? = null,
    val placement: TrackPlacement = TrackPlacement.MustInclude,
)

enum class TrackPlacement {
    Now,
    Next,
    MustInclude,
    Closer,
}

data class QueuePlan(
    val actions: List<PlannedAction>,
    val validation: QueueValidation,
)

data class QueueValidation(
    val passed: Boolean,
    val messages: List<String> = emptyList(),
    val primarySatisfied: Boolean = true,
    val mustIncludeSatisfied: Boolean = true,
    val closerSatisfied: Boolean = true,
)

data class ActionExecutionResult(
    val actionId: String,
    val type: String,
    val success: Boolean,
    val message: String,
    val tracks: List<NativeTrack> = emptyList(),
    val acceptedByPlayer: Boolean = success,
    val actuallyStarted: Boolean = false,
    val currentTrack: NativeTrack? = null,
    val queueSnapshot: List<NativeTrack> = tracks,
    val insertedTrack: NativeTrack? = null,
    val likedTrack: NativeTrack? = null,
    val playlistName: String? = null,
    val insert: Boolean = false,
    val similar: Boolean = false,
    val warnings: List<String> = emptyList(),
    val errorMessage: String? = null,
    val committedQueueSummary: CommittedQueueSummary? = null,
)

data class CommittedQueueSummary(
    val requestId: String,
    val queueVersion: Long,
    val operation: String,
    val accepted: Boolean,
    val trackCount: Int,
    val firstTitle: String,
    val insertedTitle: String = "",
    val mixMode: String = "",
    val transitionFeel: String = "",
    val energyCurve: String = "",
    val smoothnessAvg: Double = 1.0,
    val validationPassed: Boolean = true,
    val reordered: Boolean = false,
    val warnings: List<String> = emptyList(),
)

data class AgentUiCard(
    val kind: Kind,
    val label: String,
    val ok: Boolean = true,
    val count: Int = 0,
    val artists: String = "",
    val covers: List<String?> = emptyList(),
    val insert: Boolean = false,
    val similar: Boolean = false,
) {
    enum class Kind {
        Play,
        Skip,
        Like,
        Unlike,
        PlaylistAdd,
        PlaylistRemove,
        Error,
    }
}

data class TurnOutcome(
    val reply: String,
    val cards: List<AgentUiCard>,
    val trace: TurnTrace,
    val musicReferences: List<PetMemory.MusicReference> = emptyList(),
)

data class TurnTrace(
    val turnId: String,
    val plannerRaw: String = "",
    val normalizedPlan: String = "",
    val resolution: String = "",
    val queuePlan: String = "",
    val validation: String = "",
    val execution: String = "",
    val finalReply: String = "",
)
