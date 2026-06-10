package app.pipo.nativeapp.data

/**
 * 网易 yrc / lrc（偶尔 TTML）在真正开唱前埋的制作人员信息行：
 *
 *   作词 : 方文山 / 作曲 : 周杰伦 / 编曲 Arranger：林迈可 / 制作人 Producer：…
 *
 * Apple Music 不把这些当歌词展示——前奏只有「三个点」呼吸指示。这里在数据层把
 * **开头连续的一段 credit 行**整体剥掉：剥掉后 0ms→第一句之间自然形成空档，
 * AmllNativeLyricColumn 的 NativeInterludeDots 会接管（空档 ≥4s 才出点，与 Apple 一致）。
 *
 * 只剥「开头连续段」，遇到第一行真歌词立刻停手：副歌里出现冒号的真词不会被误删。
 */
object LyricCredits {
    fun stripLeading(lines: List<PipoLyricLine>): List<PipoLyricLine> {
        if (lines.isEmpty()) return lines
        var drop = 0
        var sawKeywordCredit = false
        for ((index, line) in lines.withIndex()) {
            val text = line.text.trim()
            val prevStartMs = if (index > 0) lines[index - 1].startMs else 0L
            val isCredit = when {
                CREDIT_PREFIX.containsMatchIn(text) ||
                    CREDIT_PREFIX_SHORT.containsMatchIn(text) ||
                    ENGLISH_CREDIT_PREFIX.containsMatchIn(text) -> {
                    sawKeywordCredit = true
                    true
                }
                // 首行「歌名 - 歌手」题头：仅限第一行、起始 ≤1s。
                index == 0 && line.startMs <= 1_000L && TITLE_HEADER.matches(text) -> true
                // 已确认在 credit 块里、且和上一行时间扎堆（credit 行总是密集排在一起）时，
                // 放过「冷门角色名：人名」这类没进关键词表的短标签行（如 制作人助理：xxx），
                // 避免它截断后面的 credit 连续段。时间邻近约束防止误吞紧跟开唱的
                // “你说：……”这类真歌词冒号句——开唱行和最后一条 credit 之间隔着前奏。
                sawKeywordCredit && line.startMs - prevStartMs <= 3_000L && GENERIC_LABEL.matches(text) -> true
                else -> false
            }
            if (isCredit) drop = index + 1 else break
        }
        if (drop == 0) return lines
        // 整首都像 credit 的不可能是真歌词文件出问题，保守不动。
        if (drop >= lines.size) return lines
        return lines.drop(drop)
    }

    /**
     * 中文 credit 关键词开头 + 可选角色后缀（"和声编写" / "编曲 Arranger"）+ 冒号。
     * 多字关键词允许 ≤8 个中英文后缀字符；单字关键词（词/曲/鼓 等）太容易撞上真歌词
     * （"词不达意…" / "曲终人散…"），只允许紧跟拉丁注解或直接冒号。
     */
    private val CREDIT_PREFIX = Regex(
        "^[（(\\[【]?\\s*(作词|作詞|填词|填詞|作曲|谱曲|譜曲|编曲|編曲|制作人|製作人|监制|監製|出品人|出品|发行|發行|录音师|錄音師|录音室|錄音室|录音棚|錄音棚|录音|錄音|混音师|混音師|混音|母带|母帶|和声|和聲|合声|合聲|配唱|演唱|原唱|翻唱|演奏|吉他|贝斯|貝斯|贝司|键盘|鍵盤|钢琴|鋼琴|弦乐|弦樂|打击乐|打擊樂|人声|人聲|制作|製作|企划|企劃|统筹|統籌|文案|封面|插画|插畫|设计|設計|总监|總監|工程师|工程師)[一-鿿A-Za-z ./&-]{0,20}[:：]",
    )

    /** 单字 / 缩写关键词：必须紧跟冒号（可带拉丁注解），不允许中文后缀。 */
    private val CREDIT_PREFIX_SHORT = Regex(
        "^[（(\\[【]?\\s*(词|詞|曲|鼓|OP|SP)\\s*[A-Za-z ./&-]{0,16}[:：]",
    )

    private val ENGLISH_CREDIT_PREFIX = Regex(
        "^(lyrics?|lyricist|written|composers?|composed|arrangers?|arranged|producers?|produced|" +
            "mix(?:ing|ed)?|master(?:ing|ed)?|record(?:ing|ed)?|vocals?|backing vocals?|guitars?|" +
            "bass|drums?|keyboards?|piano|strings|engineer(?:ed)?|label|published?|publisher)" +
            "\\s*(?:by)?\\s*[:：]",
        RegexOption.IGNORE_CASE,
    )

    /** 「歌名 - 歌手」题头（lrc 常见首行）。 */
    private val TITLE_HEADER = Regex("^\\S[^:：]{0,40}\\s[-—–]\\s.{1,40}$")

    /** 短标签行：≤16 字的前缀 + 冒号，前缀不含句读（真歌词的冒号句一般更长且有标点/语气）。 */
    private val GENERIC_LABEL = Regex("^[^:：。，！？!?,]{1,16}[:：].{0,60}$")
}
