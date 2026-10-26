package app.pipo.nativeapp.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Private cloud tracks are account-scoped candidates, not a public music provider. */
internal class NetEaseCloudLibrary(private val repository: PipoRepository) {
    private val refreshMutex = Mutex()
    private var refreshedOwner: Long? = null
    private var refreshedAt = 0L

    suspend fun tracks(): List<NativeTrack> {
        val owner = repository.account.value?.userId ?: return emptyList()
        return withTimeout(30_000L) {
            refreshMutex.withLock {
                check(repository.account.value?.userId == owner) { "网易云账号已切换" }
                val cached = repository.cachedTracksFor(CLOUD_DISK_PLAYLIST_ID)
                if (refreshedOwner == owner && System.currentTimeMillis() - refreshedAt < 300_000L && cached != null) {
                    return@withLock cached
                }
                // Refresh once on first use as old snapshots may contain only catalog IDs.
                val tracks = repository.cloudDiskTracksForBrowse(forceRefresh = true)
                check(repository.account.value?.userId == owner) { "网易云账号已切换" }
                refreshedOwner = owner
                refreshedAt = System.currentTimeMillis()
                tracks
            }
        }
    }

    suspend fun search(query: String): List<NativeTrack> {
        val terms = RecordingMatcher.normalize(query).split(Regex("\\s+"))
            .map(::searchKey).filter(String::isNotBlank)
        if (terms.isEmpty()) return emptyList()
        return tracks().filter { track ->
            val text = searchKey("${track.title} ${track.artist} ${track.album}")
            terms.all(text::contains)
        }
    }

    suspend fun playbackCandidates(track: NativeTrack): List<NativeTrack> = tracks().filter { candidate ->
        // Metadata matching is used only for this playback fallback, never saved as a recording link.
        val source = track.copy(title = cleanTitle(track.title))
        val target = candidate.copy(title = cleanTitle(candidate.title))
        source.artist.isNotBlank() && source.artist != "Unknown artist" &&
            target.artist.isNotBlank() && target.artist != "Unknown artist" &&
            RecordingMatcher.compare(source, target) in setOf(
                RecordingMatcher.Match.SAME_SOURCE, RecordingMatcher.Match.CONFIRMED, RecordingMatcher.Match.POSSIBLE,
            ) && SearchVersionPolicy.accepts(target, "${source.title} (${source.album})")
    }

    private fun cleanTitle(value: String): String = value.replace(AUDIO_EXTENSION, "")
    private fun searchKey(value: String): String = RecordingMatcher.normalize(value)
        .replace(SEARCH_SEPARATORS, "")

    private companion object {
        val AUDIO_EXTENSION = Regex("\\.(?:mp3|flac|wav|m4a|aac|ogg|ape|aiff|alac)$", RegexOption.IGNORE_CASE)
        val SEARCH_SEPARATORS = Regex("[\\p{P}\\p{S}\\s]+")
    }
}
