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
                    .setAllowCrossProtocolRedirects(true)
                    // 网易 CDN 有反盗链:无 Referer 的 Range 请求会偶发返回 4xx/302,
                    // 表现为切歌时 ExoPlayer 报 ERROR_CODE_IO_BAD_HTTP_STATUS,然后
                    // PlayerViewModel 走重签 URL 路径 —— 但每次切歌都被打断不可接受。
                    // 桌面端 (Tauri/Rust) 的 cdn_client 自动注入这条,这里给安卓端补上。
                    .setDefaultRequestProperties(
                        mapOf("Referer" to "https://music.163.com/")
                    )
                    // 默认 connect/read timeout 都是 8s, 移动网弱信号下首字节就可能 > 8s。
                    // 给一个更宽容的窗口,让 ExoPlayer 自己的"重试 + recoverable 路径"
                    // 来兜底,而不是动不动就抛网络错误打断播放。
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(15_000),
            )
            // 缓存写失败时仍能继续播 —— 网络抖动不掉歌
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val musicAttrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // gapless 接歌的 buffer 调小一些（默认 50s 太大，next track 切到时机会拖）。
        // 给 min/max 留更多余量是为了网络抖动时不至于秒空 —— 之前 minBufferMs=15s 在
        // 4G 弱信号下,刚补满就被消耗,反复 rebuffer 触发 ERROR_CODE_IO_NETWORK_*。
        // bufferForPlaybackMs 拉回 ExoPlayer 默认 2500ms:首字节就开播太激进,
        // 切歌瞬间下一首还没攒够 1.5s 就报错,新连接还来不及补就被 onPlayerError 打断。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 25_000,
                /* maxBufferMs = */ 40_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000,
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

                // 同曲连续失败的 attempt 计数 —— 同一 mediaItemIndex 上连失 ≥3 次就放弃重试，
                // 跳下一首或停下，避免坏 URL 触发 prepare → onPlayerError → prepare 死循环烧 CPU
                var lastErrorIndex = -1
                var consecutiveErrors = 0

                addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        // 切歌成功 = 之前的 stuck 解开了 → 重置计数
                        consecutiveErrors = 0
                        lastErrorIndex = -1
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.w("PipoPlayer", "playback error code=${error.errorCodeName}", error)
                        val curIdx = currentMediaItemIndex
                        if (curIdx == lastErrorIndex) consecutiveErrors++ else {
                            lastErrorIndex = curIdx
                            consecutiveErrors = 1
                        }
                        // 同曲连续失败 ≥3 次 → 这首确实坏了（或 ViewModel 重签也没救），
                        // 跳下一首；没下一首就停在 IDLE 让用户手动决定
                        if (consecutiveErrors >= 3) {
                            if (mediaItemCount > 1 && hasNextMediaItem()) {
                                seekToNext()
                                consecutiveErrors = 0
                                lastErrorIndex = -1
                                prepare()
                                play()
                            }
                            return
                        }

                        // HTTP 类错误大概率是网易直链过期 —— 不要在服务层 prepare/play
                        // 同一个死 URL（之前会触发"快速 retry → 3 次后跳下一首（也死）→ 一路自动切"
                        // 的雪崩）。停在 IDLE，让 PlayerViewModel.onPlayerError 走 songUrls 重签
                        // + replaceMediaItem 路径，再 prepare+play。
                        val isUrlExpiryLike = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true
                            else -> false
                        }
                        if (isUrlExpiryLike) return

                        // 网络抖动等一过性错误 —— 当前 URL 还没过期，原地 prepare 重试就行
                        val recoverable = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> true
                            else -> false
                        }
                        if (recoverable) {
                            seekToDefaultPosition()
                            prepare()
                            play()
                        } else if (mediaItemCount > 1 && hasNextMediaItem()) {
                            seekToNext()
                            prepare()
                            play()
                        }
                        // 其他不可恢复错误：停在 IDLE
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
     * "空闲"判定：player == null / 队列空 / 处于 STATE_IDLE。
     * **暂停状态（playWhenReady=false 但已加载且 STATE_READY）也保留 service** ——
     * 之前用 playWhenReady 判，暂停一下再划掉就会被杀掉，用户从最近任务再点回来
     * 发现通知没了、状态丢了。MediaSessionService 父类的语义就是"暂停的会话也要持续可见"。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_IDLE
        ) {
            stopSelf()
        }
        // 在播 / 暂停可恢复 → 不调 stopSelf, 让前台 service 继续吃通知活
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
