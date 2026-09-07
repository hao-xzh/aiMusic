package app.pipo.nativeapp.data.agent.domain

import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PetMemory
import app.pipo.nativeapp.data.PetPersona

data class AgentTurnInput(
    val userText: String,
    val history: List<PetMemory.ConversationTurn>,
    val historySummary: String = "",
    val musicReferences: List<PetMemory.MusicReference> = emptyList(),
    val currentTrack: NativeTrack?,
    val currentQueue: List<NativeTrack> = emptyList(),
    val userFacts: String,
    val persona: PetPersona = PetPersona.DEFAULT,
)

data class MusicTurnPlan(
    val turnId: String,
    val userText: String,
    val actions: List<PlannedAction>,
    val isRepair: Boolean = false,
    val repairTargetTurnId: String? = null,
    val confidence: Double = 1.0,
    val replyHint: String = "",
    val plannerRaw: String = "",
    val musicReferences: List<PetMemory.MusicReference> = emptyList(),
)

sealed class PlannedAction {
    abstract val actionId: String

    data class PlayRequest(
        override val actionId: String,
        val mode: PlayMode,
        val primaryGoal: MusicGoal = MusicGoal(),
        val target: TrackRequirement? = null,
        val desiredCount: Int = 12,
        val similar: Boolean = false,
        val jumpToInserted: Boolean = false,
        val preserveCurrent: Boolean = false,
    ) : PlannedAction()

    data class PlayTracks(
        override val actionId: String,
        val mode: PlayMode,
        val tracks: List<NativeTrack>,
        val continuous: ContinuousQueueSource?,
        val primaryGoal: MusicGoal = MusicGoal(),
        val target: TrackRequirement? = null,
        val similar: Boolean = false,
        val jumpToInserted: Boolean = false,
        val preserveCurrent: Boolean = false,
    ) : PlannedAction()

    data class PlayPlaylist(
        override val actionId: String,
        val name: String,
        val tracks: List<NativeTrack>,
    ) : PlannedAction()

    data class LikeCurrent(
        override val actionId: String,
        val like: Boolean,
    ) : PlannedAction()

    data class LikeTrack(
        override val actionId: String,
        val like: Boolean,
        val target: TrackRequirement,
    ) : PlannedAction()

    data class ModifyPlaylist(
        override val actionId: String,
        val add: Boolean,
        val playlistName: String,
    ) : PlannedAction()

    data class CreatePlaylist(
        override val actionId: String,
        val playlistName: String,
        val tracks: List<TrackRequirement>,
    ) : PlannedAction()

    data class SkipCurrent(
        override val actionId: String,
    ) : PlannedAction()

    data class Say(
        override val actionId: String,
        val text: String,
    ) : PlannedAction()

    data class Clarify(
        override val actionId: String,
        val question: String,
    ) : PlannedAction()
}

enum class PlayMode {
    ReplaceQueue,
    InsertNext,
    PlayNow,
}

enum class ArtistScope {
    Strict,
    Focus,
    Similar,
}

data class MusicGoal(
    val primaryArtists: List<String> = emptyList(),
    val artistScope: ArtistScope = ArtistScope.Strict,
    val playlistName: String = "",
    val primaryTracks: List<TrackRequirement> = emptyList(),
    val mustInclude: List<TrackRequirement> = emptyList(),
    val closer: TrackRequirement? = null,
    val excludeTerms: List<String> = emptyList(),
    val hardGenres: List<String> = emptyList(),
    val hardLanguages: List<String> = emptyList(),
    val hardVocalTypes: List<String> = emptyList(),
    val softMoods: List<String> = emptyList(),
    val softScenes: List<String> = emptyList(),
    val softTextures: List<String> = emptyList(),
    val softQualityWords: List<String> = emptyList(),
    val softEnergy: String = "any",
    val softTempoFeel: String = "any",
    val refStyles: List<String> = emptyList(),
    val aiMainStyles: List<String> = emptyList(),
    val aiAdjacentStyles: List<String> = emptyList(),
    val aiAvoidStyles: List<String> = emptyList(),
    val searchSeeds: List<String> = emptyList(),
    /** LLM 对本次选歌边界的显式判断；Unknown 不允许进入自动提交。 */
    val selectionMode: MusicSelectionMode = MusicSelectionMode.Unknown,
    /**
     * 用户明确点名的目录实体，例如专辑、原声、音乐剧、影视/游戏作品或其它具名作品。
     *
     * 这不是用关键词猜意图：由 LLM 根据完整语境提供原名、可出现在曲目元数据里的
     * 别名，以及适合目录检索的查询。Resolver 用它防止“明确要 A，最后却按画像播放 B”；
     * 开放式情绪/场景/风格推荐保持为空，继续允许画像探索和相邻风格扩展。
     */
    val catalogConstraint: CatalogConstraint = CatalogConstraint(),
    val useCurrentStyleAnchor: Boolean = false,
    val continuationKey: String = "",
    /**
     * V2 Agent 的主语义槽。
     *
     * 旧链路只能把用户话压成歌手/歌名/歌单，
     * “嗨一点 / 忧郁一点 / 当前风格多来几首 / 开车听”会被丢掉。
     * 这里把模糊风格、情绪、场景、语言、人声、避开条件都保留下来，
     * Resolver、SemanticRecall、QueueCompiler、续播 source 共用这一份结构。
     */
    val styleProfile: MusicStyleProfile = MusicStyleProfile(),
    val referenceContext: ReferenceContext = ReferenceContext.None,
    val continuationPolicy: ContinuationPolicy = ContinuationPolicy.Default,
    /** 用户要求“中间加一首周杰伦 / 夹一点女歌手 / 加一个别的歌手”时的艺人槽。 */
    val includeArtists: List<String> = emptyList(),
)

data class CatalogConstraint(
    /** 用户原话中的具名对象，主要用于诊断和回复，不直接依赖中文规则解析。 */
    val name: String = "",
    /** 可能真实出现在 album 元数据中的完整原名、译名或通行别名。 */
    val aliases: List<String> = emptyList(),
    /** LLM 针对音乐目录生成的精确查询，可包含原文、原名和版本限定。 */
    val searchQueries: List<String> = emptyList(),
) {
    val isActive: Boolean
        get() = name.isNotBlank() || aliases.isNotEmpty() || searchQueries.isNotEmpty()

    val matchTerms: List<String>
        get() = aliases
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter(::isVerifiableMetadataAlias)
            .distinctBy { it.lowercase() }

    /**
     * 至少有一个非泛类目的 album 元数据名。单词专辑名仍可精确相等，避免误伤
     * Thriller/1989 这类真实目录；只有更完整的别名才允许子串匹配。
     */
    val hasVerifiableMetadataAlias: Boolean
        get() = matchTerms.isNotEmpty()

    companion object {
        private val genericLatinTokens = setOf(
            "a", "an", "the", "music", "song", "songs", "album", "soundtrack", "ost", "musical",
            "movie", "film", "game", "anime", "original", "motion", "picture", "score", "cast",
            "recording", "broadway", "deluxe", "edition", "version", "series",
        )
        private val genericCjkAliases = setOf(
            "音乐", "歌曲", "专辑", "原声", "原声带", "音乐剧", "电影", "影视", "游戏", "动漫", "作品", "系列",
        )

        fun isVerifiableMetadataAlias(raw: String): Boolean {
            val value = raw.trim()
            if (value.isBlank() || value in genericCjkAliases) return false
            val cjkCount = value.count { ch -> Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN }
            if (cjkCount >= 2) return true
            val tokens = latinTokens(value)
            if (tokens.isEmpty()) return false
            val distinctive = tokens.filterNot(genericLatinTokens::contains)
            if (distinctive.isEmpty()) return false
            // “Hamilton Musical / Frozen Soundtrack”不是完整元数据名，既不能做子串锚点，
            // 也不能作为唯一作品别名；单独的 Hamilton/Frozen 仍保留 album 精确相等能力。
            if (tokens.size in 2..3 && distinctive.size == 1 && tokens.any(genericLatinTokens::contains)) {
                return false
            }
            return true
        }

        fun isStrongMetadataAlias(raw: String): Boolean {
            val value = raw.trim()
            if (value.isBlank()) return false
            val cjkCount = value.count { ch -> Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN }
            if (cjkCount >= 2) return true
            val tokens = latinTokens(value)
            val distinctiveCount = tokens.count { it !in genericLatinTokens }
            return distinctiveCount >= 2 || distinctiveCount >= 1 && tokens.size >= 4
        }

        private fun latinTokens(value: String): List<String> =
            Regex("[A-Za-z0-9]+").findAll(value).map { it.value.lowercase() }.toList()
    }
}

enum class MusicSelectionMode {
    Unknown,
    ExactCatalog,
    ExactTrack,
    ArtistFocus,
    Playlist,
    OpenRecommendation,
    ContextualContinuation,
}

data class MusicStyleProfile(
    val semanticQuery: String = "",
    val energy: String = "any",
    val moods: List<String> = emptyList(),
    val scenes: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val textures: List<String> = emptyList(),
    val qualityWords: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val vocalTypes: List<String> = emptyList(),
    val refStyles: List<String> = emptyList(),
    val avoidTags: List<String> = emptyList(),
    val transitionStyle: String = "soft",
    val exploration: String = "balanced",
) {
    val hasSignal: Boolean
        get() = semanticQuery.isNotBlank() ||
            energy != "any" ||
            moods.isNotEmpty() ||
            scenes.isNotEmpty() ||
            genres.isNotEmpty() ||
            textures.isNotEmpty() ||
            qualityWords.isNotEmpty() ||
            languages.isNotEmpty() ||
            vocalTypes.isNotEmpty() ||
            refStyles.isNotEmpty() ||
            avoidTags.isNotEmpty() ||
            transitionStyle != "soft" ||
            exploration != "balanced"

    fun mergedWith(other: MusicStyleProfile): MusicStyleProfile = MusicStyleProfile(
        semanticQuery = semanticQuery.ifBlank { other.semanticQuery },
        energy = if (energy != "any") energy else other.energy,
        moods = mergeDistinct(moods, other.moods),
        scenes = mergeDistinct(scenes, other.scenes),
        genres = mergeDistinct(genres, other.genres),
        textures = mergeDistinct(textures, other.textures),
        qualityWords = mergeDistinct(qualityWords, other.qualityWords),
        languages = mergeDistinct(languages, other.languages),
        vocalTypes = mergeDistinct(vocalTypes, other.vocalTypes),
        refStyles = mergeDistinct(refStyles, other.refStyles),
        avoidTags = mergeDistinct(avoidTags, other.avoidTags),
        transitionStyle = if (transitionStyle != "soft") transitionStyle else other.transitionStyle,
        exploration = if (exploration != "balanced") exploration else other.exploration,
    )

    companion object {
        private fun mergeDistinct(first: List<String>, second: List<String>): List<String> {
            val out = ArrayList<String>(first.size + second.size)
            val seen = HashSet<String>()
            for (value in first + second) {
                val trimmed = value.trim()
                val key = trimmed.lowercase()
                if (trimmed.isNotBlank() && seen.add(key)) out.add(trimmed)
            }
            return out
        }
    }
}

enum class ReferenceContext {
    None,
    CurrentTrack,
    CurrentStyle,
    CurrentQueue,
    PreviousIntent,
    MentionedTrack,
}

data class ContinuationPolicy(
    val mode: ContinuationMode = ContinuationMode.Default,
    val preserveWhenInserting: Boolean = true,
    val invalidatePreviousOnReplace: Boolean = true,
) {
    companion object {
        val Default = ContinuationPolicy()
    }
}

enum class ContinuationMode {
    Default,
    Disabled,
    SameIntent,
    SameStyle,
    SameQueue,
}

data class TrackRequirement(
    val title: String,
    val artist: String? = null,
    val placement: TrackPlacement = TrackPlacement.MustInclude,
)

enum class TrackPlacement {
    Now,
    Next,
    MustInclude,
    Closer,
}

data class QueuePlan(
    val actions: List<PlannedAction>,
    val validation: QueueValidation,
)

data class QueueValidation(
    val passed: Boolean,
    val messages: List<String> = emptyList(),
    val primarySatisfied: Boolean = true,
    val mustIncludeSatisfied: Boolean = true,
    val closerSatisfied: Boolean = true,
)

data class ActionExecutionResult(
    val actionId: String,
    val type: String,
    val success: Boolean,
    val message: String,
    val tracks: List<NativeTrack> = emptyList(),
    val acceptedByPlayer: Boolean = success,
    val actuallyStarted: Boolean = false,
    val currentTrack: NativeTrack? = null,
    val queueSnapshot: List<NativeTrack> = tracks,
    val insertedTrack: NativeTrack? = null,
    val likedTrack: NativeTrack? = null,
    val playlistName: String? = null,
    val insert: Boolean = false,
    val similar: Boolean = false,
    val warnings: List<String> = emptyList(),
    val errorMessage: String? = null,
    val committedQueueSummary: CommittedQueueSummary? = null,
)

data class CommittedQueueSummary(
    val requestId: String,
    val queueVersion: Long,
    val operation: String,
    val accepted: Boolean,
    val trackCount: Int,
    val firstTitle: String,
    val insertedTitle: String = "",
    val mixMode: String = "",
    val transitionFeel: String = "",
    val energyCurve: String = "",
    val smoothnessAvg: Double = 1.0,
    val validationPassed: Boolean = true,
    val reordered: Boolean = false,
    val warnings: List<String> = emptyList(),
)

data class AgentUiCard(
    val kind: Kind,
    val label: String,
    val ok: Boolean = true,
    val count: Int = 0,
    val artists: String = "",
    val covers: List<String?> = emptyList(),
    val insert: Boolean = false,
    val similar: Boolean = false,
) {
    enum class Kind {
        Play,
        Skip,
        Like,
        Unlike,
        PlaylistAdd,
        PlaylistRemove,
        Error,
    }
}

data class TurnOutcome(
    val reply: String,
    val cards: List<AgentUiCard>,
    val trace: TurnTrace,
    val musicReferences: List<PetMemory.MusicReference> = emptyList(),
)

data class TurnTrace(
    val turnId: String,
    val plannerRaw: String = "",
    val normalizedPlan: String = "",
    val resolution: String = "",
    val queuePlan: String = "",
    val validation: String = "",
    val execution: String = "",
    val finalReply: String = "",
)
