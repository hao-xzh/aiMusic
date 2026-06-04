package app.pipo.nativeapp.data.agent.normalize

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoPlaylist

class CatalogLexicon(
    private val tracks: List<NativeTrack>,
    private val playlists: List<PipoPlaylist> = emptyList(),
) {
    fun findTrackMentions(text: String): List<TrackMention> {
        val key = CommandTextSignals.normalizeForMatch(text)
        if (key.isBlank()) return emptyList()
        return tracks.asSequence()
            .mapNotNull { track ->
                val titleKey = CommandTextSignals.normalizeForMatch(track.title)
                if (titleKey.length < 2 || titleKey !in key) return@mapNotNull null
                TrackMention(
                    title = track.title,
                    artist = track.artist,
                    track = track,
                    confidence = if (key.contains(CommandTextSignals.normalizeForMatch(track.artist))) 0.98 else 0.82,
                )
            }
            .distinctBy { "${it.title}:${it.artist}" }
            .sortedByDescending { it.confidence }
            .take(8)
            .toList()
    }

    fun findArtistMentions(text: String): List<ArtistMention> {
        val key = CommandTextSignals.normalizeForMatch(text)
        if (key.isBlank()) return emptyList()
        return tracks.asSequence()
            .flatMap { it.artist.split("/", "&", ",", " feat.", "feat.").asSequence() }
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .mapNotNull { artist ->
                val artistKey = CommandTextSignals.normalizeForMatch(artist)
                if (artistKey.isNotBlank() && artistKey in key) ArtistMention(artist, confidence = 0.9) else null
            }
            .take(8)
            .toList()
    }

    fun findPlaylistMentions(text: String): List<PlaylistMention> {
        val key = CommandTextSignals.normalizeForMatch(text)
        if (key.isBlank()) return emptyList()
        return playlists.mapNotNull { playlist ->
            val nameKey = CommandTextSignals.normalizeForMatch(playlist.name)
            if (nameKey.isNotBlank() && (nameKey in key || key in nameKey)) {
                PlaylistMention(playlist.name, playlist.id, confidence = if (nameKey == key) 1.0 else 0.78)
            } else {
                null
            }
        }.sortedByDescending { it.confidence }
    }
}

data class TrackMention(
    val title: String,
    val artist: String,
    val track: NativeTrack,
    val confidence: Double,
)

data class ArtistMention(
    val name: String,
    val confidence: Double,
)

data class PlaylistMention(
    val name: String,
    val playlistId: Long,
    val confidence: Double,
)
