package app.pipo.nativeapp.data

/**
 * 标签召回 —— 镜像 src/lib/tag-recall.ts。
 *
 * 输入：用户意图（已经在 PetAgent 解析过 → MusicIntent）+ 用户库 + 已有的 TrackSemanticProfile
 * 输出：按 tag 命中分排序的 (track, score) 候选
 *
 * 不主动叫 AI —— LLM 索引由 SemanticIndexer 后台跑。这里只拿现有 profile。
 */
object TagRecall {

    data class Hit(val track: NativeTrack, val score: Double)

    fun recall(
        intent: PetIntent,
        library: List<NativeTrack>,
        profiles: Map<String, TrackSemanticProfile>,
    ): List<Hit> {
        val out = ArrayList<Hit>()
        for (track in library) {
            val profile = profiles[track.id] ?: continue
            if (!passesHardConstraints(profile, intent)) continue

            var score = 0.0
            score += matchArray(intent.hardLanguages, listOf(profile.language.key)) * 0.8
            score += matchArray(intent.hardRegions, listOf(profile.region.key)) * 0.7
            score += matchArray(intent.hardGenres, profile.genres) * 0.7
            score += matchArray(intent.hardSubGenres, profile.subGenres) * 0.55
            score += matchArray(intent.hardVocalTypes, listOf(profile.vocalType.key)) * 0.5
            score += matchArray(intent.softMoods, profile.moods) * 0.28
            score += matchArray(intent.softScenes, profile.scenes) * 0.28
            score += matchArray(intent.softTextures, profile.textures) * 0.2
            score += energyFit(intent.softEnergy, profile.energyWords) * 0.28
            score += tempoFit(intent.softTempoFeel, profile.tempoFeel) * 0.18

            val hasExplicit = intent.hardLanguages.isNotEmpty() ||
                intent.hardRegions.isNotEmpty() ||
                intent.hardGenres.isNotEmpty() ||
                intent.hardSubGenres.isNotEmpty() ||
                intent.hardVocalTypes.isNotEmpty() ||
                intent.softMoods.isNotEmpty() ||
                intent.softScenes.isNotEmpty() ||
                intent.softTextures.isNotEmpty() ||
                intent.softEnergy != "any" ||
                intent.softTempoFeel != "any"
            if (hasExplicit && score > 0) {
                out.add(Hit(track, kotlin.math.min(1.8, score)))
            }
        }
        out.sortByDescending { it.score }
        return out.take(220)
    }

    fun passesHardConstraints(profile: TrackSemanticProfile, intent: PetIntent): Boolean {
        if (intent.hardLanguages.isNotEmpty() && !includesAny(intent.hardLanguages, listOf(profile.language.key))) return false
        if (intent.hardRegions.isNotEmpty() && !includesAny(intent.hardRegions, listOf(profile.region.key))) return false
        if (intent.hardGenres.isNotEmpty() && !includesAny(intent.hardGenres, profile.genres)) return false
        if (intent.hardSubGenres.isNotEmpty() && !includesAny(intent.hardSubGenres, profile.subGenres)) return false
        if (intent.hardVocalTypes.isNotEmpty() && !includesAny(intent.hardVocalTypes, listOf(profile.vocalType.key))) return false

        if (includesAny(intent.excludeLanguages, listOf(profile.language.key))) return false
        if (includesAny(intent.excludeRegions, listOf(profile.region.key))) return false
        if (includesAny(intent.excludeGenres, profile.genres + profile.subGenres)) return false
        if (includesAny(intent.excludeVocalTypes, listOf(profile.vocalType.key))) return false
        if (includesAny(
                intent.excludeTags,
                profile.negativeTags + profile.moods + profile.scenes + profile.textures + profile.genres + profile.subGenres,
            )
        ) return false
        return true
    }

    private fun matchArray(needles: List<String>, haystack: List<String>): Double {
        if (needles.isEmpty()) return 0.0
        var hit = 0
        for (n in needles) if (includesAny(listOf(n), haystack)) hit++
        return hit.toDouble() / needles.size
    }

    private fun includesAny(needles: List<String>, haystack: List<String>): Boolean {
        val hs = haystack.map(::normalize)
        return needles.map(::normalize).any { n -> hs.any { h -> h == n || h.contains(n) || n.contains(h) } }
    }

    private fun energyFit(intentEnergy: String, words: List<String>): Double {
        if (intentEnergy == "any") return 0.0
        val normalized = words.map(::normalize)
        return when (intentEnergy) {
            "mid_low" -> if ("mid-low" in normalized || "low" in normalized) 1.0 else 0.0
            "mid_high" -> if ("mid" in normalized || "high" in normalized) 1.0 else 0.0
            else -> if (normalize(intentEnergy) in normalized) 1.0 else 0.0
        }
    }

    private fun tempoFit(intentTempo: String, words: List<String>): Double {
        if (intentTempo == "any") return 0.0
        return if (normalize(intentTempo) in words.map(::normalize)) 1.0 else 0.0
    }

    private fun normalize(value: String): String {
        return value.lowercase().replace(Regex("\\s+"), "")
            .replace("节奏布鲁斯", "r&b").replace("rnb", "r&b")
    }
}

/**
 * 跨模块共享的"已解析意图"——PetAgent.ParsedIntent 的简化暴露版。
 * 把内部 ParsedIntent 抽成顶层 PetIntent，方便 TagRecall / SemanticRecall / Ranker 复用。
 */
data class PetIntent(
    val queryText: String = "",
    val hardArtists: List<String> = emptyList(),
    val hardTracks: List<String> = emptyList(),
    val hardGenres: List<String> = emptyList(),
    val hardSubGenres: List<String> = emptyList(),
    val hardLanguages: List<String> = emptyList(),
    val hardRegions: List<String> = emptyList(),
    val hardVocalTypes: List<String> = emptyList(),
    val excludeLanguages: List<String> = emptyList(),
    val excludeRegions: List<String> = emptyList(),
    val excludeGenres: List<String> = emptyList(),
    val excludeVocalTypes: List<String> = emptyList(),
    val excludeTags: List<String> = emptyList(),
    val excludeArtists: List<String> = emptyList(),
    val avoidWords: List<String> = emptyList(),
    val textArtists: List<String> = emptyList(),
    val textTracks: List<String> = emptyList(),
    val textAlbums: List<String> = emptyList(),
    val softMoods: List<String> = emptyList(),
    val softScenes: List<String> = emptyList(),
    val softTextures: List<String> = emptyList(),
    val softQualityWords: List<String> = emptyList(),
    val softEnergy: String = "any",
    val softTempoFeel: String = "any",
    val musicHintsMoods: List<String> = emptyList(),
    val musicHintsScenes: List<String> = emptyList(),
    val musicHintsGenres: List<String> = emptyList(),
    val musicHintsEnergy: String = "any",
    val musicHintsTransitionStyle: String = "soft",
    val refStyles: List<String> = emptyList(),
    val refArtists: List<String> = emptyList(),
    val emotionalDirection: String? = null,
    val orderStyle: String = "smooth",
    val desiredCount: Int = 30,
)
