package app.pipo.nativeapp.data.agent.context

import app.pipo.nativeapp.data.NativeSettings
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PetMemory
import app.pipo.nativeapp.data.PetPersona
import app.pipo.nativeapp.data.agent.domain.AgentTurnInput
import app.pipo.nativeapp.data.agent.session.PlaybackIntentSessionStore

class AgentContextAssembler(
    private val sessionStore: PlaybackIntentSessionStore,
    private val referenceProvider: () -> List<AgentReference>,
    private val referenceResolver: ReferenceResolverV2 = ReferenceResolverV2(),
) {
    fun assemble(
        userText: String,
        promptContext: PetMemory.ConversationContext,
        currentTrack: NativeTrack?,
        currentQueue: List<NativeTrack>,
        settings: NativeSettings,
        persona: PetPersona,
    ): AgentTurnInput {
        val activeSession = sessionStore.active()
        val currentTrackStyle = StyleCapsuleBuilder.fromTrack(currentTrack)
        val currentQueueStyle = StyleCapsuleBuilder.fromQueue(currentQueue)
        val refs = referenceProvider()
        val resolution = referenceResolver.resolve(
            text = userText,
            currentTrack = currentTrack,
            currentTrackStyle = currentTrackStyle,
            currentQueueStyle = currentQueueStyle,
            activeSession = activeSession,
            references = refs,
        )
        return AgentTurnInput(
            userText = userText,
            history = promptContext.turns,
            historySummary = promptContext.summary,
            musicReferences = promptContext.musicReferences,
            currentTrack = currentTrack,
            currentQueue = currentQueue,
            userFacts = settings.userFacts,
            persona = persona,
            activeSession = activeSession,
            currentTrackStyle = currentTrackStyle,
            currentQueueStyle = currentQueueStyle,
            references = refs,
            resolvedTrackReference = resolution.trackRequirement,
            resolvedStyleReference = resolution.styleCapsule,
            resolvedArtistReference = resolution.artist,
            resolvedIntentReference = resolution.intent,
            referenceBindings = resolution.bindings,
            aiAutoContinueEnabled = settings.aiAutoContinueEnabled || settings.playbackMode == "AiRadio",
            defaultContinuationMode = settings.defaultContinuationMode,
            inheritAgentIntentWhenAvailable = settings.inheritAgentIntentWhenAvailable,
            inferManualQueueStyleWhenNoAgentIntent = settings.inferManualQueueStyleWhenNoAgentIntent,
        )
    }
}
