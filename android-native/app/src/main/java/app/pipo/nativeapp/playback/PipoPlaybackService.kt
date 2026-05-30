package app.pipo.nativeapp.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastKeepAlivePrepareAtMs: Long = 0L
    private var badSourceSkipToken: Long = 0L
    private var badSourceRecoveryMediaId: String? = null
    private var badSourceRecoveryUntilMs: Long = 0L
    // —— 静默缓冲卡顿兜底（后台 doze / 弱网下 BUFFERING 卡死又不报错时主动重踢）的状态 ——
    private var bufferStallToken: Long = 0L
    private var bufferStallScheduledForMediaId: String? = null
    private var bufferStallAttemptMediaId: String? = null
    private var bufferStallAttempts: Int = 0
    private var badSourceRefreshJob: Job? = null
    private var audioFocusResumeJob: Job? = null
    private var resumeAfterAudioFocusLoss = false
    private var playbackCallbackRegistered = false
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            maybeResumeAfterExternalAudioStops("playback-callback", configs)
        }
    }
    private val serviceUrlRefreshTried = LinkedHashSet<String>()
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
                    ),
                )
                future.set(resolved)
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
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "service_player_created",
                    fields = mapOf(
                        "wakeMode" to "network",
                        "repeatMode" to repeatModeName(repeatMode),
                    ),
                )

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
                        clearBadSourceRecovery()
                        // 换曲 = 旧的卡顿观察作废,新曲重置重踢预算
                        cancelBufferStallCheck()
                        resetBufferStallAttempts()
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                            serviceUrlRefreshTried.clear()
                        } else {
                            mediaItem?.mediaId?.let { serviceUrlRefreshTried.remove(it) }
                        }
                        smartAutoMixer?.onMediaItemTransition(mediaItem, reason)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // 控制层在线时仍优先由 PlayerViewModel 重签 URL；服务层只兜底坏源，
                        // 防止后台播放卡在同一个失效地址并触发前台服务崩溃。
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
                                    // 到 READY = 这一刻加载是通的:撤掉待检查、重置该首的重踢预算。
                                    cancelBufferStallCheck()
                                    resetBufferStallAttempts()
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
                        when (reason) {
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> {
                                if (!playWhenReady) {
                                    armAudioFocusAutoResume(this@apply)
                                }
                            }
                            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> {
                                clearAudioFocusAutoResume("play-when-ready-${playWhenReadyReason(reason)}")
                            }
                        }
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
                            armAudioFocusAutoResume(this@apply)
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
                            clearAudioFocusAutoResume("playing")
                            notificationPlayer?.clearRecoveryWindow()
                            mediaSession?.let { session ->
                                updateNotificationSafely(session, true, "playing")
                            }
                        }
                    }
                })
            }
        smartAutoMixer = SmartAutoMixer(
            mainPlayer = player,
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
            title = "PIPO",
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        )
    }

    private fun currentQueueFolder(player: Player): MediaItem {
        val count = player.mediaItemCount
        val title = if (count > 0) "当前播放列表" else "PIPO 音乐"
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
            val freshUrl = withContext(Dispatchers.IO) {
                urlResolver.fetchPlayableUrl(trackId)
            }
            if (!freshUrl.isNullOrBlank()) {
                return item.buildUpon()
                    .setMediaId(trackId.toString())
                    .setUri(freshUrl)
                    .setMediaMetadata(playableMetadata(item.mediaMetadata))
                    .build()
            }
        }

        val query = queryFromRequestedItem(item) ?: return null
        val track = withContext(Dispatchers.IO) {
            val candidates = runCatching {
                PipoGraph.repository.searchTracks(query, ASSISTANT_SEARCH_LIMIT)
            }.getOrDefault(emptyList())
            urlResolver.resolveFirstPlayable(candidates, ASSISTANT_PLAY_SCAN_LIMIT)
        } ?: return null
        return mediaFactory.toMediaItem(track)
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
                event = "keep_alive_prepare_suppressed",
                fields = playerFields(player) + mapOf("reason" to reason),
            )
            return
        }
        if (now - lastKeepAlivePrepareAtMs < KEEP_ALIVE_PREPARE_COOLDOWN_MS) return
        lastKeepAlivePrepareAtMs = now

        mainHandler.post {
            val session = mediaSession ?: return@post
            if (!player.playWhenReady || player.mediaItemCount == 0) return@post
            if (player.playbackState == Player.STATE_IDLE) {
                // Media3 默认通知在 IDLE 时会被 cancel。恢复期先 prepare 到 BUFFERING,
                // 保住前台媒体会话；真正换 URL / 跳坏歌仍由 PlayerViewModel 决定。
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

    private fun armAudioFocusAutoResume(player: Player) {
        if (player.mediaItemCount == 0 || player.currentMediaItem == null) return
        resumeAfterAudioFocusLoss = true
        registerPlaybackCallback()
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "audio_focus_auto_resume_armed",
            fields = playerFields(player),
        )
        audioFocusResumeJob?.cancel()
        audioFocusResumeJob = serviceScope.launch {
            delay(AUDIO_FOCUS_RESUME_PROBE_MS)
            maybeResumeAfterExternalAudioStops("delayed-probe")
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
        if (hasExternalActiveMedia(configs)) return
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "audio_focus_auto_resume",
            fields = playerFields(player) + mapOf("reason" to reason),
        )
        player.prepare()
        player.play()
    }

    private fun hasExternalActiveMedia(configs: List<AudioPlaybackConfiguration>): Boolean {
        return configs.any { config ->
            isMediaPlaybackUsage(config.audioAttributes.usage)
        }
    }

    private fun isMediaPlaybackUsage(usage: Int): Boolean {
        return usage == android.media.AudioAttributes.USAGE_MEDIA ||
            usage == android.media.AudioAttributes.USAGE_GAME
    }

    private fun clearAudioFocusAutoResume(reason: String) {
        if (!resumeAfterAudioFocusLoss && !playbackCallbackRegistered) return
        resumeAfterAudioFocusLoss = false
        audioFocusResumeJob?.cancel()
        audioFocusResumeJob = null
        unregisterPlaybackCallback()
        DiagnosticsLogStore.record(
            area = "playback_service",
            event = "audio_focus_auto_resume_cleared",
            fields = mapOf("reason" to reason),
        )
    }

    private fun recoverBadSourceOrSkip(player: Player, error: PlaybackException) {
        if (startServiceUrlRefresh(player, error)) return
        scheduleBadSourceSkip(player, error)
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
            val freshUrl = withContext(Dispatchers.IO) {
                urlResolver.fetchPlayableUrl(neteaseId)
            }
            if (token != badSourceSkipToken) return@launch
            if (!player.playWhenReady || player.currentMediaItem?.mediaId != mediaId) {
                clearBadSourceRecovery(mediaId)
                recordBadSourceSkipAbort(player, error, "changed-or-paused")
                return@launch
            }
            if (freshUrl.isNullOrBlank()) {
                clearBadSourceRecovery(mediaId)
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "bad_source_refresh_empty",
                    fields = playerFields(player) + mapOf(
                        "code" to error.errorCodeName,
                        "neteaseId" to neteaseId,
                    ),
                )
                scheduleBadSourceSkip(player, error)
                return@launch
            }

            runCatching {
                val liveItem = player.currentMediaItem ?: return@runCatching
                val itemIndex = player.indexOfMediaId(mediaId) ?: player.currentMediaItemIndex
                if (itemIndex !in 0 until player.mediaItemCount) return@runCatching
                clearBadSourceRecovery(mediaId)
                DiagnosticsLogStore.record(
                    area = "playback_service",
                    event = "bad_source_refresh_success",
                    fields = playerFields(player) + mapOf(
                        "code" to error.errorCodeName,
                        "neteaseId" to neteaseId,
                        "resumePositionMs" to startPositionMs,
                    ),
                )
                player.replaceMediaItem(itemIndex, liveItem.buildUpon().setUri(freshUrl).build())
                player.seekTo(itemIndex, startPositionMs)
                player.prepare()
                player.play()
            }.onFailure { err ->
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
            // 连踢几次都没活 —— 八成网络真没了,停手,交给报错恢复 / 网络回来(READY)/ 回前台,别 prepare 风暴
            DiagnosticsLogStore.record(
                area = "playback_service",
                event = "buffer_stall_give_up",
                fields = playerFields(player) + mapOf(
                    "attempts" to bufferStallAttempts,
                    "bufferedMs" to bufferedNow,
                ),
            )
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

    private fun updateNotificationSafely(
        session: MediaSession,
        startInForegroundRequired: Boolean,
        reason: String,
    ) {
        runCatching {
            onUpdateNotification(session, startInForegroundRequired)
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
        cancelBufferStallCheck()
        badSourceRefreshJob?.cancel()
        audioFocusResumeJob?.cancel()
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
        private const val STREAM_URL_TIMEOUT_MS = 15_000L
        private const val BAD_SOURCE_SERVICE_REFRESH_GRACE_MS = STREAM_URL_TIMEOUT_MS + 2_000L
        private const val BAD_SOURCE_CONTROLLER_GRACE_MS = 5_000L
        // 静默缓冲卡顿:进入 BUFFERING 后等这么久,若缓冲 / 进度都没推进就判定卡死并重踢
        private const val BUFFER_STALL_CHECK_DELAY_MS = 12_000L
        // 缓冲 / 进度推进的容差(ms)—— 超过才算"在前进",避免把慢速加载误判成卡死
        private const val BUFFER_STALL_PROGRESS_TOLERANCE_MS = 250L
        // 同一首最多重踢几次,防止网络真没了时 prepare 风暴
        private const val BUFFER_STALL_MAX_ATTEMPTS = 4
        private const val AUDIO_FOCUS_RESUME_PROBE_MS = 1_500L
        private const val LIBRARY_ROOT_ID = "pipo:root"
        private const val CURRENT_QUEUE_ID = "pipo:current-queue"
        private const val TRACK_MEDIA_ID_PREFIX = "pipo:track:"
        private const val ASSISTANT_SEARCH_LIMIT = 20
        private const val ASSISTANT_PLAY_SCAN_LIMIT = 8
        private val STREAM_LEVEL_FALLBACKS = listOf("lossless", "exhigh", "higher", "standard")
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
