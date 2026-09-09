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

    suspend fun lyricsForTrack(trackId: String, track: NativeTrack?): List<PipoLyricLine>? =
        withContext(Dispatchers.IO) {
            val lock = requestLocks[(trackId.hashCode() and Int.MAX_VALUE) % requestLocks.size]
            lock.withLock { resolve(trackId, track) }
        }

    private suspend fun resolve(trackId: String, track: NativeTrack?): List<PipoLyricLine>? {
        val amllId = track?.neteaseId?.toString() ?: trackId
        val amllLines = amll.lyricsForTrack(amllId)?.let(LyricCredits::stripLeading)
            ?.takeIf { OnlineLyricSupport.validTimings(it, track?.durationMs ?: 0L) }
        if (amllLines != null && OnlineLyricSupport.usableWordLyrics(amllLines, track?.durationMs ?: 0L)) {
            record(trackId, "amll", "selected", amllLines)
            return amllLines
        }

        // Missing recording metadata is a reason to keep the ID-based fallback, never to guess.
        if (track == null || track.title.isBlank() || track.artist.isBlank() || track.durationMs <= 0L) {
            record(trackId, "external", "skip_missing_metadata")
            return amllLines?.takeIf { it.isNotEmpty() }
        }
        val cache = readCache(track)
        val now = System.currentTimeMillis()
        if (cache != null && now - cache.savedAt in 0 until cache.ttlMs) {
            cache.lyrics?.let {
                record(trackId, it.source, "selected_cache", it.lines, it.sourceTrackId)
                return it.lines
            }
            record(trackId, "external", "cached_no_match")
            return amllLines?.takeIf { it.isNotEmpty() }
        }

        val attempts = coroutineScope {
            // Compare complete documents; the first network response need not have finer timing.
            val qqRequest = async { attempt(track, "qq", 8_000L) { qq.lyricsForTrack(track) } }
            val kugouResult = attempt(track, "kugou", 6_000L) { kugou.lyricsForTrack(track) }
            kugouResult to qqRequest.await()
        }
        val selected = selectWordLyrics(listOfNotNull(attempts.first.lyrics, attempts.second.lyrics))
        if (selected != null) {
            writeCache(track, selected)
            record(trackId, selected.source, "selected", selected.lines, selected.sourceTrackId)
            return selected.lines
        }
        if (cache?.lyrics != null && now - cache.savedAt in 0 until STALE_CACHE_TTL_MS) {
            record(trackId, cache.lyrics.source, "selected_stale_cache", cache.lyrics.lines, cache.lyrics.sourceTrackId)
            return cache.lyrics.lines
        }
        // Timeouts and server errors must not become persistent "no lyrics" cache entries.
        if (attempts.first.completed && attempts.second.completed) writeCache(track, null)
        return amllLines?.takeIf { it.isNotEmpty() }
    }

    private fun selectWordLyrics(candidates: List<OnlineLyrics>): OnlineLyrics? {
        val measured = candidates.map { it to OnlineLyricSupport.wordQuality(it.lines) }
        val mostTextUnits = measured.maxOfOrNull { it.second.textUnits } ?: return null
        // A much shorter document must not win just because its remaining words are finely timed.
        // This is a conservative content/granularity comparison, not an audio-accuracy score.
        return measured.filter { it.second.textUnits >= mostTextUnits * 0.9 }
            .maxByOrNull { it.second.fineTimingRatio }?.first
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
                    OnlineLyricSupport.usableWordLyrics(it.lines, track.durationMs)
                }
                record(track.id, source, if (accepted != null) "matched" else "no_valid_word_lyrics",
                    accepted?.lines.orEmpty(), accepted?.sourceTrackId)
                Attempt(accepted, completed = true)
            }
            result ?: Attempt(null, completed = false).also { record(track.id, source, "timeout") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            record(track.id, source, "failed_${e::class.java.simpleName}")
            Attempt(null, completed = false)
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
        if (data.optBoolean("missing")) return@runCatching CachedLyrics(null, savedAt, MISS_TTL_MS)
        val source = data.getString("source")
        if (source !in setOf("kugou", "qq")) return@runCatching null
        val lines = decodeLines(data.getJSONArray("lines"))
        if (!OnlineLyricSupport.usableWordLyrics(lines, track.durationMs)) return@runCatching null
        CachedLyrics(OnlineLyrics(source, data.getString("sourceTrackId"), lines), savedAt, CACHE_TTL_MS)
    }.getOrNull()

    private fun writeCache(track: NativeTrack, lyrics: OnlineLyrics?) {
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
                put("missing", lyrics == null)
                if (lyrics != null) {
                    put("source", lyrics.source)
                    put("sourceTrackId", lyrics.sourceTrackId)
                    put("lines", encodeLines(lyrics.lines))
                }
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
            ),
        )
    }

    private data class Attempt(val lyrics: OnlineLyrics?, val completed: Boolean)
    private data class CachedLyrics(val lyrics: OnlineLyrics?, val savedAt: Long, val ttlMs: Long)

    private companion object {
        const val CACHE_VERSION = 3
        const val CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        const val STALE_CACHE_TTL_MS = 30 * 24 * 60 * 60 * 1000L
        const val MISS_TTL_MS = 3 * 60 * 60 * 1000L
        const val MAX_CACHE_BYTES = 2 * 1024 * 1024L
        const val MAX_CACHE_FILES = 128
    }
}
