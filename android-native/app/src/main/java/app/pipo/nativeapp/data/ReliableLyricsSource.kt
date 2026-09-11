package app.pipo.nativeapp.data

import android.content.Context
import app.pipo.nativeapp.DiagnosticsLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Chooses one complete lyric document before publishing it to the player. */
internal class ReliableLyricsSource(context: Context) {
    private val amll = AmllLyricsSource(context)
    private val kugou = KugouLyricsSource()
    private val qq = QqLyricsSource()
    private val cacheDirectory = File(context.cacheDir, "word-lyrics-v1")
    // Bound synchronization state while coalescing repeated requests for the same recording.
    private val requestLocks = Array(16) { Mutex() }

    suspend fun lyricsForTrack(
        trackId: String,
        track: NativeTrack?,
        additionalCandidates: List<OnlineLyrics> = emptyList(),
    ): OnlineLyrics? =
        withContext(Dispatchers.IO) {
            val lock = requestLocks[(trackId.hashCode() and Int.MAX_VALUE) % requestLocks.size]
            lock.withLock { resolve(trackId, track, additionalCandidates) }
        }

    private suspend fun resolve(
        trackId: String,
        track: NativeTrack?,
        additionalCandidates: List<OnlineLyrics>,
    ): OnlineLyrics? {
        val amllId = track?.neteaseId?.toString() ?: trackId
        val amllLines = amll.lyricsForTrack(amllId)?.let(LyricCredits::stripLeading)
            ?.takeIf { OnlineLyricSupport.usableLyricsDocument(it, track?.durationMs ?: 0L) }
        val sourceCandidates = additionalCandidates.filter {
            OnlineLyricSupport.usableLyricsDocument(it.lines, track?.durationMs ?: 0L)
        }
        val amllCandidate = amllLines?.let { OnlineLyrics("amll", amllId, it) }

        // Missing recording metadata is a reason to keep the ID-based fallback, never to guess.
        if (track == null || track.title.isBlank() || track.artist.isBlank() || track.durationMs <= 0L) {
            record(trackId, "external", "skip_missing_metadata")
            return selectLyrics(sourceCandidates + listOfNotNull(amllCandidate))?.let { selected ->
                val completed = completeAmllAlignment(selected, amllCandidate)
                record(
                    trackId, completed.lyrics.source, "selected", completed.lyrics.lines,
                    completed.lyrics.sourceTrackId, completed.alignmentAddedLineCount,
                )
                completed.lyrics
            }
        }
        val cache = readCache(track)
        val now = System.currentTimeMillis()
        val cacheCandidate = cache?.takeIf {
            now - it.savedAt in 0 until STALE_CACHE_TTL_MS
        }?.lyrics

        val attempts = coroutineScope {
            // Compare complete documents; the first network response need not have finer timing.
            val qqRequest = async { attempt(track, "qq", 8_000L) { qq.lyricsForTrack(track) } }
            val kugouResult = attempt(track, "kugou", 6_000L) { kugou.lyricsForTrack(track) }
            kugouResult to qqRequest.await()
        }
        // A cache is a reusable candidate, never an early platform-priority decision.
        val selected = selectLyrics(
            listOfNotNull(amllCandidate) + sourceCandidates +
                listOfNotNull(attempts.first.lyrics, attempts.second.lyrics) + listOfNotNull(cacheCandidate),
        )
        if (selected != null) {
            val completed = completeAmllAlignment(selected, amllCandidate)
            if (selected !== cacheCandidate || completed.alignmentAddedLineCount > 0) {
                writeCache(track, completed.lyrics)
            }
            val outcome = if (selected === cacheCandidate && completed.alignmentAddedLineCount == 0) {
                "selected_cached_candidate"
            } else "selected"
            record(
                trackId, completed.lyrics.source, outcome, completed.lyrics.lines,
                completed.lyrics.sourceTrackId, completed.alignmentAddedLineCount,
            )
            return completed.lyrics
        }
        return null
    }

    private fun selectLyrics(candidates: List<OnlineLyrics>): OnlineLyrics? {
        val measured = candidates.map { candidate ->
            CandidateQuality(
                candidate,
                OnlineLyricSupport.wordQuality(candidate.lines),
                OnlineLyricSupport.documentSemantics(candidate.lines),
            )
        }
        val mostTextUnits = measured.maxOfOrNull { it.wordQuality.textUnits } ?: return null
        // A much shorter document must not win just because its remaining words are finely timed.
        // This compares source timestamp spans, not inferred audio alignment or token count.
        return measured.filter { it.wordQuality.textUnits >= mostTextUnits * 0.9 }
            .fold<CandidateQuality, CandidateQuality?>(null) { selected, candidate ->
                if (selected == null || compareCandidateQuality(candidate, selected) > 0) candidate else selected
            }?.lyrics
    }

    private fun compareCandidateQuality(left: CandidateQuality, right: CandidateQuality): Int =
        compareTimingGranularity(left.wordQuality, right.wordQuality).takeIf { it != 0 }
            ?: left.wordQuality.textUnits.compareTo(right.wordQuality.textUnits).takeIf { it != 0 }
            ?: compareSemantics(left.semantics, right.semantics)

    private fun compareTimingGranularity(left: LyricWordQuality, right: LyricWordQuality): Int = when {
        left.fineTimingRatio != right.fineTimingRatio -> left.fineTimingRatio.compareTo(right.fineTimingRatio)
        left.timedSegmentRatio != right.timedSegmentRatio -> left.timedSegmentRatio.compareTo(right.timedSegmentRatio)
        left.timedSegments != right.timedSegments -> left.timedSegments.compareTo(right.timedSegments)
        else -> 0
    }

    private fun compareSemantics(left: LyricDocumentSemantics, right: LyricDocumentSemantics): Int = when {
        left.endAlignedPrimaryLines != right.endAlignedPrimaryLines ->
            left.endAlignedPrimaryLines.compareTo(right.endAlignedPrimaryLines)
        left.companionLines != right.companionLines -> left.companionLines.compareTo(right.companionLines)
        else -> left.translationLines.compareTo(right.translationLines)
    }

    private fun completeAmllAlignment(selected: OnlineLyrics, amllCandidate: OnlineLyrics?): CompletedLyrics {
        if (amllCandidate == null || selected.source == "amll" ||
            selected.lines.any { it.role == PipoLyricRole.Primary && it.alignment == PipoLyricAlignment.End } ||
            compareTimingGranularity(
                OnlineLyricSupport.wordQuality(selected.lines),
                OnlineLyricSupport.wordQuality(amllCandidate.lines),
            ) <= 0
        ) return CompletedLyrics(selected)
        val completedLines = OnlineLyricSupport.withMissingAlignments(selected.lines, amllCandidate.lines)
        val added = completedLines.count { line ->
            line.role == PipoLyricRole.Primary && line.alignment == PipoLyricAlignment.End
        }
        return CompletedLyrics(selected.copy(lines = completedLines), added)
    }

    private suspend fun attempt(
        track: NativeTrack,
        source: String,
        timeoutMs: Long,
        fetch: suspend () -> OnlineLyrics?,
    ): Attempt {
        return try {
            val result = withTimeoutOrNull(timeoutMs) {
                val fetched = fetch()
                val stripped = fetched?.copy(lines = LyricCredits.stripLeading(fetched.lines))
                val accepted = stripped?.takeIf {
                    OnlineLyricSupport.usableLyricsDocument(it.lines, track.durationMs)
                }
                record(track.id, source, if (accepted != null) "matched" else "no_valid_lyrics",
                    accepted?.lines.orEmpty(), accepted?.sourceTrackId)
                Attempt(accepted)
            }
            result ?: Attempt(null).also { record(track.id, source, "timeout") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            record(track.id, source, "failed_${e::class.java.simpleName}")
            Attempt(null)
        }
    }

    private fun cacheFile(track: NativeTrack): File? {
        if (track.id.length !in 1..128) return null
        return File(cacheDirectory, URLEncoder.encode(track.id, "UTF-8") + ".json")
    }

    private fun readCache(track: NativeTrack): CachedLyrics? = runCatching {
        val file = cacheFile(track) ?: return@runCatching null
        if (file.length() !in 1..MAX_CACHE_BYTES) return@runCatching null
        val data = JSONObject(file.readText(Charsets.UTF_8))
        if (data.optInt("version") != CACHE_VERSION ||
            data.optString("trackId") != track.id || data.optString("title") != track.title ||
            data.optString("artist") != track.artist || data.optString("album") != track.album ||
            kotlin.math.abs(data.optLong("durationMs") - track.durationMs) > 1_000L
        ) return@runCatching null
        val savedAt = data.getLong("savedAt")
        val source = data.getString("source")
        if (source !in setOf("amll", "kugou", "netease", "netease_cloud", "qq")) return@runCatching null
        val lines = decodeLines(data.getJSONArray("lines"))
        if (!OnlineLyricSupport.usableLyricsDocument(lines, track.durationMs)) return@runCatching null
        CachedLyrics(OnlineLyrics(source, data.getString("sourceTrackId"), lines), savedAt)
    }.getOrNull()

    private fun writeCache(track: NativeTrack, lyrics: OnlineLyrics) {
        runCatching {
            val target = cacheFile(track) ?: return
            cacheDirectory.mkdirs()
            val data = JSONObject().apply {
                put("version", CACHE_VERSION)
                put("trackId", track.id)
                put("title", track.title)
                put("artist", track.artist)
                put("album", track.album)
                put("durationMs", track.durationMs)
                put("savedAt", System.currentTimeMillis())
                put("source", lyrics.source)
                put("sourceTrackId", lyrics.sourceTrackId)
                put("lines", encodeLines(lyrics.lines))
            }
            val temporary = File.createTempFile("lyrics-", ".tmp", cacheDirectory)
            try {
                temporary.writeText(data.toString(), Charsets.UTF_8)
                temporary.renameTo(target)
            } finally {
                temporary.delete()
            }
            cacheDirectory.listFiles()?.filter { it.extension == "json" }
                ?.sortedByDescending { it.lastModified() }?.drop(MAX_CACHE_FILES)?.forEach { it.delete() }
        }
    }

    private fun encodeLines(lines: List<PipoLyricLine>): JSONArray = JSONArray().apply {
        lines.forEach { line ->
            put(JSONObject().apply {
                put("start", line.startMs)
                put("duration", line.durationMs)
                put("text", line.text)
                put("timing", line.timing.name)
                put("role", line.role.name)
                put("alignment", line.alignment.name)
                put("companions", encodeLines(line.companionLines))
                put("chars", JSONArray().apply {
                    line.chars.forEach { char ->
                        put(JSONObject().apply {
                            put("start", char.startMs)
                            put("duration", char.durationMs)
                            put("text", char.text)
                            put("parts", JSONArray().apply {
                                char.timingParts.forEach { part ->
                                    put(JSONObject().put("start", part.startMs)
                                        .put("duration", part.durationMs).put("text", part.text))
                                }
                            })
                        })
                    }
                })
            })
        }
    }

    private fun decodeLines(array: JSONArray, depth: Int = 0): List<PipoLyricLine> {
        require(depth <= 4 && array.length() <= 2_000)
        return (0 until array.length()).map { index ->
            val line = array.getJSONObject(index)
            val chars = line.getJSONArray("chars")
            require(chars.length() <= 4_000)
            PipoLyricLine(
                startMs = line.getLong("start"),
                durationMs = line.getLong("duration"),
                text = line.getString("text"),
                timing = PipoLyricTiming.valueOf(line.getString("timing")),
                role = PipoLyricRole.valueOf(line.getString("role")),
                alignment = PipoLyricAlignment.valueOf(line.getString("alignment")),
                companionLines = decodeLines(line.getJSONArray("companions"), depth + 1),
                chars = (0 until chars.length()).map { charIndex ->
                    val char = chars.getJSONObject(charIndex)
                    val parts = char.getJSONArray("parts")
                    require(parts.length() <= 4_000)
                    PipoLyricChar(
                        startMs = char.getLong("start"),
                        durationMs = char.getLong("duration"),
                        text = char.getString("text"),
                        timingParts = (0 until parts.length()).map { partIndex ->
                            val part = parts.getJSONObject(partIndex)
                            PipoLyricTimingPart(part.getLong("start"), part.getLong("duration"), part.getString("text"))
                        },
                    )
                },
            )
        }
    }

    private fun record(
        trackId: String,
        source: String,
        outcome: String,
        lines: List<PipoLyricLine> = emptyList(),
        sourceTrackId: String? = null,
        alignmentAddedLineCount: Int = 0,
    ) {
        val quality = OnlineLyricSupport.wordQuality(lines)
        DiagnosticsLogStore.record(
            area = "lyrics",
            event = "source_resolve",
            fields = mapOf(
                "trackId" to trackId, "source" to source, "outcome" to outcome,
                "sourceTrackId" to sourceTrackId, "lineCount" to lines.size,
                "wordCount" to lines.sumOf { it.chars.size },
                "textUnits" to quality.textUnits, "timedSegments" to quality.timedSegments,
                "coarseUnits" to quality.coarseUnits, "untimedUnits" to quality.untimedUnits,
                "fineTimingPermille" to (quality.fineTimingRatio * 1_000).toInt(),
                "timedSegmentPermille" to (quality.timedSegmentRatio * 1_000).toInt(),
                "alignmentAddedLineCount" to alignmentAddedLineCount,
            ),
        )
    }

    private data class CandidateQuality(
        val lyrics: OnlineLyrics,
        val wordQuality: LyricWordQuality,
        val semantics: LyricDocumentSemantics,
    )
    private data class CompletedLyrics(val lyrics: OnlineLyrics, val alignmentAddedLineCount: Int = 0)
    private data class Attempt(val lyrics: OnlineLyrics?)
    private data class CachedLyrics(val lyrics: OnlineLyrics?, val savedAt: Long)

    private companion object {
        const val CACHE_VERSION = 5
        const val STALE_CACHE_TTL_MS = 30 * 24 * 60 * 60 * 1000L
        const val MAX_CACHE_BYTES = 2 * 1024 * 1024L
        const val MAX_CACHE_FILES = 128
    }
}
