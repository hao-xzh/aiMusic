import app.pipo.nativeapp.data.AudioFeatures
import app.pipo.nativeapp.data.CandidateRecall
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PetPersona
import app.pipo.nativeapp.data.PipoPlaylist
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.TrackLanguage
import app.pipo.nativeapp.data.TrackRegion
import app.pipo.nativeapp.data.TrackSemanticProfile
import app.pipo.nativeapp.data.agent.domain.AgentTurnInput
import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.MusicSelectionMode
import app.pipo.nativeapp.data.agent.domain.MusicStyleProfile
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.resolve.MusicResolver
import app.pipo.nativeapp.data.agent.resolve.ResolutionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy

/**
 * Production MusicResolver regression probe. The resolver is real: only its repository boundary
 * is a controlled local Proxy, so onlineBackfill and primary-track injection remain in the path.
 * The existing runner may call this function and report every non-null second value as a failure.
 */
fun productionResolverReliability(): List<Pair<String, String?>> = runBlocking {
    val results = mutableListOf<Pair<String, String?>>()
    val candidates = resolverFixtureTracks()
    val repository = resolverFixtureRepository(candidates)
    fun productionResolver(localTracks: List<NativeTrack>) = MusicResolver(
        repository = repository,
        loadLocalTracks = { localTracks },
        recallCandidates = { _, tracks, _, _ ->
            tracks.map(::controlledCandidate)
        },
    )
    val resolver = productionResolver(emptyList())

    fun verify(id: String, expected: String, actual: () -> Any?, passes: (Any?) -> Boolean) {
        val outcome = runCatching(actual)
        val value = outcome.getOrElse { error ->
            results += id to "$expected; resolver threw ${error::class.java.simpleName}: ${error.message.orEmpty()}"
            return
        }
        if (!passes(value)) results += id to "$expected; actual=$value" else results += id to null
    }

    suspend fun resolve(plan: MusicTurnPlan): ResolutionResult = resolver.resolve(
        plan = plan,
        input = AgentTurnInput(
            userText = plan.userText,
            history = emptyList(),
            currentTrack = null,
            currentQueue = emptyList(),
            userFacts = "",
            persona = PetPersona.FRIENDLY,
        ),
    )

    suspend fun resolveOrReport(id: String, plan: MusicTurnPlan): ResolutionResult? =
        runCatching { resolve(plan) }.getOrElse { error ->
            results += id to "production resolver threw ${error::class.java.simpleName}: ${error.message.orEmpty()}"
            null
        }

    val quietText = "来一点安静的中文歌，第一首要易烊千玺的粉雾海"
    val quietGoal = MusicGoal(
        primaryTracks = listOf(TrackRequirement(title = "粉雾海", artist = "易烊千玺")),
        hardLanguages = listOf("mandarin"),
        selectionMode = MusicSelectionMode.OpenRecommendation,
        styleProfile = MusicStyleProfile(
            semanticQuery = "安静 中文歌",
            energy = "low",
            moods = listOf("calm"),
            languages = listOf("mandarin"),
        ),
    )
    val quietResolution = resolveOrReport("quiet_primary_resolve",
        MusicTurnPlan(
            turnId = "production-resolver-quiet-primary",
            userText = quietText,
            plannerRaw = "tool_loop",
            actions = listOf(
                PlannedAction.PlayRequest(
                    actionId = "quiet-primary",
                    mode = PlayMode.ReplaceQueue,
                    primaryGoal = quietGoal,
                    desiredCount = 12,
                ),
            ),
        ),
    )
    val quietTracks = quietResolution?.firstPlayTracks()
    verify("resolver_fixture_has_six_distinct_artists", "fixture has at least six distinct artists", { candidates.map { it.artist }.distinct().size }) { it as Int >= 6 }
    verify("quiet_primary_replace_mode", "ReplaceQueue is preserved", { quietTracks?.mode }) { it == PlayMode.ReplaceQueue }
    verify("quiet_primary_first_track", "first track is 易烊千玺 - 粉雾海", { quietTracks?.tracks?.firstOrNull()?.artist to quietTracks?.tracks?.firstOrNull()?.title }) {
        it == ("易烊千玺" to "粉雾海")
    }
    verify("quiet_primary_returns_group", "primary request returns more than one track", { quietTracks?.tracks?.size ?: 0 }) { it as Int > 1 }
    verify("quiet_primary_respects_cap", "ReplaceQueue result does not exceed desiredCount=12", { quietTracks?.tracks?.size ?: 0 }) { it as Int in 1..12 }

    // Query-dependent catalog: searching the opening artist returns a rich solo
    // catalog, whereas the group query returns different quiet-song artists.
    // A uniform catalog fixture would hide the opening artist leaking into recall.
    val soloCatalog = listOf(candidates.first()) + (1..20).map {
        fixtureTrack(2000L + it, "首曲歌手的其他歌$it", "易烊千玺")
    }
    val scopedRepository = resolverFixtureRepository(candidates) { query ->
        if (query.contains("易烊千玺") || query.contains("粉雾海")) soloCatalog else candidates.drop(1)
    }
    val scopedResolver = MusicResolver(scopedRepository, loadLocalTracks = { emptyList() },
        recallCandidates = { _, tracks, _, _ -> tracks.map(::controlledCandidate) })
    val separated = scopedResolver.resolve(
        MusicTurnPlan(turnId = "opening-scope", userText = quietText, plannerRaw = "tool_loop",
            actions = listOf(PlannedAction.PlayRequest(actionId = "opening-scope", mode = PlayMode.ReplaceQueue,
                primaryGoal = quietGoal, desiredCount = 12))),
        AgentTurnInput(userText = quietText, history = emptyList(), currentTrack = null,
            currentQueue = emptyList(), userFacts = "", persona = PetPersona.FRIENDLY),
    ).firstPlayTracks()?.tracks.orEmpty()
    verify("opening_artist_does_not_fill_group", "keep the required head and recall the tail from group conditions",
        { separated.map { it.title to it.artist } }) {
        separated.firstOrNull()?.title == "粉雾海" && separated.size > 1 &&
            separated.drop(1).all { it.artist != "易烊千玺" }
    }

    val rnbResolution = resolveOrReport("rnb_group_resolve",
        MusicTurnPlan(
            turnId = "production-resolver-rnb-group",
            userText = "来一组夜晚听的 R&B",
            plannerRaw = "tool_loop",
            actions = listOf(
                PlannedAction.PlayRequest(
                    actionId = "rnb-group",
                    mode = PlayMode.ReplaceQueue,
                    primaryGoal = MusicGoal(
                        selectionMode = MusicSelectionMode.OpenRecommendation,
                        styleProfile = MusicStyleProfile(
                            semanticQuery = "夜晚 R&B",
                            genres = listOf("r&b"),
                            moods = listOf("chill"),
                        ),
                    ),
                    desiredCount = 6,
                ),
            ),
        ),
    )
    val rnbTracks = rnbResolution?.firstPlayTracks()
    verify("rnb_group_replace_mode", "R&B group preserves ReplaceQueue", { rnbTracks?.mode }) { it == PlayMode.ReplaceQueue }
    verify("rnb_group_not_single_track", "R&B group returns more than one track", { rnbTracks?.tracks?.size ?: 0 }) { it as Int > 1 }

    val exactResolution = resolveOrReport("exact_track_resolve",
        MusicTurnPlan(
            turnId = "production-resolver-exact-single",
            userText = "播放易烊千玺的粉雾海",
            plannerRaw = "tool_loop",
            actions = listOf(
                PlannedAction.PlayRequest(
                    actionId = "exact-single",
                    mode = PlayMode.PlayNow,
                    target = TrackRequirement(title = "粉雾海", artist = "易烊千玺"),
                    primaryGoal = MusicGoal(selectionMode = MusicSelectionMode.ExactTrack),
                    desiredCount = 1,
                ),
            ),
        ),
    )
    val exactTracks = exactResolution?.firstPlayTracks()
    verify("exact_track_stays_single", "explicit ExactTrack returns exactly one track", { exactTracks?.tracks?.size ?: 0 }) { it == 1 }
    verify("exact_track_identity", "explicit ExactTrack resolves 易烊千玺 - 粉雾海", { exactTracks?.tracks?.firstOrNull()?.artist to exactTracks?.tracks?.firstOrNull()?.title }) {
        it == ("易烊千玺" to "粉雾海")
    }

    val missingRequirement = TrackRequirement(title = "不存在的粉雾海版本", artist = "易烊千玺")
    val missingResolution = resolveOrReport("missing_primary_resolve",
        MusicTurnPlan(
            turnId = "production-resolver-missing-primary",
            userText = "来一点安静的中文歌，第一首要易烊千玺的不存在的粉雾海版本",
            plannerRaw = "tool_loop",
            actions = listOf(
                PlannedAction.PlayRequest(
                    actionId = "missing-primary",
                    mode = PlayMode.ReplaceQueue,
                    primaryGoal = quietGoal.copy(primaryTracks = listOf(missingRequirement)),
                    desiredCount = 12,
                ),
            ),
        ),
    )
    val missingTracks = missingResolution?.firstPlayTracks()
    verify("missing_primary_requirement_survives", "unresolved primary requirement remains on PlayTracks.primaryGoal", {
        missingTracks?.primaryGoal?.primaryTracks.orEmpty()
    }) { it == listOf(missingRequirement) }
    verify("missing_primary_is_reported", "missingRequirements identifies the unresolved first track", {
        missingResolution?.missingRequirements.orEmpty()
    }) { missing ->
        (missing as List<*>).any { it.toString().contains(missingRequirement.artist.orEmpty()) && it.toString().contains(missingRequirement.title) }
    }

    val localOnlyCandidates = candidates.map { track ->
        track.copy(id = "local-${track.id}", neteaseId = track.neteaseId?.plus(10_000), artist = "易烊千玺")
    }
    val localOnlyRepository = resolverFixtureRepository(localOnlyCandidates)
    val localOnlyResolver = MusicResolver(
        repository = localOnlyRepository,
        loadLocalTracks = { listOf(localOnlyCandidates.first()) },
        recallCandidates = { _, tracks, _, _ ->
            tracks.map(::controlledCandidate)
        },
    )
    val localOnlyPlan = MusicTurnPlan(
        turnId = "production-resolver-local-one-online-backfill",
        userText = "来一组易烊千玺的歌",
        plannerRaw = "tool_loop",
        actions = listOf(
            PlannedAction.PlayRequest(
                actionId = "local-one-online-backfill",
                mode = PlayMode.ReplaceQueue,
                primaryGoal = MusicGoal(
                    primaryArtists = listOf("易烊千玺"),
                    artistScope = ArtistScope.Strict,
                    selectionMode = MusicSelectionMode.ArtistFocus,
                ),
                desiredCount = 6,
            ),
        ),
    )
    val localOnlyResolution = runCatching {
        localOnlyResolver.resolve(
            plan = localOnlyPlan,
            input = AgentTurnInput(
                userText = localOnlyPlan.userText,
                history = emptyList(),
                currentTrack = null,
                currentQueue = emptyList(),
                userFacts = "",
                persona = PetPersona.FRIENDLY,
            ),
        )
    }.getOrElse { error ->
        results += "local_one_online_backfill_resolve" to "production resolver threw ${error::class.java.simpleName}: ${error.message.orEmpty()}"
        null
    }
    verify("local_one_online_backfill_group", "one local result is backfilled to an online group of at least six", {
        localOnlyResolution?.firstPlayTracks()?.tracks?.size ?: 0
    }) { it as Int >= 6 }

    results
}

private fun ResolutionResult.firstPlayTracks(): PlannedAction.PlayTracks? =
    plan.actions.filterIsInstance<PlannedAction.PlayTracks>().firstOrNull()

private fun controlledCandidate(track: NativeTrack): CandidateRecall.Candidate = CandidateRecall.Candidate(
    track = track,
    features = AudioFeatures(
        trackId = track.neteaseId ?: 0L,
        durationS = 180.0,
        bpm = 76.0,
        bpmConfidence = 1.0,
        rmsDb = -12.0,
        peakDb = -1.0,
        dynamicRangeDb = 9.0,
        introEnergy = 0.2,
        outroEnergy = 0.2,
        spectralCentroidHz = 1_200.0,
        headSilenceS = 0.0,
        tailSilenceS = 0.0,
    ),
    semanticProfile = TrackSemanticProfile(
        trackId = track.id,
        title = track.title,
        artists = listOf(track.artist),
        album = track.album,
        language = TrackLanguage.Mandarin,
        languageConfidence = 1.0,
        region = TrackRegion.Chinese,
        regionConfidence = 1.0,
        genres = listOf("r&b"),
        moods = listOf("calm", "chill"),
        scenes = listOf("night"),
        textures = listOf("soft"),
        energy = 0.2,
    ),
    sources = mutableListOf(CandidateRecall.Source.Text),
    sourceScores = mutableMapOf(CandidateRecall.Source.Text to 1.0),
)

private fun resolverFixtureTracks(): List<NativeTrack> = listOf(
    fixtureTrack(1001, "粉雾海", "易烊千玺"),
    fixtureTrack(1002, "夜航", "方大同"),
    fixtureTrack(1003, "慢热", "刘思鉴"),
    fixtureTrack(1004, "月亮与六便士", "陶喆"),
    fixtureTrack(1005, "温柔的风", "王菲"),
    fixtureTrack(1006, "静静听", "陈粒"),
    fixtureTrack(1007, "晚安", "曹格"),
)

private fun fixtureTrack(id: Long, title: String, artist: String) = NativeTrack(
    id = id.toString(),
    neteaseId = id,
    title = title,
    artist = artist,
    album = "Resolver 本地回归夹具",
    streamUrl = "fixture://$id",
    durationMs = 180_000L,
)

private fun resolverFixtureRepository(
    candidates: List<NativeTrack>,
    search: ((String) -> List<NativeTrack>)? = null,
): PipoRepository =
    Proxy.newProxyInstance(
        PipoRepository::class.java.classLoader,
        arrayOf(PipoRepository::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "getPlaylists" -> MutableStateFlow<List<PipoPlaylist>>(emptyList())
            "refreshAccount", "refreshPlaylists" -> Unit
            "cloudDiskTracks" -> emptyList<NativeTrack>()
            "searchTracks" -> {
                val query = arguments.orEmpty().firstOrNull()?.toString().orEmpty()
                if (search != null) search(query)
                else if (query.contains("不存在的粉雾海版本")) candidates.filterNot { it.title == "粉雾海" }
                else candidates
            }
            "toString" -> "ProductionResolverReliabilityRepository"
            "hashCode" -> System.identityHashCode(candidates)
            "equals" -> false
            else -> error("ProductionResolverReliability unexpected PipoRepository call: ${method.name}")
        }
    } as PipoRepository
