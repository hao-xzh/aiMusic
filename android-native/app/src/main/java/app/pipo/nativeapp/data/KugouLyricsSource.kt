package app.pipo.nativeapp.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.abs

/** Fetches verified, word-timed KRC lyrics from Kugou without keeping a provider cache. */
internal class KugouLyricsSource {
    suspend fun lyricsForTrack(track: NativeTrack): OnlineLyrics? {
        if (
            track.title.isBlank() ||
            track.artist.isBlank() ||
            track.durationMs <= 0L
        ) {
            return null
        }

        val songs = searchSongs(track)
            .mapNotNull { song ->
                OnlineLyricSupport.matchScore(
                    track = track,
                    title = song.title,
                    artists = song.artists,
                    album = song.album,
                    durationMs = song.durationMs,
                )?.let { score -> song to score }
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { "${it.id}:${it.hash}" }
            .take(MAX_SONG_CANDIDATES)

        for (song in songs) {
            val lyricCandidate = searchLyricCandidate(track, song) ?: continue
            val downloaded = kugouRequest(
                url = DOWNLOAD_URL,
                module = LYRIC_MODULE,
                parameters = mapOf(
                    "accesskey" to lyricCandidate.accessKey,
                    "charset" to "utf8",
                    "client" to "mobi",
                    "fmt" to "krc",
                    "id" to lyricCandidate.id,
                    "ver" to "1",
                ),
            )
            if (!downloaded.has("contenttype")) throw IOException("Malformed Kugou KRC response")
            if (downloaded.optInt("contenttype", -1) != KRC_CONTENT_TYPE) continue
            val encoded = downloaded.optString("content")
            if (encoded.isBlank()) throw IOException("Empty Kugou KRC content")

            val parsed = KrcLyricsParser.parse(decryptKrc(encoded))
            if (parsed.hasUnparsedSungText) continue
            val lines = LyricCredits.stripLeading(parsed.lines)
            if (OnlineLyricSupport.usableWordLyrics(lines, track.durationMs)) {
                return OnlineLyrics(
                    source = SOURCE,
                    sourceTrackId = song.id,
                    lines = lines,
                )
            }
        }
        return null
    }

    private suspend fun searchSongs(track: NativeTrack): List<SongCandidate> {
        val result = kugouRequest(
            url = SONG_SEARCH_URL,
            module = SEARCH_MODULE,
            parameters = mapOf(
                "sorttype" to "0",
                "keyword" to "${track.artist} ${track.title}",
                "pagesize" to SONG_SEARCH_PAGE_SIZE.toString(),
                "page" to "1",
            ),
        )
        val candidates = result.optJSONObject("data")?.optJSONArray("lists") ?: return emptyList()
        return buildList {
            for (index in 0 until candidates.length()) {
                val candidate = candidates.optJSONObject(index) ?: continue
                val id = candidate.optString("ID").trim()
                val hash = candidate.optString("FileHash").trim()
                val title = candidate.optString("SongName").trim()
                val album = candidate.optString("AlbumName").trim()
                val durationMs = candidate.optLong("Duration", 0L) * 1_000L
                val artists = candidate.optJSONArray("Singers").readNames()
                if (id.isBlank() || hash.isBlank() || title.isBlank() || durationMs <= 0L || artists.isEmpty()) continue
                add(SongCandidate(id, hash, title, artists, album, durationMs))
            }
        }
    }

    private suspend fun searchLyricCandidate(
        track: NativeTrack,
        song: SongCandidate,
    ): LyricCandidate? {
        val result = kugouRequest(
            url = LYRIC_SEARCH_URL,
            module = LYRIC_MODULE,
            parameters = mapOf(
                "album_audio_id" to song.id,
                "duration" to song.durationMs.toString(),
                "hash" to song.hash,
                "keyword" to "${track.artist} - ${track.title}",
                "lrctxt" to "1",
                "man" to "no",
            ),
        )
        val candidates = result.optJSONArray("candidates") ?: return null
        return buildList {
            for (index in 0 until candidates.length()) {
                val candidate = candidates.optJSONObject(index) ?: continue
                val id = candidate.optString("id").trim()
                val accessKey = candidate.optString("accesskey").trim()
                val durationMs = candidate.optLong("duration", 0L)
                val score = lyricCandidateScore(candidate, song, durationMs)
                if (id.isBlank() || accessKey.isBlank() || score == null) continue
                add(LyricCandidate(id, accessKey, score))
            }
        }.maxByOrNull { it.score }
    }

    /** The search request binds hash and album-audio id; reject any returned metadata that contradicts it. */
    private fun lyricCandidateScore(candidate: JSONObject, song: SongCandidate, durationMs: Long): Int? {
        if (durationMs <= 0L || abs(durationMs - song.durationMs) > MAX_DURATION_DIFFERENCE_MS) return null

        val declaredHash = candidate.optString("hash").trim()
        if (declaredHash.isNotEmpty() && !declaredHash.equals(song.hash, ignoreCase = true)) return null

        val declaredSongId = sequenceOf("album_audio_id", "song_id", "audio_id")
            .map { key -> candidate.optString(key).trim() }
            .firstOrNull { it.isNotEmpty() }
        if (declaredSongId != null && declaredSongId != song.id) return null

        val declaredTitle = candidate.firstNonBlank("song", "songname", "song_name", "title")
        val declaredArtists = candidate.readArtists()
        val declaredAlbum = candidate.firstNonBlank("album", "album_name")
        if (declaredTitle != null || declaredArtists.isNotEmpty() || declaredAlbum != null) {
            val matchedSong = NativeTrack(
                id = song.id,
                title = song.title,
                artist = song.artists.joinToString("、"),
                album = song.album,
                streamUrl = "",
                durationMs = song.durationMs,
            )
            if (
                OnlineLyricSupport.matchScore(
                    track = matchedSong,
                    title = declaredTitle ?: song.title,
                    artists = declaredArtists.ifEmpty { song.artists },
                    album = declaredAlbum ?: song.album,
                    durationMs = durationMs,
                ) == null
            ) return null
        }

        var score = candidate.optInt("score", 0)
        if (declaredHash.isNotEmpty()) score += MATCHED_HASH_BONUS
        if (declaredSongId != null) score += MATCHED_SONG_ID_BONUS
        return score
    }

    private suspend fun kugouRequest(
        url: String,
        module: String,
        parameters: Map<String, String>,
    ): JSONObject {
        val nowMs = System.currentTimeMillis()
        val mid = md5(nowMs.toString())
        val signed = linkedMapOf<String, String>()
        if (module == LYRIC_MODULE) {
            signed["appid"] = "3116"
            signed["clientver"] = "11070"
        } else {
            signed.putAll(
                mapOf(
                    "userid" to "0",
                    "appid" to "3116",
                    "token" to "",
                    "clienttime" to (nowMs / 1_000L).toString(),
                    "iscorrection" to "1",
                    "uuid" to "-",
                    "mid" to mid,
                    "dfid" to "-",
                    "clientver" to "11070",
                    "platform" to "AndroidFilter",
                ),
            )
        }
        signed.putAll(parameters)
        val joined = signed.entries
            .sortedBy { it.key }
            .joinToString(separator = "") { "${it.key}=${it.value}" }
        signed["signature"] = md5("$SIGNATURE_KEY$joined$SIGNATURE_KEY")

        val requestUrl = url.toHttpUrl().newBuilder().apply {
            signed.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", "Android14-1070-11070-201-0-$module-wifi")
            .header("Connection", "Keep-Alive")
            .header("KG-Rec", "1")
            .header("KG-RC", "1")
            .header("KG-CLIENTTIMEMS", nowMs.toString())
            .header("mid", mid)
            .get()
            .build()
        return OnlineLyricSupport.requestJson(request).also { response ->
            val errorCode = response.optInt("error_code", 0)
            val failedStatus = response.has("status") && response.optInt("status", 1) == 0
            if (failedStatus || (errorCode != 0 && errorCode != 200)) {
                throw IOException("Kugou lyrics API error $errorCode")
            }
        }
    }

    private fun decryptKrc(encoded: String): String {
        val encrypted = Base64.getDecoder().decode(encoded)
        if (encrypted.size <= KRC_MAGIC.size || !encrypted.copyOfRange(0, KRC_MAGIC.size).contentEquals(KRC_MAGIC)) {
            throw IOException("Invalid Kugou KRC payload")
        }
        val compressed = ByteArray(encrypted.size - KRC_MAGIC.size) { index ->
            (encrypted[index + KRC_MAGIC.size].toInt() xor KRC_XOR_KEY[index % KRC_XOR_KEY.size].toInt()).toByte()
        }
        return OnlineLyricSupport.inflate(compressed)
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private data class SongCandidate(
        val id: String,
        val hash: String,
        val title: String,
        val artists: List<String>,
        val album: String,
        val durationMs: Long,
    )

    private data class LyricCandidate(
        val id: String,
        val accessKey: String,
        val score: Int,
    )

    private companion object {
        const val SOURCE = "kugou"
        const val SEARCH_MODULE = "SearchSong"
        const val LYRIC_MODULE = "Lyric"
        const val SONG_SEARCH_URL = "https://complexsearch.kugou.com/v2/search/song"
        const val LYRIC_SEARCH_URL = "https://lyrics.kugou.com/v1/search"
        const val DOWNLOAD_URL = "https://lyrics.kugou.com/download"
        const val SONG_SEARCH_PAGE_SIZE = 20
        const val MAX_SONG_CANDIDATES = 2
        const val MAX_DURATION_DIFFERENCE_MS = 2_000L
        const val MATCHED_HASH_BONUS = 10_000
        const val MATCHED_SONG_ID_BONUS = 10_000
        const val KRC_CONTENT_TYPE = 0
        const val SIGNATURE_KEY = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
        val KRC_MAGIC = "krc1".toByteArray(Charsets.US_ASCII)
        val KRC_XOR_KEY = byteArrayOf(
            '@'.code.toByte(), 'G'.code.toByte(), 'a'.code.toByte(), 'w'.code.toByte(),
            '^'.code.toByte(), '2'.code.toByte(), 't'.code.toByte(), 'G'.code.toByte(),
            'Q'.code.toByte(), '6'.code.toByte(), '1'.code.toByte(), '-'.code.toByte(),
            0xce.toByte(), 0xd2.toByte(), 'n'.code.toByte(), 'i'.code.toByte(),
        )
    }
}

private object KrcLyricsParser {
    private val linePattern = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val wordHeader = Regex("""(?:\[\d+,\d+])?<(\d+),(\d+),-?\d+>""")
    private val tagPattern = Regex("""^\[([A-Za-z]+):(.*)]$""")

    fun parse(krc: String): KrcParseResult {
        val parsed = mutableListOf<IndexedLine>()
        var languageTag: String? = null
        var sourceLineIndex = 0
        var romajiLineIndex = 0
        var hasUnparsedSungText = false

        for (rawLine in krc.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            val tag = tagPattern.matchEntire(line)
            if (tag != null) {
                if (tag.groupValues[1].equals("language", ignoreCase = true)) {
                    languageTag = tag.groupValues[2].trim()
                }
                continue
            }

            val match = linePattern.matchEntire(line) ?: continue
            val lineIndex = sourceLineIndex++
            val lineStart = match.groupValues[1].toLongOrNull() ?: continue
            val lineDuration = match.groupValues[2].toLongOrNull() ?: continue
            val rawContent = match.groupValues[3]
            val wordParse = parseWords(
                lineStart = lineStart,
                content = rawContent,
            )
            hasUnparsedSungText = hasUnparsedSungText || wordParse.hasUnparsedPrefix
            val hasOriginalContent = if (wordParse.hasHeaders) wordParse.hasOriginalContent else rawContent.isNotEmpty()
            if (!hasOriginalContent) continue

            val romajiIndex = romajiLineIndex++
            val text = if (wordParse.hasHeaders) {
                wordParse.chars.joinToString(separator = "") { it.text }
            } else {
                rawContent
            }

            parsed += IndexedLine(
                sourceIndex = lineIndex,
                romajiIndex = romajiIndex,
                line = PipoLyricLine(
                    startMs = lineStart,
                    durationMs = lineDuration,
                    text = text,
                    chars = wordParse.chars,
                    timing = if (wordParse.hasHeaders) PipoLyricTiming.Word else PipoLyricTiming.Line,
                ),
            )
        }

        return KrcParseResult(
            lines = attachLanguage(
                lines = parsed,
                languageTag = languageTag,
                sourceLineCount = sourceLineIndex,
                romajiLineCount = romajiLineIndex,
            ).sortedBy { it.startMs },
            hasUnparsedSungText = hasUnparsedSungText,
        )
    }

    private fun parseWords(lineStart: Long, content: String): ParsedWords {
        val headers = wordHeader.findAll(content).toList()
        if (headers.isEmpty()) return ParsedWords(emptyList(), hasHeaders = false, hasOriginalContent = false, hasUnparsedPrefix = false)
        var hasOriginalContent = false
        val chars = buildList<PipoLyricChar> {
            var pendingDecoration = ""
            headers.forEachIndexed { index, header ->
                val offset = header.groupValues[1].toLongOrNull() ?: return@forEachIndexed
                val duration = header.groupValues[2].toLongOrNull() ?: return@forEachIndexed
                val textStart = header.range.last + 1
                val textEnd = headers.getOrNull(index + 1)?.range?.first ?: content.length
                val text = content.substring(textStart, textEnd)
                if (text.isEmpty()) return@forEachIndexed
                hasOriginalContent = true
                val startMs = lineStart + offset
                if (duration == 0L && text.isWhitespaceOrPunctuation()) {
                    if (isEmpty()) {
                        pendingDecoration += text
                    } else {
                        replaceLastText(lastIndex, last().text + text)
                    }
                    return@forEachIndexed
                }
                val mergedText = pendingDecoration + text
                pendingDecoration = ""
                add(
                    PipoLyricChar(
                        startMs = startMs,
                        durationMs = duration,
                        text = mergedText,
                        timingParts = listOf(PipoLyricTimingPart(startMs, duration, mergedText)),
                    ),
                )
            }
            if (pendingDecoration.isNotEmpty()) {
                if (isEmpty()) {
                    add(PipoLyricChar(lineStart, 0L, pendingDecoration, listOf(PipoLyricTimingPart(lineStart, 0L, pendingDecoration))))
                } else {
                    replaceLastText(lastIndex, last().text + pendingDecoration)
                }
            }
        }
        return ParsedWords(
            chars = chars,
            hasHeaders = true,
            hasOriginalContent = hasOriginalContent,
            hasUnparsedPrefix = content.substring(0, headers.first().range.first).isNotEmpty(),
        )
    }

    private fun MutableList<PipoLyricChar>.replaceLastText(index: Int, text: String) {
        val previous = this[index]
        this[index] = previous.copy(
            text = text,
            timingParts = previous.timingParts.map { part -> part.copy(text = text) },
        )
    }

    private fun String.isWhitespaceOrPunctuation(): Boolean = isNotEmpty() && all { char ->
        char.isWhitespace() || Character.getType(char) in PUNCTUATION_TYPES
    }

    private fun attachLanguage(
        lines: List<IndexedLine>,
        languageTag: String?,
        sourceLineCount: Int,
        romajiLineCount: Int,
    ): List<PipoLyricLine> {
        if (languageTag.isNullOrBlank() || lines.isEmpty()) return lines.map { it.line }
        val contents = decodeLanguageContent(languageTag) ?: return lines.map { it.line }
        val supplements = mutableMapOf<Int, MutableList<PipoLyricLine>>()

        for (index in 0 until contents.length()) {
            val language = contents.optJSONObject(index) ?: continue
            val type = language.optInt("type", -1)
            val lyricContent = language.optJSONArray("lyricContent") ?: continue
            val expectedCount = when (type) {
                0 -> romajiLineCount
                1 -> sourceLineCount
                else -> continue
            }
            if (lyricContent.length() != expectedCount) continue
            for (source in lines) {
                val languageIndex = if (type == 0) source.romajiIndex else source.sourceIndex
                val words = lyricContent.optJSONArray(languageIndex) ?: continue
                when (type) {
                    0 -> romajiLine(source.line, words)?.let { supplements.getOrPut(source.sourceIndex, ::mutableListOf).add(it) }
                    1 -> translationLine(source.line, words)?.let { supplements.getOrPut(source.sourceIndex, ::mutableListOf).add(it) }
                }
            }
        }

        return lines.map { source ->
            source.line.copy(companionLines = supplements[source.sourceIndex].orEmpty())
        }
    }

    private fun decodeLanguageContent(languageTag: String): JSONArray? = runCatching {
        JSONObject(String(Base64.getDecoder().decode(languageTag), Charsets.UTF_8)).optJSONArray("content")
    }.getOrNull()

    /** Romaji has no timestamp of its own; KRC indexes it one-to-one with the original word timings. */
    private fun romajiLine(primary: PipoLyricLine, words: JSONArray): PipoLyricLine? {
        if (words.length() != primary.chars.size || words.length() == 0) return null
        val chars = buildList {
            for (index in 0 until words.length()) {
                val text = words.opt(index) as? String ?: return null
                if (text.isBlank()) return null
                val source = primary.chars[index]
                add(
                    PipoLyricChar(
                        startMs = source.startMs,
                        durationMs = source.durationMs,
                        text = text,
                        timingParts = listOf(PipoLyricTimingPart(source.startMs, source.durationMs, text)),
                    ),
                )
            }
        }
        return PipoLyricLine(
            startMs = primary.startMs,
            durationMs = primary.durationMs,
            text = chars.joinToString(separator = "") { it.text },
            chars = chars,
            timing = PipoLyricTiming.Word,
            role = PipoLyricRole.Romaji,
        )
    }

    private fun translationLine(primary: PipoLyricLine, words: JSONArray): PipoLyricLine? {
        val text = (words.opt(0) as? String)?.takeIf { it.isNotBlank() } ?: return null
        return PipoLyricLine(
            startMs = primary.startMs,
            durationMs = primary.durationMs,
            text = text,
            role = PipoLyricRole.Translation,
        )
    }

    private data class IndexedLine(
        val sourceIndex: Int,
        val romajiIndex: Int,
        val line: PipoLyricLine,
    )

    data class KrcParseResult(
        val lines: List<PipoLyricLine>,
        val hasUnparsedSungText: Boolean,
    )

    private data class ParsedWords(
        val chars: List<PipoLyricChar>,
        val hasHeaders: Boolean,
        val hasOriginalContent: Boolean,
        val hasUnparsedPrefix: Boolean,
    )

    private val PUNCTUATION_TYPES = setOf(
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
    ).map { it.toInt() }.toSet()
}

private fun JSONArray?.readNames(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
}

private fun JSONObject.firstNonBlank(vararg keys: String): String? = keys.asSequence()
    .mapNotNull { key -> (opt(key) as? String)?.trim() }
    .firstOrNull { it.isNotEmpty() }

private fun JSONObject.readArtists(): List<String> = buildList {
    optJSONArray("singers").readArtistValues().forEach(::add)
    optJSONArray("singer").readArtistValues().forEach(::add)
    sequenceOf("singer", "singername", "singer_name", "artist", "artist_name")
        .mapNotNull { key -> (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() } }
        .forEach(::add)
}.distinct()

private fun JSONArray?.readArtistValues(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            when (val value = opt(index)) {
                is String -> value.trim().takeIf { it.isNotEmpty() }?.let(::add)
                is JSONObject -> value.optString("name").trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
    }
}
