package app.pipo.nativeapp.data

/**
 * 单曲语义档案 —— 镜像 src/lib/track-semantic-profile.ts。
 *
 * 跟 React 端字段对齐，version=1，便于以后 LLM schema 升级时直接 invalidate 旧缓存。
 *
 * 用途：
 *   - PetAgent.commentOnTrack 让 Claudio 知道这首歌的 moods/scenes/genres，说人话点评
 *   - DistillEngine 蒸馏时把这些 tag 一起喂给口味画像，比纯 metadata 准
 *   - 将来召回排序时按 hardConstraints / softPreferences 对齐打分
 */

internal const val TRACK_SEMANTIC_VERSION = 1

enum class TrackLanguage(val key: String) {
    English("english"),
    Mandarin("mandarin"),
    Cantonese("cantonese"),
    Japanese("japanese"),
    Korean("korean"),
    Instrumental("instrumental"),
    Mixed("mixed"),
    Unknown("unknown");

    companion object {
        fun from(value: String?): TrackLanguage = entries.firstOrNull { it.key == value?.lowercase() } ?: Unknown
    }
}

enum class TrackRegion(val key: String) {
    Western("western"),
    Chinese("chinese"),
    JapaneseKorean("japanese_korean"),
    Other("other"),
    Unknown("unknown");

    companion object {
        fun from(value: String?): TrackRegion = entries.firstOrNull { it.key == value?.lowercase() } ?: Unknown
    }
}

enum class VocalType(val key: String) {
    Male("male"),
    Female("female"),
    Duet("duet"),
    Group("group"),
    Instrumental("instrumental"),
    Unknown("unknown");

    companion object {
        fun from(value: String?): VocalType = entries.firstOrNull { it.key == value?.lowercase() } ?: Unknown
    }
}

data class TrackSemanticProfile(
    val trackId: String,
    val version: Int = TRACK_SEMANTIC_VERSION,
    // identity
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val year: Int? = null,
    // language / region
    val language: TrackLanguage = TrackLanguage.Unknown,
    val languageConfidence: Double = 0.5,
    val region: TrackRegion = TrackRegion.Unknown,
    val regionConfidence: Double = 0.5,
    // style
    val genres: List<String> = emptyList(),
    val subGenres: List<String> = emptyList(),
    val styleAnchors: List<String> = emptyList(),
    // vibe
    val moods: List<String> = emptyList(),
    val scenes: List<String> = emptyList(),
    val textures: List<String> = emptyList(),
    val energyWords: List<String> = emptyList(),
    val tempoFeel: List<String> = emptyList(),
    // vocal
    val vocalType: VocalType = VocalType.Unknown,
    val vocalDelivery: List<String> = emptyList(),
    // era
    val decade: String? = null,
    // audio hints (selected from AudioFeatures)
    val bpm: Double? = null,
    val energy: Double? = null,
    // negative
    val negativeTags: List<String> = emptyList(),
    val summary: String = "",
    val embeddingText: String = "",
    val confidence: Double = 0.5,
    // sources flags
    val sourceMetadata: Boolean = true,
    val sourceLyrics: Boolean = false,
    val sourceAudio: Boolean = false,
    val sourceLlm: Boolean = false,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    /** 给 PetAgent commentOnTrack 用的简短特征 line */
    fun briefForComment(): String? {
        val parts = listOf(
            summary.takeIf { it.isNotBlank() },
            moods.take(3).joinToString("/").takeIf { it.isNotBlank() }?.let { "情绪=$it" },
            scenes.take(2).joinToString("/").takeIf { it.isNotBlank() }?.let { "场景=$it" },
            genres.take(2).joinToString("/").takeIf { it.isNotBlank() }?.let { "风格=$it" },
        ).filterNotNull()
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }
}
