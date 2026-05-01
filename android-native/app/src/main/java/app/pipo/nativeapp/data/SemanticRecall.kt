package app.pipo.nativeapp.data

/**
 * 语义召回 —— 镜像 src/lib/semantic-recall.ts，向量召回 + lexical fallback 双路。
 *
 *   - 有 query embedding 向量 + 库已索引向量 → 用 cosine similarity 排序（强信号）
 *   - 没向量（用户用的 provider 不支持 / 还没索引）→ 用 lexical 关键词包含匹配（弱信号）
 *
 * 向量结果置信度高 → 阈值 0.55 以上才出，且分数自动比 lexical 路高一档。
 */
object SemanticRecall {

    private val STOPWORDS = setOf("来点", "听听", "想听", "不要", "一点", "some", "music", "song", "any")

    /**
     * lexical 路。queryVector / embeddingStore 提供时优先走 cosine。
     */
    fun recall(
        intent: PetIntent,
        query: String,
        library: List<NativeTrack>,
        profiles: Map<String, TrackSemanticProfile>,
        queryVector: FloatArray? = null,
        embeddingStore: EmbeddingStore? = null,
    ): List<TagRecall.Hit> {
        // 优先向量召回
        if (queryVector != null && embeddingStore != null) {
            val vectorHits = recallByVector(intent, library, profiles, queryVector, embeddingStore)
            if (vectorHits.isNotEmpty()) return vectorHits
        }

        // fallback：lexical
        val terms = buildQueryTerms(intent, query)
        if (terms.isEmpty()) return emptyList()
        val out = ArrayList<TagRecall.Hit>()
        for (track in library) {
            val profile = profiles[track.id] ?: continue
            if (!TagRecall.passesHardConstraints(profile, intent)) continue
            val haystack = semanticTerms(profile).joinToString(" ").lowercase()
            var score = 0.0
            for (t in terms) if (t in haystack) score += 1.0
            score /= kotlin.math.max(1, terms.size)
            if (profile.sourceLlm) score += 0.08
            if (score > 0.08) out.add(TagRecall.Hit(track, kotlin.math.min(1.2, score)))
        }
        out.sortByDescending { it.score }
        return out.take(180)
    }

    /**
     * 向量召回：用 query embedding 跟每首歌 embedding 算 cosine。
     *   - 阈值 0.55：低于这个相关度太弱，宁可空也别污染候选池
     *   - 分数线性映射到 [0.6, 1.4]，比 lexical 上限 1.2 略高，让 ranker 自然偏向向量结果
     */
    private fun recallByVector(
        intent: PetIntent,
        library: List<NativeTrack>,
        profiles: Map<String, TrackSemanticProfile>,
        queryVector: FloatArray,
        embeddingStore: EmbeddingStore,
    ): List<TagRecall.Hit> {
        val out = ArrayList<TagRecall.Hit>()
        for (track in library) {
            val profile = profiles[track.id] ?: continue
            if (!TagRecall.passesHardConstraints(profile, intent)) continue
            val vec = embeddingStore.get(track.id) ?: continue
            val sim = embeddingStore.cosine(queryVector, vec)
            if (sim < VECTOR_THRESHOLD) continue
            // [0.55, 1] → [0.6, 1.4]
            val score = 0.6 + ((sim - VECTOR_THRESHOLD) / (1f - VECTOR_THRESHOLD)) * 0.8
            out.add(TagRecall.Hit(track, score.toDouble()))
        }
        out.sortByDescending { it.score }
        return out.take(180)
    }

    private const val VECTOR_THRESHOLD = 0.55f

    /** 广域兜底：开放表达时给 LLM 留候选面，不让固定标签把池子缩死 */
    fun recallBroad(
        intent: PetIntent,
        library: List<NativeTrack>,
        profiles: Map<String, TrackSemanticProfile>,
    ): List<TagRecall.Hit> {
        val needs = intent.softQualityWords.isNotEmpty() ||
            intent.refStyles.isNotEmpty() ||
            intent.refArtists.isNotEmpty() ||
            intent.softMoods.isNotEmpty() ||
            intent.softScenes.isNotEmpty() ||
            intent.hardGenres.isNotEmpty() ||
            intent.hardRegions.isNotEmpty() ||
            intent.hardLanguages.isNotEmpty()
        if (!needs) return emptyList()

        val out = ArrayList<TagRecall.Hit>()
        val rnd = java.util.Random()
        for (track in library) {
            val profile = profiles[track.id] ?: continue
            if (!TagRecall.passesHardConstraints(profile, intent)) continue
            val completeness = (if (profile.sourceLlm) 0.35 else 0.0) +
                kotlin.math.min(0.25, profile.confidence * 0.25) +
                kotlin.math.min(0.2, profile.genres.size * 0.08) +
                kotlin.math.min(0.2, (profile.moods.size + profile.scenes.size) * 0.04)
            out.add(TagRecall.Hit(track, 0.18 + completeness + rnd.nextDouble() * 0.08))
        }
        out.sortByDescending { it.score }
        return out.take(90)
    }

    private fun semanticTerms(profile: TrackSemanticProfile): List<String> {
        return (listOf(profile.language.key, profile.region.key) +
            profile.genres + profile.subGenres + profile.styleAnchors +
            profile.moods + profile.scenes + profile.textures + profile.energyWords +
            profile.tempoFeel + listOf(profile.vocalType.key) + profile.vocalDelivery +
            profile.negativeTags + listOf(profile.summary, profile.embeddingText))
            .filter { it.isNotBlank() }
    }

    private fun buildQueryTerms(intent: PetIntent, query: String): List<String> {
        val raw = (listOf(query) +
            intent.hardGenres + intent.hardSubGenres + intent.hardRegions +
            intent.hardLanguages + intent.hardVocalTypes +
            intent.softMoods + intent.softScenes + intent.softTextures + intent.softQualityWords +
            listOf(intent.softEnergy, intent.softTempoFeel, intent.emotionalDirection ?: "") +
            intent.refStyles).joinToString(" ").lowercase()

        val mapped = raw
            .replace("欧美", "western english")
            .replace(Regex("英文|英语"), "english")
            .replace(Regex("国语|中文|华语"), "mandarin chinese")
            .replace("粤语", "cantonese")
            .replace(Regex("日语|日本"), "japanese")
            .replace(Regex("韩语|韩国"), "korean")
            .replace(Regex("rnb|节奏布鲁斯"), "r&b")
            .replace(Regex("写代码|编程"), "coding focus")
            .replace(Regex("晚上|夜里|深夜|夜晚"), "night late-night")
            .replace(Regex("下雨|雨天"), "rainy melancholic atmospheric")
            .replace("松弛", "chill smooth")
            .replace("高级", "sophisticated alternative neo soul")
            .replace(Regex("不吵|不要太吵|别太吵"), "calm soft minimal")

        return mapped.split(Regex("[^a-z0-9&\\u4e00-\\u9fff-]+", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOPWORDS }
            .distinct()
            .take(28)
    }
}
