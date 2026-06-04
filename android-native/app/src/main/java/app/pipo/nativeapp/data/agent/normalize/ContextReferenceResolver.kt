package app.pipo.nativeapp.data.agent.normalize

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PetMemory
import app.pipo.nativeapp.data.agent.domain.TrackPlacement
import app.pipo.nativeapp.data.agent.domain.TrackRequirement

class ContextReferenceResolver {
    fun resolveMentionedMusic(
        text: String,
        references: List<PetMemory.MusicReference>,
    ): TrackRequirement? {
        val key = CommandTextSignals.normalizeCommandText(text)
        if (references.isEmpty()) return null
        val hasReferenceCue = listOf("那首", "这首", "它", "这个", "刚才说的", "刚刚说的", "听这个", "想听这个")
            .any { it in key }
        if (!hasReferenceCue) return null
        val ref = references.lastOrNull { it.title.isNotBlank() } ?: return null
        return TrackRequirement(
            title = ref.title,
            artist = ref.artist?.takeIf { it.isNotBlank() },
            placement = TrackPlacement.Now,
        )
    }

    fun resolveCurrentTrack(
        text: String,
        currentTrack: NativeTrack?,
    ): TrackRequirement? {
        val key = CommandTextSignals.normalizeCommandText(text)
        if (currentTrack == null) return null
        val hasCurrentCue = listOf("这首", "当前这首", "现在这首", "正在放的").any { it in key }
        if (!hasCurrentCue) return null
        return TrackRequirement(
            title = currentTrack.title,
            artist = currentTrack.artist.takeIf { it.isNotBlank() },
            placement = TrackPlacement.Now,
        )
    }
}
