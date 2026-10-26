package app.pipo.nativeapp.data

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Conservative catalog matching. Recommendation title families must never prove recording identity. */
object RecordingMatcher {
    enum class Match { SAME_SOURCE, CONFIRMED, POSSIBLE, DIFFERENT }
    private val spaces = Regex("\\s+")
    private val punctuation = Regex("[\\p{P}\\p{S}]")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(spaces, " ").trim()

    fun candidateKey(track: NativeTrack): String =
        "${normalize(track.title)}::${normalize(track.artist)}"

    fun compare(a: NativeTrack, b: NativeTrack): Match {
        if (a.musicSource != null && a.sourceKey == b.sourceKey) return Match.SAME_SOURCE
        if (!a.recordingId.isNullOrBlank() && a.recordingId == b.recordingId) return Match.CONFIRMED
        if (a.title.isBlank() || b.title.isBlank() || a.artist.isBlank() || b.artist.isBlank()) return Match.DIFFERENT
        if (normalize(a.title).replace(punctuation, "") != normalize(b.title).replace(punctuation, "")) return Match.DIFFERENT
        if (normalize(a.artist) != normalize(b.artist)) return Match.DIFFERENT
        if (a.durationMs > 0 && b.durationMs > 0 && abs(a.durationMs - b.durationMs) > 2_000) return Match.DIFFERENT
        // Equal metadata can still describe a remaster. Only a saved recording link proves equality.
        return Match.POSSIBLE
    }

    fun uniqueSources(tracks: List<NativeTrack>): List<NativeTrack> = tracks.distinctBy { it.sourceKey }

    fun displayTracks(tracks: List<NativeTrack>): List<NativeTrack> {
        val grouped = linkedMapOf<String, NativeTrack>()
        for (track in uniqueSources(tracks)) {
            val key = track.recordingId?.takeIf { it.isNotBlank() }?.let { "recording:$it" } ?: track.sourceKey
            val old = grouped[key]
            grouped[key] = if (old == null) track else old.copy(
                alternateSources = (old.alternateSources + listOfNotNull(track.musicSource) + track.alternateSources)
                    .filter { it.key != old.musicSource?.key }.distinctBy { it.key },
            )
        }
        return grouped.values.toList()
    }
}
