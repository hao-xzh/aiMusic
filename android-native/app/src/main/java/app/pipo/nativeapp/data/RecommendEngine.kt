package app.pipo.nativeapp.data

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.RecommendationLog.HomeGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val RECOMMENDATION_ARTIST_SEPARATOR = Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+|[/&,、;；]", RegexOption.IGNORE_CASE)
private val RECOMMENDATION_ARTIST_NORMALIZE = Regex("[\\s'\"`·・\\-－—_,，。.、!?！？]+")

internal fun recommendationArtistKeys(artist: String): Set<String> =
    artist.split(RECOMMENDATION_ARTIST_SEPARATOR).map {
        it.lowercase().replace(RECOMMENDATION_ARTIST_NORMALIZE, "")
    }.filter { it.isNotBlank() }.toSet()

/** A ranked feed snapshot. Reasons describe observed signals, never fabricated song attributes. */
data class RecommendationFeed(
    val tracks: List<NativeTrack> = emptyList(),
    val reasons: Map<String, String> = emptyMap(),
    val message: String = "",
    val hasSignal: Boolean = false,
)

class RecommendEngine(
    private val library: LibraryLoader,
    private val featuresStore: AudioFeaturesStore,
    private val behaviorLog: BehaviorLog,
    private val recommendationLog: RecommendationLog,
    private val repository: PipoRepository,
    private val behaviorPreference: BehaviorPreferenceEngine,
    private val semanticStore: TrackSemanticStore? = null,
) {

    suspend fun fetchMore(
        anchor: NativeTrack?,
        excludeIds: Set<Long>,
        wantCount: Int = 8,
        reportSearchFailure: Boolean = false,
    ): List<NativeTrack> = recommend(anchor, excludeIds, wantCount, reportSearchFailure, home = false).tracks

    suspend fun homeFeed(
        excludeIds: Set<Long>, wantCount: Int = 24,
        anchor: NativeTrack? = null, useAi: Boolean = true,
        excludeSongKeys: Set<String> = emptySet(),
        onProgress: (suspend (RecommendationFeed) -> Unit)? = null,
        requestId: String? = null,
    ): RecommendationFeed =
        recommend(anchor, excludeIds, wantCount, reportSearchFailure = true, home = true,
            useAi = useAi, excludeSongKeys = excludeSongKeys, onProgress = onProgress, requestId = requestId)

    /** Continue the same taste and cooling rules without making playback wait for a discovery quota. */
    suspend fun continueHome(
        excludeIds: Set<Long>, anchor: NativeTrack? = null, wantCount: Int = 12,
        useAi: Boolean = true, excludeSongKeys: Set<String> = emptySet(),
    ): List<NativeTrack> = recommend(
        anchor, excludeIds, wantCount, reportSearchFailure = true, home = true,
        useAi = useAi, excludeSongKeys = excludeSongKeys, requireNewArtistShare = false,
    ).tracks

    private suspend fun recommend(
        anchor: NativeTrack?, excludeIds: Set<Long>, wantCount: Int,
        reportSearchFailure: Boolean, home: Boolean, useAi: Boolean = home,
        excludeSongKeys: Set<String> = emptySet(),
        onProgress: (suspend (RecommendationFeed) -> Unit)? = null,
        requestId: String? = null,
        requireNewArtistShare: Boolean = home,
    ): RecommendationFeed = withContext(Dispatchers.Default) {
        if (wantCount <= 0) return@withContext RecommendationFeed()
        val libraryStartedAt = System.nanoTime()
        val traceId = requestId ?: libraryStartedAt.toString()
        val lib = library.library()
        if (home) DiagnosticsLogStore.record(
            area = "recommendation", event = "home_library_ready",
            fields = mapOf("refreshId" to traceId, "elapsedMs" to (System.nanoTime() - libraryStartedAt) / 1_000_000L,
                "trackCount" to lib.size),
        )
        val unified = PipoGraph.userTaste.snapshot(lib)
        val taste = unified.profile
        val events = behaviorLog.readAll()
        val recentPlay = behaviorLog.recentPlay()
        val userId = if (home) repository.account.first()?.userId else null
        val recentRec = if (home) recommendationLog.recentHomeContext(userId) else recommendationLog.recentContext()
        val recentArtists = if (home) recentRec.last14dArtistCounts.keys else emptySet()
        val restingArtists = if (home) recentRec.last48hArtistCounts.keys +
            recentRec.last7dArtistCounts.filterValues { it >= HOME_WEEKLY_ARTIST_LIMIT }.keys else emptySet()
        val delta = unified.behavior
        val rejected = PipoGraph.recommendationFeedbackLog.globallyRejected()
        val now = System.currentTimeMillis()
        // Older AUTO transitions were not proof of listening. Home learning uses measured outcomes.
        val learningEvents = if (home) events.filter { it.verifiedListeningRatio() != null } else events
        val lastWeek = learningEvents.filter { it.tsMs in (now - 7L * 86_400_000)..now }
        val skipEvents = lastWeek.filter { it.type == BehaviorType.Skipped &&
            (!home || ((it.verifiedListeningRatio() ?: 1.0) < 0.35 && (it.listenedMs ?: 0) >= 1_500)) }
        val repeatedSkips = skipEvents
            .groupingBy { it.trackId }.eachCount().filterValues { it >= 2 }.keys
        val completed = learningEvents.filter { it.type == BehaviorType.Completed }
            .groupingBy { it.trackId }.eachCount()
        val savedKeys = lib.flatMapTo(HashSet()) { TrackDedupe.compatibleKeys(it) }
        val familiarKeys = learningEvents.filter { it.type != BehaviorType.PlayStarted &&
            (it.verifiedListeningRatio() ?: 0.0) >= 0.5 }
            .groupBy { it.trackId }.values.filter { it.size >= if (home) 1 else 2 }.flatMapTo(HashSet()) { listens ->
                val event = listens.last()
                TrackDedupe.compatibleKeys(NativeTrack(event.trackId, event.neteaseId,
                    event.title, event.artist, "", ""))
            }
        val familiarArtists = buildSet {
            lib.forEach { addAll(recommendationArtistKeys(it.artist)) }
            taste?.topArtists.orEmpty().filter { it.affinity > 0 }.forEach { addAll(recommendationArtistKeys(it.name)) }
            unified.explicit.filter { it.liked && it.dimension == "artist" }
                .forEach { addAll(recommendationArtistKeys(it.value)) }
            learningEvents.filter { it.type != BehaviorType.PlayStarted && (it.verifiedListeningRatio() ?: 0.0) >= 0.5 }
                .flatMap { recommendationArtistKeys(it.artist) }.groupingBy { it }.eachCount()
                .filterValues { it >= if (home) 1 else 2 }.keys.let(::addAll)
        }
        val homeMix = if (home) homeGroupShares(learningEvents, userId, now) else null
        val anchorKey = anchor?.let(TrackDedupe::songKey)
        val hardExclude: (NativeTrack) -> Boolean = { track ->
            val id = track.neteaseId
            (id != null && id in excludeIds) || (!home && TrackDedupe.songKey(track) == anchorKey) ||
                TrackDedupe.songKey(track) in excludeSongKeys ||
                // Keep three days of home exposure out, even across providers/versions.
                (home && ((id != null && id in recentRec.last3dTrackIds) ||
                    TrackDedupe.songKey(track) in recentRec.last3dSongKeys ||
                    TrackDedupe.sourceKeys(track).any { it in recentRec.last3dSourceKeys } ||
                    track.recordingId?.let { it in recentRec.last3dRecordingIds } == true)) ||
                (home && recommendationArtistKeys(track.artist).any { it in restingArtists }) ||
                (!home && ((id != null && id in recentRec.last24hTrackIds) ||
                    TrackDedupe.sourceKeys(track).any { it in recentRec.last24hSourceKeys } ||
                    track.recordingId?.let { it in recentRec.last24hRecordingIds } == true)) || rejected.contains(track) ||
                (home && excludedHomeCategory(track)) ||
                track.id in repeatedSkips ||
                (home && unified.explicitScore(track, semanticStore?.get(track.id)) < 0.0) ||
                !FunctionalMusicFilter.acceptsCategory(track, if (home) unified.explicit.filter { it.liked }.joinToString("；") { it.evidence } else anchor?.let { "${it.title} ${it.artist}" }.orEmpty()) ||
                (anchor?.let { FunctionalMusicFilter.isFunctional(it.title, it.artist) } != true &&
                    FunctionalMusicFilter.isFunctional(track.title, track.artist))
        }
        val references = if (anchor != null && (!home || !excludedHomeCategory(anchor))) listOf(anchor)
            else listeningReferences(lib, learningEvents, taste, delta) {
                rejected.contains(it) || it.id in repeatedSkips || (home && (excludedHomeCategory(it) ||
                    recommendationArtistKeys(it.artist).any { key -> key in restingArtists }))
            }
        val hasSignal = unified.explicit.any { it.liked } || lib.isNotEmpty() || references.isNotEmpty() || delta.hasSignal ||
            taste?.let { it.topArtists.isNotEmpty() || it.genres.isNotEmpty() } == true
        if (!hasSignal) return@withContext RecommendationFeed(
            message = "先听几首喜欢的歌，或登录导入收藏，PIPO 才能开始了解你的口味。",
        )

        val pool = HashMap<String, Candidate>()
        val coListenEvents = if (home) learningEvents.filter { (it.verifiedListeningRatio() ?: 0.0) >= 0.5 }
            else learningEvents
        references.take(4).forEach { reference ->
            recallAudio(reference, lib, hardExclude).forEach { pool.merge(it) }
            recallCoListen(reference, lib, coListenEvents, hardExclude).forEach { pool.merge(it) }
        }
        recallTaste(taste, lib, hardExclude).forEach { pool.merge(it) }
        recallLove(learningEvents, lib, recentPlay, hardExclude).forEach { pool.merge(it) }
        recallBehaviorDelta(delta, lib, hardExclude).forEach { pool.merge(it) }
        // A saved library is already positive evidence, even before AI taste analysis exists.
        lib.filterNot(hardExclude).forEach { pool.merge(Candidate(it, savedScore = 0.45, source = SOURCE_LIBRARY)) }
        if (home) DiagnosticsLogStore.record("recommendation", "home_local_candidates_ready", mapOf(
            "refreshId" to traceId, "candidateCount" to pool.size,
            "elapsedMs" to (System.nanoTime() - libraryStartedAt) / 1_000_000L))

        val recentStarted = events.filter { it.type == BehaviorType.PlayStarted && it.tsMs in (now - 86_400_000)..now }
            .mapTo(HashSet()) { it.trackId }
        val skippedCounts = skipEvents.groupingBy { it.trackId }.eachCount()
        // All preference/history inputs above belong to this request. Re-score only
        // candidates whose recall signals changed, not the entire saved library per reply.
        val rankedCache = HashMap<String, Pair<Candidate, Candidate>>()
        suspend fun rankCandidates(candidates: Collection<Candidate>): List<Candidate> = candidates.mapIndexed { index, candidate ->
            if (index % 256 == 0) yield()
            val track = candidate.track
            rankedCache[track.id]?.takeIf { it.first == candidate }?.let { return@mapIndexed it.second }
            val behavior = behaviorPreference.scoreTrack(delta, track)
            val affinity = if (home) maxOf(candidate.tasteScore, artistAffinity(track, taste, references))
                else artistAffinity(track, taste, references)
            val semantic = semanticAffinity(track, taste)
            val listens = completed[track.id] ?: 0
            val love = maxOf(candidate.loveScore, (1.0 - exp(-listens / 3.0)) * 0.75)
            val recentPenalty = when {
                track.id in recentStarted -> 0.42
                track.neteaseId in recentPlay.last7dTrackIds -> 0.08
                else -> 0.0
            }
            val savedScore = if (TrackDedupe.compatibleKeys(track).any { it in savedKeys })
                maxOf(candidate.savedScore, 0.45) else 0.0
            val familiarTrack = savedScore > 0 || TrackDedupe.compatibleKeys(track).any { it in familiarKeys }
            val group = when {
                familiarTrack -> HomeGroup.FamiliarTrack
                recommendationArtistKeys(track.artist).any { it in familiarArtists || it in recentArtists } -> HomeGroup.FamiliarArtist
                else -> HomeGroup.NewArtist
            }
            val skipped = skippedCounts[track.id] ?: 0
            val explicit = if (home) unified.explicitScore(track, semanticStore?.get(track.id)) else 0.0
            val score = explicit * 0.4 + candidate.audioSim * 0.16 + candidate.coListenScore * 0.12 +
                affinity * (if (home) 0.12 else 0.20) + semantic * 0.14 + love * (if (home) 0.08 else 0.12) +
                (if (home) savedScore * 0.06 else candidate.savedScore * 0.12) +
                behavior * 0.30 + candidate.discoveryScore * 0.16 - recentPenalty - skipped * 0.22
            candidate.copy(tasteScore = affinity, semanticScore = semantic, loveScore = love,
                behaviorDeltaScore = behavior, explicitScore = explicit, finalScore = score,
                savedScore = savedScore,
                isDiscovery = !familiarTrack, homeGroup = if (home) group else null)
                .also { rankedCache[track.id] = candidate to it }
        }.filter { it.behaviorDeltaScore > -0.32 && it.finalScore > -0.05 &&
            // Personalized service / reference-song / taste-conditioned AI recall supplies relevance
            // for first-time artists with no local history. Ordinary keyword hits (0.10) do not.
            (it.homeGroup != HomeGroup.NewArtist || (it.finalScore >= 0.08 &&
                recommendationArtistKeys(it.track.artist).isNotEmpty() &&
                (it.explicitScore > 0 || it.semanticScore >= 0.2 || it.behaviorDeltaScore >= 0.12 ||
                    it.discoveryScore >= 0.6 || it.audioSim >= 0.7))) &&
            (it.explicitScore > 0 || it.savedScore > 0 || it.tasteScore >= 0.25 || it.semanticScore >= 0.2 ||
                it.behaviorDeltaScore >= 0.12 || it.discoveryScore >= 0.6 || it.audioSim >= 0.7) }
            .sortedWith(compareByDescending<Candidate> { it.finalScore }.thenBy { it.songKey })
        var published = emptyList<Candidate>()
        fun recordPicks(picks: List<Candidate>) {
            val tracks = picks.map { PipoGraph.music.library.annotate(it.track) }
            if (home) discoveryRecall.markUsed(userId, tracks)
            if (tracks.isNotEmpty()) recommendationLog.logTracks(tracks,
                if (home) RecommendationLog.Source.Home else RecommendationLog.Source.Radio,
                discoveryIds = if (home) picks.filter { it.isDiscovery }.mapNotNullTo(HashSet()) { it.track.neteaseId } else null,
                userId = userId,
                homeGroups = if (home) picks.mapNotNull { candidate ->
                    candidate.track.neteaseId?.let { it to requireNotNull(candidate.homeGroup) }
                }.toMap() else null,
                discoverySourceKeys = if (home) picks.filter { it.isDiscovery }.mapTo(HashSet()) { it.track.sourceKey } else null,
                homeGroupsBySource = if (home) picks.associate { it.track.sourceKey to requireNotNull(it.homeGroup) } else null)
            if (home) picks.forEach { candidate ->
                DiagnosticsLogStore.record("recommendation", "home_track_selected", mapOf(
                    "refreshId" to traceId, "trackId" to candidate.track.id,
                    "sourceKey" to candidate.track.sourceKey, "songKey" to candidate.songKey,
                    "title" to candidate.track.title, "artist" to candidate.track.artist,
                    "group" to candidate.homeGroup?.name, "candidateSources" to candidate.source,
                    "score" to candidate.finalScore, "saved" to (candidate.savedScore > 0),
                    "artistExposure7d" to candidate.artistKeys.maxOfOrNull { recentRec.last7dArtistCounts[it] ?: 0 },
                    "songExposure14d" to (recentRec.last14dSongCounts[candidate.songKey] ?: 0)))
            }
        }
        suspend fun selectedCandidates(online: List<Candidate>): List<Candidate> {
            val startedAt = System.nanoTime()
            val combined = HashMap(pool)
            online.forEach { combined.merge(it) }
            val selected = pickDiverse(rankCandidates(combined.values), wantCount, anchor?.firstArtistKey(),
                homeMix, published, if (home) recentRec else null, requireNewArtistShare)
            if (home) DiagnosticsLogStore.record("recommendation", "home_selection_ready", mapOf(
                "refreshId" to traceId, "candidateCount" to combined.size, "onlineCount" to online.size,
                "selectedCount" to selected.size, "publishedCount" to published.size,
                "newArtistCount" to selected.count { it.homeGroup == HomeGroup.NewArtist },
                "distinctArtistCount" to selected.map { it.artistKey }.toSet().size,
                "elapsedMs" to (System.nanoTime() - startedAt) / 1_000_000L))
            return selected
        }
        suspend fun publishAvailable(online: List<Candidate>) {
            val publish = onProgress ?: return
            val selected = selectedCandidates(online)
            val minimum = if (published.isEmpty()) 1 else published.size + 1
            val maximum = if (published.isEmpty()) minOf(10, wantCount) else selected.size
            // A partial batch must pass the same relevance/diversity filters and the
            // learned new-artist share. A smaller qualified batch may play while the
            // next sources finish; ten is a display target, not another waiting gate.
            val count = (minimum..minOf(maximum, selected.size)).lastOrNull { count ->
                !requireNewArtistShare || selected.take(count).count { it.homeGroup == HomeGroup.NewArtist } >=
                    newArtistTarget(count, requireNotNull(homeMix))
            } ?: return
            val next = selected.take(count)
            recordPicks(next.drop(published.size))
            published = next
            publish(RecommendationFeed(
                tracks = next.map { PipoGraph.music.library.annotate(it.track) },
                reasons = next.associate { it.track.id to recommendationReason(it) },
                hasSignal = true,
            ))
            // If this response already supplied more than ten qualified songs, append
            // its ready tail now instead of waiting for another (possibly slow) source.
            if (next.size < selected.size) publishAvailable(online)
        }
        // Refill against the final ranked/diverse mixture, not the size of the raw search pool.
        var onlineUnavailable = false
        if (home || pool.size < wantCount * 3) {
            try {
                fetchOnlineCandidates(references, unified, hardExclude,
                    useAi = useAi, savedTracks = lib, home = home, familiarArtists = familiarArtists,
                    recentArtists = recentArtists, restingArtists = restingArtists, hasEnough = { online ->
                        val selected = selectedCandidates(online)
                        selected.size >= wantCount && (!home || !requireNewArtistShare ||
                            selected.count { it.homeGroup == HomeGroup.NewArtist } >=
                                newArtistTarget(wantCount, requireNotNull(homeMix)))
                    }, onCandidates = ::publishAvailable, traceId = traceId).forEach { pool.merge(it) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                onlineUnavailable = true
                if (pool.isEmpty() && reportSearchFailure) throw e
            }
        }
        val picks = pickDiverse(rankCandidates(pool.values), wantCount, anchor?.firstArtistKey(),
            homeMix, published, if (home) recentRec else null, requireNewArtistShare)
        val tracks = picks.map { PipoGraph.music.library.annotate(it.track) }
        recordPicks(picks.drop(published.size))
        if (home) DiagnosticsLogStore.record("recommendation", "home_feed_summary", mapOf(
            "refreshId" to traceId, "policyVersion" to 4, "requestedCount" to wantCount,
            "purpose" to if (requireNewArtistShare) "home_feed" else "home_continuation",
            "trackCount" to picks.size, "distinctArtistCount" to picks.map { it.artistKey }.toSet().size,
            "familiarTrackCount" to picks.count { it.homeGroup == HomeGroup.FamiliarTrack },
            "familiarArtistTrackCount" to picks.count { it.homeGroup == HomeGroup.FamiliarArtist },
            "newArtistTrackCount" to picks.count { it.homeGroup == HomeGroup.NewArtist },
            "newArtistTargetShare" to homeMix?.get(HomeGroup.NewArtist),
            "repeatedSongCount14d" to picks.count { it.songKey in recentRec.last14dSongCounts },
            "restingArtistCount" to restingArtists.size))
        RecommendationFeed(
            tracks = tracks,
            reasons = picks.associate { it.track.id to recommendationReason(it) },
            hasSignal = true,
            message = when {
                home && onlineUnavailable -> "新歌发现暂时不可用，请稍后重试。"
                home && tracks.isEmpty() -> "暂时没有找到符合口味的新一批歌曲，稍后再试。"
                home && tracks.size < wantCount -> "先听这 ${tracks.size} 首新推荐，稍后可以再换一批。"
                home -> "延续喜欢的风格，换一些歌曲和音乐人。"
                tracks.isEmpty() -> "这一批暂时没有合适的新歌。再听几首喜欢的，稍后回来看看。"
                onlineUnavailable -> "发现新歌暂时不可用，先为你挑选资料库中合适的音乐。"
                delta.hasSignal && taste != null -> "结合你的长期口味与最近聆听，为你挑选。"
                delta.hasSignal -> "根据你最近听完、重听和跳过的歌曲，持续调整。"
                else -> "从你的收藏和口味出发，兼顾熟悉与新发现。"
            },
        )
    }

    // ============== 召回 ==============

    private fun excludedHomeCategory(track: NativeTrack): Boolean {
        if (FunctionalMusicFilter.isReligious(track)) return true
        val semantic = semanticStore?.get(track.id) ?: return false
        return semantic.sourceLlm && semantic.confidence >= 0.55 &&
            (semantic.genres + semantic.subGenres + semantic.styleAnchors).any { FunctionalMusicFilter.isReligious(it, "") }
    }

    /** ch1: Use feature distance, not the nearly-always-positive cosine of unsigned audio features. */
    private fun recallAudio(
        anchor: NativeTrack?,
        lib: List<NativeTrack>,
        hardExclude: (NativeTrack) -> Boolean,
    ): List<Candidate> {
        if (anchor == null || lib.isEmpty()) return emptyList()
        val anchorFeat = featuresStore.get(anchor.id) ?: return emptyList()
        val anchorVec = anchorFeat.toVector() ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (t in lib) {
            if (t.id == anchor.id || hardExclude(t)) continue
            val f = featuresStore.get(t.id) ?: continue
            val v = f.toVector() ?: continue
            val distance = sqrt(anchorVec.indices.sumOf { i -> (anchorVec[i] - v[i]) * (anchorVec[i] - v[i]) } / anchorVec.size)
            val sim = exp(-distance * 3.0)
            if (sim < 0.65) continue   // 太低就别召
            out.add(Candidate(track = t, audioSim = sim, source = SOURCE_AUDIO))
        }
        out.sortByDescending { it.audioSim }
        return out.take(30)
    }

    /** ch2: 跟 anchor 在历史里"同会话出现"过的歌（Item-CF mini）
     *
     *  会话定义：连续 PlayStarted 之间间隔 < 30min 算同 session。
     *  对每个 session：里面所有歌互相 +1 共现。
     *  返回跟 anchor 共现 ≥ 1 次的 track（按共现次数 + 完成率排序）。
     */
    private fun recallCoListen(
        anchor: NativeTrack?,
        lib: List<NativeTrack>,
        events: List<BehaviorEvent>,
        hardExclude: (NativeTrack) -> Boolean,
    ): List<Candidate> {
        if (anchor == null || events.isEmpty() || lib.isEmpty()) return emptyList()
        val anchorTrackId = anchor.id
        // 按 ts 排序后切 session
        val sorted = events
            .filter { it.type == BehaviorType.PlayStarted || it.type == BehaviorType.Completed ||
                (it.type == BehaviorType.ManualCut && (it.verifiedListeningRatio() ?: 0.0) >= 0.5) }
            .sortedBy { it.tsMs }
        if (sorted.isEmpty()) return emptyList()
        val sessions = ArrayList<MutableSet<String>>()
        var cur = HashSet<String>()
        var lastTs = sorted.first().tsMs
        for (e in sorted) {
            if (e.tsMs - lastTs > 30L * 60 * 1000) {
                if (cur.isNotEmpty()) sessions.add(cur)
                cur = HashSet()
            }
            cur.add(e.trackId)
            lastTs = e.tsMs
        }
        if (cur.isNotEmpty()) sessions.add(cur)

        val co = HashMap<String, Int>()
        for (s in sessions) {
            if (anchorTrackId !in s) continue
            for (id in s) {
                if (id == anchorTrackId) continue
                co[id] = (co[id] ?: 0) + 1
            }
        }
        if (co.isEmpty()) return emptyList()

        val byId = lib.associateBy { it.id }
        val out = ArrayList<Candidate>()
        for ((id, count) in co) {
            val t = byId[id] ?: continue
            if (hardExclude(t)) continue
            // 共现次数归一化到 0..1：N 次共现 → 1 - exp(-N/3)
            val score = (1.0 - exp(-count / 3.0)).coerceIn(0.0, 1.0)
            out.add(Candidate(track = t, coListenScore = score, source = SOURCE_COLISTEN))
        }
        out.sortByDescending { it.coListenScore }
        return out.take(20)
    }

    /** ch3: 跟用户口味画像匹配（仅 artist affinity 主信号）。
     *  之前还做"标题包含 mood/genre 词" 的子串匹配 —— 任何标题里出现 "happy" / "love"
     *  / "sad" 这种常见词的歌都会被误召为 taste 候选。命中率不准、噪声大，去掉。
     *  真正的 mood/genre 召回 应该走 SemanticIndexer 的 vector 召回（见 AgentRuntime 路径）。 */
    private fun recallTaste(
        taste: TasteProfile?,
        lib: List<NativeTrack>,
        hardExclude: (NativeTrack) -> Boolean,
    ): List<Candidate> {
        if (taste == null || lib.isEmpty()) return emptyList()
        val artistAffinity = HashMap<String, Float>().apply {
            taste.topArtists.take(20).forEach { put(normalizeKey(it.name), it.affinity) }
        }
        if (artistAffinity.isEmpty()) return emptyList()

        // affinity × 0.7 → × 0.25(跟 Web/Android 主推流对齐):艺人维度变成"加分项",
        // 修"自由推荐总是同一歌手"。RecommendEngine 是 anchor 续杯路径,影响更直接。
        val out = ArrayList<Candidate>()
        for (t in lib) {
            if (hardExclude(t)) continue
            var score = 0.0
            t.artist.split('/', '&', ',', '、').forEach { a ->
                val key = normalizeKey(a)
                artistAffinity[key]?.let { score += it.toDouble() * 0.25 }
            }
            if (score > 0.0) {
                out.add(Candidate(track = t, tasteScore = score.coerceAtMost(1.0), source = SOURCE_TASTE))
            }
        }
        out.sortByDescending { it.tasteScore }
        return out.take(25)
    }

    /** ch4: "老朋友复活" —— 完成 ≥ 2 次但 7d 没听的歌，给个 love score 拉回来 */
    private fun recallLove(
        events: List<BehaviorEvent>,
        lib: List<NativeTrack>,
        recentPlay: BehaviorLog.RecentPlay,
        hardExclude: (NativeTrack) -> Boolean,
    ): List<Candidate> {
        if (events.isEmpty() || lib.isEmpty()) return emptyList()
        val completedCount = events
            .filter { it.type == BehaviorType.Completed }
            .groupingBy { it.trackId }
            .eachCount()
        val byId = lib.associateBy { it.id }
        val out = ArrayList<Candidate>()
        for ((id, count) in completedCount) {
            if (count < 2) continue
            val t = byId[id] ?: continue
            val ne = t.neteaseId
            // 7 天内听过的就别算"复活"，防止刚听完又被 love 推回来
            if (ne != null && ne in recentPlay.last7dTrackIds) continue
            if (hardExclude(t)) continue
            val score = (1.0 - exp(-count / 4.0)).coerceIn(0.0, 1.0)
            out.add(Candidate(track = t, loveScore = score, source = SOURCE_LOVE))
        }
        out.sortByDescending { it.loveScore }
        return out.take(10)
    }

    /** ch5: 最近行为偏好 delta —— 本地完成/跳过形成的短中期信号。 */
    private fun recallBehaviorDelta(
        delta: BehaviorPreferenceSnapshot,
        lib: List<NativeTrack>,
        hardExclude: (NativeTrack) -> Boolean,
    ): List<Candidate> {
        if (!delta.hasSignal || lib.isEmpty()) return emptyList()
        val out = ArrayList<Candidate>()
        for (t in lib) {
            if (hardExclude(t)) continue
            val score = behaviorPreference.scoreTrack(delta, t)
            if (score > 0.18) {
                out.add(Candidate(track = t, behaviorDeltaScore = score, source = SOURCE_BEHAVIOR_DELTA))
            }
        }
        out.sortByDescending { it.behaviorDeltaScore }
        return out.take(45)
    }

    // ============== 多样性 rerank ==============

    /**
     * 贪心选择，不破坏多样性硬约束：
     *   - 按批次大小限制同 artist，保持专辑与同名版本去重
     *   - 同 songKey 已选 → 跳
     *   - anchor 的 artist 优先级降低（前 3 个里只允许 1 首跟 anchor 同 artist）
     */
    private suspend fun pickDiverse(
        ranked: List<Candidate>, wantCount: Int, anchorArtistKey: String?, homeMix: Map<HomeGroup, Double>? = null,
        prefix: List<Candidate> = emptyList(),
        exposure: RecommendationLog.RecentContext? = null,
        requireNewArtistShare: Boolean = true,
    ): List<Candidate> {
        val picked = ArrayList(prefix)
        val artistCount = HashMap<String, Int>()
        val albumCount = HashMap<String, Int>()
        val titleFamilies = HashSet<String>()
        val prefixKeys = prefix.mapTo(HashSet()) { it.songKey }
        val remaining = ranked.filterNot { it.songKey in prefixKeys }.toMutableList()
        prefix.forEach { candidate ->
            val artist = candidate.artistKey
            val artists = if (homeMix != null) candidate.artistKeys else setOf(artist)
            artists.forEach { artistCount[it] = (artistCount[it] ?: 0) + 1 }
            val album = candidate.albumKey
            if (album.isNotBlank()) albumCount["$artist:$album"] = (albumCount["$artist:$album"] ?: 0) + 1
            val family = candidate.titleFamily
            if (family.isNotBlank()) titleFamilies.add(family)
        }
        val artistLimit = if (homeMix != null) 1 else maxOf(2, kotlin.math.ceil(wantCount * 0.12).toInt())
        while (picked.size < wantCount && remaining.isNotEmpty()) {
            yield()
            val needsUnsaved = homeMix != null && requireNewArtistShare && picked.isEmpty()
            val eligible = remaining.filter { candidate ->
                val artist = candidate.artistKey
                val family = candidate.titleFamily
                val album = candidate.albumKey
                val artists = if (homeMix != null) candidate.artistKeys else setOf(artist)
                (!needsUnsaved || candidate.savedScore == 0.0) &&
                    (homeMix == null || artists.isNotEmpty()) &&
                    artists.all { (artistCount[it] ?: 0) < artistLimit } &&
                    (family.isBlank() || family !in titleFamilies) &&
                    (album.isBlank() || (albumCount["$artist:$album"] ?: 0) < 2) &&
                    !(artist == anchorArtistKey && picked.size < 4 && (artistCount[artist] ?: 0) >= 1)
            }
            if (eligible.isEmpty()) break
            val previousArtist = picked.lastOrNull()?.artistKey
            val needsRadioDiscovery = homeMix == null &&
                picked.count { it.savedScore == 0.0 }.toDouble() / picked.size.coerceAtLeast(1) < 0.40
            fun diverseScore(candidate: Candidate): Double {
                val artist = candidate.artistKey
                val sameArtist = (artistCount[artist] ?: 0) * 0.12
                val adjacent = if (previousArtist == artist) 0.25 else 0.0
                val radioDiscovery = if (needsRadioDiscovery && candidate.savedScore == 0.0) 0.10 else 0.0
                // Apply fatigue after relevance gates: rotate qualified artists without weakening taste matching.
                val recentArtist = candidate.artistKeys.maxOfOrNull { key ->
                    (exposure?.last7dArtistCounts?.get(key) ?: 0) * 0.10 +
                        ln(1.0 + (exposure?.last14dArtistCounts?.get(key) ?: 0)) * 0.04
                } ?: 0.0
                val recentSong = (exposure?.last14dSongCounts?.get(candidate.songKey) ?: 0) * 0.12
                return candidate.finalScore - recentArtist - recentSong - sameArtist - adjacent + radioDiscovery
            }
            val best = eligible.maxBy(::diverseScore)
            val preferred = homeMix?.let { mix ->
                val counts = picked.groupingBy { it.homeGroup }.eachCount()
                val newCount = counts[HomeGroup.NewArtist] ?: 0
                val missingNew = newArtistTarget(wantCount, mix) - newCount
                val newArtistDue = missingNew > 0 &&
                    ((picked.size >= 3 && newCount == 0) || wantCount - picked.size <= missingNew)
                val available = eligible.groupBy { it.homeGroup }
                val group = if (newArtistDue && !available[HomeGroup.NewArtist].isNullOrEmpty()) HomeGroup.NewArtist
                else mix.keys.filter { !available[it].isNullOrEmpty() }.maxByOrNull {
                    (picked.size + 1) * mix.getValue(it) - (counts[it] ?: 0)
                }
                available[group]?.maxByOrNull(::diverseScore)
            }
            // Each group has already passed taste/negative-feedback filters. Reserve real opportunities
            // for new artists instead of requiring them to outscore a familiar artist's history bonus.
            val selected = preferred ?: best
            picked.add(selected)
            remaining.remove(selected)
            val artist = selected.artistKey
            val artists = if (homeMix != null) selected.artistKeys else setOf(artist)
            artists.forEach { artistCount[it] = (artistCount[it] ?: 0) + 1 }
            val album = selected.albumKey
            if (album.isNotBlank()) albumCount["$artist:$album"] = (albumCount["$artist:$album"] ?: 0) + 1
            val family = selected.titleFamily
            if (family.isNotBlank()) titleFamilies.add(family)
        }
        if (homeMix == null || !requireNewArtistShare) return picked
        // An exhausted discovery source must not silently turn the final feed into old favorites.
        // Preserve the already published prefix; it passed this same gate when it was emitted.
        val qualifiedCount = (prefix.size.coerceAtLeast(1)..picked.size).lastOrNull { count ->
            picked.take(count).count { it.homeGroup == HomeGroup.NewArtist } >= newArtistTarget(count, homeMix)
        } ?: prefix.size
        return picked.take(qualifiedCount)
    }

    private fun BehaviorEvent.verifiedListeningRatio(): Double? {
        val duration = durationMs?.takeIf { it > 0 } ?: return null
        val listened = listenedMs?.takeIf { it >= 0 } ?: return null
        return (listened.toDouble() / duration).coerceIn(0.0, 1.0)
    }

    private fun newArtistTarget(count: Int, mix: Map<HomeGroup, Double>): Int =
        (count * mix.getValue(HomeGroup.NewArtist)).roundToInt().coerceIn(1, count.coerceAtLeast(1))

    /** Local, conservative outcome association; an impression alone is never success or failure. */
    private fun homeGroupShares(events: List<BehaviorEvent>, userId: Long?, now: Long): Map<HomeGroup, Double> {
        val since = now - 28L * 86_400_000
        val recommended = recommendationLog.readAll().filter { it.source == RecommendationLog.Source.Home &&
            it.homeGroup != null && it.userId == userId && it.tsSec * 1_000 in since..now }
            .groupBy { it.sourceKey ?: it.trackId?.let { id -> "netease:$id" } }
        data class Sample(val group: HomeGroup, val value: Double, val strength: Double, val atMs: Long)
        val samples = HashMap<String, Sample>()
        fun addSample(sourceKey: String?, atMs: Long, value: Double, strength: Double) {
            if (atMs !in since..now) return
            if (sourceKey == null) return
            val prior = recommended[sourceKey]?.filter { it.tsSec * 1_000 <= atMs &&
                atMs - it.tsSec * 1_000 <= 86_400_000 }?.maxByOrNull { it.tsSec } ?: return
            val key = "${prior.songKey ?: prior.trackId}:${atMs / 86_400_000}"
            val previous = samples[key]
            // Saving is stronger evidence than a play. Repeated plays/taps cannot multiply one song/day.
            if (previous == null || strength > previous.strength ||
                (strength == previous.strength && atMs > previous.atMs)) {
                samples[key] = Sample(requireNotNull(prior.homeGroup), value, strength, atMs)
            }
        }
        for (event in events) {
            if (event.type == BehaviorType.PlayStarted) continue
            val ratio = event.verifiedListeningRatio() ?: continue
            val outcome = when {
                ratio >= 0.8 -> 1.0
                event.type == BehaviorType.Skipped && ratio < 0.35 && (event.listenedMs ?: 0) >= 1_500 -> 0.0
                else -> continue
            }
            addSample(event.neteaseId?.let { "netease:$it" } ?: event.trackId, event.tsMs, outcome, 1.0)
        }
        recommendationLog.readFavorites().filter { it.userId == userId && it.tsMs in since..now }
            .groupBy { it.trackId }.values.mapNotNull { it.maxByOrNull { event -> event.tsMs } }
            .filter { it.liked }.forEach { addSample("netease:${it.trackId}", it.tsMs, 1.0, 1.5) }
        val weights = DoubleArray(HomeGroup.entries.size)
        val positives = DoubleArray(HomeGroup.entries.size)
        samples.values.forEach {
            val weight = it.strength * exp(-(now - it.atMs).coerceAtLeast(0) / (14.0 * 86_400_000))
            weights[it.group.ordinal] += weight
            positives[it.group.ordinal] += it.value * weight
        }
        val base = mapOf(HomeGroup.FamiliarTrack to 0.25, HomeGroup.FamiliarArtist to 0.30, HomeGroup.NewArtist to 0.45)
        val adjusted = base.mapValues { (group, prior) ->
            val rate = (positives[group.ordinal] + 4.0) / (weights[group.ordinal] + 8.0)
            prior * exp(1.8 * (rate - 0.5))
        }
        val newShare = (adjusted.getValue(HomeGroup.NewArtist) / adjusted.values.sum()).coerceIn(0.35, 0.60)
        val familiarTotal = adjusted.getValue(HomeGroup.FamiliarTrack) + adjusted.getValue(HomeGroup.FamiliarArtist)
        return adjusted.mapValues { (group, value) ->
            if (group == HomeGroup.NewArtist) newShare else (1 - newShare) * value / familiarTotal
        }
    }

    // ============== Library and catalog discovery ==============

    private fun listeningReferences(
        lib: List<NativeTrack>, events: List<BehaviorEvent>, taste: TasteProfile?,
        delta: BehaviorPreferenceSnapshot, excluded: (NativeTrack) -> Boolean,
    ): List<NativeTrack> {
        val byId = lib.associateBy { it.id }.toMutableMap()
        val weights = HashMap<String, Double>()
        val now = System.currentTimeMillis()
        for (event in events) {
            val listenedRatio = event.verifiedListeningRatio()
            val signal = when (event.type) {
                BehaviorType.Completed -> 1.0
                BehaviorType.ManualCut -> if (listenedRatio != null) {
                    if (listenedRatio >= 0.8) 0.35 else 0.0
                } else if (event.completionPct >= 0.82f) 0.35 else -0.2
                BehaviorType.Skipped -> if (listenedRatio != null) {
                    when {
                        listenedRatio >= 0.8 -> 0.35
                        listenedRatio < 0.35 && (event.listenedMs ?: 0) >= 1_500 -> -1.2
                        else -> 0.0
                    }
                } else -1.2
                BehaviorType.PlayStarted -> 0.0
            }
            val ageDays = ((now - event.tsMs).coerceAtLeast(0) / 86_400_000.0)
            weights[event.trackId] = (weights[event.trackId] ?: 0.0) + signal * exp(-ageDays / 21.0)
            if (signal > 0) byId.putIfAbsent(event.trackId, NativeTrack(event.trackId, event.neteaseId,
                event.title, event.artist, "", ""))
        }
        val savedIds = lib.mapTo(HashSet()) { it.id }
        val savedArtistCounts = lib.groupingBy { it.firstArtistKey() }.eachCount()
        val ranked = byId.values.filterNot(excluded).map { track ->
            val saved = if (track.id in savedIds) 0.3 else 0.0
            val artist = taste?.topArtists?.firstOrNull { normalizeKey(it.name) == track.firstArtistKey() }?.affinity ?: 0f
            track to ((weights[track.id] ?: 0.0) + artist * 0.8 +
                behaviorPreference.scoreTrack(delta, track) * 0.8 + saved +
                (1 - exp(-(savedArtistCounts[track.firstArtistKey()] ?: 0) / 8.0)) * 0.3)
        }.filter { it.second > 0.1 }.sortedWith(compareByDescending<Pair<NativeTrack, Double>> { it.second }.thenBy { it.first.id })
        val seen = HashSet<String>()
        return ranked.map { it.first }.filter { seen.add(it.firstArtistKey()) }.take(4)
    }

    private fun artistAffinity(track: NativeTrack, taste: TasteProfile?, references: List<NativeTrack>): Double {
        val keys = track.artist.split('/', '&', ',', '、').map(::normalizeKey)
        val longTerm = taste?.topArtists.orEmpty().filter { normalizeKey(it.name) in keys }
            .maxOfOrNull { it.affinity.toDouble() } ?: 0.0
        val reference = if (references.any { it.firstArtistKey() in keys }) 0.65 else 0.0
        return maxOf(longTerm, reference).coerceIn(0.0, 1.0)
    }

    private fun semanticAffinity(track: NativeTrack, taste: TasteProfile?): Double {
        if (taste == null) return 0.0
        val profile = semanticStore?.get(track.id) ?: return 0.0
        // A title containing “pop” or “happy” is not evidence of musical style.
        if (!profile.sourceLlm || profile.confidence < 0.55) return 0.0
        val genres = (profile.genres + profile.subGenres + profile.styleAnchors).map(::normalizeKey).toSet()
        val genre = taste.genres.filter { normalizeKey(it.tag) in genres }.maxOfOrNull { it.weight.toDouble() } ?: 0.0
        val moods = profile.moods.map(::normalizeKey).toSet()
        val mood = if (taste.moods.any { normalizeKey(it) in moods }) 0.5 else 0.0
        return (genre * 0.75 + mood * 0.25) * profile.confidence
    }

    private fun recommendationReason(candidate: Candidate): String = when {
        candidate.homeGroup == HomeGroup.NewArtist -> "按你的口味，探索不常听的音乐人"
        candidate.homeGroup == HomeGroup.FamiliarArtist -> "听过或推荐过的音乐人，换一首作品"
        candidate.explicitScore > 0 -> "符合你明确表达的音乐偏好"
        candidate.loveScore >= 0.45 -> "你曾反复听完，值得再听一次"
        candidate.behaviorDeltaScore >= 0.18 -> "贴近你最近更常听完的音乐"
        candidate.audioSim >= 0.70 -> "音色与节奏接近你常听的歌曲"
        candidate.coListenScore >= 0.35 -> "与你常听的歌曲一起出现过"
        candidate.semanticScore >= 0.25 -> "贴近你的长期音乐风格"
        candidate.tasteScore >= 0.55 && candidate.savedScore == 0.0 -> "来自你常听或偏爱的音乐人"
        candidate.discoveryScore >= 0.6 -> "根据你的口味，探索新的作品"
        candidate.savedScore > 0 -> "从你的资料库中重新发现"
        else -> "贴近你音乐口味的新发现"
    }

    private suspend fun fetchOnlineCandidates(
        references: List<NativeTrack>, unified: UserTasteSnapshot,
        excluded: (NativeTrack) -> Boolean, useAi: Boolean,
        savedTracks: List<NativeTrack>, home: Boolean, familiarArtists: Set<String>,
        recentArtists: Set<String>, restingArtists: Set<String>,
        hasEnough: suspend (List<Candidate>) -> Boolean,
        onCandidates: suspend (List<Candidate>) -> Unit,
        traceId: String,
    ): List<Candidate> {
        val taste = unified.profile
        val delta = unified.behavior
        val artistSeeds = buildList {
            unified.explicit.filter { it.liked && it.dimension == "artist" }.takeLast(4).forEach { add(it.value) }
            references.forEach { add(it.artist) }
            taste?.topArtists.orEmpty().take(12).forEach { add(it.name) }
            if (home) addAll(unified.libraryArtists)
        }.map { it.split('/', '&', ',', '、').first().trim() }
            .filter { it.isNotBlank() && (!home || !FunctionalMusicFilter.isReligious(it, "")) }
            .filter { !home || recommendationArtistKeys(it).none { key -> key in restingArtists } }
            .distinctBy(::normalizeKey).take(if (home) 16 else 4)
            .map { CatalogSeed(it, artist = it, artistScore = 0.50) }
        val tagSeeds = buildOnlineSeeds(if (home) null else references.firstOrNull(), taste, delta)
            .filter { !home || !FunctionalMusicFilter.isReligious(it, "") }.take(3).map { CatalogSeed(it) }
        var successCount = 0
        var failure: Throwable? = null
        val pool = HashMap<String, Candidate>()
        val startedAt = System.nanoTime()
        var awaitedAi = false

        fun acceptsSeed(track: NativeTrack, seed: CatalogSeed): Boolean {
            if (track.musicSource == null || excluded(track)) return false
            if (seed.newArtistsOnly && recommendationArtistKeys(track.artist).let { keys ->
                    keys.isEmpty() || keys.any { it in familiarArtists || it in recentArtists }
                }) return false
            val expectedArtist = seed.suggestion?.artist ?: seed.artist
            if (expectedArtist != null && recommendationArtistKeys(track.artist)
                    .intersect(recommendationArtistKeys(expectedArtist)).isEmpty()) return false
            return seed.suggestion?.let {
                TrackDedupe.normalizeTitle(track.title) == TrackDedupe.normalizeTitle(it.title)
            } ?: true
        }

        suspend fun searchBatch(batch: List<CatalogSeed>) = coroutineScope {
            val results = Channel<Pair<CatalogSeed, Result<List<NativeTrack>>>>(batch.size.coerceAtLeast(1))
            batch.forEach { seed -> launch {
                    val result = try {
                        val limit = if (seed.suggestion != null) 8 else if (home) 60 else 20
                        val paged = home && seed.suggestion == null
                        val preferred = PipoGraph.music.preferredProviderId()
                        val offsetKey = "$preferred:${seed.query}"
                        val offset = if (paged) synchronized(catalogOffsets) { catalogOffsets[offsetKey] ?: 0 } else 0
                        val cachedMatch = seed.suggestion?.let(discoveryRecall::cachedMatch)?.takeIf { cached ->
                            cached.isNotEmpty() && cached.all {
                                it.musicSource?.providerId == preferred && SearchVersionPolicy.accepts(it, seed.query) &&
                                    acceptsSeed(it, seed)
                            }
                        }
                        // Source fallback must see the final identity/eligibility predicate.
                        // Unrelated hits from the preferred provider are not a successful recall.
                        val page = cachedMatch?.let { MusicPage(it) } ?: PipoGraph.music.searchCandidatesPage(seed.query, limit, offset) {
                            acceptsSeed(it, seed)
                        }
                        if (paged) synchronized(catalogOffsets) {
                            // Rotate through the catalog on each batch; end-of-catalog returns to page one.
                            catalogOffsets[offsetKey] = page.nextOffset?.takeIf { it > offset && it <= 10_000 } ?: 0
                            while (catalogOffsets.size > 80) catalogOffsets.remove(catalogOffsets.keys.first())
                        }
                        Result.success(page.items)
                    }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { Result.failure<List<NativeTrack>>(e) }
                    results.send(seed to result)
            } }
            repeat(batch.size) {
                val (seed, result) = results.receive()
                if (result.isSuccess) successCount++ else if (failure == null) failure = result.exceptionOrNull()
                val candidates = result.getOrDefault(emptyList()).filter { acceptsSeed(it, seed) }
                val hits = if (seed.suggestion != null) candidates.take(1) else candidates
                if (seed.suggestion != null && result.isSuccess) {
                    discoveryRecall.rememberMatch(seed.suggestion, hits.firstOrNull())
                }
                hits.forEach { pool.merge(Candidate(it, tasteScore = seed.artistScore,
                    discoveryScore = when {
                        seed.suggestion != null -> 0.85
                        seed.newArtistsOnly -> 0.65
                        else -> 0.10
                    },
                    source = if (seed.newArtistsOnly) SOURCE_NEW_ARTIST else SOURCE_DISCOVERY)) }
                onCandidates(pool.values.toList())
            }
            results.close()
        }

        val expandedAnchorIds = HashSet<Long>()
        suspend fun expandNewArtists() = coroutineScope {
            val anchors = pool.values.filter { candidate ->
                candidate.discoveryScore >= 0.6 && candidate.artistKeys.let { keys ->
                    keys.isNotEmpty() && keys.none { it in familiarArtists || it in recentArtists }
                }
            }.sortedWith(compareByDescending<Candidate> { it.discoveryScore }.thenBy { it.track.id })
                .distinctBy { it.track.firstArtistKey() }.mapNotNull { it.track.neteaseId }
                .filterNot { it in expandedAnchorIds }.take((3 - expandedAnchorIds.size).coerceAtLeast(0))
            if (anchors.isEmpty()) return@coroutineScope
            expandedAnchorIds.addAll(anchors)
            val expanded = anchors.map { id -> async {
                try { withTimeoutOrNull(5_000L) { repository.similarTracks(id) }.orEmpty() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { emptyList() }
            } }.awaitAll().flatten()
            expanded.filter { it.neteaseId != null && !excluded(it) && recommendationArtistKeys(it.artist).let { keys ->
                keys.isNotEmpty() && keys.none { key -> key in familiarArtists || key in recentArtists }
            } }.forEach { pool.merge(Candidate(it, discoveryScore = 0.65, source = SOURCE_NEW_ARTIST)) }
            onCandidates(pool.values.toList())
        }

        val completed = withTimeoutOrNull(30_000L) {
            coroutineScope {
                if (!home) {
                    val seeds = buildList {
                        unified.explicit.filter { it.liked && it.dimension == "artist" }.takeLast(3).forEach { add(CatalogSeed(it.value)) }
                        references.take(3).forEach { add(CatalogSeed(it.artist.split('/', '&', ',').first().trim())) }
                        addAll(tagSeeds)
                    }.distinctBy { it.query }.take(12)
                    for (batch in seeds.chunked(3)) searchBatch(batch)
                    return@coroutineScope
                }
                // AI runs alongside platform recall, but its single in-flight native request
                // belongs to discoveryRecall so a ready feed can stop awaiting it immediately.
                val suggestionsJob = if (useAi) async {
                    discoveryRecall.suggestions(unified, references, savedTracks, familiarArtists, excluded,
                        recentArtists = recentArtists)
                } else null
                // Service recommendations remain the independent, non-AI taste-matched source.
                val platformRequests = buildList<suspend () -> List<NativeTrack>> {
                    if (repository.account.first() != null) {
                        add { repository.dailyRecommendedTracks() }
                        add { repository.personalFmTracks() }
                    }
                    references.mapNotNull { it.neteaseId }.distinct().take(3).forEach { id ->
                        add { repository.similarTracks(id) }
                    }
                }
                // Publish qualified results as each provider finishes. Slow siblings
                // keep running to enrich the tail without blocking the first ten.
                val results = Channel<Result<List<NativeTrack>>>(platformRequests.size.coerceAtLeast(1))
                val platformJobs = platformRequests.map { request -> launch {
                    val result = try {
                        Result.success(withTimeoutOrNull(5_000L) { request() }
                            ?: throw IllegalStateException("网易云推荐响应超时"))
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { Result.failure<List<NativeTrack>>(e) }
                    results.send(result)
                } }
                repeat(platformRequests.size) {
                    val result = results.receive()
                    if (result.isSuccess) successCount++ else if (failure == null) failure = result.exceptionOrNull()
                    result.getOrDefault(emptyList()).filter { it.neteaseId != null && !excluded(it) }.forEach {
                        pool.merge(Candidate(it, discoveryScore = 0.75, source = SOURCE_PLATFORM))
                    }
                    onCandidates(pool.values.toList())
                    if (hasEnough(pool.values.toList())) {
                        platformJobs.forEach { it.cancel() }
                        suggestionsJob?.cancel()
                        return@coroutineScope
                    }
                }
                results.close()
                // hasEnough checks the final ranking, diversity and qualified new-artist target,
                // so a large pool of saved/familiar songs alone cannot bypass discovery.
                if (hasEnough(pool.values.toList())) {
                    suggestionsJob?.cancel()
                    return@coroutineScope
                }
                // Discover through platform-matched new artists while AI is still running.
                // A slow or unavailable model must not consume this independent lane's deadline.
                expandNewArtists()
                if (hasEnough(pool.values.toList())) {
                    suggestionsJob?.cancel()
                    return@coroutineScope
                }
                awaitedAi = suggestionsJob != null
                val suggestions = suggestionsJob?.await().orEmpty()
                for (batch in suggestions.map {
                    CatalogSeed("${it.title} ${it.artist}", suggestion = it, newArtistsOnly = true)
                }.chunked(4)) {
                    searchBatch(batch)
                    if (hasEnough(pool.values.toList())) return@coroutineScope
                }
                if (hasEnough(pool.values.toList())) return@coroutineScope

                if (useAi && suggestions.isNotEmpty()) {
                    val replenished = discoveryRecall.suggestions(unified, references, savedTracks,
                        familiarArtists, excluded, pendingOnly = true, omitted = suggestions.toSet(),
                        recentArtists = recentArtists)
                    for (batch in replenished.map {
                        CatalogSeed("${it.title} ${it.artist}", suggestion = it, newArtistsOnly = true)
                    }.chunked(4)) {
                        searchBatch(batch)
                        if (hasEnough(pool.values.toList())) return@coroutineScope
                    }
                }

                // Use any remaining expansion slots for newly verified AI discoveries.
                expandNewArtists()
                if (hasEnough(pool.values.toList())) return@coroutineScope
                for (batch in artistSeeds.take(6).chunked(3)) {
                    searchBatch(batch)
                    if (hasEnough(pool.values.toList())) return@coroutineScope
                }

                // More works by an already recalled artist cannot fill another artist slot.
                val extraSeeds = (artistSeeds.drop(6) + tagSeeds).distinctBy { normalizeKey(it.query) }
                for (batch in extraSeeds.chunked(3)) {
                    searchBatch(batch)
                    if (hasEnough(pool.values.toList())) return@coroutineScope
                }
            }
            true
        } ?: false
        if (home) DiagnosticsLogStore.record(
            area = "recommendation", event = "home_online_recall_completed",
            fields = mapOf("refreshId" to traceId, "elapsedMs" to (System.nanoTime() - startedAt) / 1_000_000L,
                "candidateCount" to pool.size, "successCount" to successCount,
                "expandedNewArtistCount" to expandedAnchorIds.size,
                "awaitedAi" to awaitedAi, "completed" to completed),
        )
        // Preserve completed search batches when one later request reaches the deadline.
        if (pool.isEmpty()) {
            if (successCount == 0) failure?.let { throw it }
            if (!completed) throw IllegalStateException("推荐服务响应超时，请稍后重试。")
        }
        return pool.values.toList()
    }

    private data class CatalogSeed(
        val query: String, val suggestion: DiscoverySuggestion? = null,
        val artist: String? = null, val artistScore: Double = 0.0,
        val newArtistsOnly: Boolean = false,
    )
    private val discoveryRecall by lazy { AiDiscoveryRecall(repository) }
    private val catalogOffsets = LinkedHashMap<String, Int>()

    private fun buildOnlineSeeds(
        anchor: NativeTrack?,
        taste: TasteProfile?,
        behaviorDelta: BehaviorPreferenceSnapshot,
    ): List<String> {
        val seeds = LinkedHashSet<String>()
        val anchorArtist = anchor?.artist?.split('/', '&', ',')?.firstOrNull()?.trim().orEmpty()
        val genres = (taste?.genres.orEmpty().map { it.tag } + behaviorDelta.genreScores.entries
            .filter { it.value > 0.32 }.sortedByDescending { it.value }.map { it.key }).distinct().take(3)
        genres.forEach { genre -> seeds.add(if (anchorArtist.isNotBlank()) "$anchorArtist $genre" else genre) }
        if (anchorArtist.isNotBlank()) seeds.add(anchorArtist)
        return seeds.toList().take(6)
    }

    // ============== Candidate signal merge ==============

    private data class Candidate(
        val track: NativeTrack,
        val audioSim: Double = 0.0,
        val coListenScore: Double = 0.0,
        val tasteScore: Double = 0.0,
        val loveScore: Double = 0.0,
        val behaviorDeltaScore: Double = 0.0,
        val savedScore: Double = 0.0,
        val semanticScore: Double = 0.0,
        val discoveryScore: Double = 0.0,
        val source: Int = 0,
        val explicitScore: Double = 0.0,
        val finalScore: Double = 0.0,
        val isDiscovery: Boolean = false,
        val homeGroup: HomeGroup? = null,
    ) {
        val songKey: String by lazy(LazyThreadSafetyMode.NONE) { TrackDedupe.songKey(track) }
        val artistKey: String by lazy(LazyThreadSafetyMode.NONE) {
            track.artist.split('/', '&', ',', '、').firstOrNull()?.trim().orEmpty()
                .lowercase().replace(NORMALIZE_KEY_RE, "")
        }
        val artistKeys: Set<String> by lazy(LazyThreadSafetyMode.NONE) { recommendationArtistKeys(track.artist) }
        val albumKey: String by lazy(LazyThreadSafetyMode.NONE) { track.album.lowercase().replace(NORMALIZE_KEY_RE, "") }
        val titleFamily: String by lazy(LazyThreadSafetyMode.NONE) { TrackDedupe.recommendationTitleKey(track.title) }
    }

    /** 同一首歌从多个 channel 召回 → 各 channel 信号取 max 合并 */
    private fun HashMap<String, Candidate>.merge(c: Candidate) {
        val key = c.songKey
        val existing = this[key]
        if (existing == null) {
            this[key] = c
        } else {
            this[key] = existing.copy(
                audioSim = maxOf(existing.audioSim, c.audioSim),
                coListenScore = maxOf(existing.coListenScore, c.coListenScore),
                tasteScore = maxOf(existing.tasteScore, c.tasteScore),
                loveScore = maxOf(existing.loveScore, c.loveScore),
                behaviorDeltaScore = maxOf(existing.behaviorDeltaScore, c.behaviorDeltaScore),
                savedScore = maxOf(existing.savedScore, c.savedScore),
                discoveryScore = maxOf(existing.discoveryScore, c.discoveryScore),
                source = existing.source or c.source,
            )
        }
    }

    private fun NativeTrack.firstArtistKey(): String =
        normalizeKey(artist.split('/', '&', ',', '、').firstOrNull()?.trim().orEmpty())

    private fun normalizeKey(s: String): String =
        s.lowercase().replace(NORMALIZE_KEY_RE, "")

    /**
     * AudioFeatures → 4 维归一化向量 (BPM 0..1, energy 0..1, centroid 0..1, dynamic_range 0..1)。
     * 命中条件：BPM 必须有；其他维缺时填 anchor 平均位
     */
    private fun AudioFeatures.toVector(): DoubleArray? {
        val bpm = bpm ?: return null
        if (bpmConfidence < 0.35) return null
        // BPM 60-180 映到 0..1
        val bpmN = ((bpm - 60.0) / 120.0).coerceIn(0.0, 1.0)
        // rmsDb 一般 -30 .. 0 → 越接近 0 越响 → 映到 0..1
        val energyN = ((rmsDb + 30.0) / 30.0).coerceIn(0.0, 1.0)
        // centroid 200-6000 Hz → 越大越亮 → 映到 0..1
        val centroidN = ((spectralCentroidHz - 200.0) / 5800.0).coerceIn(0.0, 1.0)
        // dynamic range 0-25dB → 越大越动态 → 映到 0..1
        val drN = (dynamicRangeDb / 25.0).coerceIn(0.0, 1.0)
        return doubleArrayOf(bpmN, energyN, centroidN, drN)
    }

    companion object {
        private val NORMALIZE_KEY_RE = Regex("[\\s'\"`·・\\-－—_,，。.、!?！？]+")
        private const val SOURCE_AUDIO = 1
        private const val SOURCE_COLISTEN = 2
        private const val SOURCE_TASTE = 4
        private const val SOURCE_LOVE = 8
        private const val SOURCE_BEHAVIOR_DELTA = 16
        private const val SOURCE_LIBRARY = 32
        private const val SOURCE_DISCOVERY = 64
        private const val SOURCE_PLATFORM = 128
        private const val SOURCE_NEW_ARTIST = 256
        private const val HOME_WEEKLY_ARTIST_LIMIT = 3
    }
}
