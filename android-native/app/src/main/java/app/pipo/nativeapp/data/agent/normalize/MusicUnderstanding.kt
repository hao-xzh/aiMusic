package app.pipo.nativeapp.data.agent.normalize

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.domain.ContinuationMode
import app.pipo.nativeapp.data.agent.domain.ContinuationPolicy
import app.pipo.nativeapp.data.agent.domain.MusicStyleProfile
import app.pipo.nativeapp.data.agent.domain.ReferenceContext

/**
 * Consumer-grade music command understanding.
 *
 * 这不是关键词补丁，而是 Agent v2 的本地语义层：把“放嗨一点 / 忧郁一点 / 当前风格多来几首 / 开车听 /
 * 别太吵 / 继续刚才那个感觉”统一抽成结构化 styleProfile + referenceContext。LLM planner 输出不稳、
 * 空回复或过窄时，Normalizer 和 Resolver 仍能拿到同一份语义对象继续执行。
 */
object MusicUnderstanding {
    data class Result(
        val wantsPlayback: Boolean,
        val wantsInsertNext: Boolean,
        val wantsStyleExplanation: Boolean,
        val wantsContinuation: Boolean,
        val referenceContext: ReferenceContext,
        val desiredCount: Int?,
        val styleProfile: MusicStyleProfile,
        val continuationPolicy: ContinuationPolicy,
        val confidence: Double,
    ) {
        val hasActionableMusicMeaning: Boolean
            get() = wantsPlayback || wantsInsertNext || wantsContinuation || styleProfile.hasSignal
    }

    fun analyze(
        text: String,
        currentTrack: NativeTrack? = null,
        currentQueue: List<NativeTrack> = emptyList(),
        historySummary: String = "",
    ): Result {
        val key = CommandTextSignals.normalizeCommandText(text)
        val lower = text.lowercase()
        val wantsStyleExplanation = containsAny(key, "什么风格", "啥风格", "什么类型", "属于什么", "这首风格", "这歌风格")
        val reference = referenceContext(key, currentTrack, currentQueue, historySummary)
        val style = styleProfileFor(text, key, lower, currentTrack, reference)
        val insertNext = containsAny(key, "下一首插", "插到下一首", "下一首来", "下一首想听", "下一首放", "下一首听", "下一首播放", "接下来来", "接下来想听", "接下来放", "放完接", "不要打断", "别打断")
        val continuation = wantsContinuation(key, reference)
        val playbackVerb = containsAny(
            key,
            "想听", "听点", "听首", "听", "放点", "放首", "放", "播放", "来点", "来首", "来几首",
            "多来", "再来", "继续", "排一组", "换一组", "换点", "接几首", "给我排", "整点", "搞点",
        )
        val vibeOnlyPlayback = style.hasSignal && (
            key.endsWith("一点") || key.endsWith("点") || key.endsWith("些") ||
                containsAny(key, "更", "换", "多", "再", "继续", "来", "别太", "不要太")
        )
        val currentStylePlayback = reference != ReferenceContext.None && continuation
        val explicitMusicRequest = CommandTextSignals.looksLikeReplaceRequest(text) ||
            CommandTextSignals.genericCatalogRequest(text) ||
            CommandTextSignals.genericSimilarRequest(text)
        val wantsPlayback = !wantsStyleExplanation && (
            explicitMusicRequest ||
                playbackVerb && (style.hasSignal || reference != ReferenceContext.None) ||
                vibeOnlyPlayback ||
                currentStylePlayback
        )
        val continuationMode = when {
            containsAny(key, "不要续", "播完就停", "播完停", "只播这些", "只放这些") -> ContinuationMode.Disabled
            reference == ReferenceContext.CurrentStyle -> ContinuationMode.SameStyle
            reference == ReferenceContext.CurrentQueue -> ContinuationMode.SameQueue
            continuation || style.hasSignal || playbackVerb -> ContinuationMode.SameIntent
            else -> ContinuationMode.Default
        }
        return Result(
            wantsPlayback = wantsPlayback,
            wantsInsertNext = insertNext,
            wantsStyleExplanation = wantsStyleExplanation,
            wantsContinuation = continuation,
            referenceContext = reference,
            desiredCount = desiredCount(text, key),
            styleProfile = style,
            continuationPolicy = ContinuationPolicy(mode = continuationMode),
            confidence = when {
                wantsPlayback && style.hasSignal -> 0.82
                wantsPlayback -> 0.72
                style.hasSignal -> 0.64
                else -> 0.45
            },
        )
    }

    fun styleExplanation(text: String, currentTrack: NativeTrack?): String {
        val key = CommandTextSignals.normalizeCommandText(text)
        val style = styleProfileFor(text, key, text.lowercase(), currentTrack, ReferenceContext.CurrentTrack)
        val parts = mutableListOf<String>()
        if (style.energy != "any") parts.add(energyLabel(style.energy))
        parts.addAll(style.genres.take(3).map(::humanLabel))
        parts.addAll(style.moods.take(3).map(::humanLabel))
        parts.addAll(style.scenes.take(2).map(::humanLabel))
        val subject = currentTrack?.let { "这首《${it.title}》" } ?: "这首歌"
        return if (parts.isEmpty()) {
            "$subject 我还需要多听一点细节才能判断，但可以先按它现在的氛围给你续几首相近的。"
        } else {
            "$subject 大概是${parts.distinct().joinToString("、")}这一类感觉；要是喜欢这个味道，我可以直接按当前风格继续排。"
        }
    }

    private fun styleProfileFor(
        text: String,
        key: String,
        lower: String,
        currentTrack: NativeTrack?,
        referenceContext: ReferenceContext,
    ): MusicStyleProfile {
        val moods = mutableListOf<String>()
        val scenes = mutableListOf<String>()
        val genres = mutableListOf<String>()
        val textures = mutableListOf<String>()
        val quality = mutableListOf<String>()
        val vocalTypes = mutableListOf<String>()
        val refStyles = mutableListOf<String>()
        val avoid = mutableListOf<String>()

        fun addMood(vararg values: String) = moods.addAll(values)
        fun addScene(vararg values: String) = scenes.addAll(values)
        fun addGenre(vararg values: String) = genres.addAll(values)
        fun addTexture(vararg values: String) = textures.addAll(values)
        fun addQuality(vararg values: String) = quality.addAll(values)
        fun addAvoid(vararg values: String) = avoid.addAll(values)

        val hasHigh = containsAny(key, "嗨", "燃", "高能", "动感", "炸", "爽", "上头", "提神", "拉满", "蹦", "节奏强", "鼓点") ||
            containsAny(lower, "party", "dance", "edm", "workout")
        val asksNotLoud = containsAny(key, "不吵", "别太吵", "不要太吵", "别太炸", "不要太炸", "别突然大声", "不要突然大声")
        val energy = when {
            hasHigh && asksNotLoud -> "mid_high"
            hasHigh -> "high"
            containsAny(key, "低能", "低能量", "安静", "轻柔", "温柔", "舒缓", "放松", "睡前", "想睡", "降一点", "慢一点") -> "low"
            containsAny(key, "稳一点", "中等", "不要太猛", "别太猛", "别太闹", "不要太闹") -> "mid"
            else -> CommandTextSignals.energyHint(text)
        }.ifBlank { "any" }

        if (hasHigh) {
            addMood("energetic", "exciting", "uplifting")
            addScene("party")
            addTexture("rhythmic", "punchy")
            addQuality("catchy")
        }
        if (asksNotLoud) {
            addAvoid("loud", "explosive", "harsh")
            addTexture("controlled", "smooth")
        }
        if (containsAny(key, "忧郁", "伤感", "难过", "emo", "丧", "孤独", "失恋", "苦", "心碎", "凌晨")) {
            addMood("melancholic", "sad", "introspective")
            addScene("late-night")
            addTexture("atmospheric")
        }
        if (containsAny(key, "下雨", "雨天", "阴天")) {
            addMood("melancholic", "calm")
            addScene("rainy", "late-night")
            addTexture("atmospheric")
        }
        if (containsAny(key, "松弛", "chill", "慵懒", "微醺", "舒服", "不紧绷")) {
            addMood("chill", "relaxed")
            addTexture("smooth", "laid-back")
        }
        if (containsAny(key, "专注", "工作", "上班", "学习", "写代码", "coding", "focus")) {
            addScene("focus", "work")
            addTexture("steady", "minimal")
            addQuality("non-distracting")
        }
        if (containsAny(key, "开车", "车上", "路上", "通勤", "公路", "自驾")) {
            addScene("drive", "road-trip")
            addMood("uplifting")
            addTexture("steady")
        }
        if (containsAny(key, "睡前", "助眠", "想睡", "晚安")) {
            addScene("sleep", "late-night")
            addMood("calm", "soothing")
            addTexture("soft", "gentle")
        }
        if (containsAny(key, "浪漫", "甜", "暧昧", "约会")) {
            addMood("romantic", "warm")
            addTexture("smooth")
        }
        if (containsAny(key, "高级", "质感", "氛围感", "电影感")) {
            addQuality("sophisticated", "cinematic")
            addTexture("atmospheric")
        }

        if (containsAny(key, "摇滚", "rock")) addGenre("rock")
        if (containsAny(key, "民谣", "folk")) addGenre("folk")
        if (containsAny(key, "爵士", "jazz")) addGenre("jazz")
        if (containsAny(key, "电子", "edm", "电音", "dance")) addGenre("electronic", "dance")
        if (containsAny(key, "嘻哈", "说唱", "hiphop", "hip-hop", "rap")) addGenre("hip-hop", "rap")
        if (containsAny(key, "rnb", "r&b", "节奏布鲁斯")) addGenre("r&b")
        if (containsAny(key, "流行", "pop")) addGenre("pop")
        if (containsAny(key, "citypop", "city pop", "城市流行")) addGenre("city pop")
        if (containsAny(key, "独立", "indie")) addGenre("indie")
        if (containsAny(key, "粤语")) addGenre("cantopop")

        if (containsAny(key, "女声", "女生", "女歌手")) vocalTypes.add("female")
        if (containsAny(key, "男声", "男生", "男歌手")) vocalTypes.add("male")
        if (referenceContext != ReferenceContext.None) refStyles.add(referenceContext.name.lowercase())

        val languages = CommandTextSignals.languageIncludes(text, CommandTextSignals.excludeTerms(text))
        val hasStyleSignal = energy != "any" ||
            moods.isNotEmpty() ||
            scenes.isNotEmpty() ||
            genres.isNotEmpty() ||
            textures.isNotEmpty() ||
            quality.isNotEmpty() ||
            vocalTypes.isNotEmpty() ||
            refStyles.isNotEmpty() ||
            avoid.isNotEmpty() ||
            languages.isNotEmpty() ||
            referenceContext != ReferenceContext.None
        val semanticQuery = if (hasStyleSignal) {
            semanticQueryFor(text, currentTrack, referenceContext, energy, moods, scenes, genres, textures, quality)
        } else {
            ""
        }
        return MusicStyleProfile(
            semanticQuery = semanticQuery,
            energy = energy,
            moods = moods.distinctBy { it.lowercase() },
            scenes = scenes.distinctBy { it.lowercase() },
            genres = genres.distinctBy { it.lowercase() },
            textures = textures.distinctBy { it.lowercase() },
            qualityWords = quality.distinctBy { it.lowercase() },
            languages = languages,
            vocalTypes = vocalTypes.distinctBy { it.lowercase() },
            refStyles = refStyles.distinctBy { it.lowercase() },
            avoidTags = avoid.distinctBy { it.lowercase() },
            transitionStyle = transitionStyleFor(key),
            exploration = explorationFor(key),
        )
    }

    private fun semanticQueryFor(
        text: String,
        currentTrack: NativeTrack?,
        referenceContext: ReferenceContext,
        energy: String,
        moods: List<String>,
        scenes: List<String>,
        genres: List<String>,
        textures: List<String>,
        quality: List<String>,
    ): String {
        val terms = listOf(text, energy) + moods + scenes + genres + textures + quality
        val base = terms.joinToString(" ").trim()
        val anchor = currentTrack?.takeIf { referenceContext != ReferenceContext.None }?.let {
            "similar to current track ${it.artist} ${it.title}"
        }.orEmpty()
        return listOf(anchor, base).filter { it.isNotBlank() }.joinToString(" ").take(260)
    }

    private fun referenceContext(
        key: String,
        currentTrack: NativeTrack?,
        currentQueue: List<NativeTrack>,
        historySummary: String,
    ): ReferenceContext = when {
        containsAny(key, "当前风格", "这个风格", "这种风格", "这首风格", "这个感觉", "这种感觉", "当前感觉", "这味", "这个味") -> ReferenceContext.CurrentStyle
        containsAny(key, "这首", "当前这首", "现在这首", "这歌") && currentTrack != null -> ReferenceContext.CurrentTrack
        containsAny(key, "当前队列", "这个队列", "这组歌", "这个歌单") && currentQueue.isNotEmpty() -> ReferenceContext.CurrentQueue
        containsAny(key, "刚才那个", "刚刚那个", "上面那个", "之前那个", "刚才的要求", "刚才那个感觉") || historySummary.isNotBlank() && containsAny(key, "继续", "再来", "多来") -> ReferenceContext.PreviousIntent
        else -> ReferenceContext.None
    }

    private fun wantsContinuation(key: String, reference: ReferenceContext): Boolean =
        containsAny(key, "继续", "接着", "再来", "多来", "多放", "来几首", "再放几首", "接几首", "续", "续上") ||
            reference in setOf(ReferenceContext.CurrentStyle, ReferenceContext.PreviousIntent, ReferenceContext.CurrentQueue)

    private fun desiredCount(text: String, key: String): Int? =
        CommandTextSignals.explicitDesiredCount(text) ?: when {
            containsAny(key, "一首", "一曲") -> 1
            containsAny(key, "两首", "二首") -> 2
            containsAny(key, "几首", "几首歌", "多来几首", "再放几首", "接几首") -> 8
            containsAny(key, "一组", "歌单", "列表", "排") -> 12
            else -> null
        }

    private fun transitionStyleFor(key: String): String = when {
        containsAny(key, "顺一点", "顺滑", "不要硬切", "别硬切", "像电台", "接得自然", "无缝") -> "radio"
        containsAny(key, "睡前", "别突然", "不要突然") -> "gentle"
        containsAny(key, "嗨", "燃", "派对", "蹦") -> "energy_up"
        else -> "soft"
    }

    private fun explorationFor(key: String): String = when {
        containsAny(key, "熟悉", "常听", "别乱推荐", "别太偏") -> "safe"
        containsAny(key, "新鲜", "冷门", "挖一点", "不一样", "探索") -> "explore"
        else -> "balanced"
    }

    private fun containsAny(value: String, vararg needles: String): Boolean = needles.any { it in value }

    private fun energyLabel(value: String): String = when (value) {
        "high" -> "高能、偏嗨"
        "mid_high" -> "有劲但不炸"
        "mid" -> "中等能量"
        "low" -> "低能量、偏安静"
        else -> "能量适中"
    }

    private fun humanLabel(value: String): String = when (value) {
        "energetic" -> "有冲劲"
        "exciting" -> "兴奋感"
        "uplifting" -> "上扬"
        "melancholic" -> "忧郁"
        "sad" -> "伤感"
        "introspective" -> "内省"
        "late-night" -> "深夜感"
        "rainy" -> "雨天氛围"
        "chill" -> "松弛"
        "relaxed" -> "放松"
        "party" -> "派对感"
        "drive" -> "适合开车"
        "focus" -> "适合专注"
        "sleep" -> "睡前"
        "smooth" -> "顺滑"
        "atmospheric" -> "氛围感"
        "rhythmic" -> "节奏感"
        "punchy" -> "鼓点明显"
        else -> value
    }
}
