package app.pipo.nativeapp.data

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.qqnative.QqNativeTransport
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** QQ Music Android RPC provider. QQ numeric song IDs and MID file identifiers stay distinct. */
class QqMusicProvider(private val transport: QqNativeTransport) : MusicProvider {

    override val id: String = MusicProviders.QQ
    override val capabilities = MusicProviderCapabilities(
        search = true,
        playback = true,
        library = true,
        likesWrite = true,
        playlistCreate = true,
        playlistWrite = true,
    )
    override val connection: StateFlow<MusicProviderConnection> = transport.connection

    override suspend fun refreshAccount() = transport.refreshAccount()

    override suspend fun logout() = transport.logout()

    override suspend fun search(query: String, limit: Int, offset: Int): MusicPage<NativeTrack> {
        require(query.isNotBlank()) { "QQ Music search query is required" }
        require(limit > 0) { "QQ Music search limit must be positive" }
        require(offset >= 0) { "QQ Music search offset cannot be negative" }
        val page = offset / limit + 1
        val data = transport.call(
            module = SEARCH_MODULE,
            method = SEARCH_METHOD,
            param = JSONObject().apply {
                put("searchid", nextSearchId())
                put("query", query)
                put("search_type", SEARCH_TYPE_SONG)
                put("num_per_page", limit)
                put("page_num", page)
                put("highlight", true)
                put("grp", true)
                put("selectors", JSONObject())
                put("vec_selectors", JSONArray())
            },
            authenticated = false,
        )
        val meta = data.requiredObject("meta", "QQ 搜索响应缺少 meta")
        val body = data.requiredObject("body", "QQ 搜索响应缺少 body")
        val songs = body.requiredArray("item_song", "QQ 搜索响应缺少歌曲列表")
        val items = songs.requiredObjects("QQ 搜索歌曲项无效").map(::trackFromSong)
        val total = meta.requiredNonNegativeInt("sum", "QQ 搜索响应缺少总数")
        val nextPage = meta.requiredInt("nextpage", "QQ 搜索响应缺少下一页")
        if (nextPage < -1 || (nextPage != -1 && items.isEmpty())) {
            throw remote("QQ 搜索分页响应没有进展")
        }
        return MusicPage(
            items = items,
            total = total,
            nextOffset = if (nextPage == -1) null else offset + items.size,
        )
    }

    override suspend fun resolveAudio(source: MusicSourceRef, quality: String): MusicAudioSource {
        val qq = source.requireQqSource()
        val file = QQ_AUDIO_TYPES[quality] ?: QQ_AUDIO_TYPES.getValue(DEFAULT_QUALITY)
        val mediaFileId = qq.mediaFileId?.trim().takeUnless { it.isNullOrEmpty() } ?: qq.mediaId
        val data = transport.call(
            module = VKEY_MODULE,
            method = VKEY_METHOD,
            param = JSONObject().apply {
                put("filename", JSONArray().put("${file.prefix}${qq.mediaId}$mediaFileId${file.extension}"))
                put("guid", UUID.randomUUID().toString().replace("-", ""))
                put("songmid", JSONArray().put(qq.mediaId))
                put("songtype", JSONArray().put(qq.songType))
                put("uin", requireAccountId())
                put("ctx", 0)
            },
        )
        val entry = data.requiredArray("midurlinfo", "QQ 播放响应缺少 midurlinfo")
            .requiredObjects("QQ 播放条目无效")
            .firstOrNull { it.optString("songmid") == qq.mediaId }
            ?: throw remote("QQ 播放响应没有歌曲 ${qq.mediaId}")
        val path = entry.optString("purl").trim()
        if (path.isEmpty()) throw remote("QQ Music 未返回可播放链接")
        val sip = data.requiredArray("sip", "QQ 播放响应缺少 sip")
            .strings("QQ 播放 CDN 地址无效")
        val domain = sip.firstOrNull { !it.startsWith("http://ws") }
            ?: sip.firstOrNull()
            ?: DEFAULT_STREAM_DOMAIN
        return MusicAudioSource(
            url = "$domain$path",
            cacheKey = "qq:${qq.mediaId}:${file.prefix}:$mediaFileId",
            quality = file.quality,
        )
    }

    override suspend fun playlists(): List<MusicPlaylist> = playlistCatalog().playlists

    override suspend fun playlistCatalog(): MusicPlaylistCatalog {
        val accountId = requireAccountId()
        val created = transport.call(
            module = PLAYLIST_BASE_READ_MODULE,
            method = PLAYLIST_BY_UIN_METHOD,
            param = JSONObject().put("uin", accountId),
        )
        val own = created.requiredArray("v_playlist", "QQ 自建歌单响应缺少 v_playlist")
            .requiredObjects("QQ 自建歌单项无效")
            .map { playlistFromJson(it, accountId, canEdit = true) }
        val complete = !created.has("bFinish") || created.requiredBooleanFlag("bFinish", "QQ 自建歌单完成标记无效")
        DiagnosticsLogStore.record("qq_library", "created_playlists", mapOf("count" to own.size, "complete" to complete))

        val encryptedUin = requireEncryptUin()
        val favorites = mutableListOf<MusicPlaylist>()
        var offset = 0
        do {
            val page = transport.call(
                module = PLAYLIST_FAV_READ_MODULE,
                method = PLAYLIST_FAV_METHOD,
                param = JSONObject().apply {
                    put("uin", encryptedUin)
                    put("offset", offset)
                    put("size", PLAYLIST_PAGE_SIZE)
                },
            )
            val rows = page.requiredArray("v_list", "QQ 收藏歌单响应缺少 v_list")
                .requiredObjects("QQ 收藏歌单项无效")
            favorites += rows.map { playlistFromJson(it, null, canEdit = false) }
            val hasMore = page.requiredBooleanFlag("hasmore", "QQ 收藏歌单响应缺少 hasmore")
            DiagnosticsLogStore.record("qq_library", "collected_page", mapOf("offset" to offset, "count" to rows.size, "has_more" to hasMore))
            if (!hasMore) break
            if (rows.isEmpty()) throw remote("QQ 收藏歌单分页没有进展")
            offset += rows.size
        } while (true)

        val seen = mutableSetOf<String>()
        // The favorite-song API has a documented fixed identity, independent of ordinary tid lists.
        val liked = MusicPlaylist(id, "0", "我喜欢的音乐", own.firstOrNull { it.isFavorites }?.trackCount ?: 0,
            accountId, true, isFavorites = true, coverUrl = own.firstOrNull { it.isFavorites }?.coverUrl,
            directoryId = FAVORITES_DIRECTORY_ID)
        val playlists = (listOf(liked) + own.filterNot { it.isFavorites } + favorites).filter { playlist ->
            seen.add(playlist.remoteId)
        }
        return MusicPlaylistCatalog(playlists, complete, if (complete) null else "QQ 部分歌单尚未返回，已保留已有歌单，可稍后刷新。")
    }

    override suspend fun playlistTracks(
        playlist: MusicPlaylist,
        limit: Int,
        offset: Int,
    ): MusicPage<NativeTrack> {
        require(playlist.providerId == id) { "QQ Music playlist provider mismatch" }
        require(limit > 0) { "QQ Music playlist limit must be positive" }
        require(offset >= 0) { "QQ Music playlist offset cannot be negative" }
        val tid = playlist.remoteId.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw remote("QQ 歌单缺少 tid")
        val directoryId = playlist.directoryId?.takeIf { it >= 0 }
            ?: throw remote("QQ 歌单缺少 dirId")
        val data = transport.call(
            module = DISS_INFO_MODULE,
            method = DISS_DETAIL_METHOD,
            param = JSONObject().apply {
                put("disstid", tid)
                put("dirid", directoryId)
                put("tag", true)
                put("song_begin", offset)
                put("song_num", limit)
                put("userinfo", true)
                put("orderlist", true)
                put("onlysonglist", false)
                if (playlist.isFavorites) put("enc_host_uin", requireEncryptUin())
            },
        )
        val songs = data.requiredArray("songlist", "QQ 歌单响应缺少 songlist")
        val items = songs.requiredObjects("QQ 歌单歌曲项无效").map(::trackFromSong)
        val total = data.requiredNonNegativeInt("total_song_num", "QQ 歌单响应缺少总数")
        val reportedMore = if (data.has("hasmore")) data.requiredBooleanFlag("hasmore", "QQ 歌单分页标记无效") else false
        val hasMore = reportedMore || offset + items.size < total
        DiagnosticsLogStore.record("qq_library", "tracks_page", mapOf("offset" to offset, "count" to items.size, "total" to total, "has_more" to hasMore))
        if (hasMore && items.isEmpty()) throw remote("QQ 歌单分页没有进展")
        return MusicPage(
            items = items,
            total = total,
            nextOffset = if (hasMore) {
                offset + items.size
            } else {
                null
            },
        )
    }

    override suspend fun like(source: MusicSourceRef, liked: Boolean) {
        val song = source.requireWritableQqSource()
        writeSonglist(
            method = if (liked) PLAYLIST_ADD_METHOD else PLAYLIST_DELETE_METHOD,
            directoryId = FAVORITES_DIRECTORY_ID,
            tid = 0L,
            sources = listOf(song),
        )
    }

    override suspend fun createPlaylist(name: String): MusicPlaylist {
        require(name.isNotBlank()) { "QQ Music playlist name is required" }
        val data = transport.call(
            module = PLAYLIST_BASE_WRITE_MODULE,
            method = PLAYLIST_CREATE_METHOD,
            param = JSONObject().put("dirName", name),
        )
        requireWriteSuccess(data, "QQ 新建歌单失败")
        val result = data.requiredObject("result", "QQ 新建歌单响应缺少 result")
        val tid = result.requiredPositiveLong("tid", "QQ 新建歌单响应缺少 tid")
        val directoryId = result.requiredPositiveLong("dirId", "QQ 新建歌单响应缺少 dirId")
        val resolvedName = result.optString("dirName").trim().ifEmpty { name }
        return MusicPlaylist(
            providerId = id,
            remoteId = tid.toString(),
            name = resolvedName,
            trackCount = 0,
            ownerId = requireAccountId(),
            canEdit = true,
            directoryId = directoryId,
        )
    }

    override suspend fun modifyPlaylist(playlist: MusicPlaylist, sources: List<MusicSourceRef>, add: Boolean) {
        require(playlist.providerId == id) { "QQ Music playlist provider mismatch" }
        if (!playlist.canEdit) unsupported("非自建歌单编辑")
        if (sources.isEmpty()) return
        val directoryId = playlist.directoryId?.takeIf { it > 0 }
            ?: throw remote("QQ 可编辑歌单缺少 dirId")
        val tid = playlist.remoteId.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw remote("QQ 可编辑歌单缺少 tid")
        writeSonglist(
            method = if (add) PLAYLIST_ADD_METHOD else PLAYLIST_DELETE_METHOD,
            directoryId = directoryId,
            tid = tid,
            sources = sources.map { it.requireWritableQqSource() },
        )
    }

    private suspend fun writeSonglist(
        method: String,
        directoryId: Long,
        tid: Long,
        sources: List<MusicSourceRef>,
    ) {
        val songInfo = JSONArray().apply {
            sources.forEach { source ->
                put(JSONObject().put("songId", source.numericId).put("songType", source.songType))
            }
        }
        val data = transport.call(
            module = PLAYLIST_DETAIL_WRITE_MODULE,
            method = method,
            param = JSONObject().apply {
                put("dirId", directoryId)
                put("tid", tid)
                put("bFmtUtf8", true)
                put("v_songInfo", songInfo)
            },
        )
        requireWriteSuccess(data, "QQ 歌单写入失败")
    }

    private fun trackFromSong(song: JSONObject): NativeTrack {
        val numericId = song.requiredPositiveLong("id", "QQ 歌曲缺少数字 ID")
        val mid = song.requiredNonBlank("mid", "QQ 歌曲缺少 MID")
        val title = song.requiredNonBlank("name", "QQ 歌曲缺少名称")
        val songType = song.requiredInt("type", "QQ 歌曲缺少类型")
        val singers = song.requiredArray("singer", "QQ 歌曲缺少歌手")
            .requiredObjects("QQ 歌曲歌手项无效")
            .map { it.requiredNonBlank("name", "QQ 歌曲歌手缺少名称") }
        val album = song.requiredObject("album", "QQ 歌曲缺少专辑")
        val albumTitle = album.optString("name").trim().ifEmpty { album.optString("title").trim() }
        val mediaFileId = song.requiredObject("file", "QQ 歌曲缺少文件信息")
            .optString("media_mid").trim().takeIf { it.isNotEmpty() }
        val durationSeconds = song.requiredNonNegativeLong("interval", "QQ 歌曲缺少时长")
        val coverId = album.optString("mid").trim().ifEmpty { album.optString("pmid").trim() }
        return NativeTrack(
            id = "qq:$mid",
            neteaseId = null,
            title = title,
            artist = singers.joinToString("/"),
            album = albumTitle,
            streamUrl = "",
            artworkUrl = coverId.takeIf { it.isNotEmpty() }?.let { QQ_ALBUM_COVER_PREFIX + it + ".jpg" },
            durationMs = durationSeconds * 1_000L,
            sourceRef = MusicSourceRef(
                providerId = id,
                mediaId = mid,
                mediaFileId = mediaFileId,
                numericId = numericId,
                songType = songType,
            ),
        )
    }

    private fun playlistFromJson(json: JSONObject, ownerId: String?, canEdit: Boolean): MusicPlaylist {
        val directoryId = json.requiredFirstNonNegativeLong(listOf("dirId", "dirid"), "QQ 歌单缺少 dirId")
        val tid = if (canEdit && directoryId == FAVORITES_DIRECTORY_ID) 0L
            else json.requiredFirstPositiveLong(listOf("tid", "dissid", "id"), "QQ 歌单缺少 tid")
        val name = json.requiredFirstNonBlank(listOf("title", "dissname", "name", "dirName"), "QQ 歌单缺少名称")
        return MusicPlaylist(
            providerId = id,
            remoteId = tid.toString(),
            name = name,
            trackCount = json.firstNonNegativeInt(listOf("songnum", "songNum", "song_cnt")) ?: 0,
            ownerId = ownerId ?: json.optString("uin").trim().takeIf { it.isNotEmpty() },
            canEdit = canEdit,
            isFavorites = canEdit && directoryId == FAVORITES_DIRECTORY_ID,
            coverUrl = json.firstNonBlank(listOf("bigpicUrl", "picurl", "picUrl", "albumPicUrl", "cover", "logo"))
                ?.let { url -> when {
                    url.startsWith("http://") -> "https://" + url.removePrefix("http://")
                    url.startsWith("//") -> "https:$url"
                    else -> url
                } },
            directoryId = directoryId,
        )
    }

    private fun requireAccountId(): String = transport.accountId?.trim().takeIf { !it.isNullOrEmpty() }
        ?: throw MusicProviderException(id, MusicProviderError.AUTH_REQUIRED, "QQ Music 尚未登录")

    private fun requireEncryptUin(): String = transport.encryptUin?.trim().takeIf { !it.isNullOrEmpty() }
        ?: throw MusicProviderException(id, MusicProviderError.AUTH_REQUIRED, "QQ Music 登录凭据缺少 encryptUin")

    private fun MusicSourceRef.requireQqSource(): MusicSourceRef {
        require(providerId == id) { "QQ Music source provider mismatch" }
        return this
    }

    private fun MusicSourceRef.requireWritableQqSource(): MusicSourceRef = requireQqSource().also {
        if (it.numericId == null || it.numericId <= 0L) throw remote("QQ 歌曲缺少可写入的数字 ID")
    }

    private fun requireWriteSuccess(data: JSONObject, message: String) {
        if (data.requiredInt("retCode", "$message：响应缺少 retCode") != 0) throw remote(message)
    }

    private fun remote(message: String): MusicProviderException =
        MusicProviderException(id, MusicProviderError.REMOTE, message)

    private fun nextSearchId(): String {
        val multiplier = (1..20).random().toLong() * 18_014_398_509_481_984L
        val randomPart = (0..4_194_304).random().toLong() * 4_294_967_296L
        val timePart = System.currentTimeMillis() % 86_400_000L
        return (multiplier + randomPart + timePart).toString()
    }

    private data class QqAudioType(val quality: String, val prefix: String, val extension: String)

    private companion object {
        const val SEARCH_MODULE = "music.search.SearchCgiService"
        const val SEARCH_METHOD = "DoSearchForQQMusicMobile"
        const val SEARCH_TYPE_SONG = 0
        const val VKEY_MODULE = "music.vkey.GetVkey"
        const val VKEY_METHOD = "UrlGetVkey"
        const val PLAYLIST_BASE_READ_MODULE = "music.musicasset.PlaylistBaseRead"
        const val PLAYLIST_BY_UIN_METHOD = "GetPlaylistByUin"
        const val PLAYLIST_FAV_READ_MODULE = "music.musicasset.PlaylistFavRead"
        const val PLAYLIST_FAV_METHOD = "CgiGetPlaylistFavInfo"
        const val PLAYLIST_BASE_WRITE_MODULE = "music.musicasset.PlaylistBaseWrite"
        const val PLAYLIST_CREATE_METHOD = "AddPlaylist"
        const val PLAYLIST_DETAIL_WRITE_MODULE = "music.musicasset.PlaylistDetailWrite"
        const val PLAYLIST_ADD_METHOD = "AddSonglist"
        const val PLAYLIST_DELETE_METHOD = "DelSonglist"
        const val DISS_INFO_MODULE = "music.srfDissInfo.DissInfo"
        const val DISS_DETAIL_METHOD = "CgiGetDiss"
        const val PLAYLIST_PAGE_SIZE = 100
        const val FAVORITES_DIRECTORY_ID = 201L
        const val DEFAULT_STREAM_DOMAIN = "http://dl.stream.qqmusic.qq.com/"
        const val QQ_ALBUM_COVER_PREFIX = "https://y.gtimg.cn/music/photo_new/T002R300x300M000"
        const val DEFAULT_QUALITY = "128"

        val QQ_AUDIO_TYPES = mapOf(
            "m4a" to QqAudioType("m4a", "C400", ".m4a"),
            "128" to QqAudioType("128", "M500", ".mp3"),
            "320" to QqAudioType("320", "M800", ".mp3"),
            "ape" to QqAudioType("ape", "A000", ".ape"),
            "flac" to QqAudioType("flac", "F000", ".flac"),
        )
    }
}

private fun JSONObject.requiredObject(key: String, message: String): JSONObject = optJSONObject(key)
    ?: throw MusicProviderException(MusicProviders.QQ, MusicProviderError.REMOTE, message)

private fun JSONObject.requiredArray(key: String, message: String): JSONArray = optJSONArray(key)
    ?: throw MusicProviderException(MusicProviders.QQ, MusicProviderError.REMOTE, message)

private fun JSONArray.requiredObjects(message: String): List<JSONObject> = List(length()) { index ->
    optJSONObject(index) ?: throw MusicProviderException(MusicProviders.QQ, MusicProviderError.REMOTE, message)
}

private fun JSONArray.strings(message: String): List<String> = List(length()) { index ->
    optString(index).trim().takeIf { it.isNotEmpty() }
        ?: throw MusicProviderException(MusicProviders.QQ, MusicProviderError.REMOTE, message)
}

private fun JSONObject.requiredNonBlank(key: String, message: String): String = optString(key).trim()
    .takeIf { it.isNotEmpty() } ?: throw MusicProviderException(MusicProviders.QQ, MusicProviderError.REMOTE, message)

private fun JSONObject.requiredInt(key: String, message: String): Int = integer(key)
    ?: throw MusicProviderException(MusicProviders.QQ, MusicProviderError.REMOTE, message)

private fun JSONObject.requiredNonNegativeInt(key: String, message: String): Int = requiredInt(key, message)
    .takeIf { it >= 0 } ?: throw MusicProviderException(MusicProviders.QQ, MusicProviderError.REMOTE, message)

private fun JSONObject.requiredBooleanFlag(key: String, message: String): Boolean = when (val value = opt(key)) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    else -> throw MusicProviderException(MusicProviders.QQ, MusicProviderError.REMOTE, message)
}

private fun JSONObject.requiredPositiveLong(key: String, message: String): Long = long(key)
    ?.takeIf { it > 0L } ?: throw MusicProviderException(MusicProviders.QQ, MusicProviderError.REMOTE, message)

private fun JSONObject.requiredNonNegativeLong(key: String, message: String): Long = long(key)
    ?.takeIf { it >= 0L } ?: throw MusicProviderException(MusicProviders.QQ, MusicProviderError.REMOTE, message)

private fun JSONObject.requiredFirstPositiveLong(keys: List<String>, message: String): Long = keys.asSequence()
    .mapNotNull { long(it) }.firstOrNull { it > 0L }
    ?: throw MusicProviderException(MusicProviders.QQ, MusicProviderError.REMOTE, message)

private fun JSONObject.requiredFirstNonNegativeLong(keys: List<String>, message: String): Long = keys.asSequence()
    .mapNotNull { long(it) }.firstOrNull { it >= 0L }
    ?: throw MusicProviderException(MusicProviders.QQ, MusicProviderError.REMOTE, message)

private fun JSONObject.requiredFirstNonBlank(keys: List<String>, message: String): String = firstNonBlank(keys)
    ?: throw MusicProviderException(MusicProviders.QQ, MusicProviderError.REMOTE, message)

private fun JSONObject.firstNonBlank(keys: List<String>): String? = keys.asSequence()
    .map { optString(it).trim() }.firstOrNull { it.isNotEmpty() }

private fun JSONObject.firstNonNegativeInt(keys: List<String>): Int? = keys.asSequence()
    .mapNotNull { integer(it) }.firstOrNull { it >= 0 }

private fun JSONObject.integer(key: String): Int? = when (val value = opt(key)) {
    is Number -> value.toInt()
    is String -> value.toIntOrNull()
    else -> null
}

private fun JSONObject.long(key: String): Long? = when (val value = opt(key)) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}
