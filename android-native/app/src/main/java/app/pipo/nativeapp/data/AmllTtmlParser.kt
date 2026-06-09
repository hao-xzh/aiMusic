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
 *   · `<p>` → 一行 Primary（agent=v1 → alignment=Start 左对齐；agent≠v1 → alignment=End 右对齐）。
 *     和 AMLL 官方播放器一致 —— 不同演唱者的整行各自占一行，第二位歌手靠右展示。
 *   · `<span begin end>` → [PipoLyricChar]（字级 timing）
 *   · `<span ttm:role="x-translation">` → 一条 `role = Translation` 的 companion
 *   · `<span ttm:role="x-bg">` → 一条 `role = Companion` 的副词（"和声 / backing vocal"），
 *     附在所在 `<p>` 的 companionLines 上，由渲染层走小字浮入通道。
 *   · `<span ttm:role="x-roman">` → 一条 `role = Romaji`（音译）的小字行，显示在主词与翻译之间
 *
 * 时间格式支持 `HH:MM:SS.fff`、`MM:SS.fff`、`SS.fff`、纯整数毫秒。
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
        // 第一位出场的非 group 演唱者作为基准（左对齐）；与之不同的演唱者整行右对齐。
        // 这与 AMLL / Apple 官方对唱排版一致，且不依赖 ttm:type（大量 TTML 并不写 type）。
        var baseAgentId: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "agent" -> parseAgent(parser)?.let { agents[it.id] = it }
                    "p" -> {
                        val parsed = parseP(parser)
                        if (parsed != null) {
                            // 所有 <p> 都作为独立 Primary；对唱右对齐规则：
                            // group（多人合唱）不右对齐；其余演唱者中第一位出场者左对齐、其余一律右对齐。
                            // 真正的背景人声 / 副词仍只通过 <span ttm:role="x-bg"> 进入 companionLines。
                            val agentId = parsed.agent ?: DEFAULT_AGENT_ID
                            val agent = agents[agentId]
                            val isGroup = agent?.type == AGENT_TYPE_GROUP
                            val isDuet = if (isGroup) {
                                false
                            } else {
                                if (baseAgentId == null) baseAgentId = agentId
                                agentId != baseAgentId
                            }
                            val alignment = if (isDuet) {
                                PipoLyricAlignment.End
                            } else {
                                PipoLyricAlignment.Start
                            }
                            primaries.add(
                                parsed.toPipoLine(role = PipoLyricRole.Primary, alignment = alignment)
                            )
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
        val chars = mutableListOf<PipoLyricChar>()
        // companions：翻译 (x-translation) + 背景人声 (x-bg) 都挂到当前 p 的 companionLines。
        // 渲染层按 role 区分 Translation 行（小字翻译）与 Companion 行（合唱 / 副词）。
        val companions = mutableListOf<PipoLyricLine>()
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
                                val text = readSpanText(parser).trim()
                                if (text.isNotEmpty()) {
                                    companions.add(
                                        PipoLyricLine(
                                            startMs = pBegin,
                                            durationMs = (pEnd - pBegin).coerceAtLeast(1L),
                                            text = text,
                                            timing = PipoLyricTiming.Line,
                                            role = PipoLyricRole.Translation,
                                        )
                                    )
                                }
                            }
                            "x-roman" -> {
                                val text = readSpanText(parser).trim()
                                if (text.isNotEmpty()) {
                                    companions.add(
                                        PipoLyricLine(
                                            startMs = pBegin,
                                            durationMs = (pEnd - pBegin).coerceAtLeast(1L),
                                            text = text,
                                            timing = PipoLyricTiming.Line,
                                            role = PipoLyricRole.Romaji,
                                        )
                                    )
                                }
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
            agent = pAgent,
            text = mainTextBuilder.toString(),
            chars = mergedChars,
            companions = companions,
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
        val textBuilder = StringBuilder()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "span")) {
            if (event == XmlPullParser.END_DOCUMENT) return null
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "span") {
                        val role = parser.getAttributeValue(TTM_NS, "role")
                            ?: parser.getAttributeValue(null, "ttm:role")
                        if (role == null) {
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
                        } else {
                            // x-bg 里再嵌 x-translation/x-roman 极罕见，按"忽略 + 跳过"处理
                            skipElement(parser)
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
            role = PipoLyricRole.Companion,
        )
    }

    /** 把当前 `<span>` 的所有文本子节点拼起来 —— 不递归取嵌套 span（AMLL 实际只有一层）。 */
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
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
    private const val DEFAULT_AGENT_ID = "v1"
    private const val AGENT_TYPE_GROUP = "group"

    private data class AgentInfo(
        val id: String,
        val type: String?,
    )
}
