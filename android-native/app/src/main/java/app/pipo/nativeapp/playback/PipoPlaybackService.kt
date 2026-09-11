package app.pipo.nativeapp.playback

import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.core.app.NotificationCompat
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.R
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.runtime.AppForeground
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale

/**
 * 镜像 src/lib/player-state.tsx 里的播放器配置：
 *   - 走 cache 优先的 HTTP DataSource（claudio-audio:// scheme 的 Android 等价物）
 *   - 系统级音频焦点 + 拔耳机自动暂停
 *   - 紧凑 buffer（gapless 接歌不延迟）
 *   - 队列循环 + Media3 自带的"曲尾无缝过渡"
 */
@UnstableApi
class PipoPlaybackService : MediaLibraryService() {
    private var mediaSession: MediaLibrarySession? = null
    private var notificationPlayer: RecoveringNotificationPlayer? = null
    private var smartAutoMixer: SmartAutoMixer? = null
    // 主 player 的响度对齐增益(衰减式 loudness normalization),挂在其 audio 链的 LoudnessGainProcessor 上
    private val playbackGain = PlaybackGain()
    // 辅助 player(实时 crossfade 的下一首淡入出声器)的独立响度增益
    private val auxPlaybackGain = PlaybackGain()
    private var crossfadeController: CrossfadeController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastKeepAlivePrepareAtMs: Long = 0L
    private var foregroundStartDeniedUntilMs: Long = 0L
    private var badSourceSkipToken: Long = 0L
    private var badSourceRecoveryMediaId: String? = null
    private var badSourceRecoveryUntilMs: Long = 0L
    // —— 静默缓冲卡顿兜底（后台 doze / 弱网下 BUFFERING 卡死又不报错时主动重踢）的状态 ——
    private var bufferStallToken: Long = 0L
    private var bufferStallScheduledForMediaId: String? = null
    private var bufferStallAttemptMediaId: String? = null
    private var bufferStallAttempts: Int = 0
    // —— 播放进度看门狗:不看 state(buffering/ready 抖动会骗过 buffer_stall),只看 position 是否"真前进" ——
    private var progressWatchdogToken: Long = 0L
    // 历史最大已播位置:只有超过它才算真前进。buffering 抖动里的倒退后小幅涨回**不算**,否则会反复
    // 清零重试计数 → 永不升级、无限重踢(Good Days 日志里 attempt 一直=1 的根因)。
    private var maxReachedPositionMs: Long = -1L
    private var lastProgressAtMs: Long = 0L
    private var maxBufferedPositionMs: Long = -1L
    private var lastBufferProgressAtMs: Long = 0L
    private var progressStallAttemptMediaId: String? = null
    private var progressStallAttempts: Int = 0
    private var progressWatchdogInternalSeekUntilMs: Long = 0L
    private var singleItemStallRetryAfterMs: Long = 0L
    private var lastTransientNetworkErrorAtMs: Long = 0L
    private var badSourceRefreshJob: Job? = null
    private var audioFocusResumeJob: Job? = null
    private var audioFocusForegroundExpiryJob: Job? = null
    private var autoResumeForegroundUntilMs = 0L
    private var playbackResumptionJob: Job? = null
    private var playbackResumptionEpoch = 0L
    private var resumptionForegroundStarted = false
    private var resumptionForegroundTimeoutJob: Job? = null
    private var audioFocusPauseJob: Job? = null
    private var resumeAfterAudioFocusLoss = false
    private var autoResumeState: AutoResumeState? = null
    private var nextAutoResumeEpoch = 0L
    private var hasAudioFocus = false
    private var waitingForAudioFocusGain = false
    private var externalAudioObservedSincePause = false
    private var externalAudioQuietSinceMs = 0L
    private var lastExternalFocusInterruptionAtMs = 0L
    private var lastExternalFocusChange: Int? = null
    private var externalFocusEpoch = 0L
    private var externalAudioPolicyKey: String? = null
    private var externalAudioPolicyStartedAtMs = 0L
    private var externalAudioPolicyEpoch = 0L
    private var currentExternalDuckingGain = 1f
    private var currentExternalDuckingRestoreQuietMs = EXTERNAL_AUDIO_DUCK_RESTORE_QUIET_MS
    private var currentExternalDuckingRestoreMs = EXTERNAL_AUDIO_DUCK_RESTORE_MS
    private var externalDuckingQuietSinceMs = 0L
    private var audioDuckingJob: Job? = null
    private var audioDuckingRestoreJob: Job? = null
    private var externalAudioPolicyProbeJob: Job? = null
    private var lastExternalPolicyLogKey: String? = null
    private var lastExternalPolicyLogAtMs = 0L
    private var lastExternalMisfireLogAtMs = 0L
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastAudioFocusRequestResult = AudioManager.AUDIOFOCUS_REQUEST_FAILED
    private var playbackCallbackRegistered = false
    /** 外部已静默后重新请求焦点的次数，用于诊断 OEM 不派发 GAIN 的恢复链。 */
    private var focusGainWaitProbes = 0
    private var trackCacheWarmer: TrackCacheWarmer? = null
    // —— 网络恢复回调:兜底重试放弃 / 等网络期间注册,网络一回来立刻续播。
    // 没有它,后台弱网卡死只能等用户回前台(前台那套 20s 自愈是 UI 帧驱动的)。 ——
    private var networkRecoveryArmed = false
    private var networkRecoveryArmAttempts = 0
    private var networkRecoveryArmRetryJob: Job? = null
    private val networkRecoveryCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.post { onNetworkRecovered("available") }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                mainHandler.post { onNetworkRecovered("validated") }
            }
        }
    }
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handleAudioFocusChange(focusChange)
        } else {
            mainHandler.post { handleAudioFocusChange(focusChange) }
        }
    }
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            handleExternalAudioPolicy("playback-callback", configs)
        }
    }
    private data class AutoResumeState(
        val epoch: Long,
        val mediaId: String,
        val cause: String,
        val blocksExternalMedia: Boolean,
        val blocksCommunicationMode: Boolean,
        val pendingInternalPauseCommand: Boolean,
    )

    private enum class ExternalAudioAction {
        Ignore,
        Duck,
        Pause,
    }

    private data class ExternalAudioProfile(
        val activeUsages: String,
        val rawActiveUsages: String,
        val inactiveConfigCount: Int,
        val ownUidConfigCount: Int,
        val ownExpectedMediaCount: Int,
        val externalMediaOrGameCount: Int,
        val hasExternalMediaOrGame: Boolean,
        val hasVoiceOrAssistant: Boolean,
        val hasNavigation: Boolean,
        val hasNotification: Boolean,
        val hasAlarmOrRingtone: Boolean,
    ) {
        val hasAnyExternalAudio: Boolean
            get() = hasExternalMediaOrGame ||
                hasVoiceOrAssistant ||
                hasNavigation ||
                hasNotification ||
                hasAlarmOrRingtone
    }

    private data class ExternalAudioPolicy(
        val action: ExternalAudioAction,
        val decision: String,
        val targetGain: Float = 1f,
        val attackMs: Long = 0L,
        val restoreQuietMs: Long = EXTERNAL_AUDIO_DUCK_RESTORE_QUIET_MS,
        val restoreMs: Long = EXTERNAL_AUDIO_DUCK_RESTORE_MS,
        val nextProbeMs: Long = 0L,
        val waitForAudioFocusGain: Boolean = false,
    )

    private val serviceUrlRefreshTried = LinkedHashSet<String>()
    private var serviceUrlRefreshReplacementMediaId: String? = null
    private val urlResolver by lazy {
        PlaybackUrlResolver(PipoGraph.repository, STREAM_LEVEL_FALLBACKS, STREAM_URL_TIMEOUT_MS)
    }
    private val mediaFactory by lazy {
        PlayerMediaFactory(PipoGraph.audioFeaturesStore)
    }
    private val libraryCallback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "library_root_requested",
                fields = mapOf("controller" to browser.packageName),
            )
            return Futures.immediateFuture(LibraryResult.ofItem(libraryRootItem(), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = when (mediaId) {
                LIBRARY_ROOT_ID -> libraryRootItem()
                CURRENT_QUEUE_ID -> currentQueueFolder(session.player)
                else -> currentQueueBrowserItems(session.player)
                    .firstOrNull { it.mediaId == mediaId }
            }
            return Futures.immediateFuture(
                item?.let { LibraryResult.ofItem(it, null) }
                    ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children = when (parentId) {
                LIBRARY_ROOT_ID -> listOf(currentQueueFolder(session.player))
                CURRENT_QUEUE_ID -> currentQueueBrowserItems(session.player)
                else -> emptyList()
            }
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "library_children_requested",
                fields = mapOf(
                    "controller" to browser.packageName,
                    "parentId" to parentId,
                    "count" to children.size,
                ),
            )
            return Futures.immediateFuture(LibraryResult.ofItemList(pagedItems(children, page, pageSize), params))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "library_search_requested",
                fields = mapOf("controller" to browser.packageName, "query" to query),
            )
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val trimmedQuery = query.trim()
            if (trimmedQuery.isBlank()) {
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(pagedItems(currentQueueBrowserItems(session.player), page, pageSize), params),
                )
            }
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                val items = withContext(Dispatchers.IO) {
                    runCatching {
                        PipoGraph.repository.searchTracks(trimmedQuery, ASSISTANT_SEARCH_LIMIT)
                    }.getOrDefault(emptyList())
                }.map(::metadataOnlyTrackItem)
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "library_search_result",
                    fields = mapOf(
                        "controller" to browser.packageName,
                        "query" to trimmedQuery,
                        "count" to items.size,
                    ),
                )
                future.set(LibraryResult.ofItemList(pagedItems(items, page, pageSize), params))
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (mediaItems.isEmpty()) return Futures.immediateFuture(mutableListOf())
            val future = SettableFuture.create<MutableList<MediaItem>>()
            serviceScope.launch {
                val resolved = runCatching {
                    resolveAssistantMediaItems(mediaItems)
                }.onFailure { err ->
                    DiagnosticsLogStore.record(
                        area = "playback_service",
                        event = "assistant_media_resolve_failed",
                        fields = mapOf(
                            "controller" to controller.packageName,
                            "requestedCount" to mediaItems.size,
                            "errorType" to err::class.java.simpleName,
                            "message" to err.message,
                        ),
                    )
                }.getOrDefault(mutableListOf())
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "assistant_media_resolved",
                    fields = mapOf(
                        "controller" to controller.packageName,
                        "requestedCount" to mediaItems.size,
                        "resolvedCount" to resolved.size,
                        "requestedItems" to mediaItems.mediaItemsSummary(),
                        "resolvedItems" to resolved.mediaItemsSummary(),
                    ),
                )
                future.set(resolved)
            }
            return future
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.isEmpty()) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs),
                )
            }
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val resolved = runCatching {
                    resolveAssistantMediaItems(mediaItems)
                }.onFailure { err ->
                    DiagnosticsLogStore.record(
                        area = "playback_service",
                        event = "assistant_media_set_resolve_failed",
                        fields = mapOf(
                            "controller" to controller.packageName,
                            "requestedCount" to mediaItems.size,
                            "startIndex" to startIndex,
                            "startPositionMs" to startPositionMs,
                            "requestedItems" to mediaItems.mediaItemsSummary(),
                            "errorType" to err::class.java.simpleName,
                            "message" to err.message,
                        ),
                    )
                }.getOrDefault(mutableListOf())
                val resolvedStartIndex = when {
                    resolved.isEmpty() -> C.INDEX_UNSET
                    startIndex == C.INDEX_UNSET -> 0
                    else -> startIndex.coerceIn(0, resolved.lastIndex)
                }
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "assistant_media_set_resolved",
                    fields = mapOf(
                        "controller" to controller.packageName,
                        "requestedCount" to mediaItems.size,
                        "resolvedCount" to resolved.size,
                        "startIndex" to startIndex,
                        "resolvedStartIndex" to resolvedStartIndex,
                        "startPositionMs" to startPositionMs,
                        "requestedItems" to mediaItems.mediaItemsSummary(),
                        "resolvedItems" to resolved.mediaItemsSummary(),
                    ),
                )
                future.set(MediaSession.MediaItemsWithStartPosition(resolved, resolvedStartIndex, startPositionMs))
            }
            return future
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val player = mediaSession.player
            if (player.mediaItemCount > 0) {
                return Futures.immediateFuture(currentPlaybackResumption(player))
            }
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            val resumptionEpoch = ++playbackResumptionEpoch
            playbackResumptionJob?.cancel()
            val job = serviceScope.launch {
                try {
                    val restored = withContext(Dispatchers.IO) {
                        withTimeout(PLAYBACK_RESUMPTION_TIMEOUT_MS) {
                            val snapshot = PipoGraph.lastPlayback.load() ?: return@withTimeout null
                            val resolved = urlResolver.resolvePlayableQueue(snapshot.queue)
                                .mapIndexedNotNull { index, track ->
                                    if (track.streamUrl.isBlank()) null else index to track
                                }
                            if (resolved.isEmpty()) return@withTimeout null
                            val originalIndex = resolved.indexOfFirst { it.first == snapshot.currentIndex }
                            val startIndex = originalIndex.takeIf { it >= 0 }
                                ?: resolved.indexOfFirst { it.first >= snapshot.currentIndex }.coerceAtLeast(0)
                            val positionMs = if (originalIndex >= 0) snapshot.positionMs else 0L
                            Triple(resolved.map { it.second }, startIndex, positionMs)
                        }
                    }
                    if (resumptionEpoch != playbackResumptionEpoch) {
                        future.cancel(false)
                        return@launch
                    }
                    // 网络解析期间 UI/其它控制器可能已经装上新队列，不能覆盖新选择。
                    if (player.mediaItemCount > 0 || restored == null) {
                        future.set(currentPlaybackResumption(player))
                        if (player.mediaItemCount == 0) finishResumptionForeground()
                        return@launch
                    }
                    val (tracks, startIndex, savedPositionMs) = restored
                    val items = tracks.map { mediaFactory.toMediaItem(it) }
                    val durationMs = tracks[startIndex].durationMs
                    val positionMs = if (durationMs > 0L && savedPositionMs >= durationMs - 1_000L) {
                        0L
                    } else {
                        savedPositionMs.coerceAtLeast(0L)
                    }
                    future.set(MediaSession.MediaItemsWithStartPosition(items, startIndex, positionMs))
                    DiagnosticsLogStore.record(
                        area = "playback_service",
                        event = "playback_snapshot_restored",
                        fields = mapOf("count" to items.size, "startIndex" to startIndex, "positionMs" to positionMs),
                    )
                } catch (error: CancellationException) {
                    future.cancel(false)
                    if (resumptionEpoch == playbackResumptionEpoch) finishResumptionForeground()
                    throw error
                } catch (error: Exception) {
                    future.setException(error)
                    if (resumptionEpoch == playbackResumptionEpoch) finishResumptionForeground()
                }
            }
            playbackResumptionJob = job
            armResumptionForegroundTimeout()
            future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
            job.invokeOnCompletion { if (it != null) future.cancel(false) }
            return future
        }
    }

    override fun onCreate() {
        super.onCreate()
        installMediaNotificationProvider()
        val cacheDataSourceFactory = PipoMediaDataSources.cacheFactory(this)

        val musicAttrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val platformMusicAttrs = android.media.AudioAttributes.Builder()
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .build()
        // 接管 duck 回调:通知/导航/语音/短视频要走 Pipo 自己的分级避让策略,
        // 但实际音量变化仍在 PCM gain 层做,不碰 crossfade 使用的 player.volume。
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(platformMusicAttrs)
            // 外部 app 正占用焦点时让系统把请求排队；失败/延迟都进入同一自动恢复状态机，
            // 不再把一次 focus failed 当成用户主动暂停而丢掉后台续播意图。
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
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
                    .setAudioProcessors(
                        arrayOf<AudioProcessor>(AmpAudioProcessor(), LoudnessGainProcessor(playbackGain)),
                    )
                    .build()
            }
        }

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setAudioAttributes(musicAttrs, /* handleAudioFocus = */ false)
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
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "service_player_created",
                    fields = mapOf(
                        "wakeMode" to "network",
                        "repeatMode" to repeatModeName(repeatMode),
                    ),
                )

                addListener(object : Player.Listener {
                    // 关键：SmartAutoMixer 的 tick 循环只有在"队列多于 1 首且在播"时才自持，且只能由
                    // onMainPlayerEvent 启动。两阶段启播下 onIsPlayingChanged/onPlaybackStateChanged
                    // 都发生在 phase-1 只有 1 首的瞬间（shouldKeepTicking=false，不排 tick）；phase-2 用
                    // addMediaItems 把队列补满时不会触发这两个回调，加上预热成功后的无缝换曲也不产生
                    // state 变化 —— 于是 mixer 整场再没机会启动 tick，表现为"每首都放到底再正常接下一首"。
                    // onEvents 在时间线变化/换曲/位置跳变后都会回调，用它兜底踢一次：队列补满那一刻
                    // tick 就起得来，随后靠 80ms 自循环持续到本场结束。幂等，无副作用。
                    override fun onEvents(player: Player, events: Player.Events) {
                        publishPreparationGate(player)
                        smartAutoMixer?.onMainPlayerEvent()
                        BackgroundAgentContinuation.onPlayerEvent()
                    }

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
                        val isUrlRefreshReplacement = updateServiceUrlRefreshTriedOnTransition(
                            mediaItem?.mediaId,
                            reason,
                        )
                        if (!isUrlRefreshReplacement) {
                            clearBadSourceRecovery()
                        }
                        if (autoResumeState?.mediaId != null &&
                            autoResumeState?.mediaId != mediaItem?.mediaId
                        ) {
                            clearAudioFocusAutoResume("media-transition")
                        }
                        // 换曲 = 旧的卡顿观察作废,新曲重置重踢预算
                        cancelBufferStallCheck()
                        resetBufferStallAttempts()
                        // 换曲(含 URL 重签 replace):丢掉旧 URI 的整曲预热。只有仍想播放时
                        // 才起新曲预热，暂停/避让期间不能让后台整曲下载抢播放带宽。
                        trackCacheWarmer?.cancel()
                        if (this@apply.playWhenReady) {
                            trackCacheWarmer?.maybeWarmCurrent(this@apply)
                        }
                        // 响度对齐:按新当前轨的整曲 rmsDb 设主 player 衰减增益。clip 的 mediaId
                        // ("automix:…")查不到 features → null → 中性(clip 已在 Rust 内部对齐)。
                        playbackGain.applyForRms(
                            mediaItem?.mediaId?.let { PipoGraph.audioFeaturesStore.get(it)?.rmsDb },
                        )
                        // 新曲:重置看门狗的进度基线(别拿上一首的 position 误判)
                        if (this@apply.playWhenReady) armProgressWatchdog(this@apply)
                        smartAutoMixer?.onMediaItemTransition(mediaItem, reason)
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) {
                        // seek（含歌词点跳回放旧段）会把 position 拉回历史最高水位之下。
                        // 进度看门狗的基线"只认创新高"，不随 seek 重置的话，重放旧区间会被
                        // 误判为无前进 → 触发内部恢复 seek：音频打嗝、isPlaying 抖动、
                        // 歌词扫色跳段，直到重新越过旧水位才停。真实卡死不受影响：
                        // 内部恢复 seek 不重置基线，位置仍冻结时下一轮照样继续恢复。
                        if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                            reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                        ) {
                            if (isProgressWatchdogInternalSeek()) return
                            if (this@apply.playWhenReady) armProgressWatchdog(this@apply)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // 错误恢复唯一权威在服务层(后台也活着)。ViewModel 只记日志不插手,
                        // 否则两层对同一错误各自 replace+seek+prepare,一次故障打断两次。
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
                        if (isLikelyTransientNetworkError(error)) {
                            lastTransientNetworkErrorAtMs = SystemClock.elapsedRealtime()
                            // 网络类错误:挂上网络恢复回调,网络一回来立刻重踢续播
                            armNetworkRecovery("transient-error")
                        }
                        if (isLikelyBadSource(error)) {
                            recoverBadSourceOrSkip(this@apply, error)
                        } else {
                            keepMediaNotificationAlive(this@apply, "error")
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        publishPreparationGate(this@apply)
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
                            Player.STATE_IDLE -> {
                                cancelBufferStallCheck()
                                trackCacheWarmer?.cancel()
                                keepMediaNotificationAlive(this@apply, "idle")
                            }
                            Player.STATE_ENDED -> {
                                cancelBufferStallCheck()
                                trackCacheWarmer?.cancel()
                            }
                            Player.STATE_BUFFERING,
                            Player.STATE_READY -> {
                                if (playWhenReady && mediaItemCount > 0) {
                                    notificationPlayer?.armRecoveryWindow()
                                    mediaSession?.let { session ->
                                        updateNotificationSafely(session, true, "state-$playbackState")
                                    }
                                }
                                if (playbackState == Player.STATE_READY) {
                                    clearBadSourceRecovery(this@apply.currentMediaItem?.mediaId)
                                    // 到 READY = 这一刻加载是通的:撤掉待检查、重置该首的重踢预算。
                                    cancelBufferStallCheck()
                                    resetBufferStallAttempts()
                                    if (playWhenReady) {
                                        // 开始整曲灌缓存(幂等):整曲落盘后当前曲播放彻底脱离网络
                                        trackCacheWarmer?.maybeWarmCurrent(this@apply)
                                    } else {
                                        trackCacheWarmer?.cancel()
                                    }
                                } else if (playWhenReady && mediaItemCount > 0) {
                                    // 播放已重新进入 BUFFERING，先把带宽全部让给播放器。
                                    trackCacheWarmer?.cancel()
                                    // 进入 BUFFERING 且想播 —— 排一个延时检查,后台静默卡死时兜底重踢。
                                    scheduleBufferStallCheck(this@apply)
                                } else {
                                    trackCacheWarmer?.cancel()
                                }
                            }
                        }
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        publishPreparationGate(this@apply)
                        DiagnosticsLogStore.record(
                            area = "playback_service",
                            event = "play_when_ready_changed",
                            fields = playerFields(this@apply) + mapOf(
                                "playWhenReady" to playWhenReady,
                                "reason" to playWhenReadyReason(reason),
                            ),
                        )
                        // 进度看门狗随"想播"开关:想播就盯,暂停就歇(playWhenReady 不受 buffering/ready 抖动影响)
                        if (playWhenReady) {
                            armProgressWatchdog(this@apply)
                        } else {
                            cancelProgressWatchdog()
                            trackCacheWarmer?.cancel()
                            disarmNetworkRecovery()
                        }
                        if (playWhenReady) {
                            // Android 15+ 后台申请焦点之前必须已有前台服务。
                            notificationPlayer?.armRecoveryWindow()
                            mediaSession?.let { session ->
                                updateNotificationSafely(session, true, "play-when-ready")
                            }
                            if (!requestAudioFocusForPlayback("play-when-ready")) {
                                pauseForAudioFocusRequestDenial(this@apply)
                                updatePlaybackCallbackRegistration(this@apply)
                                return
                            }
                            if (this@apply.playbackState == Player.STATE_READY) {
                                trackCacheWarmer?.maybeWarmCurrent(this@apply)
                            }
                        }
                        when (reason) {
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> {
                                if (!playWhenReady) {
                                    if (autoResumeStateFor(this@apply) == null) {
                                        armAudioFocusAutoResume(
                                            player = this@apply,
                                            reason = "audio-focus-loss",
                                            observedExternalAudio = true,
                                            waitForAudioFocusGain = true,
                                            blocksExternalMedia = true,
                                            blocksCommunicationMode = isInCommunicationMode(),
                                        )
                                    } else {
                                        confirmPendingAutoPauseCallback(this@apply, "audio-focus-loss")
                                    }
                                }
                            }
                            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> {
                                if (!playWhenReady && !confirmPendingAutoPauseCallback(
                                        this@apply,
                                        "user-request-callback",
                                    )
                                ) {
                                    clearAudioFocusAutoResume("play-when-ready-${playWhenReadyReason(reason)}")
                                    abandonAudioFocus("play-when-ready-${playWhenReadyReason(reason)}")
                                } else {
                                    if (playWhenReady) {
                                        clearAudioFocusAutoResume("play-when-ready-${playWhenReadyReason(reason)}")
                                    }
                                }
                            }
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> {
                                if (!playWhenReady) {
                                    clearAudioFocusAutoResume("audio-becoming-noisy")
                                    abandonAudioFocus("audio-becoming-noisy")
                                }
                            }
                            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> {
                                if (!playWhenReady) {
                                    clearAudioFocusAutoResume("play-when-ready-${playWhenReadyReason(reason)}")
                                    abandonAudioFocus("play-when-ready-${playWhenReadyReason(reason)}")
                                }
                            }
                        }
                        updatePlaybackCallbackRegistration(this@apply)
                    }

                    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                        if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                            maybeResumeAfterExternalAudioStops("suppression-cleared")
                            return
                        }
                        DiagnosticsLogStore.record(
                            area = "playback_service",
                            event = "playback_suppressed",
                            fields = playerFields(this@apply) + mapOf(
                                "reason" to playbackSuppressionReasonName(playbackSuppressionReason),
                            ),
                        )
                        if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                            if (this@apply.playWhenReady || this@apply.isPlaying) {
                                armAudioFocusAutoResume(
                                    player = this@apply,
                                    reason = "transient-audio-focus-loss",
                                    observedExternalAudio = true,
                                    waitForAudioFocusGain = true,
                                    blocksExternalMedia = true,
                                    blocksCommunicationMode = isInCommunicationMode(),
                                )
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        publishPreparationGate(this@apply)
                        smartAutoMixer?.onMainPlayerEvent()
                        DiagnosticsLogStore.record(
                            area = "playback_service",
                            event = "is_playing_changed",
                            fields = playerFields(this@apply) + mapOf("isPlaying" to isPlaying),
                        )
                        if (isPlaying) {
                            requestAudioFocusForPlayback("is-playing")
                            clearAudioFocusAutoResume("playing")
                            disarmNetworkRecovery()
                            notificationPlayer?.clearRecoveryWindow()
                            mediaSession?.let { session ->
                                updateNotificationSafely(session, true, "playing")
                            }
                        }
                        updatePlaybackCallbackRegistration(this@apply)
                        if (isPlaying) {
                            handleExternalAudioPolicy("is-playing")
                        }
                    }
                })
            }
        // —— 实时 crossfade 的辅助 player(B):只在 crossfade 期间播下一首头段做淡入 ——
        // handleAudioFocus=false(蹭主 player 的 focus,同 app 出声);只挂响度增益、不挂
        // AmpAudioProcessor(Amp 是全局视觉 RMS,辅助 player 不应干扰主视觉)。
        val auxRenderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(LoudnessGainProcessor(auxPlaybackGain)))
                    .build()
            }
        }
        val auxPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(auxRenderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setAudioAttributes(musicAttrs, /* handleAudioFocus = */ false)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(12_000, 20_000, 500, 1_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .build()
            .apply { setWakeMode(C.WAKE_MODE_NETWORK) }
        crossfadeController = CrossfadeController(
            mainPlayer = player,
            auxPlayer = auxPlayer,
            auxGain = auxPlaybackGain,
            onResult = { result -> smartAutoMixer?.onRealtimeCrossfadeResult(result) },
        )

        smartAutoMixer = SmartAutoMixer(
            mainPlayer = player,
            featuresStore = PipoGraph.audioFeaturesStore,
            crossfadeController = crossfadeController,
        )

        trackCacheWarmer = TrackCacheWarmer(this, serviceScope)
        BackgroundAgentContinuation.bind(serviceScope, player, urlResolver, mediaFactory)

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

        val sessionPlayer = RecoveringNotificationPlayer(
            player = player,
            onPlayWhenReadyCommand = ::handleSessionPlayWhenReadyCommand,
            onStopCommand = ::handleSessionStopCommand,
        ).also {
            notificationPlayer = it
        }
        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, libraryCallback)
            .setSessionActivity(sessionActivity)
            .build()
        monitorForegroundPromotionRetry()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 耳机按键可通过 startForegroundService 冷启动。先展示真实的恢复中媒体通知，
        // 再异步解析已保存队列，不能把系统的前台启动时限耗在网络请求上。
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON &&
            !isPlaybackOngoing && mediaSession?.player?.mediaItemCount == 0
        ) {
            val started = runCatching { startResumptionForeground() }.onFailure { error ->
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "resumption_foreground_failed",
                    fields = mapOf("errorType" to error::class.java.simpleName, "message" to error.message),
                )
            }
            if (started.isFailure) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startResumptionForeground() {
        val session = mediaSession ?: return
        if (resumptionForegroundStarted) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(RESUMPTION_CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(this, RESUMPTION_CHANNEL_ID)
            .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
            .setContentTitle(getString(R.string.app_name))
            .setContentIntent(session.sessionActivity)
            .setStyle(MediaStyleNotificationHelper.MediaStyle(session))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(RESUMPTION_NOTIFICATION_ID, notification)
        resumptionForegroundStarted = true
        armResumptionForegroundTimeout()
    }

    private fun armResumptionForegroundTimeout() {
        if (!resumptionForegroundStarted) return
        resumptionForegroundTimeoutJob?.cancel()
        val resumptionEpoch = playbackResumptionEpoch
        resumptionForegroundTimeoutJob = serviceScope.launch {
            delay(PLAYBACK_RESUMPTION_TIMEOUT_MS)
            if (!resumptionForegroundStarted || resumptionEpoch != playbackResumptionEpoch) return@launch
            playbackResumptionJob?.cancel()
            finishResumptionForeground()
        }
    }

    private fun finishResumptionForeground() {
        if (!resumptionForegroundStarted) return
        resumptionForegroundStarted = false
        resumptionForegroundTimeoutJob?.cancel()
        resumptionForegroundTimeoutJob = null
        // Media3 接管后仅移除启动通知，不能停止它刚刚建立的前台身份。
        if (!isPlaybackOngoing) stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(RESUMPTION_NOTIFICATION_ID)
        if (!isPlaybackOngoing && mediaSession?.player?.mediaItemCount == 0) stopSelf()
    }

    private fun retainForegroundForAutoResume(player: Player): Boolean {
        return isPlaybackOngoing && autoResumeStateFor(player) != null &&
            SystemClock.elapsedRealtime() < autoResumeForegroundUntilMs
    }

    private fun installMediaNotificationProvider() {
        val delegate = DefaultMediaNotificationProvider.Builder(this).build()
        var generation = 0L
        setMediaNotificationProvider(object : MediaNotification.Provider by delegate {
            override fun createNotification(
                session: MediaSession,
                mediaButtonPreferences: ImmutableList<CommandButton>,
                actionFactory: MediaNotification.ActionFactory,
                onNotificationChangedCallback: MediaNotification.Provider.Callback,
            ): MediaNotification {
                val expectedGeneration = ++generation
                return delegate.createNotification(session, mediaButtonPreferences, actionFactory) { notification ->
                    if (generation == expectedGeneration && mediaSession === session) {
                        if (retainForegroundForAutoResume(session.player)) {
                            // Media3 1.5.1 的异步封面回调绕过 service override，会按暂停态降级。
                            // 宽限期间只更新通知内容，不改变已存在的前台服务身份。
                            notification.notification.extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, session.platformToken)
                            getSystemService(NotificationManager::class.java)
                                .notify(notification.notificationId, notification.notification)
                        } else {
                            onNotificationChangedCallback.onNotificationChanged(notification)
                        }
                    }
                }
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    private fun currentPlaybackResumption(player: Player): MediaSession.MediaItemsWithStartPosition {
        val items = currentQueuePlayableItems(player)
        val startIndex = if (items.isEmpty()) C.INDEX_UNSET else player.currentMediaItemIndex.coerceIn(0, items.lastIndex)
        val positionMs = if (items.isEmpty()) C.TIME_UNSET else player.currentPosition.coerceAtLeast(0L)
        return MediaSession.MediaItemsWithStartPosition(items, startIndex, positionMs)
    }

    private fun libraryRootItem(): MediaItem {
        return browserFolder(
            mediaId = LIBRARY_ROOT_ID,
            title = "Pipo",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        )
    }

    private fun currentQueueFolder(player: Player): MediaItem {
        val count = player.mediaItemCount
        val title = if (count > 0) "当前播放列表" else "Pipo 音乐"
        return browserFolder(
            mediaId = CURRENT_QUEUE_ID,
            title = title,
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
        )
    }

    private fun browserFolder(mediaId: String, title: String, mediaType: Int): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun currentQueueBrowserItems(player: Player): List<MediaItem> {
        return currentQueuePlayableItems(player).map(::decoratePlayableBrowserItem)
    }

    private fun currentQueuePlayableItems(player: Player): List<MediaItem> {
        return (0 until player.mediaItemCount).map { index -> player.getMediaItemAt(index) }
    }

    private fun decoratePlayableBrowserItem(item: MediaItem): MediaItem {
        return item.buildUpon()
            .setMediaMetadata(playableMetadata(item.mediaMetadata))
            .build()
    }

    private fun metadataOnlyTrackItem(track: NativeTrack): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(secureArtworkUri(track.artworkUrl))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun playableMetadata(metadata: MediaMetadata): MediaMetadata {
        return metadata.buildUpon()
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
    }

    private fun secureArtworkUri(url: String?): Uri? {
        val value = url?.takeIf { it.isNotBlank() } ?: return null
        val secureUrl = if (value.startsWith("http://")) {
            value.replaceFirst("http://", "https://")
        } else {
            value
        }
        return runCatching { Uri.parse(secureUrl) }.getOrNull()
    }

    private fun pagedItems(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (page < 0 || pageSize <= 0) return emptyList()
        val from = page.toLong() * pageSize.toLong()
        if (from > Int.MAX_VALUE || from >= items.size) return emptyList()
        val start = from.toInt()
        val end = (start + pageSize).coerceAtMost(items.size)
        return items.subList(start, end)
    }

    private suspend fun resolveAssistantMediaItems(mediaItems: List<MediaItem>): MutableList<MediaItem> {
        val resolved = mutableListOf<MediaItem>()
        for (item in mediaItems) {
            resolveAssistantMediaItem(item)?.let { resolved += it }
        }
        return resolved
    }

    private suspend fun resolveAssistantMediaItem(item: MediaItem): MediaItem? {
        if (item.localConfiguration?.uri != null) {
            return decoratePlayableBrowserItem(item)
        }

        findCurrentQueueMediaItem(item.mediaId)?.let { queueItem ->
            if (queueItem.localConfiguration?.uri != null) return decoratePlayableBrowserItem(queueItem)
        }

        item.requestMetadata.mediaUri?.let { mediaUri ->
            return item.buildUpon()
                .setUri(mediaUri)
                .setMediaMetadata(playableMetadata(item.mediaMetadata))
                .build()
        }

        mediaIdTrackId(item.mediaId)?.let { trackId ->
            val fresh = withContext(Dispatchers.IO) {
                urlResolver.fetchPlayable(trackId)
            }
            if (fresh != null) {
                return item.buildUpon()
                    .setMediaId(trackId.toString())
                    .setUri(fresh.url)
                    .setCustomCacheKey(fresh.cacheKey)
                    .setMediaMetadata(playableMetadata(item.mediaMetadata))
                    .build()
            }
        }

        val query = queryFromRequestedItem(item) ?: return null
        val candidates = withContext(Dispatchers.IO) {
            val candidates = runCatching {
                PipoGraph.repository.searchTracks(query, ASSISTANT_SEARCH_LIMIT)
            }.getOrDefault(emptyList())
            assistantSearchCandidates(query, candidates)
        }
        if (candidates.isEmpty()) {
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "assistant_media_search_rejected",
                fields = mapOf(
                    "query" to query,
                    "requestedItem" to item.mediaItemSummary(),
                ),
            )
            return null
        }
        val track = urlResolver.resolveFirstPlayable(candidates, ASSISTANT_PLAY_SCAN_LIMIT) ?: return null
        return mediaFactory.toMediaItem(track)
    }

    private fun assistantSearchCandidates(query: String, candidates: List<NativeTrack>): List<NativeTrack> {
        val normalizedQuery = normalizeAssistantSearchText(query)
        val queryTokens = assistantSearchTokens(normalizedQuery)
        if (normalizedQuery.isBlank() && queryTokens.isEmpty()) return emptyList()
        return candidates.filter { track ->
            assistantQueryMatchesTrack(normalizedQuery, queryTokens, track)
        }
    }

    private fun assistantQueryMatchesTrack(
        normalizedQuery: String,
        queryTokens: List<String>,
        track: NativeTrack,
    ): Boolean {
        val title = normalizeAssistantSearchText(track.title)
        val artist = normalizeAssistantSearchText(track.artist)
        val album = normalizeAssistantSearchText(track.album)
        val combined = listOf(title, artist, album)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (combined.isBlank()) return false
        if (normalizedQuery.isNotBlank() && combined.contains(normalizedQuery)) return true
        if (title.isNotBlank() && normalizedQuery.isNotBlank()) {
            if (title == normalizedQuery) return true
            if (title.contains(normalizedQuery) || normalizedQuery.contains(title)) return true
            if (assistantCloseEnough(normalizedQuery, title)) return true
        }
        if (queryTokens.isEmpty()) return false
        val trackTokens = assistantSearchTokens(combined)
        return queryTokens.all { token ->
            combined.contains(token) || trackTokens.any { candidate -> assistantCloseEnough(token, candidate) }
        }
    }

    private fun normalizeAssistantSearchText(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return buildString(value.length) {
            value.lowercase(Locale.ROOT).forEach { ch ->
                append(if (ch.isLetterOrDigit()) ch else ' ')
            }
        }.trim().replace(Regex("\\s+"), " ")
    }

    private fun assistantSearchTokens(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return value.split(' ')
            .map { it.trim() }
            .filter { it.length >= 2 && it !in ASSISTANT_QUERY_STOP_WORDS }
    }

    private fun assistantCloseEnough(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) return false
        val maxLen = maxOf(left.length, right.length)
        val allowedDistance = when {
            maxLen <= 5 -> 1
            maxLen <= 12 -> 2
            else -> 3
        }
        if (kotlin.math.abs(left.length - right.length) > allowedDistance) return false
        return boundedEditDistance(left, right, allowedDistance) <= allowedDistance
    }

    private fun boundedEditDistance(left: String, right: String, maxDistance: Int): Int {
        if (left == right) return 0
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost,
                )
                rowMin = minOf(rowMin, current[j])
            }
            if (rowMin > maxDistance) return maxDistance + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private fun List<MediaItem>.mediaItemsSummary(): String {
        return take(4).joinToString(" | ") { it.mediaItemSummary() }
    }

    private fun MediaItem.mediaItemSummary(): String {
        val title = mediaMetadata.title?.toString().orEmpty()
        val artist = mediaMetadata.artist?.toString().orEmpty()
        val query = requestMetadata.searchQuery.orEmpty()
        return listOf(
            mediaId.takeIf { it.isNotBlank() }?.let { "id=$it" },
            title.takeIf { it.isNotBlank() }?.let { "title=$it" },
            artist.takeIf { it.isNotBlank() }?.let { "artist=$it" },
            query.takeIf { it.isNotBlank() }?.let { "query=$it" },
        ).filterNotNull().joinToString(",").take(220)
    }

    private fun findCurrentQueueMediaItem(mediaId: String): MediaItem? {
        if (mediaId.isBlank()) return null
        val player = mediaSession?.player ?: return null
        return (0 until player.mediaItemCount)
            .firstNotNullOfOrNull { index ->
                player.getMediaItemAt(index).takeIf { it.mediaId == mediaId }
            }
    }

    private fun mediaIdTrackId(mediaId: String): Long? {
        if (mediaId.isBlank()) return null
        val raw = mediaId.removePrefix(TRACK_MEDIA_ID_PREFIX)
        return raw.toLongOrNull()
    }

    private fun queryFromRequestedItem(item: MediaItem): String? {
        item.requestMetadata.searchQuery
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return listOfNotNull(
            item.mediaMetadata.title?.toString(),
            item.mediaMetadata.artist?.toString(),
            item.mediaMetadata.albumTitle?.toString(),
        )
            .joinToString(" ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun keepMediaNotificationAlive(player: Player, reason: String) {
        if (!player.playWhenReady || player.mediaItemCount == 0) return
        val now = SystemClock.elapsedRealtime()
        val sessionPlayer = notificationPlayer ?: return
        if (!sessionPlayer.isRecovering()) return
        if (isWaitingForBadSourceController(player, now)) {
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "keep_alive_bad_source_refresh",
                fields = playerFields(player) + mapOf("reason" to reason),
            )
            sessionPlayer.armRecoveryWindow()
            if (now - lastKeepAlivePrepareAtMs >= KEEP_ALIVE_PREPARE_COOLDOWN_MS) {
                lastKeepAlivePrepareAtMs = now
                mainHandler.post {
                    val liveSession = mediaSession ?: return@post
                    if (player.playWhenReady && player.mediaItemCount > 0) {
                        updateNotificationSafely(liveSession, true, "keep-alive-$reason-bad-source")
                    }
                }
            }
            return
        }
        if (now - lastKeepAlivePrepareAtMs < KEEP_ALIVE_PREPARE_COOLDOWN_MS) return
        lastKeepAlivePrepareAtMs = now

        mainHandler.post {
            val session = mediaSession ?: return@post
            if (!player.playWhenReady || player.mediaItemCount == 0) return@post
            if (player.playbackState == Player.STATE_IDLE) {
                // Media3 默认通知在 IDLE 时会被 cancel。恢复期先 prepare 到 BUFFERING,
                // 保住前台媒体会话；真正换 URL / 跳坏歌由服务端坏源恢复链路决定。
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "keep_alive_prepare",
                    fields = playerFields(player) + mapOf("reason" to reason),
                )
                runCatching { player.prepare() }
                    .onFailure { err ->
                        DiagnosticsLogStore.record(
                            area = "playback_service",
                            event = "keep_alive_prepare_failed",
                            fields = playerFields(player) + mapOf(
                                "reason" to reason,
                                "errorType" to err::class.java.simpleName,
                                "message" to err.message,
                            ),
                        )
                    }
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

    private fun armAudioFocusAutoResume(
        player: Player,
        reason: String,
        observedExternalAudio: Boolean = false,
        waitForAudioFocusGain: Boolean = false,
        blocksExternalMedia: Boolean = false,
        blocksCommunicationMode: Boolean = false,
        pendingInternalPauseCommand: Boolean = false,
    ) {
        if (player.mediaItemCount == 0 || player.currentMediaItem == null) return
        val epoch = ++nextAutoResumeEpoch
        autoResumeState = AutoResumeState(
            epoch = epoch,
            mediaId = player.currentMediaItem?.mediaId.orEmpty(),
            cause = reason,
            blocksExternalMedia = blocksExternalMedia,
            blocksCommunicationMode = blocksCommunicationMode,
            pendingInternalPauseCommand = pendingInternalPauseCommand,
        )
        resumeAfterAudioFocusLoss = true
        autoResumeForegroundUntilMs = SystemClock.elapsedRealtime() + AUDIO_FOCUS_FOREGROUND_GRACE_MS
        audioFocusForegroundExpiryJob?.cancel()
        audioFocusForegroundExpiryJob = serviceScope.launch {
            delay(AUDIO_FOCUS_FOREGROUND_GRACE_MS)
            if (autoResumeState?.epoch != epoch) return@launch
            autoResumeForegroundUntilMs = 0L
            mediaSession?.let { session ->
                updateNotificationSafely(session, session.player.playWhenReady, "focus-grace-expired")
            }
        }
        waitingForAudioFocusGain = waitForAudioFocusGain
        focusGainWaitProbes = 0
        externalAudioObservedSincePause = observedExternalAudio
        externalAudioQuietSinceMs = 0L
        registerPlaybackCallback()
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "audio_focus_auto_resume_armed",
            fields = playerFields(player) + mapOf(
                "reason" to reason,
                "epoch" to epoch,
                "observedExternalAudio" to externalAudioObservedSincePause,
                "waitForAudioFocusGain" to waitingForAudioFocusGain,
                "blocksExternalMedia" to blocksExternalMedia,
                "blocksCommunicationMode" to blocksCommunicationMode,
                "pendingInternalPauseCommand" to pendingInternalPauseCommand,
            ),
        )
        audioFocusResumeJob?.cancel()
        scheduleAudioFocusResumeProbe(AUDIO_FOCUS_RESUME_PROBE_MS, "delayed-probe", epoch)
    }

    private fun scheduleAudioFocusResumeProbe(
        delayMs: Long,
        reason: String,
        epoch: Long? = autoResumeState?.epoch,
    ) {
        val expectedEpoch = epoch ?: return
        audioFocusResumeJob?.cancel()
        audioFocusResumeJob = serviceScope.launch {
            delay(delayMs)
            if (autoResumeState?.epoch != expectedEpoch) return@launch
            maybeResumeAfterExternalAudioStops(reason)
        }
    }

    private fun scheduleAudioFocusPauseProbe(reason: String, epoch: Long = externalFocusEpoch) {
        audioFocusPauseJob?.cancel()
        audioFocusPauseJob = serviceScope.launch {
            delay(AUDIO_FOCUS_POLICY_CONFIRM_MS)
            if (externalFocusEpoch != epoch || lastExternalFocusChange == null) return@launch
            handleExternalAudioPolicy("audio-focus-$reason")
        }
    }

    private fun autoResumeStateFor(player: Player): AutoResumeState? {
        val state = autoResumeState ?: return null
        return state.takeIf { it.mediaId == player.currentMediaItem?.mediaId }
    }

    private fun confirmPendingAutoPauseCallback(player: Player, reason: String): Boolean {
        val state = autoResumeStateFor(player) ?: return false
        if (state.pendingInternalPauseCommand) {
            autoResumeState = state.copy(pendingInternalPauseCommand = false)
        }
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "external_audio_auto_pause_confirmed",
            fields = playerFields(player) + mapOf(
                "reason" to reason,
                "cause" to state.cause,
                "epoch" to state.epoch,
            ),
        )
        return true
    }

    /** MediaSession/锁屏/智能代理发来的显式控制命令优先级高于自动恢复。 */
    private fun handleSessionPlayWhenReadyCommand(playWhenReady: Boolean) {
        val player = mediaSession?.player ?: return
        if (playWhenReady) {
            if (autoResumeState != null) {
                clearAudioFocusAutoResume("session-play-command")
            }
            return
        }
        val state = autoResumeStateFor(player)
        if (state?.pendingInternalPauseCommand == true) {
            autoResumeState = state.copy(pendingInternalPauseCommand = false)
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "external_audio_internal_pause_command",
                fields = playerFields(player) + mapOf(
                    "cause" to state.cause,
                    "epoch" to state.epoch,
                ),
            )
            return
        }
        playbackResumptionEpoch += 1L
        playbackResumptionJob?.cancel()
        finishResumptionForeground()
        // 即使 player 已处于暂停、不会再产生状态回调，也能在这里识别用户/智能控制器
        // 的明确 pause，保证之后绝不被旧的 quiet probe 自动拉起。
        clearAudioFocusAutoResume("session-pause-command")
        abandonAudioFocus("session-pause-command")
        trackCacheWarmer?.cancel()
    }

    private fun handleSessionStopCommand() {
        playbackResumptionEpoch += 1L
        playbackResumptionJob?.cancel()
        finishResumptionForeground()
        clearAudioFocusAutoResume("session-stop-command")
        abandonAudioFocus("session-stop-command")
        trackCacheWarmer?.cancel()
        disarmNetworkRecovery()
        clearBadSourceRecovery()
        cancelBufferStallCheck()
        cancelProgressWatchdog()
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "session_stop_command",
            fields = mediaSession?.player?.let(::playerFields).orEmpty(),
        )
    }

    private fun pauseForAudioFocusRequestDenial(player: Player) {
        val commandPlayer = mediaSession?.player ?: player
        val profile = externalAudioProfile(audioManager.activePlaybackConfigurations)
        armAudioFocusAutoResume(
            player = commandPlayer,
            reason = "focus-request-denied",
            observedExternalAudio = true,
            waitForAudioFocusGain = true,
            blocksExternalMedia = true,
            blocksCommunicationMode = isInCommunicationMode(),
            pendingInternalPauseCommand = true,
        )
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "audio_focus_request_deferred_pause",
            fields = playerFields(commandPlayer) + mapOf(
                "activeUsages" to profile.activeUsages,
                "blocksExternalMedia" to profile.hasExternalMediaOrGame,
                "communicationMode" to isInCommunicationMode(),
            ),
        )
        runCatching { commandPlayer.pause() }
            .onFailure { err ->
                clearAudioFocusAutoResume("focus-request-pause-failed")
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "audio_focus_request_deferred_pause_failed",
                    fields = playerFields(commandPlayer) + mapOf(
                        "errorType" to err::class.java.simpleName,
                        "message" to err.message,
                    ),
                )
            }
    }

    private fun updatePlaybackCallbackRegistration(player: Player? = mediaSession?.player) {
        val shouldMonitor = resumeAfterAudioFocusLoss ||
            currentExternalDuckingGain < 0.999f ||
            player?.playWhenReady == true ||
            player?.isPlaying == true
        if (shouldMonitor) {
            registerPlaybackCallback()
        } else {
            unregisterPlaybackCallback()
        }
    }

    private fun registerPlaybackCallback() {
        if (playbackCallbackRegistered) return
        runCatching {
            audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler)
            playbackCallbackRegistered = true
        }.onFailure { err ->
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "audio_playback_callback_register_failed",
                fields = mapOf(
                    "errorType" to err::class.java.simpleName,
                    "message" to err.message,
                ),
            )
        }
    }

    private fun unregisterPlaybackCallback() {
        if (!playbackCallbackRegistered) return
        runCatching {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        }
        playbackCallbackRegistered = false
    }

    private fun requestAudioFocusForPlayback(reason: String): Boolean {
        if (Build.VERSION.SDK_INT >= 35 && !AppForeground.isForeground.value &&
            !isPlaybackOngoing && !resumptionForegroundStarted
        ) {
            val session = mediaSession ?: return false
            if (session.player.mediaItemCount == 0) return false
            if (session.player.playbackState == Player.STATE_IDLE) {
                session.player.prepare()
            }
            if (!updateNotificationSafely(session, true, "before-audio-focus-$reason")) {
                lastAudioFocusRequestResult = AudioManager.AUDIOFOCUS_REQUEST_FAILED
                return false
            }
        }
        if (hasAudioFocus) {
            lastAudioFocusRequestResult = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return true
        }
        val request = audioFocusRequest ?: return true
        val result = audioManager.requestAudioFocus(request)
        lastAudioFocusRequestResult = result
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasAudioFocus = granted
        waitingForAudioFocusGain = result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        if (granted) {
            lastExternalFocusChange = null
            resetExternalAudioPolicyWindow()
        }
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "audio_focus_request",
            fields = mapOf(
                "reason" to reason,
                "result" to audioFocusRequestResultName(result),
            ),
        )
        return granted
    }

    private fun abandonAudioFocus(reason: String) {
        val request = audioFocusRequest ?: return
        runCatching { audioManager.abandonAudioFocusRequest(request) }
        hasAudioFocus = false
        waitingForAudioFocusGain = false
        lastExternalFocusChange = null
        externalFocusEpoch += 1L
        audioFocusPauseJob?.cancel()
        audioFocusPauseJob = null
        resetExternalAudioPolicyWindow()
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "audio_focus_abandoned",
            fields = mapOf("reason" to reason),
        )
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        val player = mediaSession?.player
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "audio_focus_changed",
            fields = player?.let(::playerFields).orEmpty() + mapOf(
                "focusChange" to audioFocusChangeName(focusChange),
            ),
        )
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                lastAudioFocusRequestResult = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                waitingForAudioFocusGain = false
                focusGainWaitProbes = 0
                lastExternalFocusChange = null
                externalFocusEpoch += 1L
                audioFocusPauseJob?.cancel()
                audioFocusPauseJob = null
                resetExternalAudioPolicyWindow()
                handleExternalAudioPolicy("audio-focus-gain")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                waitingForAudioFocusGain = false
                externalFocusEpoch += 1L
                lastExternalFocusChange = focusChange
                lastExternalFocusInterruptionAtMs = SystemClock.elapsedRealtime()
                if (player?.isPlaying == true || player?.playWhenReady == true) {
                    handleExternalAudioPolicy("audio-focus-loss")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                waitingForAudioFocusGain = true
                externalFocusEpoch += 1L
                lastExternalFocusChange = focusChange
                lastExternalFocusInterruptionAtMs = SystemClock.elapsedRealtime()
                if (player?.isPlaying == true || player?.playWhenReady == true) {
                    handleExternalAudioPolicy("audio-focus-loss-transient")
                    scheduleAudioFocusPauseProbe("loss-transient")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasAudioFocus = false
                waitingForAudioFocusGain = true
                externalFocusEpoch += 1L
                lastExternalFocusChange = focusChange
                lastExternalFocusInterruptionAtMs = SystemClock.elapsedRealtime()
                if (player?.isPlaying == true || player?.playWhenReady == true) {
                    handleExternalAudioPolicy("audio-focus-duck")
                    scheduleAudioFocusPauseProbe("duck")
                }
            }
        }
    }

    private fun maybeResumeAfterExternalAudioStops(
        reason: String,
        configs: List<AudioPlaybackConfiguration> = audioManager.activePlaybackConfigurations,
    ) {
        if (!resumeAfterAudioFocusLoss) return
        val player = mediaSession?.player ?: return
        val state = autoResumeStateFor(player)
        if (state == null) {
            clearAudioFocusAutoResume("missing-or-stale-state-$reason")
            return
        }
        if (player.isPlaying || player.mediaItemCount == 0 || player.currentMediaItem == null) {
            clearAudioFocusAutoResume("not-needed-$reason")
            return
        }
        val profile = externalAudioProfile(configs)
        if (hasExternalPauseBlockingAudio(profile)) {
            externalAudioObservedSincePause = true
            externalAudioQuietSinceMs = 0L
            scheduleAudioFocusResumeProbe(AUDIO_FOCUS_RESUME_PROBE_MS, "external-still-active")
            return
        }
        if (!externalAudioObservedSincePause) {
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "audio_focus_auto_resume_waiting",
                fields = playerFields(player) + mapOf(
                    "reason" to reason,
                    "wait" to "no-external-audio-observed",
                    "epoch" to state.epoch,
                    "cause" to state.cause,
                ),
            )
            scheduleAudioFocusResumeProbe(AUDIO_FOCUS_RESUME_PROBE_MS, "wait-external-observed")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (externalAudioQuietSinceMs == 0L) {
            externalAudioQuietSinceMs = now
            scheduleAudioFocusResumeProbe(EXTERNAL_AUDIO_QUIET_BEFORE_RESUME_MS, "quiet-window")
            return
        }
        val quietForMs = now - externalAudioQuietSinceMs
        if (quietForMs < EXTERNAL_AUDIO_QUIET_BEFORE_RESUME_MS) {
            scheduleAudioFocusResumeProbe(
                EXTERNAL_AUDIO_QUIET_BEFORE_RESUME_MS - quietForMs,
                "quiet-window",
            )
            return
        }
        if (!requestAudioFocusForPlayback("auto-resume-$reason")) {
            focusGainWaitProbes += 1
            val retryBaseMs = if (
                lastAudioFocusRequestResult == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
            ) {
                AUDIO_FOCUS_RESUME_PROBE_MS
            } else {
                AUDIO_FOCUS_FAILED_RETRY_BASE_MS
            }
            val retryMultiplier = 1L shl (focusGainWaitProbes - 1).coerceIn(0, 3)
            val retryDelayMs = (retryBaseMs * retryMultiplier)
                .coerceAtMost(AUDIO_FOCUS_RETRY_MAX_DELAY_MS)
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "audio_focus_auto_resume_focus_pending",
                fields = playerFields(player) + mapOf(
                    "epoch" to state.epoch,
                    "attempt" to focusGainWaitProbes,
                    "requestResult" to audioFocusRequestResultName(lastAudioFocusRequestResult),
                    "retryDelayMs" to retryDelayMs,
                ),
            )
            scheduleAudioFocusResumeProbe(retryDelayMs, "focus-not-ready")
            return
        }
        focusGainWaitProbes = 0
        if (autoResumeState?.epoch != state.epoch) return
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "audio_focus_auto_resume",
            fields = playerFields(player) + mapOf(
                "reason" to reason,
                "quietForMs" to quietForMs,
                "epoch" to state.epoch,
                "cause" to state.cause,
            ),
        )
        restoreExternalAudioDucking("auto-resume-$reason", quietMs = 0L)
        player.prepare()
        player.play()
    }

    private fun handleExternalAudioPolicy(
        reason: String,
        configs: List<AudioPlaybackConfiguration> = audioManager.activePlaybackConfigurations,
    ) {
        val player = mediaSession?.player ?: return
        val profile = externalAudioProfile(configs)
        val now = SystemClock.elapsedRealtime()
        val focusChange = lastExternalFocusChange
        val hasRecentFocusSignal = focusChange != null &&
            now - lastExternalFocusInterruptionAtMs <= EXTERNAL_FOCUS_FALLBACK_WINDOW_MS
        // Focus 回调通常先于 playback configuration 回调到达。若 transient/duck
        // 之后配置已经明确为空，不要再被“最近有过焦点丢失”挡住恢复；只有永久
        // LOSS 且还没有建立自动恢复状态时，才保留无配置也暂停的兜底。
        val holdUnobservedPermanentLoss = focusChange == AudioManager.AUDIOFOCUS_LOSS &&
            !resumeAfterAudioFocusLoss
        if (!profile.hasAnyExternalAudio && !holdUnobservedPermanentLoss) {
            resetExternalAudioPolicyWindow()
            maybeRestoreExternalAudioDucking(reason)
            maybeResumeAfterExternalAudioStops(reason, configs)
            return
        }

        val policyKey = externalAudioPolicyKey(profile, focusChange)
        if (externalAudioPolicyKey != policyKey) {
            externalAudioPolicyKey = policyKey
            externalAudioPolicyStartedAtMs = now
            externalAudioPolicyEpoch += 1L
            externalAudioPolicyProbeJob?.cancel()
            externalAudioPolicyProbeJob = null
        } else if (externalAudioPolicyStartedAtMs == 0L) {
            externalAudioPolicyStartedAtMs = now
        }
        val durationMs = now - externalAudioPolicyStartedAtMs
        val policy = decideExternalAudioPolicy(
            profile = profile,
            focusChange = focusChange,
            durationMs = durationMs,
            hasRecentFocusSignal = hasRecentFocusSignal,
        )
        recordExternalAudioPolicyDecision(
            player = player,
            reason = reason,
            profile = profile,
            policy = policy,
            focusChange = focusChange,
            durationMs = durationMs,
        )

        when (policy.action) {
            ExternalAudioAction.Ignore -> {
                recordExternalAudioMisfireIgnored(player, reason, profile, policy, focusChange, durationMs)
                restoreExternalAudioDucking("ignore-${policy.decision}-$reason", quietMs = 0L)
                if (policy.nextProbeMs > 0L) {
                    scheduleExternalAudioPolicyProbe(policy.nextProbeMs, "ignore-${policy.decision}")
                }
            }
            ExternalAudioAction.Duck -> {
                applyExternalAudioDucking(player, reason, profile, policy, focusChange, durationMs)
                scheduleExternalAudioPolicyProbe(
                    policy.nextProbeMs.takeIf { it > 0L } ?: AUDIO_DUCKING_STATE_PROBE_MS,
                    "duck-${policy.decision}",
                )
            }
            ExternalAudioAction.Pause -> {
                pauseForExternalAudioPolicy(player, reason, profile, policy, focusChange, durationMs)
            }
        }
    }

    private fun scheduleExternalAudioPolicyProbe(
        delayMs: Long,
        reason: String,
        epoch: Long = externalAudioPolicyEpoch,
    ) {
        externalAudioPolicyProbeJob?.cancel()
        externalAudioPolicyProbeJob = serviceScope.launch {
            delay(delayMs.coerceAtLeast(1L))
            if (externalAudioPolicyEpoch != epoch) return@launch
            handleExternalAudioPolicy("policy-probe-$reason")
        }
    }

    private fun resetExternalAudioPolicyWindow() {
        externalAudioPolicyEpoch += 1L
        externalAudioPolicyKey = null
        externalAudioPolicyStartedAtMs = 0L
        externalAudioPolicyProbeJob?.cancel()
        externalAudioPolicyProbeJob = null
        lastExternalPolicyLogKey = null
    }

    private fun externalAudioProfile(configs: List<AudioPlaybackConfiguration>): ExternalAudioProfile {
        val activeConfigs = configs.filter(::isPlaybackConfigActiveForPolicy)
        val ownUidConfigs = activeConfigs.filter(::isOwnPlaybackConfig)
        val policyConfigs = if (ownUidConfigs.isNotEmpty()) {
            activeConfigs.filterNot(::isOwnPlaybackConfig)
        } else {
            activeConfigs
        }
        val usages = policyConfigs.map { it.audioAttributes.usage }
        val ownExpectedCount = if (ownUidConfigs.isNotEmpty()) 0 else expectedOwnMediaPlaybackCount()
        val externalMediaCount = (usages.count(::isMediaPlaybackUsage) - ownExpectedCount)
            .coerceAtLeast(0)
        return ExternalAudioProfile(
            activeUsages = activeUsageSummary(policyConfigs),
            rawActiveUsages = activeUsageSummary(activeConfigs),
            inactiveConfigCount = configs.size - activeConfigs.size,
            ownUidConfigCount = ownUidConfigs.size,
            ownExpectedMediaCount = ownExpectedCount,
            externalMediaOrGameCount = externalMediaCount,
            hasExternalMediaOrGame = externalMediaCount > 0,
            hasVoiceOrAssistant = usages.any(::isVoiceOrAssistantUsage),
            hasNavigation = usages.any(::isNavigationUsage),
            hasNotification = usages.any(::isNotificationOrSonificationUsage),
            hasAlarmOrRingtone = usages.any(::isAlarmOrRingtoneUsage),
        )
    }

    private fun isPlaybackConfigActiveForPolicy(config: AudioPlaybackConfiguration): Boolean {
        return invokeBooleanPlaybackConfigMethod(config, "isActive") ?: true
    }

    private fun isOwnPlaybackConfig(config: AudioPlaybackConfiguration): Boolean {
        val uid = invokeIntPlaybackConfigMethod(config, "getClientUid") ?: return false
        return uid == Process.myUid()
    }

    private fun invokeBooleanPlaybackConfigMethod(
        config: AudioPlaybackConfiguration,
        methodName: String,
    ): Boolean? {
        return runCatching {
            val method = config.javaClass.methods.firstOrNull { method ->
                method.name == methodName && method.parameterTypes.isEmpty()
            } ?: return null
            method.invoke(config) as? Boolean
        }.getOrNull()
    }

    private fun invokeIntPlaybackConfigMethod(
        config: AudioPlaybackConfiguration,
        methodName: String,
    ): Int? {
        return runCatching {
            val method = config.javaClass.methods.firstOrNull { method ->
                method.name == methodName && method.parameterTypes.isEmpty()
            } ?: return null
            method.invoke(config) as? Int
        }.getOrNull()
    }

    private fun externalAudioPolicyKey(profile: ExternalAudioProfile, focusChange: Int?): String {
        val category = when {
            profile.hasAlarmOrRingtone -> "alarm"
            isInCommunicationMode() -> "communication-mode"
            profile.hasVoiceOrAssistant -> "voice"
            profile.hasNavigation -> "navigation"
            profile.hasExternalMediaOrGame -> "media"
            profile.hasNotification -> "notification"
            focusChange == AudioManager.AUDIOFOCUS_LOSS -> "focus-loss"
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "focus-transient"
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "focus-duck"
            else -> "none"
        }
        return if (focusChange == null) category else "$category:focus-$externalFocusEpoch"
    }

    private fun decideExternalAudioPolicy(
        profile: ExternalAudioProfile,
        focusChange: Int?,
        durationMs: Long,
        hasRecentFocusSignal: Boolean,
    ): ExternalAudioPolicy {
        val waitForGain = waitingForAudioFocusGain && !hasAudioFocus
        if (profile.hasAlarmOrRingtone) {
            return ExternalAudioPolicy(
                action = ExternalAudioAction.Pause,
                decision = "pause_alarm_or_ringtone",
                waitForAudioFocusGain = waitForGain,
            )
        }
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS && !profile.hasAnyExternalAudio) {
            return ExternalAudioPolicy(
                action = ExternalAudioAction.Pause,
                decision = "pause_focus_loss",
                waitForAudioFocusGain = waitForGain,
            )
        }
        if (isInCommunicationMode()) {
            return ExternalAudioPolicy(
                action = ExternalAudioAction.Pause,
                decision = "pause_communication_mode",
                waitForAudioFocusGain = waitForGain,
            )
        }
        if (profile.hasVoiceOrAssistant) {
            if (durationMs >= EXTERNAL_VOICE_PAUSE_AFTER_MS) {
                return ExternalAudioPolicy(
                    action = ExternalAudioAction.Pause,
                    decision = "pause_long_voice",
                    waitForAudioFocusGain = waitForGain,
                )
            }
            return ExternalAudioPolicy(
                action = ExternalAudioAction.Duck,
                decision = "duck_voice",
                targetGain = VOICE_DUCK_GAIN,
                attackMs = EXTERNAL_AUDIO_DUCK_FAST_ATTACK_MS,
                restoreQuietMs = EXTERNAL_AUDIO_DUCK_RESTORE_QUIET_MS,
                restoreMs = EXTERNAL_AUDIO_DUCK_RESTORE_MS,
            )
        }
        if (profile.hasNavigation) {
            return ExternalAudioPolicy(
                action = ExternalAudioAction.Duck,
                decision = "duck_navigation",
                targetGain = NAVIGATION_DUCK_GAIN,
                attackMs = EXTERNAL_AUDIO_DUCK_FAST_ATTACK_MS,
                restoreQuietMs = EXTERNAL_AUDIO_DUCK_RESTORE_QUIET_MS,
                restoreMs = EXTERNAL_AUDIO_DUCK_RESTORE_MS,
            )
        }
        if (profile.hasExternalMediaOrGame) {
            // 部分短视频/游戏只更新 AudioPlaybackConfiguration，并不规范地申请
            // AudioFocus。之前这里一律永久 ignore，导致“其他 App 明明在发声但
            // Claudio 不避让”。保留一个很短的误触发确认窗，随后按同一套
            // duck -> pause 策略处理；真正静默的残留配置会由 isActive 过滤掉。
            if (!hasRecentFocusSignal && durationMs < EXTERNAL_MEDIA_MISFIRE_IGNORE_MS) {
                return ExternalAudioPolicy(
                    action = ExternalAudioAction.Ignore,
                    decision = "ignore_media_confirming_without_focus",
                    nextProbeMs = EXTERNAL_MEDIA_MISFIRE_IGNORE_MS - durationMs,
                )
            }
            if (durationMs < EXTERNAL_MEDIA_MISFIRE_IGNORE_MS) {
                return ExternalAudioPolicy(
                    action = ExternalAudioAction.Ignore,
                    decision = "ignore_media_misfire",
                    nextProbeMs = EXTERNAL_MEDIA_MISFIRE_IGNORE_MS - durationMs,
                )
            }
            if (durationMs < EXTERNAL_MEDIA_PAUSE_AFTER_MS) {
                return ExternalAudioPolicy(
                    action = ExternalAudioAction.Duck,
                    decision = if (
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                    ) {
                        "duck_external_media_transient"
                    } else {
                        "duck_external_media_confirming"
                    },
                    targetGain = MEDIA_TRANSIENT_DUCK_GAIN,
                    attackMs = EXTERNAL_AUDIO_DUCK_FAST_ATTACK_MS,
                    restoreQuietMs = EXTERNAL_AUDIO_DUCK_RESTORE_QUIET_MS,
                    restoreMs = EXTERNAL_AUDIO_DUCK_RESTORE_MS,
                    nextProbeMs = minOf(
                        AUDIO_DUCKING_STATE_PROBE_MS,
                        EXTERNAL_MEDIA_PAUSE_AFTER_MS - durationMs,
                    ),
                )
            }
            return ExternalAudioPolicy(
                action = ExternalAudioAction.Pause,
                decision = "pause_external_media",
                waitForAudioFocusGain = waitForGain,
            )
        }
        if (profile.hasNotification) {
            return ExternalAudioPolicy(
                action = ExternalAudioAction.Duck,
                decision = "duck_notification",
                targetGain = NOTIFICATION_DUCK_GAIN,
                attackMs = EXTERNAL_AUDIO_DUCK_NOTIFICATION_ATTACK_MS,
                restoreQuietMs = EXTERNAL_AUDIO_DUCK_RESTORE_QUIET_MS,
                restoreMs = EXTERNAL_AUDIO_DUCK_RESTORE_MS,
            )
        }
        if (hasRecentFocusSignal && focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            return ExternalAudioPolicy(
                action = ExternalAudioAction.Duck,
                decision = "duck_focus_can_duck",
                targetGain = NOTIFICATION_DUCK_GAIN,
                attackMs = EXTERNAL_AUDIO_DUCK_NOTIFICATION_ATTACK_MS,
                restoreQuietMs = EXTERNAL_AUDIO_DUCK_RESTORE_QUIET_MS,
                restoreMs = EXTERNAL_AUDIO_DUCK_RESTORE_MS,
            )
        }
        if (hasRecentFocusSignal && focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            return ExternalAudioPolicy(
                action = ExternalAudioAction.Duck,
                decision = "duck_focus_transient",
                targetGain = VOICE_DUCK_GAIN,
                attackMs = EXTERNAL_AUDIO_DUCK_FAST_ATTACK_MS,
                restoreQuietMs = EXTERNAL_AUDIO_DUCK_RESTORE_QUIET_MS,
                restoreMs = EXTERNAL_AUDIO_DUCK_RESTORE_MS,
            )
        }
        return ExternalAudioPolicy(action = ExternalAudioAction.Ignore, decision = "ignore_no_policy_audio")
    }

    private fun applyExternalAudioDucking(
        player: Player,
        reason: String,
        profile: ExternalAudioProfile,
        policy: ExternalAudioPolicy,
        focusChange: Int?,
        durationMs: Long,
    ) {
        currentExternalDuckingRestoreQuietMs = policy.restoreQuietMs
        currentExternalDuckingRestoreMs = policy.restoreMs
        externalDuckingQuietSinceMs = 0L
        audioDuckingRestoreJob?.cancel()
        audioDuckingRestoreJob = null
        val target = policy.targetGain.coerceIn(EXTERNAL_AUDIO_DUCK_MIN_GAIN, 1f)
        if (kotlin.math.abs(currentExternalDuckingGain - target) < 0.01f) return
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "external_audio_duck_applied",
            fields = playerFields(player) + mapOf(
                "reason" to reason,
                "decision" to policy.decision,
                "focusChange" to audioFocusChangeNameOrNone(focusChange),
                "activeUsages" to profile.activeUsages,
                "rawActiveUsages" to profile.rawActiveUsages,
                "inactiveConfigCount" to profile.inactiveConfigCount,
                "ownUidConfigCount" to profile.ownUidConfigCount,
                "ownExpectedMediaCount" to profile.ownExpectedMediaCount,
                "externalMediaOrGameCount" to profile.externalMediaOrGameCount,
                "targetGain" to target,
                "durationMs" to durationMs,
                "quietForMs" to 0L,
            ),
        )
        animateExternalAudioDucking(target, policy.attackMs)
        updatePlaybackCallbackRegistration(player)
    }

    private fun pauseForExternalAudioPolicy(
        player: Player,
        reason: String,
        profile: ExternalAudioProfile,
        policy: ExternalAudioPolicy,
        focusChange: Int?,
        durationMs: Long,
    ) {
        if (player.mediaItemCount == 0 || player.currentMediaItem == null) return
        if (!player.playWhenReady && !player.isPlaying) return
        if (!resumeAfterAudioFocusLoss) {
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "external_audio_pause_escalated",
                fields = playerFields(player) + mapOf(
                    "reason" to reason,
                    "decision" to policy.decision,
                    "focusChange" to audioFocusChangeNameOrNone(focusChange),
                    "activeUsages" to profile.activeUsages,
                    "rawActiveUsages" to profile.rawActiveUsages,
                    "inactiveConfigCount" to profile.inactiveConfigCount,
                    "ownUidConfigCount" to profile.ownUidConfigCount,
                    "ownExpectedMediaCount" to profile.ownExpectedMediaCount,
                    "externalMediaOrGameCount" to profile.externalMediaOrGameCount,
                    "targetGain" to policy.targetGain,
                    "durationMs" to durationMs,
                    "quietForMs" to 0L,
                ),
            )
        }
        restoreExternalAudioDucking("pause-${policy.decision}-$reason", quietMs = 0L)
        val commandPlayer = mediaSession?.player ?: player
        armAudioFocusAutoResume(
            player = commandPlayer,
            reason = "external-audio-${policy.decision}-$reason",
            observedExternalAudio = profile.hasAnyExternalAudio || focusChange != null || isInCommunicationMode(),
            waitForAudioFocusGain = policy.waitForAudioFocusGain || !hasAudioFocus,
            blocksExternalMedia = profile.hasExternalMediaOrGame ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS,
            blocksCommunicationMode = isInCommunicationMode(),
            pendingInternalPauseCommand = true,
        )
        runCatching { commandPlayer.pause() }
            .onFailure { err ->
                clearAudioFocusAutoResume("external-audio-pause-failed")
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "external_audio_auto_pause_failed",
                    fields = playerFields(commandPlayer) + mapOf(
                        "reason" to reason,
                        "decision" to policy.decision,
                        "errorType" to err::class.java.simpleName,
                        "message" to err.message,
                    ),
                )
            }
    }

    private fun hasExternalPauseBlockingAudio(profile: ExternalAudioProfile): Boolean {
        if (profile.hasAlarmOrRingtone || profile.hasVoiceOrAssistant || profile.hasNavigation) return true
        val state = autoResumeState ?: return false
        if (state.blocksExternalMedia && profile.hasExternalMediaOrGame) return true
        if (state.blocksCommunicationMode && isInCommunicationMode()) return true
        return false
    }

    private fun expectedOwnMediaPlaybackCount(): Int {
        val player = mediaSession?.player
        val mainExpected = if (player?.isPlaying == true) 1 else 0
        val auxExpected = if (crossfadeController?.hasActiveAuxPlayback == true) 1 else 0
        return mainExpected + auxExpected
    }

    private fun isMediaPlaybackUsage(usage: Int): Boolean {
        return usage == android.media.AudioAttributes.USAGE_MEDIA ||
            usage == android.media.AudioAttributes.USAGE_GAME
    }

    private fun isVoiceOrAssistantUsage(usage: Int): Boolean {
        return usage == android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION ||
            usage == android.media.AudioAttributes.USAGE_ASSISTANT ||
            usage == android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
    }

    private fun isNavigationUsage(usage: Int): Boolean {
        return usage == android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    @Suppress("DEPRECATION")
    private fun isNotificationOrSonificationUsage(usage: Int): Boolean {
        return usage == android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION ||
            usage == android.media.AudioAttributes.USAGE_NOTIFICATION ||
            usage == android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT ||
            usage == android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED ||
            usage == android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST ||
            usage == android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT
    }

    private fun isAlarmOrRingtoneUsage(usage: Int): Boolean {
        return usage == android.media.AudioAttributes.USAGE_ALARM ||
            usage == android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE
    }

    private fun isInCommunicationMode(): Boolean {
        val mode = audioManager.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun activeUsageSummary(configs: List<AudioPlaybackConfiguration>): String {
        return configs
            .map { playbackUsageName(it.audioAttributes.usage) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}:${it.value}" }
    }

    private fun recordExternalAudioPolicyDecision(
        player: Player,
        reason: String,
        profile: ExternalAudioProfile,
        policy: ExternalAudioPolicy,
        focusChange: Int?,
        durationMs: Long,
    ) {
        val now = SystemClock.elapsedRealtime()
        val key = "${policy.action}:${policy.decision}:${profile.activeUsages}:${audioFocusChangeNameOrNone(focusChange)}"
        if (lastExternalPolicyLogKey == key && now - lastExternalPolicyLogAtMs < EXTERNAL_AUDIO_POLICY_LOG_THROTTLE_MS) {
            return
        }
        lastExternalPolicyLogKey = key
        lastExternalPolicyLogAtMs = now
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "external_audio_policy_decision",
            fields = playerFields(player) + mapOf(
                "reason" to reason,
                "decision" to policy.decision,
                "action" to policy.action.name.lowercase(Locale.US),
                "focusChange" to audioFocusChangeNameOrNone(focusChange),
                "activeUsages" to profile.activeUsages,
                "rawActiveUsages" to profile.rawActiveUsages,
                "inactiveConfigCount" to profile.inactiveConfigCount,
                "ownUidConfigCount" to profile.ownUidConfigCount,
                "ownExpectedMediaCount" to profile.ownExpectedMediaCount,
                "externalMediaOrGameCount" to profile.externalMediaOrGameCount,
                "targetGain" to policy.targetGain,
                "durationMs" to durationMs,
                "quietForMs" to currentExternalDuckingQuietForMs(),
            ),
        )
    }

    private fun recordExternalAudioMisfireIgnored(
        player: Player,
        reason: String,
        profile: ExternalAudioProfile,
        policy: ExternalAudioPolicy,
        focusChange: Int?,
        durationMs: Long,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastExternalMisfireLogAtMs < EXTERNAL_AUDIO_MISFIRE_LOG_THROTTLE_MS) return
        lastExternalMisfireLogAtMs = now
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "external_audio_misfire_ignored",
            fields = playerFields(player) + mapOf(
                "reason" to reason,
                "decision" to policy.decision,
                "focusChange" to audioFocusChangeNameOrNone(focusChange),
                "activeUsages" to profile.activeUsages,
                "rawActiveUsages" to profile.rawActiveUsages,
                "inactiveConfigCount" to profile.inactiveConfigCount,
                "ownUidConfigCount" to profile.ownUidConfigCount,
                "ownExpectedMediaCount" to profile.ownExpectedMediaCount,
                "externalMediaOrGameCount" to profile.externalMediaOrGameCount,
                "targetGain" to policy.targetGain,
                "durationMs" to durationMs,
                "quietForMs" to currentExternalDuckingQuietForMs(),
            ),
        )
    }

    private fun maybeRestoreExternalAudioDucking(reason: String) {
        restoreExternalAudioDucking(reason, quietMs = currentExternalDuckingRestoreQuietMs)
    }

    private fun restoreExternalAudioDucking(reason: String, quietMs: Long) {
        if (currentExternalDuckingGain >= 0.999f) {
            externalDuckingQuietSinceMs = 0L
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (quietMs > 0L) {
            if (externalDuckingQuietSinceMs == 0L) {
                externalDuckingQuietSinceMs = now
                scheduleExternalAudioDuckingRestoreProbe(quietMs, reason)
                return
            }
            val quietForMs = now - externalDuckingQuietSinceMs
            if (quietForMs < quietMs) {
                scheduleExternalAudioDuckingRestoreProbe(quietMs - quietForMs, reason)
                return
            }
        }
        val quietForMs = currentExternalDuckingQuietForMs()
        externalDuckingQuietSinceMs = 0L
        audioDuckingRestoreJob?.cancel()
        audioDuckingRestoreJob = null
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "external_audio_duck_restored",
            fields = mediaSession?.player?.let(::playerFields).orEmpty() + mapOf(
                "reason" to reason,
                "decision" to "restore",
                "focusChange" to audioFocusChangeNameOrNone(lastExternalFocusChange),
                "activeUsages" to activeUsageSummary(audioManager.activePlaybackConfigurations),
                "targetGain" to 1f,
                "durationMs" to 0L,
                "quietForMs" to quietForMs,
            ),
        )
        animateExternalAudioDucking(1f, currentExternalDuckingRestoreMs)
        updatePlaybackCallbackRegistration()
    }

    private fun scheduleExternalAudioDuckingRestoreProbe(delayMs: Long, reason: String) {
        audioDuckingRestoreJob?.cancel()
        audioDuckingRestoreJob = serviceScope.launch {
            delay(delayMs.coerceAtLeast(1L))
            handleExternalAudioPolicy("duck-restore-$reason")
        }
    }

    private fun animateExternalAudioDucking(targetGain: Float, durationMs: Long) {
        val target = targetGain.coerceIn(EXTERNAL_AUDIO_DUCK_MIN_GAIN, 1f)
        val start = currentExternalDuckingGain
        audioDuckingJob?.cancel()
        if (durationMs <= 0L || kotlin.math.abs(start - target) < 0.001f) {
            setExternalAudioDuckingGain(target)
            return
        }
        val startedAtMs = SystemClock.elapsedRealtime()
        audioDuckingJob = serviceScope.launch {
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - startedAtMs
                val p = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                val eased = audioDuckingSmoothStep(p)
                setExternalAudioDuckingGain(start + (target - start) * eased)
                if (p >= 1f) break
                delay(EXTERNAL_AUDIO_DUCK_FRAME_MS)
            }
        }
    }

    private fun setExternalAudioDuckingGain(gain: Float) {
        val clamped = gain.coerceIn(EXTERNAL_AUDIO_DUCK_MIN_GAIN, 1f)
        currentExternalDuckingGain = clamped
        playbackGain.setDuckingLinear(clamped)
        auxPlaybackGain.setDuckingLinear(clamped)
    }

    private fun currentExternalDuckingQuietForMs(): Long {
        return if (externalDuckingQuietSinceMs == 0L) {
            0L
        } else {
            SystemClock.elapsedRealtime() - externalDuckingQuietSinceMs
        }
    }

    private fun audioDuckingSmoothStep(p: Float): Float {
        val x = p.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun clearAudioFocusAutoResume(reason: String) {
        val hadForegroundGrace = autoResumeForegroundUntilMs > 0L
        autoResumeForegroundUntilMs = 0L
        audioFocusForegroundExpiryJob?.cancel()
        audioFocusForegroundExpiryJob = null
        autoResumeState = null
        if (hadForegroundGrace) {
            // 显式暂停也可能发生在已暂停状态，必须主动结束前台宽限而不依赖 Player 回调。
            mainHandler.post {
                mediaSession?.let { session ->
                    updateNotificationSafely(session, session.player.playWhenReady, "focus-cleared-$reason")
                }
            }
        }
        audioFocusPauseJob?.cancel()
        audioFocusPauseJob = null
        if (!resumeAfterAudioFocusLoss) {
            audioFocusResumeJob?.cancel()
            audioFocusResumeJob = null
            externalAudioPolicyProbeJob?.cancel()
            externalAudioPolicyProbeJob = null
            externalAudioObservedSincePause = false
            externalAudioQuietSinceMs = 0L
            waitingForAudioFocusGain = false
            resetExternalAudioPolicyWindow()
            restoreExternalAudioDucking("clear-auto-resume-$reason", quietMs = 0L)
            updatePlaybackCallbackRegistration()
            return
        }
        resumeAfterAudioFocusLoss = false
        audioFocusResumeJob?.cancel()
        audioFocusResumeJob = null
        externalAudioPolicyProbeJob?.cancel()
        externalAudioPolicyProbeJob = null
        externalAudioObservedSincePause = false
        externalAudioQuietSinceMs = 0L
        waitingForAudioFocusGain = false
        resetExternalAudioPolicyWindow()
        restoreExternalAudioDucking("clear-auto-resume-$reason", quietMs = 0L)
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "audio_focus_auto_resume_cleared",
            fields = mapOf("reason" to reason),
        )
        updatePlaybackCallbackRegistration()
    }

    private fun recoverBadSourceOrSkip(player: Player, error: PlaybackException) {
        if (startServiceUrlRefresh(player, error)) return
        scheduleBadSourceSkip(player, error)
    }

    private fun markServiceUrlRefreshReplacement(mediaId: String) {
        serviceUrlRefreshReplacementMediaId = mediaId
    }

    private fun updateServiceUrlRefreshTriedOnTransition(mediaId: String?, reason: Int): Boolean {
        val isRefreshReplacement = mediaId != null && mediaId == serviceUrlRefreshReplacementMediaId
        if (isRefreshReplacement) {
            serviceUrlRefreshReplacementMediaId = null
        }
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            if (!isRefreshReplacement) {
                serviceUrlRefreshTried.clear()
            }
        } else {
            mediaId?.let { serviceUrlRefreshTried.remove(it) }
        }
        return isRefreshReplacement
    }

    private fun startServiceUrlRefresh(player: Player, error: PlaybackException): Boolean {
        if (!player.playWhenReady || player.mediaItemCount == 0) return false
        val mediaItem = player.currentMediaItem ?: return false
        val mediaId = mediaItem.mediaId.takeIf { it.isNotBlank() } ?: return false
        val neteaseId = mediaId.toLongOrNull()
        if (neteaseId == null) {
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "bad_source_refresh_unavailable",
                fields = playerFields(player) + mapOf(
                    "code" to error.errorCodeName,
                    "reason" to "non-numeric-media-id",
                ),
            )
            return false
        }
        if (!serviceUrlRefreshTried.add(mediaId)) {
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "bad_source_refresh_unavailable",
                fields = playerFields(player) + mapOf(
                    "code" to error.errorCodeName,
                    "reason" to "already-tried",
                ),
            )
            return false
        }

        val token = ++badSourceSkipToken
        val startPositionMs = maxOf(
            player.currentPosition.coerceAtLeast(0L),
            crossfadeController?.resumePositionMsFor(mediaId) ?: 0L,
        )
        badSourceRecoveryMediaId = mediaId
        badSourceRecoveryUntilMs = SystemClock.elapsedRealtime() + BAD_SOURCE_SERVICE_REFRESH_GRACE_MS
        notificationPlayer?.armRecoveryWindow()
        mediaSession?.let { session ->
            updateNotificationSafely(session, true, "bad-source-refresh-start")
        }
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "bad_source_refresh_start",
            fields = playerFields(player) + mapOf(
                "code" to error.errorCodeName,
                "neteaseId" to neteaseId,
                "resumePositionMs" to startPositionMs,
            ),
        )

        badSourceRefreshJob?.cancel()
        badSourceRefreshJob = serviceScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                urlResolver.fetchPlayable(neteaseId)
            }
            if (token != badSourceSkipToken) return@launch
            if (!player.playWhenReady || player.currentMediaItem?.mediaId != mediaId) {
                clearBadSourceRecovery(mediaId)
                recordBadSourceSkipAbort(player, error, "changed-or-paused")
                return@launch
            }
            if (fresh == null) {
                clearBadSourceRecovery(mediaId)
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "bad_source_refresh_empty",
                    fields = playerFields(player) + mapOf(
                        "code" to error.errorCodeName,
                        "neteaseId" to neteaseId,
                    ),
                )
                if (shouldWaitForNetworkBeforeSkipping()) {
                    // 重签拿不到 URL 多半是没网,这时跳下一首同样是死的 —— 原地等网络回来续播
                    holdStalledTrackForNetwork(player, "bad-source-refresh-empty", startPositionMs)
                } else {
                    scheduleBadSourceSkip(player, error)
                }
                return@launch
            }

            runCatching {
                val liveItem = player.currentMediaItem ?: return@runCatching
                val itemIndex = player.currentMediaItemIndex
                if (itemIndex !in 0 until player.mediaItemCount) return@runCatching
                if (player.getMediaItemAt(itemIndex).mediaId != mediaId) return@runCatching
                val resumePositionMs = maxOf(startPositionMs, player.currentPosition.coerceAtLeast(0L))
                notificationPlayer?.armRecoveryWindow()
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "bad_source_refresh_success",
                    fields = playerFields(player) + mapOf(
                        "code" to error.errorCodeName,
                        "neteaseId" to neteaseId,
                        "resumePositionMs" to resumePositionMs,
                    ),
                )
                markServiceUrlRefreshReplacement(mediaId)
                player.replaceMediaItem(
                    itemIndex,
                    liveItem.buildUpon()
                        .setUri(fresh.url)
                        .setCustomCacheKey(fresh.cacheKey)
                        .build(),
                )
                player.seekTo(itemIndex, resumePositionMs)
                player.prepare()
                player.play()
                mediaSession?.let { session ->
                    updateNotificationSafely(session, true, "bad-source-refresh-success")
                }
            }.onFailure { err ->
                if (serviceUrlRefreshReplacementMediaId == mediaId) {
                    serviceUrlRefreshReplacementMediaId = null
                }
                clearBadSourceRecovery(mediaId)
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "bad_source_refresh_failed",
                    fields = playerFields(player) + mapOf(
                        "code" to error.errorCodeName,
                        "neteaseId" to neteaseId,
                        "errorType" to err::class.java.simpleName,
                        "message" to err.message,
                    ),
                )
                scheduleBadSourceSkip(player, error)
            }
        }
        return true
    }

    private fun scheduleBadSourceSkip(player: Player, error: PlaybackException) {
        if (!player.playWhenReady || player.mediaItemCount <= 1) {
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "bad_source_no_skip",
                fields = playerFields(player) + mapOf(
                    "code" to error.errorCodeName,
                    "reason" to if (!player.playWhenReady) "not-playing" else "single-item",
                ),
            )
            return
        }
        val mediaId = player.currentMediaItem?.mediaId
        val token = ++badSourceSkipToken
        badSourceRecoveryMediaId = mediaId
        badSourceRecoveryUntilMs = SystemClock.elapsedRealtime() + BAD_SOURCE_CONTROLLER_GRACE_MS
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
            if (!player.playWhenReady || player.currentMediaItem?.mediaId != mediaId) {
                clearBadSourceRecovery(mediaId)
                recordBadSourceSkipAbort(player, error, "changed-or-paused")
                return@postDelayed
            }
            val stuckState = player.playbackState == Player.STATE_IDLE ||
                player.playbackState == Player.STATE_BUFFERING
            if (!stuckState) {
                clearBadSourceRecovery(mediaId)
                recordBadSourceSkipAbort(player, error, "recovered")
                return@postDelayed
            }
            val nextIndex = player.nextMediaItemIndex
            if (nextIndex == C.INDEX_UNSET || nextIndex == player.currentMediaItemIndex) {
                clearBadSourceRecovery(mediaId)
                recordBadSourceSkipAbort(player, error, "no-next")
                return@postDelayed
            }
            runCatching {
                clearBadSourceRecovery(mediaId)
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

    private fun isWaitingForBadSourceController(player: Player, now: Long): Boolean {
        val mediaId = player.currentMediaItem?.mediaId ?: return false
        return mediaId == badSourceRecoveryMediaId &&
            (now < badSourceRecoveryUntilMs || badSourceRefreshJob?.isActive == true)
    }

    private fun clearBadSourceRecovery(mediaId: String? = null) {
        if (mediaId != null && mediaId != badSourceRecoveryMediaId) return
        badSourceRecoveryMediaId = null
        badSourceRecoveryUntilMs = 0L
    }

    private fun recordBadSourceSkipAbort(player: Player, error: PlaybackException, reason: String) {
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "bad_source_skip_aborted",
            fields = playerFields(player) + mapOf(
                "code" to error.errorCodeName,
                "reason" to reason,
            ),
        )
    }

    // —— 静默缓冲卡顿兜底 ——
    // 既有的播放恢复要么靠 onPlayerError（后台有效），要么靠 UI 30Hz 轮询的 monitorPlaybackProgress
    // （只前台、且只救 READY 冻结）。后台一旦发生「BUFFERING 卡死又不报错」（doze / MIUI 掐网、socket
    // 没断但数据不来），没人来救 —— UI 一直显示加载中，直到回前台、设备解除限制那笔挂起缓冲才补上。
    // Service 常驻，这里在它自己的 BUFFERING 事件上挂一个延时检查，后台也能主动重踢把它救活。
    private fun scheduleBufferStallCheck(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (bufferStallScheduledForMediaId == mediaId) return // 同一首已排队,别重复堆 postDelayed
        bufferStallScheduledForMediaId = mediaId
        val token = ++bufferStallToken
        val bufferedAtSchedule = player.bufferedPosition
        val positionAtSchedule = player.currentPosition
        mainHandler.postDelayed({
            if (bufferStallScheduledForMediaId == mediaId) bufferStallScheduledForMediaId = null
            if (token != bufferStallToken) return@postDelayed
            evaluateBufferStall(player, mediaId, bufferedAtSchedule, positionAtSchedule)
        }, BUFFER_STALL_CHECK_DELAY_MS)
    }

    private fun cancelBufferStallCheck() {
        bufferStallToken += 1L // 令所有已排队的检查回调失效
        bufferStallScheduledForMediaId = null
    }

    private fun resetBufferStallAttempts() {
        bufferStallAttemptMediaId = null
        bufferStallAttempts = 0
    }

    private fun resetProgressStallAttempts() {
        progressStallAttemptMediaId = null
        progressStallAttempts = 0
        singleItemStallRetryAfterMs = 0L
    }

    private fun evaluateBufferStall(
        player: Player,
        mediaId: String,
        bufferedAtScheduleMs: Long,
        positionAtScheduleMs: Long,
    ) {
        // 任一前提不再成立 = 不是我们要救的场景（已恢复 / 暂停 / 换曲 / 队列空）
        if (player.playbackState != Player.STATE_BUFFERING) return
        if (!player.playWhenReady || player.mediaItemCount == 0) return
        if (player.currentMediaItem?.mediaId != mediaId) return
        val now = SystemClock.elapsedRealtime()
        if (isWaitingForBadSourceController(player, now)) return // 坏源恢复在跑,让它先走

        val bufferedNow = player.bufferedPosition
        val positionNow = player.currentPosition
        val advancing = bufferedNow > bufferedAtScheduleMs + BUFFER_STALL_PROGRESS_TOLERANCE_MS ||
            positionNow > positionAtScheduleMs + BUFFER_STALL_PROGRESS_TOLERANCE_MS
        if (advancing) {
            // 还在补数据 / 还在前进,只是慢 —— 不打断,再观察一轮
            scheduleBufferStallCheck(player)
            return
        }

        if (bufferStallAttemptMediaId != mediaId) {
            bufferStallAttemptMediaId = mediaId
            bufferStallAttempts = 0
        }
        if (bufferStallAttempts >= BUFFER_STALL_MAX_ATTEMPTS) {
            // 连踢几次都没活 —— 八成网络真没了,停手别 prepare 风暴。
            // 挂上网络恢复回调:网络一回来 onNetworkRecovered 立刻重踢续播,不用等回前台。
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "buffer_stall_give_up",
                fields = playerFields(player) + mapOf(
                    "attempts" to bufferStallAttempts,
                    "bufferedMs" to bufferedNow,
                ),
            )
            armNetworkRecovery("buffer-stall-give-up")
            return
        }
        bufferStallAttempts += 1
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "buffer_stall_rekick",
            fields = playerFields(player) + mapOf(
                "attempt" to bufferStallAttempts,
                "positionMs" to positionNow,
                "bufferedMs" to bufferedNow,
            ),
        )
        runCatching {
            // 与 monitorPlaybackProgress 的自愈一致:seek 回原位 + prepare,逼它重新拉流。
            // 若网络真没了,这一步会转成 ERROR_CODE_IO_NETWORK_* → 走既有报错恢复,也强过静默卡死。
            markProgressWatchdogInternalSeek()
            player.seekTo(positionNow.coerceAtLeast(0L))
            player.prepare()
        }.onFailure { err ->
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "buffer_stall_rekick_failed",
                fields = playerFields(player) + mapOf(
                    "errorType" to err::class.java.simpleName,
                    "message" to err.message,
                ),
            )
        }
        // 再排一轮,看这次重踢有没有救活(没救活会累加 attempts 直到上限后停手）
        scheduleBufferStallCheck(player)
    }

    private fun publishPreparationGate(player: Player) {
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val durationMs = player.duration.takeIf { it > 0L && it != C.TIME_UNSET }
        val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
        PlaybackPreparationGate.update(
            playWhenReady = player.playWhenReady,
            isPlaying = player.isPlaying,
            playbackState = player.playbackState,
            bufferedAheadMs = ((player.bufferedPosition - positionMs).coerceAtLeast(0L) / speed).toLong(),
            remainingMs = durationMs?.let { ((it - positionMs).coerceAtLeast(0L) / speed).toLong() } ?: Long.MAX_VALUE,
        )
    }

    private fun armProgressWatchdog(player: Player) {
        publishPreparationGate(player)
        maxReachedPositionMs = player.currentPosition.coerceAtLeast(0L)
        lastProgressAtMs = SystemClock.elapsedRealtime()
        maxBufferedPositionMs = player.bufferedPosition
        lastBufferProgressAtMs = lastProgressAtMs
        resetProgressStallAttempts()
        scheduleProgressWatchdog(player)
    }

    private fun scheduleProgressWatchdog(player: Player) {
        val token = ++progressWatchdogToken
        mainHandler.postDelayed({
            if (token != progressWatchdogToken) return@postDelayed
            evaluateProgressWatchdog(player)
        }, PROGRESS_WATCHDOG_INTERVAL_MS)
    }

    private fun cancelProgressWatchdog() {
        progressWatchdogToken += 1L
    }

    private fun markProgressWatchdogInternalSeek() {
        progressWatchdogInternalSeekUntilMs =
            SystemClock.elapsedRealtime() + PROGRESS_WATCHDOG_INTERNAL_SEEK_GRACE_MS
    }

    private fun isProgressWatchdogInternalSeek(): Boolean {
        return SystemClock.elapsedRealtime() <= progressWatchdogInternalSeekUntilMs
    }

    /**
     * READY/BUFFERING 抖动不能重置进度观察。正常补缓冲、系统抑制和短暂弱网不跳片段；
     * 持续无进展才在原位置换源恢复，保留歌曲内容。
     */
    private fun evaluateProgressWatchdog(player: Player) {
        publishPreparationGate(player)
        if (!player.playWhenReady || player.mediaItemCount == 0) return
        if (player.playbackState == Player.STATE_ENDED) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val now = SystemClock.elapsedRealtime()
        if (isWaitingForBadSourceController(player, now) ||
            player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
        ) {
            lastProgressAtMs = now
            lastBufferProgressAtMs = now
            scheduleProgressWatchdog(player)
            return
        }
        val positionNow = player.currentPosition.coerceAtLeast(0L)
        if (player.bufferedPosition > maxBufferedPositionMs + BUFFER_STALL_PROGRESS_TOLERANCE_MS) {
            maxBufferedPositionMs = player.bufferedPosition
            lastBufferProgressAtMs = now
        }
        if (positionNow > maxReachedPositionMs + BUFFER_STALL_PROGRESS_TOLERANCE_MS) {
            maxReachedPositionMs = positionNow
            lastProgressAtMs = now
            resetProgressStallAttempts()
        } else if (now - lastProgressAtMs >= PROGRESS_STALL_THRESHOLD_MS &&
            (player.playbackState != Player.STATE_BUFFERING ||
                now - lastBufferProgressAtMs >= PROGRESS_STALL_THRESHOLD_MS)
        ) {
            if (shouldWaitForNetworkBeforeSkipping()) {
                holdStalledTrackForNetwork(player, "progress-watchdog", positionNow)
            } else {
                if (progressStallAttemptMediaId != mediaId) {
                    progressStallAttemptMediaId = mediaId
                    progressStallAttempts = 0
                }
                progressStallAttempts += 1
                lastProgressAtMs = now
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "progress_stall_escalate",
                    fields = playerFields(player) + mapOf(
                        "attempt" to progressStallAttempts,
                        "positionMs" to positionNow,
                    ),
                )
                recoverSilentStall(player)
            }
        }
        scheduleProgressWatchdog(player)
    }

    /**
     * 静默卡死(无 PlaybackException,逃过 onPlayerError)的升级恢复:重签 URL → 换 URI 续播;
     * 拿不到新 URL / 这首已重签过还卡 → 跳到下一首。复用坏源恢复的字段与节流,不依赖 error。
     */
    private fun recoverSilentStall(player: Player) {
        if (!player.playWhenReady || player.mediaItemCount == 0) return
        val mediaItem = player.currentMediaItem ?: return
        val mediaId = mediaItem.mediaId.takeIf { it.isNotBlank() } ?: return
        val neteaseId = mediaId.toLongOrNull()
        val startPositionMs = player.currentPosition.coerceAtLeast(0L)
        if (neteaseId == null) {
            skipStalledToNext(player, "non-numeric-id")
            return
        }
        if (!serviceUrlRefreshTried.add(mediaId)) {
            // 已重签过但近期是网络错误/网络未验证时,保留当前歌等待恢复；否则才认定这首源坏了。
            if (shouldWaitForNetworkBeforeSkipping()) {
                holdStalledTrackForNetwork(player, "already-refreshed", startPositionMs)
            } else {
                skipStalledToNext(player, "already-refreshed")
            }
            return
        }
        val token = ++badSourceSkipToken
        badSourceRecoveryMediaId = mediaId
        badSourceRecoveryUntilMs = SystemClock.elapsedRealtime() + BAD_SOURCE_SERVICE_REFRESH_GRACE_MS
        notificationPlayer?.armRecoveryWindow()
        mediaSession?.let { session ->
            updateNotificationSafely(session, true, "silent-stall-url-refresh-start")
        }
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "silent_stall_url_refresh",
            fields = playerFields(player) + mapOf("neteaseId" to neteaseId, "resumePositionMs" to startPositionMs),
        )
        badSourceRefreshJob?.cancel()
        badSourceRefreshJob = serviceScope.launch {
            val fresh = withContext(Dispatchers.IO) { urlResolver.fetchPlayable(neteaseId) }
            if (token != badSourceSkipToken) return@launch
            if (!player.playWhenReady || player.currentMediaItem?.mediaId != mediaId) {
                clearBadSourceRecovery(mediaId)
                return@launch
            }
            if (fresh == null) {
                clearBadSourceRecovery(mediaId)
                if (shouldWaitForNetworkBeforeSkipping()) {
                    holdStalledTrackForNetwork(player, "refresh-empty", startPositionMs)
                } else {
                    skipStalledToNext(player, "refresh-empty")
                }
                return@launch
            }
            runCatching {
                val idx = player.currentMediaItemIndex
                if (idx !in 0 until player.mediaItemCount) return@runCatching
                if (player.getMediaItemAt(idx).mediaId != mediaId) return@runCatching
                val resumePositionMs = maxOf(startPositionMs, player.currentPosition.coerceAtLeast(0L))
                notificationPlayer?.armRecoveryWindow()
                markServiceUrlRefreshReplacement(mediaId)
                player.replaceMediaItem(
                    idx,
                    mediaItem.buildUpon()
                        .setUri(fresh.url)
                        .setCustomCacheKey(fresh.cacheKey)
                        .build(),
                )
                player.seekTo(idx, resumePositionMs)
                player.prepare()
                player.play()
                mediaSession?.let { session ->
                    updateNotificationSafely(session, true, "silent-stall-url-refresh-success")
                }
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "silent_stall_url_refresh_success",
                    fields = playerFields(player) + mapOf("neteaseId" to neteaseId, "resumePositionMs" to resumePositionMs),
                )
            }.onFailure {
                if (serviceUrlRefreshReplacementMediaId == mediaId) {
                    serviceUrlRefreshReplacementMediaId = null
                }
                clearBadSourceRecovery(mediaId)
                skipStalledToNext(player, "refresh-failed")
            }
        }
    }

    private fun skipStalledToNext(player: Player, reason: String) {
        if (!player.playWhenReady || player.mediaItemCount == 0) return
        val nextIndex = player.nextMediaItemIndex
        if (player.mediaItemCount <= 1 ||
            nextIndex == C.INDEX_UNSET ||
            nextIndex == player.currentMediaItemIndex
        ) {
            recoverSingleStalledItem(player, reason)
            return
        }
        runCatching {
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "silent_stall_skip",
                fields = playerFields(player) + mapOf("reason" to reason, "nextIndex" to nextIndex),
            )
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }

    private fun recoverSingleStalledItem(player: Player, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now < singleItemStallRetryAfterMs) return
        singleItemStallRetryAfterMs = now + SINGLE_ITEM_STALL_RETRY_COOLDOWN_MS
        lastProgressAtMs = now
        notificationPlayer?.armRecoveryWindow()
        armNetworkRecovery("single-item-$reason")
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "single_item_stall_rekick",
            fields = playerFields(player) + mapOf(
                "reason" to reason,
                "cooldownMs" to SINGLE_ITEM_STALL_RETRY_COOLDOWN_MS,
            ),
        )
        runCatching {
            markProgressWatchdogInternalSeek()
            player.seekTo(player.currentPosition.coerceAtLeast(0L))
            player.prepare()
        }.onFailure { err ->
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "single_item_stall_rekick_failed",
                fields = playerFields(player) + mapOf(
                    "reason" to reason,
                    "errorType" to err::class.java.simpleName,
                    "message" to err.message,
                ),
            )
        }
    }

    private fun holdStalledTrackForNetwork(player: Player, reason: String, resumePositionMs: Long) {
        val now = SystemClock.elapsedRealtime()
        lastProgressAtMs = now
        bufferStallAttemptMediaId = player.currentMediaItem?.mediaId
        bufferStallAttempts = 0
        armNetworkRecovery("hold-$reason")
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "silent_stall_wait_network",
            fields = playerFields(player) + mapOf(
                "reason" to reason,
                "resumePositionMs" to resumePositionMs,
                "recentTransientNetworkError" to hasRecentTransientNetworkError(now),
                "networkReady" to isActiveNetworkReady(),
            ),
        )
    }

    private fun shouldWaitForNetworkBeforeSkipping(): Boolean {
        return hasRecentTransientNetworkError() || !isActiveNetworkReady()
    }

    private fun hasRecentTransientNetworkError(now: Long = SystemClock.elapsedRealtime()): Boolean {
        return lastTransientNetworkErrorAtMs > 0L &&
            now - lastTransientNetworkErrorAtMs <= TRANSIENT_NETWORK_SKIP_DEFER_MS
    }

    private fun isActiveNetworkReady(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun armNetworkRecovery(reason: String) {
        if (networkRecoveryArmed) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.registerDefaultNetworkCallback(networkRecoveryCallback) }
            .onSuccess {
                networkRecoveryArmed = true
                networkRecoveryArmAttempts = 0
                networkRecoveryArmRetryJob?.cancel()
                networkRecoveryArmRetryJob = null
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "network_recovery_armed",
                    fields = mapOf("reason" to reason),
                )
            }
            .onFailure { err ->
                networkRecoveryArmAttempts += 1
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "network_recovery_arm_failed",
                    fields = mapOf(
                        "reason" to reason,
                        "errorType" to err::class.java.simpleName,
                        "message" to err.message,
                        "attempt" to networkRecoveryArmAttempts,
                    ),
                )
                if (networkRecoveryArmAttempts < NETWORK_RECOVERY_ARM_MAX_ATTEMPTS) {
                    val retryDelayMs = NETWORK_RECOVERY_ARM_RETRY_BASE_MS * networkRecoveryArmAttempts
                    networkRecoveryArmRetryJob?.cancel()
                    networkRecoveryArmRetryJob = serviceScope.launch {
                        delay(retryDelayMs)
                        networkRecoveryArmRetryJob = null
                        armNetworkRecovery("retry-$reason")
                    }
                }
            }
    }

    private fun disarmNetworkRecovery() {
        networkRecoveryArmRetryJob?.cancel()
        networkRecoveryArmRetryJob = null
        networkRecoveryArmAttempts = 0
        if (!networkRecoveryArmed) return
        networkRecoveryArmed = false
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.unregisterNetworkCallback(networkRecoveryCallback) }
    }

    /**
     * 网络恢复(或注册时网络本就在)的单发重踢:还想播且卡在 IDLE/BUFFERING 就
     * seek 原位 + prepare 续播,并解锁该曲的 URL 重签额度让坏源恢复可以再跑一轮。
     * 单发后立即注销;若依旧卡死,看门狗/给 up 路径会再次 arm,形成低频重试环。
     */
    private fun onNetworkRecovered(trigger: String) {
        if (!networkRecoveryArmed) return
        if (!isActiveNetworkReady()) return
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            disarmNetworkRecovery()
            return
        }
        val stalled = player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_BUFFERING
        if (!stalled) {
            disarmNetworkRecovery()
            return
        }
        disarmNetworkRecovery()
        resetBufferStallAttempts()
        player.currentMediaItem?.mediaId?.let { serviceUrlRefreshTried.remove(it) }
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "network_recovered_rekick",
            fields = playerFields(player) + mapOf("trigger" to trigger),
        )
        notificationPlayer?.armRecoveryWindow()
        runCatching {
            player.seekTo(player.currentPosition.coerceAtLeast(0L))
            player.prepare()
            player.play()
        }
        armProgressWatchdog(player)
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        updateNotificationSafely(session, startInForegroundRequired, "media3")
    }

    private fun updateNotificationSafely(
        session: MediaSession,
        startInForegroundRequired: Boolean,
        reason: String,
    ): Boolean {
        // 冷启动恢复期间 Media3 还没有可播队列；它的空闲通知更新不能撤掉启动通知。
        if (resumptionForegroundStarted &&
            (session.player.mediaItemCount == 0 || !startInForegroundRequired)
        ) return true
        val now = SystemClock.elapsedRealtime()
        val appInForeground = AppForeground.isForeground.value
        if (appInForeground && foregroundStartDeniedUntilMs > now) {
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "notification_foreground_backoff_cleared",
                fields = playerFields(session.player) + mapOf("reason" to reason),
            )
            foregroundStartDeniedUntilMs = 0L
        }
        // 自动避让保留已有前台身份；用户暂停/停止会清掉状态。宽限到期后交还 Media3。
        val retainForAutoResume = retainForegroundForAutoResume(session.player)
        val foregroundRequired = startInForegroundRequired || retainForAutoResume
        val deniedBackoff = now < foregroundStartDeniedUntilMs
        val startInForeground = foregroundRequired && !deniedBackoff
        if (foregroundRequired && !appInForeground && !deniedBackoff) {
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "notification_foreground_background_start",
                fields = playerFields(session.player) + mapOf(
                    "reason" to reason,
                    "appForeground" to appInForeground,
                ),
            )
        } else if (foregroundRequired && deniedBackoff) {
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "notification_foreground_deferred",
                fields = playerFields(session.player) + mapOf(
                    "reason" to reason,
                    "appForeground" to appInForeground,
                    "deniedBackoff" to true,
                ),
            )
        }
        val result = runCatching {
            super.onUpdateNotification(session, startInForeground)
        }.onSuccess {
            if (startInForeground) foregroundStartDeniedUntilMs = 0L
            if (isPlaybackOngoing) finishResumptionForeground()
        }.onFailure { err ->
            val foregroundDenied = isForegroundStartDenied(err)
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = if (foregroundDenied) {
                    "notification_foreground_denied"
                } else {
                    "notification_update_failed"
                },
                fields = playerFields(session.player) + mapOf(
                    "reason" to reason,
                    "startForeground" to startInForeground,
                    "errorType" to err::class.java.simpleName,
                    "message" to err.message,
                ),
            )
            if (foregroundDenied && startInForeground) {
                foregroundStartDeniedUntilMs =
                    SystemClock.elapsedRealtime() + FOREGROUND_START_DENIED_BACKOFF_MS
                runCatching { super.onUpdateNotification(session, false) }
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
        return result.isSuccess && (!foregroundRequired || isPlaybackOngoing)
    }

    private fun monitorForegroundPromotionRetry() {
        serviceScope.launch {
            AppForeground.isForeground.collect { appInForeground ->
                if (!appInForeground) return@collect
                if (resumeAfterAudioFocusLoss) {
                    maybeResumeAfterExternalAudioStops("app-foreground")
                }
                if (foregroundStartDeniedUntilMs <= 0L) return@collect
                val session = mediaSession ?: return@collect
                val player = session.player
                if (!player.playWhenReady || player.mediaItemCount == 0) return@collect
                foregroundStartDeniedUntilMs = 0L
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "notification_foreground_retry_on_app_resume",
                    fields = playerFields(player),
                )
                updateNotificationSafely(session, true, "app-foreground-retry")
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
     * "空闲"判定：player == null / 队列空，或处于 STATE_IDLE 且没有播放/恢复意图。
     * **暂停状态（playWhenReady=false 但已加载且 STATE_READY）也保留 service** ——
     * 之前用 playWhenReady 判，暂停一下再划掉就会被杀掉，用户从最近任务再点回来
     * 发现通知没了、状态丢了。网络/坏源恢复也会短暂落入 IDLE，不能在这个瞬间误杀服务。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        val recovering = notificationPlayer?.isRecovering() == true ||
            badSourceRecoveryMediaId != null ||
            networkRecoveryArmed ||
            resumeAfterAudioFocusLoss
        val shouldKeepService = player != null &&
            player.mediaItemCount > 0 &&
            (player.playbackState != Player.STATE_IDLE || player.playWhenReady || recovering)
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "task_removed",
            fields = player?.let(::playerFields).orEmpty() + mapOf(
                "recovering" to recovering,
                "networkRecoveryArmed" to networkRecoveryArmed,
                "badSourceRecovery" to (badSourceRecoveryMediaId != null),
                "autoResumeArmed" to resumeAfterAudioFocusLoss,
                "keepService" to shouldKeepService,
            ),
        )
        if (!shouldKeepService) {
            stopSelf()
            return
        }
        if (player?.playWhenReady == true) {
            if (player.playbackState == Player.STATE_IDLE) {
                notificationPlayer?.armRecoveryWindow()
                runCatching { player.prepare() }
                    .onFailure { err ->
                        DiagnosticsLogStore.record(
                            area = "playback_service",
                            event = "task_removed_prepare_failed",
                            fields = playerFields(player) + mapOf(
                                "errorType" to err::class.java.simpleName,
                                "message" to err.message,
                            ),
                        )
                    }
            }
            mediaSession?.let { session ->
                updateNotificationSafely(session, true, "task-removed-keep-alive")
            }
        }
        // 在播 / 暂停可恢复 / 弱网恢复中 → 不调 stopSelf，让媒体 service 继续存活。
    }

    override fun onDestroy() {
        PlaybackPreparationGate.reset()
        playbackResumptionEpoch += 1L
        playbackResumptionJob?.cancel()
        finishResumptionForeground()
        mediaSession?.player?.let { player ->
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "service_destroy",
                fields = playerFields(player),
            )
        }
        smartAutoMixer?.release()
        smartAutoMixer = null
        crossfadeController?.release()
        crossfadeController = null
        trackCacheWarmer?.cancel()
        trackCacheWarmer = null
        BackgroundAgentContinuation.release()
        disarmNetworkRecovery()
        cancelBufferStallCheck()
        cancelProgressWatchdog()
        badSourceRefreshJob?.cancel()
        audioFocusResumeJob?.cancel()
        audioFocusForegroundExpiryJob?.cancel()
        audioFocusPauseJob?.cancel()
        audioDuckingJob?.cancel()
        audioDuckingRestoreJob?.cancel()
        externalAudioPolicyProbeJob?.cancel()
        setExternalAudioDuckingGain(1f)
        abandonAudioFocus("service-destroy")
        unregisterPlaybackCallback()
        serviceScope.cancel()
        val releasedSession = mediaSession
        mediaSession = null
        releasedSession?.let { session ->
            session.player.release()
            session.release()
        }
        notificationPlayer = null
        super.onDestroy()
    }

    private companion object {
        private const val KEEP_ALIVE_PREPARE_COOLDOWN_MS = 1_500L
        private const val KEEP_ALIVE_NOTIFICATION_DELAY_MS = 80L
        private const val FOREGROUND_START_DENIED_BACKOFF_MS = 2 * 60 * 1000L
        private const val STREAM_URL_TIMEOUT_MS = 15_000L
        private const val PLAYBACK_RESUMPTION_TIMEOUT_MS = 30_000L
        private const val RESUMPTION_CHANNEL_ID = "pipo-playback-resumption"
        private const val RESUMPTION_NOTIFICATION_ID = 1002
        private const val BAD_SOURCE_SERVICE_REFRESH_GRACE_MS = STREAM_URL_TIMEOUT_MS + 2_000L
        private const val BAD_SOURCE_CONTROLLER_GRACE_MS = 5_000L
        // 静默缓冲卡顿:进入 BUFFERING 后等这么久,若缓冲 / 进度都没推进就判定卡死并重踢
        private const val BUFFER_STALL_CHECK_DELAY_MS = 12_000L
        // 缓冲 / 进度推进的容差(ms)—— 超过才算"在前进",避免把慢速加载误判成卡死
        private const val BUFFER_STALL_PROGRESS_TOLERANCE_MS = 250L
        // 同一首最多重踢几次,防止网络真没了时 prepare 风暴
        private const val BUFFER_STALL_MAX_ATTEMPTS = 4
        // 12s 缓冲检查先原位重试；仍持续无进展到 20s 才换源，不自动跳过音频片段。
        private const val PROGRESS_WATCHDOG_INTERVAL_MS = 1_000L
        private const val PROGRESS_STALL_THRESHOLD_MS = 20_000L
        private const val PROGRESS_WATCHDOG_INTERNAL_SEEK_GRACE_MS = 1_000L
        private const val SINGLE_ITEM_STALL_RETRY_COOLDOWN_MS = 15_000L
        private const val TRANSIENT_NETWORK_SKIP_DEFER_MS = 60_000L
        private const val NETWORK_RECOVERY_ARM_RETRY_BASE_MS = 1_500L
        private const val NETWORK_RECOVERY_ARM_MAX_ATTEMPTS = 3
        private const val AUDIO_FOCUS_FOREGROUND_GRACE_MS = 10 * 60 * 1_000L
        // 外部声音结束后要尽快重新取回焦点；原先 1.5s 首次探测叠加静默窗，
        // 会让短暂语音/视频结束后的恢复明显拖沓。
        private const val AUDIO_FOCUS_RESUME_PROBE_MS = 350L
        private const val AUDIO_FOCUS_FAILED_RETRY_BASE_MS = 3_000L
        private const val AUDIO_FOCUS_RETRY_MAX_DELAY_MS = 30_000L
        private const val AUDIO_FOCUS_POLICY_CONFIRM_MS = 120L
        private const val EXTERNAL_AUDIO_QUIET_BEFORE_RESUME_MS = 450L
        // 要覆盖 0.9s 的媒体确认/暂停阈值及主线程调度余量，否则 probe 稍晚就会
        // 被误判成“没有最近焦点信号”，持续外部媒体反而不会暂停。
        private const val EXTERNAL_FOCUS_FALLBACK_WINDOW_MS = 2_500L
        private const val EXTERNAL_MEDIA_MISFIRE_IGNORE_MS = 220L
        // 短促误触发先忽略，确认是持续媒体后先柔和 duck；超过 0.9s 才暂停。
        // 这样通知/短音不打断，短视频/游戏持续出声也不会长期与音乐混播。
        private const val EXTERNAL_MEDIA_PAUSE_AFTER_MS = 900L
        private const val EXTERNAL_VOICE_PAUSE_AFTER_MS = 8_000L
        private const val EXTERNAL_AUDIO_DUCK_FAST_ATTACK_MS = 60L
        private const val EXTERNAL_AUDIO_DUCK_NOTIFICATION_ATTACK_MS = 60L
        private const val EXTERNAL_AUDIO_DUCK_RESTORE_QUIET_MS = 220L
        private const val EXTERNAL_AUDIO_DUCK_RESTORE_MS = 220L
        private const val AUDIO_DUCKING_STATE_PROBE_MS = 250L
        private const val EXTERNAL_AUDIO_DUCK_FRAME_MS = 16L
        private const val MEDIA_TRANSIENT_DUCK_GAIN = 0.55f
        private const val VOICE_DUCK_GAIN = 0.28f
        private const val NAVIGATION_DUCK_GAIN = 0.38f
        private const val NOTIFICATION_DUCK_GAIN = 0.75f
        private const val EXTERNAL_AUDIO_DUCK_MIN_GAIN = 0.05f
        private const val EXTERNAL_AUDIO_POLICY_LOG_THROTTLE_MS = 1_000L
        private const val EXTERNAL_AUDIO_MISFIRE_LOG_THROTTLE_MS = 1_000L
        private const val LIBRARY_ROOT_ID = "pipo:root"
        private const val CURRENT_QUEUE_ID = "pipo:current-queue"
        private const val TRACK_MEDIA_ID_PREFIX = "pipo:track:"
        private const val ASSISTANT_SEARCH_LIMIT = 20
        private const val ASSISTANT_PLAY_SCAN_LIMIT = 8
        private val ASSISTANT_QUERY_STOP_WORDS = setOf(
            "a",
            "an",
            "the",
            "play",
            "song",
            "music",
            "please",
        )
        private val STREAM_LEVEL_FALLBACKS = listOf("lossless", "exhigh", "higher", "standard")
    }
}

private fun isLikelyBadSource(error: PlaybackException): Boolean = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    // 内容本身坏(容器解析/解码失败):可能是 CDN 上的坏副本,重签 URL 换源一次,
    // 还坏就跳下一首。以前由 ViewModel 直接跳,恢复权收归服务后并入坏源链路。
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
    else -> false
}

private fun isLikelyTransientNetworkError(error: PlaybackException): Boolean = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    // Doze/弱网里的 socket reset 经常只被 Media3 包成 IO_UNSPECIFIED；没有明确 4xx、
    // 解析或解码证据时先走网络恢复，不能把它当坏源在后台直接跳歌。
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true
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

private fun repeatModeName(mode: Int): String = when (mode) {
    Player.REPEAT_MODE_OFF -> "off"
    Player.REPEAT_MODE_ONE -> "one"
    Player.REPEAT_MODE_ALL -> "all"
    else -> mode.toString()
}

private fun playWhenReadyReason(reason: Int): String = when (reason) {
    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user"
    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio_focus_loss"
    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "audio_becoming_noisy"
    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
    Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end_of_media_item"
    else -> reason.toString()
}

private fun playbackSuppressionReasonName(reason: Int): String = when (reason) {
    Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "none"
    Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "transient_audio_focus_loss"
    else -> reason.toString()
}

private fun audioFocusChangeName(focusChange: Int): String = when (focusChange) {
    AudioManager.AUDIOFOCUS_GAIN -> "gain"
    AudioManager.AUDIOFOCUS_LOSS -> "loss"
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "loss_transient"
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "loss_transient_can_duck"
    else -> focusChange.toString()
}

private fun audioFocusChangeNameOrNone(focusChange: Int?): String {
    return focusChange?.let(::audioFocusChangeName) ?: "none"
}

private fun audioFocusRequestResultName(result: Int): String = when (result) {
    AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "granted"
    AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "failed"
    AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "delayed"
    else -> result.toString()
}

@Suppress("DEPRECATION")
private fun playbackUsageName(usage: Int): String = when (usage) {
    android.media.AudioAttributes.USAGE_MEDIA -> "media"
    android.media.AudioAttributes.USAGE_GAME -> "game"
    android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION -> "voice_communication"
    android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> "voice_signalling"
    android.media.AudioAttributes.USAGE_ASSISTANT -> "assistant"
    android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "accessibility"
    android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "navigation"
    android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "sonification"
    android.media.AudioAttributes.USAGE_NOTIFICATION -> "notification"
    android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT -> "notification_instant"
    android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED -> "notification_delayed"
    android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST -> "notification_request"
    android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT -> "notification_event"
    android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "ringtone"
    android.media.AudioAttributes.USAGE_ALARM -> "alarm"
    android.media.AudioAttributes.USAGE_UNKNOWN -> "unknown"
    else -> usage.toString()
}
