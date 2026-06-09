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

            val builtLine = PipoLyricLine(
                startMs = lineStart,
                durationMs = lineDur,
                text = text,
                chars = mergedChars,
                timing = PipoLyricTiming.Word,
            )
            // 行尾括号和声（"主体 (oh baby)"）切成独立括号行，主体留在主行；
            // 切出的括号行随后被 mergeSimultaneousYrcLines 当 ad-lib 归到主行的 companionLines。
            val (mainLine, adlib) = splitTrailingAdlib(builtLine)
            lines.add(mainLine)
            if (adlib != null) lines.add(adlib)
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
    val duetLines = mutableListOf<PipoLyricLine>()
    val companionCandidates = mutableListOf<PipoLyricLine>()
    for (line in lines) {
        if (isParentheticalLine(line.text)) {
            // 括号 ad-lib（"(yeah)" "(oh)"）= 和声，alignment 跟主行（默认 Start）。
            companionCandidates.add(line)
        } else {
            val previousPrimary = primaryLines.lastOrNull()
            if (
                previousPrimary == null ||
                kotlin.math.abs(line.startMs - previousPrimary.startMs) >= NEAR_SIMULTANEOUS_LINE_MS
            ) {
                primaryLines.add(line)
            } else if (!sameLyricText(line.text, previousPrimary.text)) {
                // 同一时间戳附近出现第二条不同文本，网易 YRC 通常是在标副唱 / 对唱。
                // 这类不是小号背景人声，而是第二演唱者主旋律：按 AMLL 的 duet line 处理，
                // 保持完整字号并靠右；括号 ad-lib 才继续作为 Companion 小字显示。
                duetLines.add(line.copy(alignment = PipoLyricAlignment.End))
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
    } + duetLines + orphans).sortedBy { it.startMs }
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

/**
 * 把"主体 (和声)"形式的行,在行尾括号处切成 [主行, 括号行]。
 *
 * 仅处理**行尾**的单个括号段、且括号前确有主体内容的安全情形：
 *   - 整行括号（"(oh)"）→ open==0，不切，仍由 isParentheticalLine 当整行 ad-lib。
 *   - 行中括号（"la (la) la"）→ 右括号后还有内容，不切。
 * 网易云 yrc 的括号符号往往附着在邻字上,故按 text 字符下标定位、必要时把跨界 char
 * 按文本比例切分时间（括号符号不发音,时间误差忽略不计）。返回 companion 仍以 Primary 标记,
 * 由 mergeSimultaneousYrcLines 按括号识别后统一改成 Companion 并就近挂载。
 */
private fun splitTrailingAdlib(line: PipoLyricLine): Pair<PipoLyricLine, PipoLyricLine?> {
    val text = line.text
    if (line.chars.isEmpty() || text.length < 4) return line to null
    val open = maxOf(text.lastIndexOf('('), text.lastIndexOf('（'))
    if (open <= 0) return line to null
    val close = maxOf(text.lastIndexOf(')'), text.lastIndexOf('）'))
    if (close <= open) return line to null
    // 右括号之后只允许空白，否则属于行中括号，不切。
    if (text.substring(close + 1).isNotBlank()) return line to null
    if (text.substring(0, open).isBlank()) return line to null
    if (text.substring(open).trim().length < 3) return line to null // 至少 "(x)"

    val mainChars = mutableListOf<PipoLyricChar>()
    val compChars = mutableListOf<PipoLyricChar>()
    var acc = 0
    for (ch in line.chars) {
        val start = acc
        val end = acc + ch.text.length
        when {
            end <= open -> mainChars.add(ch)
            start >= open -> compChars.add(ch)
            else -> {
                // 跨界 char（含 '(' 的那个，如 "你 ("）：前半归主体、后半（'(' 起）归括号行。
                val cut = (open - start).coerceIn(0, ch.text.length)
                val head = ch.text.substring(0, cut)
                val tail = ch.text.substring(cut)
                val headDur = if (ch.text.isNotEmpty()) {
                    (ch.durationMs * head.length / ch.text.length).coerceAtLeast(0L)
                } else {
                    0L
                }
                if (head.isNotEmpty()) {
                    val d = headDur.coerceAtLeast(1L)
                    mainChars.add(
                        ch.copy(
                            text = head,
                            durationMs = d,
                            timingParts = listOf(PipoLyricTimingPart(ch.startMs, d, head)),
                        ),
                    )
                }
                if (tail.isNotEmpty()) {
                    val tStart = ch.startMs + headDur
                    val tDur = (ch.durationMs - headDur).coerceAtLeast(1L)
                    compChars.add(
                        ch.copy(
                            startMs = tStart,
                            text = tail,
                            durationMs = tDur,
                            timingParts = listOf(PipoLyricTimingPart(tStart, tDur, tail)),
                        ),
                    )
                }
            }
        }
        acc = end
    }
    if (mainChars.isEmpty() || compChars.isEmpty()) return line to null

    val mainMerged = mergeAdjacentAsciiLyricChars(mainChars)
    val compMerged = mergeAdjacentAsciiLyricChars(compChars)
    val mainText = mainMerged.joinToString("") { it.text }
    val compText = compMerged.joinToString("") { it.text }
    if (mainText.isBlank() || compText.isBlank()) return line to null

    val compStart = compMerged.first().startMs
    val compEnd = compMerged.maxOf { it.startMs + it.durationMs }
    val main = line.copy(text = mainText, chars = mainMerged)
    val companion = PipoLyricLine(
        startMs = compStart,
        durationMs = (compEnd - compStart).coerceAtLeast(1L),
        text = compText,
        chars = compMerged,
        timing = PipoLyricTiming.Word,
        role = PipoLyricRole.Primary,
    )
    return main to companion
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

internal fun mergeAdjacentAsciiLyricChars(chars: List<PipoLyricChar>): List<PipoLyricChar> {
    if (chars.size <= 1) return chars
    val merged = ArrayList<PipoLyricChar>(chars.size)
    for (char in chars) {
        val prev = merged.lastOrNull()
        if (prev != null && shouldAttachTrailingPunctuation(prev.text, char.text)) {
            val start = minOf(prev.startMs, char.startMs)
            val end = maxOf(prev.startMs + prev.durationMs, char.startMs + char.durationMs)
            merged[merged.lastIndex] = prev.copy(
                startMs = start,
                durationMs = (end - start).coerceAtLeast(1L),
                text = prev.text + char.text,
                timingParts = prev.timingPartsOrSelf() + char.timingPartsOrSelf(),
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
                timingParts = prev.timingPartsOrSelf() + char.timingPartsOrSelf(),
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
    val linear = linearTokenProgress(positionMs)
    val parts = timingPartsForProgress()
    if (parts.size <= 1) return linear

    // 音节必须参与，但不能 100% 接管视觉进度。否则 ha-ha / 长词这类 token
    // 会在每个 timingPart 边界改变速度，看起来像横向一截一截卡。
    // 这里用整词线性进度做底，再叠加一部分音节进度修正：音节会影响快慢，
    // 但扫色/上浮仍是一条连续曲线。
    val syllable = timingPartProgress(parts, positionMs)
    val influence = syllableProgressInfluence(text, parts)
    return (linear + (syllable - linear) * influence).coerceIn(0f, 1f)
}

private fun PipoLyricChar.linearTokenProgress(positionMs: Long): Float {
    if (positionMs <= startMs) return 0f
    if (positionMs >= startMs + durationMs) return 1f
    if (durationMs <= 0L) return 1f
    return ((positionMs - startMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

private fun timingPartProgress(parts: List<PipoLyricTimingPart>, positionMs: Long): Float {
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

private fun syllableProgressInfluence(
    tokenText: String,
    parts: List<PipoLyricTimingPart>,
): Float {
    val visibleGlyphs = tokenText.count(::isWordLikeChar).coerceAtLeast(1)
    val partDensity = parts.size.toFloat() / visibleGlyphs.toFloat()
    // 音节进度按“字符数”分配区间，但各音节时长不均：短时长却覆盖多字母的音节会让进度陡增，
    // 表现为扫色“跳一段”。这里大幅压低音节影响、以整词线性进度为主，保证扫色从头到尾连续丝滑，
    // 音节仅作极轻微的快慢修正，不再造成可见跳变。（想完全线性可全部设 0f。）
    return when {
        // 很密的 parts ≈ 逐字母碎片，直接纯线性最丝滑。
        partDensity >= 0.75f -> 0.0f
        parts.size >= 6 -> 0.10f
        else -> 0.15f
    }
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
