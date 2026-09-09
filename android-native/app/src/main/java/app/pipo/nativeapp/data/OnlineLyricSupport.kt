package app.pipo.nativeapp.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

internal data class OnlineLyrics(
    val source: String,
    val sourceTrackId: String,
    val lines: List<PipoLyricLine>,
)

internal class LyricHttpException(val statusCode: Int) : IOException("Lyrics HTTP $statusCode")

internal data class LyricWordQuality(
    val textUnits: Int,
    val timedSegments: Int,
    val coarseUnits: Int,
    val untimedUnits: Int,
) {
    // Measures timestamp granularity only. Audio alignment cannot be inferred from token counts.
    val fineTimingRatio: Double
        get() = if (textUnits == 0) 0.0 else
            (textUnits - coarseUnits - untimedUnits).coerceAtLeast(0).toDouble() / textUnits
}

/** Shared transport and conservative recording matching for the external lyric sources. */
internal object OnlineLyricSupport {
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val MAX_DURATION_DIFFERENCE_MS = 2_000L
    private val client by lazy {
        PipoHttp.client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    suspend fun requestJson(request: Request): JSONObject = JSONObject(requestText(request))

    suspend fun requestText(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw LyricHttpException(it.code)
                        val body = it.body ?: throw IOException("Empty lyrics response")
                        body.byteStream().use(::readBounded).toString(Charsets.UTF_8)
                    }
                }
                if (continuation.isActive) {
                    result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
            }
        })
    }

    fun inflate(bytes: ByteArray): String =
        InflaterInputStream(ByteArrayInputStream(bytes)).use(::readBounded).toString(Charsets.UTF_8)

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            if (output.size() + size > MAX_RESPONSE_BYTES) throw IOException("Lyrics response too large")
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }

    /** A title match alone is insufficient: duration, vocalist and version must agree. */
    fun matchScore(
        track: NativeTrack,
        title: String,
        artists: List<String>,
        album: String,
        durationMs: Long,
    ): Int? {
        if (track.durationMs <= 0L || durationMs <= 0L) return null
        val durationDifference = abs(track.durationMs - durationMs)
        if (durationDifference > MAX_DURATION_DIFFERENCE_MS) return null
        val requestedTitle = normalize(coreTitle(track.title))
        if (requestedTitle.isBlank() || requestedTitle != normalize(coreTitle(title))) return null
        val versions = versionTags(track.title, track.album)
        if (versions != versionTags(title, album)) return null
        if (versions.isNotEmpty() &&
            (track.album.isBlank() || album.isBlank() || normalize(track.album) != normalize(album))
        ) return null
        val requestedArtists = track.artist.split(ARTIST_SEPARATOR).filter { it.isNotBlank() }
        val candidateArtists = artists.flatMap { it.split(ARTIST_SEPARATOR) }.filter { it.isNotBlank() }
        if (requestedArtists.isEmpty() || candidateArtists.isEmpty()) return null
        if (requestedArtists.any { requested -> candidateArtists.none { sameArtist(requested, it) } }) return null
        if (candidateArtists.any { candidate -> requestedArtists.none { sameArtist(it, candidate) } }) return null
        val albumBonus = if (track.album.isNotBlank() && normalize(track.album) == normalize(album)) 20 else 0
        return 100 + albumBonus - (durationDifference / 100L).toInt()
    }

    /** Validate source timestamps; never manufacture syllable timing from a line duration. */
    fun usableWordLyrics(lines: List<PipoLyricLine>, durationMs: Long): Boolean {
        val primary = lines.filter { it.role == PipoLyricRole.Primary && it.text.isNotBlank() }
        if (primary.isEmpty()) return false
        if (primary.count { it.chars.isNotEmpty() } * 5 < primary.size * 4) return false
        if (primary.sumOf { it.chars.count { char -> char.text.isNotBlank() } } < 2) return false
        return validTimings(lines, durationMs)
    }

    fun wordQuality(lines: List<PipoLyricLine>): LyricWordQuality {
        var textUnits = 0
        var timedSegments = 0
        var coarseUnits = 0
        var untimedUnits = 0
        for (line in lines) {
            if (line.role != PipoLyricRole.Primary) continue
            val units = pronunciationUnits(line.text)
            textUnits += units
            if (line.chars.isEmpty()) {
                untimedUnits += units
                continue
            }
            // Repeated timestamps do not become finer data just because text was split into chars.
            val segments = line.chars.flatMap { it.timingPartsOrSelf() }
                .groupBy { it.startMs to it.durationMs }
            var lineCoarseUnits = 0
            for (parts in segments.values) {
                val segmentUnits = pronunciationUnits(parts.joinToString("") { it.text })
                if (segmentUnits > 0) timedSegments++
                lineCoarseUnits += (segmentUnits - 1).coerceAtLeast(0)
            }
            coarseUnits += lineCoarseUnits.coerceAtMost(units)
        }
        return LyricWordQuality(textUnits, timedSegments, coarseUnits, untimedUnits)
    }

    fun hasTranslation(line: PipoLyricLine): Boolean =
        line.companionLines.any { it.role == PipoLyricRole.Translation && it.text.isNotBlank() }

    /** Reuse a translation only when the original sentence and its recording position agree. */
    fun withMissingTranslations(
        primary: List<PipoLyricLine>,
        donor: List<PipoLyricLine>,
    ): List<PipoLyricLine> {
        val translatedByText = donor.filter { it.role == PipoLyricRole.Primary && hasTranslation(it) }
            .groupBy { normalize(it.text) }
        if (translatedByText.isEmpty()) return primary
        return primary.map { line ->
            if (line.role != PipoLyricRole.Primary || hasTranslation(line)) return@map line
            val textKey = normalize(line.text)
            if (textKey.isEmpty()) return@map line
            val candidates = translatedByText[textKey].orEmpty()
                .filter { abs(it.startMs - line.startMs) <= MAX_DURATION_DIFFERENCE_MS }
            val nearestDistance = candidates.minOfOrNull { abs(it.startMs - line.startMs) } ?: return@map line
            val nearest = candidates.filter { abs(it.startMs - line.startMs) == nearestDistance }
                .singleOrNull() ?: return@map line
            val translations = nearest.companionLines.filter {
                it.role == PipoLyricRole.Translation && it.text.isNotBlank()
            }.map { translation ->
                translation.copy(
                    startMs = line.startMs, durationMs = line.durationMs,
                    chars = emptyList(), timing = PipoLyricTiming.Line,
                    companionLines = emptyList(),
                )
            }
            // Only the companion changes. Original text, word timestamps and animation stay intact.
            line.copy(companionLines = line.companionLines + translations)
        }
    }

    private fun pronunciationUnits(text: String): Int {
        var units = 0
        var inWord = false
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            when {
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN -> {
                    units++
                    inWord = false
                }
                Character.isLetterOrDigit(codePoint) -> {
                    if (!inWord) units++
                    inWord = true
                }
                codePoint == '\''.code || codePoint == '’'.code -> Unit
                else -> inWord = false
            }
        }
        return units
    }

    fun validTimings(lines: List<PipoLyricLine>, durationMs: Long): Boolean {
        if (lines.isEmpty() || lines.size > 2_000) return false
        fun valid(line: PipoLyricLine): Boolean {
            if (line.startMs !in 0..MAX_TRACK_TIME_MS || line.durationMs !in 1..MAX_TRACK_TIME_MS) return false
            if (durationMs > 0 && line.startMs + line.durationMs > durationMs + MAX_DURATION_DIFFERENCE_MS) return false
            if (line.chars.zipWithNext().any { (a, b) -> a.startMs > b.startMs }) return false
            if (line.chars.any {
                    it.startMs < line.startMs || it.durationMs < 0 ||
                        (it.durationMs == 0L && it.text.any(Char::isLetterOrDigit)) ||
                        it.startMs + it.durationMs > line.startMs + line.durationMs + 100L
                }) return false
            if (line.chars.isNotEmpty() &&
                line.chars.joinToString("") { it.text }.filterNot(Char::isWhitespace) != line.text.filterNot(Char::isWhitespace)
            ) return false
            if (line.chars.any { char ->
                    val parts = char.timingParts
                    parts.isNotEmpty() && (
                        parts.joinToString("") { it.text } != char.text ||
                            parts.zipWithNext().any { (a, b) -> a.startMs > b.startMs } ||
                            parts.any { part ->
                                part.startMs < char.startMs || part.durationMs < 0L ||
                                    (part.durationMs == 0L && part.text.any(Char::isLetterOrDigit)) ||
                                    part.startMs + part.durationMs > char.startMs + char.durationMs + 100L
                            }
                        )
                }) return false
            return line.companionLines.all(::valid)
        }
        return lines.all(::valid)
    }

    private fun sameArtist(left: String, right: String): Boolean {
        if (normalize(left) == normalize(right)) return true
        // Chinese artist names sometimes carry a Latin alias, e.g. G.E.M.邓紫棋.
        val leftHan = left.filter { it in '\u3400'..'\u9fff' }
        val rightHan = right.filter { it in '\u3400'..'\u9fff' }
        return leftHan.length >= 2 && leftHan == rightHan
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }

    private fun coreTitle(value: String): String = BRACKETS.replace(value) { match ->
        val annotation = match.value
        if (VERSION_PATTERNS.any { it.second.containsMatchIn(annotation) } ||
            CREDIT_ANNOTATION.containsMatchIn(annotation)
        ) "" else annotation
    }

    private fun versionTags(title: String, album: String): Set<String> {
        val description = "$title $album"
        return VERSION_PATTERNS.filter { it.second.containsMatchIn(description) }.map { it.first }.toSet()
    }

    private val ARTIST_SEPARATOR = Regex("(?:\\s*[/、&,，;；]\\s*|\\s+(?i:feat\\.?|ft\\.?)\\s+)")
    private const val MAX_TRACK_TIME_MS = 24 * 60 * 60 * 1000L
    private val BRACKETS = Regex("[（(\\[][^）)\\]]*[）)\\]]")
    private val CREDIT_ANNOTATION = Regex("主题曲|主題曲|片尾曲|片头曲|片頭曲|插曲|电影|電影|电视剧|電視劇", RegexOption.IGNORE_CASE)
    private val VERSION_PATTERNS = listOf(
        "live" to Regex("\\blive\\b|\\bconcert\\b|现场|現場|演唱会|演唱會", RegexOption.IGNORE_CASE),
        "remix" to Regex("\\bremix\\b|混音|\\bDJ\\b", RegexOption.IGNORE_CASE),
        "instrumental" to Regex("\\binstrumental\\b|\\bkaraoke\\b|伴奏|纯音乐|純音樂", RegexOption.IGNORE_CASE),
        "acoustic" to Regex("\\bacoustic\\b|不插电|不插電", RegexOption.IGNORE_CASE),
        "demo" to Regex("\\bdemo\\b", RegexOption.IGNORE_CASE),
        "remaster" to Regex("\\bremaster(?:ed)?\\b|重制|重製", RegexOption.IGNORE_CASE),
        "rerecording" to Regex("重录|重錄|重生版|重新演绎|重新演繹|Taylor.s Version", RegexOption.IGNORE_CASE),
        "spedup" to Regex("sped.?up|加速版|nightcore", RegexOption.IGNORE_CASE),
        "slowed" to Regex("\\bslowed\\b|慢速版|降速版", RegexOption.IGNORE_CASE),
    )
}
