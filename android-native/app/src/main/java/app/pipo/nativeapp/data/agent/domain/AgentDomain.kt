package app.pipo.nativeapp.data.agent.domain

import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PetMemory
import app.pipo.nativeapp.data.PetPersona
import app.pipo.nativeapp.data.agent.context.AgentReference
import app.pipo.nativeapp.data.agent.context.ReferenceBinding
import app.pipo.nativeapp.data.agent.context.StyleCapsule
import app.pipo.nativeapp.data.agent.intent.MusicIntent
import app.pipo.nativeapp.data.agent.session.ContinuationPolicy
import app.pipo.nativeapp.data.agent.session.PlaybackIntentSession
import app.pipo.nativeapp.data.agent.session.SessionMutation

data class AgentTurnInput(
    val userText: String,
    val history: List<PetMemory.ConversationTurn>,
    val historySummary: String = "",
    val musicReferences: List<PetMemory.MusicReference> = emptyList(),
    val currentTrack: NativeTrack?,
    val currentQueue: List<NativeTrack> = emptyList(),
    val userFacts: String,
    val persona: PetPersona = PetPersona.DEFAULT,
    val activeSession: PlaybackIntentSession? = null,
    val currentTrackStyle: StyleCapsule? = null,
    val currentQueueStyle: StyleCapsule? = null,
    val references: List<AgentReference> = emptyList(),
    val resolvedTrackReference: TrackRequirement? = null,
    val resolvedStyleReference: StyleCapsule? = null,
    val resolvedArtistReference: String? = null,
    val resolvedIntentReference: MusicIntent? = null,
    val referenceBindings: List<ReferenceBinding> = emptyList(),
    val aiAutoContinueEnabled: Boolean = false,
    val defaultContinuationMode: String = "",
    val inheritAgentIntentWhenAvailable: Boolean = true,
    val inferManualQueueStyleWhenNoAgentIntent: Boolean = true,
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
    val sessionMutation: SessionMutation = SessionMutation.None,
    val referenceBindings: List<ReferenceBinding> = emptyList(),
    val continuationPolicy: ContinuationPolicy? = null,
    val activeIntent: MusicIntent? = null,
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
        val jumpToInserted: Boolean = true,
        val musicIntent: MusicIntent? = null,
        val continuationPolicy: ContinuationPolicy? = null,
        val sessionMutation: SessionMutation = SessionMutation.None,
        val styleCapsule: StyleCapsule? = null,
    ) : PlannedAction()

    data class PlayTracks(
        override val actionId: String,
        val mode: PlayMode,
        val tracks: List<NativeTrack>,
        val continuous: ContinuousQueueSource?,
        val primaryGoal: MusicGoal = MusicGoal(),
        val target: TrackRequirement? = null,
        val similar: Boolean = false,
        val jumpToInserted: Boolean = true,
        val musicIntent: MusicIntent? = null,
        val continuationPolicy: ContinuationPolicy? = null,
        val sessionMutation: SessionMutation = SessionMutation.None,
        val styleCapsule: StyleCapsule? = null,
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

    data class AnswerStyle(
        override val actionId: String,
        val capsule: StyleCapsule,
        val text: String = "",
    ) : PlannedAction()

    data class UpdatePreference(
        override val actionId: String,
        val capsule: StyleCapsule?,
        val preferenceText: String,
    ) : PlannedAction()

    data class UpdateContinuation(
        override val actionId: String,
        val policy: ContinuationPolicy,
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
    PreserveCurrentThenReplace,
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
)

data class TrackRequirement(
    val title: String,
    val artist: String? = null,
    val placement: TrackPlacement = TrackPlacement.MustInclude,
    val index: Int? = null,
)

enum class TrackPlacement {
    Now,
    Next,
    AfterCurrent,
    Middle,
    AtIndex,
    End,
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
    val sessionId: String = "",
    val generation: Long = 0L,
    val sessionMutation: String = "",
    val continuationMode: String = "",
    val activeIntentHash: String = "",
    val referenceBindings: List<ReferenceBinding> = emptyList(),
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
    val sessionId: String = "",
    val generation: Long = 0L,
    val sessionMutation: String = "",
    val continuationMode: String = "",
    val activeIntentHash: String = "",
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
