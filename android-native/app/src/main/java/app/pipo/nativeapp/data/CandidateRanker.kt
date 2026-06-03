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
        /** 分位 bucket(0..3,0 最高分位),供续杯端跨段抽样 */
        val bucket: Int = 0,
    )

    data class Options(
        val topN: Int = 100,
        val recentPlay: BehaviorLog.RecentPlay? = null,
        val recentRecommendation: RecommendationLog.RecentContext? = null,
        val behaviorPreference: BehaviorPreferenceSnapshot = BehaviorPreferenceSnapshot.Empty,
    )

    // 权重 v2(2026-05) —— 跟 Web 端 candidate-ranker.ts 对齐。
    // TASTE 0.25 → 0.40,内部走 max(profileTags/1.2, acoustic*0.9, profile*0.25, audio*0.7),
    // 让画像维度(风格/情绪/年代/文化/声学)替代旧版"只看 topArtists"的主信号。
    private const val W_INTENT = 0.30
    // 接歌适配只做同档候选的 tie-breaker，不能压过用户需求/口味画像。
    private const val W_TRANSITION = 0.12
    private const val W_FRESHNESS = 0.07
    private const val W_TASTE = 0.40
    private const val W_BEHAVIOR = 0.10

    fun rank(
        candidates: List<CandidateRecall.Candidate>,
        intent: PetIntent,
        options: Options = Options(),
    ): List<Ranked> {
        val avoidKeys = (intent.avoidWords + intent.aiAvoidStyles)
            .map { it.lowercase().trim() }
            .filter { it.isNotBlank() }
        val excludedArtistKeys = intent.excludeArtists
            .map(::normalizeArtistKey)
            .filter { it.isNotBlank() }
        val ranked = ArrayList<Ranked>()
        val rnd = java.util.Random()

        for (c in candidates) {
            if (avoidKeys.isNotEmpty() && hitsAvoid(c, avoidKeys)) continue
            if (excludedArtistKeys.isNotEmpty() && hitsExcludedArtist(c.track, excludedArtistKeys)) continue
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
            // taste 内部:ProfileTags(风格维度) + Acoustic(声学维度)主导,Profile(artist) 0.25×
            val tasteScore = clamp01(maxOf(
                maxOf(
                    (c.sourceScores[CandidateRecall.Source.ProfileTags] ?: 0.0) / 1.2,
                    (c.sourceScores[CandidateRecall.Source.Acoustic] ?: 0.0) * 0.9,
                ),
                maxOf(
                    (c.sourceScores[CandidateRecall.Source.Profile] ?: 0.0) * 0.25,
                    (c.sourceScores[CandidateRecall.Source.Audio] ?: 0.0) * 0.7,
                ),
            ))
            val behaviorScore = clamp01((c.sourceScores[CandidateRecall.Source.Behavior] ?: 0.0) / 1.5)
            val sourcePreferenceDelta = (c.sourceScores[CandidateRecall.Source.PreferenceDelta] ?: 0.0) / 1.4
            val scoredPreferenceDelta = options.behaviorPreference.scoreTrack(c.track, c.semanticProfile, c.features)
            val rawPreferenceDelta = if (sourcePreferenceDelta > 0.0) {
                maxOf(sourcePreferenceDelta, scoredPreferenceDelta)
            } else {
                scoredPreferenceDelta
            }
            val preferenceDeltaScore = rawPreferenceDelta.coerceIn(-1.0, 1.0)
            val behaviorAffinityScore = clamp01(maxOf(behaviorScore, preferenceDeltaScore))
            val transitionScore = clamp01(c.sourceScores[CandidateRecall.Source.Transition] ?: 0.0)
            val freshnessScore = clamp01(
                (c.sourceScores[CandidateRecall.Source.Explore] ?: 0.0) -
                    (c.sourceScores[CandidateRecall.Source.Behavior] ?: 0.0) * 0.3
            )

            val w = rankWeights(intent)
            // 扰动 0.025 → 0.08(跟 Web 对齐),配合 bucket 洗牌共破"同 query 总同结果"
            val baseScore = intentScore * W_INTENT +
                tagScore * w.tag + semanticScore * w.semantic +
                tasteScore * w.taste + behaviorAffinityScore * w.behavior +
                transitionScore * W_TRANSITION + freshnessScore * W_FRESHNESS +
                rnd.nextDouble() * 0.08

            val recentPlayPenalty = if (explicitlyMentioned) 0.0
                else recentPenalty(neteaseId, options.recentPlay?.last24hTrackIds, options.recentPlay?.last7dTrackIds, 0.5, 0.25)
            val recentRecPenalty = if (explicitlyMentioned) 0.0
                else recentPenalty(neteaseId, options.recentRecommendation?.last24hTrackIds, options.recentRecommendation?.last7dTrackIds, 0.55, 0.24)
            // artist-level fatigue —— 修"自由推荐总是同一歌手"。
            // 阈值: 24h ≥5 次 -0.30, ≥10 -0.30(累计), 7d ≥20 -0.20。explicit 时不 fatigue。
            val artistFatiguePenalty = if (explicitlyMentioned) 0.0
                else computeArtistFatigue(c.track, options.recentRecommendation)

            val preferenceDeltaAdjust = if (explicitlyMentioned) {
                maxOf(0.0, preferenceDeltaScore) * 0.06
            } else {
                preferenceDeltaScore * 0.16
            }
            // 能量方向罚分 —— 用户明确要安静/低能量(或嗨/高能量)时，声学能量严重不符的歌降权。
            // 修"来点安静的却端出最吵的最爱"：taste 信号再强也不该违背当下能量诉求。点名要的不罚。
            val energyMismatch = if (explicitlyMentioned) 0.0
                else energyDirectionPenalty(intent, c.features)
            val finalScore = baseScore + preferenceDeltaAdjust -
                recentPlayPenalty - recentRecPenalty - artistFatiguePenalty - energyMismatch
            if (finalScore <= 0) continue
            ranked.add(Ranked(c, finalScore))
        }

        ranked.sortByDescending { it.finalScore }
        val asksVersion = TrackDedupe.queryAsksForSpecificVersion(
            intent.musicHintsGenres + intent.musicHintsMoods + intent.textTracks + intent.textArtists + intent.textAlbums
        )
        val deduped = if (asksVersion) ranked else dedupeByCandidate(ranked)

        // 分位 bucket 洗牌(同 query 重复触发时让顺序在每段内变化,跨段保持优先级)
        val withBuckets = if (!asksVersion && deduped.size > 8) {
            shuffleByBuckets(deduped)
        } else {
            // 至少打 bucket 标签
            deduped.mapIndexed { idx, r -> r.copy(bucket = pickBucket(idx, deduped.size)) }
        }
        return withBuckets.take(options.topN)
    }

    private fun pickBucket(rank: Int, total: Int): Int {
        val ratio = rank.toDouble() / maxOf(1, total)
        return when {
            ratio < 0.25 -> 0
            ratio < 0.50 -> 1
            ratio < 0.75 -> 2
            else -> 3
        }
    }

    private fun shuffleByBuckets(items: List<Ranked>): List<Ranked> {
        val withBucket = items.mapIndexed { idx, r -> r.copy(bucket = pickBucket(idx, items.size)) }
        val groups = Array(4) { ArrayList<Ranked>() }
        for (r in withBucket) groups[r.bucket].add(r)
        for (g in groups) g.shuffle()
        val out = ArrayList<Ranked>(items.size)
        for (g in groups) out.addAll(g)
        return out
    }

    /**
     * artist-level fatigue —— 镜像 Web 端 computeArtistFatigue。
     *
     * 阈值 v2(2026-05 放宽):用户反馈"压完同艺人浮上来的歌偏冷门",原来 24h ≥5 -0.30
     * 触发太早。放宽到 24h ≥8 -0.15 / ≥15 -0.20(累计 -0.35) / 7d ≥30 -0.15(累计 -0.50)。
     * 让"用户喜欢的艺人继续推"的空间更大,fatigue 仍能挡住"连推 20 首同人"的极端情况,
     * 但不会把"听了 5 首 Taylor"就立刻切到非 Taylor 的歌。
     */
    private fun computeArtistFatigue(
        track: NativeTrack,
        ctx: RecommendationLog.RecentContext?,
    ): Double {
        if (ctx == null) return 0.0
        val key = normalizeArtistKey(track.artist.split('/', '&', ',', '、').firstOrNull().orEmpty())
        if (key.isEmpty()) return 0.0
        val c24 = ctx.last24hArtistCounts[key] ?: 0
        val c7 = ctx.last7dArtistCounts[key] ?: 0
        var penalty = 0.0
        if (c24 >= 8) penalty += 0.15
        if (c24 >= 15) penalty += 0.20
        if (c7 >= 30) penalty += 0.15
        return penalty
    }

    /**
     * 能量方向罚分 —— 只在用户明确给了能量方向(softEnergy / musicHintsEnergy != any)时生效。
     * 用声学能量 eMid=(introEnergy+outroEnergy)/2 衡量：要安静(low)时越吵罚越多，要嗨(high)时
     * 越蔫罚越多。没有声学特征的歌中性(0 罚，不奖不惩)，避免误伤未分析曲目。
     * 上限 0.35：足以把"最常听的吵歌"挤下安静请求的队首，又不至于一刀切。
     */
    private fun energyDirectionPenalty(intent: PetIntent, features: AudioFeatures?): Double {
        if (features == null) return 0.0
        val target = intent.musicHintsEnergy.takeIf { it != "any" }
            ?: intent.softEnergy.takeIf { it != "any" }
            ?: return 0.0
        val eMid = (features.introEnergy + features.outroEnergy) / 2.0
        return when (target) {
            "low", "mid_low" -> (((eMid - 0.32) / 0.40).coerceIn(0.0, 1.0)) * 0.35
            "high", "mid_high" -> (((0.42 - eMid) / 0.40).coerceIn(0.0, 1.0)) * 0.30
            else -> 0.0
        }
    }

    private fun normalizeArtistKey(s: String): String =
        s.lowercase().replace(Regex("[\\s'\"`·・\\-－—_,，。.、!?！？]+"), "")

    private fun hitsExcludedArtist(track: NativeTrack, excludedArtistKeys: List<String>): Boolean {
        val whole = normalizeArtistKey(track.artist)
        val parts = track.artist
            .split("/", "&", ",", "、", " feat.", " feat ", "feat.", "feat ", " featuring ", " ft.", " ft ")
            .map(::normalizeArtistKey)
            .filter { it.isNotBlank() }
        return excludedArtistKeys.any { key ->
            parts.any { part -> part == key || (part.length >= 3 && key.length >= 3 && (part in key || key in part)) } ||
                (whole.length >= 3 && key.length >= 3 && key in whole)
        }
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
        val semanticHay = c.semanticProfile?.let { sp ->
            (sp.genres + sp.subGenres + sp.styleAnchors + sp.moods + sp.scenes +
                sp.textures + sp.energyWords + sp.tempoFeel + sp.negativeTags +
                listOf(sp.language.key, sp.region.key, sp.vocalType.key, sp.summary))
                .joinToString(" ")
                .lowercase()
        }.orEmpty()
        return avoid.any { it in hay || it in semanticHay }
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
