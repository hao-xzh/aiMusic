package app.pipo.nativeapp.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 镜像 src/lib/player-state.tsx 里的播放器配置：
 *   - 走 cache 优先的 HTTP DataSource（claudio-audio:// scheme 的 Android 等价物）
 *   - 系统级音频焦点 + 拔耳机自动暂停
 *   - 紧凑 buffer（gapless 接歌不延迟）
 *   - 队列循环 + Media3 自带的"曲尾无缝过渡"
 */
@UnstableApi
class PipoPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(PipoMediaCache.get(this))
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setUserAgent("PipoNative/0.1")
                    .setAllowCrossProtocolRedirects(true),
            )
            // 缓存写失败时仍能继续播 —— 网络抖动不掉歌
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val musicAttrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // gapless 接歌的 buffer 调小一些（默认 50s 太大，next track 切到时机会拖）
        // 同时保证最低 ~5s 防 mobile 网络抖动
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000,
            )
            // 对接歌过渡至关重要：当前曲不再向前缓冲、空间留给下一首
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 自定义 RenderersFactory：在 audio 渲染链里挂 AmpAudioProcessor
        // 让真实 PCM 经过 AmpAudioProcessor 算 RMS 写到全局 Amp.flow
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(AmpAudioProcessor()))
                    .build()
            }
        }

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setAudioAttributes(musicAttrs, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ALL
                // 关键：保持 CPU + WiFi 醒着 —— 默认 ExoPlayer 不持锁
                // 屏幕熄灭 ~30s 后 doze/idle，CPU 睡 → 网络 socket 关 →
                // 当前缓冲耗尽就停（"放着放着自动停止"的根因）
                setWakeMode(C.WAKE_MODE_NETWORK)

                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        // 网络抖动 / 解码瞬时失败 → 自动 prepare 重试，比让用户手动恢复体验好
                        Log.w("PipoPlayer", "playback error code=${error.errorCodeName}", error)
                        val recoverable = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true
                            else -> false
                        }
                        if (recoverable) {
                            // 同首歌 head 处重连；如果 1.5s 内还失败 ，下次再走 next
                            seekToDefaultPosition()
                            prepare()
                        } else if (mediaItemCount > 1) {
                            // 不可恢复（解码 / DRM）→ 跳下一首避免卡死
                            seekToNext()
                            prepare()
                        }
                    }
                })
            }

        // 通知栏 / 锁屏的"点击区"指回主 Activity —— 没有这条，状态栏播放卡片被
        // 点击后系统找不到目标，整个通知体验不通畅。
        val activityClass = Class.forName("app.pipo.nativeapp.MainActivity")
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, activityClass).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * 用户从最近任务划掉 app 时调用。
     *
     * 默认行为是：如果当前没在播 → stopSelf()。但 MediaSessionService 父类会在播放时
     * **保留** service。我们显式实现这条逻辑：只有在确实空闲时才 stopSelf，正在播继续。
     * —— 否则部分 ROM（小米/华为定制）会把整个服务连同前台通知一起干掉，"放着放着停了"。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        // 在播 → 不调 stopSelf, 让前台 service 继续吃通知活
    }

    override fun onDestroy() {
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
