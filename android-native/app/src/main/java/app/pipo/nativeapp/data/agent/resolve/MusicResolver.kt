package app.pipo.nativeapp.data.agent.resolve

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.BehaviorPreferenceSnapshot
import app.pipo.nativeapp.data.CandidateRanker
import app.pipo.nativeapp.data.CandidateRecall
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PetIntent
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.TrackDedupe
import app.pipo.nativeapp.data.agent.domain.AgentTurnInput
import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.intent.ArtistDistribution
import app.pipo.nativeapp.data.agent.intent.MusicIntent
import app.pipo.nativeapp.data.agent.intent.MusicIntentCompiler
import app.pipo.nativeapp.data.agent.normalize.CatalogLexicon
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import app.pipo.nativeapp.data.agent.session.ContinuationMode
import app.pipo.nativeapp.data.agent.session.ContinuationPolicy
import app.pipo.nativeapp.data.agent.session.SessionMutation
import kotlinx.coroutines.flow.first

class MusicResolver(
    private val repository: PipoRepository,
) {
    private val trackResolver = TrackResolver(repository)
    private val playlistResolver = PlaylistResolver()

    suspend fun resolve(plan: MusicTurnPlan, input: AgentTurnInput): ResolutionResult {
        val localTracks = runCatching { PipoGraph.library.library() }.getOrDefault(emptyList())
        val resolvedActions = plan.actions.map { action ->
            when (action) {
                is PlannedAction.PlayRequest -> resolvePlayRequest(action, plan, input, localTracks)
                is PlannedAction.PlayPlaylist -> resolvePlaylist(action)
                else -> action
            }
        }
        val summary = buildSummary(plan, resolvedActions)
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "resolution",
            fields = mapOf(
                "turnId" to plan.turnId,
                "libraryCount" to localTracks.size,
                "summary" to summary.take(220),
            ),
        )
        val primaryResolved = resolvedActions.filterIsInstance<PlannedAction.PlayTracks>().firstOrNull()
        return ResolutionResult(
            plan = plan.copy(
                actions = resolvedActions,
                sessionMutation = primaryResolved?.sessionMutation ?: plan.sessionMutation,
                continuationPolicy = primaryResolved?.continuationPolicy ?: plan.continuationPolicy,
                activeIntent = primaryResolved?.musicIntent ?: plan.activeIntent,
            ),
            summary = summary,
            missingRequirements = missingRequirements(plan, resolvedActions),
        )
    }

    private suspend fun resolvePlaylist(action: PlannedAction.PlayPlaylist): PlannedAction.PlayPlaylist {
        if (CommandTextSignals.isCloudPlaylistName(action.name)) {
            return action.copy(
                name = "我的网盘",
                tracks = runCatching { repository.cloudDiskTracks() }.getOrDefault(emptyList()),
            )
        }
        val playlists = runCatching { repository.playlists.first() }.getOrDefault(emptyList())
        val resolved = playlistResolver.resolve(action.name, playlists)
        val tracks = resolved?.let {
            runCatching { repository.tracksForPlaylist(it.playlist.id) }.getOrDefault(emptyList())
        }.orEmpty()
        return action.copy(
            name = resolved?.playlist?.name ?: action.name,
            tracks = tracks,
        )
    }

    private suspend fun resolvePlayRequest(
        action: PlannedAction.PlayRequest,
        plan: MusicTurnPlan,
        input: AgentTurnInput,
        localTracks: List<NativeTrack>,
    ): PlannedAction.PlayTracks {
        val scopedTracks = scopedTracksFor(action.primaryGoal.playlistName, localTracks)
        val allowOnline = action.primaryGoal.playlistName.isBlank()
        val tracks = when (action.mode) {
            PlayMode.PlayNow -> {
                val target = action.target
                val head = target?.let { trackResolver.resolve(it, scopedTracks, allowOnline = allowOnline).track }
                if (head == null) emptyList()
                else mergeUnique(
                    listOf(head),
                    rankForIntent(
                        petIntentFor(action, plan, input, scopedTracks),
                        input,
                        scopedTracks,
                        action.desiredCount.coerceAtLeast(8),
                        action.primaryGoal.artistScope,
                        allowOnline = allowOnline,
                    ),
                )
            }
            PlayMode.InsertNext -> {
                action.target?.let { trackResolver.resolve(it, scopedTracks, allowOnline = allowOnline).track }?.let(::listOf).orEmpty()
            }
            PlayMode.ReplaceQueue,
            PlayMode.PreserveCurrentThenReplace -> {
                val explicitCount = CommandTextSignals.explicitDesiredCount(plan.userText)
                val minDesired = if (action.primaryGoal.primaryTracks.isNotEmpty() || explicitCount != null) 1 else 6
                val desired = action.desiredCount.coerceIn(minDesired, 60)
                val intent = petIntentFor(action, plan, input, scopedTracks)
                val artistScope = action.primaryGoal.artistScope
                val ranked = rankForIntent(intent, input, scopedTracks, desired, artistScope, allowOnline = allowOnline)
                val primaryResolved = action.primaryGoal.primaryTracks
                    .mapNotNull { requirement ->
                        trackResolver.resolve(requirement, scopedTracks, allowOnline = allowOnline).track?.let {
                            ResolvedRequirement(requirement, it)
                        }
                    }
                val required = (action.primaryGoal.mustInclude + listOfNotNull(action.primaryGoal.closer))
                    .mapNotNull { requirement ->
                        trackResolver.resolve(requirement, scopedTracks, allowOnline = allowOnline).track?.let {
                            ResolvedRequirement(requirement, it)
                        }
                    }
                val base = if (ranked.isNotEmpty()) {
                    ranked
                } else if (allowOnline) {
                    onlineBackfill(intent, desired, artistScope)
                } else {
                    emptyList()
                }
                val seeded = if (primaryResolved.isNotEmpty()) injectPrimaryTracks(base, primaryResolved, desired) else base
                val merged = injectRequired(seeded, required, desired)
                val finalTracks = if (merged.isNotEmpty()) {
                    merged
                } else if (allowOnline) {
                    onlineBackfill(intent, desired, artistScope)
                } else {
                    emptyList()
                }
                if (action.mode == PlayMode.PreserveCurrentThenReplace && input.currentTrack != null) {
                    mergeUnique(listOf(input.currentTrack), finalTracks).take(desired.coerceAtLeast(2))
                } else {
                    finalTracks
                }
            }
        }
        val musicIntent = musicIntentFor(action, plan, input, scopedTracks)
        val continuationPolicy = continuationPolicyFor(action, plan, input)
        val sessionMutation = sessionMutationFor(action, plan, input, continuationPolicy)
        return PlannedAction.PlayTracks(
            actionId = action.actionId,
            mode = action.mode,
            tracks = tracks,
            continuous = if (
                action.mode in setOf(PlayMode.ReplaceQueue, PlayMode.PreserveCurrentThenReplace) &&
                continuationPolicy.enabled
            ) {
                continuousSourceFor(musicIntent, action, plan, scopedTracks)
            } else {
                null
            },
            primaryGoal = action.primaryGoal,
            target = action.target,
            similar = action.similar,
            jumpToInserted = action.jumpToInserted,
            musicIntent = musicIntent,
            continuationPolicy = continuationPolicy,
            sessionMutation = sessionMutation,
            styleCapsule = input.resolvedStyleReference
                ?: input.currentTrackStyle.takeIf { CommandTextSignals.currentStyleRequest(plan.userText) }
                ?: input.currentQueueStyle.takeIf { CommandTextSignals.currentStyleRequest(plan.userText) },
        )
    }

    private suspend fun scopedTracksFor(playlistName: String, fallbackLocalTracks: List<NativeTrack>): List<NativeTrack> {
        if (playlistName.isBlank()) return fallbackLocalTracks
        if (CommandTextSignals.isCloudPlaylistName(playlistName)) {
            return runCatching { repository.cloudDiskTracks() }.getOrDefault(emptyList())
        }
        val playlists = runCatching { repository.playlists.first() }.getOrDefault(emptyList())
        val resolved = playlistResolver.resolve(playlistName, playlists) ?: return emptyList()
        return runCatching { repository.tracksForPlaylist(resolved.playlist.id) }.getOrDefault(emptyList())
    }

    private fun petIntentFor(
        action: PlannedAction.PlayRequest,
        plan: MusicTurnPlan,
        input: AgentTurnInput,
        localTracks: List<NativeTrack>,
    ): PetIntent {
        return musicIntentFor(action, plan, input, localTracks).toPetIntent()
    }

    private fun musicIntentFor(
        action: PlannedAction.PlayRequest,
        plan: MusicTurnPlan,
        input: AgentTurnInput,
        localTracks: List<NativeTrack>,
    ): MusicIntent {
        val compiled = MusicIntentCompiler.fromAction(action, plan.userText, input)
        val target = action.target
        val primaryTracks = action.primaryGoal.primaryTracks
        val mustInclude = action.primaryGoal.mustInclude
        val closer = action.primaryGoal.closer
        val lexicon = CatalogLexicon(localTracks)
        val trackMentions = lexicon.findTrackMentions(plan.userText).take(4)
        val artistMentions = lexicon.findArtistMentions(plan.userText).take(4)
        val trackHints = mergeTextHints(
            (listOfNotNull(target) + primaryTracks + mustInclude + listOfNotNull(closer)).map { it.title },
            trackMentions.map { it.title },
        )
        val rawPrimaryArtists = action.primaryGoal.primaryArtists
        val rawArtistHints = mergeTextHints(
            rawPrimaryArtists + listOfNotNull(target?.artist) +
                primaryTracks.mapNotNull { it.artist } + mustInclude.mapNotNull { it.artist } + listOfNotNull(closer?.artist),
            artistMentions.map { it.name } + trackMentions.map { it.artist },
        )
        val artistResolver = ArtistResolver(localTracks)
        val primaryArtists = canonicalArtists(rawPrimaryArtists, artistResolver)
        val artistHints = canonicalArtists(rawArtistHints, artistResolver)
        val excludeTerms = action.primaryGoal.excludeTerms
        val genres = genreHints(plan.userText)
        val languages = CommandTextSignals.languageIncludes(plan.userText, excludeTerms)
        val energy = CommandTextSignals.energyHint(plan.userText)
        return compiled.copy(
            primaryArtists = if (action.mode in setOf(PlayMode.ReplaceQueue, PlayMode.PreserveCurrentThenReplace)) {
                primaryArtists.ifEmpty { compiled.primaryArtists }
            } else {
                artistHints.ifEmpty { compiled.primaryArtists }
            },
            artistDistribution = if (primaryArtists.size > 1 || artistHints.size > 1) {
                ArtistDistribution.BalancedInterleave
            } else {
                compiled.artistDistribution
            },
            primaryTracks = if (action.mode == PlayMode.PlayNow || action.mode == PlayMode.InsertNext) {
                trackHints.map { TrackRequirement(title = it) }.ifEmpty { compiled.primaryTracks }
            } else {
                primaryTracks.ifEmpty { compiled.primaryTracks }
            },
            mustIncludeTracks = (mustInclude + listOfNotNull(closer)).ifEmpty { compiled.mustIncludeTracks },
            excludeTerms = (excludeTerms + compiled.excludeTerms).distinct(),
            softEnergy = energy.takeIf { it != "any" } ?: compiled.softEnergy,
            aiMainStyles = (genres + compiled.aiMainStyles).distinct(),
            desiredCount = action.desiredCount,
        )
    }

    private suspend fun rankForIntent(
        intent: PetIntent,
        input: AgentTurnInput,
        localTracks: List<NativeTrack>,
        desired: Int,
        artistScope: ArtistScope,
        allowOnline: Boolean = true,
    ): List<NativeTrack> {
        if (localTracks.isEmpty()) return if (allowOnline) onlineBackfill(intent, desired, artistScope) else emptyList()
        val behaviorEvents = runCatching { PipoGraph.behaviorLog.readAll() }.getOrDefault(emptyList())
        val behaviorPreference = runCatching { PipoGraph.behaviorPreference.current() }
            .getOrDefault(BehaviorPreferenceSnapshot.Empty)
        val queryVector = runCatching {
            val query = buildEmbeddingQuery(intent)
            if (query.isBlank()) null else PipoGraph.embeddingIndexer.embedQuery(query)
        }.getOrNull()
        val candidates = CandidateRecall.recall(
            intent = intent,
            library = localTracks,
            featuresStore = PipoGraph.audioFeaturesStore,
            semanticStore = PipoGraph.trackSemanticStore,
            indexer = PipoGraph.semanticIndexer,
            tasteProfile = PipoGraph.tasteProfileStore.current(),
            behaviorEvents = behaviorEvents,
            behaviorPreference = behaviorPreference,
            currentTrack = input.currentTrack,
            limit = 220,
            queryVector = queryVector,
            embeddingStore = runCatching { PipoGraph.embeddingStore }.getOrNull(),
        )
        val ranked = CandidateRanker.rank(
            candidates = candidates,
            intent = intent,
            options = CandidateRanker.Options(
                topN = desired * 3,
                recentPlay = runCatching { PipoGraph.behaviorLog.recentPlay() }.getOrNull(),
                recentRecommendation = runCatching { PipoGraph.recommendationLog.recentContext() }.getOrNull(),
                behaviorPreference = behaviorPreference,
            ),
        ).map { it.candidate.track }
        val artistKeys = intent.hardArtists
            .map(CommandTextSignals::normalizeForMatch)
            .filter { it.isNotBlank() }
        val scoped = applyArtistScope(
            tracks = ranked,
            artistKeys = artistKeys,
            artistScope = artistScope,
        )
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "recall_scope_filter",
            fields = mapOf(
                "artistScope" to artistScope.name,
                "requiredArtists" to intent.hardArtists.joinToString(","),
                "rankedBefore" to ranked.size,
                "scopedAfter" to scoped.size,
                "queryVector" to (queryVector != null),
            ),
        )
        val distributed = if (intent.artistDistribution == ArtistDistribution.BalancedInterleave.name && artistKeys.size > 1) {
            balanceInterleaveByArtists(scoped, artistKeys)
        } else {
            scoped
        }
        val localResult = when (artistScope) {
            ArtistScope.Strict -> TrackDedupe.capSameTitle(distributed).take(desired)
            ArtistScope.Focus -> TrackDedupe.capSameTitle(
                diversifyByArtistButPreserveFocus(distributed, artistKeys),
            ).take(desired)
            ArtistScope.Similar -> TrackDedupe.capSameTitle(diversifyByArtist(distributed)).take(desired)
        }
        return if (localResult.isNotEmpty()) {
            localResult
        } else if (allowOnline) {
            onlineBackfill(intent, desired, artistScope)
        } else {
            emptyList()
        }
    }

    private suspend fun onlineBackfill(
        intent: PetIntent,
        desired: Int,
        artistScope: ArtistScope,
    ): List<NativeTrack> {
        val queries = buildSearchQueries(intent)
        val artistKeys = intent.hardArtists
            .map(CommandTextSignals::normalizeForMatch)
            .filter { it.isNotBlank() }
        val out = ArrayList<NativeTrack>()
        val seen = HashSet<String>()
        for (query in queries) {
            if (out.size >= desired) break
            val hits = runCatching { repository.searchTracks(query, limit = 20) }.getOrDefault(emptyList())
            val scopedHits = when {
                artistScope == ArtistScope.Strict && artistKeys.isNotEmpty() ->
                    hits.filter { artistMatchesAny(it.artist, artistKeys) }
                artistScope == ArtistScope.Focus && artistKeys.isNotEmpty() ->
                    hits.sortedByDescending { if (artistMatchesAny(it.artist, artistKeys)) 1 else 0 }
                else -> hits
            }
            for (track in scopedHits) {
                if (seen.add(TrackDedupe.songKey(track))) out.add(track)
                if (out.size >= desired) break
            }
        }
        return out
    }

    private fun continuousSourceFor(
        musicIntent: MusicIntent,
        action: PlannedAction.PlayRequest,
        plan: MusicTurnPlan,
        localTracks: List<NativeTrack>,
    ): ContinuousQueueSource {
        val intent = musicIntent.toPetIntent()
        val artistKeys = intent.hardArtists
            .map(CommandTextSignals::normalizeForMatch)
            .filter { it.isNotBlank() }
        val artistScope = action.primaryGoal.artistScope
        if (action.primaryGoal.playlistName.isNotBlank()) {
            return ContinuousQueueSource { excludeIds ->
                val scoped = applyArtistScope(
                    tracks = localTracks,
                    artistKeys = artistKeys,
                    artistScope = artistScope,
                )
                TrackDedupe.capSameTitle(scoped)
                    .filter { track -> track.neteaseId?.let { it !in excludeIds } ?: true }
                    .take(12)
            }
        }
        val seedQueries = buildSearchQueries(intent).ifEmpty { listOf(plan.userText) }
        return ContinuousQueueSource { excludeIds ->
            val out = ArrayList<NativeTrack>()
            val seen = HashSet<String>()
            for (query in seedQueries) {
                if (out.size >= 12) break
                val hits = runCatching { repository.searchTracks(query, limit = 10) }.getOrDefault(emptyList())
                val scopedHits = when {
                    artistScope == ArtistScope.Strict && artistKeys.isNotEmpty() ->
                        hits.filter { artistMatchesAny(it.artist, artistKeys) }
                    artistScope == ArtistScope.Focus && artistKeys.isNotEmpty() ->
                        hits.sortedByDescending { if (artistMatchesAny(it.artist, artistKeys)) 1 else 0 }
                    else -> hits
                }
                for (track in scopedHits) {
                    val id = track.neteaseId
                    if (id != null && id in excludeIds) continue
                    if (seen.add(TrackDedupe.songKey(track))) out.add(track)
                    if (out.size >= 12) break
                }
            }
            out
        }
    }

    private fun continuationPolicyFor(
        action: PlannedAction.PlayRequest,
        plan: MusicTurnPlan,
        input: AgentTurnInput,
    ): ContinuationPolicy {
        if (CommandTextSignals.disableContinuation(plan.userText)) {
            return ContinuationPolicy(enabled = false, mode = ContinuationMode.Off)
        }
        val enabled = action.continuationPolicy?.enabled
            ?: (input.aiAutoContinueEnabled || CommandTextSignals.enableContinuation(plan.userText) || CommandTextSignals.wantsMoreFromStyle(plan.userText))
        val mode = when {
            !enabled -> ContinuationMode.Off
            CommandTextSignals.currentStyleRequest(plan.userText) -> ContinuationMode.CurrentTrackStyle
            action.primaryGoal.playlistName.isNotBlank() -> ContinuationMode.ManualPlaylistStyle
            else -> ContinuationMode.SameIntent
        }
        return action.continuationPolicy ?: ContinuationPolicy(
            enabled = enabled,
            mode = mode,
            desiredBatchSize = 8,
        )
    }

    private fun sessionMutationFor(
        action: PlannedAction.PlayRequest,
        plan: MusicTurnPlan,
        input: AgentTurnInput,
        policy: ContinuationPolicy,
    ): SessionMutation {
        if (action.mode == PlayMode.InsertNext) return SessionMutation.KeepCurrentSession
        if (CommandTextSignals.disableContinuation(plan.userText)) return SessionMutation.PauseCurrentSession
        if (action.sessionMutation != SessionMutation.None) return action.sessionMutation
        if (action.mode == PlayMode.PreserveCurrentThenReplace || CommandTextSignals.wantsMoreFromStyle(plan.userText)) {
            return if (input.activeSession?.isActive() == true) SessionMutation.UpdateCurrentSession else SessionMutation.CreateNewSession
        }
        if (action.mode == PlayMode.ReplaceQueue || action.mode == PlayMode.PlayNow) return SessionMutation.CreateNewSession
        return if (policy.enabled) SessionMutation.UpdateCurrentSession else SessionMutation.None
    }

    private fun buildEmbeddingQuery(intent: PetIntent): String =
        (listOf(intent.queryText) + intent.refStyles + intent.aiMainStyles + intent.aiAdjacentStyles +
            intent.softMoods + intent.softScenes + intent.softTextures)
            .filter { it.isNotBlank() && it != "any" }
            .distinct()
            .take(12)
            .joinToString(" ")

    private fun buildSearchQueries(intent: PetIntent): List<String> {
        val out = mutableListOf<String>()
        for (track in intent.textTracks + intent.hardTracks) {
            val artist = (intent.textArtists + intent.hardArtists).firstOrNull().orEmpty()
            out.add(listOf(artist, track).filter { it.isNotBlank() }.joinToString(" "))
        }
        out.addAll(intent.hardArtists)
        out.addAll(intent.textArtists)
        out.addAll(intent.hardGenres)
        out.addAll(intent.musicHintsGenres)
        out.addAll(intent.aiAdjacentStyles)
        if (out.isEmpty() && intent.queryText.isNotBlank()) out.add(intent.queryText)
        return out.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(8)
    }

    private fun mergeUnique(first: List<NativeTrack>, second: List<NativeTrack>): List<NativeTrack> {
        val out = ArrayList<NativeTrack>(first.size + second.size)
        val seen = HashSet<String>()
        for (track in first + second) {
            if (seen.add(TrackDedupe.songKey(track))) out.add(track)
        }
        return out
    }

    private fun injectRequired(
        base: List<NativeTrack>,
        required: List<ResolvedRequirement>,
        desired: Int,
    ): List<NativeTrack> {
        if (required.isEmpty()) return base.take(desired)
        val requiredKeys = required.mapTo(HashSet()) { TrackDedupe.songKey(it.track) }
        val room = (desired - requiredKeys.size).coerceAtLeast(0)
        val out = base.filter { TrackDedupe.songKey(it) !in requiredKeys }
            .take(room)
            .toMutableList()
        for (item in required.filterNot {
            it.requirement.placement == app.pipo.nativeapp.data.agent.domain.TrackPlacement.Closer ||
                it.requirement.placement == app.pipo.nativeapp.data.agent.domain.TrackPlacement.End
        }) {
            val placement = item.requirement.placement
            val insertion = when (placement) {
                app.pipo.nativeapp.data.agent.domain.TrackPlacement.Next,
                app.pipo.nativeapp.data.agent.domain.TrackPlacement.AfterCurrent -> 1.coerceAtMost(out.size)
                app.pipo.nativeapp.data.agent.domain.TrackPlacement.Middle -> (out.size / 2).coerceIn(0, out.size)
                app.pipo.nativeapp.data.agent.domain.TrackPlacement.AtIndex -> (item.requirement.index ?: 0).coerceIn(0, out.size)
                app.pipo.nativeapp.data.agent.domain.TrackPlacement.Now -> 0
                else -> when {
                    out.isEmpty() -> 0
                    out.size >= 3 -> 3
                    else -> out.size
                }
            }
            out.add(insertion.coerceIn(0, out.size), item.track)
        }
        for (item in required.filter {
            it.requirement.placement == app.pipo.nativeapp.data.agent.domain.TrackPlacement.Closer ||
                it.requirement.placement == app.pipo.nativeapp.data.agent.domain.TrackPlacement.End
        }) {
            out.removeAll { TrackDedupe.songKey(it) == TrackDedupe.songKey(item.track) }
            out.add(item.track)
        }
        return out.take(desired.coerceAtLeast(required.size))
    }

    private fun injectPrimaryTracks(
        base: List<NativeTrack>,
        primary: List<ResolvedRequirement>,
        desired: Int,
    ): List<NativeTrack> {
        if (primary.isEmpty()) return base.take(desired)
        val out = ArrayList<NativeTrack>(desired)
        val seen = HashSet<String>()
        for (item in primary) {
            if (seen.add(TrackDedupe.songKey(item.track))) out.add(item.track)
            if (out.size >= desired) return out
        }
        for (track in base) {
            if (seen.add(TrackDedupe.songKey(track))) out.add(track)
            if (out.size >= desired) break
        }
        return out
    }

    private fun buildSummary(plan: MusicTurnPlan, actions: List<PlannedAction>): String {
        val play = actions.filterIsInstance<PlannedAction.PlayTracks>()
        return buildString {
            append("actions=").append(actions.size)
            append(";playActions=").append(play.size)
            append(";tracks=").append(play.sumOf { it.tracks.size })
            val includeTitle = CommandTextSignals.includedTrackTitle(plan.userText)
            val closerTitle = CommandTextSignals.closerTrackTitle(plan.userText)
            if (!includeTitle.isNullOrBlank()) append(";mustInclude=").append(includeTitle)
            if (!closerTitle.isNullOrBlank()) append(";closer=").append(closerTitle)
        }
    }

    private fun missingRequirements(plan: MusicTurnPlan, actions: List<PlannedAction>): List<String> {
        val tracks = actions.filterIsInstance<PlannedAction.PlayTracks>().flatMap { it.tracks }
        val missing = mutableListOf<String>()
        val includeTitle = CommandTextSignals.includedTrackTitle(plan.userText)
        val closerTitle = CommandTextSignals.closerTrackTitle(plan.userText)
        if (!includeTitle.isNullOrBlank() && tracks.none { titleMatches(it.title, includeTitle) }) {
            missing.add("mustInclude:$includeTitle")
        }
        if (!closerTitle.isNullOrBlank() && tracks.none { titleMatches(it.title, closerTitle) }) {
            missing.add("closer:$closerTitle")
        }
        return missing
    }

    private fun diversifyByArtist(tracks: List<NativeTrack>, cap: Int = 3): List<NativeTrack> {
        val counts = HashMap<String, Int>()
        val primary = ArrayList<NativeTrack>()
        val overflow = ArrayList<NativeTrack>()
        for (track in tracks) {
            val key = CommandTextSignals.normalizeForMatch(track.artist)
            val count = counts[key] ?: 0
            counts[key] = count + 1
            if (count < cap) primary.add(track) else overflow.add(track)
        }
        return primary + overflow
    }

    private fun applyArtistScope(
        tracks: List<NativeTrack>,
        artistKeys: List<String>,
        artistScope: ArtistScope,
    ): List<NativeTrack> {
        if (artistKeys.isEmpty()) return tracks
        return when (artistScope) {
            ArtistScope.Strict -> tracks.filter { track -> artistMatchesAny(track.artist, artistKeys) }
            ArtistScope.Focus -> {
                val primary = tracks.filter { track -> artistMatchesAny(track.artist, artistKeys) }
                val rest = tracks.filterNot { track -> artistMatchesAny(track.artist, artistKeys) }
                primary + rest
            }
            ArtistScope.Similar -> tracks
        }
    }

    private fun diversifyByArtistButPreserveFocus(
        tracks: List<NativeTrack>,
        artistKeys: List<String>,
        minFocusRatio: Double = 0.7,
    ): List<NativeTrack> {
        if (artistKeys.isEmpty()) return diversifyByArtist(tracks)
        val primary = tracks.filter { artistMatchesAny(it.artist, artistKeys) }
        val rest = tracks.filterNot { artistMatchesAny(it.artist, artistKeys) }
        val desiredPrimaryCount = (tracks.size * minFocusRatio).toInt().coerceAtLeast(1)
        val head = primary.take(desiredPrimaryCount)
        val tail = diversifyByArtist(primary.drop(desiredPrimaryCount) + rest)
        return head + tail
    }

    private fun balanceInterleaveByArtists(
        tracks: List<NativeTrack>,
        artistKeys: List<String>,
    ): List<NativeTrack> {
        if (tracks.isEmpty() || artistKeys.size <= 1) return tracks
        val buckets = artistKeys.associateWith { ArrayDeque<NativeTrack>() }.toMutableMap()
        val unmatched = ArrayDeque<NativeTrack>()
        for (track in tracks) {
            val matchedKey = artistKeys.firstOrNull { key -> artistMatchesAny(track.artist, listOf(key)) }
            if (matchedKey == null) {
                unmatched.addLast(track)
            } else {
                buckets.getOrPut(matchedKey) { ArrayDeque() }.addLast(track)
            }
        }
        val out = ArrayList<NativeTrack>(tracks.size)
        val seen = HashSet<String>()
        var advanced: Boolean
        do {
            advanced = false
            for (key in artistKeys) {
                val bucket = buckets[key] ?: continue
                while (bucket.isNotEmpty()) {
                    val next = bucket.removeFirst()
                    if (seen.add(TrackDedupe.songKey(next))) {
                        out.add(next)
                        advanced = true
                        break
                    }
                }
            }
        } while (advanced)
        while (unmatched.isNotEmpty()) {
            val next = unmatched.removeFirst()
            if (seen.add(TrackDedupe.songKey(next))) out.add(next)
        }
        for (track in tracks) {
            if (seen.add(TrackDedupe.songKey(track))) out.add(track)
        }
        return out
    }

    private fun artistMatchesAny(actualRaw: String, artistKeys: List<String>): Boolean =
        actualRaw.split("/", "&", ",", "、")
            .map { CommandTextSignals.normalizeForMatch(it) }
            .filter { it.isNotBlank() }
            .any { actual ->
                artistKeys.any { expected ->
                    actual == expected || actual.contains(expected) || expected.contains(actual)
                }
            }

    private fun titleMatches(leftRaw: String, rightRaw: String): Boolean {
        val left = CommandTextSignals.normalizeForMatch(leftRaw)
        val right = CommandTextSignals.normalizeForMatch(rightRaw)
        return right.isNotBlank() && (left == right || left.contains(right) || right.contains(left))
    }

    private fun looksLikeLanguage(value: String): Boolean =
        listOf("韩语", "国语", "粤语", "英文", "日语", "中文", "korean", "mandarin", "cantonese", "english", "japanese")
            .any { it in value.lowercase() }

    private fun canonicalArtists(raw: List<String>, resolver: ArtistResolver): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (artist in raw) {
            val canonical = resolver.resolve(artist).canonical.trim()
            if (canonical.isNotBlank() && seen.add(CommandTextSignals.normalizeForMatch(canonical))) {
                out.add(canonical)
            }
        }
        return out
    }

    private fun mergeTextHints(first: List<String>, second: List<String>): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (value in first + second) {
            val trimmed = value.trim()
            if (trimmed.isNotBlank() && seen.add(CommandTextSignals.normalizeForMatch(trimmed))) {
                out.add(trimmed)
            }
        }
        return out
    }

    private fun genreHints(text: String): List<String> {
        val lower = text.lowercase()
        val out = mutableListOf<String>()
        if ("r&b" in lower || "rnb" in lower || "节奏布鲁斯" in lower) out.add("r&b")
        if ("摇滚" in text || "rock" in lower) out.add("rock")
        if ("民谣" in text || "folk" in lower) out.add("folk")
        if ("爵士" in text || "jazz" in lower) out.add("jazz")
        if ("电子" in text || "electronic" in lower) out.add("electronic")
        if ("嘻哈" in text || "hiphop" in lower || "hip-hop" in lower || "rap" in lower) out.add("hip-hop")
        return out.distinct()
    }

}

data class ResolutionResult(
    val plan: MusicTurnPlan,
    val summary: String,
    val missingRequirements: List<String>,
)

private data class ResolvedRequirement(
    val requirement: TrackRequirement,
    val track: NativeTrack,
)
