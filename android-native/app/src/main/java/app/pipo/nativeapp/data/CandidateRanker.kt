package app.pipo.nativeapp.data

/**
 * 候选打分排序 —— 镜像 src/lib/candidate-ranker.ts。
 *
 * 把多路 Candidate[] 按 weighted relevance + recent penalties 排出 Top N，
 * 喂给后续的 SmoothQueue 或者 Pet 的 initialBatch。
 */
object CandidateRanker {

    data class Ranked(
        val candidate: CandidateRecall.Candidate,
        val finalScore: Double,
    )

    data class Options(
        val topN: Int = 100,
        val recentPlay: BehaviorLog.RecentPlay? = null,
        val recentRecommendation: RecommendationLog.RecentContext? = null,
    )

    private const val W_INTENT = 0.35
    private const val W_TRANSITION = 0.15
    private const val W_FRESHNESS = 0.07
    private const val W_TASTE = 0.25
    private const val W_BEHAVIOR = 0.18

    fun rank(
        candidates: List<CandidateRecall.Candidate>,
        intent: PetIntent,
        options: Options = Options(),
    ): List<Ranked> {
        val avoidKeys = intent.avoidWords.map { it.lowercase().trim() }.filter { it.isNotBlank() }
        val ranked = ArrayList<Ranked>()
        val rnd = java.util.Random()

        for (c in candidates) {
            if (avoidKeys.isNotEmpty() && hitsAvoid(c, avoidKeys)) continue
            val explicitlyMentioned = TrackDedupe.queryExplicitlyMentions(
                c.track,
                intent.hardArtists, intent.hardTracks,
                intent.textArtists, intent.textTracks,
            )
            if (c.semanticProfile != null && !explicitlyMentioned &&
                !TagRecall.passesHardConstraints(c.semanticProfile, intent)
            ) continue
            val neteaseId = c.track.neteaseId
            if (neteaseId != null && options.recentPlay?.last24hTrackIds?.contains(neteaseId) == true && !explicitlyMentioned) continue

            val intentScore = clamp01(c.sourceScores[CandidateRecall.Source.Text] ?: 0.0)
            val tagScore = clamp01((c.sourceScores[CandidateRecall.Source.Tag] ?: 0.0) / 1.6)
            val semanticScore = clamp01(maxOf(
                c.sourceScores[CandidateRecall.Source.Semantic] ?: 0.0,
                (c.sourceScores[CandidateRecall.Source.SemanticBroad] ?: 0.0) * 0.65,
            ))
            val tasteScore = clamp01(maxOf(
                c.sourceScores[CandidateRecall.Source.Profile] ?: 0.0,
                (c.sourceScores[CandidateRecall.Source.Audio] ?: 0.0) * 0.7,
            ))
            val behaviorScore = clamp01((c.sourceScores[CandidateRecall.Source.Behavior] ?: 0.0) / 1.5)
            val transitionScore = clamp01(c.sourceScores[CandidateRecall.Source.Transition] ?: 0.0)
            val freshnessScore = clamp01(
                (c.sourceScores[CandidateRecall.Source.Explore] ?: 0.0) -
                    (c.sourceScores[CandidateRecall.Source.Behavior] ?: 0.0) * 0.3
            )

            val w = rankWeights(intent)
            val baseScore = intentScore * W_INTENT +
                tagScore * w.tag + semanticScore * w.semantic +
                tasteScore * w.taste + behaviorScore * w.behavior +
                transitionScore * W_TRANSITION + freshnessScore * W_FRESHNESS +
                rnd.nextDouble() * 0.025

            val recentPlayPenalty = if (explicitlyMentioned) 0.0
                else recentPenalty(neteaseId, options.recentPlay?.last24hTrackIds, options.recentPlay?.last7dTrackIds, 0.5, 0.25)
            val recentRecPenalty = if (explicitlyMentioned) 0.0
                else recentPenalty(neteaseId, options.recentRecommendation?.last24hTrackIds, options.recentRecommendation?.last7dTrackIds, 0.35, 0.15)

            val finalScore = baseScore - recentPlayPenalty - recentRecPenalty
            if (finalScore <= 0) continue
            ranked.add(Ranked(c, finalScore))
        }

        ranked.sortByDescending { it.finalScore }
        val asksVersion = TrackDedupe.queryAsksForSpecificVersion(
            intent.musicHintsGenres + intent.musicHintsMoods + intent.textTracks + intent.textArtists + intent.textAlbums
        )
        val deduped = if (asksVersion) ranked else dedupeByCandidate(ranked)
        return deduped.take(options.topN)
    }

    private fun dedupeByCandidate(items: List<Ranked>): List<Ranked> {
        val seen = HashSet<String>()
        val out = ArrayList<Ranked>(items.size)
        for (r in items) {
            val key = TrackDedupe.songKey(r.candidate.track)
            if (seen.add(key)) out.add(r)
        }
        return out
    }

    private data class Weights(val tag: Double, val semantic: Double, val taste: Double, val behavior: Double)

    private fun rankWeights(intent: PetIntent): Weights {
        val hardCount = intent.hardLanguages.size + intent.hardRegions.size +
            intent.hardGenres.size + intent.hardSubGenres.size + intent.hardVocalTypes.size +
            intent.excludeLanguages.size + intent.excludeGenres.size + intent.excludeTags.size
        val vibeCount = intent.softMoods.size + intent.softScenes.size + intent.softTextures.size +
            (if (intent.softEnergy == "any") 0 else 1)
        return when {
            hardCount > 0 -> Weights(0.34, 0.24, 0.12, 0.06)
            vibeCount > 0 -> Weights(0.18, 0.34, 0.16, 0.08)
            else -> Weights(0.08, 0.16, W_TASTE, W_BEHAVIOR)
        }
    }

    private fun hitsAvoid(c: CandidateRecall.Candidate, avoid: List<String>): Boolean {
        val hay = (c.track.title + " " + c.track.artist + " " + c.track.album).lowercase()
        return avoid.any { it in hay }
    }

    private fun recentPenalty(
        trackId: Long?,
        in24h: Set<Long>?,
        in7d: Set<Long>?,
        last24hPenalty: Double,
        last7dPenalty: Double,
    ): Double {
        if (trackId == null) return 0.0
        if (in24h?.contains(trackId) == true) return last24hPenalty
        if (in7d?.contains(trackId) == true) return last7dPenalty
        return 0.0
    }

    private fun clamp01(x: Double): Double = if (x < 0) 0.0 else if (x > 1) 1.0 else x
}
