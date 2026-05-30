package app.pipo.nativeapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 低成本行为学习层。
 *
 * 不调用 AI，不改写长期 TasteProfile；只从本地 BehaviorLog 推导一个短期/中期
 * preference delta，供点歌和续杯排序使用。源数据仍是 BehaviorLog，因此这里可以只做
 * 内存缓存，按事件数量 + 最新时间戳失效。
 */
class BehaviorPreferenceEngine(
    private val behaviorLog: BehaviorLog,
    private val library: LibraryLoader,
    private val semanticStore: TrackSemanticStore,
    private val featuresStore: AudioFeaturesStore,
    private val indexer: SemanticIndexer,
) {
    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cachedSnapshot: BehaviorPreferenceSnapshot = BehaviorPreferenceSnapshot.Empty

    suspend fun current(): BehaviorPreferenceSnapshot = withContext(Dispatchers.Default) {
        val events = runCatching { behaviorLog.readAll() }.getOrDefault(emptyList())
        val lastTs = events.maxOfOrNull { it.tsMs } ?: 0L
        val key = "${events.size}:$lastTs"
        val cached = cachedSnapshot
        if (cachedKey == key) return@withContext cached

        val snapshot = buildSnapshot(events)
        cachedKey = key
        cachedSnapshot = snapshot
        snapshot
    }

    fun scoreTrack(snapshot: BehaviorPreferenceSnapshot, track: NativeTrack): Double {
        if (!snapshot.hasSignal) return 0.0
        val features = featuresStore.get(track.id)
        val semantic = semanticStore.get(track.id)
            ?: indexer.buildRuleBasedProfile(track, features)
        return snapshot.scoreTrack(track, semantic, features)
    }

    private suspend fun buildSnapshot(events: List<BehaviorEvent>): BehaviorPreferenceSnapshot {
        if (events.isEmpty()) return BehaviorPreferenceSnapshot.Empty
        val tracks = runCatching { library.library() }.getOrDefault(emptyList())
        val byId = tracks.associateBy { it.id }
        val now = System.currentTimeMillis()
        val cutoff = now - WINDOW_DAYS * 24L * 3600L * 1000L

        val artist = ScoreBucket()
        val genre = ScoreBucket()
        val mood = ScoreBucket()
        val scene = ScoreBucket()
        val language = ScoreBucket()
        val region = ScoreBucket()
        val vocal = ScoreBucket()
        val energy = ScoreBucket()
        val tempo = ScoreBucket()
        var evidence = 0.0
        var weightedEvents = 0.0

        for (event in events) {
            if (event.tsMs < cutoff) continue
            val track = byId[event.trackId] ?: NativeTrack(
                id = event.trackId,
                neteaseId = event.neteaseId,
                title = event.title,
                artist = event.artist,
                album = "",
                streamUrl = "",
            )
            val base = eventPreferenceWeight(event)
            if (base == 0.0) continue
            val ageHours = max(0.0, (now - event.tsMs) / 3_600_000.0)
            val recency = exp(-ageHours / RECENCY_HALF_LIFE_HOURS)
            val weight = base * recency
            if (weight == 0.0) continue

            weightedEvents += kotlin.math.abs(weight)
            if (base > 0) evidence += 1.0
            val features = featuresStore.get(track.id)
            val semantic = semanticStore.get(track.id)
                ?: indexer.buildRuleBasedProfile(track, features)

            addArtistKeys(artist, track, weight)
            addKeys(genre, semantic.genres + semantic.subGenres + semantic.styleAnchors, weight, 1.0)
            addKeys(mood, semantic.moods + semantic.textures, weight, 0.85)
            addKeys(scene, semantic.scenes, weight, 0.75)
            addKey(language, semantic.language.key, weight, semantic.languageConfidence)
            addKey(region, semantic.region.key, weight, semantic.regionConfidence)
            addKey(vocal, semantic.vocalType.key, weight, 0.65)
            addKey(energy, energyBucket(semantic, features), weight, 0.75)
            addKey(tempo, tempoBucket(semantic, features), weight, 0.60)
        }

        if (weightedEvents < MIN_WEIGHTED_EVENTS) return BehaviorPreferenceSnapshot.Empty
        val confidence = min(1.0, sqrt(weightedEvents / CONFIDENCE_FULL_WEIGHT))
        return BehaviorPreferenceSnapshot(
            observedCount = events.size,
            weightedEvidence = weightedEvents,
            confidence = confidence,
            updatedAtMs = now,
            artistScores = artist.normalized(),
            genreScores = genre.normalized(),
            moodScores = mood.normalized(),
            sceneScores = scene.normalized(),
            languageScores = language.normalized(),
            regionScores = region.normalized(),
            vocalScores = vocal.normalized(),
            energyScores = energy.normalized(),
            tempoScores = tempo.normalized(),
        )
    }

    private fun eventPreferenceWeight(event: BehaviorEvent): Double =
        when (event.type) {
            BehaviorType.Completed -> 1.15
            BehaviorType.PlayStarted -> 0.12
            BehaviorType.Skipped -> -1.15
            BehaviorType.ManualCut -> when {
                event.completionPct >= 0.82f -> 0.28
                event.completionPct >= 0.62f -> -0.10
                else -> -0.45
            }
        }

    private fun addArtistKeys(bucket: ScoreBucket, track: NativeTrack, weight: Double) {
        track.artist
            .split("/", "&", ",", "、", " feat.", " feat ", " featuring ", " ft.", " ft ")
            .map(::normalizeKey)
            .filter { it.isNotBlank() }
            .take(4)
            .forEach { bucket.add(it, weight) }
    }

    private fun addKeys(bucket: ScoreBucket, keys: List<String>, weight: Double, multiplier: Double) {
        keys.map(::normalizeKey)
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
            .forEach { bucket.add(it, weight * multiplier) }
    }

    private fun addKey(bucket: ScoreBucket, key: String?, weight: Double, multiplier: Double) {
        val normalized = normalizeKey(key.orEmpty())
        if (normalized.isNotBlank() && normalized != "unknown") bucket.add(normalized, weight * multiplier)
    }

    private fun energyBucket(profile: TrackSemanticProfile, features: AudioFeatures?): String {
        val energy = profile.energy ?: features?.let { (it.introEnergy + it.outroEnergy) / 2.0 }
        return when {
            energy == null -> profile.energyWords.firstOrNull().orEmpty()
            energy < 0.32 -> "energy_low"
            energy < 0.62 -> "energy_mid"
            else -> "energy_high"
        }
    }

    private fun tempoBucket(profile: TrackSemanticProfile, features: AudioFeatures?): String {
        val bpm = profile.bpm ?: features?.bpm
        return when {
            bpm == null -> profile.tempoFeel.firstOrNull().orEmpty()
            bpm < 82.0 -> "tempo_slow"
            bpm < 118.0 -> "tempo_mid"
            else -> "tempo_fast"
        }
    }

    private class ScoreBucket {
        private val scores = HashMap<String, Double>()

        fun add(key: String, weight: Double) {
            if (key.isBlank() || key == "unknown") return
            scores[key] = (scores[key] ?: 0.0) + weight
        }

        fun normalized(): Map<String, Double> {
            val maxAbs = scores.values.maxOfOrNull { kotlin.math.abs(it) } ?: return emptyMap()
            if (maxAbs <= 0.0) return emptyMap()
            return scores
                .filterValues { kotlin.math.abs(it) >= MIN_BUCKET_ABS_SCORE }
                .mapValues { (_, v) -> (v / maxAbs).coerceIn(-1.0, 1.0) }
        }
    }

    companion object {
        private const val WINDOW_DAYS = 45
        private const val RECENCY_HALF_LIFE_HOURS = 120.0
        private const val MIN_WEIGHTED_EVENTS = 2.5
        private const val CONFIDENCE_FULL_WEIGHT = 42.0
        private const val MIN_BUCKET_ABS_SCORE = 0.12
    }
}

data class BehaviorPreferenceSnapshot(
    val observedCount: Int,
    val weightedEvidence: Double,
    val confidence: Double,
    val updatedAtMs: Long,
    val artistScores: Map<String, Double>,
    val genreScores: Map<String, Double>,
    val moodScores: Map<String, Double>,
    val sceneScores: Map<String, Double>,
    val languageScores: Map<String, Double>,
    val regionScores: Map<String, Double>,
    val vocalScores: Map<String, Double>,
    val energyScores: Map<String, Double>,
    val tempoScores: Map<String, Double>,
) {
    val hasSignal: Boolean
        get() = confidence >= 0.18 && weightedEvidence >= 2.5

    fun scoreTrack(
        track: NativeTrack,
        semantic: TrackSemanticProfile?,
        features: AudioFeatures?,
    ): Double {
        if (!hasSignal) return 0.0
        var score = 0.0
        score += matchArtist(track) * 0.32
        if (semantic != null) {
            score += matchList(genreScores, semantic.genres + semantic.subGenres + semantic.styleAnchors) * 0.28
            score += matchList(moodScores, semantic.moods + semantic.textures) * 0.20
            score += matchList(sceneScores, semantic.scenes) * 0.14
            score += matchKey(languageScores, semantic.language.key) * 0.12
            score += matchKey(regionScores, semantic.region.key) * 0.08
            score += matchKey(vocalScores, semantic.vocalType.key) * 0.08
            score += matchKey(energyScores, energyBucket(semantic, features)) * 0.12
            score += matchKey(tempoScores, tempoBucket(semantic, features)) * 0.08
        }
        return (score * confidence).coerceIn(-1.0, 1.0)
    }

    fun brief(maxItems: Int = 4): String? {
        if (!hasSignal) return null
        val likes = topPositive(maxItems)
        val avoids = topNegative(maxItems)
        if (likes.isEmpty() && avoids.isEmpty()) return null
        return buildString {
            if (likes.isNotEmpty()) append("最近更常听完: ${likes.joinToString("、")}")
            if (avoids.isNotEmpty()) {
                if (isNotEmpty()) append("；")
                append("最近常跳过: ${avoids.joinToString("、")}")
            }
        }
    }

    /**
     * 给库外搜索用的低成本 seed。这里不调用 AI，只把最近完成/跳过形成的偏好
     * 转成少量可搜索短词；真正找歌仍走 repository.searchTracks。
     */
    fun onlineSeeds(maxItems: Int = 6): List<String> {
        if (!hasSignal) return emptyList()
        val seeds = LinkedHashSet<String>()
        val genres = topLabels(genreScores, 3, minScore = 0.24)
        val moods = topLabels(moodScores, 3, minScore = 0.24)
        val scenes = topLabels(sceneScores, 2, minScore = 0.20)
        val languages = topLabels(languageScores, 2, minScore = 0.18)
        val vocals = topLabels(vocalScores, 1, minScore = 0.18)
        val energies = topLabels(energyScores, 1, minScore = 0.18)
        val tempos = topLabels(tempoScores, 1, minScore = 0.18)

        val primaryGenre = genres.firstOrNull()
        val primaryMood = moods.firstOrNull()
        val primaryScene = scenes.firstOrNull()
        val primaryLanguage = languages.firstOrNull()
        val primaryVocal = vocals.firstOrNull()

        addSeed(seeds, primaryGenre, primaryMood)
        addSeed(seeds, primaryLanguage, primaryGenre ?: primaryMood)
        addSeed(seeds, primaryScene, primaryMood ?: primaryGenre)
        addSeed(seeds, primaryVocal, primaryGenre ?: primaryMood)
        addSeed(seeds, primaryGenre ?: primaryMood, energies.firstOrNull())
        addSeed(seeds, primaryGenre ?: primaryMood, tempos.firstOrNull())

        genres.forEach { addSeed(seeds, it, null) }
        moods.forEach { addSeed(seeds, it, null) }
        scenes.forEach { addSeed(seeds, it, null) }

        return seeds.asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(maxItems)
            .toList()
    }

    private fun topPositive(maxItems: Int): List<String> =
        ranked(
            genreScores,
            moodScores,
            sceneScores,
            languageScores,
            vocalScores,
            energyScores,
            tempoScores,
            artistScores,
        )
            .filter { it.value > 0.32 }
            .take(maxItems)
            .map { displayKey(it.key) }

    private fun topNegative(maxItems: Int): List<String> =
        ranked(
            genreScores,
            moodScores,
            sceneScores,
            languageScores,
            vocalScores,
            energyScores,
            tempoScores,
            artistScores,
        )
            .filter { it.value < -0.32 }
            .take(maxItems)
            .map { displayKey(it.key) }

    private fun topLabels(scores: Map<String, Double>, maxItems: Int, minScore: Double): List<String> =
        scores.entries
            .asSequence()
            .filter { it.value >= minScore && it.key.isNotBlank() && it.key != "unknown" }
            .sortedByDescending { it.value }
            .map { displayKey(it.key) }
            .filter { it.isNotBlank() && it != "未知" }
            .distinct()
            .take(maxItems)
            .toList()

    private fun ranked(vararg maps: Map<String, Double>): List<Map.Entry<String, Double>> {
        val merged = HashMap<String, Double>()
        for (m in maps) {
            for ((k, v) in m) {
                if (k.isBlank() || k == "unknown") continue
                val old = merged[k]
                if (old == null || kotlin.math.abs(v) > kotlin.math.abs(old)) merged[k] = v
            }
        }
        return merged.entries.sortedByDescending { kotlin.math.abs(it.value) }
    }

    private fun matchArtist(track: NativeTrack): Double {
        var best = 0.0
        track.artist
            .split("/", "&", ",", "、", " feat.", " feat ", " featuring ", " ft.", " ft ")
            .map(::normalizeKey)
            .filter { it.isNotBlank() }
            .forEach { key -> best = bestByKey(artistScores, key, best) }
        return best
    }

    private fun matchList(scores: Map<String, Double>, keys: List<String>): Double {
        var best = 0.0
        keys.map(::normalizeKey)
            .filter { it.isNotBlank() }
            .forEach { key -> best = bestByKey(scores, key, best) }
        return best
    }

    private fun matchKey(scores: Map<String, Double>, key: String?): Double =
        bestByKey(scores, normalizeKey(key.orEmpty()), 0.0)

    private fun bestByKey(scores: Map<String, Double>, key: String, current: Double): Double {
        if (key.isBlank() || key == "unknown") return current
        var best = current
        for ((candidate, value) in scores) {
            if (candidate == key || (candidate.length >= 3 && key.length >= 3 && (candidate in key || key in candidate))) {
                if (kotlin.math.abs(value) > kotlin.math.abs(best)) best = value
            }
        }
        return best
    }

    private fun energyBucket(profile: TrackSemanticProfile, features: AudioFeatures?): String {
        val energy = profile.energy ?: features?.let { (it.introEnergy + it.outroEnergy) / 2.0 }
        return when {
            energy == null -> profile.energyWords.firstOrNull().orEmpty()
            energy < 0.32 -> "energy_low"
            energy < 0.62 -> "energy_mid"
            else -> "energy_high"
        }
    }

    private fun tempoBucket(profile: TrackSemanticProfile, features: AudioFeatures?): String {
        val bpm = profile.bpm ?: features?.bpm
        return when {
            bpm == null -> profile.tempoFeel.firstOrNull().orEmpty()
            bpm < 82.0 -> "tempo_slow"
            bpm < 118.0 -> "tempo_mid"
            else -> "tempo_fast"
        }
    }

    companion object {
        val Empty = BehaviorPreferenceSnapshot(
            observedCount = 0,
            weightedEvidence = 0.0,
            confidence = 0.0,
            updatedAtMs = 0L,
            artistScores = emptyMap(),
            genreScores = emptyMap(),
            moodScores = emptyMap(),
            sceneScores = emptyMap(),
            languageScores = emptyMap(),
            regionScores = emptyMap(),
            vocalScores = emptyMap(),
            energyScores = emptyMap(),
            tempoScores = emptyMap(),
        )
    }
}

private fun addSeed(seeds: MutableSet<String>, a: String?, b: String?) {
    val left = a?.trim().orEmpty()
    val right = b?.trim().orEmpty()
    when {
        left.isNotBlank() && right.isNotBlank() && left != right -> seeds.add("$left $right")
        left.isNotBlank() -> seeds.add(left)
        right.isNotBlank() -> seeds.add(right)
    }
}

private fun displayKey(key: String): String =
    when (normalizeKey(key)) {
        "english" -> "英文"
        "mandarin" -> "华语"
        "cantonese" -> "粤语"
        "japanese" -> "日语"
        "korean" -> "韩语"
        "mixed" -> "多语种"
        "instrumental" -> "纯音乐"
        "western" -> "欧美"
        "chinese" -> "华语"
        "japanesekorean" -> "日韩"
        "male" -> "男声"
        "female" -> "女声"
        "duet" -> "对唱"
        "group" -> "乐队"
        "energylow" -> "低能量"
        "energymid" -> "中能量"
        "energyhigh" -> "高能量"
        "temposlow" -> "慢歌"
        "tempomid" -> "中速"
        "tempofast" -> "快歌"
        "citypop" -> "city pop"
        "indiefolk" -> "indie folk"
        "singersongwriter" -> "singer songwriter"
        "rb" -> "R&B"
        "hiphop" -> "hip hop"
        "edm" -> "EDM"
        "lofi" -> "lo-fi"
        "dreampop" -> "dream pop"
        "altrock" -> "alt rock"
        else -> key
    }

private fun normalizeKey(s: String): String =
    s.lowercase().replace(Regex("[\\s'\"`·・\\-－—_,，。.、!?！？()（）\\[\\]【】《》<>&/]+"), "")
