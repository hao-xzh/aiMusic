package app.pipo.nativeapp.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
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

    @Volatile
    private var cached: List<NativeTrack>? = null
    private val playlistLoadSemaphore = Semaphore(TRACKS_LOAD_CONCURRENCY)

    suspend fun library(forceRefresh: Boolean = false): List<NativeTrack> {
        if (!forceRefresh) cached?.let { return it }
        var playlists = runCatching { repository.playlists.first() }.getOrDefault(emptyList())
        if (playlists.isEmpty()) {
            runCatching {
                repository.refreshAccount()
                repository.refreshPlaylists()
            }
            playlists = runCatching { repository.playlists.first() }.getOrDefault(emptyList())
            if (playlists.isEmpty()) return emptyList()
        }
        val tracks = coroutineScope {
            val deferred = playlists.map { p ->
                async {
                    playlistLoadSemaphore.withPermit {
                        runCatching { repository.tracksForPlaylist(p.id) }.getOrDefault(emptyList())
                    }
                }
            }
            val seen = HashSet<String>()
            val out = ArrayList<NativeTrack>()
            deferred.forEach { d ->
                d.await().forEach { t ->
                    if (seen.add(t.id)) out.add(t)
                }
            }
            out
        }
        if (tracks.isNotEmpty()) {
            cached = tracks
        }
        return tracks
    }

    fun invalidate() { cached = null }

    companion object {
        private const val TRACKS_LOAD_CONCURRENCY = 4
    }
}
