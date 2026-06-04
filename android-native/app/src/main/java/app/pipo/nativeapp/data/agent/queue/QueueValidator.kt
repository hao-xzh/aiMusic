package app.pipo.nativeapp.data.agent.queue

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.QueueValidation
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals

class QueueValidator(
    private val constraintScorer: ConstraintScorer = ConstraintScorer(),
) {
    fun validate(userText: String, actions: List<PlannedAction>): QueueValidation {
        val play = actions.filterIsInstance<PlannedAction.PlayTracks>().firstOrNull()
        val tracks = play?.tracks.orEmpty()
        val includeTitle = CommandTextSignals.includedTrackTitle(userText)
        val closerTitle = CommandTextSignals.closerTrackTitle(userText)
        val requiredArtists = play?.primaryGoal?.primaryArtists
            ?.takeIf { it.isNotEmpty() }
            ?: CommandTextSignals.primaryArtistHints(userText)
        val artistScope = play?.primaryGoal?.artistScope ?: CommandTextSignals.artistScope(userText)
        val excludeTerms = CommandTextSignals.excludeTerms(userText)
        val excludedLanguages = CommandTextSignals.languageExcludes(userText)
        val includedLanguages = CommandTextSignals.languageIncludes(userText, excludeTerms)
        val energyHint = CommandTextSignals.energyHint(userText)
        val messages = mutableListOf<String>()
        var primarySatisfied = true
        var mustIncludeSatisfied = true
        var closerSatisfied = true

        validateDirectTarget(userText, play, messages)

        val primaryTracks = play?.primaryGoal?.primaryTracks.orEmpty()
        if (primaryTracks.isNotEmpty() && play != null) {
            val missing = primaryTracks.filter { requirement ->
                tracks.none { titleMatches(it, requirement.title) }
            }
            if (missing.isNotEmpty()) {
                messages.add("primary_tracks_missed:" + missing.take(3).joinToString("|") { it.title })
                primarySatisfied = false
            }
        }

        if (requiredArtists.isNotEmpty() && play?.mode == PlayMode.ReplaceQueue) {
            val exceptionTitles = (
                play.primaryGoal.mustInclude + listOfNotNull(play.primaryGoal.closer)
            ).map { CommandTextSignals.normalizeForMatch(it.title) }.toSet()
            primarySatisfied = validateArtistScope(
                tracks = tracks,
                requiredArtists = requiredArtists,
                artistScope = artistScope,
                exceptionTitles = exceptionTitles,
                messages = messages,
            )
        }
        if (!includeTitle.isNullOrBlank() && play != null) {
            mustIncludeSatisfied = tracks.any { titleMatches(it, includeTitle) }
            if (!mustIncludeSatisfied) messages.add("must_include_missed")
            if (mustIncludeSatisfied && play.mode == PlayMode.ReplaceQueue && tracks.firstOrNull()?.let { titleMatches(it, includeTitle) } == true) {
                messages.add("must_include_at_head")
                mustIncludeSatisfied = false
            }
        }
        if (!closerTitle.isNullOrBlank() && play?.mode == PlayMode.ReplaceQueue && tracks.isNotEmpty()) {
            closerSatisfied = tracks.takeLast(2).any { titleMatches(it, closerTitle) }
            if (!closerSatisfied) messages.add("closer_missed")
        }
        if (excludeTerms.isNotEmpty() && tracks.any { constraintScorer.hitsAvoidTerm(it, excludeTerms) }) {
            messages.add("exclude_term_hit")
        }
        if (excludedLanguages.isNotEmpty() && tracks.any { trackLanguage(it) in excludedLanguages }) {
            messages.add("exclude_language_hit")
        }
        if (energyHint == "low" && openingTooHighEnergy(tracks.firstOrNull())) {
            messages.add("opening_energy_too_high")
        }
        if (includedLanguages.size >= 2 && CommandTextSignals.normalizeCommandText(userText).contains("穿插") &&
            languageAlternationBroken(tracks, includedLanguages)
        ) {
            messages.add("language_interleave_weak")
        }
        return QueueValidation(
            passed = primarySatisfied && mustIncludeSatisfied && closerSatisfied &&
                "direct_target_missed" !in messages &&
                "insert_target_missed" !in messages &&
                "exclude_term_hit" !in messages &&
                "exclude_language_hit" !in messages &&
                "opening_energy_too_high" !in messages &&
                "language_interleave_weak" !in messages,
            messages = messages,
            primarySatisfied = primarySatisfied,
            mustIncludeSatisfied = mustIncludeSatisfied,
            closerSatisfied = closerSatisfied,
        )
    }

    private fun validateDirectTarget(
        userText: String,
        play: PlannedAction.PlayTracks?,
        messages: MutableList<String>,
    ) {
        if (play == null) return
        val target: TrackRequirement? = when (play.mode) {
            PlayMode.PlayNow -> CommandTextSignals.artistTrackTarget(userText) ?: CommandTextSignals.connectiveLeadTrack(userText)
            PlayMode.InsertNext -> CommandTextSignals.insertNextTrack(userText)
            PlayMode.ReplaceQueue,
            PlayMode.PreserveCurrentThenReplace -> null
        }
        val first = play.tracks.firstOrNull()
        if (target != null && first?.let { titleMatches(it, target.title) } != true) {
            messages.add(if (play.mode == PlayMode.InsertNext) "insert_target_missed" else "direct_target_missed")
        }
    }

    private fun titleMatches(track: NativeTrack, title: String): Boolean {
        val left = CommandTextSignals.normalizeForMatch(track.title)
        val right = CommandTextSignals.normalizeForMatch(title)
        return right.isNotBlank() && (left == right || left.contains(right) || right.contains(left))
    }

    private fun validateArtistScope(
        tracks: List<NativeTrack>,
        requiredArtists: List<String>,
        artistScope: ArtistScope,
        exceptionTitles: Set<String>,
        messages: MutableList<String>,
    ): Boolean {
        if (requiredArtists.isEmpty() || tracks.isEmpty()) return true
        val artistKeys = requiredArtists
            .map(CommandTextSignals::normalizeForMatch)
            .filter { it.isNotBlank() }
        val scopedTracks = tracks.filterNot { track ->
            val title = CommandTextSignals.normalizeForMatch(track.title)
            exceptionTitles.any { it.isNotBlank() && (title == it || title.contains(it) || it.contains(title)) }
        }
        return when (artistScope) {
            ArtistScope.Strict -> {
                val illegal = scopedTracks.filterNot { artistMatchesAny(it.artist, artistKeys) }
                if (illegal.isNotEmpty()) {
                    messages.add(
                        "strict_artist_scope_violated:" +
                            illegal.take(3).joinToString("|") { "${it.title}-${it.artist}" },
                    )
                    false
                } else {
                    true
                }
            }
            ArtistScope.Focus -> {
                val hit = scopedTracks.count { artistMatchesAny(it.artist, artistKeys) }
                val ratio = hit.toDouble() / scopedTracks.size.coerceAtLeast(1).toDouble()
                if (ratio < 0.7) {
                    messages.add("focus_artist_ratio_low:$hit/${scopedTracks.size}")
                    false
                } else {
                    true
                }
            }
            ArtistScope.Similar -> true
        }
    }

    private fun artistMatchesAny(actualRaw: String, artistKeys: List<String>): Boolean {
        return actualRaw.split("/", "&", ",", "、")
            .map { CommandTextSignals.normalizeForMatch(it) }
            .filter { it.isNotBlank() }
            .any { actual ->
                artistKeys.any { expected ->
                    actual == expected || actual.contains(expected) || expected.contains(actual)
                }
            }
    }

    private fun trackLanguage(track: NativeTrack): String? {
        val profile = PipoGraph.trackSemanticStore.get(track.id)
            ?: PipoGraph.semanticIndexer.buildRuleBasedProfile(track, PipoGraph.audioFeaturesStore.get(track.id))
        return profile.language.key.takeIf { it != "unknown" }
    }

    private fun openingTooHighEnergy(track: NativeTrack?): Boolean {
        if (track == null) return false
        val features = PipoGraph.audioFeaturesStore.get(track.id) ?: return false
        val eMid = (features.introEnergy + features.outroEnergy) / 2.0
        return eMid > 0.62
    }

    private fun languageAlternationBroken(tracks: List<NativeTrack>, requiredLanguages: List<String>): Boolean {
        val known = tracks.take(8).mapNotNull(::trackLanguage).filter { it in requiredLanguages }
        if (known.size < 4) return false
        var run = 1
        for (i in 1 until known.size) {
            run = if (known[i] == known[i - 1]) run + 1 else 1
            if (run >= 3) return true
        }
        return false
    }
}
