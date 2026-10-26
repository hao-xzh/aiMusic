package app.pipo.nativeapp.playback

import app.pipo.nativeapp.data.NativeTrack

internal object PlaybackCacheKeys {
    fun resolvedNetease(
        id: Long,
        level: String,
        bitrate: Int,
        sizeBytes: Long,
        isTrial: Boolean = false,
    ): String {
        val cleanLevel = level.ifBlank { "unknown" }
        val br = bitrate.takeIf { it > 0 } ?: 0
        val size = sizeBytes.takeIf { it > 0L } ?: 0L
        return "netease:$id:$cleanLevel:$br:$size" + if (isTrial) ":trial" else ""
    }

    fun forTrack(track: NativeTrack): String? {
        track.streamCacheKey?.takeIf { it.isNotBlank() }?.let { return it }
        // URL 签名会变，且同一 recording 的不同 source/quality 不是同一份字节。
        // 只有 provider 返回的版本化 cache key 才能跨 URL 复用缓存。
        return null
    }

    fun forMediaId(mediaId: String): String? = null
}
