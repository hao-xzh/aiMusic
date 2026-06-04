package app.pipo.nativeapp.data.agent.session

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.context.StyleCapsule
import app.pipo.nativeapp.data.agent.intent.MusicIntent
import app.pipo.nativeapp.playback.orchestrator.MixPolicy
import app.pipo.nativeapp.playback.orchestrator.QueueHardConstraints
import app.pipo.nativeapp.playback.orchestrator.QueueSoftPreferences

data class PlaybackIntentSession(
    val sessionId: String,
    val generation: Long,
    val status: SessionStatus,
    val origin: SessionOrigin,
    val rootUserText: String,
    val lastUserText: String,
    val activeIntent: MusicIntent,
    val activeIntentHash: String,
    val continuationPolicy: ContinuationPolicy,
    val queuePolicy: QueuePolicy,
    val mixPolicy: MixPolicy,
    val hardConstraints: QueueHardConstraints,
    val softPreferences: QueueSoftPreferences,
    val styleAnchor: StyleCapsule? = null,
    val trackAnchor: NativeTrack? = null,
    val queueAnchorIds: List<String> = emptyList(),
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val expiresAtMs: Long? = null,
) {
    fun isActive(nowMs: Long = System.currentTimeMillis()): Boolean =
        status == SessionStatus.Active && (expiresAtMs == null || expiresAtMs > nowMs)
}

enum class SessionStatus {
    Active,
    Superseded,
    Paused,
    Expired,
}

enum class SessionOrigin {
    AgentInstruction,
    ManualPlaylist,
    CurrentTrackStyle,
    CurrentQueueStyle,
    DefaultAiRadio,
    Recovery,
}

data class ContinuationPolicy(
    val enabled: Boolean,
    val mode: ContinuationMode,
    val inheritHardConstraints: Boolean = true,
    val inheritSoftPreferences: Boolean = true,
    val inheritMixPolicy: Boolean = true,
    val desiredBatchSize: Int = 8,
    val maxTotalQueueSize: Int = 80,
    val expireOnManualReplace: Boolean = true,
    val expireOnNewAgentSession: Boolean = true,
)

enum class ContinuationMode {
    Off,
    SameIntent,
    CurrentTrackStyle,
    CurrentQueueStyle,
    ManualPlaylistStyle,
    DefaultAiRadio,
}

data class QueuePolicy(
    val preserveCurrentTrack: Boolean = false,
    val defaultDesiredCount: Int = 12,
    val allowOnlineBackfill: Boolean = true,
)

enum class SessionMutation {
    KeepCurrentSession,
    CreateNewSession,
    UpdateCurrentSession,
    SupersedeCurrentSession,
    PauseCurrentSession,
    DisableContinuation,
    None,
}
