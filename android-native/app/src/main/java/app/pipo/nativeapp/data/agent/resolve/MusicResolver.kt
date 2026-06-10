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
import app.pipo.nativeapp.data.agent.normalize.CatalogLexicon
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        return ResolutionResult(
            plan = plan.copy(actions = resolvedActions),
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
                        intentFor(action, plan, scopedTracks),
                        input,
                        scopedTracks,
                        action.desiredCount.coerceAtLeast(8),
                        action.primaryGoal.artistScope,
                        allowOnline = allowOnline,
                    ),
                )
            }
            PlayMode.InsertNext -> {
                action.target?.let { trackResolver.resolve(it, scopedTracks, allowOnline = allowOnline).track }?.let(::listOf)
                    ?: rankForIntent(
                        intentFor(action, plan, scopedTracks),
                        input,
                        scopedTracks,
                        desired = 1,
                        artistScope = action.primaryGoal.artistScope,
                        allowOnline = allowOnline,
                    ).take(1)
            }
            PlayMode.ReplaceQueue -> {
                val explicitCount = CommandTextSignals.explicitDesiredCount(plan.userText)
                val minDesired = if (action.primaryGoal.primaryTracks.isNotEmpty() || explicitCount != null) 1 else 6
                val desired = action.desiredCount.coerceIn(minDesired, 60)
                val intent = intentFor(action, plan, scopedTracks)
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
                val includedArtistTracks = resolveIncludedArtistTracks(
                    includeArtists = action.primaryGoal.includeArtists,
                    ranked = ranked,
                    scopedTracks = scopedTracks,
                    allowOnline = allowOnline,
                )
                val base = if (ranked.isNotEmpty()) {
                    ranked
                } else if (allowOnline) {
                    onlineBackfill(intent, desired, artistScope)
                } else {
                    emptyList()
                }
                val seeded = if (primaryResolved.isNotEmpty()) injectPrimaryTracks(base, primaryResolved, desired) else base
                val withRequiredTracks = injectRequired(seeded, required, desired)
                val merged = injectIncludedArtistTracks(withRequiredTracks, includedArtistTracks, desired)
                if (merged.isNotEmpty()) {
                    merged
                } else if (allowOnline) {
                    onlineBackfill(intent, desired, artistScope)
                } else {
                    emptyList()
                }
            }
        }
        return PlannedAction.PlayTracks(
            actionId = action.actionId,
            mode = action.mode,
            tracks = tracks,
            continuous = if (action.mode == PlayMode.ReplaceQueue) continuousSourceFor(action, plan, scopedTracks) else null,
            primaryGoal = action.primaryGoal,
            target = action.target,
            similar = action.similar,
            jumpToInserted = action.jumpToInserted,
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

    private fun intentFor(
        action: PlannedAction.PlayRequest,
        plan: MusicTurnPlan,
        localTracks: List<NativeTrack>,
    ): PetIntent {
        val useTextSignals = !plan.plannerRaw.startsWith("tool_loop")
        val target = action.target
        val primaryTracks = action.primaryGoal.primaryTracks
        val mustInclude = action.primaryGoal.mustInclude
        val closer = action.primaryGoal.closer
        val lexicon = CatalogLexicon(localTracks)
        val trackMentions = if (useTextSignals) lexicon.findTrackMentions(plan.userText).take(4) else emptyList()
        val artistMentions = if (useTextSignals) lexicon.findArtistMentions(plan.userText).take(4) else emptyList()
        val trackHints = mergeTextHints(
            (listOfNotNull(target) + primaryTracks + mustInclude + listOfNotNull(closer)).map { it.title },
            trackMentions.map { it.title },
        )
        val rawPrimaryArtists = action.primaryGoal.primaryArtists
        val includeArtists = action.primaryGoal.includeArtists
        val rawArtistHints = mergeTextHints(
            rawPrimaryArtists + includeArtists + listOfNotNull(target?.artist) +
                primaryTracks.mapNotNull { it.artist } + mustInclude.mapNotNull { it.artist } + listOfNotNull(closer?.artist),
            artistMentions.map { it.name } + trackMentions.map { it.artist },
        )
        val artistResolver = ArtistResolver(localTracks)
        val primaryArtists = canonicalArtists(rawPrimaryArtists, artistResolver)
        val artistHints = canonicalArtists(rawArtistHints, artistResolver)
        val goal = action.primaryGoal
        val style = goal.styleProfile
        val styleAvoid = mergeTextHints(style.avoidTags, goal.aiAvoidStyles)
        val excludeTerms = mergeTextHints(goal.excludeTerms, styleAvoid)
        val genres = mergeTextHints(
            if (useTextSignals) genreHints(plan.userText) else emptyList(),
            style.genres,
            goal.hardGenres,
            goal.aiMainStyles,
        )
        val languages = mergeTextHints(
            if (useTextSignals) CommandTextSignals.languageIncludes(plan.userText, excludeTerms) else emptyList(),
            style.languages,
            goal.hardLanguages,
        )
        val vocalTypes = mergeTextHints(style.vocalTypes, goal.hardVocalTypes)
        val softMoods = mergeTextHints(style.moods, goal.softMoods)
        val softScenes = mergeTextHints(style.scenes, goal.softScenes)
        val softTextures = mergeTextHints(style.textures, goal.softTextures)
        val softQualityWords = mergeTextHints(style.qualityWords, goal.softQualityWords)
        val refStyles = mergeTextHints(style.refStyles, goal.refStyles)
        val energy = when {
            style.energy.isNotBlank() && style.energy != "any" -> style.energy
            goal.softEnergy.isNotBlank() && goal.softEnergy != "any" -> goal.softEnergy
            useTextSignals -> CommandTextSignals.energyHint(plan.userText)
            else -> "any"
        }
        val semanticQuery = style.semanticQuery
            .ifBlank { goal.searchSeeds.firstOrNull().orEmpty() }
            .ifBlank { if (useTextSignals) plan.userText else structuredSearchQuery(action) }
        val styleTerms = mergeTextHints(softMoods, softScenes, softTextures, softQualityWords, refStyles)
        return PetIntent(
            queryText = semanticQuery,
            hardArtists = if (action.mode == PlayMode.ReplaceQueue) primaryArtists else artistHints,
            hardGenres = genres,
            hardLanguages = languages,
            hardVocalTypes = vocalTypes,
            textArtists = artistHints,
            hardTracks = if (action.mode == PlayMode.PlayNow || action.mode == PlayMode.InsertNext) trackHints else emptyList(),
            textTracks = trackHints,
            excludeArtists = excludeTerms.filterNot(::looksLikeLanguage),
            excludeLanguages = if (useTextSignals) CommandTextSignals.languageExcludes(plan.userText) else emptyList(),
            excludeTags = excludeTerms.filterNot(::looksLikeLanguage),
            avoidWords = excludeTerms,
            softMoods = softMoods,
            softScenes = softScenes,
            softTextures = softTextures,
            softQualityWords = softQualityWords,
            softEnergy = energy,
            softTempoFeel = goal.softTempoFeel,
            musicHintsMoods = softMoods,
            musicHintsScenes = softScenes,
            musicHintsGenres = genres,
            musicHintsEnergy = energy,
            musicHintsTransitionStyle = style.transitionStyle,
            refStyles = refStyles,
            aiMainStyles = mergeTextHints(genres, styleTerms, goal.aiMainStyles),
            aiAdjacentStyles = goal.aiAdjacentStyles,
            aiAvoidStyles = styleAvoid,
            aiExploration = style.exploration,
            emotionalDirection = softMoods.firstOrNull(),
            orderStyle = when (energy) {
                "high", "mid_high" -> "energy_up"
                "low" -> "smooth_down"
                else -> "smooth"
            },
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
        val queryVector = queryVectorFor(intent)
        val embeddingStore = queryVector?.let {
            runCatching { PipoGraph.embeddingStore.takeIf { store -> store.count() > 0 } }.getOrNull()
        }
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
            embeddingStore = embeddingStore,
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
            ),
        )
        val localResult = when (artistScope) {
            ArtistScope.Strict -> TrackDedupe.capSameTitle(scoped).take(desired)
            ArtistScope.Focus -> TrackDedupe.capSameTitle(
                diversifyByArtistButPreserveFocus(scoped, artistKeys),
            ).take(desired)
            ArtistScope.Similar -> TrackDedupe.capSameTitle(diversifyByArtist(scoped)).take(desired)
        }
        return if (localResult.isNotEmpty()) {
            localResult
        } else if (allowOnline) {
            onlineBackfill(intent, desired, artistScope)
        } else {
            emptyList()
        }
    }

    private suspend fun queryVectorFor(intent: PetIntent): FloatArray? {
        val query = intent.queryText.trim()
        if (query.isBlank()) return null
        val hasSemanticNeed = intent.softMoods.isNotEmpty() ||
            intent.softScenes.isNotEmpty() ||
            intent.softTextures.isNotEmpty() ||
            intent.softQualityWords.isNotEmpty() ||
            intent.refStyles.isNotEmpty() ||
            intent.aiMainStyles.isNotEmpty() ||
            intent.softEnergy != "any"
        if (!hasSemanticNeed) return null
        val hasIndexedVectors = runCatching { PipoGraph.embeddingStore.count() > 0 }.getOrDefault(false)
        if (!hasIndexedVectors) return null
        return runCatching { PipoGraph.embeddingIndexer.embedQuery(query) }.getOrNull()
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
        // 分批并行搜（批内 3 个并行，凑够 desired 不开下一批）。query 按「具体歌→歌手→风格」
        // 优先级排序，合并仍按该顺序取，结果语义与串行一致；wall-time 约为串行的 1/3。
        for (batch in queries.chunked(3)) {
            if (out.size >= desired) break
            val hitsPerQuery = coroutineScope {
                batch.map { query ->
                    async { runCatching { repository.searchTracks(query, limit = 20) }.getOrDefault(emptyList()) }
                }.awaitAll()
            }
            for (hits in hitsPerQuery) {
                if (out.size >= desired) break
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
        }
        return out
    }

    private fun continuousSourceFor(
        action: PlannedAction.PlayRequest,
        plan: MusicTurnPlan,
        localTracks: List<NativeTrack>,
    ): ContinuousQueueSource {
        val intent = intentFor(action, plan, localTracks)
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

    private fun buildSearchQueries(intent: PetIntent): List<String> {
        val out = mutableListOf<String>()
        for (track in intent.textTracks + intent.hardTracks) {
            val artist = (intent.textArtists + intent.hardArtists).firstOrNull().orEmpty()
            out.add(listOf(artist, track).filter { it.isNotBlank() }.joinToString(" "))
        }
        out.add(intent.queryText)
        out.addAll(intent.hardArtists)
        out.addAll(intent.textArtists)
        out.addAll(intent.hardGenres)
        out.addAll(intent.musicHintsGenres)
        out.addAll(intent.softMoods)
        out.addAll(intent.softScenes)
        out.addAll(intent.softTextures)
        out.addAll(intent.softQualityWords)
        out.addAll(intent.refStyles)
        val artistPrefix = (intent.hardArtists + intent.textArtists).firstOrNull().orEmpty()
        val styleQuery = (intent.softMoods + intent.softScenes + intent.softTextures + intent.hardGenres)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (styleQuery.isNotBlank()) out.add(listOf(artistPrefix, styleQuery).filter { it.isNotBlank() }.joinToString(" "))
        return out.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(10)
    }

    private fun mergeUnique(first: List<NativeTrack>, second: List<NativeTrack>): List<NativeTrack> {
        val out = ArrayList<NativeTrack>(first.size + second.size)
        val seen = HashSet<String>()
        for (track in first + second) {
            if (seen.add(TrackDedupe.songKey(track))) out.add(track)
        }
        return out
    }

    private suspend fun resolveIncludedArtistTracks(
        includeArtists: List<String>,
        ranked: List<NativeTrack>,
        scopedTracks: List<NativeTrack>,
        allowOnline: Boolean,
    ): List<NativeTrack> {
        if (includeArtists.isEmpty()) return emptyList()
        val resolver = ArtistResolver(scopedTracks)
        val canonical = canonicalArtists(includeArtists, resolver).ifEmpty { includeArtists }
        val out = ArrayList<NativeTrack>()
        val seen = HashSet<String>()
        val localPool = ranked + scopedTracks
        for (artist in canonical) {
            val key = CommandTextSignals.normalizeForMatch(artist)
            if (key.isBlank()) continue
            val local = localPool.firstOrNull { track ->
                TrackDedupe.songKey(track) !in seen && artistMatchesAny(track.artist, listOf(key))
            }
            val picked = local ?: if (allowOnline) {
                runCatching { repository.searchTracks(artist, limit = 12) }
                    .getOrDefault(emptyList())
                    .firstOrNull { track -> artistMatchesAny(track.artist, listOf(key)) && TrackDedupe.songKey(track) !in seen }
            } else {
                null
            }
            if (picked != null && seen.add(TrackDedupe.songKey(picked))) out.add(picked)
        }
        return out
    }

    private fun injectIncludedArtistTracks(
        base: List<NativeTrack>,
        artistTracks: List<NativeTrack>,
        desired: Int,
    ): List<NativeTrack> {
        if (artistTracks.isEmpty()) return base.take(desired)
        val out = base.toMutableList()
        val seen = out.mapTo(HashSet()) { TrackDedupe.songKey(it) }
        artistTracks.forEachIndexed { index, track ->
            val key = TrackDedupe.songKey(track)
            if (!seen.add(key)) return@forEachIndexed
            val insertion = (3 + index * 2).coerceIn(0, out.size)
            out.add(insertion, track)
        }
        return out.take(desired.coerceAtLeast(artistTracks.size + 1))
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
        for (item in required.filterNot { it.requirement.placement == app.pipo.nativeapp.data.agent.domain.TrackPlacement.Closer }) {
            val insertion = when {
                out.isEmpty() -> 0
                out.size >= 3 -> 3
                else -> out.size
            }
            out.add(insertion.coerceIn(0, out.size), item.track)
        }
        for (item in required.filter { it.requirement.placement == app.pipo.nativeapp.data.agent.domain.TrackPlacement.Closer }) {
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
            val structured = plan.plannerRaw.startsWith("tool_loop")
            val firstPlay = play.firstOrNull()
            val includeTitle = if (structured) {
                firstPlay?.primaryGoal?.mustInclude?.firstOrNull()?.title
            } else {
                CommandTextSignals.includedTrackTitle(plan.userText)
            }
            val includeArtists = if (structured) {
                firstPlay?.primaryGoal?.includeArtists.orEmpty()
            } else {
                CommandTextSignals.includedArtistHints(plan.userText)
            }
            val closerTitle = if (structured) {
                firstPlay?.primaryGoal?.closer?.title
            } else {
                CommandTextSignals.closerTrackTitle(plan.userText)
            }
            if (!includeTitle.isNullOrBlank()) append(";mustInclude=").append(includeTitle)
            if (includeArtists.isNotEmpty()) append(";includeArtists=").append(includeArtists.joinToString("/"))
            if (!closerTitle.isNullOrBlank()) append(";closer=").append(closerTitle)
        }
    }

    private fun missingRequirements(plan: MusicTurnPlan, actions: List<PlannedAction>): List<String> {
        val tracks = actions.filterIsInstance<PlannedAction.PlayTracks>().flatMap { it.tracks }
        val play = actions.filterIsInstance<PlannedAction.PlayTracks>().firstOrNull()
        val missing = mutableListOf<String>()
        val structured = plan.plannerRaw.startsWith("tool_loop")
        val includeTitle = if (structured) {
            play?.primaryGoal?.mustInclude?.firstOrNull()?.title
        } else {
            CommandTextSignals.includedTrackTitle(plan.userText)
        }
        val includeArtists = if (structured) {
            play?.primaryGoal?.includeArtists.orEmpty()
        } else {
            CommandTextSignals.includedArtistHints(plan.userText)
        }
        val closerTitle = if (structured) {
            play?.primaryGoal?.closer?.title
        } else {
            CommandTextSignals.closerTrackTitle(plan.userText)
        }
        if (!includeTitle.isNullOrBlank() && tracks.none { titleMatches(it.title, includeTitle) }) {
            missing.add("mustInclude:$includeTitle")
        }
        includeArtists.forEach { artist ->
            val key = CommandTextSignals.normalizeForMatch(artist)
            if (key.isNotBlank() && tracks.none { artistMatchesAny(it.artist, listOf(key)) }) {
                missing.add("includeArtist:$artist")
            }
        }
        if (!closerTitle.isNullOrBlank() && tracks.none { titleMatches(it.title, closerTitle) }) {
            missing.add("closer:$closerTitle")
        }
        return missing
    }

    private fun structuredSearchQuery(action: PlannedAction.PlayRequest): String {
        val goal = action.primaryGoal
        return listOf(
            action.target?.let { listOfNotNull(it.artist, it.title).joinToString(" ") },
            goal.primaryTracks.firstOrNull()?.let { listOfNotNull(it.artist, it.title).joinToString(" ") },
            goal.primaryArtists.joinToString(" ").takeIf { it.isNotBlank() },
            goal.hardGenres.joinToString(" ").takeIf { it.isNotBlank() },
            goal.softMoods.joinToString(" ").takeIf { it.isNotBlank() },
            goal.softScenes.joinToString(" ").takeIf { it.isNotBlank() },
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
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

    private fun mergeTextHints(vararg groups: List<String>): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (group in groups) {
            for (value in group) {
                val trimmed = value.trim()
                if (trimmed.isNotBlank() && seen.add(CommandTextSignals.normalizeForMatch(trimmed))) {
                    out.add(trimmed)
                }
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
        if ("电子" in text || "电音" in text || "electronic" in lower || "edm" in lower || "dance" in lower) out.add("electronic")
        if ("嘻哈" in text || "说唱" in text || "hiphop" in lower || "hip-hop" in lower || "rap" in lower) out.add("hip-hop")
        if ("流行" in text || "pop" in lower) out.add("pop")
        if ("citypop" in lower || "city pop" in lower || "城市流行" in text) out.add("city pop")
        if ("独立" in text || "indie" in lower) out.add("indie")
        if ("粤语" in text || "cantopop" in lower) out.add("cantopop")
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
