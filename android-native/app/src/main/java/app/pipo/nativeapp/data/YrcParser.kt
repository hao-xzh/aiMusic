package app.pipo.nativeapp.data

/**
 * 网易云 yrc（逐字）格式解析。镜像 src/lib/yrc.ts。
 *
 *   [39820,3170](39820,500,0)Some (40320,460,0)words
 *
 *   - `[39820,3170]`  行起 39820ms 持续 3170ms
 *   - `(start,dur,0)` 该 token 在歌曲里的**绝对** start ms + 持续 ms
 */
object YrcParser {
    private val lineHeader = Regex("""^\[(\d+),(\d+)]""")
    private val tokenRegex = Regex("""\((\d+),(\d+),(?:-?\d+)\)([^()\[\]]*)""")

    fun parse(raw: String): List<PipoLyricLine> {
        val lines = mutableListOf<PipoLyricLine>()
        for (rawLine in raw.split('\n')) {
            val line = rawLine.trim().trimEnd('\r')
            if (line.isEmpty()) continue
            // JSON 元信息行
            if (line.startsWith("{")) continue

            val header = lineHeader.find(line) ?: continue
            val lineStart = header.groupValues[1].toLongOrNull() ?: continue
            val lineDur = header.groupValues[2].toLongOrNull() ?: continue

            val rest = line.substring(header.range.last + 1)
            val chars = mutableListOf<PipoLyricChar>()
            tokenRegex.findAll(rest).forEach { m ->
                val tokenStart = m.groupValues[1].toLongOrNull() ?: return@forEach
                val tokenDur = m.groupValues[2].toLongOrNull() ?: return@forEach
                val text = m.groupValues[3]
                if (text.isEmpty()) return@forEach
                // 把 token 切成单字 —— netease 的 yrc token 是词/音节级（中文 1-2 字、英文 1 词），
                // 切到字级让 karaoke wipe 真正"逐字"。每个 char 均分原 token 的 duration。
                // 空白和标点保留在它前一个非空白字符上，避免单独高亮一个空格闪烁。
                val groups = splitIntoVisualChars(text)
                if (groups.isEmpty()) return@forEach
                val perDur = tokenDur / groups.size.coerceAtLeast(1)
                groups.forEachIndexed { idx, charText ->
                    chars.add(
                        PipoLyricChar(
                            startMs = tokenStart + idx * perDur,
                            durationMs = if (idx == groups.size - 1) tokenDur - perDur * idx else perDur,
                            text = charText,
                        )
                    )
                }
            }

            val text = if (chars.isNotEmpty()) chars.joinToString("") { it.text } else ""
            if (chars.isEmpty() && text.isEmpty()) continue

            lines.add(
                PipoLyricLine(
                    startMs = lineStart,
                    durationMs = lineDur,
                    text = text,
                    chars = chars,
                )
            )
        }
        lines.sortBy { it.startMs }

        // 翻译行去重：网易云对带翻译的歌会把翻译塞进 yrc，下一行起点几乎相同
        val dedup = mutableListOf<PipoLyricLine>()
        for (l in lines) {
            val prev = dedup.lastOrNull()
            if (prev != null && kotlin.math.abs(l.startMs - prev.startMs) < 50) continue
            dedup.add(l)
        }
        return dedup
    }
}

/**
 * 把 yrc token 切成"视觉字符 / 视觉单词"。规则：
 *   - 中日韩 (CJK) 字符：每个独立成字
 *   - ASCII 字母 / 数字：连续的当**一个 word**整体（不切！）—— 让英文按单词跳动而不是逐字母
 *   - 空白和标点：附着到它前一个 char 上（不单独成字，避免高亮闪烁）
 *
 * 之前 bug：注释说"不切"，代码却 startNew(c) 每个字母都新起一个，
 * 导致 "hello" 被切成 h/e/l/l/o 五块，每个独立 bounce 起伏 → 用户报"逐字母跳"。
 */
fun splitIntoVisualChars(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<StringBuilder>()
    fun startNew(c: Char) {
        out.add(StringBuilder().append(c))
    }
    fun appendToLast(c: Char) {
        if (out.isEmpty()) startNew(c) else out.last().append(c)
    }
    var lastWasAsciiWord = false
    for (c in text) {
        val isCjk = c in '一'..'鿿' || c in '぀'..'ヿ' || c in '가'..'힣'
        val isAsciiWord = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')
        when {
            isCjk -> {
                startNew(c)
                lastWasAsciiWord = false
            }
            isAsciiWord -> {
                // 关键修复：连续 ASCII 字母 / 数字合并到上一个单元 → 一个英文单词整体跳
                if (lastWasAsciiWord) appendToLast(c) else startNew(c)
                lastWasAsciiWord = true
            }
            else -> {
                // 空白 / 标点：附在前一个字符上，不单独成字
                appendToLast(c)
                lastWasAsciiWord = false
            }
        }
    }
    return out.map { it.toString() }
}

/**
 * 算某个字在 positionMs 时刻的"播放进度"，用于 wipe 动画。
 *   - 还没到 → 0
 *   - 已唱完 → 1
 *   - 正在唱 → 0..1
 */
fun PipoLyricChar.progress(positionMs: Long): Float {
    if (positionMs <= startMs) return 0f
    if (positionMs >= startMs + durationMs) return 1f
    if (durationMs <= 0L) return 1f
    return ((positionMs - startMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}
