package app.pipo.nativeapp.data.agent.intent

import app.pipo.nativeapp.data.PetIntent
import app.pipo.nativeapp.data.agent.context.StyleCapsule
import app.pipo.nativeapp.data.agent.domain.AgentTurnInput
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals

object MusicIntentCompiler {
    fun fromAction(
        action: PlannedAction.PlayRequest,
        userText: String,
        input: AgentTurnInput? = null,
    ): MusicIntent {
        val style = input?.resolvedStyleReference
            ?: input?.currentTrackStyle?.takeIf { CommandTextSignals.currentStyleRequest(userText) }
            ?: input?.currentQueueStyle?.takeIf { CommandTextSignals.currentStyleRequest(userText) }
        return fromGoal(
            goal = action.primaryGoal,
            target = action.target,
            userText = userText,
            desiredCount = action.desiredCount,
            style = style,
            fallbackIntent = input?.resolvedIntentReference,
        )
    }

    fun fromGoal(
        goal: MusicGoal,
        target: TrackRequirement?,
        userText: String,
        desiredCount: Int,
        style: StyleCapsule? = null,
        fallbackIntent: MusicIntent? = null,
    ): MusicIntent {
        val descriptor = MusicDescriptorLexicon.profileFor(userText)
        val moodTerms = moodTerms(userText, style)
        val sceneTerms = sceneTerms(userText, style)
        val textureTerms = textureTerms(userText, style)
        val refStyles = style?.asSearchTerms().orEmpty()
        val primaryArtists = merge(goal.primaryArtists, fallbackIntent?.primaryArtists.orEmpty())
        val primaryTracks = goal.primaryTracks.ifEmpty {
            listOfNotNull(target).ifEmpty { fallbackIntent?.primaryTracks.orEmpty() }
        }
        val mustInclude = goal.mustInclude + listOfNotNull(goal.closer)
        val exclude = merge(goal.excludeTerms, CommandTextSignals.excludeTerms(userText), fallbackIntent?.excludeTerms.orEmpty())
        val energy = descriptor.energy.takeIf { it != "any" }
            ?: CommandTextSignals.energyHint(userText)
            .takeIf { it != "any" }
            ?: style?.energy
            ?: fallbackIntent?.softEnergy
            ?: "any"
        val tempo = descriptor.tempoFeel.takeIf { it != "any" }
            ?: style?.tempoFeel
            ?: fallbackIntent?.softTempoFeel
            ?: "any"
        val artistDistribution = when {
            primaryArtists.size > 1 -> ArtistDistribution.BalancedInterleave
            else -> fallbackIntent?.artistDistribution ?: ArtistDistribution.PrimaryDominant
        }
        return MusicIntent(
            queryText = userText,
            primaryArtists = primaryArtists,
            artistScope = goal.artistScope,
            artistDistribution = artistDistribution,
            primaryTracks = primaryTracks,
            mustIncludeTracks = mustInclude,
            excludeTerms = exclude,
            softMoods = merge(descriptor.moods, moodTerms),
            softScenes = merge(descriptor.scenes, sceneTerms),
            softTextures = merge(descriptor.textures, textureTerms),
            softEnergy = energy,
            softTempoFeel = tempo,
            refStyles = refStyles,
            refArtists = style?.artist?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
            aiMainStyles = merge(CommandTextSignals.genreHints(userText), descriptor.genres, style?.genres.orEmpty(), fallbackIntent?.aiMainStyles.orEmpty()),
            aiAdjacentStyles = merge(descriptor.descriptors, fallbackIntent?.aiAdjacentStyles.orEmpty()),
            aiAvoidStyles = aiAvoidStyles(userText),
            searchQueries = merge(descriptor.searchQueries, fallbackIntent?.searchQueries.orEmpty()),
            emotionalDirection = descriptor.emotionalDirection ?: emotionalDirection(userText),
            desiredCount = desiredCount,
            source = if (style != null) IntentSource.CurrentTrackStyle else IntentSource.UserCommand,
        )
    }

    fun toPetIntent(musicIntent: MusicIntent): PetIntent = musicIntent.toPetIntent()

    private fun moodTerms(text: String, style: StyleCapsule?): List<String> {
        val out = mutableListOf<String>()
        if (listOf("燃", "嗨", "热血").any { it in text }) out.add("energetic")
        if (listOf("忧郁", "丧", "emo").any { it in text.lowercase() }) out.add("melancholic")
        if (listOf("轻松", "舒服", "治愈").any { it in text }) out.add("relaxed")
        if (listOf("安静", "睡前").any { it in text }) out.add("calm")
        out.addAll(style?.moods.orEmpty())
        return out.distinct().take(8)
    }

    private fun sceneTerms(text: String, style: StyleCapsule?): List<String> {
        val out = mutableListOf<String>()
        if (listOf("开车", "车上").any { it in text }) out.add("driving")
        if (listOf("工作", "专注", "上班").any { it in text }) out.add("focus")
        if (listOf("睡前", "夜里").any { it in text }) out.add("night")
        out.addAll(style?.scenes.orEmpty())
        return out.distinct().take(8)
    }

    private fun textureTerms(text: String, style: StyleCapsule?): List<String> {
        val out = mutableListOf<String>()
        if (listOf("别太吵", "不吵").any { it in text }) out.add("not_noisy")
        if (listOf("顺", "顺滑", "像电台").any { it in text }) out.add("smooth")
        if (listOf("空气感", "氛围").any { it in text }) out.add("atmospheric")
        out.addAll(style?.textures.orEmpty())
        return out.distinct().take(8)
    }

    private fun aiAvoidStyles(text: String): List<String> {
        val out = mutableListOf<String>()
        if (listOf("别太丧", "不要太丧", "不太丧").any { it in text }) out.add("too_depressive")
        if (listOf("别太吵", "不要太吵").any { it in text }) out.add("too_noisy")
        return out
    }

    private fun emotionalDirection(text: String): String? = when {
        listOf("燃一点", "嗨一点", "更燃").any { it in text } -> "brighter"
        listOf("忧郁一点", "更丧", "emo").any { it in text.lowercase() } -> "darker"
        listOf("别太丧", "轻一点").any { it in text } -> "less_depressive"
        else -> null
    }

    private fun merge(vararg values: List<String>): List<String> {
        val seen = HashSet<String>()
        return values.flatMap { it }
            .map { it.trim() }
            .filter { it.isNotBlank() && seen.add(CommandTextSignals.normalizeForMatch(it)) }
    }
}
