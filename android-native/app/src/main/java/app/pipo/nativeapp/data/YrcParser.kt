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
    private val offsetTag = Regex("""\[(?:offset|offsetMs)\s*:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val jsonOffset = Regex(""""offset"\s*:\s*([+-]?\d+)""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): List<PipoLyricLine> {
        val lines = mutableListOf<PipoLyricLine>()
        val offsetMs = parseOffsetMs(raw)
        for (rawLine in raw.split('\n')) {
            val line = rawLine.trim().trimEnd('\r')
            if (line.isEmpty()) continue
            // JSON 元信息行
            if (line.startsWith("{")) continue

            val header = lineHeader.find(line) ?: continue
            val lineStart = ((header.groupValues[1].toLongOrNull() ?: continue) + offsetMs).coerceAtLeast(0L)
            val lineDur = header.groupValues[2].toLongOrNull() ?: continue

            val rest = line.substring(header.range.last + 1)
            val chars = mutableListOf<PipoLyricChar>()
            tokenRegex.findAll(rest).forEach { m ->
                val tokenStart = ((m.groupValues[1].toLongOrNull() ?: return@forEach) + offsetMs).coerceAtLeast(0L)
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
                            timingParts = listOf(
                                PipoLyricTimingPart(
                                    startMs = tokenStart + idx * perDur,
                                    durationMs = if (idx == groups.size - 1) tokenDur - perDur * idx else perDur,
                                    text = charText,
                                ),
                            ),
                        )
                    )
                }
            }

            val rawMergedChars = mergeAdjacentAsciiLyricChars(chars)
            val mergedChars = normalizeYrcLineTimings(
                chars = rawMergedChars,
                lineStartMs = lineStart,
                lineDurationMs = lineDur,
                tightenInternalDurations = true,
            )
            val text = if (mergedChars.isNotEmpty()) mergedChars.joinToString("") { it.text } else ""
            if (mergedChars.isEmpty() && text.isEmpty()) continue

            lines.add(
                PipoLyricLine(
                    startMs = lineStart,
                    durationMs = lineDur,
                    text = text,
                    chars = mergedChars,
                    timing = PipoLyricTiming.Word,
                )
            )
        }
        lines.sortBy { it.startMs }

        return mergeSimultaneousYrcLines(lines)
    }

    private fun parseOffsetMs(raw: String): Long {
        return offsetTag.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: jsonOffset.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: 0L
    }
}

private fun mergeSimultaneousYrcLines(lines: List<PipoLyricLine>): List<PipoLyricLine> {
    if (lines.size <= 1) return lines

    val primaryLines = mutableListOf<PipoLyricLine>()
    val companionCandidates = mutableListOf<PipoLyricLine>()
    for (line in lines) {
        if (isParentheticalLine(line.text)) {
            companionCandidates.add(line)
        } else {
            val previousPrimary = primaryLines.lastOrNull()
            if (
                previousPrimary == null ||
                kotlin.math.abs(line.startMs - previousPrimary.startMs) >= NEAR_SIMULTANEOUS_LINE_MS
            ) {
                primaryLines.add(line)
            } else if (!sameLyricText(line.text, previousPrimary.text)) {
                // 同一时间戳附近出现第二条不同文本，网易 YRC 通常是在标副唱 / ad-lib。
                // 之前这里直接跳过，导致非括号副词丢失；重复同文本仍按去重处理。
                companionCandidates.add(line)
            }
        }
    }
    if (primaryLines.isEmpty()) return lines

    val attached = List(primaryLines.size) { mutableListOf<PipoLyricLine>() }
    val orphans = mutableListOf<PipoLyricLine>()
    for (companion in companionCandidates) {
        val hostIndex = findCompanionHostIndex(companion, primaryLines)
        if (hostIndex >= 0) {
            attached[hostIndex].add(companion)
        } else {
            orphans.add(companion)
        }
    }

    return (primaryLines.mapIndexed { idx, line ->
        val companions = attached[idx]
            .sortedBy { audioStartMs(it) }
            .take(MAX_COMPANION_LYRIC_LINES)
            .map { it.copy(role = PipoLyricRole.Companion) }
        if (companions.isEmpty()) line else line.copy(companionLines = line.companionLines + companions)
    } + orphans).sortedBy { it.startMs }
}

private fun findCompanionHostIndex(
    companion: PipoLyricLine,
    primaryLines: List<PipoLyricLine>,
): Int {
    val companionStart = audioStartMs(companion)
    val companionEnd = audioEndMs(companion)
    var bestIndex = -1
    var bestScore = Long.MIN_VALUE
    primaryLines.forEachIndexed { idx, primary ->
        val primaryStart = audioStartMs(primary)
        val primaryEnd = audioEndMs(primary)
        val nextPrimaryStart = primaryLines.getOrNull(idx + 1)?.let { audioStartMs(it) }
        val hostWindowEnd = nextPrimaryStart?.let { nextStart ->
            minOf(primaryEnd + COMPANION_HOST_SLOP_MS, nextStart - 1L)
        } ?: (primaryEnd + COMPANION_HOST_SLOP_MS)
        val overlap = minOf(companionEnd, hostWindowEnd) -
            maxOf(companionStart, primaryStart - COMPANION_HOST_SLOP_MS)
        if (overlap <= 0L) return@forEachIndexed

        val midpointDistance = kotlin.math.abs(
            ((companionStart + companionEnd) / 2L) - ((primaryStart + hostWindowEnd) / 2L),
        )
        val score = overlap * 10L - midpointDistance
        if (score > bestScore) {
            bestScore = score
            bestIndex = idx
        }
    }
    return bestIndex
}

private fun audioStartMs(line: PipoLyricLine): Long {
    return line.chars.firstOrNull()?.startMs ?: line.startMs
}

private fun audioEndMs(line: PipoLyricLine): Long {
    val charEnd = line.chars.maxOfOrNull { it.startMs + it.durationMs }
    return maxOf(charEnd ?: line.startMs, line.startMs + line.durationMs)
}

private fun normalizeYrcLineTimings(
    chars: List<PipoLyricChar>,
    lineStartMs: Long,
    lineDurationMs: Long,
    tightenInternalDurations: Boolean,
): List<PipoLyricChar> {
    if (chars.isEmpty()) return chars
    val normalizedChars = if (tightenInternalDurations) {
        clampDurationsToNextTokenStart(chars)
    } else {
        chars
    }
    val lineEndMs = lineStartMs + lineDurationMs
    val last = normalizedChars.last()
    val lastEndMs = last.startMs + last.durationMs
    val tailFromLastStartMs = lineEndMs - last.startMs
    val previousTypicalMs = typicalPreviousTokenDurationMs(normalizedChars.dropLast(1))
    val estimatedLastMs = estimateSungTokenDurationMs(last.text)
    val maxVisualLastMs = maxOf(
        estimatedLastMs,
        (previousTypicalMs * 1.55f).toLong(),
    ).coerceIn(MIN_LAST_TOKEN_VISUAL_MS, MAX_LAST_TOKEN_VISUAL_MS)

    val looksLikeLineTailPackedIntoLast =
        tailFromLastStartMs >= LONG_TAIL_AFTER_LAST_TOKEN_MS &&
            kotlin.math.abs(lastEndMs - lineEndMs) <= LINE_END_SLOP_MS &&
            last.durationMs > maxOf(maxVisualLastMs * 2L, previousTypicalMs * 2L)

    if (!looksLikeLineTailPackedIntoLast) return normalizedChars

    val cappedDuration = maxVisualLastMs.coerceAtLeast(1L)
    val cappedLast = last.copy(
        durationMs = cappedDuration,
        timingParts = listOf(
            PipoLyricTimingPart(
                startMs = last.startMs,
                durationMs = cappedDuration,
                text = last.text,
            ),
        ),
    )
    return normalizedChars.dropLast(1) + cappedLast
}

private fun clampDurationsToNextTokenStart(chars: List<PipoLyricChar>): List<PipoLyricChar> {
    if (chars.size <= 1) return chars
    return chars.mapIndexed { idx, char ->
        val next = chars.getOrNull(idx + 1) ?: return@mapIndexed char
        val maxDurationMs = (next.startMs - char.startMs).coerceAtLeast(1L)
        if (char.durationMs > maxDurationMs) {
            char.copy(durationMs = maxDurationMs)
        } else {
            char
        }
    }
}

private fun typicalPreviousTokenDurationMs(chars: List<PipoLyricChar>): Long {
    val values = chars
        .map { it.durationMs }
        .filter { it in 80L..2_200L }
        .sorted()
    if (values.isEmpty()) return DEFAULT_TOKEN_VISUAL_MS
    return values[values.size / 2]
}

private fun estimateSungTokenDurationMs(text: String): Long {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return DEFAULT_TOKEN_VISUAL_MS
    val asciiCount = trimmed.count { it in 'a'..'z' || it in 'A'..'Z' || it.isDigit() }
    val cjkCount = trimmed.count { it in '一'..'鿿' || it in '぀'..'ヿ' || it in '가'..'힣' }
    val raw = when {
        asciiCount > 0 -> 520L + asciiCount.coerceAtMost(12) * 38L
        cjkCount > 0 -> cjkCount * 280L
        else -> DEFAULT_TOKEN_VISUAL_MS
    }
    return raw.coerceIn(MIN_LAST_TOKEN_VISUAL_MS, MAX_LAST_TOKEN_VISUAL_MS)
}

private fun isParentheticalLine(text: String): Boolean {
    val s = text.trim()
    if (s.length < 2) return false
    val first = s.first()
    val last = s.last()
    return (first == '(' && last == ')') ||
        (first == '（' && last == '）') ||
        (first == '[' && last == ']') ||
        (first == '【' && last == '】')
}

private fun sameLyricText(left: String, right: String): Boolean {
    return left.replace(Regex("\\s+"), " ").trim()
        .equals(right.replace(Regex("\\s+"), " ").trim(), ignoreCase = true)
}

private fun mergeAdjacentAsciiLyricChars(chars: List<PipoLyricChar>): List<PipoLyricChar> {
    if (chars.size <= 1) return chars
    val merged = ArrayList<PipoLyricChar>(chars.size)
    for (char in chars) {
        val prev = merged.lastOrNull()
        if (prev != null && shouldAttachTrailingPunctuation(prev.text, char.text)) {
            merged[merged.lastIndex] = prev.copy(
                text = prev.text + char.text,
            )
        } else if (prev != null && shouldMergeAsciiFragments(prev.text, char.text)) {
            val start = minOf(prev.startMs, char.startMs)
            val end = maxOf(prev.startMs + prev.durationMs, char.startMs + char.durationMs)
            val mergedText = prev.text + char.text
            val mergedDurationMs = (end - start).coerceAtLeast(1L)
            merged[merged.lastIndex] = PipoLyricChar(
                startMs = start,
                durationMs = mergedDurationMs,
                text = mergedText,
                timingParts = listOf(
                    PipoLyricTimingPart(
                        startMs = start,
                        durationMs = mergedDurationMs,
                        text = mergedText,
                    ),
                ),
            )
        } else {
            merged.add(char)
        }
    }
    return merged
}

private fun shouldAttachTrailingPunctuation(left: String, right: String): Boolean {
    if (left.isBlank() || right.isEmpty()) return false
    if (right.any { isWordLikeChar(it) }) return false
    return true
}

private fun shouldMergeAsciiFragments(left: String, right: String): Boolean {
    if (!isAsciiWordFragment(left) || !isAsciiWordFragment(right)) return false
    if (left.lastOrNull()?.isWhitespace() == true || right.firstOrNull()?.isWhitespace() == true) return false
    val leftTail = left.lastOrNull() ?: return false
    val rightHead = right.firstOrNull() ?: return false
    return isAsciiWordJoiner(leftTail) || isAsciiWordJoiner(rightHead) ||
        (leftTail.isLetterOrDigit() && rightHead.isLetterOrDigit())
}

private fun isAsciiWordFragment(value: String): Boolean {
    var hasAsciiWord = false
    for (c in value) {
        val asciiWord = isAsciiWordChar(c)
        if (asciiWord) hasAsciiWord = true
        if (isCjkWordChar(c)) return false
    }
    return hasAsciiWord
}

private fun isWordLikeChar(c: Char): Boolean {
    return isAsciiWordChar(c) || isCjkWordChar(c)
}

private fun isAsciiWordChar(c: Char): Boolean {
    return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
}

private fun isCjkWordChar(c: Char): Boolean {
    return c in '一'..'鿿' || c in '぀'..'ヿ' || c in '가'..'힣'
}

private fun isAsciiWordJoiner(c: Char): Boolean {
    return c == '\'' || c == '’' || c == '-' || c.isLetterOrDigit()
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
        val isCjk = isCjkWordChar(c)
        val isAsciiWord = isAsciiWordChar(c)
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
                // 空白 / 标点：附在前一个字符上，不单独成字。
                // 撇号 / 连字符仍保持 ASCII word 状态，让 can't / you're / uh-oh
                // 在单个 YRC token 内直接成为一个视觉单词，避免后续碎片抢走扫色速度。
                appendToLast(c)
                lastWasAsciiWord = lastWasAsciiWord && isAsciiInlineWordJoiner(c)
            }
        }
    }
    return out.map { it.toString() }
}

private fun isAsciiInlineWordJoiner(c: Char): Boolean {
    return c == '\'' || c == '’' || c == '-'
}

/**
 * 算某个字在 positionMs 时刻的"播放进度"，用于 wipe 动画。
 *   - 还没到 → 0
 *   - 已唱完 → 1
 *   - 正在唱 → 0..1
 */
fun PipoLyricChar.progress(positionMs: Long): Float {
    val parts = timingPartsForProgress()
    if (parts.size <= 1) {
        if (positionMs <= startMs) return 0f
        if (positionMs >= startMs + durationMs) return 1f
        if (durationMs <= 0L) return 1f
        return ((positionMs - startMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    val totalTextLength = parts.sumOf { it.text.length }.coerceAtLeast(1)
    var consumed = 0
    var progress = 0f
    parts.forEachIndexed { idx, part ->
        val partLength = part.text.length.coerceAtLeast(1)
        val partStartProgress = consumed.toFloat() / totalTextLength.toFloat()
        val partEndProgress = (consumed + partLength).toFloat() / totalTextLength.toFloat()
        val nextStartMs = parts.getOrNull(idx + 1)?.startMs
        val effectiveDurationMs = nextStartMs
            ?.let { (it - part.startMs).coerceAtLeast(1L).coerceAtMost(part.durationMs.coerceAtLeast(1L)) }
            ?: part.durationMs.coerceAtLeast(1L)
        val partEndMs = part.startMs + effectiveDurationMs
        progress = when {
            positionMs < part.startMs -> return partStartProgress
            positionMs >= partEndMs -> partEndProgress
            else -> {
                val t = ((positionMs - part.startMs).toFloat() / effectiveDurationMs.toFloat()).coerceIn(0f, 1f)
                partStartProgress + (partEndProgress - partStartProgress) * t
            }
        }
        consumed += partLength
    }
    return progress.coerceIn(0f, 1f)
}

fun PipoLyricChar.timingPartsForProgress(): List<PipoLyricTimingPart> {
    if (timingParts.isEmpty()) return timingPartsOrSelf()
    val tokenEndMs = startMs + durationMs.coerceAtLeast(1L)
    return timingParts
        .filter { part ->
            part.text.isNotEmpty() &&
                part.startMs < tokenEndMs &&
                part.startMs + part.durationMs.coerceAtLeast(1L) > startMs
        }
        .sortedBy { it.startMs }
        .ifEmpty { timingPartsOrSelf() }
}

fun PipoLyricChar.timingPartsOrSelf(): List<PipoLyricTimingPart> {
    return timingParts.ifEmpty {
        listOf(
            PipoLyricTimingPart(
                startMs = startMs,
                durationMs = durationMs,
                text = text,
            ),
        )
    }
}

private const val NEAR_SIMULTANEOUS_LINE_MS = 80L
private const val MAX_COMPANION_LYRIC_LINES = 2
private const val COMPANION_HOST_SLOP_MS = 650L
private const val LONG_TAIL_AFTER_LAST_TOKEN_MS = 1_800L
private const val LINE_END_SLOP_MS = 120L
private const val DEFAULT_TOKEN_VISUAL_MS = 520L
private const val MIN_LAST_TOKEN_VISUAL_MS = 320L
private const val MAX_LAST_TOKEN_VISUAL_MS = 1_550L
