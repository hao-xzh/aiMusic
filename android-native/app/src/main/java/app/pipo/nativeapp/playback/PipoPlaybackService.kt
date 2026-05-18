package app.pipo.nativeapp.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.PipoGraph

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
    private var notificationPlayer: RecoveringNotificationPlayer? = null
    private var smartAutoMixer: SmartAutoMixer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastKeepAlivePrepareAtMs: Long = 0L
    private var badSourceSkipToken: Long = 0L

    override fun onCreate() {
        super.onCreate()
        val cacheDataSourceFactory = PipoMediaDataSources.cacheFactory(this)

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

                addListener(object : Player.Listener {
                    override fun onMediaItemTransition(
                        mediaItem: androidx.media3.common.MediaItem?,
                        reason: Int,
                    ) {
                        DiagnosticsLogStore.record(
                            area = "playback_service",
                            event = "media_transition",
                            fields = mapOf(
                                "reason" to mediaTransitionReason(reason),
                                "mediaId" to mediaItem?.mediaId,
                                "title" to mediaItem?.mediaMetadata?.title?.toString(),
                            ),
                        )
                        badSourceSkipToken += 1L
                        smartAutoMixer?.onMediaItemTransition(mediaItem, reason)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // 控制层在线时仍优先由 PlayerViewModel 重签 URL；服务层只兜底坏源，
                        // 防止后台播放卡在同一个失效地址并触发前台服务崩溃。
                        Log.w("PipoPlayer", "playback error code=${error.errorCodeName}", error)
                        DiagnosticsLogStore.record(
                            area = "playback_service",
                            event = "player_error",
                            fields = playerFields(this@apply) + mapOf(
                                "code" to error.errorCodeName,
                                "message" to error.message,
                            ),
                        )
                        smartAutoMixer?.onMainPlayerError()
                        notificationPlayer?.armRecoveryWindow()
                        if (isLikelyBadSource(error)) {
                            scheduleBadSourceSkip(this@apply, error)
                        } else {
                            keepMediaNotificationAlive(this@apply, "error")
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        DiagnosticsLogStore.record(
                            area = "playback_service",
                            event = "state_changed",
                            fields = mapOf(
                                "state" to playbackStateName(playbackState),
                                "playWhenReady" to this@apply.playWhenReady,
                                "mediaItemCount" to this@apply.mediaItemCount,
                                "mediaId" to this@apply.currentMediaItem?.mediaId,
                            ),
                        )
                        smartAutoMixer?.onMainPlayerEvent()
                        when (playbackState) {
                            Player.STATE_IDLE -> keepMediaNotificationAlive(this@apply, "idle")
                            Player.STATE_BUFFERING,
                            Player.STATE_READY -> {
                                if (playWhenReady && mediaItemCount > 0) {
                                    notificationPlayer?.armRecoveryWindow()
                                    mediaSession?.let { session ->
                                        updateNotificationSafely(session, true, "state-$playbackState")
                                    }
                                }
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        smartAutoMixer?.onMainPlayerEvent()
                        if (isPlaying) {
                            notificationPlayer?.clearRecoveryWindow()
                            mediaSession?.let { session ->
                                updateNotificationSafely(session, true, "playing")
                            }
                        }
                    }
                })
            }
        smartAutoMixer = SmartAutoMixer(
            context = this,
            mainPlayer = player,
            dataSourceFactory = cacheDataSourceFactory,
            audioAttributes = musicAttrs,
            featuresStore = PipoGraph.audioFeaturesStore,
        )

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

        val sessionPlayer = RecoveringNotificationPlayer(player).also {
            notificationPlayer = it
        }
        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private fun keepMediaNotificationAlive(player: Player, reason: String) {
        if (!player.playWhenReady || player.mediaItemCount == 0) return
        val now = SystemClock.elapsedRealtime()
        val sessionPlayer = notificationPlayer ?: return
        if (!sessionPlayer.isRecovering()) return
        if (now - lastKeepAlivePrepareAtMs < KEEP_ALIVE_PREPARE_COOLDOWN_MS) return
        lastKeepAlivePrepareAtMs = now

        mainHandler.post {
            val session = mediaSession ?: return@post
            if (!player.playWhenReady || player.mediaItemCount == 0) return@post
            if (player.playbackState == Player.STATE_IDLE) {
                // Media3 默认通知在 IDLE 时会被 cancel。恢复期先 prepare 到 BUFFERING,
                // 保住前台媒体会话；真正换 URL / 跳坏歌仍由 PlayerViewModel 决定。
                runCatching { player.prepare() }
                    .onFailure { Log.w("PipoPlayer", "notification keep-alive prepare failed ($reason)", it) }
            }
            mainHandler.postDelayed({
                val liveSession = mediaSession ?: return@postDelayed
                if (player.playWhenReady &&
                    player.mediaItemCount > 0 &&
                    sessionPlayer.playbackState != Player.STATE_IDLE
                ) {
                    updateNotificationSafely(liveSession, true, "keep-alive-$reason")
                }
            }, KEEP_ALIVE_NOTIFICATION_DELAY_MS)
        }
    }

    private fun scheduleBadSourceSkip(player: Player, error: PlaybackException) {
        if (!player.playWhenReady || player.mediaItemCount <= 1) return
        val mediaId = player.currentMediaItem?.mediaId
        val token = ++badSourceSkipToken
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "bad_source_wait",
            fields = playerFields(player) + mapOf(
                "code" to error.errorCodeName,
                "graceMs" to BAD_SOURCE_CONTROLLER_GRACE_MS,
            ),
        )
        mainHandler.postDelayed({
            if (token != badSourceSkipToken) return@postDelayed
            if (!player.playWhenReady || player.currentMediaItem?.mediaId != mediaId) return@postDelayed
            if (player.playbackState != Player.STATE_IDLE) return@postDelayed
            val nextIndex = player.nextMediaItemIndex
            if (nextIndex == C.INDEX_UNSET || nextIndex == player.currentMediaItemIndex) return@postDelayed
            runCatching {
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "bad_source_skip",
                    fields = playerFields(player) + mapOf(
                        "code" to error.errorCodeName,
                        "nextIndex" to nextIndex,
                    ),
                )
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }.onFailure { err ->
                Log.w("PipoPlayer", "bad source skip failed", err)
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "bad_source_skip_failed",
                    fields = playerFields(player) + mapOf(
                        "errorType" to err::class.java.simpleName,
                        "message" to err.message,
                    ),
                )
            }
        }, BAD_SOURCE_CONTROLLER_GRACE_MS)
    }

    private fun updateNotificationSafely(
        session: MediaSession,
        startInForegroundRequired: Boolean,
        reason: String,
    ) {
        runCatching {
            onUpdateNotification(session, startInForegroundRequired)
        }.onFailure { err ->
            val foregroundDenied = isForegroundStartDenied(err)
            Log.w("PipoPlayer", "notification update failed ($reason)", err)
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = if (foregroundDenied) {
                    "notification_foreground_denied"
                } else {
                    "notification_update_failed"
                },
                fields = playerFields(session.player) + mapOf(
                    "reason" to reason,
                    "startForeground" to startInForegroundRequired,
                    "errorType" to err::class.java.simpleName,
                    "message" to err.message,
                ),
            )
            if (foregroundDenied && startInForegroundRequired) {
                runCatching { onUpdateNotification(session, false) }
                    .onFailure { fallback ->
                        DiagnosticsLogStore.record(
                            area = "playback_service",
                            event = "notification_fallback_failed",
                            fields = playerFields(session.player) + mapOf(
                                "reason" to reason,
                                "errorType" to fallback::class.java.simpleName,
                                "message" to fallback.message,
                            ),
                        )
                    }
            }
        }
    }

    private fun playerFields(player: Player): Map<String, Any?> {
        return mapOf(
            "mediaId" to player.currentMediaItem?.mediaId,
            "title" to player.currentMediaItem?.mediaMetadata?.title?.toString(),
            "state" to playbackStateName(player.playbackState),
            "playWhenReady" to player.playWhenReady,
            "mediaItemCount" to player.mediaItemCount,
            "positionMs" to player.currentPosition.coerceAtLeast(0L),
        )
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
        smartAutoMixer?.release()
        smartAutoMixer = null
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        notificationPlayer = null
        super.onDestroy()
    }

    private companion object {
        private const val KEEP_ALIVE_PREPARE_COOLDOWN_MS = 1_500L
        private const val KEEP_ALIVE_NOTIFICATION_DELAY_MS = 80L
        private const val BAD_SOURCE_CONTROLLER_GRACE_MS = 5_000L
    }
}

private fun isLikelyBadSource(error: PlaybackException): Boolean = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> true
    else -> false
}

private fun isForegroundStartDenied(error: Throwable): Boolean {
    val name = error::class.java.simpleName
    val message = error.message.orEmpty()
    return name == "ForegroundServiceStartNotAllowedException" ||
        message.contains("startForegroundService() not allowed", ignoreCase = true)
}

private fun mediaTransitionReason(reason: Int): String = when (reason) {
    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist_changed"
    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
    else -> reason.toString()
}

private fun playbackStateName(state: Int): String = when (state) {
    Player.STATE_IDLE -> "idle"
    Player.STATE_BUFFERING -> "buffering"
    Player.STATE_READY -> "ready"
    Player.STATE_ENDED -> "ended"
    else -> state.toString()
}
