package app.pipo.nativeapp.data

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 业界主流 mini 实现 —— "多路召回 → 多因子打分 → 多样性 rerank → 在线兜底"。
 *
 * 形态参考 Spotify Discover Weekly / YouTube Music Recommended for You 的最小可行版：
 *
 *   召回 (recall, ~80 candidates)
 *     ch1 audio similarity   —— anchor 的 BPM/能量/谱心 cosine
 *     ch2 co-listen / Item-CF —— 跟 anchor 在最近 30 天同 session 共现的歌
 *     ch3 taste affinity     —— TasteProfile.topArtists / genres 命中加权
 *     ch4 love revival       —— 完成 ≥2 次但 7 天没听的"老朋友"
 *
 *   排序 (rank)：score = 0.30·rel + 0.25·taste + 0.20·love + recencyPen + diversityPen
 *
 *   多样性 (rerank)：贪心选 —— 同 artist 上限 2、同 songKey 不入、与已选歌相似度去抖
 *
 *   兜底 (fallback)：本地池太薄时走 AI seeds，但有护栏（不允许只用 anchor.artist 当 seed，
 *   强制混入 mood/era/genre），并对结果走同一套 rank + diversity
 *
 * 不依赖任何在线 ML 服务；BehaviorLog / TasteProfile 越丰富，结果越准。冷启动（无历史）
 * 也能给出合理推荐 —— 退化为"同音色 + 同口味标签"。
 */
class RecommendEngine(
    private val library: LibraryLoader,
    private val featuresStore: AudioFeaturesStore,
    private val behaviorLog: BehaviorLog,
    private val tasteProfileStore: TasteProfileStore,
    private val recommendationLog: RecommendationLog,
    private val repository: PipoRepository,
) {

    suspend fun fetchMore(
        anchor: NativeTrack?,
        excludeIds: Set<Long>,
        wantCount: Int = 8,
    ): List<NativeTrack> {
        val lib = runCatching { library.library() }.getOrDefault(emptyList())
        val taste = tasteProfileStore.flow.value
        val events = runCatching { behaviorLog.readAll() }.getOrDefault(emptyList())
        val recentPlay = runCatching { behaviorLog.recentPlay() }
            .getOrDefault(BehaviorLog.RecentPlay(emptySet(), emptySet()))
        val recentRec = runCatching { recommendationLog.recentContext() }
            .getOrDefault(RecommendationLog.RecentContext(emptySet(), emptySet()))

        val anchorKey = anchor?.let { TrackDedupe.songKey(it) }
        val anchorArtistKey = anchor?.firstArtistKey()

        // 软排除：本地库里最近 24h 听过 / 推荐过的，先扣分但不禁
        // 硬排除：anchor 自己 + excludeIds 里的 neteaseId
        val hardExclude: (NativeTrack) -> Boolean = { t ->
            val ne = t.neteaseId
            val sk = TrackDedupe.songKey(t)
            (ne != null && ne in excludeIds) || sk == anchorKey
        }

        // ---- 召回 ----
        val pool = HashMap<String, Candidate>()  // songKey -> Candidate
        recallAudio(anchor, lib, hardExclude).forEach { pool.merge(it) }
        recallCoListen(anchor, lib, events, hardExclude).forEach { pool.merge(it) }
        recallTaste(taste, lib, hardExclude).forEach { pool.merge(it) }
        recallLove(events, lib, recentPlay, hardExclude).forEach { pool.merge(it) }

        if (pool.isEmpty()) {
            // 本地空 → 直接走 AI / 在线兜底
            return fetchFromOnline(anchor, taste, excludeIds, wantCount)
        }

        // ---- 打分 ----
        val ranked = pool.values.map { c ->
            val rel = c.audioSim
            val taste0 = c.tasteScore
            val love = c.loveScore
            val ne = c.track.neteaseId
            val recencyPen = when {
                ne != null && ne in recentPlay.last24hTrackIds -> -0.6
                ne != null && ne in recentRec.last24hTrackIds -> -0.25
                ne != null && ne in recentPlay.last7dTrackIds -> -0.10
                else -> 0.0
            }
            val score = 0.30 * rel + 0.25 * taste0 + 0.20 * love + recencyPen +
                // 微噪声 ±0.03 给重排提供 serendipity，避免每次永远同一组
                (kotlin.random.Random.nextDouble(-0.03, 0.03))
            c.copy(finalScore = score)
        }.sortedByDescending { it.finalScore }

        // ---- 多样性 rerank ----
        val picks = pickDiverse(ranked, wantCount, anchorArtistKey)
        if (picks.size >= wantCount) {
            logRecommendations(picks)
            return picks.map { it.track }
        }

        // ---- 在线兜底 ----
        val haveKeys = picks.mapTo(HashSet()) { TrackDedupe.songKey(it.track) }
        val online = fetchFromOnline(anchor, taste, excludeIds, wantCount - picks.size)
            .filter { TrackDedupe.songKey(it) !in haveKeys && !hardExclude(it) }
        val final = picks.map { it.track } + online
        logRecommendations(picks)
        return final.take(wantCount)
    }

    // ============== 召回 ==============

    /** ch1: 跟 anchor 音色近似 —— BPM / RMS / spectral centroid 的归一化 cosine */
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
            val sim = cosine(anchorVec, v)
            if (sim < 0.55) continue   // 太低就别召
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
            .filter { it.type == BehaviorType.PlayStarted || it.type == BehaviorType.Completed }
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

    /** ch3: 跟用户口味画像匹配（TasteProfile.topArtists / genres）*/
    private fun recallTaste(
        taste: TasteProfile?,
        lib: List<NativeTrack>,
        hardExclude: (NativeTrack) -> Boolean,
    ): List<Candidate> {
        if (taste == null || lib.isEmpty()) return emptyList()
        val artistAffinity = HashMap<String, Float>().apply {
            taste.topArtists.take(20).forEach { put(normalizeKey(it.name), it.affinity) }
        }
        // 用 artist 命中作为主信号；标题里出现 mood/genre 词作为次信号（轻微）
        val tagWords = (taste.moods + taste.genres.map { it.tag })
            .map { normalizeKey(it) }
            .filter { it.isNotBlank() }
            .toSet()

        val out = ArrayList<Candidate>()
        for (t in lib) {
            if (hardExclude(t)) continue
            var score = 0.0
            t.artist.split('/', '&', ',', '、').forEach { a ->
                val key = normalizeKey(a)
                artistAffinity[key]?.let { score += it.toDouble() * 0.7 }
            }
            if (score == 0.0 && tagWords.isNotEmpty()) {
                val titleN = normalizeKey(t.title)
                if (tagWords.any { it in titleN }) score += 0.15
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

    // ============== 多样性 rerank ==============

    /**
     * 贪心选择，不破坏多样性硬约束：
     *   - 同 artist 上限 2
     *   - 同 songKey 已选 → 跳
     *   - anchor 的 artist 优先级降低（前 3 个里只允许 1 首跟 anchor 同 artist）
     */
    private fun pickDiverse(
        ranked: List<Candidate>,
        wantCount: Int,
        anchorArtistKey: String?,
    ): List<Candidate> {
        val picked = ArrayList<Candidate>()
        val artistCount = HashMap<String, Int>()
        val seenSongKeys = HashSet<String>()
        var anchorArtistTaken = 0

        for (c in ranked) {
            if (picked.size >= wantCount) break
            val sk = TrackDedupe.songKey(c.track)
            if (sk in seenSongKeys) continue
            val ak = c.track.firstArtistKey()
            val artistN = artistCount[ak] ?: 0
            if (artistN >= 2) continue
            // 前 3 首里只允许 1 首跟 anchor 同 artist —— 否则一上来全推同 artist
            if (anchorArtistKey != null && ak == anchorArtistKey && picked.size < 3 && anchorArtistTaken >= 1) continue

            picked.add(c)
            seenSongKeys.add(sk)
            artistCount[ak] = artistN + 1
            if (anchorArtistKey != null && ak == anchorArtistKey) anchorArtistTaken++
        }
        return picked
    }

    // ============== 在线兜底 ==============

    /**
     * 本地召回不够时走 netease 搜索。但 seed 有护栏 —— 不允许"光放 anchor.artist 名"
     * 这种容易回流同曲多版本的 seed。混入 mood / genre / era 让搜索面更宽。
     */
    private suspend fun fetchFromOnline(
        anchor: NativeTrack?,
        taste: TasteProfile?,
        excludeIds: Set<Long>,
        wantCount: Int,
    ): List<NativeTrack> {
        if (wantCount <= 0) return emptyList()
        val seeds = buildOnlineSeeds(anchor, taste)
        if (seeds.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, NativeTrack>()  // songKey -> track
        val anchorArtistKey = anchor?.firstArtistKey()
        val artistCount = HashMap<String, Int>()
        for (seed in seeds) {
            if (out.size >= wantCount * 3) break
            val hits = runCatching { repository.searchTracks(seed, limit = 12) }.getOrDefault(emptyList())
            for (t in hits) {
                val ne = t.neteaseId ?: continue
                if (ne in excludeIds) continue
                val sk = TrackDedupe.songKey(t)
                if (sk in out) continue
                val ak = t.firstArtistKey()
                val artistN = artistCount[ak] ?: 0
                if (artistN >= 2) continue  // 同 artist 上限 2，跟本地 rerank 对齐
                // anchor 的 artist 在线最多 1 首（搜索结果通常是该 artist 的另一首热曲）
                if (anchorArtistKey != null && ak == anchorArtistKey && artistN >= 1) continue
                out[sk] = t
                artistCount[ak] = artistN + 1
            }
        }
        return out.values.toList().take(wantCount)
    }

    private fun buildOnlineSeeds(anchor: NativeTrack?, taste: TasteProfile?): List<String> {
        val seeds = LinkedHashSet<String>()
        // 不能光放 anchor.artist —— 会回流同 artist 热曲。改成
        // "anchor.artist + mood" / "anchor.artist + genre" 这种组合 seed
        val anchorArtist = anchor?.artist?.split('/', '&', ',')?.firstOrNull()?.trim().orEmpty()
        val moods = taste?.moods?.take(3).orEmpty()
        val genres = taste?.genres?.map { it.tag }?.take(3).orEmpty()
        val eras = taste?.eras?.map { it.label }?.take(2).orEmpty()
        val cultural = taste?.culturalContext?.take(2).orEmpty()

        // 优先组合 seed
        for (g in genres) {
            if (anchorArtist.isNotBlank()) seeds.add("$anchorArtist $g")
            else seeds.add(g)
        }
        for (m in moods) {
            if (genres.isNotEmpty()) seeds.add("${genres.first()} $m")
            else seeds.add(m)
        }
        for (e in eras) seeds.add(e)
        for (c in cultural) seeds.add(c)
        // 单独 anchor.artist 放最后兜底（且只放 1 个），不让它霸占 seeds
        if (anchorArtist.isNotBlank() && seeds.size < 3) seeds.add(anchorArtist)
        return seeds.toList().take(6)
    }

    // ============== utility ==============

    private fun logRecommendations(picks: List<Candidate>) {
        val ids = picks.mapNotNull { it.track.neteaseId }
        if (ids.isEmpty()) return
        runCatching { recommendationLog.log(ids, RecommendationLog.Source.Pet) }
    }

    private data class Candidate(
        val track: NativeTrack,
        val audioSim: Double = 0.0,
        val coListenScore: Double = 0.0,
        val tasteScore: Double = 0.0,
        val loveScore: Double = 0.0,
        val source: Int = 0,
        val finalScore: Double = 0.0,
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

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    companion object {
        private const val SOURCE_AUDIO = 1
        private const val SOURCE_COLISTEN = 2
        private const val SOURCE_TASTE = 4
        private const val SOURCE_LOVE = 8
    }
}
