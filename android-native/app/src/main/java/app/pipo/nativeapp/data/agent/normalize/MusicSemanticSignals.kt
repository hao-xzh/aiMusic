package app.pipo.nativeapp.data.agent.normalize

/**
 * 面向消费者自然语言的音乐语义信号层。
 *
 * 这里不做最终选歌，也不把模糊话术硬编码成固定歌单；它只把“嗨一点 / 忧郁一点 /
 * 当前风格多来几首 / 开车听 / 别太吵”等开放表达翻译成可召回、可排序、可续播的
 * 软约束和搜索种子，后续仍由 CandidateRecall / CandidateRanker / 无缝队列优化决定具体歌曲。
 */
data class ConsumerMusicSemantics(
    val energy: String = "any",
    val tempoFeel: String = "any",
    val genres: List<String> = emptyList(),
    val moods: List<String> = emptyList(),
    val scenes: List<String> = emptyList(),
    val textures: List<String> = emptyList(),
    val qualityWords: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val vocalTypes: List<String> = emptyList(),
    val avoidTags: List<String> = emptyList(),
    val aiMainStyles: List<String> = emptyList(),
    val aiAdjacentStyles: List<String> = emptyList(),
    val aiAvoidStyles: List<String> = emptyList(),
    val searchSeeds: List<String> = emptyList(),
    val wantsCurrentStyle: Boolean = false,
    val wantsMoreLikeCurrent: Boolean = false,
    val asksStyleQuestion: Boolean = false,
) {
    val hasSignal: Boolean
        get() = energy != "any" || tempoFeel != "any" || genres.isNotEmpty() || moods.isNotEmpty() ||
            scenes.isNotEmpty() || textures.isNotEmpty() || qualityWords.isNotEmpty() || languages.isNotEmpty() ||
            vocalTypes.isNotEmpty() || avoidTags.isNotEmpty() || aiMainStyles.isNotEmpty() ||
            aiAdjacentStyles.isNotEmpty() || aiAvoidStyles.isNotEmpty() || searchSeeds.isNotEmpty() ||
            wantsCurrentStyle || wantsMoreLikeCurrent || asksStyleQuestion

    val playableSignal: Boolean
        get() = hasSignal && !asksStyleQuestion
}

object MusicSemanticSignals {
    private val playCues = listOf(
        "放", "播放", "想听", "听点", "听首", "来点", "来些", "来几首", "多来", "排", "换", "继续", "接着",
    )

    fun extract(text: String): ConsumerMusicSemantics {
        val key = CommandTextSignals.normalizeCommandText(text)
        if (key.isBlank()) return ConsumerMusicSemantics()

        val genres = mutableListOf<String>()
        val moods = mutableListOf<String>()
        val scenes = mutableListOf<String>()
        val textures = mutableListOf<String>()
        val quality = mutableListOf<String>()
        val languages = mutableListOf<String>()
        val vocalTypes = mutableListOf<String>()
        val avoidTags = mutableListOf<String>()
        val mainStyles = mutableListOf<String>()
        val adjacentStyles = mutableListOf<String>()
        val avoidStyles = mutableListOf<String>()
        val seeds = mutableListOf<String>()
        var energy = "any"
        var tempo = "any"

        fun mentioned(vararg words: String): Boolean = words.any { it in key }
        fun addSeeds(vararg values: String) { values.filterTo(seeds) { it.isNotBlank() } }

        // 语言 / 地区：这里是硬偏好，但仍允许 Resolver 结合用户排除词做过滤。
        if (mentioned("华语", "中文", "国语", "普通话", "中文歌")) languages += "mandarin"
        if (mentioned("粤语")) languages += "cantonese"
        if (mentioned("英文", "英语", "欧美", "欧美歌", "english")) languages += "english"
        if (mentioned("日语", "日文", "japanese")) languages += "japanese"
        if (mentioned("韩语", "韩文", "korean")) languages += "korean"

        // 明确风格词。
        if (mentioned("r&b", "rnb", "节奏布鲁斯")) {
            genres += "r&b"; moods += listOf("chill", "smooth"); scenes += listOf("night", "city walk")
            textures += listOf("smooth", "warm"); addSeeds("R&B 律动", "夜晚 R&B", "华语 R&B")
        }
        if (mentioned("摇滚", "rock")) { genres += "rock"; textures += "guitar"; addSeeds("燃 摇滚", "摇滚 热歌") }
        if (mentioned("民谣", "folk")) { genres += "folk"; textures += listOf("acoustic", "warm"); addSeeds("温柔 民谣", "民谣 治愈") }
        if (mentioned("爵士", "jazz")) { genres += "jazz"; textures += listOf("smooth", "sophisticated"); addSeeds("爵士 放松", "jazz chill") }
        if (mentioned("电子", "电音", "edm", "dance", "house")) { genres += "electronic"; scenes += "party"; textures += "dance"; addSeeds("电子 动感", "EDM 派对") }
        if (mentioned("嘻哈", "说唱", "rap", "hiphop", "hip-hop")) { genres += "hip-hop"; textures += "rhythmic"; addSeeds("说唱 热门", "hip hop 节奏") }
        if (mentioned("citypop", "city pop", "城市流行")) { genres += "pop"; mainStyles += "city pop"; addSeeds("city pop", "城市流行") }
        if (mentioned("lofi", "lo-fi", "lofi")) { textures += listOf("lo-fi", "soft"); scenes += "focus"; addSeeds("lofi 学习", "lo-fi chill") }

        // 能量 / 情绪 / 场景：用户说“嗨一点”必须能落到召回和在线搜索。
        if (mentioned("嗨", "嗨一点", "燃", "燃一点", "炸", "高能", "高能量", "动感", "带劲", "上头", "提神", "拉满", "蹦迪", "派对")) {
            energy = if (mentioned("别太炸", "不要太炸", "没那么炸", "不太炸")) "mid_high" else "high"
            tempo = "fast"
            moods += listOf("energetic", "excited", "uplifting")
            scenes += listOf("party", "workout", "driving")
            textures += listOf("punchy", "rhythmic", "dance")
            mainStyles += listOf("energetic", "high", "party")
            addSeeds("高能 华语 热歌", "燃 向上 歌曲", "派对 动感 热歌", "节奏感 强 歌曲", "健身 高燃 音乐")
        }
        if (mentioned("轻快", "有活力", "活泼", "开心", "明亮", "阳光", "快乐")) {
            if (energy == "any") energy = "mid_high"
            if (tempo == "any") tempo = "fast"
            moods += listOf("happy", "bright", "uplifting")
            scenes += listOf("commute", "morning")
            textures += listOf("bouncy", "clean")
            mainStyles += listOf("happy", "bright")
            addSeeds("轻快 开心 流行", "阳光 活力 歌曲")
        }
        if (mentioned("安静", "舒缓", "放松", "轻柔", "温柔", "治愈", "睡前", "助眠", "不吵", "别太吵", "不要太吵", "柔和")) {
            energy = if (energy == "high") "mid" else "low"
            tempo = if (tempo == "fast") "medium" else "slow"
            moods += listOf("calm", "relaxed", "tender", "chill")
            scenes += listOf("night", "sleep", "focus")
            textures += listOf("soft", "minimal", "warm")
            mainStyles += listOf("calm", "chill")
            addSeeds("安静 治愈 歌曲", "温柔 放松 音乐", "睡前 舒缓 歌曲", "深夜 温柔")
        }
        if (mentioned("忧郁", "郁一点", "emo", "难过", "伤感", "失恋", "丧", "孤独", "寂寞", "心碎", "凌晨", "雨天", "下雨")) {
            if (energy == "any" || energy == "high") energy = "mid_low"
            if (tempo == "any") tempo = "slow"
            moods += listOf("melancholic", "sad", "lonely", "night")
            scenes += listOf("late-night", "rainy day", "city walk")
            textures += listOf("atmospheric", "soft")
            mainStyles += listOf("melancholic", "night")
            addSeeds("伤感 华语 歌曲", "emo 流行", "深夜 孤独 歌曲", "雨天 忧郁 音乐")
        }
        if (mentioned("开车", "车上", "公路", "通勤")) {
            scenes += listOf("driving", "commute")
            if (energy == "any") energy = "mid"
            addSeeds("开车 歌单", "公路 音乐", "通勤 好听 歌曲")
        }
        if (mentioned("学习", "工作", "写代码", "编程", "专注")) {
            scenes += listOf("focus", "coding")
            textures += listOf("minimal", "smooth")
            if (energy == "any") energy = "mid_low"
            addSeeds("学习 专注 音乐", "写代码 歌曲", "工作 背景 音乐")
        }
        if (mentioned("高级", "质感", "氛围", "氛围感", "电影感")) {
            quality += listOf("sophisticated", "atmospheric")
            textures += listOf("atmospheric", "smooth")
            mainStyles += "atmospheric"
            addSeeds("高级感 音乐", "氛围感 歌曲")
        }
        if (mentioned("女声", "女生唱", "女歌手")) vocalTypes += "female"
        if (mentioned("男声", "男生唱", "男歌手")) vocalTypes += "male"
        if (mentioned("纯音乐", "无人声", "instrumental")) vocalTypes += "instrumental"

        if (mentioned("不要说唱", "别说唱", "不想听说唱")) { avoidTags += "rap-heavy"; avoidStyles += "hip-hop" }
        if (mentioned("不要电音", "别电音", "不想听电音")) { avoidTags += "party"; avoidStyles += "electronic" }
        if (mentioned("别太吵", "不要太吵", "不吵")) { avoidTags += listOf("noisy", "aggressive"); avoidStyles += listOf("aggressive", "noisy") }

        val wantsCurrentStyle = mentioned(
            "当前风格", "这首风格", "这个风格", "这种风格", "当前这种", "这种感觉", "这个感觉", "这个调调", "刚才那个感觉", "刚才这种",
        ) || (mentioned("类似这首", "像这首", "跟这首类似") && !mentioned("歌单"))
        val wantsMoreLikeCurrent = wantsCurrentStyle && mentioned("多来", "来几首", "再来", "继续", "接着", "多放", "多排")
        val asksStyleQuestion = mentioned("什么风格", "什么类型", "啥风格", "啥类型", "这首歌风格", "这歌风格", "这首什么歌路")

        val langPrefix = when {
            "english" in languages -> "英文"
            "cantonese" in languages -> "粤语"
            "mandarin" in languages -> "华语"
            else -> ""
        }
        if (langPrefix.isNotBlank()) {
            val withLang = seeds.take(4).map { seed -> "$langPrefix $seed" }
            seeds.addAll(0, withLang)
        }

        return ConsumerMusicSemantics(
            energy = energy,
            tempoFeel = tempo,
            genres = dedupe(genres),
            moods = dedupe(moods),
            scenes = dedupe(scenes),
            textures = dedupe(textures),
            qualityWords = dedupe(quality),
            languages = dedupe(languages),
            vocalTypes = dedupe(vocalTypes),
            avoidTags = dedupe(avoidTags),
            aiMainStyles = dedupe(mainStyles + genres + moods.take(4) + scenes.take(3)),
            aiAdjacentStyles = dedupe(adjacentStyles),
            aiAvoidStyles = dedupe(avoidStyles),
            searchSeeds = dedupe(seeds).take(12),
            wantsCurrentStyle = wantsCurrentStyle,
            wantsMoreLikeCurrent = wantsMoreLikeCurrent,
            asksStyleQuestion = asksStyleQuestion,
        )
    }

    fun looksLikeFuzzyMusicRequest(text: String): Boolean {
        val semantics = extract(text)
        if (!semantics.playableSignal) return false
        val key = CommandTextSignals.normalizeCommandText(text)
        if (semantics.wantsMoreLikeCurrent || semantics.wantsCurrentStyle) return true
        return playCues.any { it in key } || CommandTextSignals.isCatalogDescriptor(text)
    }

    fun looksLikeStyleQuestion(text: String): Boolean = extract(text).asksStyleQuestion

    private fun dedupe(values: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (value in values) {
            val normalized = value.trim().lowercase()
            if (normalized.isNotBlank()) seen.add(normalized)
        }
        return seen.toList()
    }
}
