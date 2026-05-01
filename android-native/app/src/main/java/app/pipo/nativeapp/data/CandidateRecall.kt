package app.pipo.nativeapp.data

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 多路候选召回 —— 镜像 src/lib/candidate-recall.ts。
 *
 * 召回路径：
 *   - text：用户句子里直接点名的歌名/艺人/专辑
 *   - tag：TrackSemanticProfile 硬标签
 *   - semantic / semantic_broad：embeddingText/summary 文本匹配 + 广域兜底
 *   - profile：TasteProfile 的 topArtists / genres / moods
 *   - audio：musicHints.energy / transitionStyle 推 BPM/能量过滤
 *   - behavior：近期 PlayStarted/Completed 多的（无 liked/disliked，跟 React 字段对齐）
 *   - transition：当前播放接得上的
 *   - explore：很久没听但 profile 匹配的
 */
object CandidateRecall {

    enum class Source { Text, Tag, Semantic, SemanticBroad, Profile, Audio, Behavior, Transition, Explore }

    data class Candidate(
        val track: NativeTrack,
        val features: AudioFeatures?,
        val semanticProfile: TrackSemanticProfile?,
        val sources: MutableList<Source> = mutableListOf(),
        val sourceScores: MutableMap<Source, Double> = mutableMapOf(),
    )

    fun recall(
        intent: PetIntent,
        library: List<NativeTrack>,
        featuresStore: AudioFeaturesStore,
        semanticStore: TrackSemanticStore,
        indexer: SemanticIndexer,
        tasteProfile: TasteProfile?,
        behaviorEvents: List<BehaviorEvent>,
        currentTrack: NativeTrack? = null,
        limit: Int = 200,
        queryVector: FloatArray? = null,
        embeddingStore: EmbeddingStore? = null,
    ): List<Candidate> {
        if (library.isEmpty()) return emptyList()

        // 一次性把 features + semantic 全读出来
        val featuresMap = library.associate { it.id to featuresStore.get(it.id) }
        val semanticMap = HashMap<String, TrackSemanticProfile>()
        for (t in library) {
            val cached = semanticStore.get(t.id)
            if (cached != null) {
                semanticMap[t.id] = cached
            } else {
                // 规则版兜底（不叫 LLM）—— 跟 React getSemanticProfiles 一样
                semanticMap[t.id] = indexer.buildRuleBasedProfile(t, featuresMap[t.id])
            }
        }

        val buckets = HashMap<String, Candidate>()
        fun ensure(track: NativeTrack): Candidate = buckets.getOrPut(track.id) {
            Candidate(track = track, features = featuresMap[track.id], semanticProfile = semanticMap[track.id])
        }
        fun hit(track: NativeTrack, source: Source, score: Double) {
            val c = ensure(track)
            if (source !in c.sources) c.sources.add(source)
            val prev = c.sourceScores[source] ?: 0.0
            if (score > prev) c.sourceScores[source] = score
        }

        // 1) text
        for (h in recallByText(intent, library)) hit(h.track, Source.Text, h.score)
        // 2) tag
        for (h in TagRecall.recall(intent, library, semanticMap)) hit(h.track, Source.Tag, h.score)
        // 3) semantic（向量优先，没有就 lexical）
        for (h in SemanticRecall.recall(
            intent, intent.queryText, library, semanticMap,
            queryVector = queryVector,
            embeddingStore = embeddingStore,
        )) hit(h.track, Source.Semantic, h.score)
        // 4) semantic_broad
        for (h in SemanticRecall.recallBroad(intent, library, semanticMap)) hit(h.track, Source.SemanticBroad, h.score)
        // 5) profile
        for (h in recallByProfile(intent, library, tasteProfile)) hit(h.track, Source.Profile, h.score)
        // 6) audio
        for (h in recallByAudio(intent, library, featuresMap)) hit(h.track, Source.Audio, h.score)
        // 7) behavior
        for (h in recallByBehavior(library, behaviorEvents)) hit(h.track, Source.Behavior, h.score)
        // 8) transition
        if (currentTrack != null) {
            for (h in recallByTransition(currentTrack, featuresMap[currentTrack.id], library, featuresMap)) {
                hit(h.track, Source.Transition, h.score)
            }
        }
        // 9) explore
        for (h in recallByExplore(library, tasteProfile, behaviorEvents)) hit(h.track, Source.Explore, h.score)

        return buckets.values.toList().take(limit)
    }

    // ----- recall paths -----

    data class Hit(val track: NativeTrack, val score: Double)

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[\\s'\"·・\\-－—]+"), "")

    private fun recallByText(intent: PetIntent, library: List<NativeTrack>): List<Hit> {
        if (intent.textArtists.isEmpty() && intent.textTracks.isEmpty() && intent.textAlbums.isEmpty()) return emptyList()
        val artistKeys = intent.textArtists.map(::normalize).filter { it.isNotBlank() }
        val trackKeys = intent.textTracks.map(::normalize).filter { it.isNotBlank() }
        val albumKeys = intent.textAlbums.map(::normalize).filter { it.isNotBlank() }
        val out = ArrayList<Hit>()
        for (t in library) {
            val titleN = normalize(t.title)
            val artistN = t.artist.split("/", "&", ",").map { normalize(it) }.joinToString("/")
            val albumN = normalize(t.album)
            var score = 0.0
            for (k in trackKeys) {
                if (titleN == k) score += 1.0
                else if (k in titleN || titleN in k) score += 0.6
            }
            for (k in artistKeys) {
                val parts = artistN.split("/")
                if (parts.any { it == k }) score += 0.9
                else if (k in artistN) score += 0.5
            }
            for (k in albumKeys) {
                if (albumN == k) score += 0.7
                else if (k in albumN || albumN in k) score += 0.4
            }
            if (score > 0) out.add(Hit(t, score))
        }
        out.sortByDescending { it.score }
        return out.take(80)
    }

    private fun recallByProfile(intent: PetIntent, library: List<NativeTrack>, profile: TasteProfile?): List<Hit> {
        if (profile == null) return emptyList()
        val topArtistKeys = HashMap<String, Double>()
        profile.topArtists.take(40).forEach { topArtistKeys[normalize(it.name)] = it.affinity.toDouble() }
        val tagWords = (intent.musicHintsMoods + intent.musicHintsGenres).map(::normalize).filter { it.isNotBlank() }.toSet()

        val out = ArrayList<Hit>()
        for (t in library) {
            var score = 0.0
            t.artist.split("/", "&", ",").forEach { a ->
                topArtistKeys[normalize(a.trim())]?.let { score += it * 0.45 }
            }
            if (tagWords.isNotEmpty()) {
                val titleN = normalize(t.title)
                if (tagWords.any { it in titleN }) score += 0.2
            }
            if (score > 0) out.add(Hit(t, score))
        }
        out.sortByDescending { it.score }
        return out.take(120)
    }

    private fun recallByAudio(intent: PetIntent, library: List<NativeTrack>, features: Map<String, AudioFeatures?>): List<Hit> {
        if (intent.musicHintsEnergy == "any") return emptyList()
        val range: Pair<Double, Double> = when (intent.musicHintsEnergy) {
            "low" -> 0.0 to 0.25
            "mid" -> 0.2 to 0.55
            else -> 0.45 to 1.0  // high
        }
        val bpmFloor = when (intent.musicHintsTransitionStyle) {
            "party" -> 110.0
            "tight" -> 100.0
            else -> 0.0
        }
        val out = ArrayList<Hit>()
        for (t in library) {
            val a = features[t.id] ?: continue
            val eMid = (a.introEnergy + a.outroEnergy) / 2.0
            if (eMid < range.first || eMid > range.second) continue
            if (bpmFloor > 0.0 && (a.bpm ?: 0.0) < bpmFloor) continue
            val mid = (range.first + range.second) / 2
            val score = 1 - min(1.0, kotlin.math.abs(eMid - mid) / max(0.01, mid))
            out.add(Hit(t, 0.4 + score * 0.4))
        }
        out.sortByDescending { it.score }
        return out.take(120)
    }

    private fun recallByBehavior(library: List<NativeTrack>, log: List<BehaviorEvent>): List<Hit> {
        if (log.isEmpty()) return emptyList()
        val cutoffMs = System.currentTimeMillis() - 90L * 24 * 3600 * 1000  // 近 90 天
        val nowMs = System.currentTimeMillis()
        val score = HashMap<String, Double>()
        for (ev in log) {
            if (ev.tsMs < cutoffMs) continue
            val ageHours = max(0.0, (nowMs - ev.tsMs) / 3_600_000.0)
            val decay = exp(-ageHours / 72.0)
            val w = when (ev.type) {
                BehaviorType.Completed -> 0.25
                BehaviorType.Skipped -> -0.6
                BehaviorType.ManualCut -> -0.15
                BehaviorType.PlayStarted -> 0.0
            }
            score[ev.trackId] = (score[ev.trackId] ?: 0.0) + w * decay
        }
        val byId = library.associateBy { it.id }
        val out = ArrayList<Hit>()
        for ((id, s) in score) {
            if (s <= 0) continue
            val t = byId[id] ?: continue
            out.add(Hit(t, min(1.5, s)))
        }
        out.sortByDescending { it.score }
        return out.take(80)
    }

    private fun recallByTransition(
        current: NativeTrack,
        currentFeat: AudioFeatures?,
        library: List<NativeTrack>,
        features: Map<String, AudioFeatures?>,
    ): List<Hit> {
        if (currentFeat == null) return emptyList()
        val out = ArrayList<Hit>()
        for (t in library) {
            if (t.id == current.id) continue
            val f = features[t.id] ?: continue
            val risk = TransitionScore.risk(
                TransitionScore.Scored(current, currentFeat),
                TransitionScore.Scored(t, f),
                sameArtistPenalty = 0.0,
            )
            if (risk >= 1.0) continue
            out.add(Hit(t, 1.0 - risk))
        }
        out.sortByDescending { it.score }
        return out.take(60)
    }

    private fun recallByExplore(library: List<NativeTrack>, profile: TasteProfile?, log: List<BehaviorEvent>): List<Hit> {
        if (profile == null) return emptyList()
        val recentlyTouched = log.takeLast(30).map { it.trackId }.toSet()
        val topArtistKeys = profile.topArtists.take(30).map { normalize(it.name) }.toSet()
        val out = ArrayList<Hit>()
        for (t in library) {
            if (t.id in recentlyTouched) continue
            val matches = t.artist.split("/", "&", ",").any { normalize(it.trim()) in topArtistKeys }
            if (!matches) continue
            out.add(Hit(t, 0.4))
        }
        out.shuffle()
        return out.take(40)
    }
}
