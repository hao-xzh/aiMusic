package app.pipo.nativeapp.data.agent.resolve

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals

class ArtistResolver(
    private val library: List<NativeTrack>,
) {
    fun resolve(raw: String): ResolvedArtist {
        val rawKey = CommandTextSignals.normalizeForMatch(raw)
        val group = aliasGroup(rawKey)
        val aliases = group?.let { listOf(it.key) + it.value }.orEmpty()
        val candidates = library.asSequence()
            .flatMap { it.artist.split("/", "&", ",", " feat.", "feat.").asSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val exact = candidates.firstOrNull { CommandTextSignals.normalizeForMatch(it) == rawKey }
        val aliasHit = if (exact == null) {
            candidates.firstOrNull { candidate ->
                val candidateKey = CommandTextSignals.normalizeForMatch(candidate)
                aliases.any { alias -> CommandTextSignals.normalizeForMatch(alias) == candidateKey }
            }
        } else null
        val canonical = exact ?: aliasHit ?: group?.key ?: raw
        return ResolvedArtist(
            raw = raw,
            canonical = canonical,
            aliases = aliases,
            confidence = when {
                exact != null -> 1.0
                aliasHit != null -> 0.92
                else -> 0.55
            },
        )
    }

    companion object {
        fun identityKey(raw: String): String {
            val key = CommandTextSignals.normalizeForMatch(raw)
            return aliasGroup(key)?.key?.let(CommandTextSignals::normalizeForMatch) ?: key
        }

        private fun aliasGroup(key: String): Map.Entry<String, List<String>>? =
            MANUAL_ALIASES.entries.firstOrNull { (name, aliases) ->
                CommandTextSignals.normalizeForMatch(name) == key ||
                    aliases.any { CommandTextSignals.normalizeForMatch(it) == key }
            }

        private val MANUAL_ALIASES = mapOf(
            "陈奕迅" to listOf("Eason Chan", "Eason"),
            "eason" to listOf("陈奕迅", "Eason Chan"),
            "周杰伦" to listOf("Jay Chou", "Jay"),
            "jaychou" to listOf("周杰伦", "Jay"),
            "莫文蔚" to listOf("Karen Mok"),
            "karenmok" to listOf("莫文蔚"),
            "汪苏泷" to listOf("Silence Wang"),
            "silencewang" to listOf("汪苏泷"),
            "The Weeknd" to listOf("Weeknd", "The Weekend", "威肯", "盆栽"),
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
