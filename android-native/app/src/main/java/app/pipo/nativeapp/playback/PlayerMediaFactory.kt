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
            if (!featureDurationReliable) {
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
                    ),
                )
                return builder.build()
            }
            val headSafe = !preserveHeadBoundary && headMs in 1L..min(5000L, (durMs * 0.10).toLong())
            val tailSafe = !preserveTailBoundary && tailMs in 1L..min(8000L, (durMs * 0.15).toLong())
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
    }
}
