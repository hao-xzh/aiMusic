package app.pipo.nativeapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

/** Anonymous QQ Music QRC word-lyrics lookup for already-known local/cloud tracks. */
internal class QqLyricsSource {
    suspend fun lyricsForTrack(track: NativeTrack): OnlineLyrics? {
        if (track.title.isBlank() || track.artist.isBlank() || track.durationMs <= 0L) return null

        val query = "${track.artist} ${track.title}".trim()
        val candidates = search(query)
            .mapNotNull { candidate ->
                OnlineLyricSupport.matchScore(
                    track = track,
                    title = candidate.title,
                    artists = candidate.artists,
                    album = candidate.album,
                    durationMs = candidate.durationMs,
                )?.let { score -> candidate to score }
            }
            .sortedByDescending { it.second }
            .take(MAX_CANDIDATES)

        for ((candidate, _) in candidates) {
            val response = request(
                method = "GetPlayLyricInfo",
                module = "music.musichallSong.PlayLyricInfo",
                param = JSONObject().apply {
                    put("albumName", encodeBase64(candidate.album))
                    put("crypt", 1)
                    put("ct", 19)
                    put("cv", 2111)
                    put("interval", candidate.durationMs / 1000L)
                    put("lrc_t", 0)
                    put("qrc", 1)
                    put("qrc_t", 0)
                    put("roma", 1)
                    put("roma_t", 0)
                    put("singerName", encodeBase64(candidate.artists.joinToString(",")))
                    put("songID", candidate.id)
                    put("songName", encodeBase64(candidate.title))
                    put("trans", 1)
                    put("trans_t", 0)
                    put("type", 0)
                },
            )
            val primaryPayload = response.optString("lyric").trim()
            if (primaryPayload.isEmpty()) continue

            val primary = try {
                parseQrc(QrcDecoder.decode(primaryPayload), PipoLyricRole.Primary)
            } catch (_: QrcWordTimingException) {
                continue
            }
            if (primary.isEmpty()) continue

            val translations = optionalSidecar(response.optString("trans"), PipoLyricRole.Translation)
            val romanizations = optionalSidecar(response.optString("roma"), PipoLyricRole.Romaji)
            val lines = LyricCredits.stripLeading(attachCompanions(primary, translations, romanizations))
            if (!OnlineLyricSupport.usableWordLyrics(lines, track.durationMs)) continue

            return OnlineLyrics(
                source = SOURCE,
                sourceTrackId = candidate.id.toString(),
                lines = lines,
            )
        }
        return null
    }

    private suspend fun search(query: String): List<QqCandidate> {
        val data = request(
            method = "DoSearchForQQMusicLite",
            module = "music.search.SearchCgiService",
            param = JSONObject().apply {
                put("search_id", "1")
                put("remoteplace", "search.android.keyboard")
                put("query", query)
                put("search_type", 0)
                put("num_per_page", SEARCH_PAGE_SIZE)
                put("page_num", 1)
                put("highlight", 0)
                put("nqc_flag", 0)
                put("page_id", 1)
                put("grp", 1)
            },
        )
        val songs = data.optJSONObject("body")?.optJSONArray("item_song") ?: return emptyList()
        return songs.mapNotNull { item -> item.toCandidateOrNull() }
    }

    private suspend fun request(method: String, module: String, param: JSONObject): JSONObject {
        val current = session()
        return try {
            requestWithSession(current, method, module, param)
        } catch (error: QqApiException) {
            if (!error.isSessionFailure) throw error
            requestWithSession(refreshSession(current), method, module, param)
        }
    }

    private suspend fun session(): QqSession = sessionMutex.withLock {
        val now = System.currentTimeMillis()
        cachedSession?.takeIf { it.expiresAtMs > now } ?: createSession().also { cachedSession = it }
    }

    private suspend fun refreshSession(failed: QqSession): QqSession = sessionMutex.withLock {
        val now = System.currentTimeMillis()
        cachedSession?.takeIf { it.sid != failed.sid && it.expiresAtMs > now }
            ?: createSession().also { cachedSession = it }
    }

    private suspend fun createSession(): QqSession {
        val data = requestEnvelope(
            session = null,
            method = "GetSession",
            module = "music.getSession.session",
            param = JSONObject().apply {
                put("caller", 0)
                put("uid", "0")
                put("vkey", 0)
            },
        )
        val result = data.optJSONObject("session") ?: throw IOException("QQ lyrics session missing")
        val uid = result.optString("uid").takeIf { it.isNotBlank() } ?: throw IOException("QQ lyrics uid missing")
        val sid = result.optString("sid").takeIf { it.isNotBlank() } ?: throw IOException("QQ lyrics sid missing")
        val userIp = result.optString("userip").takeIf { it.isNotBlank() } ?: throw IOException("QQ lyrics address missing")
        return QqSession(uid, sid, userIp, System.currentTimeMillis() + SESSION_TTL_MS)
    }

    private suspend fun requestWithSession(
        session: QqSession,
        method: String,
        module: String,
        param: JSONObject,
    ): JSONObject = requestEnvelope(session, method, module, param)

    private suspend fun requestEnvelope(
        session: QqSession?,
        method: String,
        module: String,
        param: JSONObject,
    ): JSONObject {
        val comm = JSONObject().apply {
            put("ct", 11)
            put("cv", "1003006")
            put("v", "1003006")
            put("os_ver", "15")
            put("phonetype", "24122RKC7C")
            put("rom", "Redmi/miro/miro:15/AE3A.240806.005/OS2.0.105.0.VOMCNXM:user/release-keys")
            put("tmeAppID", "qqmusiclight")
            put("nettype", "NETWORK_WIFI")
            put("udid", "0")
            session?.let {
                put("uid", it.uid)
                put("sid", it.sid)
                put("userip", it.userIp)
            }
        }
        val body = JSONObject()
            .put("comm", comm)
            .put("request", JSONObject().put("method", method).put("module", module).put("param", param))
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val response = OnlineLyricSupport.requestJson(
            Request.Builder()
                .url(ENDPOINT)
                .header("Cookie", "tmeLoginType=-1;")
                .header("User-Agent", USER_AGENT)
                .post(body)
                .build(),
        )
        val reply = response.optJSONObject("request")
        val topCode = response.optInt("code", Int.MIN_VALUE)
        val requestCode = reply?.optInt("code", Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (topCode != 0 || requestCode != 0) {
            throw QqApiException(topCode, requestCode, response.optString("message", response.optString("msg")))
        }
        return reply?.optJSONObject("data") ?: throw IOException("QQ lyrics response missing data")
    }

    private fun parseQrc(
        raw: String,
        role: PipoLyricRole,
    ): List<PipoLyricLine> {
        val encodedContent = QRC_CONTENT.find(raw)?.groupValues?.getOrNull(1)
            ?: throw IOException("Unsupported QQ QRC payload")
        val content = decodeXmlEntities(encodedContent)
        return content.lineSequence().mapNotNull { rawLine ->
            val line = rawLine.trimEnd('\r')
            val header = LINE_HEADER.find(line) ?: return@mapNotNull null
            val startMs = header.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val durationMs = header.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val body = line.substring(header.range.last + 1)
            val chars = parseWords(body) ?: throw QrcWordTimingException()
            val text = if (chars.isNotEmpty()) chars.joinToString(separator = "") { it.text } else body
            if (text.isBlank()) return@mapNotNull null
            PipoLyricLine(
                startMs = startMs,
                durationMs = durationMs,
                text = text,
                chars = chars,
                timing = if (chars.isEmpty()) PipoLyricTiming.Line else PipoLyricTiming.Word,
                role = role,
            )
        }.toList()
    }

    private fun parseWords(body: String): List<PipoLyricChar>? {
        val words = WORD_TIMESTAMP.findAll(body).toList()
        if (words.isEmpty()) return emptyList()
        var textStart = 0
        val chars = mutableListOf<PipoLyricChar>()
        for (timestamp in words) {
            val rawText = body.substring(textStart, timestamp.range.first)
            textStart = timestamp.range.last + 1
            val prefix = WORD_PREFIX.find(rawText)
            if (prefix == null && MALFORMED_WORD_PREFIX.containsMatchIn(rawText)) return null
            val text = if (prefix == null) rawText else rawText.substring(prefix.range.last + 1)
            val startMs = timestamp.groupValues[1].toLongOrNull() ?: return null
            val durationMs = timestamp.groupValues[2].toLongOrNull() ?: return null
            if (text.isNotEmpty() && text != "\r") {
                chars += PipoLyricChar(startMs = startMs, durationMs = durationMs, text = text)
            }
        }
        if (body.substring(textStart).isNotEmpty()) return null
        return chars
    }

    private fun optionalSidecar(payload: String, role: PipoLyricRole): List<PipoLyricLine> {
        if (payload.isBlank()) return emptyList()
        return try {
            val decoded = QrcDecoder.decode(payload)
            if (QRC_CONTENT.containsMatchIn(decoded)) parseQrc(decoded, role)
            else LrcParser.parse(decoded).map { it.copy(role = role) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeXmlEntities(value: String): String = XML_ENTITY.replace(value) { entity ->
        val token = entity.groupValues[1]
        when (token) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> numericXmlEntity(token) ?: entity.value
        }
    }

    private fun numericXmlEntity(token: String): String? {
        val radix = if (token.startsWith("#x", ignoreCase = true)) 16 else 10
        val digits = if (radix == 16) token.drop(2) else token.drop(1)
        val codePoint = digits.toIntOrNull(radix) ?: return null
        return if (codePoint in 0..0x10ffff && codePoint !in 0xd800..0xdfff) {
            String(Character.toChars(codePoint))
        } else {
            null
        }
    }

    private fun attachCompanions(
        primary: List<PipoLyricLine>,
        translations: List<PipoLyricLine>,
        romanizations: List<PipoLyricLine>,
    ): List<PipoLyricLine> {
        return primary.map { line ->
            // QQ translation is commonly centisecond LRC, while the original is millisecond QRC.
            // End times differ because LRC ends at the next stamp; only a unique rounded start
            // establishes the association. Do not align translated text by row number.
            val companions = listOf(translations, romanizations).mapNotNull { supplements ->
                val candidate = supplements.singleOrNull {
                    kotlin.math.abs(it.startMs - line.startMs) <= SIDECAR_START_TOLERANCE_MS
                } ?: return@mapNotNull null
                if (primary.count {
                        kotlin.math.abs(it.startMs - candidate.startMs) <= SIDECAR_START_TOLERANCE_MS
                    } != 1
                ) return@mapNotNull null
                if (candidate.chars.isEmpty()) candidate.copy(startMs = line.startMs, durationMs = line.durationMs)
                else candidate
            }
            if (companions.isEmpty()) line else line.copy(companionLines = companions)
        }
    }

    private fun JSONArray.mapNotNull(transform: (JSONObject) -> QqCandidate?): List<QqCandidate> = buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(transform)?.let(::add)
        }
    }

    private fun JSONObject.toCandidateOrNull(): QqCandidate? {
        val id = optLong("id", -1L)
        val title = optString("title").trim()
        val album = optJSONObject("album")?.optString("name").orEmpty().trim()
        val durationMs = optLong("interval", 0L) * 1000L
        val artists = optJSONArray("singer").toNames()
        return if (id > 0L && title.isNotEmpty() && artists.isNotEmpty() && durationMs > 0L) {
            QqCandidate(id, title, artists, album, durationMs)
        } else {
            null
        }
    }

    private fun JSONArray?.toNames(): List<String> = buildList {
        this@toNames ?: return@buildList
        for (index in 0 until length()) {
            optJSONObject(index)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        }
    }

    private fun encodeBase64(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private data class QqSession(
        val uid: String,
        val sid: String,
        val userIp: String,
        val expiresAtMs: Long,
    )

    private data class QqCandidate(
        val id: Long,
        val title: String,
        val artists: List<String>,
        val album: String,
        val durationMs: Long,
    )

    private class QqApiException(topCode: Int, requestCode: Int, detail: String) : IOException("QQ lyrics API rejected the request") {
        val isSessionFailure = detail.contains("session", ignoreCase = true) ||
            detail.contains("sid", ignoreCase = true) ||
            topCode == SESSION_FAILURE_CODE || requestCode == SESSION_FAILURE_CODE
    }

    private class QrcWordTimingException : IOException("Malformed QQ QRC word timing")

    private companion object {
        const val SOURCE = "qq"
        const val ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val USER_AGENT = "okhttp/3.14.9"
        const val SEARCH_PAGE_SIZE = 20
        const val MAX_CANDIDATES = 2
        const val SESSION_TTL_MS = 20L * 60 * 1000
        const val SESSION_FAILURE_CODE = 1000
        const val SIDECAR_START_TOLERANCE_MS = 10L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val QRC_CONTENT = Regex("""<Lyric_1\s+[^>]*\bLyricContent="(.*?)"\s*/>""", setOf(RegexOption.DOT_MATCHES_ALL))
        val LINE_HEADER = Regex("""^\[(\d+),(\d+)]""")
        val WORD_TIMESTAMP = Regex("""\((\d+),(\d+)\)""")
        val WORD_PREFIX = Regex("""^\[\d+,\d+]""")
        val MALFORMED_WORD_PREFIX = Regex("""^\[\d+(?:,|])""")
        val XML_ENTITY = Regex("""&(#(?:[xX][0-9a-fA-F]+|\d+)|amp|lt|gt|quot|apos);""")
        val sessionMutex = Mutex()

        @Volatile
        var cachedSession: QqSession? = null
    }
}
