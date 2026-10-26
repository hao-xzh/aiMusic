package app.pipo.nativeapp.data

import kotlinx.coroutines.flow.StateFlow

/** Capability-aware boundary shared by playback, library, recommendation and sync on Android. */
interface MusicProvider {
    val id: String
    val capabilities: MusicProviderCapabilities
    val connection: StateFlow<MusicProviderConnection>
    suspend fun refreshAccount()
    suspend fun logout()
    suspend fun search(query: String, limit: Int, offset: Int): MusicPage<NativeTrack>
    suspend fun resolveAudio(source: MusicSourceRef, quality: String): MusicAudioSource?
    suspend fun playlists(): List<MusicPlaylist>
    suspend fun playlistCatalog(): MusicPlaylistCatalog = MusicPlaylistCatalog(playlists())
    suspend fun playlistTracks(playlist: MusicPlaylist, limit: Int, offset: Int): MusicPage<NativeTrack>
    /** Stop once the exact source is found. Absence requires a complete, progressing read. */
    suspend fun playlistMembership(playlist: MusicPlaylist, source: MusicSourceRef): MusicPlaylistMembership {
        require(source.providerId == id && playlist.providerId == id)
        var offset = 0
        repeat(1_000) {
            val page = playlistTracks(playlist, 200, offset)
            page.items.firstOrNull { it.musicSource?.key == source.key }?.let {
                return MusicPlaylistMembership(true, it)
            }
            val end = offset + page.items.size
            val next = page.nextOffset ?: page.total?.let { total -> end.takeIf { it < total } }
            if (next == null) return MusicPlaylistMembership(false)
            check(next > offset && page.items.isNotEmpty()) { "远端分页未完整结束" }
            offset = next
        }
        error("远端分页未完整结束")
    }
    /** Bounded read for secondary consumers that must not invalidate the normal library cache. */
    suspend fun samplePlaylistTracks(playlist: MusicPlaylist, limit: Int): MusicPage<NativeTrack> =
        playlistTracks(playlist, limit, offset = 0)
    suspend fun like(source: MusicSourceRef, liked: Boolean) { unsupported("收藏写入") }
    suspend fun createPlaylist(name: String): MusicPlaylist { unsupported("新建歌单") }
    suspend fun modifyPlaylist(playlist: MusicPlaylist, sources: List<MusicSourceRef>, add: Boolean) {
        unsupported("歌单编辑")
    }

    fun unsupported(operation: String): Nothing = throw MusicProviderException(
        id, MusicProviderError.UNSUPPORTED, "${MusicProviders.label(id)}暂不支持$operation",
    )
}

data class MusicProviderCapabilities(
    val search: Boolean = true,
    val playback: Boolean = true,
    val library: Boolean = true,
    val likesWrite: Boolean = false,
    val playlistCreate: Boolean = false,
    val playlistWrite: Boolean = false,
)

data class MusicProviderAccount(
    val id: String,
    val nickname: String,
    val avatarUrl: String? = null,
    /** null means the provider has not confirmed this account's music entitlement. */
    val hasMusicSubscription: Boolean? = null,
)
data class MusicProviderConnection(
    val configured: Boolean,
    val account: MusicProviderAccount? = null,
    val error: String? = null,
)

data class MusicPage<T>(val items: List<T>, val total: Int? = null, val nextOffset: Int? = null)
data class MusicPlaylistMembership(val present: Boolean, val track: NativeTrack? = null)
data class MusicPlaylistCatalog(
    val playlists: List<MusicPlaylist>,
    val complete: Boolean = true,
    val warning: String? = null,
)
data class MusicAudioSource(val url: String, val cacheKey: String, val quality: String, val isTrial: Boolean = false)

data class MusicPlaylist(
    val providerId: String,
    val remoteId: String,
    val name: String,
    val trackCount: Int,
    val ownerId: String?,
    val canEdit: Boolean,
    val isFavorites: Boolean = false,
    val coverUrl: String? = null,
    val directoryId: Long? = null,
) {
    val key: String get() = "$providerId:${ownerId.orEmpty()}:$remoteId"
}

enum class MusicProviderError { UNCONFIGURED, AUTH_REQUIRED, UNSUPPORTED, NETWORK, RATE_LIMITED, REMOTE, STALE_SESSION }

class MusicProviderException(
    val providerId: String,
    val code: MusicProviderError,
    message: String,
) : Exception(message)
