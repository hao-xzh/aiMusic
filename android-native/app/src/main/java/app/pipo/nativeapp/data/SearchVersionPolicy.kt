package app.pipo.nativeapp.data

/** Version labels guide search only; they never prove that two sources are one recording. */
object SearchVersionPolicy {
    private val labels = Regex("[（(\\[【]([^()（）\\[\\]【】]+)[）)\\]】]|\\s[-–—]\\s(.+)$")
    private val groups = listOf(
        Regex("(?<![a-z])live(?![a-z])|现场|現場|演唱会|演唱會", RegexOption.IGNORE_CASE),
        Regex("(?<![a-z])cover(?![a-z])|翻唱", RegexOption.IGNORE_CASE),
        Regex("(?<![a-z])(?:instrumental|karaoke)(?![a-z])|伴奏|纯音乐|純音樂", RegexOption.IGNORE_CASE),
        Regex("(?<![a-z])remix(?![a-z])|混音", RegexOption.IGNORE_CASE),
        Regex("(?<![a-z])(?:acoustic|unplugged)(?![a-z])|不插电|不插電", RegexOption.IGNORE_CASE),
        Regex("(?<![a-z])demo(?![a-z])|小样|小樣", RegexOption.IGNORE_CASE),
    )
    private val trailingLabel = Regex("(?:^|\\s)(live|cover|instrumental|karaoke|remix|acoustic|unplugged|demo)(?:\\s+(?:version|edit|mix))?$", RegexOption.IGNORE_CASE)
    private val chineseLabels = Regex("现场|現場|演唱会|演唱會|翻唱|伴奏|混音|不插电|不插電|小样|小樣")
    private val liveAlbum = Regex("(?<![a-z])live(?![a-z])|现场|現場|演唱会|演唱會", RegexOption.IGNORE_CASE)

    private fun versions(text: String): Set<Int> {
        val annotations = labels.findAll(text).flatMap { it.groupValues.drop(1).asSequence() }.joinToString(" ")
        val marked = annotations + " " + trailingLabel.find(text)?.value.orEmpty() + " " +
            chineseLabels.findAll(text).joinToString(" ") { it.value }
        return groups.indices.filterTo(linkedSetOf()) { groups[it].containsMatchIn(marked) }
    }

    fun accepts(track: NativeTrack, query: String): Boolean {
        val requested = versions(query)
        val actual = versions(track.title) + versions(track.album) +
            if (liveAlbum.containsMatchIn(track.album)) setOf(0) else emptySet()
        return if (requested.isEmpty()) actual.isEmpty() else actual.containsAll(requested)
    }
}
