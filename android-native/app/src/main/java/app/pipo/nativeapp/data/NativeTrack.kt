package app.pipo.nativeapp.data

/**
 * 镜像 src/lib/tauri.ts 的 TrackInfo + neteaseToTrack 投射结果。
 *
 *   - 没有 streamUrl 时表示 URL 还没拿到，UI 走 wrapAudioUrl 之后再补
 *   - streamCacheKey 是 Media3 本地媒体缓存的稳定 key；网易云直链会重签，但同一首歌
 *     可以继续命中已经缓存下来的字节。
 *   - cover/album 可空对应 React 的 PlaylistInfo / Track
 */
data class NativeTrack(
    val id: String,
    val neteaseId: Long? = null,
    val title: String,
    val artist: String,
    val album: String,
    val streamUrl: String,
    val streamCacheKey: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
)
