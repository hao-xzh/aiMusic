package app.pipo.nativeapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 用户库聚合 —— 把所有歌单的 tracks 合并成一个 library，用于本地召回管线。
 *
 * 缓存策略：
 *   - 第一次调用：拉所有歌单的 tracks（仓库内存层会按需拉网或拿 cache）
 *   - 后续调用：直接复用上次结果（除非主动 invalidate）
 *   - 用 trackId 去重，跨歌单出现的同一首只保留一份
 */
class LibraryLoader(private val repository: PipoRepository) {

    private data class CacheEntry(
        val revision: Long,
        val generation: Long,
        val tracks: List<NativeTrack>,
    )

    private data class LibraryLoad(
        val tracks: List<NativeTrack>,
        val complete: Boolean,
    )

    private data class SourceLoad(
        val tracks: List<NativeTrack>,
        val complete: Boolean,
    )

    private val cacheLock = Any()
    private var cached: CacheEntry? = null
    private var invalidationGeneration = 0L
    private val loadMutex = Mutex()
    private val playlistLoadSemaphore = Semaphore(TRACKS_LOAD_CONCURRENCY)

    suspend fun library(forceRefresh: Boolean = false): List<NativeTrack> {
        val revision = repository.libraryRevision.first()
        if (!forceRefresh) cachedFor(revision)?.let { return it }
        return loadMutex.withLock {
            while (true) {
                val loadRevision = repository.libraryRevision.first()
                if (!forceRefresh) cachedFor(loadRevision)?.let { return@withLock it }
                val loadGeneration = synchronized(cacheLock) { invalidationGeneration }
                val load = loadLibrary()
                val tracks = load.tracks
                // A like/add/delete may have changed the library while its playlists were being
                // aggregated. Retry instead of publishing an old snapshot to recommendation.
                if (loadRevision != repository.libraryRevision.first()) continue
                val committed = synchronized(cacheLock) {
                    if (loadGeneration != invalidationGeneration) false else {
                        if (load.complete && tracks.isNotEmpty()) {
                            cached = CacheEntry(loadRevision, loadGeneration, tracks)
                        }
                        true
                    }
                }
                if (committed) return@withLock tracks
            }
            error("unreachable")
        }
    }

    private suspend fun loadLibrary(): LibraryLoad {
        var playlists = readPlaylists()
        var playlistMembershipComplete = true
        if (playlists.isEmpty()) {
            playlistMembershipComplete = try {
                repository.refreshAccount()
                repository.refreshPlaylistsForBrowse()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            playlists = readPlaylists()
        }
        return coroutineScope {
            val deferred = playlists.map { p ->
                async {
                    playlistLoadSemaphore.withPermit {
                        try {
                            val tracks = repository.tracksForPlaylist(p.id)
                            SourceLoad(tracks, tracks.size >= p.trackCount)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            SourceLoad(emptyList(), complete = false)
                        }
                    }
                }
            }
            val cloudDeferred = async {
                try {
                    SourceLoad(repository.cloudDiskTracksForBrowse(), complete = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    SourceLoad(emptyList(), complete = false)
                }
            }
            val seen = HashSet<String>()
            val out = ArrayList<NativeTrack>()
            var complete = playlistMembershipComplete
            deferred.forEach { d ->
                val source = d.await()
                complete = complete && source.complete
                source.tracks.forEach { t ->
                    if (seen.add(t.id)) out.add(t)
                }
            }
            val cloud = cloudDeferred.await()
            complete = complete && cloud.complete
            cloud.tracks.forEach { t ->
                if (seen.add(t.id)) out.add(t)
            }
            LibraryLoad(out, complete)
        }
    }

    private suspend fun readPlaylists(): List<PipoPlaylist> = try {
        repository.playlists.first()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    private fun cachedFor(revision: Long): List<NativeTrack>? = synchronized(cacheLock) {
        cached?.takeIf { it.revision == revision && it.generation == invalidationGeneration }?.tracks
    }

    fun peek(): List<NativeTrack> = synchronized(cacheLock) { cached?.tracks.orEmpty() }

    fun invalidate() = synchronized(cacheLock) {
        invalidationGeneration += 1L
        cached = null
    }

    companion object {
        private const val TRACKS_LOAD_CONCURRENCY = 4
    }
}
