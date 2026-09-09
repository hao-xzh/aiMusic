package app.pipo.nativeapp.data.agent.resolve

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.CandidateRanker
import app.pipo.nativeapp.data.CandidateRecall
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.FunctionalMusicFilter
import app.pipo.nativeapp.data.GuardedContinuousQueueSource
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.MusicSearchException
import app.pipo.nativeapp.data.PetIntent
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoPlaylist
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.RecommendationFeedbackLog
import app.pipo.nativeapp.data.TrackDedupe
import app.pipo.nativeapp.data.agent.domain.AgentTurnInput
import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.normalize.CatalogConstraintMatcher
import app.pipo.nativeapp.data.agent.normalize.CatalogLexicon
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import app.pipo.nativeapp.data.agent.queue.ConstraintEvidence
import app.pipo.nativeapp.data.agent.queue.ConstraintScorer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

class MusicResolver(
    private val repository: PipoRepository,
    private val loadLocalTracks: suspend () -> List<NativeTrack> = {
        runCatching { PipoGraph.library.library() }.getOrDefault(emptyList())
    },
    private val recallCandidates: ((PetIntent, List<NativeTrack>, NativeTrack?, Int) -> List<CandidateRecall.Candidate>)? = null,
) {
    private companion object {
        const val CONTINUATION_WANT_COUNT = 12
        const val CONTINUATION_LOCAL_POOL_COUNT = 96
        const val CONTINUATION_SEARCH_LIMIT = 50
        const val CONTINUATION_MAX_SEARCH_QUERIES = 16
        const val ARTIST_FOCUS_MIN_NUMERATOR = 7
        const val ARTIST_FOCUS_MIN_DENOMINATOR = 10
    }

    private val trackResolver = TrackResolver(repository)
    private val playlistResolver = PlaylistResolver()
    private val constraintScorer = ConstraintScorer()

    private data class SearchAttempt(
        val tracks: List<NativeTrack>?,
        val failure: MusicSearchException?,
    )

    private suspend fun searchBatch(queries: List<String>, limit: Int): List<List<NativeTrack>> {
        val attempts = coroutineScope {
            queries.map { query ->
                async {
                    try {
                        SearchAttempt(repository.searchTracks(query, limit), failure = null)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: MusicSearchException) {
                        SearchAttempt(tracks = null, failure = error)
                    }
                }
            }.awaitAll()
        }
        val successes = attempts.mapNotNull { it.tracks }
        if (successes.isEmpty()) {
            throw requireNotNull(attempts.mapNotNull { it.failure }.firstOrNull())
        }
        return successes
    }

    suspend fun resolve(
        plan: MusicTurnPlan,
        input: AgentTurnInput,
        allowOnlineSearch: Boolean = true,
    ): ResolutionResult {
        val localTracks = loadLocalTracks()
        val resolvedActions = plan.actions.map { action ->
            when (action) {
                is PlannedAction.PlayRequest -> resolvePlayRequest(
                    action,
                    plan,
                    input,
                    localTracks,
                    allowOnlineSearch,
                )
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
        val playlists = availablePlaylists()
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
        allowOnlineSearch: Boolean,
    ): PlannedAction.PlayTracks {
        val catalogConstraint = action.primaryGoal.catalogConstraint
        val unfilteredScopedTracks = scopedTracksFor(action.primaryGoal.playlistName, localTracks)
        val scopedTracks = unfilteredScopedTracks
            .let { tracks ->
                if (catalogConstraint.isActive) {
                    tracks.filter { CatalogConstraintMatcher.matches(it, catalogConstraint) }
                } else {
                    tracks
                }
            }
        if (catalogConstraint.isActive) {
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "catalog_scope_local",
                fields = mapOf(
                    "catalog" to catalogConstraint.name.take(80),
                    "before" to unfilteredScopedTracks.size,
                    "matched" to scopedTracks.size,
                    "aliases" to catalogConstraint.aliases.joinToString("|").take(160),
                ),
            )
        }
        val allowOnline = action.primaryGoal.playlistName.isBlank() && allowOnlineSearch
        val tracks = when (action.mode) {
            PlayMode.PlayNow -> {
                val target = action.target
                val head = target?.let { trackResolver.resolve(it, scopedTracks, allowOnline = allowOnline).track }
                if (head == null) emptyList()
                else if (action.desiredCount <= 1) listOf(head)
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
                ).take(action.desiredCount)
            }
            PlayMode.InsertNext -> {
                // desiredCount=1 仍是“下一首插某首”；>1 是批量插播（这首听完放 X 的歌/
                // 某专辑/下一首开始听 Y）：目标歌（若有）打头，其余按意图排序补齐。
                val desired = action.desiredCount.coerceIn(1, 30)
                val head = action.target?.let { trackResolver.resolve(it, scopedTracks, allowOnline = allowOnline).track }
                when {
                    head != null && desired <= 1 -> listOf(head)
                    head != null -> mergeUnique(
                        listOf(head),
                        rankForIntent(
                            intentFor(action, plan, scopedTracks),
                            input,
                            scopedTracks,
                            desired,
                            action.primaryGoal.artistScope,
                            allowOnline = allowOnline,
                        ),
                    ).take(desired)
                    else -> rankForIntent(
                        intentFor(action, plan, scopedTracks),
                        input,
                        scopedTracks,
                        desired,
                        action.primaryGoal.artistScope,
                        allowOnline = allowOnline,
                    ).take(desired)
                }
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
            continuous = if (action.mode != PlayMode.InsertNext && tracks.size > 1) {
                continuousSourceFor(action, plan, input, scopedTracks, allowOnline)
            } else {
                null
            },
            primaryGoal = action.primaryGoal,
            target = action.target,
            similar = action.similar,
            jumpToInserted = action.jumpToInserted,
            preserveCurrent = action.preserveCurrent,
        )
    }

    private suspend fun scopedTracksFor(playlistName: String, fallbackLocalTracks: List<NativeTrack>): List<NativeTrack> {
        if (playlistName.isBlank()) return fallbackLocalTracks
        if (CommandTextSignals.isCloudPlaylistName(playlistName)) {
            return runCatching { repository.cloudDiskTracks() }.getOrDefault(emptyList())
        }
        val playlists = availablePlaylists()
        val resolved = playlistResolver.resolve(playlistName, playlists) ?: return emptyList()
        return runCatching { repository.tracksForPlaylist(resolved.playlist.id) }.getOrDefault(emptyList())
    }

    private suspend fun availablePlaylists(): List<PipoPlaylist> {
        var playlists = runCatching { repository.playlists.first() }.getOrDefault(emptyList())
        if (playlists.isEmpty()) {
            runCatching { repository.refreshPlaylists() }
            playlists = runCatching { repository.playlists.first() }.getOrDefault(emptyList())
        }
        return playlists
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
        // Opening/must-include/closing tracks are resolved and inserted separately.
        // Their singer/title must not become the recommendation pool for the rest.
        val standaloneRequirements = if (action.mode == PlayMode.ReplaceQueue) emptyList()
            else listOfNotNull(target) + primaryTracks + mustInclude + listOfNotNull(closer)
        val trackHints = mergeTextHints(
            standaloneRequirements.map { it.title },
            trackMentions.map { it.title },
        )
        val rawPrimaryArtists = action.primaryGoal.primaryArtists
        val includeArtists = action.primaryGoal.includeArtists
        val rawArtistHints = mergeTextHints(
            rawPrimaryArtists +
                (if (action.mode == PlayMode.ReplaceQueue) emptyList() else includeArtists) +
                standaloneRequirements.mapNotNull { it.artist },
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
        val semanticQuery = goal.catalogConstraint.searchQueries.firstOrNull().orEmpty()
            .ifBlank { style.semanticQuery }
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
            textAlbums = goal.catalogConstraint.matchTerms,
            catalogAnchors = goal.catalogConstraint.matchTerms,
            catalogQueries = goal.catalogConstraint.searchQueries,
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
        val unifiedTaste = PipoGraph.userTaste.snapshot(PipoGraph.library.peek())
        val behaviorPreference = unifiedTaste.behavior
        val queryVector = queryVectorFor(intent)
        val embeddingStore = queryVector?.let {
            runCatching { PipoGraph.embeddingStore.takeIf { store -> store.count() > 0 } }.getOrNull()
        }
        val recallLimit = maxOf(220, desired * 8).coerceAtMost(900)
        val candidates = recallCandidates?.invoke(intent, localTracks, input.currentTrack, recallLimit) ?: CandidateRecall.recall(
            intent = intent,
            library = localTracks,
            featuresStore = PipoGraph.audioFeaturesStore,
            semanticStore = PipoGraph.trackSemanticStore,
            indexer = PipoGraph.semanticIndexer,
            tasteProfile = unifiedTaste.profile,
            behaviorEvents = behaviorEvents,
            behaviorPreference = behaviorPreference,
            currentTrack = input.currentTrack,
            limit = recallLimit,
            queryVector = queryVector,
            embeddingStore = embeddingStore,
        )
        val feedback = contextFeedbackFor(intent)
        val hardRejected = runCatching { PipoGraph.recommendationFeedbackLog.globallyRejected() }
            .getOrDefault(RecommendationFeedbackLog.RejectedContext(emptySet(), emptySet()))
        val rankedRaw = CandidateRanker.rank(
            candidates = candidates,
            intent = intent,
            options = CandidateRanker.Options(
                topN = desired * 3,
                recentPlay = runCatching { PipoGraph.behaviorLog.recentPlay() }.getOrNull(),
                recentRecommendation = runCatching { PipoGraph.recommendationLog.recentContext() }.getOrNull(),
                behaviorPreference = behaviorPreference,
                userTaste = unifiedTaste,
            ),
        )
        val ranked = rankedRaw
            .filterNot { feedback.contains(it.candidate.track) || hardRejected.contains(it.candidate.track) }
            .map { it.candidate.track }
        val feedbackFiltered = rankedRaw.size - ranked.size
        if (feedbackFiltered > 0) {
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "context_feedback_filtered",
                fields = mapOf(
                    "query" to intent.queryText.take(80),
                    "filteredCount" to feedbackFiltered,
                    "rankedBefore" to rankedRaw.size,
                    "rankedAfter" to ranked.size,
                ),
            )
        }
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
        val explicitTrackRequest = intent.hardTracks.isNotEmpty() || intent.textTracks.isNotEmpty()
        fun capTitles(items: List<NativeTrack>): List<NativeTrack> =
            if (explicitTrackRequest) TrackDedupe.capSameTitle(items)
            else TrackDedupe.capRecommendationTitleFamily(items)
        val localResult = when (artistScope) {
            ArtistScope.Strict -> capTitles(scoped).take(desired)
            ArtistScope.Focus -> takeWithinArtistScope(
                tracks = capTitles(scoped),
                artistKeys = artistKeys,
                artistScope = artistScope,
                desired = desired,
            )
            ArtistScope.Similar -> capTitles(diversifyByArtist(scoped)).take(desired)
        }
        return when {
            localResult.size >= desired -> localResult
            // 本地有少量命中仍需联网补齐；保留相同硬约束和艺人范围。
            localResult.isNotEmpty() && allowOnline ->
                takeWithinArtistScope(
                    tracks = mergeUnique(localResult, onlineBackfill(intent, desired, artistScope)),
                    artistKeys = artistKeys,
                    artistScope = artistScope,
                    desired = desired,
                )
            localResult.isNotEmpty() -> localResult
            allowOnline -> onlineBackfill(intent, desired, artistScope)
            else -> emptyList()
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
        var catalogRejected = 0
        val genericRecommendation = intent.hardTracks.isEmpty() && intent.textTracks.isEmpty() && intent.catalogAnchors.isEmpty()
        val hardRejected = runCatching { PipoGraph.recommendationFeedbackLog.globallyRejected() }
            .getOrDefault(RecommendationFeedbackLog.RejectedContext(emptySet(), emptySet()))
        // 分批并行搜（批内 3 个并行，凑够目标不开下一批）。query 按「具体歌→歌手→风格」
        // 优先级排序，合并仍按该顺序取，结果语义与串行一致；wall-time 约为串行的 1/3。
        // 多收四倍再重排/去标题族。不能在搜索阶段先留第一个 Pop 标题，否则会把
        // 搜索顺序误当智能排序，并可能被模板标题提前塞满池子。
        val poolTarget = desired * 4
        val allowFunctional = FunctionalMusicFilter.mentionsFunctional(
            listOf(intent.queryText) + intent.hardGenres + intent.musicHintsGenres +
                intent.refStyles + intent.aiMainStyles,
        )
        var searchedSuccessfully = false
        for (batch in queries.chunked(3)) {
            if (out.size >= poolTarget) break
            val hitsPerQuery = try {
                searchBatch(batch, limit = 20)
            } catch (error: MusicSearchException) {
                if (!searchedSuccessfully) throw error
                break
            }
            searchedSuccessfully = true
            for (hits in hitsPerQuery) {
                if (out.size >= poolTarget) break
                val scopedHits = when {
                    artistScope == ArtistScope.Strict && artistKeys.isNotEmpty() ->
                        hits.filter { artistMatchesAny(it.artist, artistKeys) }
                    artistScope == ArtistScope.Focus && artistKeys.isNotEmpty() ->
                        hits.sortedByDescending { if (artistMatchesAny(it.artist, artistKeys)) 1 else 0 }
                    else -> hits
                }
                for (track in scopedHits) {
                    if (intent.catalogAnchors.isNotEmpty() &&
                        !CatalogConstraintMatcher.matches(track, intent.catalogAnchors)
                    ) {
                        catalogRejected++
                        continue
                    }
                    if (hardRejected.contains(track)) continue
                    if (!FunctionalMusicFilter.acceptsCategory(track, intent.queryText)) continue
                    // 情绪词搜索的助眠/养生流水线内容不进候选（明确要纯音乐/点名歌手除外）。
                    if (!allowFunctional &&
                        FunctionalMusicFilter.isFunctional(track.title, track.artist) &&
                        !artistMatchesAny(track.artist, artistKeys)
                    ) {
                        continue
                    }
                    if (seen.add(TrackDedupe.songKey(track))) out.add(track)
                    if (out.size >= poolTarget) break
                }
            }
        }
        if (intent.catalogAnchors.isNotEmpty()) {
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "catalog_scope_online",
                fields = mapOf(
                    "anchors" to intent.catalogAnchors.joinToString("|").take(160),
                    "accepted" to out.size,
                    "rejected" to catalogRejected,
                    "queries" to queries.joinToString("|").take(200),
                ),
            )
        }
        val feedbackFiltered = filterContextFeedback(out, intent)
        val tasteReranked = rerankOnlineCandidates(feedbackFiltered, intent, poolTarget)
        val freshFirst = demoteRecentlyRecommended(tasteReranked)
        val titleCapped = if (genericRecommendation) {
            TrackDedupe.capRecommendationTitleFamily(freshFirst)
        } else {
            freshFirst
        }
        return takeWithinArtistScope(
            tracks = titleCapped,
            artistKeys = artistKeys,
            artistScope = artistScope,
            desired = desired,
        )
    }

    /**
     * 在线搜索只负责召回，不能把搜索服务的标题热度直接当推荐分。
     * 复用本地同一套 intent / 画像 / 长短期行为排序，再把没被召回器识别的结果
     * 按原搜索顺序补回，保证窄查询不因画像稀疏而饿死。
     */
    private suspend fun rerankOnlineCandidates(
        tracks: List<NativeTrack>,
        intent: PetIntent,
        limit: Int,
    ): List<NativeTrack> {
        if (tracks.size <= 1) return tracks
        val behaviorEvents = runCatching { PipoGraph.behaviorLog.readAll() }.getOrDefault(emptyList())
        val unifiedTaste = PipoGraph.userTaste.snapshot(PipoGraph.library.peek())
        val behaviorPreference = unifiedTaste.behavior
        val recallLimit = maxOf(limit, tracks.size)
        val candidates = recallCandidates?.invoke(intent, tracks, null, recallLimit) ?: CandidateRecall.recall(
            intent = intent,
            library = tracks,
            featuresStore = PipoGraph.audioFeaturesStore,
            semanticStore = PipoGraph.trackSemanticStore,
            indexer = PipoGraph.semanticIndexer,
            tasteProfile = unifiedTaste.profile,
            behaviorEvents = behaviorEvents,
            behaviorPreference = behaviorPreference,
            currentTrack = null,
            limit = recallLimit,
        )
        val ranked = CandidateRanker.rank(
            candidates = candidates,
            intent = intent,
            options = CandidateRanker.Options(
                topN = maxOf(limit, tracks.size),
                recentPlay = runCatching { PipoGraph.behaviorLog.recentPlay() }.getOrNull(),
                recentRecommendation = runCatching { PipoGraph.recommendationLog.recentContext() }.getOrNull(),
                behaviorPreference = behaviorPreference,
                userTaste = unifiedTaste,
            ),
        ).map { it.candidate.track }
        val seen = ranked.mapTo(HashSet()) { TrackDedupe.songKey(it) }
        return buildList {
            addAll(ranked)
            tracks.forEach { if (seen.add(TrackDedupe.songKey(it))) add(it) }
        }.take(limit)
    }

    private fun contextFeedbackFor(intent: PetIntent): RecommendationFeedbackLog.RejectedContext =
        runCatching { PipoGraph.recommendationFeedbackLog.rejectedForIntent(intent) }
            .getOrDefault(RecommendationFeedbackLog.RejectedContext(emptySet(), emptySet()))

    private fun filterContextFeedback(tracks: List<NativeTrack>, intent: PetIntent): List<NativeTrack> {
        if (tracks.isEmpty()) return tracks
        val feedback = contextFeedbackFor(intent)
        return tracks.filterNot { feedback.contains(it) }
    }

    /** 近期（24h/7d）推荐过的沉底：池子够大时等于换一批新歌；池子太小时仍允许重复兜底。 */
    private fun demoteRecentlyRecommended(tracks: List<NativeTrack>): List<NativeTrack> {
        if (tracks.size <= 1) return tracks
        val recent = runCatching { PipoGraph.recommendationLog.recentContext() }.getOrNull() ?: return tracks
        if (recent.last24hTrackIds.isEmpty() && recent.last7dTrackIds.isEmpty()) return tracks
        val fresh = ArrayList<NativeTrack>(tracks.size)
        val weekStale = ArrayList<NativeTrack>()
        val dayStale = ArrayList<NativeTrack>()
        for (track in tracks) {
            val id = track.neteaseId
            when {
                id == null -> fresh.add(track)
                id in recent.last24hTrackIds -> dayStale.add(track)
                id in recent.last7dTrackIds -> weekStale.add(track)
                else -> fresh.add(track)
            }
        }
        if (dayStale.isEmpty() && weekStale.isEmpty()) return tracks
        return fresh + weekStale + dayStale
    }

    private fun continuousSourceFor(
        action: PlannedAction.PlayRequest,
        plan: MusicTurnPlan,
        input: AgentTurnInput,
        localTracks: List<NativeTrack>,
        allowOnline: Boolean,
    ): ContinuousQueueSource {
        val intent = intentFor(action, plan, localTracks)
        val artistKeys = intent.hardArtists
            .map(CommandTextSignals::normalizeForMatch)
            .filter { it.isNotBlank() }
        val artistScope = action.primaryGoal.artistScope
        if (action.primaryGoal.playlistName.isNotBlank()) {
            val fetcher: suspend (Set<Long>) -> List<NativeTrack> = { excludeIds ->
                val hardRejected = runCatching { PipoGraph.recommendationFeedbackLog.globallyRejected() }
                    .getOrDefault(RecommendationFeedbackLog.RejectedContext(emptySet(), emptySet()))
                val scoped = applyArtistScope(
                    tracks = localTracks,
                    artistKeys = artistKeys,
                    artistScope = artistScope,
                )
                TrackDedupe.capSameTitle(scoped)
                    .filter { track ->
                        (track.neteaseId?.let { it !in excludeIds } ?: true) && !hardRejected.contains(track)
                    }
                    .take(12)
            }
            return guardedContinuousSource(action, intent, artistKeys, plan.userText, fetcher)
        }
        val seedQueries = buildContinuationSearchQueries(intent).ifEmpty { listOf(plan.userText) }
        val allowFunctional = FunctionalMusicFilter.mentionsFunctional(
            listOf(intent.queryText, plan.userText) + intent.hardGenres + intent.musicHintsGenres +
                intent.refStyles + intent.aiMainStyles,
        )
        val preferLatin = intent.hardLanguages.any { lang ->
            "英" in lang || lang.lowercase().startsWith("eng")
        }
        val fetcher: suspend (Set<Long>) -> List<NativeTrack> = { excludeIds ->
            val local = localContinuation(intent, input, localTracks, artistScope, excludeIds)
            if (local.size >= CONTINUATION_WANT_COUNT || !allowOnline) {
                local
            } else {
                val haveKeys = local.mapTo(HashSet()) { TrackDedupe.songKey(it) }
                val online = searchContinuation(
                    seedQueries,
                    artistScope,
                    artistKeys,
                    excludeIds,
                    allowFunctional,
                    preferLatin,
                    intent.catalogAnchors,
                )
                    .let { filterContextFeedback(it, intent) }
                    .filter { TrackDedupe.songKey(it) !in haveKeys }
                takeWithinArtistScope(
                    tracks = local + online,
                    artistKeys = artistKeys,
                    artistScope = artistScope,
                    desired = CONTINUATION_WANT_COUNT,
                )
            }
        }
        return guardedContinuousSource(action, intent, artistKeys, plan.userText, fetcher)
    }

    private fun guardedContinuousSource(
        action: PlannedAction.PlayRequest,
        intent: PetIntent,
        artistKeys: List<String>,
        userRequest: String,
        fetcher: suspend (Set<Long>) -> List<NativeTrack>,
    ): ContinuousQueueSource {
        val goal = action.primaryGoal
        val strictArtists = goal.artistScope == ArtistScope.Strict && artistKeys.isNotEmpty()
        val excludedTerms = mergeTextHints(intent.avoidWords, intent.excludeTags, intent.aiAvoidStyles)

        fun accepts(track: NativeTrack): Boolean {
            if (!FunctionalMusicFilter.acceptsCategory(track, userRequest)) return false
            if (strictArtists && !exactArtistMatchesAny(track.artist, artistKeys)) return false
            if (goal.hardLanguages.isNotEmpty() &&
                constraintScorer.hardLanguageEvidence(track, goal.hardLanguages) != ConstraintEvidence.Match
            ) return false
            if (goal.hardGenres.isNotEmpty() &&
                constraintScorer.hardGenreEvidence(track, goal.hardGenres) != ConstraintEvidence.Match
            ) return false
            if (intent.excludeLanguages.isNotEmpty() &&
                constraintScorer.excludedLanguageEvidence(track, intent.excludeLanguages) != ConstraintEvidence.Mismatch
            ) return false
            if (excludedTerms.isNotEmpty() &&
                constraintScorer.avoidEvidence(track, excludedTerms) != ConstraintEvidence.Mismatch
            ) return false
            if (goal.catalogConstraint.isActive && !CatalogConstraintMatcher.matches(track, goal.catalogConstraint)) return false
            return true
        }

        return GuardedContinuousQueueSource(
            allowDefaultFallback = false,
            acceptsTrack = ::accepts,
            fetcher = { excludeIds -> fetcher(excludeIds).filter(::accepts) },
        )
    }

    /** 标题+歌手以拉丁字母为主（粗粒度语言判定，仅用于排序偏好，不做硬过滤）。 */
    private fun titleMostlyLatin(track: NativeTrack): Boolean {
        val text = "${track.title} ${track.artist}"
        var latin = 0
        var cjk = 0
        for (ch in text) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
                Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN -> cjk++
            }
        }
        return latin > cjk
    }

    private suspend fun localContinuation(
        intent: PetIntent,
        input: AgentTurnInput,
        localTracks: List<NativeTrack>,
        artistScope: ArtistScope,
        excludeIds: Set<Long>,
    ): List<NativeTrack> {
        if (localTracks.isEmpty()) return emptyList()
        val rankedPool = rankForIntent(
            intent = intent,
            input = input,
            localTracks = localTracks,
            desired = CONTINUATION_LOCAL_POOL_COUNT,
            artistScope = artistScope,
            allowOnline = false,
        )
        val eligible = rankedPool.filter { track ->
            track.neteaseId?.let { it !in excludeIds } ?: true
        }
        val artistKeys = intent.hardArtists
            .map(CommandTextSignals::normalizeForMatch)
            .filter { it.isNotBlank() }
        val sampled = if (artistKeys.isNotEmpty() && artistScope != ArtistScope.Similar) {
            takeWithinArtistScope(
                tracks = eligible,
                artistKeys = artistKeys,
                artistScope = artistScope,
                desired = CONTINUATION_WANT_COUNT,
            )
        } else {
            sampleContinuation(eligible, CONTINUATION_WANT_COUNT)
        }
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "continuous_source_local_sample",
            fields = mapOf(
                "query" to intent.queryText.take(80),
                "languages" to intent.hardLanguages.joinToString(","),
                "rankedPoolCount" to rankedPool.size,
                "eligibleCount" to eligible.size,
                "selectedCount" to sampled.size,
            ),
        )
        return sampled
    }

    private fun sampleContinuation(pool: List<NativeTrack>, wantCount: Int): List<NativeTrack> {
        if (pool.size <= wantCount) return pool
        val available = TrackDedupe.capSameTitle(pool).toMutableList()
        if (available.size <= wantCount) return available
        val rnd = java.util.Random()
        val out = ArrayList<NativeTrack>(wantCount)
        val artistCounts = HashMap<String, Int>()
        var guard = 0
        while (out.size < wantCount && available.isNotEmpty() && guard < pool.size * 3) {
            guard += 1
            val idx = weightedRankIndex(available.size, rnd)
            val track = available.removeAt(idx)
            val artistKey = firstArtistKey(track)
            val count = artistCounts[artistKey] ?: 0
            if (count >= 2 && available.size >= wantCount - out.size) continue
            out.add(track)
            artistCounts[artistKey] = count + 1
        }
        if (out.size < wantCount) {
            for (track in available.shuffled()) {
                out.add(track)
                if (out.size >= wantCount) break
            }
        }
        return out
    }

    private fun weightedRankIndex(size: Int, rnd: java.util.Random): Int {
        if (size <= 1) return 0
        var total = 0.0
        for (idx in 0 until size) {
            total += 1.0 / (1.0 + idx * 0.08)
        }
        var ticket = rnd.nextDouble() * total
        for (idx in 0 until size) {
            ticket -= 1.0 / (1.0 + idx * 0.08)
            if (ticket <= 0.0) return idx
        }
        return size - 1
    }

    private fun firstArtistKey(track: NativeTrack): String =
        track.artist.split("/", "&", ",", "、")
            .firstOrNull()
            ?.let(CommandTextSignals::normalizeForMatch)
            .orEmpty()

    private suspend fun searchContinuation(
        seedQueries: List<String>,
        artistScope: ArtistScope,
        artistKeys: List<String>,
        excludeIds: Set<Long>,
        allowFunctional: Boolean,
        preferLatin: Boolean,
        catalogAnchors: List<String>,
    ): List<NativeTrack> {
        val out = ArrayList<NativeTrack>()
        val seen = HashSet<String>()
        val hardRejected = runCatching { PipoGraph.recommendationFeedbackLog.globallyRejected() }
            .getOrDefault(RecommendationFeedbackLog.RejectedContext(emptySet(), emptySet()))
        var searchedSuccessfully = false
        for (batch in seedQueries.chunked(3)) {
            if (out.size >= CONTINUATION_WANT_COUNT * 2) break
            val hitsPerQuery = try {
                searchBatch(batch, limit = CONTINUATION_SEARCH_LIMIT)
            } catch (error: MusicSearchException) {
                if (!searchedSuccessfully) throw error
                break
            }
            searchedSuccessfully = true
            for (hits in hitsPerQuery) {
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
                    if (catalogAnchors.isNotEmpty() &&
                        !CatalogConstraintMatcher.matches(track, catalogAnchors)
                    ) {
                        continue
                    }
                    if (hardRejected.contains(track)) continue
                    // 续杯是无人值守的：情绪词搜索返回的助眠/养生流水线内容必须挡掉
                    //（点名歌手的结果放行），否则一次续杯就把电台灌满纯音乐。
                    if (!allowFunctional &&
                        FunctionalMusicFilter.isFunctional(track.title, track.artist) &&
                        !artistMatchesAny(track.artist, artistKeys)
                    ) {
                        continue
                    }
                    if (seen.add(TrackDedupe.songKey(track))) out.add(track)
                    if (out.size >= CONTINUATION_WANT_COUNT * 2) break
                }
            }
        }
        // 英文意图的电台：拉丁字面的结果优先（中文流行/功能性内容沉底），稳定分区不打乱组内顺序。
        if (preferLatin) {
            val (latin, other) = out.partition { titleMostlyLatin(it) }
            out.clear()
            out.addAll(latin)
            out.addAll(other)
        }
        val deduped = TrackDedupe.dedupe(out)
        val ordered = if (artistScope == ArtistScope.Similar) diversifyByArtist(deduped) else deduped
        return takeWithinArtistScope(
            tracks = TrackDedupe.capRecommendationTitleFamily(demoteRecentlyRecommended(ordered)),
            artistKeys = artistKeys,
            artistScope = artistScope,
            desired = CONTINUATION_WANT_COUNT,
        )
    }

    private fun buildContinuationSearchQueries(intent: PetIntent): List<String> {
        val out = buildSearchQueries(intent).toMutableList()
        val artists = (intent.hardArtists + intent.textArtists)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy(CommandTextSignals::normalizeForMatch)
        val styleTerms = mergeTextHints(
            intent.hardGenres,
            intent.musicHintsGenres,
            intent.softMoods,
            intent.softScenes,
            intent.softTextures,
            intent.softQualityWords,
            intent.refStyles,
        )
        for (artist in artists.take(2)) {
            for (style in styleTerms.take(4)) {
                out.add("$artist $style")
            }
            intent.queryText.takeIf { it.isNotBlank() }?.let { out.add("$artist $it") }
        }
        return out
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy(CommandTextSignals::normalizeForMatch)
            .take(CONTINUATION_MAX_SEARCH_QUERIES)
    }

    private fun buildSearchQueries(intent: PetIntent): List<String> {
        val out = mutableListOf<String>()
        out.addAll(intent.catalogQueries)
        out.addAll(intent.catalogAnchors)
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
        var searchedSuccessfully = false
        var searchUnavailable = false
        for (artist in canonical) {
            val key = CommandTextSignals.normalizeForMatch(artist)
            if (key.isBlank()) continue
            val local = localPool.firstOrNull { track ->
                TrackDedupe.songKey(track) !in seen && artistMatchesAny(track.artist, listOf(key))
            }
            val picked = local ?: if (allowOnline && !searchUnavailable) {
                try {
                    repository.searchTracks(artist, limit = 12).also { searchedSuccessfully = true }
                        .firstOrNull { track -> artistMatchesAny(track.artist, listOf(key)) && TrackDedupe.songKey(track) !in seen }
                } catch (error: MusicSearchException) {
                    if (!searchedSuccessfully && out.isEmpty()) throw error
                    searchUnavailable = true
                    null
                }
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
        play?.primaryGoal?.primaryTracks.orEmpty().forEach { requirement ->
            if (tracks.none { CommandTextSignals.normalizeForMatch(it.title) == CommandTextSignals.normalizeForMatch(requirement.title) &&
                    (requirement.artist.isNullOrBlank() || artistMatchesAny(it.artist,
                        listOf(CommandTextSignals.normalizeForMatch(requirement.artist)))) }) {
                missing.add("primaryTrack:" + listOfNotNull(requirement.artist, requirement.title).joinToString(" - "))
            }
        }
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

    private fun takeWithinArtistScope(
        tracks: List<NativeTrack>,
        artistKeys: List<String>,
        artistScope: ArtistScope,
        desired: Int,
    ): List<NativeTrack> {
        if (desired <= 0) return emptyList()
        if (artistKeys.isEmpty()) return tracks.take(desired)
        return when (artistScope) {
            ArtistScope.Strict -> tracks
                .filter { track -> artistMatchesAny(track.artist, artistKeys) }
                .take(desired)
            ArtistScope.Focus -> takeArtistFocus(tracks, artistKeys, desired)
            ArtistScope.Similar -> tracks.take(desired)
        }
    }

    /**
     * Focus 允许少量同味艺人，但提交的任意前缀至少保持 70% 目标艺人。
     * 目标艺人不足时宁可返回更短列表触发在线补齐，也不拿无关歌曲凑满。
     */
    private fun takeArtistFocus(
        tracks: List<NativeTrack>,
        artistKeys: List<String>,
        desired: Int,
    ): List<NativeTrack> {
        val primary = tracks.filter { artistMatchesAny(it.artist, artistKeys) }
        if (primary.isEmpty()) return emptyList()
        val rest = tracks.filterNot { artistMatchesAny(it.artist, artistKeys) }
        val requiredPrimary = (
            desired * ARTIST_FOCUS_MIN_NUMERATOR + ARTIST_FOCUS_MIN_DENOMINATOR - 1
            ) / ARTIST_FOCUS_MIN_DENOMINATOR
        val primaryHead = primary.take(requiredPrimary.coerceAtMost(desired))
        val maxRestForRatio = primaryHead.size *
            (ARTIST_FOCUS_MIN_DENOMINATOR - ARTIST_FOCUS_MIN_NUMERATOR) /
            ARTIST_FOCUS_MIN_NUMERATOR
        val restHead = rest.take(minOf(desired - primaryHead.size, maxRestForRatio))
        val extraPrimary = primary
            .drop(primaryHead.size)
            .take(desired - primaryHead.size - restHead.size)
        return primaryHead + restHead + extraPrimary
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

    private fun exactArtistMatchesAny(actualRaw: String, artistKeys: List<String>): Boolean =
        actualRaw.split("/", "&", ",", "、")
            .map(CommandTextSignals::normalizeForMatch)
            .filter { it.isNotBlank() }
            .any { actual -> actual in artistKeys }

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
