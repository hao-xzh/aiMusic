package app.pipo.nativeapp.playback

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoRepository
import kotlinx.coroutines.withTimeoutOrNull

internal class PlaybackUrlResolver(
    private val repository: PipoRepository,
    private val streamLevelFallbacks: List<String>,
    private val streamUrlTimeoutMs: Long,
) {
    suspend fun fetchPlayableUrl(id: Long): String? {
        var attemptedLevels = 0
        for (level in streamLevelFallbacks) {
            attemptedLevels += 1
            val url = withTimeoutOrNull(streamUrlTimeoutMs) {
                runCatching { repository.songUrls(listOf(id), level) }
                    .getOrNull()
                    ?.firstOrNull { it.id == id }
                    ?.url
                    ?.takeIf { it.isNotBlank() }
            }
            if (!url.isNullOrBlank()) return url
        }
        DiagnosticsLogStore.record(
            area = "playback",
            event = "playable_url_empty",
            fields = mapOf(
                "neteaseId" to id,
                "attemptedLevels" to attemptedLevels,
                "timeoutMs" to streamUrlTimeoutMs,
            ),
        )
        return null
    }

    suspend fun resolveSinglePlayable(track: NativeTrack): NativeTrack? {
        if (track.streamUrl.isNotBlank()) return track
        val id = track.neteaseId ?: return null
        val url = fetchPlayableUrl(id) ?: return null
        return track.copy(streamUrl = url)
    }

    suspend fun resolveFirstPlayable(
        candidates: List<NativeTrack>,
        scanLimit: Int,
    ): NativeTrack? {
        candidates.take(scanLimit).forEach { track ->
            resolveSinglePlayable(track)?.let { return it }
        }
        return null
    }

    suspend fun resolvePlayableQueue(queue: List<NativeTrack>): List<NativeTrack> {
        val missingIds = queue.mapNotNull { track ->
            if (track.streamUrl.isBlank()) track.neteaseId else null
        }.distinct()
        if (missingIds.isEmpty()) return queue

        val urls = HashMap<Long, String>()
        var unresolved = missingIds
        for (level in streamLevelFallbacks) {
            for (chunk in unresolved.chunked(50)) {
                withTimeoutOrNull(streamUrlTimeoutMs) {
                    runCatching { repository.songUrls(chunk, level) }.getOrNull()
                }?.forEach { u ->
                    u.url?.takeIf { it.isNotBlank() }?.let { urls[u.id] = it }
                }
            }
            unresolved = unresolved.filter { it !in urls }
            if (unresolved.isEmpty()) break
        }
        if (unresolved.isNotEmpty()) {
            DiagnosticsLogStore.record(
                area = "playback",
                event = "playable_queue_partial",
                fields = mapOf(
                    "missingCount" to missingIds.size,
                    "resolvedCount" to urls.size,
                    "unresolvedCount" to unresolved.size,
                    "attemptedLevels" to streamLevelFallbacks.size,
                    "timeoutMs" to streamUrlTimeoutMs,
                ),
            )
        }

        return queue.map { track ->
            val id = track.neteaseId
            val resolved = if (id != null) urls[id] else null
            if (track.streamUrl.isBlank() && !resolved.isNullOrBlank()) {
                track.copy(streamUrl = resolved)
            } else {
                track
            }
        }
    }
}
