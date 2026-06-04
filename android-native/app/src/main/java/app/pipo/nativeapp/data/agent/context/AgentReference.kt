package app.pipo.nativeapp.data.agent.context

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.intent.MusicIntent

sealed class AgentReference {
    abstract val refId: String
    abstract val label: String
    abstract val createdAtMs: Long

    data class TrackRef(
        override val refId: String,
        override val label: String,
        val track: NativeTrack,
        val reason: String = "",
        override val createdAtMs: Long = System.currentTimeMillis(),
    ) : AgentReference()

    data class ArtistRef(
        override val refId: String,
        override val label: String,
        val artist: String,
        val reason: String = "",
        override val createdAtMs: Long = System.currentTimeMillis(),
    ) : AgentReference()

    data class StyleRef(
        override val refId: String,
        override val label: String,
        val capsule: StyleCapsule,
        val reason: String = "",
        override val createdAtMs: Long = System.currentTimeMillis(),
    ) : AgentReference()

    data class QueueRef(
        override val refId: String,
        override val label: String,
        val trackIds: List<String>,
        val reason: String = "",
        override val createdAtMs: Long = System.currentTimeMillis(),
    ) : AgentReference()

    data class IntentRef(
        override val refId: String,
        override val label: String,
        val intent: MusicIntent,
        val intentHash: String,
        val reason: String = "",
        override val createdAtMs: Long = System.currentTimeMillis(),
    ) : AgentReference()
}

data class ReferenceBinding(
    val phrase: String,
    val refId: String,
    val refType: String,
    val confidence: Double,
)
