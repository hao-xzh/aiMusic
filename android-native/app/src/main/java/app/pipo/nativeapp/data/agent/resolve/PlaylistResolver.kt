package app.pipo.nativeapp.data.agent.resolve

import app.pipo.nativeapp.data.PipoPlaylist
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals

class PlaylistResolver {
    fun resolve(query: String, playlists: List<PipoPlaylist>): ResolvedPlaylist? {
        val key = CommandTextSignals.normalizeForMatch(query)
        if (key.isBlank()) return null
        val exact = playlists.firstOrNull { CommandTextSignals.normalizeForMatch(it.name) == key }
        if (exact != null) return ResolvedPlaylist(exact, confidence = 1.0)
        val partial = playlists.firstOrNull {
            val nameKey = CommandTextSignals.normalizeForMatch(it.name)
            nameKey.contains(key) || key.contains(nameKey)
        }
        return partial?.let { ResolvedPlaylist(it, confidence = 0.76) }
    }
}

data class ResolvedPlaylist(
    val playlist: PipoPlaylist,
    val confidence: Double,
)
