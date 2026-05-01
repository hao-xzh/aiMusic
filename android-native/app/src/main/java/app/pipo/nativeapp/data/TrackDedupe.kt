package app.pipo.nativeapp.data

/**
 * 跨源去重 —— 镜像 src/lib/track-dedupe.ts。
 * 同一首歌的 cover/live/remix/伴奏 多版本合并到同 songKey。
 */
object TrackDedupe {
    private val VERSION_WORDS = listOf("live", "remix", "伴奏", "纯音乐", "cover", "翻唱")
    private val PAREN_RE = Regex("（.*?）|\\(.*?\\)|\\[.*?\\]")
    private val SPACE_RE = Regex("\\s+")
    private val VERSION_RE = Regex("live|remix|伴奏|纯音乐|cover|翻唱", RegexOption.IGNORE_CASE)

    fun normalizeTitle(name: String): String {
        return name.lowercase()
            .replace(SPACE_RE, "")
            .replace(PAREN_RE, "")
            .replace(VERSION_RE, "")
    }

    fun songKey(t: NativeTrack): String {
        val title = normalizeTitle(t.title)
        // React 取 artists[0]；NativeTrack.artist 是已经合好的串，取第一段最稳
        val firstArtist = t.artist.split("/", "&", ",", " feat.", "feat.").firstOrNull()?.trim().orEmpty()
        val artist = normalizeTitle(firstArtist)
        return "$title::$artist"
    }

    fun dedupe(items: List<NativeTrack>): List<NativeTrack> {
        val seen = HashSet<String>()
        val out = ArrayList<NativeTrack>(items.size)
        for (t in items) {
            val k = songKey(t)
            if (seen.add(k)) out.add(t)
        }
        return out
    }

    /** 用户原话明确点名了某首/某人/某专辑 —— 用于跳过去重和负向过滤 */
    fun queryExplicitlyMentions(track: NativeTrack, hardArtists: List<String>, hardTracks: List<String>, textArtists: List<String>, textTracks: List<String>): Boolean {
        val title = normalizeTitle(track.title)
        val firstArtist = track.artist.split("/", "&", ",").firstOrNull()?.trim().orEmpty()
        val artist = normalizeTitle(firstArtist)
        val album = normalizeTitle(track.album)
        val trackHints = (hardTracks + textTracks).map(::normalizeTitle).filter { it.isNotBlank() }
        val artistHints = (hardArtists + textArtists).map(::normalizeTitle).filter { it.isNotBlank() }
        return (title.isNotEmpty() && trackHints.any { it in title || title in it }) ||
            (artist.isNotEmpty() && artistHints.any { it in artist || artist in it }) ||
            (album.isNotEmpty() && trackHints.any { it in album || album in it })
    }

    /** 是否在找特定版本（live/remix/伴奏/...）—— 命中时不要把这些版本去掉 */
    fun queryAsksForSpecificVersion(allTextTokens: List<String>): Boolean {
        val text = allTextTokens.joinToString(" ").lowercase()
        return VERSION_WORDS.any { it in text }
    }
}
