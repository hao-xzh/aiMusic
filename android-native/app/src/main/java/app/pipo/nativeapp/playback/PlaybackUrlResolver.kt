package app.pipo.nativeapp.playback

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoRepository
import kotlinx.coroutines.withTimeoutOrNull

internal class PlaybackUrlResolver(
    private val repository: PipoRepository,
    private val streamLevelFallbacks: List<String>,
    private val streamUrlTimeoutMs: Long,
) {
    suspend fun fetchPlayableUrl(id: Long): String? {
        for (level in streamLevelFallbacks) {
            val url = withTimeoutOrNull(streamUrlTimeoutMs) {
                runCatching { repository.songUrls(listOf(id), level) }
                    .getOrNull()
                    ?.firstOrNull { it.id == id }
                    ?.url
                    ?.takeIf { it.isNotBlank() }
            }
            if (!url.isNullOrBlank()) return url
        }
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
