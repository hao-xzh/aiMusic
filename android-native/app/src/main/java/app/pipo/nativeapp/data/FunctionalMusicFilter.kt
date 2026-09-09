package app.pipo.nativeapp.data

/**
 * 网易搜索的“功能性音乐”护栏。
 *
 * 用情绪/场景词（舒缓、放松、安静…）搜索时，返回结果大量是助眠/冥想/养生类
 * 流水线内容（“脑波催眠版”“5分钟入睡催眠曲”“轻松养生馆”…）。主队列有 LLM
 * 逐首把关，但续杯/在线兜底是无人值守的——必须在结果侧统一拦截，否则一次
 * 自动续杯就能把电台灌满纯音乐（实际事故：情绪词续杯追加 12 首助眠曲）。
 *
 * 标题与歌手名一起匹配；调用方负责放行条件（用户明确要纯音乐、点名的歌手、
 * 当前锚点本身就是功能性音乐等）。
 */
object FunctionalMusicFilter {
    private val RELIGIOUS = listOf("大悲咒", "佛教", "佛经", "佛乐", "佛音", "念佛", "诵经", "心经", "六字大明咒", "南无", "阿弥陀佛", "观音心咒", "宗教音乐", "赞美诗", "圣歌", "buddhist", "buddhism", "sutra", "mantra", "gregorian chant", "worship music", "gospel")
    private val NEGATION = Regex("不要|不想|不喜欢|别放|别来|不听|排除|避开|讨厌|不含|拒绝|without|don't|do not|avoid|no ", RegexOption.IGNORE_CASE)
    private val CATEGORY_SPACING = Regex("[\\s\\p{P}\\p{S}]+")
    private val RELIGIOUS_KEYS = RELIGIOUS.map { it.replace(CATEGORY_SPACING, "") }

    fun isReligious(title: String, artist: String, album: String = ""): Boolean {
        val text = "$title $artist $album".lowercase().replace(CATEGORY_SPACING, "")
        return RELIGIOUS_KEYS.any { it in text }
    }

    fun isReligious(track: NativeTrack): Boolean = isReligious(track.title, track.artist, track.album)

    private fun positiveMention(text: String, words: List<String>): Boolean =
        text.lowercase().split(Regex("[，。；！？,;.!?\\n]" )).any { clause ->
            words.any { word ->
                val at = clause.indexOf(word)
                at >= 0 && !NEGATION.containsMatchIn(clause.substring(0, at))
            }
        }

    /** Quiet/relaxed is an acoustic request; it never grants a religious category. */
    fun allowsReligious(request: String): Boolean = positiveMention(request, RELIGIOUS)

    fun acceptsCategory(track: NativeTrack, request: String): Boolean =
        !isReligious(track) || allowsReligious(request)
    private val KEYWORDS = listOf(
        "纯音乐", "轻音乐", "催眠", "助眠", "入睡", "安眠", "哄睡", "睡眠音乐",
        "冥想", "白噪音", "白噪声", "胎教", "疗愈", "理疗", "养生", "八音盒",
        "脑波", "脑部", "spa", "安神", "静心", "瑜伽", "禅修", "钢琴曲集",
        "写代码", "编程", "专注音乐", "工作音乐", "学习音乐",
        "meditation", "white noise", "sleep music", "relaxing music",
        "study music", "healing music", "bgm合集",
        "coding", "programming music", "deep focus", "focus music", "concentration",
        "work music", "study beats", "lofi beats", "lo-fi beats", "calm code",
    )

    fun isFunctional(title: String, artist: String): Boolean {
        val haystack = "$title $artist".lowercase()
        return KEYWORDS.any { it in haystack }
    }

    /** 这些文本里出现关键词 = 用户/意图明确想要功能性音乐，过滤应整体跳过。 */
    fun mentionsFunctional(texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        return texts.any { positiveMention(it, KEYWORDS + RELIGIOUS) }
    }
}
