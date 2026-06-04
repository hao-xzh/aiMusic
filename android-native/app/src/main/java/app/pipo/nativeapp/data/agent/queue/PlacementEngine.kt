package app.pipo.nativeapp.data.agent.queue

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals

class PlacementEngine {
    fun reorderReplaceQueue(userText: String, tracks: List<NativeTrack>): List<NativeTrack> {
        if (tracks.size <= 1) return tracks
        var out = tracks.toMutableList()
        val primaryArtists = CommandTextSignals.primaryArtistHints(userText)
        if (primaryArtists.isNotEmpty()) {
            val primary = out.filter { track -> primaryArtists.any { artistMatches(track, it) } }
            if (primary.isNotEmpty()) {
                val primaryKeys = primary.mapTo(HashSet()) { songKey(it) }
                out = (primary + out.filter { songKey(it) !in primaryKeys }).toMutableList()
            }
        }

        if (CommandTextSignals.normalizeCommandText(userText).contains("穿插")) {
            out = interleaveLanguages(out, CommandTextSignals.languageIncludes(userText)).toMutableList()
        }

        val includeTitle = CommandTextSignals.includedTrackTitle(userText)
        if (!includeTitle.isNullOrBlank()) {
            out = moveTrack(out, includeTitle, targetIndex = minOf(3, out.lastIndex), keepIfBetween = 1..3)
        }

        val closerTitle = CommandTextSignals.closerTrackTitle(userText)
        if (!closerTitle.isNullOrBlank()) {
            out = moveTrack(out, closerTitle, targetIndex = out.lastIndex, keepIfBetween = IntRange.EMPTY)
        }
        return out
    }

    private fun moveTrack(
        tracks: MutableList<NativeTrack>,
        title: String,
        targetIndex: Int,
        keepIfBetween: IntRange,
    ): MutableList<NativeTrack> {
        val idx = tracks.indexOfFirst { titleMatches(it, title) }
        if (idx < 0) return tracks
        if (!keepIfBetween.isEmpty() && idx in keepIfBetween) return tracks
        val track = tracks.removeAt(idx)
        val insertion = targetIndex.coerceIn(0, tracks.size)
        tracks.add(insertion, track)
        return tracks
    }

    private fun titleMatches(track: NativeTrack, title: String): Boolean {
        val left = CommandTextSignals.normalizeForMatch(track.title)
        val right = CommandTextSignals.normalizeForMatch(title)
        return right.isNotBlank() && (left == right || left.contains(right) || right.contains(left))
    }

    private fun artistMatches(track: NativeTrack, artist: String): Boolean {
        val left = CommandTextSignals.normalizeForMatch(track.artist)
        val right = CommandTextSignals.normalizeForMatch(artist)
        return right.isNotBlank() && (left == right || left.contains(right) || right.contains(left))
    }

    private fun songKey(track: NativeTrack): String =
        "${CommandTextSignals.normalizeForMatch(track.title)}:${CommandTextSignals.normalizeForMatch(track.artist)}"

    private fun interleaveLanguages(tracks: List<NativeTrack>, languages: List<String>): List<NativeTrack> {
        if (tracks.size < 4 || languages.size < 2) return tracks
        val head = tracks.first()
        val remaining = tracks.drop(1)
        val buckets = languages.associateWith { lang ->
            ArrayDeque(remaining.filter { trackLanguage(it) == lang })
        }
        val bucketKeys = buckets.values.flatten().mapTo(HashSet(), ::songKey)
        val others = ArrayDeque(remaining.filter { songKey(it) !in bucketKeys })
        val out = ArrayList<NativeTrack>(tracks.size)
        out.add(head)
        var nextLanguageIndex = nextLanguageIndexAfter(trackLanguage(head), languages)
        while (out.size < tracks.size) {
            var picked: NativeTrack? = null
            repeat(languages.size) {
                val lang = languages[(nextLanguageIndex + it) % languages.size]
                val bucket = buckets[lang]
                if (picked == null && bucket != null && bucket.isNotEmpty()) {
                    picked = bucket.removeFirst()
                    nextLanguageIndex = (languages.indexOf(lang) + 1) % languages.size
                }
            }
            if (picked != null) {
                out.add(picked!!)
            } else if (others.isNotEmpty()) {
                out.add(others.removeFirst())
            } else {
                break
            }
        }
        return out + remaining.filter { candidate -> out.none { songKey(it) == songKey(candidate) } }
    }

    private fun nextLanguageIndexAfter(current: String?, languages: List<String>): Int {
        val idx = languages.indexOf(current)
        return if (idx >= 0) (idx + 1) % languages.size else 0
    }

    private fun trackLanguage(track: NativeTrack): String? {
        val profile = PipoGraph.trackSemanticStore.get(track.id)
            ?: PipoGraph.semanticIndexer.buildRuleBasedProfile(track, PipoGraph.audioFeaturesStore.get(track.id))
        return profile.language.key.takeIf { it != "unknown" }
    }
}
