package app.pipo.nativeapp.data

/**
 * 镜像 src/lib/tauri.ts 的 TrackInfo + neteaseToTrack 投射结果。
 *
 *   - 没有 streamUrl 时表示 URL 还没拿到，UI 走 wrapAudioUrl 之后再补
 *   - cover/album 可空对应 React 的 PlaylistInfo / Track
 */
data class NativeTrack(
    val id: String,
    val neteaseId: Long? = null,
    val title: String,
    val artist: String,
    val album: String,
    val streamUrl: String,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
)
