package app.pipo.nativeapp.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.runtime.AppForeground
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var progressStallAttemptMediaId: String? = null
    private var progressStallAttempts: Int = 0
    private var progressStallSkipAheadMediaId: String? = null
    private var progressWatchdogInternalSeekUntilMs: Long = 0L
    private val learnedProgressStallSkips = LinkedHashMap<String, ProgressStallSkip>()
    private var lastTransientNetworkErrorAtMs: Long = 0L
    private var badSourceRefreshJob: Job? = null
    private var audioFocusResumeJob: Job? = null
    private var audioFocusPauseJob: Job? = null
    private var resumeAfterAudioFocusLoss = false
    private var autoPausingForExternalAudio = false
    private var hasAudioFocus = false
    private var waitingForAudioFocusGain = false
    private var externalAudioObservedSincePause = false
    private var externalAudioQuietSinceMs = 0L
    private var lastExternalFocusInterruptionAtMs = 0L
    private var audioFocusRequest: AudioFocusRequest? = null
    private var playbackCallbackRegistered = false
    /** 等系统 AUDIOFOCUS_GAIN 回调的连续探测次数 —— 防"对方静默弃焦点、GAIN 永远不来"的死等 */
    private var focusGainWaitProbes = 0
    private var trackCacheWarmer: TrackCacheWarmer? = null
    // —— 网络恢复回调:兜底重试放弃 / 等网络期间注册,网络一回来立刻续播。
    // 没有它,后台弱网卡死只能等用户回前台(前台那套 20s 自愈是 UI 帧驱动的)。 ——
    private var networkRecoveryArmed = false
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
            maybePauseForExternalAudio("playback-callback", configs)
            maybeResumeAfterExternalAudioStops("playback-callback", configs)
        }
    }
    private data class ProgressStallSkip(
        val fromMs: Long,
        val toMs: Long,
        val learnedAtMs: Long,
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
            val items = currentQueuePlayableItems(player)
            val startIndex = if (items.isEmpty()) {
                C.INDEX_UNSET
            } else {
                player.currentMediaItemIndex.coerceIn(0, items.lastIndex)
            }
            val positionMs = if (startIndex == C.INDEX_UNSET) {
                C.TIME_UNSET
            } else {
                player.currentPosition.coerceAtLeast(0L)
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(items, startIndex, positionMs),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        val cacheDataSourceFactory = PipoMediaDataSources.cacheFactory(this)

        val musicAttrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val platformMusicAttrs = android.media.AudioAttributes.Builder()
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .build()
        // 不声明 willPauseWhenDucked：通知音等短暂事件由系统自动压低音量(auto-duck)，
        // 不进我们的自定义暂停路径 —— 之前声明了却既不暂停也不压音量，通知音全音量叠播。
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(platformMusicAttrs)
            .setAcceptsDelayedFocusGain(false)
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
                        smartAutoMixer?.onMainPlayerEvent()
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
                        // 换曲 = 旧的卡顿观察作废,新曲重置重踢预算
                        cancelBufferStallCheck()
                        resetBufferStallAttempts()
                        // 换曲(含 URL 重签 replace):丢掉旧 URI 的整曲预热,起新曲的
                        trackCacheWarmer?.cancel()
                        trackCacheWarmer?.maybeWarmCurrent(this@apply)
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
                                keepMediaNotificationAlive(this@apply, "idle")
                            }
                            Player.STATE_ENDED -> cancelBufferStallCheck()
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
                                    // 开始整曲灌缓存(幂等):整曲落盘后当前曲播放彻底脱离网络
                                    trackCacheWarmer?.maybeWarmCurrent(this@apply)
                                } else if (playWhenReady && mediaItemCount > 0) {
                                    // 进入 BUFFERING 且想播 —— 排一个延时检查,后台静默卡死时兜底重踢。
                                    scheduleBufferStallCheck(this@apply)
                                }
                            }
                        }
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        DiagnosticsLogStore.record(
                            area = "playback_service",
                            event = "play_when_ready_changed",
                            fields = playerFields(this@apply) + mapOf(
                                "playWhenReady" to playWhenReady,
                                "reason" to playWhenReadyReason(reason),
                            ),
                        )
                        // 进度看门狗随"想播"开关:想播就盯,暂停就歇(playWhenReady 不受 buffering/ready 抖动影响)
                        if (playWhenReady) armProgressWatchdog(this@apply) else cancelProgressWatchdog()
                        if (playWhenReady && !requestAudioFocusForPlayback("play-when-ready")) {
                            autoPausingForExternalAudio = false
                            clearAudioFocusAutoResume("focus-request-denied")
                            runCatching { this@apply.pause() }
                            updatePlaybackCallbackRegistration(this@apply)
                            return
                        }
                        if (playWhenReady) {
                            notificationPlayer?.armRecoveryWindow()
                            mediaSession?.let { session ->
                                updateNotificationSafely(session, true, "play-when-ready")
                            }
                        }
                        when (reason) {
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> {
                                if (!playWhenReady) {
                                    armAudioFocusAutoResume(
                                        player = this@apply,
                                        reason = "audio-focus-loss",
                                        observedExternalAudio = true,
                                        waitForAudioFocusGain = true,
                                    )
                                }
                            }
                            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> {
                                if (!playWhenReady && autoPausingForExternalAudio) {
                                    autoPausingForExternalAudio = false
                                    val shouldWaitForFocusGain = waitingForAudioFocusGain && !hasAudioFocus
                                    armAudioFocusAutoResume(
                                        player = this@apply,
                                        reason = "external-audio-pause",
                                        observedExternalAudio = true,
                                        waitForAudioFocusGain = shouldWaitForFocusGain,
                                    )
                                } else if (!playWhenReady) {
                                    clearAudioFocusAutoResume("play-when-ready-${playWhenReadyReason(reason)}")
                                    abandonAudioFocus("play-when-ready-${playWhenReadyReason(reason)}")
                                } else {
                                    clearAudioFocusAutoResume("play-when-ready-${playWhenReadyReason(reason)}")
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
                                )
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
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
                            maybePauseForExternalAudio("is-playing")
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
        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, libraryCallback)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
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
    ) {
        if (player.mediaItemCount == 0 || player.currentMediaItem == null) return
        resumeAfterAudioFocusLoss = true
        waitingForAudioFocusGain = waitForAudioFocusGain
        focusGainWaitProbes = 0
        if (observedExternalAudio) {
            externalAudioObservedSincePause = true
        }
        externalAudioQuietSinceMs = 0L
        registerPlaybackCallback()
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "audio_focus_auto_resume_armed",
            fields = playerFields(player) + mapOf(
                "reason" to reason,
                "observedExternalAudio" to externalAudioObservedSincePause,
                "waitForAudioFocusGain" to waitingForAudioFocusGain,
            ),
        )
        audioFocusResumeJob?.cancel()
        scheduleAudioFocusResumeProbe(AUDIO_FOCUS_RESUME_PROBE_MS, "delayed-probe")
    }

    private fun scheduleAudioFocusResumeProbe(delayMs: Long, reason: String) {
        audioFocusResumeJob?.cancel()
        audioFocusResumeJob = serviceScope.launch {
            delay(delayMs)
            maybeResumeAfterExternalAudioStops(reason)
        }
    }

    private fun scheduleAudioFocusPauseProbe(reason: String) {
        audioFocusPauseJob?.cancel()
        audioFocusPauseJob = serviceScope.launch {
            delay(AUDIO_FOCUS_PAUSE_CONFIRM_MS)
            maybePauseForExternalAudio("audio-focus-$reason")
        }
    }

    private fun updatePlaybackCallbackRegistration(player: Player? = mediaSession?.player) {
        val shouldMonitor = resumeAfterAudioFocusLoss ||
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
        if (hasAudioFocus) return true
        val request = audioFocusRequest ?: return true
        val result = audioManager.requestAudioFocus(request)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasAudioFocus = granted
        waitingForAudioFocusGain = result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
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
                waitingForAudioFocusGain = false
                focusGainWaitProbes = 0
                maybeResumeAfterExternalAudioStops("audio-focus-gain")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                waitingForAudioFocusGain = false
                lastExternalFocusInterruptionAtMs = SystemClock.elapsedRealtime()
                if (player?.isPlaying == true || player?.playWhenReady == true) {
                    maybePauseForExternalAudio("audio-focus-loss")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                waitingForAudioFocusGain = true
                lastExternalFocusInterruptionAtMs = SystemClock.elapsedRealtime()
                if (player?.isPlaying == true || player?.playWhenReady == true) {
                    scheduleAudioFocusPauseProbe("loss-transient")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasAudioFocus = false
                waitingForAudioFocusGain = true
                if (player?.isPlaying == true || player?.playWhenReady == true) {
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
        if (player.isPlaying || player.mediaItemCount == 0 || player.currentMediaItem == null) {
            clearAudioFocusAutoResume("not-needed-$reason")
            return
        }
        val allowMediaWithoutRecentFocus = waitingForAudioFocusGain && !hasAudioFocus
        if (hasExternalInterruptingAudio(configs, allowMediaWithoutRecentFocus = allowMediaWithoutRecentFocus)) {
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
                ),
            )
            scheduleAudioFocusResumeProbe(AUDIO_FOCUS_RESUME_PROBE_MS, "wait-external-observed")
            return
        }
        if (waitingForAudioFocusGain && !hasAudioFocus) {
            focusGainWaitProbes += 1
            if (focusGainWaitProbes <= FOCUS_GAIN_WAIT_PROBE_LIMIT) {
                scheduleAudioFocusResumeProbe(AUDIO_FOCUS_RESUME_PROBE_MS, "wait-focus-gain")
                return
            }
            // 系统的 AUDIOFOCUS_GAIN 回调等不到(对方静默弃焦/部分 OEM 不派发)。
            // 外部声音已停,别无限死等 —— 放下等待标记,走下面"主动重新请求焦点"恢复。
            // 之前这里会每 1.5s 空转直到用户回前台手动播放("后台暂停打开 app 才好"一例)。
            waitingForAudioFocusGain = false
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "audio_focus_gain_wait_timeout",
                fields = playerFields(player) + mapOf("probes" to focusGainWaitProbes),
            )
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
            scheduleAudioFocusResumeProbe(AUDIO_FOCUS_RESUME_PROBE_MS, "focus-not-ready")
            return
        }
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "audio_focus_auto_resume",
            fields = playerFields(player) + mapOf(
                "reason" to reason,
                "quietForMs" to quietForMs,
            ),
        )
        player.prepare()
        player.play()
    }

    private fun maybePauseForExternalAudio(
        reason: String,
        configs: List<AudioPlaybackConfiguration> = audioManager.activePlaybackConfigurations,
    ) {
        val player = mediaSession?.player ?: return
        if (player.mediaItemCount == 0 || player.currentMediaItem == null) return
        if (!player.playWhenReady && !player.isPlaying) return
        if (!hasExternalInterruptingAudio(configs, allowMediaWithoutRecentFocus = false)) return
        if (!resumeAfterAudioFocusLoss) {
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "external_audio_auto_pause",
                fields = playerFields(player) + mapOf(
                    "reason" to reason,
                    "activeUsages" to activeUsageSummary(configs),
                ),
            )
        }
        autoPausingForExternalAudio = true
        armAudioFocusAutoResume(
            player = player,
            reason = "external-audio-$reason",
            observedExternalAudio = true,
            waitForAudioFocusGain = !hasAudioFocus,
        )
        runCatching { player.pause() }
            .onFailure { err ->
                autoPausingForExternalAudio = false
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "external_audio_auto_pause_failed",
                    fields = playerFields(player) + mapOf(
                        "reason" to reason,
                        "errorType" to err::class.java.simpleName,
                        "message" to err.message,
                    ),
                )
            }
        mainHandler.postDelayed({
            autoPausingForExternalAudio = false
        }, EXTERNAL_AUDIO_PAUSE_FLAG_CLEAR_MS)
    }

    private fun hasExternalInterruptingAudio(
        configs: List<AudioPlaybackConfiguration>,
        allowMediaWithoutRecentFocus: Boolean,
    ): Boolean {
        val interruptingUsages = configs.map { it.audioAttributes.usage }
            .filter(::isInterruptingPlaybackUsage)
        if (interruptingUsages.isEmpty()) return false
        if (interruptingUsages.any(::isVoiceOrAssistantUsage)) return true
        val externalMediaCount = interruptingUsages.count(::isMediaPlaybackUsage) - expectedOwnMediaPlaybackCount()
        if (externalMediaCount <= 0) return false
        return allowMediaWithoutRecentFocus || hasRecentExternalFocusInterruption()
    }

    private fun expectedOwnMediaPlaybackCount(): Int {
        val player = mediaSession?.player
        val mainExpected = if (player?.isPlaying == true) 1 else 0
        val auxExpected = if (crossfadeController?.hasActiveAuxPlayback == true) 1 else 0
        return mainExpected + auxExpected
    }

    private fun isInterruptingPlaybackUsage(usage: Int): Boolean {
        return isMediaPlaybackUsage(usage) || isVoiceOrAssistantUsage(usage)
    }

    private fun isMediaPlaybackUsage(usage: Int): Boolean {
        return usage == android.media.AudioAttributes.USAGE_MEDIA ||
            usage == android.media.AudioAttributes.USAGE_GAME
    }

    private fun isVoiceOrAssistantUsage(usage: Int): Boolean {
        return usage == android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION ||
            usage == android.media.AudioAttributes.USAGE_ASSISTANT
    }

    private fun hasRecentExternalFocusInterruption(): Boolean {
        return SystemClock.elapsedRealtime() - lastExternalFocusInterruptionAtMs <=
            EXTERNAL_MEDIA_FOCUS_LINK_WINDOW_MS
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

    private fun clearAudioFocusAutoResume(reason: String) {
        if (!resumeAfterAudioFocusLoss) {
            audioFocusResumeJob?.cancel()
            audioFocusResumeJob = null
            externalAudioObservedSincePause = false
            externalAudioQuietSinceMs = 0L
            waitingForAudioFocusGain = false
            updatePlaybackCallbackRegistration()
            return
        }
        resumeAfterAudioFocusLoss = false
        audioFocusResumeJob?.cancel()
        audioFocusResumeJob = null
        externalAudioObservedSincePause = false
        externalAudioQuietSinceMs = 0L
        waitingForAudioFocusGain = false
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
        val startPositionMs = player.currentPosition.coerceAtLeast(0L)
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
                val itemIndex = player.indexOfMediaId(mediaId) ?: player.currentMediaItemIndex
                if (itemIndex !in 0 until player.mediaItemCount) return@runCatching
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
                        .setCustomCacheKey(liveItem.localConfiguration?.customCacheKey ?: fresh.cacheKey)
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

    private fun Player.indexOfMediaId(mediaId: String): Int? {
        return (0 until mediaItemCount).firstOrNull { i -> getMediaItemAt(i).mediaId == mediaId }
    }

    private fun isWaitingForBadSourceController(player: Player, now: Long): Boolean {
        val mediaId = player.currentMediaItem?.mediaId ?: return false
        return mediaId == badSourceRecoveryMediaId && now < badSourceRecoveryUntilMs
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
        progressStallSkipAheadMediaId = null
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

    private fun armProgressWatchdog(player: Player) {
        maxReachedPositionMs = player.currentPosition.coerceAtLeast(0L)
        lastProgressAtMs = SystemClock.elapsedRealtime()
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
     * 进度看门狗:只看 position 是否前进,不看 state。补 buffer_stall 的盲区 —— "buffering/ready 高频
     * 抖动但 position 不动"会把 buffer_stall 的检查定时器反复 cancel,导致无限卡死(用户遇到
     * 的"放着放着卡住、暂停加载暂停加载")。这里以 playWhenReady 为生命周期(抖动骗不过它),想播却
     * ≥PROGRESS_STALL_THRESHOLD_MS 没前进就直接小步前跳。Good Days 这类固定坏片段原地 prepare 无效,
     * 用户手动跳到 7~9s 能续播,所以自动恢复也走同一策略。
     */
    private fun evaluateProgressWatchdog(player: Player) {
        if (!player.playWhenReady || player.mediaItemCount == 0) return // 暂停/空 → 歇着,等下次 arm
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val now = SystemClock.elapsedRealtime()
        if (isWaitingForBadSourceController(player, now)) {
            // URL 重签 / 跳过正在进行,让它走完,别插手
            scheduleProgressWatchdog(player)
            return
        }
        val positionNow = player.currentPosition.coerceAtLeast(0L)
        if (maybeSkipKnownProgressStall(player, mediaId, positionNow, now)) {
            scheduleProgressWatchdog(player)
            return
        }
        // 只有"超过历史最大已播位置"才算真前进 —— buffering/ready 抖动里的倒退后小幅涨回不算,
        // 否则反复清零重试计数 → 永不升级、无限重踢(Good Days 日志里 attempt 一直=1)。
        if (positionNow > maxReachedPositionMs + BUFFER_STALL_PROGRESS_TOLERANCE_MS) {
            maxReachedPositionMs = positionNow
            lastProgressAtMs = now
            resetProgressStallAttempts()
            scheduleProgressWatchdog(player)
            return
        }
        if (now - lastProgressAtMs >= PROGRESS_STALL_THRESHOLD_MS) {
            if (progressStallAttemptMediaId != mediaId) {
                progressStallAttemptMediaId = mediaId
                progressStallAttempts = 0
                progressStallSkipAheadMediaId = null
            }
            if (progressStallSkipAheadMediaId != mediaId && tryProgressStallSkipAhead(player, mediaId, positionNow, now)) {
                lastProgressAtMs = now
            } else {
                // 小步前跳后还卡 = 不是单个坏片段,是这首在该位置源/URL 拉不到数据。
                // 升级:重签 URL → 换 URI 续播;拿不到 / 已重签过 → 跳到下一首。不再干重踢。
                progressStallAttempts += 1
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
            // 升级后交给 recoverSilentStall(进入 bad-source-recovery 窗口),这里不再插手
        }
        scheduleProgressWatchdog(player)
    }

    private fun tryProgressStallSkipAhead(player: Player, mediaId: String, positionMs: Long, now: Long): Boolean {
        val targetMs = progressStallTargetMs(player, positionMs + PROGRESS_STALL_SKIP_AHEAD_MS)
        if (targetMs <= positionMs + BUFFER_STALL_PROGRESS_TOLERANCE_MS) return false
        progressStallSkipAheadMediaId = mediaId
        progressStallAttempts += 1
        maxReachedPositionMs = targetMs
        rememberProgressStallSkip(mediaId, positionMs, targetMs, now)
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "progress_stall_skip_ahead",
            fields = playerFields(player) + mapOf(
                "attempt" to progressStallAttempts,
                "fromPositionMs" to positionMs,
                "toPositionMs" to targetMs,
                "stalledMs" to (now - lastProgressAtMs),
            ),
        )
        runCatching {
            markProgressWatchdogInternalSeek()
            player.seekTo(targetMs)
            player.prepare()
        }
        return true
    }

    private fun maybeSkipKnownProgressStall(
        player: Player,
        mediaId: String,
        positionMs: Long,
        now: Long,
    ): Boolean {
        val learnedSkip = learnedProgressStallSkips[mediaId] ?: return false
        if (now - learnedSkip.learnedAtMs > PROGRESS_STALL_LEARNED_SKIP_TTL_MS) {
            learnedProgressStallSkips.remove(mediaId)
            return false
        }
        val skipWindowStartMs = (learnedSkip.fromMs - PROGRESS_STALL_KNOWN_SKIP_LEAD_MS).coerceAtLeast(0L)
        val skipWindowEndMs = learnedSkip.fromMs + BUFFER_STALL_PROGRESS_TOLERANCE_MS
        if (positionMs !in skipWindowStartMs..skipWindowEndMs) return false

        val targetMs = progressStallTargetMs(player, learnedSkip.toMs)
        if (targetMs <= positionMs + BUFFER_STALL_PROGRESS_TOLERANCE_MS) return false
        maxReachedPositionMs = targetMs
        lastProgressAtMs = now
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "progress_stall_known_skip",
            fields = playerFields(player) + mapOf(
                "fromPositionMs" to positionMs,
                "toPositionMs" to targetMs,
                "learnedFromMs" to learnedSkip.fromMs,
                "learnedToMs" to learnedSkip.toMs,
            ),
        )
        runCatching {
            markProgressWatchdogInternalSeek()
            player.seekTo(targetMs)
            player.prepare()
        }
        return true
    }

    private fun progressStallTargetMs(player: Player, requestedTargetMs: Long): Long {
        val durationMs = player.duration.takeIf { it > 0L && it != C.TIME_UNSET }
        val maxTargetMs = durationMs?.let { it - PROGRESS_STALL_SKIP_AHEAD_END_GUARD_MS }
        return if (maxTargetMs != null) {
            requestedTargetMs.coerceAtMost(maxTargetMs)
        } else {
            requestedTargetMs
        }
    }

    private fun rememberProgressStallSkip(mediaId: String, fromMs: Long, toMs: Long, now: Long) {
        if (mediaId.isBlank()) return
        trimExpiredProgressStallSkips(now)
        learnedProgressStallSkips.remove(mediaId)
        learnedProgressStallSkips[mediaId] = ProgressStallSkip(fromMs = fromMs, toMs = toMs, learnedAtMs = now)
        while (learnedProgressStallSkips.size > PROGRESS_STALL_LEARNED_SKIP_MAX) {
            val oldestMediaId = learnedProgressStallSkips.keys.firstOrNull() ?: break
            learnedProgressStallSkips.remove(oldestMediaId)
        }
    }

    private fun trimExpiredProgressStallSkips(now: Long) {
        val iterator = learnedProgressStallSkips.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.learnedAtMs > PROGRESS_STALL_LEARNED_SKIP_TTL_MS) {
                iterator.remove()
            }
        }
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
                val idx = player.indexOfMediaId(mediaId) ?: player.currentMediaItemIndex
                if (idx !in 0 until player.mediaItemCount) return@runCatching
                val resumePositionMs = maxOf(startPositionMs, player.currentPosition.coerceAtLeast(0L))
                notificationPlayer?.armRecoveryWindow()
                markServiceUrlRefreshReplacement(mediaId)
                player.replaceMediaItem(
                    idx,
                    mediaItem.buildUpon()
                        .setUri(fresh.url)
                        .setCustomCacheKey(mediaItem.localConfiguration?.customCacheKey ?: fresh.cacheKey)
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
        if (!player.playWhenReady || player.mediaItemCount <= 1) return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex == player.currentMediaItemIndex) return
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
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "network_recovery_armed",
                    fields = mapOf("reason" to reason),
                )
            }
            .onFailure { err ->
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "network_recovery_arm_failed",
                    fields = mapOf(
                        "reason" to reason,
                        "errorType" to err::class.java.simpleName,
                        "message" to err.message,
                    ),
                )
            }
    }

    private fun disarmNetworkRecovery() {
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

    private fun updateNotificationSafely(
        session: MediaSession,
        startInForegroundRequired: Boolean,
        reason: String,
    ) {
        val now = SystemClock.elapsedRealtime()
        val appInForeground = AppForeground.isForeground.value
        val startInForeground = startInForegroundRequired && appInForeground
        if (startInForegroundRequired && !startInForeground) {
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "notification_foreground_deferred",
                fields = playerFields(session.player) + mapOf(
                    "reason" to reason,
                    "appForeground" to appInForeground,
                    "deniedBackoff" to (now < foregroundStartDeniedUntilMs),
                ),
            )
        }
        runCatching {
            onUpdateNotification(session, startInForeground)
        }.onSuccess {
            if (startInForeground) foregroundStartDeniedUntilMs = 0L
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
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "task_removed",
            fields = player?.let(::playerFields).orEmpty(),
        )
        if (player == null ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_IDLE
        ) {
            stopSelf()
        }
        // 在播 / 暂停可恢复 → 不调 stopSelf, 让前台 service 继续吃通知活
    }

    override fun onDestroy() {
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
        disarmNetworkRecovery()
        cancelBufferStallCheck()
        cancelProgressWatchdog()
        badSourceRefreshJob?.cancel()
        audioFocusResumeJob?.cancel()
        audioFocusPauseJob?.cancel()
        abandonAudioFocus("service-destroy")
        unregisterPlaybackCallback()
        serviceScope.cancel()
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
        private const val FOREGROUND_START_DENIED_BACKOFF_MS = 2 * 60 * 1000L
        private const val STREAM_URL_TIMEOUT_MS = 15_000L
        private const val BAD_SOURCE_SERVICE_REFRESH_GRACE_MS = STREAM_URL_TIMEOUT_MS + 2_000L
        private const val BAD_SOURCE_CONTROLLER_GRACE_MS = 5_000L
        // 静默缓冲卡顿:进入 BUFFERING 后等这么久,若缓冲 / 进度都没推进就判定卡死并重踢
        private const val BUFFER_STALL_CHECK_DELAY_MS = 12_000L
        // 缓冲 / 进度推进的容差(ms)—— 超过才算"在前进",避免把慢速加载误判成卡死
        private const val BUFFER_STALL_PROGRESS_TOLERANCE_MS = 250L
        // 同一首最多重踢几次,防止网络真没了时 prepare 风暴
        private const val BUFFER_STALL_MAX_ATTEMPTS = 4
        // 进度看门狗:每 0.5s 看一次 position;想播却约 1.5s 没前进就判定卡住并小步前跳。
        private const val PROGRESS_WATCHDOG_INTERVAL_MS = 500L
        private const val PROGRESS_STALL_THRESHOLD_MS = 1_250L
        private const val PROGRESS_STALL_SKIP_AHEAD_MS = 4_000L
        private const val PROGRESS_STALL_SKIP_AHEAD_END_GUARD_MS = 1_500L
        private const val PROGRESS_STALL_KNOWN_SKIP_LEAD_MS = 800L
        private const val PROGRESS_STALL_LEARNED_SKIP_TTL_MS = 6 * 60 * 60 * 1000L
        private const val PROGRESS_STALL_LEARNED_SKIP_MAX = 32
        private const val PROGRESS_WATCHDOG_INTERNAL_SEEK_GRACE_MS = 1_000L
        private const val TRANSIENT_NETWORK_SKIP_DEFER_MS = 60_000L
        private const val AUDIO_FOCUS_RESUME_PROBE_MS = 1_500L
        /** 等系统 GAIN 回调最多探测次数(×1.5s),超过即放弃死等、主动重新请求焦点 */
        private const val FOCUS_GAIN_WAIT_PROBE_LIMIT = 4
        private const val AUDIO_FOCUS_PAUSE_CONFIRM_MS = 350L
        private const val EXTERNAL_AUDIO_PAUSE_FLAG_CLEAR_MS = 500L
        private const val EXTERNAL_AUDIO_QUIET_BEFORE_RESUME_MS = 1_200L
        private const val EXTERNAL_MEDIA_FOCUS_LINK_WINDOW_MS = 2_000L
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
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
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
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> true
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
