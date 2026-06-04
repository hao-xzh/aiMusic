package app.pipo.nativeapp.data.agent.resolve

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals

class TrackResolver(
    private val repository: PipoRepository,
) {
    suspend fun resolve(
        requirement: TrackRequirement,
        library: List<NativeTrack>,
        allowOnline: Boolean = true,
    ): ResolvedTrack {
        val local = resolveLocal(requirement, library)
        if (local != null) {
            return ResolvedTrack(
                requirement = requirement,
                track = local,
                candidates = listOf(local),
                confidence = if (requirement.artist.isNullOrBlank()) 0.9 else 0.98,
                source = ResolveSource.Local,
            )
        }
        if (!allowOnline) {
            return ResolvedTrack(
                requirement = requirement,
                track = null,
                candidates = emptyList(),
                confidence = 0.0,
                source = ResolveSource.None,
                error = ResolveError.NotFound,
            )
        }
        val query = listOfNotNull(requirement.artist, requirement.title).joinToString(" ")
        val online = runCatching { repository.searchTracks(query, limit = 8) }.getOrDefault(emptyList())
        val picked = online
            .filter { requirement.artist.isNullOrBlank() || artistMatches(it.artist, requirement.artist) }
            .minByOrNull { variantWeight(it.title) }
            ?: online.minByOrNull { variantWeight(it.title) }
        return ResolvedTrack(
            requirement = requirement,
            track = picked,
            candidates = online,
            confidence = if (picked == null) 0.0 else if (requirement.artist.isNullOrBlank()) 0.72 else 0.82,
            source = if (picked == null) ResolveSource.None else ResolveSource.Online,
            error = if (picked == null) ResolveError.NotFound else null,
        )
    }

    private fun resolveLocal(requirement: TrackRequirement, library: List<NativeTrack>): NativeTrack? {
        val titleKey = CommandTextSignals.normalizeForMatch(requirement.title)
        if (titleKey.isBlank()) return null
        val exact = library.filter {
            CommandTextSignals.normalizeForMatch(it.title) == titleKey &&
                (requirement.artist.isNullOrBlank() || artistMatches(it.artist, requirement.artist))
        }
        if (exact.isNotEmpty()) return exact.minByOrNull { variantWeight(it.title) }
        val partial = library.filter {
            val key = CommandTextSignals.normalizeForMatch(it.title)
            key.isNotBlank() && (titleKey in key || key in titleKey) &&
                (requirement.artist.isNullOrBlank() || artistMatches(it.artist, requirement.artist))
        }
        return partial.minByOrNull { variantWeight(it.title) }
    }

    private fun artistMatches(leftRaw: String, rightRaw: String?): Boolean {
        if (rightRaw.isNullOrBlank()) return true
        val left = CommandTextSignals.normalizeForMatch(leftRaw)
        val right = CommandTextSignals.normalizeForMatch(rightRaw)
        return right.isNotBlank() && (left == right || left.contains(right) || right.contains(left))
    }

    private fun variantWeight(title: String): Int {
        val lower = title.lowercase()
        var weight = title.length
        if ("live" in lower || "现场" in lower || "演唱会" in lower) weight += 1000
        if ("伴奏" in lower || "instrumental" in lower || "karaoke" in lower) weight += 1000
        if ("cover" in lower || "翻唱" in lower) weight += 800
        if ("remix" in lower || "混音" in lower) weight += 700
        if ("acoustic" in lower || "unplugged" in lower) weight += 600
        if ("demo" in lower) weight += 500
        return weight
    }
}

data class ResolvedTrack(
    val requirement: TrackRequirement,
    val track: NativeTrack?,
    val candidates: List<NativeTrack>,
    val confidence: Double,
    val source: ResolveSource,
    val error: ResolveError? = null,
)

enum class ResolveSource { Local, Online, None }

enum class ResolveError { NotFound }
