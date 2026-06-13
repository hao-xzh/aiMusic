package app.pipo.nativeapp.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.AudioFeaturesStore
import app.pipo.nativeapp.data.NativeTrack
import kotlin.math.min

@androidx.annotation.OptIn(UnstableApi::class)
internal class PlayerMediaFactory(
    private val featuresStore: AudioFeaturesStore,
) {
    fun toMediaItem(
        track: NativeTrack,
        preserveHeadBoundary: Boolean = false,
        preserveTailBoundary: Boolean = false,
    ): MediaItem {
        val artworkUri = track.artworkUrl?.let { url ->
            val secureUrl = if (url.startsWith("http://")) {
                url.replaceFirst("http://", "https://")
            } else {
                url
            }
            Uri.parse(secureUrl)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(artworkUri)
            .build()


        val builder = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.streamUrl)
            .setMediaMetadata(metadata)
        PlaybackCacheKeys.forTrack(track)?.let { builder.setCustomCacheKey(it) }

        val features = featuresStore.get(track.id)
        if (features != null) {
            val headMs = (features.headSilenceS * 1000).toLong().coerceAtLeast(0L)
            val tailMs = (features.tailSilenceS * 1000).toLong().coerceAtLeast(0L)
            val durMs = (features.durationS * 1000).toLong()
            val metadataDurMs = track.durationMs.takeIf { it > 0L }
            val featureDurationReliable = metadataDurMs == null ||
                durMs >= (metadataDurMs * FEATURE_DURATION_MIN_RATIO).toLong() ||
                metadataDurMs - durMs <= FEATURE_DURATION_MAX_SHORTFALL_MS
            // 尾部裁剪终点 = 特征时长 − 尾静音：特征可能来自被截断/不同版本的音频
            //（分析下载有 45s 总超时，弱网必截断），时长不与元数据严格一致就裁尾，
            // 会把真实歌曲的结尾连人声一起剪掉（“人声没唱完就切歌”）。
            // 头部修剪从文件起点量起，不依赖时长一致性，维持宽松判据即可。
            val tailDurationTrusted = metadataDurMs == null ||
                kotlin.math.abs(metadataDurMs - durMs) <= TAIL_TRIM_MAX_DURATION_DRIFT_MS
            if (!featureDurationReliable || !tailDurationTrusted) {
                DiagnosticsLogStore.record(
                    area = "playback",
                    event = "media_clipping_suppressed",
                    fields = mapOf(
                        "trackId" to track.id,
                        "neteaseId" to track.neteaseId,
                        "title" to track.title,
                        "artist" to track.artist,
                        "featureDurationMs" to durMs,
                        "metadataDurationMs" to metadataDurMs,
                        "scope" to if (featureDurationReliable) "tail_only" else "all",
                    ),
                )
            }
            val headSafe = featureDurationReliable &&
                !preserveHeadBoundary && headMs in 1L..min(5000L, (durMs * 0.10).toLong())
            val tailSafe = tailDurationTrusted && featureDurationReliable &&
                !preserveTailBoundary && tailMs in MIN_TAIL_TRIM_MS..min(8000L, (durMs * 0.15).toLong())
            val canTrim = durMs >= 30_000L && (headSafe || tailSafe) &&
                (durMs - (if (headSafe) headMs else 0L) - (if (tailSafe) tailMs else 0L)) >= 20_000L
            if (canTrim) {
                builder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(if (headSafe) headMs else 0L)
                        .setEndPositionMs(if (tailSafe) durMs - tailMs else C.TIME_END_OF_SOURCE)
                        .build(),
                )
            }
        }
        return builder.build()
    }

    private companion object {
        private const val FEATURE_DURATION_MIN_RATIO = 0.88
        private const val FEATURE_DURATION_MAX_SHORTFALL_MS = 20_000L

        /** 尾裁剪要求特征时长与元数据时长的最大偏差：超出即视为“特征不是这条音频”。 */
        private const val TAIL_TRIM_MAX_DURATION_DRIFT_MS = 1_500L

        /** 低于该值的“尾静音”不值得裁（多半是淡出被误判为静音，裁了反而切掉自然衰减）。 */
        private const val MIN_TAIL_TRIM_MS = 400L
    }
}
