package app.pipo.nativeapp.data.agent.context

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.domain.TrackPlacement
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.intent.MusicIntent
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import app.pipo.nativeapp.data.agent.session.PlaybackIntentSession

class ReferenceResolverV2 {
    fun resolve(
        text: String,
        currentTrack: NativeTrack?,
        currentTrackStyle: StyleCapsule?,
        currentQueueStyle: StyleCapsule?,
        activeSession: PlaybackIntentSession?,
        references: List<AgentReference>,
    ): ReferenceResolution {
        val key = CommandTextSignals.normalizeCommandText(text)
        val bindings = ArrayList<ReferenceBinding>()
        val track = resolveTrack(key, currentTrack, references, bindings)
        val style = resolveStyle(key, currentTrackStyle, currentQueueStyle, references, bindings)
        val artist = resolveArtist(key, references, bindings)
        val intent = resolveIntent(key, activeSession, references, bindings)
        return ReferenceResolution(track, style, artist, intent, bindings)
    }

    private fun resolveTrack(
        key: String,
        currentTrack: NativeTrack?,
        references: List<AgentReference>,
        bindings: MutableList<ReferenceBinding>,
    ): TrackRequirement? {
        val wantsCurrent = listOf("这首", "当前这首", "现在这首", "正在放的").any { it in key }
        if (wantsCurrent && currentTrack != null) {
            bindings.add(ReferenceBinding("这首", currentTrack.id, "TrackRef", 0.95))
            return TrackRequirement(currentTrack.title, currentTrack.artist.takeIf { it.isNotBlank() }, TrackPlacement.Now)
        }
        val wantsRecent = listOf("那首", "刚才那首", "刚刚那首", "它", "这个").any { it in key }
        if (!wantsRecent) return null
        val ref = references.asReversed().filterIsInstance<AgentReference.TrackRef>().firstOrNull() ?: return null
        bindings.add(ReferenceBinding("那首", ref.refId, "TrackRef", 0.75))
        return TrackRequirement(ref.track.title, ref.track.artist.takeIf { it.isNotBlank() }, TrackPlacement.Now)
    }

    private fun resolveStyle(
        key: String,
        currentTrackStyle: StyleCapsule?,
        currentQueueStyle: StyleCapsule?,
        references: List<AgentReference>,
        bindings: MutableList<ReferenceBinding>,
    ): StyleCapsule? {
        val wantsCurrentStyle = listOf("当前风格", "这种风格", "这个风格", "这种感觉", "这个感觉", "像这首").any { it in key }
        if (wantsCurrentStyle) {
            val style = currentTrackStyle ?: currentQueueStyle
            if (style != null) {
                bindings.add(ReferenceBinding("当前风格", style.capsuleId, "StyleRef", 0.95))
                return style
            }
        }
        val wantsRecentStyle = listOf("刚才那个感觉", "刚才那个风格", "刚刚那个感觉", "继续刚才").any { it in key }
        if (!wantsRecentStyle) return null
        val ref = references.asReversed().filterIsInstance<AgentReference.StyleRef>().firstOrNull() ?: return null
        bindings.add(ReferenceBinding("刚才那个感觉", ref.refId, "StyleRef", 0.8))
        return ref.capsule
    }

    private fun resolveArtist(
        key: String,
        references: List<AgentReference>,
        bindings: MutableList<ReferenceBinding>,
    ): String? {
        val wantsRecentArtist = listOf("刚才那个歌手", "刚刚那个歌手", "那个歌手", "这个歌手").any { it in key }
        if (!wantsRecentArtist) return null
        val ref = references.asReversed().filterIsInstance<AgentReference.ArtistRef>().firstOrNull() ?: return null
        bindings.add(ReferenceBinding("那个歌手", ref.refId, "ArtistRef", 0.78))
        return ref.artist.takeIf { it.isNotBlank() }
    }

    private fun resolveIntent(
        key: String,
        activeSession: PlaybackIntentSession?,
        references: List<AgentReference>,
        bindings: MutableList<ReferenceBinding>,
    ): MusicIntent? {
        val wantsActive = listOf("继续这个要求", "按这个要求", "照这个要求", "继续现在这个", "续同要求").any { it in key }
        if (wantsActive && activeSession?.isActive() == true) {
            bindings.add(ReferenceBinding("这个要求", activeSession.sessionId, "IntentRef", 0.9))
            return activeSession.activeIntent
        }
        val wantsRecent = listOf("刚才那个要求", "刚刚那个要求").any { it in key }
        if (!wantsRecent) return null
        val ref = references.asReversed().filterIsInstance<AgentReference.IntentRef>().firstOrNull() ?: return null
        bindings.add(ReferenceBinding("刚才那个要求", ref.refId, "IntentRef", 0.75))
        return ref.intent
    }
}

data class ReferenceResolution(
    val trackRequirement: TrackRequirement?,
    val styleCapsule: StyleCapsule?,
    val artist: String?,
    val intent: MusicIntent?,
    val bindings: List<ReferenceBinding>,
)
