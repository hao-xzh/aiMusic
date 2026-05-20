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
 *         <p ... ttm:agent="v2">          ← v2 = 副唱 / 合唱
 *           ...
 *         </p>
 *       </div>
 *     </body>
 *   </tt>
 * ```
 *
 * 输出按既有 [PipoLyricLine] 模型组装：
 *   · `<p>` → 一行（agent=v1 → Primary；agent≠v1 → Companion，附到上一条 Primary）
 *   · `<span begin end>` → [PipoLyricChar]（字级 timing）
 *   · `<span ttm:role="x-translation">` → 一条 `role = Translation` 的 companion
 *   · `<span ttm:role="x-roman">` → 暂不消费（保留位置，未来要展示罗马音再加）
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
        val companionsForPrev = mutableListOf<PipoLyricLine>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "p") {
                val parsed = parseP(parser)
                if (parsed != null) {
                    val isPrimary = parsed.agent == null || parsed.agent == "v1"
                    if (isPrimary) {
                        // 上一条 primary 还没结算的 companions 先合上
                        if (companionsForPrev.isNotEmpty() && primaries.isNotEmpty()) {
                            val last = primaries.removeAt(primaries.lastIndex)
                            primaries.add(
                                last.copy(companionLines = last.companionLines + companionsForPrev.toList())
                            )
                            companionsForPrev.clear()
                        }
                        primaries.add(parsed.toPipoLine(role = PipoLyricRole.Primary))
                    } else {
                        companionsForPrev.add(parsed.toPipoLine(role = PipoLyricRole.Companion))
                    }
                }
            }
            event = parser.next()
        }
        // 文档结束时还有未挂的 companions
        if (companionsForPrev.isNotEmpty() && primaries.isNotEmpty()) {
            val last = primaries.removeAt(primaries.lastIndex)
            primaries.add(last.copy(companionLines = last.companionLines + companionsForPrev.toList()))
        }
        return primaries.sortedBy { it.startMs }
    }

    /** 解析单个 `<p>` 元素 —— 返回 null 表示该 p 没拿到有效 begin/end，直接跳过。 */
    private fun parseP(parser: XmlPullParser): ParsedP? {
        val pBegin = parseTimeAttr(parser, "begin") ?: return null
        val pEnd = parseTimeAttr(parser, "end") ?: return null
        val pAgent = parser.getAttributeValue(TTM_NS, "agent")
            ?: parser.getAttributeValue(null, "ttm:agent")
        val chars = mutableListOf<PipoLyricChar>()
        val translations = mutableListOf<PipoLyricLine>()
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
                                    translations.add(
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
                                // 暂不消费罗马音 —— 跳过 span 内容
                                skipElement(parser)
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
                    // <p> 直接夹着的文字（极少见，但 LRC-style fallback 也可能出现）
                    mainTextBuilder.append(parser.text)
                }
                else -> Unit
            }
            event = parser.next()
        }
        return ParsedP(
            beginMs = pBegin,
            endMs = pEnd,
            agent = pAgent,
            text = mainTextBuilder.toString(),
            chars = chars,
            translations = translations,
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
        val translations: List<PipoLyricLine>,
    ) {
        fun toPipoLine(role: PipoLyricRole): PipoLyricLine {
            val durationMs = (endMs - beginMs).coerceAtLeast(1L)
            val finalText = if (text.isNotEmpty()) text else chars.joinToString("") { it.text }
            val timing = if (chars.isNotEmpty()) PipoLyricTiming.Word else PipoLyricTiming.Line
            return PipoLyricLine(
                startMs = beginMs,
                durationMs = durationMs,
                text = finalText,
                chars = chars,
                timing = timing,
                companionLines = translations,
                role = role,
            )
        }
    }

    private const val TTM_NS = "http://www.w3.org/ns/ttml#metadata"
}
