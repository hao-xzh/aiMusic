package app.pipo.nativeapp.playback

import android.app.Application
import android.content.ComponentName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.pipo.nativeapp.data.BehaviorType
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.Discovery
import app.pipo.nativeapp.data.LastPlaybackStore
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoLyricLine
import app.pipo.nativeapp.data.TrackDedupe
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 播放状态 —— 镜像 src/lib/player-state.tsx 的 PlayerState。
 *
 * 跟 React 端一致的初始 state：current 为 null，queue 空，positionMs 0。
 * 没有任何虚构数据；只在拿到真账号 + 真歌单后才进入正常播放。
 */
data class PlayerUiState(
    val queue: List<NativeTrack> = emptyList(),
    val currentIndex: Int = 0,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lyrics: List<PipoLyricLine> = emptyList(),
    val isReady: Boolean = false,
) {
    val activeLyricIndex: Int
        get() = lyrics.indexOfLast { line -> positionMs >= line.startMs }.coerceAtLeast(0)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = PipoGraph.repository
    private val controllerFuture = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, PipoPlaybackService::class.java)),
    ).buildAsync()

    private var controller: MediaController? = null
    private var loadedLyricsFor: String? = null
    /** 每发一次 lyrics 拉取递增；fetch 完成时跟该值对比，过期 fetch 丢弃。
     *  比对 trackId 的方案在 A→B→A 的快速切歌下会误把 B 的延迟响应判成 "目标还是 A" 写入。 */
    private var lyricsRequestSeq: Long = 0L
    /** 上一次歌词拉取协程 —— 启新的之前 cancel,避免快速切歌时一堆协程攒着等
     *  callRaw 的 30s timeout 槽位。lyricsRequestSeq 已经保证写不进 stale 数据,
     *  这里 cancel 是早释放资源 + 避免 IO 线程堆积。 */
    private var lyricsJob: Job? = null
    /** playFromAgent / playTrack 的 phase generation —— phase-2 完成时跟当前值
     *  对比,不一致说明用户已经启了新的播放,phase-2 的 addMediaItems 不能往
     *  新队列尾部追(否则会污染新歌单)。 */
    private var playGen: Int = 0

    // 之前这里有副 ExoPlayer (OverlapPlayer) 做 crossfade 风格的两曲叠声接歌。
    // 用户反馈"听感混乱"——他们要的是 gapless（专辑模式无缝接，前一首尾巴拼下一首开头），
    // 不是 DJ 风格 crossfade。所以删掉副 player + 所有重叠淡入淡出逻辑，
    // 改用 ExoPlayer 自带 gapless transition + ClippingConfiguration 裁头尾静音
    // （toMediaItem 里已经做了）。这才是 iTunes / 网易 那种"接得像同一首"的体验。

    /**
     * 续杯式队列扩展。镜像 src/lib/player-state.tsx 的 `continuousSourceRef`：
     *   - 默认走 Discovery（同 artist + 当前 taste tags）
     *   - PipoNativeApp 在用户走 AI 选歌路径时可以替换为 pet-agent 的 source
     */
    /** 默认续杯源 —— RecommendEngine 多路召回 + 多因子打分 + 多样性 rerank。
     *  本地库 + 听歌行为 + 口味画像越丰富，结果越准；冷启动也能给"音色 + 标签"召回。
     *  在 RecommendEngine 之上再加一层"排除当前队列已有 songKey"做最终安全网。 */
    private val defaultContinuousSource = ContinuousQueueSource { excludeIds ->
        val current = state.queue.getOrNull(state.currentIndex)
        val raw = PipoGraph.recommendEngine.fetchMore(
            anchor = current,
            excludeIds = excludeIds,
            wantCount = 8,
        )
        val existingSongKeys = state.queue.mapTo(HashSet()) { TrackDedupe.songKey(it) }
        raw.filter { TrackDedupe.songKey(it) !in existingSongKeys }
    }
    private var continuousSource: ContinuousQueueSource? = defaultContinuousSource
    /** 续杯调用是否在飞行中 —— 防短时间内重复触发 */
    private var fetchingMore = false
    /** current 后剩 < N 首时触发续杯。3 = 至少留 2 首缓冲 */
    private val extendThreshold = 3
    /** URL 获取优先保留无损；无损不可用时自动降级，保证长时间播放不断。 */
    private val streamLevelFallbacks = listOf("lossless", "exhigh", "higher", "standard")
    private val urlResolver by lazy {
        PlaybackUrlResolver(repository, streamLevelFallbacks, STREAM_URL_TIMEOUT_MS)
    }
    private val mediaFactory by lazy { PlayerMediaFactory(featuresStore) }
    private val behaviorTracker by lazy { PlaybackBehaviorTracker(PipoGraph.behaviorLog, viewModelScope) }

    // gapless 模式不需要 currentPlan / overlapStartedFor / fadeOutScheduledFor / fadeJob
    // 所有跨曲过渡交给 ExoPlayer 自己处理（ConcatenatingMediaSource 内部已经做缓冲预拉）

    private val lastPlaybackStore by lazy { PipoGraph.lastPlayback }

    /** init 时加载的上次播放快照 —— controller ready 时复原到 ExoPlayer */
    private val savedSnapshot: LastPlaybackStore.Snapshot? = runCatching { PipoGraph.lastPlayback.load() }.getOrNull()

    var state by mutableStateOf(
        savedSnapshot?.let { snap ->
            val cur = snap.queue.getOrNull(snap.currentIndex)
            val resumePos = safeResumePosition(snap.positionMs, cur?.durationMs ?: 0L)
            PlayerUiState(
                queue = snap.queue,
                currentIndex = snap.currentIndex,
                title = cur?.title.orEmpty(),
                artist = cur?.artist.orEmpty(),
                album = cur?.album.orEmpty(),
                artworkUrl = cur?.artworkUrl,
                isPlaying = false,
                positionMs = resumePos,
                durationMs = cur?.durationMs ?: 0L,
                isReady = false,
            )
        } ?: PlayerUiState(),
    )
        private set

    /** 已经在本曲尝试过 URL 重签的 trackId —— 重签后再失败就别回头了，跳下一首 */
    private val urlRefreshTried = HashSet<String>()
    /** 当前正在刷新 URL 的 trackId —— 错误风暴里只让一个刷新协程在飞 */
    private var refreshingUrlForTrack: String? = null
    /** 弱网类错误的原地恢复计数；超过上限再重签 URL。 */
    private var transientRetryForTrack: String? = null
    private var transientRetryCount = 0
    private var transientRetryJob: Job? = null
    /** 防止一串坏 URL 把整张歌单瞬间跳空。真正出声后清零。 */
    private var recoverySkipCount = 0
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val nextTrackPrewarmer by lazy { NextTrackPrewarmer(appContext) }
    private var nextPrewarmJob: Job? = null
    private var prewarmingNextKey: String? = null
    private var prewarmedNextKey: String? = null
    private var stablePlaybackResetJob: Job? = null
    private var userPausedPlayback = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFrom(player)
        }

        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int,
        ) {
            // 切到新一首 = 之前那首已经播过去了（自然结束 / 用户切 / 重签后跳过来），
            // 把当前这首从"已尝试重签"集合里清出来，等若干小时后这首 URL 又过期时还能重签一次。
            // PLAYLIST_CHANGED（playFromAgent/playTrack/setMediaItems）→ 整张表清空。
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                urlRefreshTried.clear()
            } else {
                mediaItem?.mediaId?.let { urlRefreshTried.remove(it) }
            }
            transientRetryForTrack = null
            transientRetryCount = 0
            transientRetryJob?.cancel()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                logEventForPrev(BehaviorType.Completed, completionPctOverride = 1f)
                logEventForCurrent(BehaviorType.PlayStarted)
            } else {
                // 用户主动切：next/previous 里已经 log 过 Skipped/ManualCut
                logEventForCurrent(BehaviorType.PlayStarted)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlaybackError(error)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                recoverEndedPlaybackState()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                userPausedPlayback = false
                recoverySkipCount = 0
                scheduleStablePlaybackReset()
            }
        }
    }

    private fun isLikelyUrlExpiry(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true
        else -> false
    }

    private fun isTransientNetworkError(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> true
        else -> false
    }

    private fun handlePlaybackError(error: PlaybackException) {
        Log.w("PlayerVM", "playback error code=${error.errorCodeName}")
        when {
            isLikelyUrlExpiry(error) -> refreshCurrentTrackUrl("url-expiry")
            isTransientNetworkError(error) -> retryTransientNetworkError(error)
            else -> controller?.let(::skipToNextOrStop)
        }
    }

    private fun retryTransientNetworkError(error: PlaybackException) {
        val player = controller ?: return
        val track = currentTrackFor(player) ?: return
        if (transientRetryForTrack == track.id) {
            transientRetryCount += 1
        } else {
            transientRetryForTrack = track.id
            transientRetryCount = 1
        }
        if (transientRetryCount > MAX_TRANSIENT_RETRIES) {
            Log.w("PlayerVM", "network retry exhausted for ${track.title}, refreshing url")
            transientRetryForTrack = null
            transientRetryCount = 0
            refreshCurrentTrackUrl("network-retry-exhausted")
            return
        }
        val attempt = transientRetryCount
        val resumePosMs = maxOf(player.currentPosition, state.positionMs).coerceAtLeast(0L)
        val targetTrackId = track.id
        transientRetryJob?.cancel()
        transientRetryJob = viewModelScope.launch {
            delay((attempt * 1200L).coerceAtMost(3500L))
            val livePlayer = controller ?: return@launch
            val targetIdx = livePlayer.indexOfMediaId(targetTrackId) ?: return@launch
            if (currentTrackFor(livePlayer)?.id != targetTrackId) return@launch
            livePlayer.repeatMode = Player.REPEAT_MODE_ALL
            livePlayer.seekTo(targetIdx, resumePosMs)
            livePlayer.prepare()
            livePlayer.play()
            Log.w("PlayerVM", "network retry #$attempt for ${track.title}: ${error.errorCodeName}")
        }
    }

    private fun refreshCurrentTrackUrl(reason: String) {
        val player = controller ?: return
        val track = currentTrackFor(player) ?: return
        refreshTrackUrlAndResume(player, track, reason)
    }

    private fun refreshTrackUrlAndResume(
        initialPlayer: Player,
        track: NativeTrack,
        reason: String,
        force: Boolean = false,
        resumePositionMs: Long? = null,
    ) {
        val player = controller ?: initialPlayer
        if (refreshingUrlForTrack == track.id) return

        // 已经在这首上重签过一次还失败 —— 这首确实坏了（区域受限 / 真下架 / 重签 URL 也是死的）。
        // 死链一次就能确认，不需要"先重试 N 次再放弃"。
        if (!force && track.id in urlRefreshTried) {
            Log.w("PlayerVM", "${track.title} died after refresh, skipping")
            skipToNextOrStop(player)
            return
        }
        val ne = track.neteaseId ?: run {
            // 没有 neteaseId 没法重签 —— 直接跳
            skipToNextOrStop(player)
            return
        }

        if (force) {
            urlRefreshTried.remove(track.id)
        }
        urlRefreshTried.add(track.id)
        refreshingUrlForTrack = track.id
        // 错误时 player 在 IDLE，currentPosition 可能已重置。state.positionMs 由 syncFrom
        // 持续更新，是更可靠的"上一刻播到哪儿"。
        val resumePosMs = resumePositionMs
            ?: maxOf(player.currentPosition, state.positionMs).coerceAtLeast(0L)
        val targetTrackId = track.id
        viewModelScope.launch {
            try {
                val fresh = fetchPlayableUrl(ne)
                if (fresh == null) {
                    Log.w("PlayerVM", "songUrls returned no url for ${track.title}, skipping ($reason)")
                    controller?.let(::skipToNextOrStop)
                    return@launch
                }
                val livePlayer = controller ?: run {
                    urlRefreshTried.remove(targetTrackId)
                    return@launch
                }
                val updated = track.copy(streamUrl = fresh)
                val newQueue = state.queue.toMutableList().apply {
                    val stateIdx = indexOfFirst { it.id == targetTrackId }
                    if (stateIdx >= 0) set(stateIdx, updated)
                }
                state = state.copy(queue = newQueue)
                // 重签期间用户可能切歌或换歌单。用 mediaId 找位置，而不是相信旧 index。
                val updatedIdx = livePlayer.indexOfMediaId(targetTrackId)
                if (updatedIdx == null) {
                    // 手动恢复 / service 重建时，player 里可能已经没有 media items，
                    // 但 ViewModel 还保留着最后队列。此时直接重建播放器，避免 play 按钮 no-op。
                    if (force) {
                        val startPosition = resumePosMs.takeIf { it > 1000L } ?: 0L
                        livePlayer.setMediaItems(listOf(toMediaItem(updated)), 0, startPosition)
                        livePlayer.repeatMode = Player.REPEAT_MODE_ALL
                        livePlayer.prepare()
                        userPausedPlayback = false
                        livePlayer.play()
                    }
                    return@launch
                }
                livePlayer.replaceMediaItem(updatedIdx, toMediaItem(updated))
                // 只有 player 还停在被重签的这首上(没自动跳走)才接续播放;
                // 已经在播下一首时不去打断 —— replaceMediaItem 已把 URL 更新,
                // 下次 wraparound / 用户手动回来时直接是新 URL。
                if (livePlayer.currentMediaItemIndex == updatedIdx) {
                    if (resumePosMs > 1000L) {
                        livePlayer.seekTo(updatedIdx, resumePosMs)
                    } else {
                        livePlayer.seekTo(updatedIdx, 0L)
                    }
                    livePlayer.prepare()
                    userPausedPlayback = false
                    livePlayer.play()
                }
            } finally {
                refreshingUrlForTrack = null
            }
        }
    }

    private fun recoverEndedPlaybackState() {
        val player = controller ?: return
        if (state.queue.isEmpty() || userPausedPlayback) return
        Log.w("PlayerVM", "player reached STATE_ENDED, forcing repeat recovery")
        player.repeatMode = Player.REPEAT_MODE_ALL
        if (player.mediaItemCount <= 1 && recoverFromStateQueue(player, offset = 1)) return
        if (player.mediaItemCount == 0) {
            recoverForManualPlay(player)
            return
        }
        val targetIdx = if (player.mediaItemCount > 1) {
            val currentIdx = player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
            if (currentIdx + 1 < player.mediaItemCount) currentIdx + 1 else 0
        } else 0
        val track = state.queue.firstOrNull { it.id == player.getMediaItemAt(targetIdx).mediaId }
            ?: state.queue.getOrNull(targetIdx)
        if (track?.neteaseId != null) {
            refreshTrackUrlAndResume(
                initialPlayer = player,
                track = track,
                reason = "ended-recovery",
                force = true,
                resumePositionMs = 0L,
            )
        } else {
            player.seekTo(targetIdx, 0L)
            player.prepare()
            userPausedPlayback = false
            player.play()
        }
    }

    private fun currentTrackFor(player: Player): NativeTrack? {
        val mediaId = player.currentMediaItem?.mediaId
        return mediaId?.let { id -> state.queue.firstOrNull { it.id == id } }
            ?: state.queue.getOrNull(player.currentMediaItemIndex.coerceAtLeast(0))
    }

    private fun Player.indexOfMediaId(mediaId: String): Int? {
        return (0 until mediaItemCount).firstOrNull { i -> getMediaItemAt(i).mediaId == mediaId }
    }

    private fun skipToNextOrStop(p: Player) {
        if (recoverySkipCount >= MAX_RECOVERY_SKIPS) {
            Log.w("PlayerVM", "too many recovery skips in a row, staying paused")
            p.pause()
            return
        }
        if (p.mediaItemCount <= 1 && recoverFromStateQueue(p, offset = 1)) {
            recoverySkipCount += 1
            return
        }
        if (p.mediaItemCount > 1) {
            recoverySkipCount += 1
            p.repeatMode = Player.REPEAT_MODE_ALL
            val currentIdx = p.currentMediaItemIndex.coerceIn(0, p.mediaItemCount - 1)
            val nextIdx = if (currentIdx + 1 < p.mediaItemCount) currentIdx + 1 else 0
            p.seekTo(nextIdx, 0L)
            p.prepare()
            p.play()
            return
        }
        // 队列只剩这一首时不能 seek 到下一首。停下来等用户按播放；manualPlayRecovery
        // 会清掉本曲的自动恢复标记并重新签 URL，避免只能重启 app 才能再播。
        p.pause()
    }

    private fun scheduleStablePlaybackReset() {
        val player = controller ?: return
        val trackId = currentTrackFor(player)?.id ?: return
        stablePlaybackResetJob?.cancel()
        stablePlaybackResetJob = viewModelScope.launch {
            delay(STABLE_PLAYBACK_RESET_MS)
            val live = controller ?: return@launch
            if (live.isPlaying && currentTrackFor(live)?.id == trackId) {
                urlRefreshTried.remove(trackId)
                if (transientRetryForTrack == trackId) {
                    transientRetryForTrack = null
                    transientRetryCount = 0
                }
            }
        }
    }

    private fun safeResumePosition(positionMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return positionMs.coerceAtLeast(0L)
        val safe = positionMs.coerceIn(0L, durationMs)
        return if (safe >= durationMs - END_POSITION_TOLERANCE_MS) 0L else safe
    }

    private fun currentCompletionPct(): Float =
        controller?.let(behaviorTracker::currentCompletionPct) ?: 0f

    private fun logEventForCurrent(type: BehaviorType, completionPctOverride: Float? = null) {
        val player = controller ?: return
        behaviorTracker.logCurrent(player, state, type, completionPctOverride)
    }

    private fun logEventForPrev(type: BehaviorType, completionPctOverride: Float? = null) {
        val player = controller ?: return
        behaviorTracker.logPrevious(player, state, type, completionPctOverride)
    }

    init {
        controllerFuture.addListener(
            {
                controller = controllerFuture.get().also { player ->
                    player.addListener(listener)
                    if (player.mediaItemCount == 0) {
                        // 优先复原上次播放快照（同步立即装上，杀掉黑屏 + 跳到 playlist[0] 的尴尬）
                        val snap = savedSnapshot
                        if (snap != null && snap.queue.isNotEmpty()) {
                            viewModelScope.launch {
                                runCatching {
                                    val resolved = resolvePlayableQueue(snap.queue)
                                        .filter { it.streamUrl.isNotBlank() }
                                    if (resolved.isEmpty()) return@runCatching
                                    val targetIdx = snap.currentIndex.coerceIn(0, resolved.size - 1)
                                    // 冷启动：恢复后仍保持队列循环，避免播完一次就停。
                                    state = state.copy(queue = resolved, currentIndex = targetIdx)
                                    player.setMediaItems(
                                        resolved.map(::toMediaItem),
                                        targetIdx,
                                        safeResumePosition(
                                            snap.positionMs,
                                            resolved.getOrNull(targetIdx)?.durationMs ?: 0L,
                                        ),
                                    )
                                    player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                                    player.prepare()
                                    // 不要 play()——等用户点
                                    syncFrom(player)
                                }
                            }
                        } else {
                            viewModelScope.launch {
                                runCatching {
                                    repository.refreshAccount()
                                    repository.refreshPlaylists()
                                    val playlistId = repository.playlists.first().firstOrNull()?.id
                                        ?: return@runCatching
                                    val tracks = resolvePlayableQueue(repository.tracksForPlaylist(playlistId))
                                        .filter { it.streamUrl.isNotBlank() }
                                    if (tracks.isEmpty()) return@runCatching
                                    state = state.copy(queue = tracks)
                                    player.setMediaItems(tracks.map(::toMediaItem))
                                    player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                                    player.prepare()
                                    syncFrom(player)
                                }
                            }
                        }
                    } else {
                        syncFrom(player)
                    }
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun toggle() {
        val player = controller ?: return
        if (player.isPlaying) {
            userPausedPlayback = true
            player.pause()
        } else {
            // STATE_IDLE/ENDED/error 时直接 play() 经常只会拿同一条死 URL 再失败。
            // 手动按播放代表用户明确想恢复：清掉本曲自动恢复标记并重新签 URL。
            if (!recoverForManualPlay(player)) {
                ensurePlayerLive(player)
                player.play()
            }
        }
        syncFrom(player)
    }

    private fun recoverForManualPlay(player: Player): Boolean {
        val track = currentTrackFor(player) ?: return false
        val needsFreshUrl = player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED ||
            player.playerError != null ||
            track.streamUrl.isBlank()
        if (!needsFreshUrl) return false
        val ne = track.neteaseId ?: return false
        transientRetryJob?.cancel()
        transientRetryForTrack = null
        transientRetryCount = 0
        recoverySkipCount = 0
        urlRefreshTried.remove(track.id)
        val resumeTrack = track.copy(neteaseId = ne)
        val durationMs = player.duration.takeIf { it > 0 } ?: state.durationMs
        val positionMs = maxOf(player.currentPosition, state.positionMs)
        val atEnd = durationMs > 0L && positionMs >= durationMs - END_POSITION_TOLERANCE_MS
        refreshTrackUrlAndResume(
            initialPlayer = player,
            track = resumeTrack,
            reason = "manual-play",
            force = true,
            resumePositionMs = if (atEnd || player.playbackState == Player.STATE_ENDED) 0L else null,
        )
        return true
    }

    /**
     * 装入 AI 选好的播放队列。镜像 src/lib/player-state.tsx playNetease(t, queue, { continuous }) 的语义。
     *   - 整 batch 装为新队列，从第 0 首播
     *   - 把 AI 给的续杯 source 装上，队尾接近时自动从 reservoir / refill 取下一批
     */
    /**
     * 插一首到当前队列下一首位置 + 立刻跳过去播。
     * 当前歌的进度被丢掉（用户的"插队"心智 = 立刻听 X，听完回到原本的下一首）。
     * 不切歌单、不替换队列；REPEAT_MODE_ALL 保留。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun insertNext(track: NativeTrack) {
        val player = controller ?: return
        viewModelScope.launch {
            val resolved = if (track.streamUrl.isNotBlank()) {
                track
            } else {
                val id = track.neteaseId ?: return@launch
                val url = fetchPlayableUrl(id) ?: return@launch
                track.copy(streamUrl = url)
            }
            val live = controller ?: return@launch
            // 当前队列空（还没开始放过）→ 退化成"装队列 + 播"，避免插队进虚空
            if (live.mediaItemCount == 0) {
                state = state.copy(queue = listOf(resolved), currentIndex = 0)
                live.setMediaItems(listOf(toMediaItem(resolved)), 0, 0L)
                live.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                live.volume = 1f
                live.prepare()
                userPausedPlayback = false
                live.play()
                return@launch
            }
            val curIdx = live.currentMediaItemIndex.coerceAtLeast(0)
            val insertIdx = (curIdx + 1).coerceAtMost(live.mediaItemCount)
            live.addMediaItem(insertIdx, toMediaItem(resolved))
            // state.queue 跟着 player 队列一起插
            val newQueue = state.queue.toMutableList().apply {
                add(insertIdx.coerceAtMost(size), resolved)
            }
            state = state.copy(queue = newQueue)
            // 跳过去立刻播 —— "插队"语义是"我现在就要听 X"
            live.seekTo(insertIdx, 0L)
            ensurePlayerLive(live)
            userPausedPlayback = false
            live.play()
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun playFromAgent(initialBatch: List<NativeTrack>, source: ContinuousQueueSource?) {
        if (initialBatch.isEmpty()) return
        val player = controller ?: return
        // phase-1 开始前 ++ —— phase-2 在 await resolvePlayableQueue 期间用户可能再次
        // 启播(playFromAgent / playTrack / insertNext),那时 playGen 已被新一次 ++,
        // 我们这次的 phase-2 拿到 gen != playGen 就放弃 addMediaItems,避免把
        // "上一次歌单的剩余歌" 追到 "这一次新歌单" 的尾部 → 队列脏掉。
        val gen = ++playGen
        viewModelScope.launch {
            // -------- 阶段 1：解析第 1 首立刻播 --------
            val first = initialBatch.first()
            val firstResolved: NativeTrack = if (first.streamUrl.isNotBlank()) {
                first
            } else {
                val id = first.neteaseId ?: return@launch
                val url = fetchPlayableUrl(id) ?: return@launch
                first.copy(streamUrl = url)
            }
            if (gen != playGen) return@launch
            continuousSource = source
            app.pipo.nativeapp.ui.PetBubbleStateAccessor.resetForNewQueue()
            val pendingQueue = listOf(firstResolved) + initialBatch.drop(1)
            state = state.copy(queue = pendingQueue, currentIndex = 0)
            player.setMediaItems(listOf(toMediaItem(firstResolved)), 0, 0L)
            player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            player.volume = 1f
            player.prepare()
            userPausedPlayback = false
            player.play()

            // -------- 阶段 2：后台补 batch 剩下歌 --------
            val rest = initialBatch.drop(1)
            if (rest.isEmpty()) return@launch
            val resolvedRest = resolvePlayableQueue(rest).filter { it.streamUrl.isNotBlank() }
            if (resolvedRest.isEmpty()) return@launch
            // generation 检查:resolvePlayableQueue 期间用户可能已切到别的歌单,
            // 此时 playGen 已被 bump,我们这次 phase-2 是过期的,放弃追加。
            if (gen != playGen) return@launch
            // 重新拿一遍 controller —— phase-1 之后 service 可能已经 rebind/release
            val livePlayer = controller ?: return@launch
            livePlayer.addMediaItems(resolvedRest.map(::toMediaItem))
            // 用 player 实际的 currentMediaItemIndex 写 state.currentIndex —— phase-1/2
            // 之间用户可能已经按过 next（或自然播放结束），player 已经在 index 0 之外
            state = state.copy(
                queue = listOf(firstResolved) + resolvedRest,
                currentIndex = livePlayer.currentMediaItemIndex.coerceAtLeast(0),
            )
        }
    }

    /**
     * 用户从歌单点了一首具体的歌：两阶段播 —— 先把"那一首"的 URL 单独解析后立刻开播
     * （200ms 内出声），然后后台去解析整个队列剩下歌的 URL + smooth-queue 排序，
     * 解析完用 addMediaItems append 到播放器尾部。
     *
     * 之前是"等整张歌单（最多 200 首，4 次串行 HTTP）的 URL 全解析完才开播"，
     * 表现为点歌后画面切到播放页、要等 2-5s 才出声 —— 这个 fix 把 perceived
     * latency 砍掉 ~90%。镜像 src/lib/player-state.tsx playNetease 的语义。
     *
     * smooth=true 时走 SmoothQueue 重排（用户点的那首作为起点不动）。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun playTrack(track: NativeTrack, contextQueue: List<NativeTrack>, smooth: Boolean = true) {
        val player = controller ?: return
        val playable = contextQueue.filter { it.streamUrl.isNotBlank() || it.neteaseId != null }
        if (playable.isEmpty()) return
        // 同 playFromAgent 注释:phase-2 过程中用户可能再启播,gen 不一致就放弃。
        val gen = ++playGen
        viewModelScope.launch {
            // -------- 阶段 1：立刻开播"那一首" --------
            // 优先用已有 streamUrl；没有就只为这 1 个 id 发 1 次 songUrls 请求
            val pickedResolved: NativeTrack = if (track.streamUrl.isNotBlank()) {
                track
            } else {
                val id = track.neteaseId ?: return@launch
                val url = fetchPlayableUrl(id) ?: return@launch
                track.copy(streamUrl = url)
            }
            if (gen != playGen) return@launch
            // phase-1 期间显式 null —— 此时 queue 只有 1 首，开 continuous 会立刻触发
            // maybeExtendQueue（remaining=0 ≤ 阈值 3），跟 phase-2 的 addMediaItems 抢插。
            // phase-2 完成后再切回 RecommendEngine，让 "歌单播完接续相似歌" 这条 UX 也能用上。
            continuousSource = null
            app.pipo.nativeapp.ui.PetBubbleStateAccessor.resetForNewQueue()
            // 播放器先只装第一首以尽快出声，但 state 保留完整候选队列。
            // 如果 phase-2 网络慢/失败，ENDED/next 仍能从 state 里找到下一首并单独解析。
            val pickedIdxInPlayable = playable.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            val phaseOneQueue = listOf(pickedResolved) +
                playable.drop(pickedIdxInPlayable + 1) +
                playable.take(pickedIdxInPlayable)
            state = state.copy(queue = phaseOneQueue, currentIndex = 0)
            player.setMediaItems(listOf(toMediaItem(pickedResolved)), 0, 0L)
            player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            player.volume = 1f
            player.prepare()
            userPausedPlayback = false
            player.play()

            // -------- 阶段 2：后台补完整队列 --------
            // 解析剩下歌的 URL（分批 ≤ 50 个），smooth 排序，append 到播放器
            // 这一段几秒钟内完成，期间用户已经在听；REPEAT_MODE_ALL 会自然衔接
            val rest = playable.filter { it.id != track.id }
            if (rest.isEmpty()) return@launch
            val resolvedRest = resolvePlayableQueue(rest).filter { it.streamUrl.isNotBlank() }
            if (resolvedRest.isEmpty()) return@launch
            // 同 playFromAgent:resolvePlayableQueue 期间用户可能切到别的歌单
            if (gen != playGen) return@launch
            val full = listOf(pickedResolved) + resolvedRest
            val ordered = if (smooth) {
                app.pipo.nativeapp.data.SmoothQueue.smooth(
                    tracks = full,
                    featuresStore = featuresStore,
                    startTrackId = track.id,
                    mode = app.pipo.nativeapp.data.SmoothQueue.Mode.Library,
                )
            } else full
            // 把 picked 之后的 + 之前的（环形）依次 append。SmoothQueue 通常把
            // startTrackId 放在 index 0，但保险起见按实际位置切
            val pickIdxInOrdered = ordered.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            val tail = ordered.drop(pickIdxInOrdered + 1) + ordered.take(pickIdxInOrdered)
            // 重新拿 controller —— suspend 期间 service 可能已经 rebind/release
            val livePlayer = controller ?: return@launch
            if (tail.isNotEmpty()) {
                livePlayer.addMediaItems(tail.map(::toMediaItem))
            }
            // 用 player 实际 currentMediaItemIndex —— phase-1/2 之间用户可能按过 next
            state = state.copy(
                queue = listOf(pickedResolved) + tail,
                currentIndex = livePlayer.currentMediaItemIndex.coerceAtLeast(0),
            )
            // continuousSource 保持 null —— 歌单 tap = 就播这张歌单，REPEAT_MODE_ALL
            // 在尾部循环。不挂任何"相似续杯"，那是 explicit Discovery 入口的事
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun next() {
        controller?.let { p ->
            val pct = currentCompletionPct()
            logEventForCurrent(
                if (pct < 0.5f) BehaviorType.Skipped else BehaviorType.ManualCut,
                completionPctOverride = pct,
            )
            userPausedPlayback = false
            seekByOffsetOrReplay(p, offset = 1)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun previous() {
        controller?.let { p ->
            logEventForCurrent(BehaviorType.ManualCut, completionPctOverride = currentCompletionPct())
            userPausedPlayback = false
            seekByOffsetOrReplay(p, offset = -1)
        }
    }

    private fun seekByOffsetOrReplay(p: Player, offset: Int) {
        if (p.mediaItemCount <= 1 && recoverFromStateQueue(p, offset)) {
            return
        }
        if (p.mediaItemCount == 0) {
            recoverForManualPlay(p)
            return
        }
        p.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
        val count = p.mediaItemCount
        val currentIdx = p.currentMediaItemIndex.coerceIn(0, count - 1)
        val targetIdx = Math.floorMod(currentIdx + offset, count)
        p.seekTo(targetIdx, 0L)
        p.prepare()
        p.play()
        syncFrom(p)
    }

    private fun recoverFromStateQueue(player: Player, offset: Int): Boolean {
        val queue = state.queue
        if (queue.size <= 1) return false
        val currentId = player.currentMediaItem?.mediaId
        val currentIdx = currentId
            ?.let { id -> queue.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: state.currentIndex.coerceIn(0, queue.lastIndex)
        val targetIdx = Math.floorMod(currentIdx + offset, queue.size)
        val rotated = queue.drop(targetIdx) + queue.take(targetIdx)
        val gen = ++playGen
        viewModelScope.launch {
            val firstResolved = resolveFirstPlayable(rotated) ?: return@launch
            if (gen != playGen) return@launch
            val live = controller ?: return@launch
            val resolvedIdx = rotated.indexOfFirst { it.id == firstResolved.id }.coerceAtLeast(0)
            val pendingQueue = listOf(firstResolved) +
                rotated.drop(resolvedIdx + 1) +
                rotated.take(resolvedIdx)
            state = state.copy(queue = pendingQueue, currentIndex = 0)
            live.setMediaItems(listOf(toMediaItem(firstResolved)), 0, 0L)
            live.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            live.volume = 1f
            live.prepare()
            userPausedPlayback = false
            live.play()

            val rest = pendingQueue.drop(1)
            val resolvedRest = resolvePlayableQueue(rest).filter { it.streamUrl.isNotBlank() }
            if (gen != playGen || resolvedRest.isEmpty()) return@launch
            val liveAfterResolve = controller ?: return@launch
            liveAfterResolve.addMediaItems(resolvedRest.map(::toMediaItem))
            state = state.copy(
                queue = listOf(firstResolved) + resolvedRest,
                currentIndex = liveAfterResolve.currentMediaItemIndex.coerceAtLeast(0),
            )
        }
        return true
    }

    /**
     * Player 在 IDLE / ENDED 状态下，seek/next/prev 都是 no-op。
     * 调用 prepare 把 player 拉回可用态（IDLE 时） 或 seek 到 default position（ENDED 时）。
     * 没有副作用 —— 已经在 READY/BUFFERING 的 player 调 prepare 也安全。
     */
    private fun ensurePlayerLive(p: Player) {
        when (p.playbackState) {
            Player.STATE_IDLE -> p.prepare()
            Player.STATE_ENDED -> {
                // ENDED + REPEAT_MODE_OFF 时 hasNextMediaItem=false → seekToNext no-op。
                // 强制开循环 + seek 回首项让 next 也能"绕回去"
                p.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                p.seekToDefaultPosition(p.currentMediaItemIndex.coerceAtLeast(0))
                p.prepare()
            }
            else -> { /* READY / BUFFERING — 啥都不用做 */ }
        }
    }

    fun seekTo(fraction: Float) {
        val player = controller ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        syncFrom(player)
    }

    fun refreshPosition() {
        controller?.let(::syncFrom)
    }

    override fun onCleared() {
        controller?.let { p ->
            val q = state.queue
            if (q.isNotEmpty()) {
                runCatching {
                    lastPlaybackStore.save(q, p.currentMediaItemIndex.coerceAtLeast(0), p.currentPosition.coerceAtLeast(0))
                }
            }
        }
        controller?.removeListener(listener)
        nextPrewarmJob?.cancel()
        nextTrackPrewarmer.cancel()
        stablePlaybackResetJob?.cancel()
        MediaController.releaseFuture(controllerFuture)
        controller = null
        super.onCleared()
    }

    private fun syncFrom(player: Player) {
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val queue = state.queue
        // 关键：trackId 以 player.currentMediaItem.mediaId 为权威源，不再相信 state.queue[index]。
        // 之前 phase-1/phase-2 期间（state.queue 还没追上 player 实际队列）会出现
        // "用 queue 里的旧 track.id 拉歌词、配上 player 实际在播的另一首" → 歌词不对。
        val playerMediaId = player.currentMediaItem?.mediaId
        val track = queue.getOrNull(index)
        val authoritativeTrackId = playerMediaId ?: track?.id
        if (authoritativeTrackId != null && loadedLyricsFor != authoritativeTrackId) {
            loadedLyricsFor = authoritativeTrackId
            // 立刻清空旧歌词 —— 拉新歌词有 100-500ms 网络延迟，期间宁可空白也不要
            // 把 A 的歌词留在 B 上"对不上"
            if (state.lyrics.isNotEmpty()) {
                state = state.copy(lyrics = emptyList())
            }
            val targetTrackId = authoritativeTrackId
            // 用单调递增 token 而不是 trackId 做竞态保护 ——
            // A→B→A 切歌时 trackId 会回到 A，老的 A 拉取已经被新 B 覆盖（loadedLyricsFor=B），
            // 然后又被新 A 覆盖（loadedLyricsFor=A），此时如果用 trackId 比对，老的 A
            // 拉取（可能比新 A 拉取先到）会通过校验，把过期的（甚至来源不对的）lines 写入。
            // counter 唯一递增，每次发起 +1，回来时只有"当前最新"的 token 能写
            lyricsRequestSeq += 1
            val mySeq = lyricsRequestSeq
            lyricsJob?.cancel()
            lyricsJob = viewModelScope.launch {
                val lines = runCatching {
                    repository.lyricsForTrack(targetTrackId)
                }.getOrDefault(emptyList())
                if (lyricsRequestSeq == mySeq) {
                    state = state.copy(lyrics = lines)
                }
            }
        }
        // 单曲首次出场时触发语义标注（fire-and-forget；LLM 失败也不影响播放）
        maybeTriggerSemantic(track)
        state = state.copy(
            currentIndex = index,
            title = player.mediaMetadata.title?.toString() ?: track?.title.orEmpty(),
            artist = player.mediaMetadata.artist?.toString() ?: track?.artist.orEmpty(),
            album = player.mediaMetadata.albumTitle?.toString() ?: track?.album.orEmpty(),
            artworkUrl = player.mediaMetadata.artworkUri?.toString() ?: track?.artworkUrl,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0 } ?: track?.durationMs ?: 0L,
            isReady = true,
        )
        // 续杯：current 后剩 < 阈值时调一次 fetchMore
        maybeExtendQueue(player)
        maybePrewarmNextTrack(player)
        // 节流持久化：杀掉冷启动黑屏 + 跳到 playlist[0] 的尴尬
        if (queue.isNotEmpty()) {
            lastPlaybackStore.saveThrottled(queue, index, state.positionMs)
        }
    }

    private fun maybePrewarmNextTrack(player: Player) {
        if (!player.isPlaying) return
        val durationMs = player.duration.takeIf { it > 0 } ?: state.durationMs
        if (durationMs <= 0L) return
        val positionMs = player.currentPosition.coerceAtLeast(state.positionMs)
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        val shouldPrewarm = positionMs >= (durationMs * PREWARM_AFTER_PROGRESS).toLong() ||
            remainingMs <= PREWARM_WHEN_REMAINING_MS
        if (!shouldPrewarm) return
        val nextTrack = nextTrackFor(player) ?: return
        val nextKey = prewarmKey(nextTrack)
        if (nextKey == prewarmedNextKey || nextKey == prewarmingNextKey) return

        nextPrewarmJob?.cancel()
        nextTrackPrewarmer.cancel()
        prewarmingNextKey = nextKey
        nextPrewarmJob = viewModelScope.launch {
            val playable = refreshTrackForPrewarm(nextTrack) ?: run {
                if (prewarmingNextKey == nextKey) prewarmingNextKey = null
                return@launch
            }
            val playableKey = prewarmKey(playable)
            val warmed = withTimeoutOrNull(NEXT_PREWARM_TIMEOUT_MS) {
                nextTrackPrewarmer.prewarm(playable)
            } ?: run {
                nextTrackPrewarmer.cancel()
                false
            }
            if (warmed) {
                prewarmedNextKey = playableKey
            }
            if (prewarmingNextKey == nextKey || prewarmingNextKey == playableKey) {
                prewarmingNextKey = null
            }
        }
    }

    private suspend fun refreshTrackForPrewarm(track: NativeTrack): NativeTrack? {
        val refreshed = track.neteaseId
            ?.let { id -> fetchPlayableUrl(id)?.let { url -> track.copy(streamUrl = url) } }
            ?: track.takeIf { it.streamUrl.isNotBlank() }
            ?: return null
        val player = controller ?: return refreshed
        val itemIndex = player.indexOfMediaId(refreshed.id) ?: return refreshed
        val queueIndex = state.queue.indexOfFirst { it.id == refreshed.id }
        if (queueIndex >= 0 && state.queue[queueIndex].streamUrl != refreshed.streamUrl) {
            val nextQueue = state.queue.toMutableList().apply { set(queueIndex, refreshed) }
            state = state.copy(queue = nextQueue)
        }
        if (itemIndex != player.currentMediaItemIndex) {
            runCatching { player.replaceMediaItem(itemIndex, toMediaItem(refreshed)) }
        }
        return refreshed
    }

    private fun nextTrackFor(player: Player): NativeTrack? {
        val queue = state.queue
        if (queue.size < 2) return null
        val currentId = player.currentMediaItem?.mediaId
        val currentIndex = currentId
            ?.let { id -> queue.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: state.currentIndex.coerceIn(0, queue.lastIndex)
        val nextIndex = if (currentIndex + 1 < queue.size) currentIndex + 1 else 0
        if (nextIndex == currentIndex) return null
        return queue.getOrNull(nextIndex)
    }

    private fun prewarmKey(track: NativeTrack): String = "${track.id}:${track.streamUrl}"

    private fun maybeExtendQueue(player: Player) {
        if (fetchingMore) return
        val source = continuousSource ?: return
        val queue = state.queue
        val remaining = queue.size - state.currentIndex - 1
        if (remaining > extendThreshold) return
        fetchingMore = true
        viewModelScope.launch {
            try {
                val excludeIds = queue.mapNotNull { it.neteaseId }.toSet()
                val more = source.fetchMore(excludeIds)
                if (more.isEmpty()) {
                    // source 跑空了 —— 不关闭循环。
                    // 之前这里关闭循环，导致：当前队列播完
                    // → STATE_ENDED → seekToNextMediaItem 是 no-op → 用户感觉
                    // "播完就停，next 也按不动，要重启 app"。
                    // 现在改成：拆掉 source（不再尝试续杯）但保留 REPEAT_MODE_ALL，
                    // 让现有队列继续循环，至少能听。
                    continuousSource = null
                    return@launch
                }
                val resolved = resolvePlayableQueue(more).filter { it.streamUrl.isNotBlank() }
                if (resolved.isEmpty()) return@launch
                state = state.copy(queue = queue + resolved)
                player.addMediaItems(resolved.map(::toMediaItem))
            } finally {
                fetchingMore = false
            }
        }
    }

    private fun toMediaItem(track: NativeTrack) = mediaFactory.toMediaItem(track)

    /** 已经分析过的 Symphonia 特征—— 全局 AudioFeaturesStore（跨 session 持久 + 蒸馏复用） */
    private val featuresStore by lazy { PipoGraph.audioFeaturesStore }

    /**
     * 语义档案 + embedding 索引以前在每曲首播时叫一次 LLM —— 一首歌额外两次 AI 请求，
     * 用户反馈"太频繁"。改成只在 Distill 批量跑（蒸馏是显式 opt-in 的"昂贵分析"事件）。
     * 这里留空保持 syncFrom 调用点干净；想恢复就把这两行加回来。
     */
    private fun maybeTriggerSemantic(@Suppress("UNUSED_PARAMETER") track: app.pipo.nativeapp.data.NativeTrack?) {
        // 故意空：单曲索引集中到 Distill batch，每首歌减少 2 次 AI 调用
    }

    private suspend fun fetchPlayableUrl(id: Long): String? = urlResolver.fetchPlayableUrl(id)

    private suspend fun resolveSinglePlayable(track: NativeTrack): NativeTrack? =
        urlResolver.resolveSinglePlayable(track)

    private suspend fun resolveFirstPlayable(candidates: List<NativeTrack>): NativeTrack? {
        return urlResolver.resolveFirstPlayable(candidates, RECOVERY_SCAN_LIMIT)
    }

    private suspend fun resolvePlayableQueue(queue: List<NativeTrack>): List<NativeTrack> =
        urlResolver.resolvePlayableQueue(queue)

    companion object {
        private const val STREAM_URL_TIMEOUT_MS = 15_000L
        private const val MAX_TRANSIENT_RETRIES = 2
        private const val MAX_RECOVERY_SKIPS = 2
        private const val PREWARM_AFTER_PROGRESS = 0.55f
        private const val PREWARM_WHEN_REMAINING_MS = 90_000L
        private const val NEXT_PREWARM_TIMEOUT_MS = 20_000L
        private const val STABLE_PLAYBACK_RESET_MS = 12_000L
        private const val END_POSITION_TOLERANCE_MS = 1_500L
        private const val RECOVERY_SCAN_LIMIT = 8
    }
}
