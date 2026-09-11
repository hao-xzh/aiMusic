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
        acceptsTrack: (NativeTrack) -> Boolean = { true },
    ): ResolvedTrack {
        val local = resolveLocal(requirement, library).filter(acceptsTrack)
        val localPick = pickMatchingVersion(local, requirement)
        if (localPick != null) {
            val track = localPick
            return ResolvedTrack(
                requirement = requirement,
                track = track,
                candidates = local,
                confidence = if (requirement.artist.isNullOrBlank()) 0.9 else 0.98,
                source = ResolveSource.Local,
            )
        }
        if (local.size > 1) {
            return ResolvedTrack(
                requirement = requirement,
                track = null,
                candidates = local,
                confidence = 0.0,
                source = ResolveSource.None,
                error = ResolveError.Ambiguous,
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
        val online = repository.searchTracks(query, limit = 8)
        val matches = online.filter { requirementMatches(it, requirement) && acceptsTrack(it) }
        val distinctMatches = matches.distinctBy { it.id }
        val picked = pickMatchingVersion(distinctMatches, requirement)
        return ResolvedTrack(
            requirement = requirement,
            track = picked,
            candidates = matches,
            confidence = if (picked == null) 0.0 else if (requirement.artist.isNullOrBlank()) 0.72 else 0.82,
            source = if (picked == null) ResolveSource.None else ResolveSource.Online,
            error = when {
                distinctMatches.isEmpty() -> ResolveError.NotFound
                picked == null -> ResolveError.Ambiguous
                else -> null
            },
        )
    }

    private fun resolveLocal(requirement: TrackRequirement, library: List<NativeTrack>): List<NativeTrack> {
        val titleKey = CommandTextSignals.normalizeForMatch(requirement.title)
        if (titleKey.isBlank()) return emptyList()
        return library.filter { requirementMatches(it, requirement) }.distinctBy { it.id }
    }

    private fun requirementMatches(track: NativeTrack, requirement: TrackRequirement): Boolean =
        CommandTextSignals.trackTitleMatches(track.title, requirement.title) &&
            (requirement.artist.isNullOrBlank() || artistMatches(track.artist, requirement.artist))

    private fun pickMatchingVersion(matches: List<NativeTrack>, requirement: TrackRequirement): NativeTrack? {
        if (matches.size <= 1) return matches.singleOrNull()
        val exact = matches.filter {
            CommandTextSignals.normalizeForMatch(it.title) == CommandTextSignals.normalizeForMatch(requirement.title)
        }
        val preferred = exact.ifEmpty { matches }
        if (preferred.size == 1) return preferred.single()
        // Do not resolve a genuinely ambiguous performer by arbitrary search order.
        return preferred.firstOrNull().takeIf {
            !requirement.artist.isNullOrBlank() || preferred.map { CommandTextSignals.normalizeForMatch(it.artist) }.distinct().size == 1
        }
    }

    private fun artistMatches(leftRaw: String, rightRaw: String?): Boolean {
        if (rightRaw.isNullOrBlank()) return true
        val right = CommandTextSignals.normalizeForMatch(rightRaw)
        return right.isNotBlank() && leftRaw.split("/", "&", ",", "、")
            .any { CommandTextSignals.normalizeForMatch(it) == right }
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

enum class ResolveError { NotFound, Ambiguous }
