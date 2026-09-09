package app.pipo.nativeapp.data.agent.queue

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.TrackLanguage
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals

data class TrackConstraintMetadata(
    val language: String? = null,
    val genres: List<String> = emptyList(),
    val verified: Boolean = false,
    val tags: List<String> = emptyList(),
)

enum class ConstraintEvidence { Match, Mismatch, Unknown }

class ConstraintScorer(
    private val metadataForTrack: (NativeTrack) -> TrackConstraintMetadata = ::pipoMetadata,
) {
    fun hitsAvoidTerm(track: NativeTrack, terms: List<String>): Boolean {
        return avoidEvidence(track, terms) == ConstraintEvidence.Match
    }

    fun avoidEvidence(track: NativeTrack, terms: List<String>): ConstraintEvidence {
        if (terms.isEmpty()) return ConstraintEvidence.Mismatch
        val needles = terms.map(CommandTextSignals::normalizeForMatch).filter { it.isNotBlank() }
        val text = CommandTextSignals.normalizeForMatch("${track.title} ${track.artist} ${track.album}")
        val metadata = metadataForTrack(track)
        // Genre/version exclusions use verified metadata when available. A song called
        // "No Rap Cruise" is not rap just because those letters occur in its title.
        // Artist/title exclusions remain literal constraints.
        if (needles.any { needle ->
            !(metadata.verified && isSemanticExclusion(needle)) && needle in text
        }) return ConstraintEvidence.Match
        if (!metadata.verified) return ConstraintEvidence.Unknown
        return if (needles.any { needle ->
            (metadata.genres + metadata.tags).any { value -> matches(needle, value) }
        }) {
            ConstraintEvidence.Match
        } else {
            ConstraintEvidence.Mismatch
        }
    }

    fun hardLanguageEvidence(track: NativeTrack, required: List<String>): ConstraintEvidence {
        val metadata = metadataForTrack(track)
        val actual = metadata.language?.takeIf { metadata.verified && it.isNotBlank() && it != TrackLanguage.Unknown.key }
            ?: return ConstraintEvidence.Unknown
        return if (required.any { languageMatches(it, actual) }) ConstraintEvidence.Match else ConstraintEvidence.Mismatch
    }

    fun excludedLanguageEvidence(track: NativeTrack, excluded: List<String>): ConstraintEvidence {
        if (excluded.isEmpty()) return ConstraintEvidence.Mismatch
        val metadata = metadataForTrack(track)
        val actual = metadata.language?.takeIf { metadata.verified && it.isNotBlank() && it != TrackLanguage.Unknown.key }
            ?: return ConstraintEvidence.Unknown
        return if (excluded.any { languageMatches(it, actual) }) ConstraintEvidence.Match else ConstraintEvidence.Mismatch
    }

    fun hardGenreEvidence(track: NativeTrack, required: List<String>): ConstraintEvidence {
        val metadata = metadataForTrack(track)
        if (!metadata.verified || metadata.genres.isEmpty()) return ConstraintEvidence.Unknown
        return if (required.any { wanted -> metadata.genres.any { actual -> matches(wanted, actual) } }) {
            ConstraintEvidence.Match
        } else {
            ConstraintEvidence.Mismatch
        }
    }

    fun verifiedLanguage(track: NativeTrack): String? {
        val metadata = metadataForTrack(track)
        return metadata.language?.takeIf { metadata.verified && it.isNotBlank() && it != TrackLanguage.Unknown.key }
    }

    companion object {
        fun pipoMetadata(track: NativeTrack): TrackConstraintMetadata {
            val profile = runCatching { PipoGraph.trackSemanticStore.get(track.id) }.getOrNull()
                ?: return TrackConstraintMetadata()
            return TrackConstraintMetadata(
                language = profile.language.key.takeIf { it != TrackLanguage.Unknown.key },
                genres = profile.genres + profile.subGenres,
                tags = profile.negativeTags + profile.moods + profile.scenes + profile.textures + profile.styleAnchors,
                // These profiles are currently inferred from title/artist/lyrics or an
                // LLM. sourceMetadata describes inputs, not authoritative genre/language.
                verified = false,
            )
        }
    }

    private fun languageMatches(required: String, actual: String): Boolean =
        canonicalLanguage(required) == canonicalLanguage(actual)

    private fun isSemanticExclusion(value: String): Boolean = canonicalGenre(value) in setOf(
        "hip-hop", "electronic", "jazz", "folk", "rock", "r&b", "soul", "citypop", "live", "cover", "instrumental",
    )

    private fun canonicalLanguage(value: String): String = when (CommandTextSignals.normalizeForMatch(value)) {
        "en", "english", "英文", "英语" -> "english"
        "zh", "chinese", "mandarin", "中文", "华语", "国语", "普通话" -> "mandarin"
        "yue", "cantonese", "粤语" -> "cantonese"
        "ja", "japanese", "日语", "日文" -> "japanese"
        "ko", "korean", "韩语", "韩文" -> "korean"
        else -> CommandTextSignals.normalizeForMatch(value)
    }

    private fun matches(left: String, right: String): Boolean {
        val a = canonicalGenre(left)
        val b = canonicalGenre(right)
        return a.isNotBlank() && a == b
    }

    private fun canonicalGenre(value: String): String = when (CommandTextSignals.normalizeForMatch(value)) {
        "rnb", "r&b", "节奏布鲁斯" -> "r&b"
        "hiphop", "嘻哈", "说唱", "rap", "rapheavy" -> "hip-hop"
        "edm", "电子", "电音", "dance", "house", "techno", "trance" -> "electronic"
        "jazz", "爵士", "轻爵士", "smoothjazz", "bossa", "swing" -> "jazz"
        "live", "现场", "现场版", "演唱会", "concert" -> "live"
        "cover", "翻唱", "翻唱版" -> "cover"
        "instrumental", "伴奏", "伴奏版" -> "instrumental"
        "folk", "民谣", "acoustic", "unplugged" -> "folk"
        "rock", "摇滚", "indierock", "britpop" -> "rock"
        "soul", "灵魂", "neosoul" -> "soul"
        "citypop", "城市流行" -> "citypop"
        else -> CommandTextSignals.normalizeForMatch(value)
    }
}
