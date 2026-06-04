package app.pipo.nativeapp.data.agent.resolve

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals

class ArtistResolver(
    private val library: List<NativeTrack>,
) {
    fun resolve(raw: String): ResolvedArtist {
        val aliases = MANUAL_ALIASES[CommandTextSignals.normalizeForMatch(raw)].orEmpty()
        val candidates = library.asSequence()
            .flatMap { it.artist.split("/", "&", ",", " feat.", "feat.").asSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val rawKey = CommandTextSignals.normalizeForMatch(raw)
        val exact = candidates.firstOrNull { CommandTextSignals.normalizeForMatch(it) == rawKey }
        val aliasHit = if (exact == null) {
            candidates.firstOrNull { candidate ->
                val candidateKey = CommandTextSignals.normalizeForMatch(candidate)
                aliases.any { alias -> CommandTextSignals.normalizeForMatch(alias) == candidateKey }
            }
        } else null
        val partial = if (exact == null && aliasHit == null) {
            candidates.firstOrNull { candidate ->
                val key = CommandTextSignals.normalizeForMatch(candidate)
                rawKey in key || key in rawKey
            }
        } else null
        val canonical = exact ?: aliasHit ?: partial ?: raw
        return ResolvedArtist(
            raw = raw,
            canonical = canonical,
            aliases = aliases,
            confidence = when {
                exact != null -> 1.0
                aliasHit != null -> 0.92
                partial != null -> 0.74
                else -> 0.55
            },
        )
    }

    private companion object {
        val MANUAL_ALIASES = mapOf(
            "陈奕迅" to listOf("Eason Chan", "Eason"),
            "eason" to listOf("陈奕迅", "Eason Chan"),
            "周杰伦" to listOf("Jay Chou", "Jay"),
            "jaychou" to listOf("周杰伦", "Jay"),
            "莫文蔚" to listOf("Karen Mok"),
            "karenmok" to listOf("莫文蔚"),
            "汪苏泷" to listOf("Silence Wang"),
            "silencewang" to listOf("汪苏泷"),
            "theweeknd" to listOf("Weeknd", "The Weekend", "威肯", "盆栽"),
            "sza" to listOf("SZA"),
        )
    }
}

data class ResolvedArtist(
    val raw: String,
    val canonical: String,
    val aliases: List<String>,
    val confidence: Double,
)
