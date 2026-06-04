package app.pipo.nativeapp.data.agent.execute

import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.domain.ActionExecutionResult
import app.pipo.nativeapp.data.agent.context.ReferenceBinding
import app.pipo.nativeapp.data.agent.context.StyleCapsule
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.intent.MusicIntent
import app.pipo.nativeapp.data.agent.session.ContinuationPolicy
import app.pipo.nativeapp.data.agent.session.SessionMutation

interface AgentActionExecutor {
    suspend fun playQueue(
        actionId: String,
        mode: PlayMode,
        tracks: List<NativeTrack>,
        continuous: ContinuousQueueSource?,
        primaryGoal: MusicGoal,
        target: TrackRequirement?,
        similar: Boolean,
        musicIntent: MusicIntent? = null,
        continuationPolicy: ContinuationPolicy? = null,
        sessionMutation: SessionMutation = SessionMutation.None,
        styleCapsule: StyleCapsule? = null,
        referenceBindings: List<ReferenceBinding> = emptyList(),
    ): ActionExecutionResult

    suspend fun insertNext(
        actionId: String,
        track: NativeTrack,
        jumpToInserted: Boolean,
    ): ActionExecutionResult

    suspend fun skip(actionId: String): ActionExecutionResult

    suspend fun likeCurrent(actionId: String, like: Boolean): ActionExecutionResult

    suspend fun modifyPlaylist(
        actionId: String,
        add: Boolean,
        playlistName: String,
    ): ActionExecutionResult

    suspend fun updateContinuation(
        actionId: String,
        policy: ContinuationPolicy,
        currentQueue: List<NativeTrack>,
        currentTrack: NativeTrack?,
        styleCapsule: StyleCapsule?,
        referenceBindings: List<ReferenceBinding> = emptyList(),
    ): ActionExecutionResult
}
