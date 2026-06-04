package app.pipo.nativeapp.data.agent.context

import app.pipo.nativeapp.data.AudioFeatures
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.TrackSemanticProfile

data class StyleCapsule(
    val capsuleId: String,
    val source: StyleSource,
    val trackId: String? = null,
    val title: String = "",
    val artist: String = "",
    val genres: List<String> = emptyList(),
    val moods: List<String> = emptyList(),
    val scenes: List<String> = emptyList(),
    val textures: List<String> = emptyList(),
    val energy: String = "any",
    val tempoFeel: String = "any",
    val summary: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    fun asSearchTerms(): List<String> =
        (genres + moods + scenes + textures + listOf(energy, tempoFeel, summary))
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "any" }
            .distinct()
}

enum class StyleSource {
    CurrentTrack,
    CurrentQueue,
    PlannerExplanation,
    UserPreference,
}

object StyleCapsuleBuilder {
    fun fromTrack(track: NativeTrack?): StyleCapsule? {
        if (track == null) return null
        val profile = runCatching {
            PipoGraph.trackSemanticStore.get(track.id)
                ?: PipoGraph.semanticIndexer.buildRuleBasedProfile(track, PipoGraph.audioFeaturesStore.get(track.id))
        }.getOrNull()
        val features = runCatching { PipoGraph.audioFeaturesStore.get(track.id) }.getOrNull()
        return fromTrack(track, profile, features)
    }

    fun fromQueue(queue: List<NativeTrack>): StyleCapsule? {
        val tracks = queue.take(8)
        if (tracks.isEmpty()) return null
        val capsules = tracks.mapNotNull(::fromTrack)
        if (capsules.isEmpty()) return null
        val genres = capsules.flatMap { it.genres }.topTerms()
        val moods = capsules.flatMap { it.moods }.topTerms()
        val scenes = capsules.flatMap { it.scenes }.topTerms()
        val textures = capsules.flatMap { it.textures }.topTerms()
        val energy = capsules.map { it.energy }.filter { it != "any" }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "any"
        return StyleCapsule(
            capsuleId = "queue:${tracks.joinToString("|") { it.id }.hashCode().toUInt().toString(16)}",
            source = StyleSource.CurrentQueue,
            genres = genres,
            moods = moods,
            scenes = scenes,
            textures = textures,
            energy = energy,
            tempoFeel = capsules.map { it.tempoFeel }.filter { it != "any" }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "any",
            summary = (genres + moods + textures).take(6).joinToString("、"),
        )
    }

    private fun fromTrack(
        track: NativeTrack,
        profile: TrackSemanticProfile?,
        features: AudioFeatures?,
    ): StyleCapsule {
        val energy = when {
            features == null -> "any"
            (features.introEnergy + features.outroEnergy) / 2.0 >= 0.68 -> "high"
            (features.introEnergy + features.outroEnergy) / 2.0 <= 0.32 -> "low"
            else -> "medium"
        }
        val tempoFeel = when {
            features?.bpm == null -> "any"
            features.bpm >= 128 -> "fast"
            features.bpm <= 82 -> "slow"
            else -> "medium"
        }
        val summary = profile?.summary?.takeIf { it.isNotBlank() }
            ?: listOf(track.artist, track.title, profile?.genres?.firstOrNull(), profile?.moods?.firstOrNull())
                .filterNotNull()
                .filter { it.isNotBlank() }
                .joinToString(" ")
        return StyleCapsule(
            capsuleId = "track:${track.id}",
            source = StyleSource.CurrentTrack,
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            genres = (profile?.genres.orEmpty() + profile?.subGenres.orEmpty() + profile?.styleAnchors.orEmpty()).distinct().take(6),
            moods = profile?.moods.orEmpty().distinct().take(6),
            scenes = profile?.scenes.orEmpty().distinct().take(4),
            textures = profile?.textures.orEmpty().distinct().take(6),
            energy = energy,
            tempoFeel = tempoFeel,
            summary = summary,
        )
    }

    private fun List<String>.topTerms(limit: Int = 6): List<String> =
        map { it.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(limit)
}
