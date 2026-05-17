package app.pipo.nativeapp.data

data class LyricClock(
    val positionMs: Long,
    val activeIndex: Int,
    val timing: PipoLyricTiming,
)

object LyricTiming {
    fun resolve(positionMs: Long, lines: List<PipoLyricLine>): LyricClock {
        val safePositionMs = positionMs.coerceAtLeast(0L)
        if (lines.isEmpty()) {
            return LyricClock(
                positionMs = safePositionMs,
                activeIndex = -1,
                timing = PipoLyricTiming.Line,
            )
        }

        val timing = if (lines.any { it.timing == PipoLyricTiming.Word && it.chars.isNotEmpty() }) {
            PipoLyricTiming.Word
        } else {
            PipoLyricTiming.Line
        }
        return LyricClock(
            positionMs = safePositionMs,
            activeIndex = activeIndexFor(safePositionMs, lines, timing),
            timing = timing,
        )
    }

    fun focusLeadMs(lines: List<PipoLyricLine>): Long {
        val timing = if (lines.any { it.timing == PipoLyricTiming.Word && it.chars.isNotEmpty() }) {
            PipoLyricTiming.Word
        } else {
            PipoLyricTiming.Line
        }
        return focusLeadMs(timing)
    }

    private fun activeIndexFor(
        positionMs: Long,
        lines: List<PipoLyricLine>,
        timing: PipoLyricTiming,
    ): Int {
        val ledPositionMs = positionMs + focusLeadMs(timing)
        val idx = lines.indexOfLast { line -> ledPositionMs >= audioStartMs(line, timing) }
        return idx
    }

    fun audioStartMs(line: PipoLyricLine): Long {
        val timing = if (line.timing == PipoLyricTiming.Word && line.chars.isNotEmpty()) {
            PipoLyricTiming.Word
        } else {
            PipoLyricTiming.Line
        }
        return audioStartMs(line, timing)
    }

    private fun audioStartMs(line: PipoLyricLine, timing: PipoLyricTiming): Long {
        if (timing != PipoLyricTiming.Word || line.chars.isEmpty()) return line.startMs
        return line.chars.firstOrNull()?.startMs ?: line.startMs
    }

    private fun focusLeadMs(timing: PipoLyricTiming): Long {
        return if (timing == PipoLyricTiming.Line) LINE_FOCUS_LEAD_MS else 0L
    }

    private const val LINE_FOCUS_LEAD_MS = 220L
}
