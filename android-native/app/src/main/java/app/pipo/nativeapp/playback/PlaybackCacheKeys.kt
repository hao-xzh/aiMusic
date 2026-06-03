package app.pipo.nativeapp.playback

import app.pipo.nativeapp.data.NativeTrack

internal object PlaybackCacheKeys {
    fun resolvedNetease(
        id: Long,
        level: String,
        bitrate: Int,
        sizeBytes: Long,
    ): String {
        val cleanLevel = level.ifBlank { "unknown" }
        val br = bitrate.takeIf { it > 0 } ?: 0
        val size = sizeBytes.takeIf { it > 0L } ?: 0L
        return "netease:$id:$cleanLevel:$br:$size"
    }

    fun forTrack(track: NativeTrack): String? {
        track.streamCacheKey?.takeIf { it.isNotBlank() }?.let { return it }
        val id = track.neteaseId ?: track.id.toLongOrNull()
        return id?.let { "netease:$it" }
    }

    fun forMediaId(mediaId: String): String? {
        return mediaId.toLongOrNull()?.let { "netease:$it" }
    }
}
