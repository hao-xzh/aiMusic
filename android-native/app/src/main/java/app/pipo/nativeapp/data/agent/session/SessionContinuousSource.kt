package app.pipo.nativeapp.data.agent.session

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.intent.MusicIntent

interface SessionContinuousSource : ContinuousQueueSource {
    val sessionId: String
    val generation: Long
    val activeIntentHash: String
    val origin: SessionOrigin
    val policy: ContinuationPolicy

    suspend fun fetchMore(context: ContinuationContext): ContinuationResult
}

data class ContinuationContext(
    val activeSession: PlaybackIntentSession,
    val currentTrack: NativeTrack?,
    val currentQueue: List<NativeTrack>,
    val excludeIds: Set<Long>,
    val excludeSongKeys: Set<String>,
    val reason: ContinuationReason,
)

data class ContinuationResult(
    val tracks: List<NativeTrack>,
    val updatedSession: PlaybackIntentSession? = null,
    val diagnostics: List<String> = emptyList(),
)

enum class ContinuationReason {
    LowRemaining,
    ForceNextAtQueueEnd,
    UserPressedNextAtEnd,
    Recovery,
}

class MusicIntentSessionContinuousSource(
    override val sessionId: String,
    override val generation: Long,
    override val activeIntentHash: String,
    override val origin: SessionOrigin,
    val intent: MusicIntent,
    override val policy: ContinuationPolicy,
    private val store: PlaybackIntentSessionStore,
    private val fetcher: suspend (MusicIntent, Set<Long>, Int) -> List<NativeTrack>,
) : SessionContinuousSource {
    override suspend fun fetchMore(context: ContinuationContext): ContinuationResult {
        if (!matches(context.activeSession) || !store.isCurrent(sessionId, generation, activeIntentHash)) {
            recordStale(reason = context.reason.name)
            return ContinuationResult(emptyList(), diagnostics = listOf("stale_session"))
        }
        val batchSize = policy.desiredBatchSize.coerceIn(1, 30)
        val tracks = fetcher(intent, context.excludeIds, batchSize)
        return ContinuationResult(tracks)
    }

    override suspend fun fetchMore(excludeIds: Set<Long>): List<NativeTrack> {
        val active = store.active()
        if (active == null || !matches(active) || !store.isCurrent(sessionId, generation, activeIntentHash)) {
            recordStale(reason = "legacy_fetch")
            return emptyList()
        }
        return fetchMore(
            ContinuationContext(
                activeSession = active,
                currentTrack = null,
                currentQueue = emptyList(),
                excludeIds = excludeIds,
                excludeSongKeys = emptySet(),
                reason = ContinuationReason.LowRemaining,
            ),
        ).tracks
    }

    private fun matches(session: PlaybackIntentSession): Boolean =
        session.sessionId == sessionId &&
            session.generation == generation &&
            session.activeIntentHash == activeIntentHash &&
            session.isActive()

    private fun recordStale(reason: String) {
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "session_continuation_stale",
            fields = mapOf(
                "sessionId" to sessionId,
                "generation" to generation,
                "intentHash" to activeIntentHash,
                "origin" to origin.name,
                "reason" to reason,
            ),
        )
    }
}

class LegacySessionContinuousSource(
    override val sessionId: String,
    override val generation: Long,
    override val activeIntentHash: String,
    override val origin: SessionOrigin,
    override val policy: ContinuationPolicy,
    private val legacy: ContinuousQueueSource,
    private val store: PlaybackIntentSessionStore,
) : SessionContinuousSource {
    override suspend fun fetchMore(context: ContinuationContext): ContinuationResult {
        if (context.activeSession.sessionId != sessionId ||
            context.activeSession.generation != generation ||
            context.activeSession.activeIntentHash != activeIntentHash ||
            !store.isCurrent(sessionId, generation, activeIntentHash)
        ) {
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "legacy_session_continuation_stale",
                fields = mapOf(
                    "sessionId" to sessionId,
                    "generation" to generation,
                    "intentHash" to activeIntentHash,
                    "origin" to origin.name,
                ),
            )
            return ContinuationResult(emptyList(), diagnostics = listOf("stale_session"))
        }
        return ContinuationResult(legacy.fetchMore(context.excludeIds))
    }

    override suspend fun fetchMore(excludeIds: Set<Long>): List<NativeTrack> {
        val active = store.active() ?: return emptyList()
        return fetchMore(
            ContinuationContext(
                activeSession = active,
                currentTrack = null,
                currentQueue = emptyList(),
                excludeIds = excludeIds,
                excludeSongKeys = emptySet(),
                reason = ContinuationReason.LowRemaining,
            ),
        ).tracks
    }
}
