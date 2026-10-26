package app.pipo.nativeapp.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Keeps the proven JNI account and request implementation behind a source-aware boundary. */
class NetEaseMusicProvider(private val repository: PipoRepository, scope: CoroutineScope) : MusicProvider {
    override val id = MusicProviders.NETEASE
    override val capabilities = MusicProviderCapabilities(likesWrite = true, playlistCreate = true, playlistWrite = true)
    override val connection = repository.account.map { account ->
        MusicProviderConnection(true, account?.let { MusicProviderAccount(it.userId.toString(), it.nickname, it.avatarUrl, it.hasMusicSubscription) })
    }.stateIn(scope, SharingStarted.Eagerly, MusicProviderConnection(true, repository.account.value?.let {
        MusicProviderAccount(it.userId.toString(), it.nickname, it.avatarUrl, it.hasMusicSubscription)
    }))

    override suspend fun refreshAccount() {
        repository.refreshAccount()
        val account = repository.account.value
        connection.first { it.account?.id == account?.userId?.toString() &&
            it.account?.hasMusicSubscription == account?.hasMusicSubscription }
    }
    override suspend fun logout() = repository.logout()
    override suspend fun search(query: String, limit: Int, offset: Int): MusicPage<NativeTrack> {
        val tracks = repository.searchTracksPage(query, limit, offset)
        return MusicPage(tracks, nextOffset = if (tracks.size >= limit) offset + tracks.size else null)
    }

    override suspend fun resolveAudio(source: MusicSourceRef, quality: String): MusicAudioSource? {
        val mediaId = requireNotNull(source.neteaseId) { "无效的网易云歌曲" }
        val result = repository.songUrls(listOf(mediaId), quality).firstOrNull { it.id == mediaId && !it.url.isNullOrBlank() } ?: return null
        val cacheKey = "netease:$mediaId:$quality:${result.bitrate}:${result.sizeBytes}" + if (result.isTrial) ":trial" else ""
        return MusicAudioSource(requireNotNull(result.url), cacheKey, quality, isTrial = result.isTrial)
    }

    override suspend fun playlists(): List<MusicPlaylist> {
        val owner = requireAccount()
        repository.refreshPlaylistsForBrowse()
        checkOwner(owner)
        return repository.playlists.first().map { playlist ->
            MusicPlaylist(id, playlist.id.toString(), playlist.name, playlist.trackCount,
                playlist.userId?.toString(), playlist.userId?.toString() == owner,
                playlist.specialType == 5 && playlist.userId?.toString() == owner, playlist.coverUrl)
        }
    }

    override suspend fun playlistTracks(playlist: MusicPlaylist, limit: Int, offset: Int): MusicPage<NativeTrack> {
        require(playlist.providerId == id)
        val owner = requireAccount()
        // The JNI repository already fetches every page; refreshing page zero invalidates its cache.
        val tracks = repository.tracksForPlaylist(playlist.remoteId.toLong(), forceRefresh = offset == 0)
        checkOwner(owner)
        val page = tracks.drop(offset).take(limit)
        return MusicPage(page, tracks.size, (offset + page.size).takeIf { it < tracks.size })
    }

    override suspend fun playlistMembership(playlist: MusicPlaylist, source: MusicSourceRef): MusicPlaylistMembership {
        require(playlist.providerId == id && source.providerId == id)
        val owner = requireAccount()
        val present = repository.playlistContainsTrack(playlist.remoteId.toLong(), requireNotNull(source.neteaseId))
        checkOwner(owner)
        return MusicPlaylistMembership(present)
    }

    override suspend fun samplePlaylistTracks(playlist: MusicPlaylist, limit: Int): MusicPage<NativeTrack> {
        require(playlist.providerId == id)
        require(limit > 0)
        val owner = requireAccount()
        val tracks = repository.tracksForPlaylist(playlist.remoteId.toLong(), forceRefresh = false)
        checkOwner(owner)
        val page = tracks.take(limit)
        return MusicPage(page, tracks.size, page.size.takeIf { it < tracks.size })
    }

    override suspend fun like(source: MusicSourceRef, liked: Boolean) {
        val owner = requireAccount()
        repository.likeSong(requireNotNull(source.neteaseId), liked)
        checkOwner(owner)
    }

    override suspend fun createPlaylist(name: String): MusicPlaylist {
        val owner = requireAccount()
        val playlistId = repository.createPlaylist(name)
        checkOwner(owner)
        return MusicPlaylist(id, playlistId.toString(), name, 0, owner, true)
    }

    override suspend fun modifyPlaylist(playlist: MusicPlaylist, sources: List<MusicSourceRef>, add: Boolean) {
        val owner = requireAccount()
        require(playlist.providerId == id && playlist.canEdit && playlist.ownerId == owner) { "只能编辑当前账号自己的歌单" }
        repository.playlistModifyTracks(playlist.remoteId.toLong(), if (add) "add" else "del", sources.map { requireNotNull(it.neteaseId) })
        checkOwner(owner)
    }

    private fun requireAccount(): String = repository.account.value?.userId?.toString()
        ?: throw MusicProviderException(id, MusicProviderError.AUTH_REQUIRED, "请先登录网易云")
    private fun checkOwner(owner: String) {
        if (repository.account.value?.userId?.toString() != owner)
            throw MusicProviderException(id, MusicProviderError.STALE_SESSION, "网易云账号已切换，请重新刷新")
    }
}
