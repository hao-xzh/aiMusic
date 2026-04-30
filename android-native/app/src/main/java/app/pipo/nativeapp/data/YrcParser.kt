package app.pipo.nativeapp.data

object YrcParser {
    private val lineRegex = Regex("""\[(\d+),(\d+)](.*)""")
    private val charRegex = Regex("""\((\d+),(\d+),\d+\)([^(]+)""")

    fun parse(raw: String): List<PipoLyricLine> {
        return raw.lineSequence().mapNotNull { line ->
            val match = lineRegex.matchEntire(line.trim()) ?: return@mapNotNull null
            val start = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val duration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val chars = charRegex.findAll(match.groupValues[3]).mapNotNull { c ->
                val charStart = c.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val charDuration = c.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                PipoLyricChar(
                    startMs = start + charStart,
                    durationMs = charDuration,
                    text = c.groupValues[3],
                )
            }.toList()
            PipoLyricLine(
                startMs = start,
                durationMs = duration,
                text = chars.joinToString(separator = "") { it.text },
                chars = chars,
            )
        }.sortedBy { it.startMs }.toList()
    }
}
