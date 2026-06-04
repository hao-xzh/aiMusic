package app.pipo.nativeapp.data.agent.intent

import app.pipo.nativeapp.data.PetIntent
import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals

data class MusicIntent(
    val queryText: String = "",
    val primaryArtists: List<String> = emptyList(),
    val artistScope: ArtistScope = ArtistScope.Focus,
    val artistDistribution: ArtistDistribution = ArtistDistribution.PrimaryDominant,
    val primaryTracks: List<TrackRequirement> = emptyList(),
    val mustIncludeTracks: List<TrackRequirement> = emptyList(),
    val excludeTerms: List<String> = emptyList(),
    val softMoods: List<String> = emptyList(),
    val softScenes: List<String> = emptyList(),
    val softTextures: List<String> = emptyList(),
    val softEnergy: String = "any",
    val softTempoFeel: String = "any",
    val refStyles: List<String> = emptyList(),
    val refArtists: List<String> = emptyList(),
    val aiMainStyles: List<String> = emptyList(),
    val aiAdjacentStyles: List<String> = emptyList(),
    val aiAvoidStyles: List<String> = emptyList(),
    val searchQueries: List<String> = emptyList(),
    val emotionalDirection: String? = null,
    val desiredCount: Int = 12,
    val source: IntentSource = IntentSource.UserCommand,
) {
    fun stableHash(): String {
        val body = listOf(
            queryText,
            primaryArtists.joinToString("|"),
            artistScope.name,
            artistDistribution.name,
            primaryTracks.joinToString("|") { "${it.title}:${it.artist.orEmpty()}:${it.placement.name}:${it.index ?: -1}" },
            mustIncludeTracks.joinToString("|") { "${it.title}:${it.artist.orEmpty()}:${it.placement.name}:${it.index ?: -1}" },
            excludeTerms.joinToString("|"),
            softMoods.joinToString("|"),
            softScenes.joinToString("|"),
            softTextures.joinToString("|"),
            softEnergy,
            softTempoFeel,
            refStyles.joinToString("|"),
            refArtists.joinToString("|"),
            aiMainStyles.joinToString("|"),
            aiAdjacentStyles.joinToString("|"),
            aiAvoidStyles.joinToString("|"),
            searchQueries.joinToString("|"),
            emotionalDirection.orEmpty(),
            desiredCount.toString(),
            source.name,
        ).joinToString("\u001f")
        return body.hashCode().toUInt().toString(16)
    }

    fun toPetIntent(): PetIntent {
        val allTracks = primaryTracks + mustIncludeTracks
        val genres = (aiMainStyles + refStyles).distinct()
        val languages = CommandTextSignals.languageIncludes(queryText, excludeTerms)
        return PetIntent(
            queryText = queryText,
            hardArtists = primaryArtists,
            artistDistribution = artistDistribution.name,
            hardTracks = allTracks.map { it.title }.distinct(),
            hardGenres = genres,
            hardLanguages = languages,
            textArtists = (primaryArtists + refArtists).distinct(),
            textTracks = allTracks.map { it.title }.distinct(),
            excludeArtists = excludeTerms.filterNot(::looksLikeLanguage),
            excludeLanguages = CommandTextSignals.languageExcludes(queryText),
            excludeTags = excludeTerms.filterNot(::looksLikeLanguage),
            avoidWords = excludeTerms + aiAvoidStyles,
            softMoods = softMoods,
            softScenes = softScenes,
            softTextures = softTextures,
            softEnergy = softEnergy,
            softTempoFeel = softTempoFeel,
            musicHintsMoods = softMoods,
            musicHintsScenes = softScenes,
            musicHintsGenres = genres,
            musicHintsEnergy = softEnergy,
            refStyles = refStyles,
            refArtists = refArtists,
            aiMainStyles = aiMainStyles,
            aiAdjacentStyles = (aiAdjacentStyles + searchQueries).distinct(),
            aiAvoidStyles = aiAvoidStyles,
            emotionalDirection = emotionalDirection,
            desiredCount = desiredCount,
        )
    }

    private fun looksLikeLanguage(value: String): Boolean {
        val key = value.lowercase()
        return listOf("韩语", "国语", "粤语", "英文", "日语", "中文", "korean", "mandarin", "cantonese", "english", "japanese")
            .any { it in key }
    }
}

enum class IntentSource {
    UserCommand,
    CurrentTrackStyle,
    CurrentQueueStyle,
    ManualPlaylist,
    DefaultAiRadio,
    Recovery,
}

enum class ArtistDistribution {
    BalancedInterleave,
    PrimaryThenSecondary,
    RandomMix,
    PrimaryDominant,
}
