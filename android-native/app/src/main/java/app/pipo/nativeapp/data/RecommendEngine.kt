package app.pipo.nativeapp.data

import app.pipo.nativeapp.data.RecommendationLog.HomeGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun recommendationArtistKeys(artist: String): Set<String> =
    artist.split('/', '&', ',', '、').map {
        it.lowercase().replace(Regex("[\\s'\"`·・\\-－—_,，。.、!?！？]+"), "")
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
    ): RecommendationFeed =
        recommend(anchor, excludeIds, wantCount, reportSearchFailure = true, home = true,
            useAi = useAi, excludeSongKeys = excludeSongKeys)

    private suspend fun recommend(
        anchor: NativeTrack?, excludeIds: Set<Long>, wantCount: Int,
        reportSearchFailure: Boolean, home: Boolean, useAi: Boolean = home,
        excludeSongKeys: Set<String> = emptySet(),
    ): RecommendationFeed = withContext(Dispatchers.Default) {
        if (wantCount <= 0) return@withContext RecommendationFeed()
        val lib = library.library()
        val unified = PipoGraph.userTaste.snapshot(lib)
        val taste = unified.profile
        val events = behaviorLog.readAll()
        val recentPlay = behaviorLog.recentPlay()
        val recentRec = recommendationLog.recentContext()
        val delta = unified.behavior
        val rejected = PipoGraph.recommendationFeedbackLog.globallyRejected()
        val now = System.currentTimeMillis()
        val userId = if (home) repository.account.first()?.userId else null
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
            .groupBy { it.trackId }.values.filter { it.size >= 2 }.flatMapTo(HashSet()) { listens ->
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
                .filterValues { it >= 2 }.keys.let(::addAll)
        }
        val homeMix = if (home) homeGroupShares(learningEvents, userId, now) else null
        val anchorKey = anchor?.let(TrackDedupe::songKey)
        val hardExclude: (NativeTrack) -> Boolean = { track ->
            val id = track.neteaseId
            (id != null && id in excludeIds) || (!home && TrackDedupe.songKey(track) == anchorKey) ||
                TrackDedupe.songKey(track) in excludeSongKeys ||
                (!home && id != null && id in recentRec.last24hTrackIds) || rejected.contains(track) ||
                (home && excludedHomeCategory(track)) ||
                track.id in repeatedSkips ||
                (home && unified.explicitScore(track, semanticStore?.get(track.id)) < 0.0) ||
                !FunctionalMusicFilter.acceptsCategory(track, if (home) unified.explicit.filter { it.liked }.joinToString("；") { it.evidence } else anchor?.let { "${it.title} ${it.artist}" }.orEmpty()) ||
                (anchor?.let { FunctionalMusicFilter.isFunctional(it.title, it.artist) } != true &&
                    FunctionalMusicFilter.isFunctional(track.title, track.artist))
        }
        val references = if (anchor != null && (!home || !excludedHomeCategory(anchor))) listOf(anchor)
            else listeningReferences(lib, learningEvents, taste, delta) {
                rejected.contains(it) || it.id in repeatedSkips || (home && excludedHomeCategory(it))
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

        val recentStarted = events.filter { it.type == BehaviorType.PlayStarted && it.tsMs in (now - 86_400_000)..now }
            .mapTo(HashSet()) { it.trackId }
        fun rankCandidates(candidates: Collection<Candidate>): List<Candidate> = candidates.map { candidate ->
            val track = candidate.track
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
                recommendationArtistKeys(track.artist).any { it in familiarArtists } -> HomeGroup.FamiliarArtist
                else -> HomeGroup.NewArtist
            }
            val exposurePenalty = if (home && track.neteaseId in recentRec.last24hTrackIds) 0.20 else 0.0
            val skipped = skipEvents.count { it.trackId == track.id }
            val explicit = if (home) unified.explicitScore(track, semanticStore?.get(track.id)) else 0.0
            val score = explicit * 0.4 + candidate.audioSim * 0.16 + candidate.coListenScore * 0.12 +
                affinity * 0.20 + semantic * 0.14 + love * 0.12 + (if (home) savedScore else candidate.savedScore) * 0.12 +
                behavior * 0.30 + candidate.discoveryScore * 0.16 - recentPenalty - exposurePenalty - skipped * 0.22
            candidate.copy(tasteScore = affinity, semanticScore = semantic, loveScore = love,
                behaviorDeltaScore = behavior, explicitScore = explicit, finalScore = score,
                savedScore = savedScore,
                isDiscovery = !familiarTrack, homeGroup = if (home) group else null)
        }.filter { it.behaviorDeltaScore > -0.32 && it.finalScore > -0.05 &&
            // Personalized service / reference-song / taste-conditioned AI recall supplies relevance
            // for first-time artists with no local history. Ordinary keyword hits (0.10) do not.
            (it.homeGroup != HomeGroup.NewArtist || (it.finalScore >= 0.08 &&
                recommendationArtistKeys(it.track.artist).isNotEmpty() &&
                (it.explicitScore > 0 || it.semanticScore >= 0.2 || it.behaviorDeltaScore >= 0.12 ||
                    it.discoveryScore >= 0.6 || it.audioSim >= 0.7))) &&
            (it.explicitScore > 0 || it.savedScore > 0 || it.tasteScore >= 0.25 || it.semanticScore >= 0.2 ||
                it.behaviorDeltaScore >= 0.12 || it.discoveryScore >= 0.6 || it.audioSim >= 0.7) }
            .sortedWith(compareByDescending<Candidate> { it.finalScore }.thenBy { TrackDedupe.songKey(it.track) })
        // Refill against the final ranked/diverse mixture, not the size of the raw search pool.
        var onlineUnavailable = false
        if (home || pool.size < wantCount * 3) {
            try {
                fetchOnlineCandidates(references, unified, hardExclude, excludeIds,
                    useAi = useAi, savedTracks = lib, home = home, familiarArtists = familiarArtists, hasEnough = { online ->
                        val combined = HashMap(pool)
                        online.forEach { combined.merge(it) }
                        val ranked = rankCandidates(combined.values)
                        val selected = pickDiverse(ranked, wantCount, anchor?.firstArtistKey(), homeMix)
                        selected.size >= wantCount && (!home ||
                            selected.count { it.homeGroup == HomeGroup.NewArtist } >=
                                newArtistTarget(wantCount, requireNotNull(homeMix)))
                    }).forEach { pool.merge(it) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                onlineUnavailable = true
                if (pool.isEmpty() && reportSearchFailure) throw e
            }
        }
        val picks = pickDiverse(rankCandidates(pool.values), wantCount, anchor?.firstArtistKey(), homeMix)
        val tracks = picks.map { it.track }
        if (tracks.isNotEmpty()) recommendationLog.logTracks(tracks,
            if (home) RecommendationLog.Source.Home else RecommendationLog.Source.Radio,
            discoveryIds = if (home) picks.filter { it.isDiscovery }.mapNotNullTo(HashSet()) { it.track.neteaseId } else null,
            userId = userId,
            homeGroups = if (home) picks.mapNotNull { candidate ->
                candidate.track.neteaseId?.let { it to requireNotNull(candidate.homeGroup) }
            }.toMap() else null)
        RecommendationFeed(
            tracks = tracks,
            reasons = picks.associate { it.track.id to recommendationReason(it) },
            hasSignal = true,
            message = when {
                home && onlineUnavailable -> "新歌发现暂时不可用，请稍后重试。"
                home && tracks.isEmpty() -> "暂时没有找到合适的未收藏歌曲，稍后再换一批。"
                home -> "结合你的口味，重温熟悉的喜欢，也听见新的发现。"
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
    private fun pickDiverse(
        ranked: List<Candidate>, wantCount: Int, anchorArtistKey: String?, homeMix: Map<HomeGroup, Double>? = null,
    ): List<Candidate> {
        val picked = ArrayList<Candidate>()
        val artistCount = HashMap<String, Int>()
        val albumCount = HashMap<String, Int>()
        val titleFamilies = HashSet<String>()
        val remaining = ranked.toMutableList()
        val artistLimit = maxOf(2, kotlin.math.ceil(wantCount * 0.12).toInt())
        while (picked.size < wantCount && remaining.isNotEmpty()) {
            val needsUnsaved = homeMix != null && picked.isEmpty()
            val eligible = remaining.filter { candidate ->
                val artist = candidate.track.firstArtistKey()
                val family = TrackDedupe.recommendationTitleKey(candidate.track.title)
                val album = normalizeKey(candidate.track.album)
                (!needsUnsaved || candidate.savedScore == 0.0) &&
                    (artistCount[artist] ?: 0) < (if (candidate.homeGroup == HomeGroup.NewArtist) minOf(2, artistLimit) else artistLimit) &&
                    (family.isBlank() || family !in titleFamilies) &&
                    (album.isBlank() || (albumCount["$artist:$album"] ?: 0) < 2) &&
                    !(artist == anchorArtistKey && picked.size < 4 && (artistCount[artist] ?: 0) >= 1)
            }
            if (eligible.isEmpty()) break
            fun diverseScore(candidate: Candidate): Double {
                val artist = candidate.track.firstArtistKey()
                val sameArtist = (artistCount[artist] ?: 0) * 0.12
                val adjacent = if (picked.lastOrNull()?.track?.firstArtistKey() == artist) 0.25 else 0.0
                val radioDiscovery = if (homeMix == null && candidate.savedScore == 0.0 &&
                    picked.count { it.savedScore == 0.0 }.toDouble() / picked.size.coerceAtLeast(1) < 0.40) 0.10 else 0.0
                return candidate.finalScore - sameArtist - adjacent + radioDiscovery
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
            val artist = selected.track.firstArtistKey()
            artistCount[artist] = (artistCount[artist] ?: 0) + 1
            val album = normalizeKey(selected.track.album)
            if (album.isNotBlank()) albumCount["$artist:$album"] = (albumCount["$artist:$album"] ?: 0) + 1
            val family = TrackDedupe.recommendationTitleKey(selected.track.title)
            if (family.isNotBlank()) titleFamilies.add(family)
        }
        return picked
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
            .groupBy { it.trackId }
        data class Sample(val group: HomeGroup, val value: Double, val strength: Double, val atMs: Long)
        val samples = HashMap<String, Sample>()
        fun addSample(trackId: Long?, atMs: Long, value: Double, strength: Double) {
            if (atMs !in since..now) return
            val prior = recommended[trackId]?.filter { it.tsSec * 1_000 <= atMs &&
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
            addSample(event.neteaseId, event.tsMs, outcome, 1.0)
        }
        recommendationLog.readFavorites().filter { it.userId == userId && it.tsMs in since..now }
            .groupBy { it.trackId }.values.mapNotNull { it.maxByOrNull { event -> event.tsMs } }
            .filter { it.liked }.forEach { addSample(it.trackId, it.tsMs, 1.0, 1.5) }
        val weights = DoubleArray(HomeGroup.entries.size)
        val positives = DoubleArray(HomeGroup.entries.size)
        samples.values.forEach {
            val weight = it.strength * exp(-(now - it.atMs).coerceAtLeast(0) / (14.0 * 86_400_000))
            weights[it.group.ordinal] += weight
            positives[it.group.ordinal] += it.value * weight
        }
        val base = mapOf(HomeGroup.FamiliarTrack to 0.45, HomeGroup.FamiliarArtist to 0.25, HomeGroup.NewArtist to 0.30)
        val adjusted = base.mapValues { (group, prior) ->
            val rate = (positives[group.ordinal] + 4.0) / (weights[group.ordinal] + 8.0)
            prior * exp(1.8 * (rate - 0.5))
        }
        val newShare = (adjusted.getValue(HomeGroup.NewArtist) / adjusted.values.sum()).coerceIn(0.20, 0.45)
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
        candidate.homeGroup == HomeGroup.FamiliarArtist -> "熟悉的音乐人，换一首少听的作品"
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
        excluded: (NativeTrack) -> Boolean, exclusions: Set<Long>, useAi: Boolean,
        savedTracks: List<NativeTrack>, home: Boolean, familiarArtists: Set<String>,
        hasEnough: (List<Candidate>) -> Boolean,
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
            .distinctBy(::normalizeKey).take(if (home) 16 else 4)
            .map { CatalogSeed(it, artist = it, artistScore = 0.50) }
        val tagSeeds = buildOnlineSeeds(references.firstOrNull(), taste, delta)
            .filter { !home || !FunctionalMusicFilter.isReligious(it, "") }.take(3).map { CatalogSeed(it) }
        var successCount = 0
        var failure: Throwable? = null
        val pool = HashMap<String, Candidate>()

        suspend fun searchBatch(batch: List<CatalogSeed>) {
            val results = coroutineScope {
                batch.map { seed -> async {
                    try {
                        val limit = if (seed.suggestion != null) 8 else if (home) 60 else 20
                        val paged = home && seed.suggestion == null
                        val offset = if (paged) synchronized(catalogOffsets) { catalogOffsets[seed.query] ?: 0 } else 0
                        val tracks = if (home) withTimeoutOrNull(5_000L) {
                            if (paged) repository.searchTracksPage(seed.query, limit, offset)
                            else repository.searchTracks(seed.query, limit)
                        } ?: throw IllegalStateException("歌曲搜索响应超时")
                        else repository.searchTracks(seed.query, limit)
                        if (paged) synchronized(catalogOffsets) {
                            // Rotate through the catalog on each batch; end-of-catalog returns to page one.
                            catalogOffsets[seed.query] = if (tracks.size >= limit && offset + limit <= 10_000) offset + limit else 0
                            while (catalogOffsets.size > 80) catalogOffsets.remove(catalogOffsets.keys.first())
                        }
                        seed to Result.success(tracks)
                    }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { seed to Result.failure<List<NativeTrack>>(e) }
                } }.awaitAll()
            }
            for ((seed, result) in results) {
                if (result.isSuccess) successCount++ else if (failure == null) failure = result.exceptionOrNull()
                val candidates = result.getOrDefault(emptyList()).filter { it.neteaseId != null && !excluded(it) &&
                    (!seed.newArtistsOnly || recommendationArtistKeys(it.artist).let { keys ->
                        keys.isNotEmpty() && keys.none { key -> key in familiarArtists }
                    }) }
                val hits = if (seed.suggestion != null) candidates.filter {
                    TrackDedupe.normalizeTitle(it.title) == TrackDedupe.normalizeTitle(seed.suggestion.title) &&
                        it.artist.split('/', '&', ',', '、').any { artist -> normalizeKey(artist) == normalizeKey(seed.suggestion.artist) }
                }.take(1) else if (seed.artist != null) candidates.filter { track ->
                    track.artist.split('/', '&', ',', '、').any { normalizeKey(it) == normalizeKey(seed.artist) }
                } else candidates
                hits.forEach { pool.merge(Candidate(it, tasteScore = seed.artistScore,
                    discoveryScore = when {
                        seed.suggestion != null -> 0.85
                        seed.newArtistsOnly -> 0.65
                        else -> 0.10
                    },
                    source = if (seed.newArtistsOnly) SOURCE_NEW_ARTIST else SOURCE_DISCOVERY)) }
            }
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
                // This source runs even when familiar-artist candidates already fill the batch.
                val suggestionsJob = async {
                    if (useAi) discoveryRecall.suggestions(unified, references, exclusions, savedTracks, familiarArtists)
                    else emptyList()
                }
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
                // These requests feed the same candidate pool and were always all required before
                // AI suggestions were verified. Run them together, but consume awaitAll's input
                // order so source precedence and candidate merge order remain unchanged.
                val results = platformRequests.map { request -> async {
                    try {
                        Result.success(withTimeoutOrNull(5_000L) { request() }
                            ?: throw IllegalStateException("网易云推荐响应超时"))
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { Result.failure<List<NativeTrack>>(e) }
                } }.awaitAll()
                results.forEach { result ->
                    if (result.isSuccess) successCount++ else if (failure == null) failure = result.exceptionOrNull()
                    result.getOrDefault(emptyList()).filter { it.neteaseId != null && !excluded(it) }.forEach {
                        pool.merge(Candidate(it, discoveryScore = 0.75, source = SOURCE_PLATFORM))
                    }
                }
                val suggestions = suggestionsJob.await()
                for (batch in suggestions.map {
                    CatalogSeed("${it.title} ${it.artist}", suggestion = it, newArtistsOnly = true)
                }.chunked(4)) {
                    searchBatch(batch)
                }
                if (hasEnough(pool.values.toList())) return@coroutineScope

                // Expand through taste-matched unfamiliar artists before returning to known artists.
                // This lane also works when AI is unavailable. Identity and all home filters still apply.
                val newArtistAnchors = pool.values.filter { candidate ->
                    candidate.discoveryScore >= 0.6 && recommendationArtistKeys(candidate.track.artist).let { keys ->
                        keys.isNotEmpty() && keys.none { it in familiarArtists }
                    }
                }.sortedWith(compareByDescending<Candidate> { it.discoveryScore }.thenBy { it.track.id })
                    .distinctBy { it.track.firstArtistKey() }.mapNotNull { it.track.neteaseId }.take(3)
                val expanded = newArtistAnchors.map { id -> async {
                    try { withTimeoutOrNull(5_000L) { repository.similarTracks(id) }.orEmpty() }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { emptyList() }
                } }.awaitAll().flatten()
                expanded.filter { it.neteaseId != null && !excluded(it) && recommendationArtistKeys(it.artist).let { keys ->
                    keys.isNotEmpty() && keys.none { key -> key in familiarArtists }
                } }.forEach { pool.merge(Candidate(it, discoveryScore = 0.65, source = SOURCE_NEW_ARTIST)) }
                if (hasEnough(pool.values.toList())) return@coroutineScope
                for (batch in artistSeeds.take(6).chunked(3)) searchBatch(batch)

                // A catalog-verified AI suggestion also opens that musician's other works for exploration.
                val relatedArtists = pool.values.filter { it.discoveryScore >= 0.85 }.map { it.track.firstArtistKey() }.toSet()
                val relatedSeeds = suggestions.filter { normalizeKey(it.artist) in relatedArtists }
                    .map { CatalogSeed(it.artist, artist = it.artist, artistScore = 0.30, newArtistsOnly = true) }
                val extraSeeds = (artistSeeds.drop(6) + relatedSeeds + tagSeeds).distinctBy { normalizeKey(it.query) }
                for (batch in extraSeeds.chunked(3)) {
                    searchBatch(batch)
                    if (hasEnough(pool.values.toList())) return@coroutineScope
                }
                if (home) repeat(2) {
                    for (batch in (artistSeeds + relatedSeeds).distinctBy { normalizeKey(it.query) }.chunked(3)) {
                        searchBatch(batch)
                        if (hasEnough(pool.values.toList())) return@coroutineScope
                    }
                }
            }
            true
        } ?: false
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
    )

    /** 同一首歌从多个 channel 召回 → 各 channel 信号取 max 合并 */
    private fun HashMap<String, Candidate>.merge(c: Candidate) {
        val key = TrackDedupe.songKey(c.track)
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
        s.lowercase().replace(Regex("[\\s'\"`·・\\-－—_,，。.、!?！？]+"), "")

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
        private const val SOURCE_AUDIO = 1
        private const val SOURCE_COLISTEN = 2
        private const val SOURCE_TASTE = 4
        private const val SOURCE_LOVE = 8
        private const val SOURCE_BEHAVIOR_DELTA = 16
        private const val SOURCE_LIBRARY = 32
        private const val SOURCE_DISCOVERY = 64
        private const val SOURCE_PLATFORM = 128
        private const val SOURCE_NEW_ARTIST = 256
    }
}
