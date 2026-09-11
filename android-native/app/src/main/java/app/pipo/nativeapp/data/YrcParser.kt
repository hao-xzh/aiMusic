package app.pipo.nativeapp.data

/**
 * 网易云 yrc（逐字）格式解析。
 *
 *   [39820,3170](39820,500,0)Some (40320,460,0)words
 *
 *   - `[39820,3170]`  行起 39820ms 持续 3170ms
 *   - `(start,dur,0)` 该 token 在歌曲里的**绝对** start ms + 持续 ms
 */
object YrcParser {
    private val lineHeader = Regex("""^\[(\d+),(\d+)]""")
    // 只识别 timing header，token 正文由当前 header 末尾截到下一个 header 起点。
    // 旧正则用 `[^()\[\]]*` 取正文，会把 "hello (oh)" / “（和声）”直接丢掉，
    // 后续 splitTrailingAdlib 根本看不到副词。以下一个合法数字 timing header 作边界，
    // 既保留普通括号，也不会把下一个 token 的时间头吃进正文。
    private val tokenHeader = Regex("""\((\d+),(\d+),(?:-?\d+)\)""")
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
            val tokenHeaders = tokenHeader.findAll(rest).toList()
            tokenHeaders.forEachIndexed { tokenIndex, m ->
                val tokenStart = ((m.groupValues[1].toLongOrNull() ?: return@forEachIndexed) + offsetMs)
                    .coerceAtLeast(0L)
                val tokenDur = m.groupValues[2].toLongOrNull() ?: return@forEachIndexed
                val textStart = m.range.last + 1
                val textEnd = tokenHeaders.getOrNull(tokenIndex + 1)?.range?.first ?: rest.length
                val text = rest.substring(textStart, textEnd)
                if (text.isEmpty()) return@forEachIndexed
                // 一个源时间头就是一个真实发音片段。中英混合或含空格的 token
                // 也不能按字符组均分时间，否则会把估算时序误报为更细的逐字来源。
                chars.add(
                    PipoLyricChar(
                        startMs = tokenStart,
                        durationMs = tokenDur,
                        text = text,
                        timingParts = listOf(PipoLyricTimingPart(tokenStart, tokenDur, text)),
                    ),
                )
            }

            // 与 QRC/KRC/TTML 一样保留源时间。行尾长音可能真实持续数秒，
            // 不能根据文字长度或相邻词时长把它截成估算的短音。
            val mergedChars = mergeAdjacentAsciiLyricChars(chars)
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
    // YRC 没有 agent id，只能把同一时刻的不同文本视为多位演唱者。
    // 记住每个 primary 后已经附加了几个同时歌手，让 2/3/4... 位依次右/左/右交替，
    // 而不是三人及以上全部堆到右侧。
    val simultaneousSingerCounts = mutableListOf<Int>()
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
                simultaneousSingerCounts.add(0)
            } else if (!sameLyricText(line.text, previousPrimary.text)) {
                // 同一时间戳附近出现第二条不同文本，网易 YRC 通常是在标副唱 / 对唱。
                // 这类不是小号背景人声，而是第二演唱者主旋律：按 AMLL 的 duet line 处理，
                // 保持完整字号并靠右；括号 ad-lib 才继续作为 Companion 小字显示。
                val extraSingerIndex = simultaneousSingerCounts.lastOrNull() ?: 0
                val alignment = if (extraSingerIndex % 2 == 0) {
                    PipoLyricAlignment.End
                } else {
                    PipoLyricAlignment.Start
                }
                duetLines.add(line.copy(alignment = alignment))
                simultaneousSingerCounts[simultaneousSingerCounts.lastIndex] = extraSingerIndex + 1
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

/**
 * 把"主体 (和声)"形式的行,在行尾括号处切成 [主行, 括号行]。
 *
 * 仅处理**行尾**的单个括号段、且括号前确有主体内容的安全情形：
 *   - 整行括号（"(oh)"）→ open==0，不切，仍由 isParentheticalLine 当整行 ad-lib。
 *   - 行中括号（"la (la) la"）→ 右括号后还有内容，不切。
 * 只在原始时间片段边界拆分；同一片段横跨主体和和声时保留原行，不按文字比例估算时间。
 * 返回 companion 仍以 Primary 标记,
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
    val sourceChars = line.chars.flatMap { char ->
        char.timingPartsOrSelf().map { part ->
            PipoLyricChar(part.startMs, part.durationMs, part.text, listOf(part))
        }
    }
    for (ch in sourceChars) {
        val start = acc
        val end = acc + ch.text.length
        when {
            end <= open -> mainChars.add(ch)
            start >= open -> compChars.add(ch)
            else -> return line to null
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
 * 算某个字在 positionMs 时刻的"播放进度"，用于 wipe 动画。
 *   - 还没到 → 0
 *   - 已唱完 → 1
 *   - 正在唱 → 0..1
 */
fun PipoLyricChar.progress(positionMs: Long): Float {
    val parts = timingPartsForProgress()
    val endMs = effectiveEndMs(parts)
    if (positionMs >= endMs) return 1f
    val linear = linearTokenProgress(positionMs, endMs)
    if (parts.size <= 1) return linear

    // 音节必须参与，但不能 100% 接管视觉进度。否则 ha-ha / 长词这类 token
    // 会在每个 timingPart 边界改变速度，看起来像横向一截一截卡。
    // 这里用整词线性进度做底，再叠加一部分音节进度修正：音节会影响快慢，
    // 但扫色/上浮仍是一条连续曲线。
    val syllable = timingPartProgress(parts, positionMs)
    val influence = timingPartProgressInfluence(this, parts)
    return (linear + (syllable - linear) * influence).coerceIn(0f, 1f)
}

private fun PipoLyricChar.linearTokenProgress(positionMs: Long, endMs: Long = rawEndMs()): Float {
    if (positionMs <= startMs) return 0f
    if (positionMs >= endMs) return 1f
    val duration = (endMs - startMs).coerceAtLeast(1L)
    return ((positionMs - startMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
}

fun PipoLyricChar.effectiveEndMs(): Long = effectiveEndMs(timingPartsForProgress())

fun PipoLyricChar.effectiveDurationMs(): Long = (effectiveEndMs() - startMs).coerceAtLeast(1L)

private fun PipoLyricChar.effectiveEndMs(parts: List<PipoLyricTimingPart>): Long {
    val rawEnd = rawEndMs()
    if (parts.size <= 1) return rawEnd
    val quality = timingPartQuality(this, parts)
    if (!quality.reliable && !timingPartsCoverTokenText(this, parts)) return rawEnd
    return timingPartsEffectiveEndMs(parts).coerceAtLeast(startMs + 1L)
}

private fun PipoLyricChar.rawEndMs(): Long = startMs + durationMs.coerceAtLeast(1L)

private fun timingPartsEffectiveEndMs(parts: List<PipoLyricTimingPart>): Long {
    var end = Long.MIN_VALUE
    parts.forEachIndexed { index, part ->
        val nextStartMs = parts.getOrNull(index + 1)?.startMs
        val effectiveDurationMs = nextStartMs
            ?.let { (it - part.startMs).coerceAtLeast(1L).coerceAtMost(part.durationMs.coerceAtLeast(1L)) }
            ?: part.durationMs.coerceAtLeast(1L)
        end = maxOf(end, part.startMs + effectiveDurationMs)
    }
    return end
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

private fun timingPartProgressInfluence(
    token: PipoLyricChar,
    parts: List<PipoLyricTimingPart>,
): Float {
    val tokenText = token.text
    val visibleGlyphs = tokenText.count(::isWordLikeChar).coerceAtLeast(1)
    val partDensity = parts.size.toFloat() / visibleGlyphs.toFloat()
    val quality = timingPartQuality(token, parts)
    // Apple Music Web 给每个 .syllable 直接挂 data-delay/data-duration，然后普通词
    // 按一个线性的 --gradient-progress 跑到底；它不会让内部字母/音节片段高权重改写
    // 整个词的扫色速度。这里保留 timingParts 只做很轻的节奏修正，避免合并后的英文词
    // 在片段边界突然跳一截，表现成“一整个单词一帧播完”。
    val baseInfluence = when {
        !quality.reliable -> 0.06f
        partDensity >= 0.75f -> if (quality.tight) 0.08f else 0.04f
        parts.size >= 6 -> if (quality.tight) 0.10f else 0.06f
        else -> if (quality.tight) 0.14f else 0.08f
    }
    return baseInfluence.coerceIn(0f, 0.16f)
}

private data class TimingPartQuality(
    val reliable: Boolean,
    val tight: Boolean,
)

private fun timingPartQuality(
    token: PipoLyricChar,
    parts: List<PipoLyricTimingPart>,
): TimingPartQuality {
    if (parts.size <= 1) return TimingPartQuality(reliable = false, tight = false)
    val tokenStart = token.startMs
    val tokenDuration = token.durationMs.coerceAtLeast(1L)
    val tokenEnd = tokenStart + tokenDuration
    var ordered = true
    var previousStart = Long.MIN_VALUE
    var previousEnd: Long? = null
    var maxGap = 0L
    parts.forEachIndexed { index, part ->
        val start = part.startMs
        val nextStartMs = parts.getOrNull(index + 1)?.startMs
        val effectiveDurationMs = nextStartMs
            ?.let { (it - part.startMs).coerceAtLeast(1L).coerceAtMost(part.durationMs.coerceAtLeast(1L)) }
            ?: part.durationMs.coerceAtLeast(1L)
        val end = start + effectiveDurationMs
        if (start < previousStart) ordered = false
        previousEnd?.let { maxGap = maxOf(maxGap, start - it) }
        previousStart = start
        previousEnd = end
    }
    val firstStart = parts.first().startMs
    val lastEnd = previousEnd ?: parts.maxOf { it.startMs + it.durationMs.coerceAtLeast(1L) }
    val coverage = ((lastEnd - firstStart).coerceAtLeast(1L).toFloat() / tokenDuration.toFloat())
    val reliable = ordered &&
        firstStart >= tokenStart - 90L &&
        lastEnd <= tokenEnd + 180L &&
        coverage in 0.35f..1.40f &&
        maxGap <= maxOf(280L, tokenDuration / 2L)
    val tight = reliable &&
        firstStart >= tokenStart - 45L &&
        lastEnd <= tokenEnd + 90L &&
        coverage in 0.58f..1.18f &&
        maxGap <= maxOf(180L, tokenDuration / 3L)
    return TimingPartQuality(reliable = reliable, tight = tight)
}

private fun timingPartsCoverTokenText(
    token: PipoLyricChar,
    parts: List<PipoLyricTimingPart>,
): Boolean {
    val tokenUnits = token.text.count(::isWordLikeChar).coerceAtLeast(1)
    val partUnits = parts.sumOf { part ->
        part.text.count(::isWordLikeChar).takeIf { it > 0 } ?: 1
    }
    if (partUnits < (tokenUnits * 0.75f).toInt().coerceAtLeast(1)) return false
    var ordered = true
    var previousStart = Long.MIN_VALUE
    parts.forEach { part ->
        if (part.startMs < previousStart) ordered = false
        previousStart = part.startMs
    }
    if (!ordered) return false
    val firstStart = parts.first().startMs
    val lastEnd = timingPartsEffectiveEndMs(parts)
    val rawEnd = token.rawEndMs()
    return firstStart >= token.startMs - 90L &&
        lastEnd > token.startMs &&
        lastEnd <= rawEnd + 180L
}

fun PipoLyricChar.progressVelocityPerMs(positionMs: Long): Float {
    val parts = timingPartsForProgress()
    if (parts.size <= 1) return 1f / effectiveDurationMs().toFloat()
    return timingPartProgressVelocity(parts, positionMs)
        .takeIf { it.isFinite() && it > 0f }
        ?: (1f / effectiveDurationMs().toFloat())
}

private fun timingPartProgressVelocity(
    parts: List<PipoLyricTimingPart>,
    positionMs: Long,
): Float {
    val totalTextLength = parts.sumOf { it.text.length }.coerceAtLeast(1)
    var consumed = 0
    parts.forEachIndexed { idx, part ->
        val partLength = part.text.length.coerceAtLeast(1)
        val partStartProgress = consumed.toFloat() / totalTextLength.toFloat()
        val partEndProgress = (consumed + partLength).toFloat() / totalTextLength.toFloat()
        val nextStartMs = parts.getOrNull(idx + 1)?.startMs
        val effectiveDurationMs = nextStartMs
            ?.let { (it - part.startMs).coerceAtLeast(1L).coerceAtMost(part.durationMs.coerceAtLeast(1L)) }
            ?: part.durationMs.coerceAtLeast(1L)
        val partEndMs = part.startMs + effectiveDurationMs
        if (positionMs < part.startMs) {
            return if (idx == 0) {
                (partEndProgress - partStartProgress) / effectiveDurationMs.toFloat()
            } else {
                0f
            }
        }
        if (positionMs < partEndMs || idx == parts.lastIndex) {
            return (partEndProgress - partStartProgress) / effectiveDurationMs.toFloat()
        }
        consumed += partLength
    }
    return 0f
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
