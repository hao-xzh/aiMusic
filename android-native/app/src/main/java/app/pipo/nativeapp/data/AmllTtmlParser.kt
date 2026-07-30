package app.pipo.nativeapp.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * AMLL TTML 歌词解析器。
 *
 * 输入是 amll-dev/amll-ttml-db 的 `ncm-lyrics/<网易云ID>.ttml` 内容，
 * 关键结构（命名空间略）：
 * ```xml
 *   <tt>
 *     <body>
 *       <div>
 *         <p begin end ttm:agent="v1">
 *           <span begin end>あ</span>     ← 字级时间戳
 *           <span begin end>の</span>
 *           ...
 *           <span ttm:role="x-translation" xml:lang="zh-CN">翻译文本</span>
 *           <span ttm:role="x-roman">罗马音</span>
 *         </p>
 *         <p ... ttm:agent="v2">          ← 第二位演唱者（对唱 / 合唱）
 *           ...
 *         </p>
 *       </div>
 *     </body>
 *   </tt>
 * ```
 *
 * 输出按既有 [PipoLyricLine] 模型组装：
 *   · `<p>` → 一行 Primary；同一位非 group 演唱者保持当前侧，歌手变化时左右交替，
 *     group 保持主侧且不改变交替状态，对齐 AMLL 的多歌手 Apple Music 风格转换。
 *   · `<span begin end>` → [PipoLyricChar]（字级 timing）
 *   · `<span ttm:role="x-translation">` → 一条 `role = Translation` 的 companion
 *   · `<span ttm:role="x-bg">` → 一条 `role = Companion` 的副词（"和声 / backing vocal"），
 *     附在所在 `<p>` 的 companionLines 上，由渲染层走小字浮入通道。
 *   · `<span ttm:role="x-roman">` → 一条 `role = Romaji`（音译）的小字行，显示在主词与翻译之间
 *
 * 时间格式支持 `HH:MM:SS.fff`、`MM:SS.fff`、`SS.fff`、纯数字秒数，以及容错的 `s` / `ms` 后缀。
 */
object AmllTtmlParser {

    fun parse(ttml: String): List<PipoLyricLine> {
        if (ttml.isBlank()) return emptyList()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(ttml))
        }
        val primaries = mutableListOf<PipoLyricLine>()
        val agents = mutableMapOf<String, AgentInfo>()
        val sidecars = mutableMapOf<String, SidecarExtras>()
        // AMLL 官方的 Apple Music 风格转换不是把“v1 之外的所有人”都挤在右边：
        // 连续同一位演唱者保持当前侧，换成另一位非 group 演唱者时左右翻转。
        // 因此三人及以上也能让相邻的不同歌手分居两侧；group 始终在主侧且不打断交替状态。
        var lastPersonAgentId: String? = null
        var lastPersonAlignment = PipoLyricAlignment.Start
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "agent" -> parseAgent(parser)?.let { agents[it.id] = it }
                    "translation" -> parseSidecarContainer(
                        parser = parser,
                        role = PipoLyricRole.Translation,
                        sidecars = sidecars,
                    )
                    "transliteration" -> parseSidecarContainer(
                        parser = parser,
                        role = PipoLyricRole.Romaji,
                        sidecars = sidecars,
                    )
                    "p" -> {
                        val parsed = parseP(parser)
                        if (parsed != null) {
                            val agentId = parsed.agent ?: DEFAULT_AGENT_ID
                            val agent = agents[agentId]
                            val isGroup = agent?.type == AGENT_TYPE_GROUP
                            val alignment = if (isGroup) {
                                PipoLyricAlignment.Start
                            } else {
                                val nextAlignment = when {
                                    lastPersonAgentId == null -> {
                                        if (agent?.type == AGENT_TYPE_OTHER) {
                                            PipoLyricAlignment.End
                                        } else {
                                            PipoLyricAlignment.Start
                                        }
                                    }
                                    lastPersonAgentId == agentId -> lastPersonAlignment
                                    lastPersonAlignment == PipoLyricAlignment.Start -> PipoLyricAlignment.End
                                    else -> PipoLyricAlignment.Start
                                }
                                lastPersonAgentId = agentId
                                lastPersonAlignment = nextAlignment
                                nextAlignment
                            }
                            val primary = parsed.toPipoLine(
                                role = PipoLyricRole.Primary,
                                alignment = alignment,
                            )
                            primaries.add(attachSidecar(primary, parsed.key?.let(sidecars::get)))
                        }
                    }
                }
            }
            event = parser.next()
        }
        return primaries.sortedBy { it.startMs }
    }

    private fun parseAgent(parser: XmlPullParser): AgentInfo? {
        val id = parser.getAttributeValue(XML_NS, "id")
            ?: parser.getAttributeValue(null, "xml:id")
            ?: parser.getAttributeValue(null, "id")
            ?: return null
        val type = parser.getAttributeValue(null, "type")
            ?: parser.getAttributeValue(TTM_NS, "type")
            ?: parser.getAttributeValue(null, "ttm:type")
        return AgentInfo(id = id, type = type)
    }

    /** 解析单个 `<p>` 元素 —— 返回 null 表示该 p 没拿到有效 begin/end，直接跳过。 */
    private fun parseP(parser: XmlPullParser): ParsedP? {
        val pBegin = parseTimeAttr(parser, "begin") ?: return null
        val pEnd = parseTimeAttr(parser, "end") ?: return null
        val pAgent = parser.getAttributeValue(TTM_NS, "agent")
            ?: parser.getAttributeValue(null, "ttm:agent")
        val pKey = parser.getAttributeValue(ITUNES_NS, "key")
            ?: parser.getAttributeValue(null, "itunes:key")
            ?: parser.getAttributeValue(null, "key")
            ?: parser.attributeValueByLocalName("key")
        val chars = mutableListOf<PipoLyricChar>()
        // companions：翻译 (x-translation) + 背景人声 (x-bg) 都挂到当前 p 的 companionLines。
        // 渲染层按 role 区分 Translation 行（小字翻译）与 Companion 行（合唱 / 副词）。
        val companions = mutableListOf<PipoLyricLine>()
        val backgroundSupplementaries = mutableListOf<PipoLyricLine>()
        val mainTextBuilder = StringBuilder()
        // 同步读取 p 的纯文本（用于没有字级 span 时的回落）。
        // span 文本会另外存进 chars 并拼到 mainTextBuilder 里。
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "p")) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "span") {
                        val role = parser.getAttributeValue(TTM_NS, "role")
                            ?: parser.getAttributeValue(null, "ttm:role")
                        when (role) {
                            "x-translation" -> {
                                val content = parseSupplementaryContent(parser)
                                addSupplementaryContent(
                                    content = content,
                                    role = PipoLyricRole.Translation,
                                    startMs = pBegin,
                                    endMs = pEnd,
                                    mainDestination = companions,
                                    backgroundDestination = backgroundSupplementaries,
                                )
                            }
                            "x-roman" -> {
                                val content = parseSupplementaryContent(parser)
                                addSupplementaryContent(
                                    content = content,
                                    role = PipoLyricRole.Romaji,
                                    startMs = pBegin,
                                    endMs = pEnd,
                                    mainDestination = companions,
                                    backgroundDestination = backgroundSupplementaries,
                                )
                            }
                            "x-bg" -> {
                                // 背景人声 / 合唱：x-bg span 自己带 begin/end，内部嵌套字级 <span> 子节点。
                                // 解析成一条独立的 Companion line，让渲染层走和 yrc mergeSimultaneousYrcLines
                                // 产出的副唱完全相同的展示通道。
                                val bgBegin = parseTimeAttr(parser, "begin") ?: pBegin
                                val bgEnd = parseTimeAttr(parser, "end") ?: pEnd
                                val bgLine = parseBackgroundVocalSpan(parser, bgBegin, bgEnd)
                                if (bgLine != null) companions.add(bgLine)
                            }
                            null -> {
                                if (isRubyContainer(parser)) {
                                    parseRubyContainer(parser, pBegin, pEnd)?.let { rubyChar ->
                                        mainTextBuilder.append(rubyChar.text)
                                        chars.add(rubyChar)
                                    }
                                } else {
                                    // 字级 timing 的 span
                                    val spanBegin = parseTimeAttr(parser, "begin")
                                    val spanEnd = parseTimeAttr(parser, "end")
                                    val text = readSpanText(parser)
                                    if (text.isNotEmpty()) {
                                        mainTextBuilder.append(text)
                                        if (spanBegin != null && spanEnd != null && spanEnd > spanBegin) {
                                            chars.add(
                                                PipoLyricChar(
                                                    startMs = spanBegin,
                                                    durationMs = spanEnd - spanBegin,
                                                    text = text,
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                            else -> skipElement(parser)
                        }
                    } else {
                        skipElement(parser)
                    }
                }
                XmlPullParser.TEXT -> {
                    // span 之间的空格 / 标点会以 <p> 的直接 TEXT 节点出现，例如
                    //     <span>DON'T</span> <span>ACT</span>
                    // 中间那个空格。如果只拼到 mainTextBuilder 而不进 chars，
                    // line.text 比 sum(chars[].text.length) 长，lyricDrawUnits 的 cursor
                    // 偏移会错位，末尾字符拿不到 draw unit、active 时直接消失。
                    // 这里把 TEXT 附到上一个 char 的尾部（空格不影响 sweep 节奏，
                    // 绘制时 isWhitespace 会跳过它的浮动 / emphasis）。
                    val txt = parser.text
                    if (txt.isNotEmpty()) {
                        mainTextBuilder.append(txt)
                        if (chars.isNotEmpty()) {
                            val last = chars[chars.size - 1]
                            chars[chars.size - 1] = last.copy(text = last.text + txt)
                        }
                    }
                }
                else -> Unit
            }
            event = parser.next()
        }
        val mergedChars = mergeAdjacentAsciiLyricChars(chars)
        return ParsedP(
            beginMs = pBegin,
            endMs = pEnd,
            key = pKey,
            agent = pAgent,
            text = mainTextBuilder.toString(),
            chars = mergedChars,
            companions = attachBackgroundSupplementaries(companions, backgroundSupplementaries),
        )
    }

    /**
     * 解析 `<span ttm:role="x-bg" begin end>` 内部的字级子 span，构造一条 Companion line。
     * 进入时 parser 在 `<span ttm:role="x-bg">` 的 START_TAG 上，
     * 退出时 parser 已经消费完对应的 END_TAG。
     */
    private fun parseBackgroundVocalSpan(
        parser: XmlPullParser,
        bgBegin: Long,
        bgEnd: Long,
    ): PipoLyricLine? {
        val bgChars = mutableListOf<PipoLyricChar>()
        val supplementaries = mutableListOf<PipoLyricLine>()
        val textBuilder = StringBuilder()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "span")) {
            if (event == XmlPullParser.END_DOCUMENT) return null
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "span") {
                        val role = parser.getAttributeValue(TTM_NS, "role")
                            ?: parser.getAttributeValue(null, "ttm:role")
                        when (role) {
                            null -> {
                                if (isRubyContainer(parser)) {
                                    parseRubyContainer(parser, bgBegin, bgEnd)?.let { rubyChar ->
                                        textBuilder.append(rubyChar.text)
                                        bgChars.add(rubyChar)
                                    }
                                } else {
                                    val spanBegin = parseTimeAttr(parser, "begin")
                                    val spanEnd = parseTimeAttr(parser, "end")
                                    val text = readSpanText(parser)
                                    if (text.isNotEmpty()) {
                                        textBuilder.append(text)
                                        if (spanBegin != null && spanEnd != null && spanEnd > spanBegin) {
                                            bgChars.add(
                                                PipoLyricChar(
                                                    startMs = spanBegin,
                                                    durationMs = spanEnd - spanBegin,
                                                    text = text,
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                            "x-translation", "x-roman" -> {
                                val content = parseSupplementaryContent(parser)
                                val supplementaryRole = if (role == "x-translation") {
                                    PipoLyricRole.Translation
                                } else {
                                    PipoLyricRole.Romaji
                                }
                                addSupplementaryContent(
                                    content = content,
                                    role = supplementaryRole,
                                    startMs = bgBegin,
                                    endMs = bgEnd,
                                    mainDestination = supplementaries,
                                    backgroundDestination = supplementaries,
                                )
                            }
                            else -> skipElement(parser)
                        }
                    } else {
                        skipElement(parser)
                    }
                }
                XmlPullParser.TEXT -> {
                    // 同 parseP：x-bg 内部 span 之间的空格 / 标点也要附到上一个 char 末尾，
                    // 否则末尾字符在 active 时同样会消失。
                    val txt = parser.text
                    if (txt.isNotEmpty()) {
                        textBuilder.append(txt)
                        if (bgChars.isNotEmpty()) {
                            val last = bgChars[bgChars.size - 1]
                            bgChars[bgChars.size - 1] = last.copy(text = last.text + txt)
                        }
                    }
                }
                else -> Unit
            }
            event = parser.next()
        }
        val finalText = if (textBuilder.isNotEmpty()) {
            textBuilder.toString()
        } else {
            bgChars.joinToString("") { it.text }
        }
        if (finalText.isBlank()) return null
        val mergedBgChars = mergeAdjacentAsciiLyricChars(bgChars)
        return PipoLyricLine(
            startMs = bgBegin,
            durationMs = (bgEnd - bgBegin).coerceAtLeast(1L),
            text = finalText,
            chars = mergedBgChars,
            timing = if (mergedBgChars.isNotEmpty()) PipoLyricTiming.Word else PipoLyricTiming.Line,
            companionLines = distinctSupplementaries(supplementaries),
            role = PipoLyricRole.Companion,
        )
    }

    /**
     * 读取 x-translation / x-roman（以及 head sidecar 的 text）。
     * AMLL 规范允许其内嵌 x-bg：普通文本属于主唱，x-bg 文本属于背景人声。
     */
    private fun parseSupplementaryContent(parser: XmlPullParser): ParsedSupplementaryContent {
        val rootName = parser.name
        val main = StringBuilder()
        val background = StringBuilder()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == rootName)) {
            if (event == XmlPullParser.END_DOCUMENT) break
            when (event) {
                XmlPullParser.START_TAG -> {
                    val role = parser.getAttributeValue(TTM_NS, "role")
                        ?: parser.getAttributeValue(null, "ttm:role")
                    val text = readElementText(parser)
                    if (role == "x-bg") {
                        background.append(text)
                    } else {
                        main.append(text)
                    }
                }
                XmlPullParser.TEXT -> main.append(parser.text)
            }
            event = parser.next()
        }
        return ParsedSupplementaryContent(
            mainText = main.toString().trim(),
            backgroundText = background.toString().trim(),
        )
    }

    private fun readElementText(parser: XmlPullParser): String {
        val text = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_DOCUMENT -> return text.toString()
            }
        }
        return text.toString()
    }

    private fun isRubyContainer(parser: XmlPullParser): Boolean {
        val ruby = parser.getAttributeValue(TTS_NS, "ruby")
            ?: parser.getAttributeValue(null, "tts:ruby")
        return ruby == "container"
    }

    /**
     * Pipo 当前没有 ruby 注音展示模型，但不能因此把主歌词 base 也丢掉。
     * 保留 tts:ruby="base" 文本，并用 ruby text 的最早 begin / 最晚 end 作为该 base 的扫色区间。
     */
    private fun parseRubyContainer(
        parser: XmlPullParser,
        fallbackStartMs: Long,
        fallbackEndMs: Long,
    ): PipoLyricChar? {
        val base = StringBuilder()
        var timedStartMs: Long? = null
        var timedEndMs: Long? = null
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    val ruby = parser.getAttributeValue(TTS_NS, "ruby")
                        ?: parser.getAttributeValue(null, "tts:ruby")
                    when (ruby) {
                        "base" -> base.append(readElementText(parser))
                        "text" -> {
                            val startMs = parseTimeAttr(parser, "begin")
                            val endMs = parseTimeAttr(parser, "end")
                            readElementText(parser)
                            if (startMs != null && endMs != null && endMs > startMs) {
                                timedStartMs = minOf(timedStartMs ?: startMs, startMs)
                                timedEndMs = maxOf(timedEndMs ?: endMs, endMs)
                            }
                        }
                        else -> depth++
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return null
            }
        }
        val text = base.toString().trim()
        if (text.isEmpty()) return null
        val startMs = timedStartMs ?: fallbackStartMs
        val endMs = (timedEndMs ?: fallbackEndMs).coerceAtLeast(startMs + 1L)
        return PipoLyricChar(
            startMs = startMs,
            durationMs = endMs - startMs,
            text = text,
            timingParts = listOf(PipoLyricTimingPart(startMs, endMs - startMs, text)),
        )
    }

    private fun parseSidecarContainer(
        parser: XmlPullParser,
        role: PipoLyricRole,
        sidecars: MutableMap<String, SidecarExtras>,
    ) {
        val rootName = parser.name
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == rootName)) {
            if (event == XmlPullParser.END_DOCUMENT) return
            if (event == XmlPullParser.START_TAG) {
                if (parser.name == "text") {
                    val lineId = parser.getAttributeValue(null, "for")
                        ?: parser.attributeValueByLocalName("for")
                    val content = parseSupplementaryContent(parser)
                    if (!lineId.isNullOrBlank()) {
                        val extras = sidecars.getOrPut(lineId) { SidecarExtras() }
                        content.mainText.takeIf(String::isNotBlank)?.let { text ->
                            extras.main.add(SidecarEntry(role, text))
                        }
                        content.backgroundText.takeIf(String::isNotBlank)?.let { text ->
                            extras.background.add(SidecarEntry(role, text))
                        }
                    }
                } else {
                    skipElement(parser)
                }
            }
            event = parser.next()
        }
    }

    private fun addSupplementaryContent(
        content: ParsedSupplementaryContent,
        role: PipoLyricRole,
        startMs: Long,
        endMs: Long,
        mainDestination: MutableList<PipoLyricLine>,
        backgroundDestination: MutableList<PipoLyricLine>,
    ) {
        content.mainText.takeIf(String::isNotBlank)?.let { text ->
            mainDestination.add(supplementaryLine(text, role, startMs, endMs))
        }
        content.backgroundText.takeIf(String::isNotBlank)?.let { text ->
            backgroundDestination.add(supplementaryLine(text, role, startMs, endMs))
        }
    }

    private fun supplementaryLine(
        text: String,
        role: PipoLyricRole,
        startMs: Long,
        endMs: Long,
    ): PipoLyricLine {
        return PipoLyricLine(
            startMs = startMs,
            durationMs = (endMs - startMs).coerceAtLeast(1L),
            text = text,
            timing = PipoLyricTiming.Line,
            role = role,
        )
    }

    private fun attachSidecar(line: PipoLyricLine, extras: SidecarExtras?): PipoLyricLine {
        if (extras == null) return line
        val mainExtras = extras.main.map { entry ->
            supplementaryLine(
                text = entry.text,
                role = entry.role,
                startMs = line.startMs,
                endMs = line.startMs + line.durationMs,
            )
        }
        var companions = distinctSupplementaries(line.companionLines + mainExtras)
        if (extras.background.isNotEmpty()) {
            val backgroundIndex = companions.indexOfFirst { it.role == PipoLyricRole.Companion }
            if (backgroundIndex >= 0) {
                val background = companions[backgroundIndex]
                val backgroundExtras = extras.background.map { entry ->
                    supplementaryLine(
                        text = entry.text,
                        role = entry.role,
                        startMs = background.startMs,
                        endMs = background.startMs + background.durationMs,
                    )
                }
                companions = companions.toMutableList().also { updated ->
                    updated[backgroundIndex] = background.copy(
                        companionLines = distinctSupplementaries(
                            background.companionLines + backgroundExtras,
                        ),
                    )
                }
            }
        }
        return line.copy(companionLines = companions)
    }

    private fun attachBackgroundSupplementaries(
        companions: List<PipoLyricLine>,
        supplementaries: List<PipoLyricLine>,
    ): List<PipoLyricLine> {
        if (supplementaries.isEmpty()) return companions
        val backgroundIndex = companions.indexOfFirst { it.role == PipoLyricRole.Companion }
        if (backgroundIndex < 0) return companions
        return companions.toMutableList().also { updated ->
            val background = updated[backgroundIndex]
            updated[backgroundIndex] = background.copy(
                companionLines = distinctSupplementaries(
                    background.companionLines + supplementaries,
                ),
            )
        }
    }

    private fun distinctSupplementaries(lines: List<PipoLyricLine>): List<PipoLyricLine> {
        // 简化的 Pipo 行模型没有语言维度；sidecar 同一行可能同时带英文、中文等多个版本。
        // 对齐 AMLL 降级到简化歌词时的规则：主唱 / 背景人声各保留第一条翻译和第一条音译。
        // Companion 本身可以有多条，不能按 role 去重。
        val seenSupplementaryRoles = mutableSetOf<PipoLyricRole>()
        return lines.filter { line ->
            when (line.role) {
                PipoLyricRole.Translation, PipoLyricRole.Romaji -> seenSupplementaryRoles.add(line.role)
                else -> true
            }
        }
    }

    private fun XmlPullParser.attributeValueByLocalName(localName: String): String? {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index) == localName) return getAttributeValue(index)
        }
        return null
    }

    /** 把当前普通 `<span>` 的直接文本子节点拼起来；ruby 由 [parseRubyContainer] 单独解析。 */
    private fun readSpanText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT -> if (depth == 1) sb.append(parser.text)
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
    }

    private fun skipElement(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** 优先无命名空间（AMLL 写的就是 `begin="..."`），其次任意命名空间。 */
    private fun parseTimeAttr(parser: XmlPullParser, name: String): Long? {
        val raw = parser.getAttributeValue(null, name)
            ?: parser.getAttributeValue(XmlPullParser.NO_NAMESPACE, name)
            ?: return null
        return parseTtmlTime(raw)
    }

    /**
     * TTML 时间 → ms。支持 `HH:MM:SS.fff` / `MM:SS.fff` / `SS.fff` / `12345ms` / 纯数字毫秒。
     * 容错：失败返回 null（调用方决定怎么处理）。
     */
    internal fun parseTtmlTime(value: String): Long? {
        val v = value.trim()
        if (v.isEmpty()) return null
        // `12345ms` 或 `12.5s` 这类带后缀的形式
        if (v.endsWith("ms", ignoreCase = true)) {
            return v.dropLast(2).trim().toDoubleOrNull()?.toLong()
        }
        if (v.endsWith("s", ignoreCase = true) && !v.contains(':')) {
            return v.dropLast(1).trim().toDoubleOrNull()?.let { (it * 1000.0).toLong() }
        }
        // `HH:MM:SS.fff` / `MM:SS.fff` / `SS.fff`
        val parts = v.split(':')
        return when (parts.size) {
            1 -> parts[0].toDoubleOrNull()?.let { (it * 1000.0).toLong() }
            2 -> {
                val min = parts[0].toLongOrNull() ?: return null
                val sec = parts[1].toDoubleOrNull() ?: return null
                (min * 60_000L + (sec * 1000.0).toLong())
            }
            3 -> {
                val hr = parts[0].toLongOrNull() ?: return null
                val min = parts[1].toLongOrNull() ?: return null
                val sec = parts[2].toDoubleOrNull() ?: return null
                (hr * 3_600_000L + min * 60_000L + (sec * 1000.0).toLong())
            }
            else -> null
        }
    }

    private data class ParsedP(
        val beginMs: Long,
        val endMs: Long,
        val key: String?,
        val agent: String?,
        val text: String,
        val chars: List<PipoLyricChar>,
        val companions: List<PipoLyricLine>,
    ) {
        fun toPipoLine(
            role: PipoLyricRole,
            alignment: PipoLyricAlignment = PipoLyricAlignment.Start,
        ): PipoLyricLine {
            val durationMs = (endMs - beginMs).coerceAtLeast(1L)
            val finalText = if (text.isNotEmpty()) text else chars.joinToString("") { it.text }
            val timing = if (chars.isNotEmpty()) PipoLyricTiming.Word else PipoLyricTiming.Line
            // 副词跟随所在 <p> 的对齐方向 —— v2 行里的 x-bg 也右对齐，
            // 视觉上跟主行配成一对。Translation 同理由渲染层判断。
            val alignedCompanions = if (alignment == PipoLyricAlignment.Start) {
                companions
            } else {
                companions.map { it.copy(alignment = alignment) }
            }
            return PipoLyricLine(
                startMs = beginMs,
                durationMs = durationMs,
                text = finalText,
                chars = chars,
                timing = timing,
                companionLines = alignedCompanions,
                role = role,
                alignment = alignment,
            )
        }
    }

    private const val TTM_NS = "http://www.w3.org/ns/ttml#metadata"
    private const val TTS_NS = "http://www.w3.org/ns/ttml#styling"
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
    private const val ITUNES_NS = "http://music.apple.com/lyric-ttml-internal"
    private const val DEFAULT_AGENT_ID = "v1"
    private const val AGENT_TYPE_GROUP = "group"
    private const val AGENT_TYPE_OTHER = "other"

    private data class ParsedSupplementaryContent(
        val mainText: String,
        val backgroundText: String,
    )

    private data class SidecarEntry(
        val role: PipoLyricRole,
        val text: String,
    )

    private class SidecarExtras {
        val main = mutableListOf<SidecarEntry>()
        val background = mutableListOf<SidecarEntry>()
    }

    private data class AgentInfo(
        val id: String,
        val type: String?,
    )
}
