package app.pipo.nativeapp.playback

import android.app.Application
import android.content.ComponentName
import android.os.SystemClock
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.BehaviorType
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.Discovery
import app.pipo.nativeapp.data.LastPlaybackStore
import app.pipo.nativeapp.data.LyricTiming
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.PipoLyricLine
import app.pipo.nativeapp.data.PipoLyricAlignment
import app.pipo.nativeapp.data.RecommendationLog
import app.pipo.nativeapp.data.SmoothQueue
import app.pipo.nativeapp.data.TrackDedupe
import app.pipo.nativeapp.data.TransitionScore
import app.pipo.nativeapp.data.coverImageRequest
import app.pipo.nativeapp.data.agent.memory.AgentLedgerStore
import app.pipo.nativeapp.playback.orchestrator.AgentQueueRequest
import app.pipo.nativeapp.playback.orchestrator.CommittedQueuePlan
import app.pipo.nativeapp.playback.orchestrator.CommittedQueuePlanStore
import app.pipo.nativeapp.playback.orchestrator.PlaybackOrchestrator
import app.pipo.nativeapp.playback.orchestrator.PlaybackQueueWriter
import app.pipo.nativeapp.playback.orchestrator.PlaybackSessionManager
import app.pipo.nativeapp.playback.orchestrator.QueueCommitResult
import app.pipo.nativeapp.playback.orchestrator.QueueOperation
import app.pipo.nativeapp.playback.orchestrator.TransitionPreparer
import coil.Coil
import coil.request.SuccessResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    val currentTrackId: String? = null,
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val lyrics: List<PipoLyricLine> = emptyList(),
    val isLyricsLoading: Boolean = false,
    val isReady: Boolean = false,
    val isLoading: Boolean = false,
    val playbackMode: PlaybackQueueMode = PlaybackQueueMode.ShufflePlay,
)

enum class PlaybackQueueMode {
    ShufflePlay,
    OrderOnce,
    PlaylistLoop,
    SingleLoop,
    AiRadio,
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
    private var currentLyricsRequest: LyricsRequest? = null
    private var nextLyricsRequest: LyricsRequest? = null

    /** 只保留当前曲和实际下一首；切歌时接管同一请求，不再重复走多来源查询。 */
    private class LyricsRequest(
        val trackId: String,
        val track: NativeTrack?,
        scope: CoroutineScope,
        repository: PipoRepository,
    ) {
        var result: Result<List<PipoLyricLine>>? = null
            private set

        val deferred = scope.async {
            val loaded = try {
                Result.success(
                    if (track != null) repository.lyricsForTrack(track)
                    else repository.lyricsForTrack(trackId),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            result = loaded
            loaded
        }

        fun matches(id: String, metadata: NativeTrack?): Boolean {
            if (trackId != id) return false
            if (track == null || metadata == null) return true
            return track.neteaseId == metadata.neteaseId &&
                track.title == metadata.title && track.artist == metadata.artist && track.album == metadata.album &&
                (track.durationMs <= 0L || metadata.durationMs <= 0L ||
                    kotlin.math.abs(track.durationMs - metadata.durationMs) <= 1_000L)
        }
    }
    /** playFromAgent / playTrack 的 phase generation —— phase-2 完成时跟当前值
     *  对比,不一致说明用户已经启了新的播放,phase-2 的 addMediaItems 不能往
     *  新队列尾部追(否则会污染新歌单)。 */
    private var playGen: Int = 0

    private sealed interface PendingAgentPlayback {
        data class Replace(
            val initialBatch: List<NativeTrack>,
            val source: ContinuousQueueSource?,
            val queueVersion: Long,
            val requestId: String,
        ) : PendingAgentPlayback

        data class Insert(val track: NativeTrack, val queueVersion: Long, val requestId: String) : PendingAgentPlayback
        data class QueueNext(val track: NativeTrack, val queueVersion: Long, val requestId: String) : PendingAgentPlayback
        data class InsertBatch(
            val tracks: List<NativeTrack>,
            val jumpToInserted: Boolean,
            val queueVersion: Long,
            val requestId: String,
        ) : PendingAgentPlayback
    }

    private var pendingAgentPlayback: PendingAgentPlayback? = null

    private data class QueueRecommendationExclusions(
        val trackIds: Set<Long>,
        val songKeys: Set<String>,
    )

    private val queueRecommendationAvoidIds = LinkedHashSet<Long>()
    private val queueRecommendationAvoidSongKeys = LinkedHashSet<String>()
    private var activeRecommendationContextText: String? = null

    // 真正的播放权威仍是 MediaSessionService 里的主 ExoPlayer。Service 侧的 SmartAutoMixer
    // 只在 TransitionScore 高置信度时做单播放器智能切换；这里负责把队列排到更容易接。

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
            wantCount = QUEUE_EXTEND_FETCH_CANDIDATE_COUNT,
        )
        val existingSongKeys = state.queue.mapTo(HashSet()) { TrackDedupe.songKey(it) }
        val unique = raw.filter { TrackDedupe.songKey(it) !in existingSongKeys }
        if (current != null && unique.size > 1) {
            SmoothQueue.smooth(
                tracks = listOf(current) + unique,
                featuresStore = featuresStore,
                startTrackId = current.id,
                mode = SmoothQueue.Mode.Discovery,
            ).filter { it.id != current.id }
        } else {
            unique
        }
    }
    private var continuousSource: ContinuousQueueSource? = null
    /** 最近一次 Agent 主队列的续播源。
     * active source 受播放模式控制：OrderOnce/Shuffle 不续，AiRadio 才启用；
     * InsertNext 不会写这个字段，所以“下一首插歌”不会污染当前主指令的自动续播。 */
    private var lastAgentContinuousSource: ContinuousQueueSource? = null
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
            PlayerUiState(
                queue = snap.queue,
                currentIndex = snap.currentIndex,
                title = cur?.title.orEmpty(),
                artist = cur?.artist.orEmpty(),
                album = cur?.album.orEmpty(),
                artworkUrl = cur?.artworkUrl,
                isPlaying = false,
                durationMs = cur?.durationMs ?: 0L,
                isReady = false,
            )
        } ?: PlayerUiState(),
    )
        private set

    /**
     * 播放进度(毫秒)—— 从 [state] 拆出来的高频状态。
     *
     * 30Hz 的 refreshPosition 只更新这一个 Long,不再每帧重建整个 PlayerUiState。这样只有真正
     * 用到进度的叶子(进度条 / 时间标签 / 歌词时钟)才随之重组;顶层 shell 与播放页其余部分
     * 只在元数据(标题、封面、播放态…)真正变化时才重组,告别 30Hz 全树 churn。
     */
    var positionMs by mutableLongStateOf(
        savedSnapshot?.let { snap ->
            val cur = snap.queue.getOrNull(snap.currentIndex)
            safeResumePosition(snap.positionMs, cur?.durationMs ?: 0L)
        } ?: 0L,
    )
        private set

    var lyricPlaybackSpeed by mutableStateOf(1f)
        private set

    var lyricPlaybackDiscontinuitySequence by mutableLongStateOf(0L)
        private set

    /** 已经在本曲尝试过 URL 重签的 trackId —— 重签后再失败就别回头了，跳下一首 */
    private val urlRefreshTried = HashSet<String>()
    /** 当前正在刷新 URL 的 trackId —— 错误风暴里只让一个刷新协程在飞 */
    private var refreshingUrlForTrack: String? = null
    /** 这次 PLAYLIST_CHANGED 是否只是我们用新 URL 替换了同一首，而不是用户换队列 */
    private var urlRefreshReplacementTrackId: String? = null
    /** 防止一串坏 URL 把整张歌单瞬间跳空。真正出声后清零。 */
    private var recoverySkipCount = 0
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val nextTrackPrewarmer by lazy { NextTrackPrewarmer(appContext) }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val transitionPreparer by lazy {
        TransitionPreparer(
            urlResolver = urlResolver,
            prewarmer = nextTrackPrewarmer,
            featuresStore = featuresStore,
            repository = repository,
        )
    }
    private val playbackSessionManager by lazy {
        PlaybackSessionManager(
            writer = object : PlaybackQueueWriter {
                override fun replaceQueue(plan: CommittedQueuePlan): Boolean {
                    originalQueueOrder = plan.tracks.map { it.id }
                    val accepted = if (plan.preserveCurrent) {
                        replaceFutureFromAgent(
                            plan.tracks,
                            continuousSourceForPlan(plan),
                            queueVersion = plan.queueVersion,
                            requestId = plan.requestId,
                        )
                    } else {
                        playFromAgent(
                            plan.tracks,
                            continuousSourceForPlan(plan),
                            queueVersion = plan.queueVersion,
                            requestId = plan.requestId,
                        )
                    }
                    // AI 出过的整组都进推荐日志（Source.Pet）：短时间内再要同风格时，
                    // ranker/在线回填把这批沉底，不再出一模一样的歌单。
                    if (accepted) {
                        rememberQueueRecommendations(plan.tracks, RecommendationLog.Source.Pet)
                    }
                    return accepted
                }

                override fun insertNext(plan: CommittedQueuePlan): Boolean {
                    if (plan.tracks.isEmpty()) return false
                    val accepted = queueNextBatch(
                        tracks = plan.tracks,
                        jumpToInserted = plan.jumpToInserted,
                        queueVersion = plan.queueVersion,
                        requestId = plan.requestId,
                    )
                    if (accepted) {
                        rememberQueueRecommendations(plan.tracks, RecommendationLog.Source.Pet)
                    }
                    return accepted
                }
            },
        )
    }

    private fun continuousSourceForPlan(plan: CommittedQueuePlan): ContinuousQueueSource? {
        plan.continuous?.let { return it }
        val manualOrigin = plan.sourceUserText.startsWith("manual_") || plan.sourceUserText == "taste_screen_play"
        return defaultContinuousSource.takeIf {
            manualOrigin && preferredPlaybackMode == PlaybackQueueMode.AiRadio
        }
    }

    private fun rememberActiveRecommendationContext(request: AgentQueueRequest) {
        if (request.operation == QueueOperation.InsertNext) return
        val text = request.sourceUserText.trim()
        if (text.isBlank() || isSystemRecommendationContext(text)) return
        activeRecommendationContextText = text.take(240)
    }

    private fun recommendationFeedbackContext(): String? {
        activeRecommendationContextText?.let { return it }
        val planText = CommittedQueuePlanStore.current()?.sourceUserText?.trim().orEmpty()
        return planText.takeIf { it.isNotBlank() && !isSystemRecommendationContext(it) }
    }

    private fun recordRecommendationRejection(track: NativeTrack, contextText: String?) {
        // Queue removal is an explicit, cross-context dislike. Keep the context signal too,
        // but do not make persistence depend on an active AI request.
        runCatching { PipoGraph.recommendationFeedbackLog.reject(track) }
        val text = contextText?.trim().orEmpty()
        if (text.isNotBlank()) {
            runCatching { PipoGraph.recommendationFeedbackLog.rejectForContext(track, text) }
        }
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "recommendation_context_rejected",
            fields = mapOf(
                "trackId" to track.id,
                "neteaseId" to track.neteaseId,
                "title" to track.title,
                "artist" to track.artist,
                "context" to text.take(96),
            ),
        )
    }

    private fun isSystemRecommendationContext(text: String): Boolean {
        return text.startsWith("manual_") ||
            text == "taste_screen_play" ||
            text == "queue_extend_append" ||
            text == "force_extend_and_play_next" ||
            text == "recover_from_state_queue"
    }

    private val playbackOrchestrator by lazy {
        PlaybackOrchestrator(
            featuresStore = featuresStore,
            sessionManager = playbackSessionManager,
        )
    }
    private var transitionPrepareJob: Job? = null
    private var nextPrewarmJob: Job? = null
    private var nextArtworkPrefetchJob: Job? = null
    private var nextArtworkPrefetchKey: Pair<String, String>? = null
    private var nextArtworkPrefetched = false
    private var nextArtworkRetryAtMs = 0L
    private var prewarmingNextKey: String? = null
    private var prewarmedNextKey: String? = null
    /** 让路后复用同一首已解析 URL，恢复水位时不反复触发重签。 */
    private var resolvedPrewarmTrackId: String? = null
    private var resolvedPrewarmTrack: NativeTrack? = null
    private val prewarmFailureBackoffUntilMs = HashMap<String, Long>()
    private var autoMixFeatureJob: Job? = null
    private var autoMixFeatureKey: String? = null
    private val autoMixFeatureBackoffUntilMs = HashMap<String, Long>()
    private var queueExtendBackoffUntilMs = 0L
    private var queueExtendFailureCount = 0
    private var queueExtendSkipDiagKey: String? = null
    private var queueExtendSkipDiagAtMs = 0L
    private var stablePlaybackResetJob: Job? = null
    private var userPausedPlayback = false
    private var resolvingPlayback = false
    private var preferredPlaybackMode: PlaybackQueueMode = PlaybackQueueMode.ShufflePlay

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFrom(player)
            behaviorTracker.onIsPlayingChanged(player, state, player.isPlaying)
        }

        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int,
        ) {
            lyricPlaybackDiscontinuitySequence += 1L
            controller?.let { behaviorTracker.onMediaItemTransition(it, state) }
            DiagnosticsLogStore.record(
                area = "playback",
                event = "media_transition",
                fields = mapOf(
                    "reason" to mediaTransitionReason(reason),
                    "mediaId" to mediaItem?.mediaId,
                    "title" to mediaItem?.mediaMetadata?.title?.toString(),
                ),
            )
            updateUrlRefreshTriedOnTransition(mediaItem?.mediaId, reason)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                if (behaviorTracker.previousReachedCompletionThreshold()) {
                    logEventForPrev(BehaviorType.Completed, completionPctOverride = 1f)
                }
                logEventForCurrent(BehaviorType.PlayStarted)
                maybeExtendQueue(trigger = "media_transition_auto", logSkips = true)
                // 自动 wrap 时新曲已经开始播放；这里只做无打断扩队，不能再走
                // force-next/fallback seek，否则会把同一首已经播出的开头归零重放。
                recordAutoWrapPlaybackPreserved(mediaItem)
            } else {
                // 用户主动切：next/previous 里已经 log 过 Skipped/ManualCut
                logEventForCurrent(BehaviorType.PlayStarted)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val sameItem = oldPosition.mediaItem?.mediaId == newPosition.mediaItem?.mediaId &&
                oldPosition.mediaItemIndex == newPosition.mediaItemIndex
            val sourceAdjustment = sameItem && (
                reason == Player.DISCONTINUITY_REASON_INTERNAL ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                )
            // 起播/seek 后的数据源位置修正不是一次新的用户跳转，不能重播已画出的歌词。
            // 显式 seek、切歌和单曲循环仍立即重锚，包括真正的向后拖动。
            if (!sourceAdjustment) lyricPlaybackDiscontinuitySequence += 1L
            DiagnosticsLogStore.record(
                area = "lyrics",
                event = "position_discontinuity",
                fields = mapOf(
                    "trackId" to newPosition.mediaItem?.mediaId,
                    "reason" to reason, "clockReset" to !sourceAdjustment,
                    "fromMs" to oldPosition.positionMs, "toMs" to newPosition.positionMs,
                ),
            )
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
            controller?.let { behaviorTracker.onIsPlayingChanged(it, state, isPlaying) }
            if (isPlaying) {
                userPausedPlayback = false
                recoverySkipCount = 0
                scheduleStablePlaybackReset()
            }
        }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        DiagnosticsLogStore.record(
            area = "playback",
            event = "player_error",
            fields = currentTrackFields() + mapOf(
                "code" to error.errorCodeName,
                "positionMs" to positionMs,
                "durationMs" to state.durationMs,
                "message" to error.message,
            ),
        )
        // 错误恢复的唯一权威是 PipoPlaybackService(后台也活着):URL 重签、瞬时网络
        // 重试、跳坏源、网络恢复续播全在服务端。这里只记日志、不再插手 —— 之前两层
        // 对同一错误各自"重签→replace→seek→prepare→play",一次故障被打断两次,
        // 正是"播放中突然停顿又继续"的放大器;后台时这层又不在,行为还不一致。
    }

    private fun markUrlRefreshReplacement(trackId: String) {
        urlRefreshReplacementTrackId = trackId
    }

    private fun updateUrlRefreshTriedOnTransition(mediaId: String?, reason: Int) {
        val isRefreshReplacement = mediaId != null && mediaId == urlRefreshReplacementTrackId
        if (isRefreshReplacement) {
            urlRefreshReplacementTrackId = null
        }
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            if (!isRefreshReplacement) {
                urlRefreshTried.clear()
            }
        } else {
            mediaId?.let { urlRefreshTried.remove(it) }
        }
    }

    private fun refreshTrackUrlAndResume(
        initialPlayer: Player,
        track: NativeTrack,
        reason: String,
        force: Boolean = false,
        resumePositionMs: Long? = null,
        resumeAsCurrent: Boolean = false,
    ) {
        val player = controller ?: initialPlayer
        if (refreshingUrlForTrack == track.id) return
        if (CrossfadeController.protectsMediaItem(track.id)) return
        val endedRecovery = reason == "ended-recovery"
        val recoveryMediaId = player.currentMediaItem?.mediaId
        val recoveryQueueVersion = PlaybackSessionClock.currentQueueVersion()

        // 已经在这首上重签过一次还失败 —— 这首确实坏了（区域受限 / 真下架 / 重签 URL 也是死的）。
        // 死链一次就能确认，不需要"先重试 N 次再放弃"。
        if (!force && track.id in urlRefreshTried) {
            DiagnosticsLogStore.record(
                area = "playback",
                event = "url_refresh_failed_skip",
                fields = trackFields(track) + mapOf("reason" to reason),
            )
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
        DiagnosticsLogStore.record(
            area = "playback",
            event = "url_refresh_start",
            fields = trackFields(track) + mapOf(
                "reason" to reason,
                "force" to force,
            ),
        )
        // 错误时 player 在 IDLE，currentPosition 可能已重置。positionMs 由 syncFrom
        // 持续更新，是更可靠的"上一刻播到哪儿"。
        val resumePosMs = resumePositionMs
            ?: maxOf(player.currentPosition, positionMs).coerceAtLeast(0L)
        val targetTrackId = track.id
        setResolvingPlayback(true)
        viewModelScope.launch {
            try {
                val fresh = fetchPlayable(ne)
                val livePlayer = controller ?: run {
                    urlRefreshTried.remove(targetTrackId)
                    return@launch
                }
                if (CrossfadeController.protectsMediaItem(targetTrackId) ||
                    (endedRecovery &&
                        (CrossfadeController.ownsTransition ||
                            livePlayer.playbackState != Player.STATE_ENDED ||
                            livePlayer.currentMediaItem?.mediaId != recoveryMediaId ||
                            !PlaybackSessionClock.isCurrent(recoveryQueueVersion) || userPausedPlayback))
                ) return@launch
                if (fresh == null) {
                    DiagnosticsLogStore.record(
                        area = "playback",
                        event = "url_refresh_empty",
                        fields = trackFields(track) + mapOf("reason" to reason),
                    )
                    controller?.let(::skipToNextOrStop)
                    return@launch
                }
                DiagnosticsLogStore.record(
                    area = "playback",
                    event = "url_refresh_success",
                    fields = trackFields(track) + mapOf("reason" to reason),
                )
                val updated = track.withPlayable(fresh)
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
                        val liveCurrentPosMs = if (livePlayer.currentMediaItem?.mediaId == targetTrackId) {
                            livePlayer.currentPosition.coerceAtLeast(0L)
                        } else {
                            0L
                        }
                        val startPosition = maxOf(resumePosMs, liveCurrentPosMs)
                        markUrlRefreshReplacement(targetTrackId)
                        livePlayer.setMediaItems(listOf(toMediaItem(updated)), 0, startPosition)
                        applyPlaybackMode(livePlayer)
                        livePlayer.prepare()
                        userPausedPlayback = false
                        livePlayer.play()
                    }
                    return@launch
                }
                val updatedQueueIndex = state.queue.indexOfFirst { it.id == targetTrackId }
                // replace 可能暂时把播放器的位置归零；必须在改音源之前保存当前听到的位置。
                val replacingCurrent = livePlayer.currentMediaItemIndex == updatedIdx
                val positionBeforeReplacement = if (replacingCurrent) livePlayer.currentPosition.coerceAtLeast(0L) else 0L
                val replacement = if (replacingCurrent) {
                    livePlayer.getMediaItemAt(updatedIdx).buildUpon()
                        .setUri(fresh.url)
                        .setCustomCacheKey(fresh.cacheKey)
                        .build()
                } else if (updatedQueueIndex >= 0) {
                    toMediaItem(updated, state.queue, updatedQueueIndex)
                } else {
                    toMediaItem(updated)
                }
                markUrlRefreshReplacement(targetTrackId)
                livePlayer.replaceMediaItem(
                    updatedIdx,
                    replacement,
                )
                // 只有 player 还停在被重签的这首上(没自动跳走)才接续播放;
                // 已经在播下一首时不去打断 —— replaceMediaItem 已把 URL 更新,
                // 下次 wraparound / 用户手动回来时直接是新 URL。
                if (resumeAsCurrent || livePlayer.currentMediaItemIndex == updatedIdx) {
                    val liveCurrentPosMs = if (livePlayer.currentMediaItemIndex == updatedIdx) {
                        livePlayer.currentPosition.coerceAtLeast(0L)
                    } else {
                        0L
                    }
                    val latestResumePosMs = maxOf(resumePosMs, positionBeforeReplacement, liveCurrentPosMs)
                    livePlayer.seekTo(updatedIdx, latestResumePosMs)
                    livePlayer.prepare()
                    userPausedPlayback = false
                    livePlayer.play()
                }
            } finally {
                refreshingUrlForTrack = null
                setResolvingPlayback(false)
            }
        }
    }

    private fun recoverEndedPlaybackState() {
        val player = controller ?: return
        if (BackgroundAgentContinuation.isActive()) return
        // 下一首可能已由辅助播放器播出了头段，ENDED 的唯一接管者此时是 Service。
        if (CrossfadeController.ownsTransition || player.playbackState != Player.STATE_ENDED) return
        if (state.queue.isEmpty() || userPausedPlayback) return
        if (state.playbackMode == PlaybackQueueMode.OrderOnce) return
        DiagnosticsLogStore.record(
            area = "playback",
            event = "ended_recovery",
            fields = currentTrackFields(),
        )
        applyPlaybackMode(player)
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

    private fun manualResumeTrackFor(player: Player): NativeTrack? {
        val stateTrack = state.currentTrackId
            ?.let { id -> state.queue.firstOrNull { it.id == id } }
            ?: state.queue.getOrNull(state.currentIndex)
        val playerTrack = currentTrackFor(player)
        val playerMediaId = player.currentMediaItem?.mediaId
        return if (stateTrack != null && stateTrack.id != playerMediaId) {
            stateTrack
        } else {
            playerTrack ?: stateTrack
        }
    }

    private fun currentQueueIndexFor(player: Player, queue: List<NativeTrack> = state.queue): Int {
        val mediaId = player.currentMediaItem?.mediaId
        val mapped = mediaId?.let { id -> queue.indexOfFirst { it.id == id } } ?: -1
        if (mapped >= 0) return mapped
        if (queue.isEmpty()) return 0
        return player.currentMediaItemIndex.coerceIn(0, queue.lastIndex)
    }

    private fun Player.indexOfMediaId(mediaId: String): Int? {
        return (0 until mediaItemCount).firstOrNull { i -> getMediaItemAt(i).mediaId == mediaId }
    }

    private fun skipToNextOrStop(p: Player) {
        if (recoverySkipCount >= MAX_RECOVERY_SKIPS) {
            DiagnosticsLogStore.record(
                area = "playback",
                event = "recovery_skips_exhausted",
                fields = currentTrackFields() + mapOf("count" to recoverySkipCount),
            )
            p.pause()
            return
        }
        if (p.mediaItemCount <= 1 && recoverFromStateQueue(p, offset = 1)) {
            recoverySkipCount += 1
            return
        }
        if (p.mediaItemCount > 1) {
            val currentIdx = p.currentMediaItemIndex.coerceIn(0, p.mediaItemCount - 1)
            val nextIdx = offsetTargetIndex(currentIdx, p.mediaItemCount, offset = 1) ?: run {
                p.pause()
                syncFrom(p)
                return
            }
            recoverySkipCount += 1
            applyPlaybackMode(p)
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
        behaviorTracker.logPrevious(type, completionPctOverride)
    }

    private fun deferAgentPlayback(request: PendingAgentPlayback, reason: String) {
        pendingAgentPlayback = request
        DiagnosticsLogStore.record(
            area = "queue",
            event = "agent_play_deferred",
            fields = mapOf(
                "reason" to reason,
                "type" to when (request) {
                    is PendingAgentPlayback.Replace -> "replace"
                    is PendingAgentPlayback.Insert -> "insert"
                    is PendingAgentPlayback.QueueNext -> "queue_next"
                    is PendingAgentPlayback.InsertBatch -> "insert_batch"
                },
                "count" to when (request) {
                    is PendingAgentPlayback.Replace -> request.initialBatch.size
                    is PendingAgentPlayback.Insert -> 1
                    is PendingAgentPlayback.QueueNext -> 1
                    is PendingAgentPlayback.InsertBatch -> request.tracks.size
                },
            ),
        )
    }

    private fun markAgentPlaybackStartFailed(requestId: String, queueVersion: Long, error: String) {
        if (requestId.isBlank()) return
        AgentLedgerStore.markPlaybackStart(
            context = appContext,
            requestId = requestId,
            queueVersion = queueVersion,
            actuallyStarted = false,
            error = error,
        )
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "playback_start_proof",
            fields = mapOf(
                "requestId" to requestId,
                "queueVersion" to queueVersion,
                "actuallyStarted" to false,
                "error" to error,
            ),
        )
    }

    private fun drainPendingAgentPlayback(reason: String) {
        val request = pendingAgentPlayback ?: return
        if (controller == null) return
        pendingAgentPlayback = null
        DiagnosticsLogStore.record(
            area = "queue",
            event = "agent_play_deferred_flush",
            fields = mapOf(
                "reason" to reason,
                "type" to when (request) {
                    is PendingAgentPlayback.Replace -> "replace"
                    is PendingAgentPlayback.Insert -> "insert"
                    is PendingAgentPlayback.QueueNext -> "queue_next"
                    is PendingAgentPlayback.InsertBatch -> "insert_batch"
                },
            ),
        )
        when (request) {
            is PendingAgentPlayback.Replace -> playFromAgent(request.initialBatch, request.source, request.queueVersion, request.requestId)
            is PendingAgentPlayback.Insert -> insertNext(request.track, request.queueVersion, request.requestId)
            is PendingAgentPlayback.QueueNext -> queueNext(request.track, request.queueVersion, request.requestId)
            is PendingAgentPlayback.InsertBatch ->
                queueNextBatch(request.tracks, request.jumpToInserted, request.queueVersion, request.requestId)
        }
    }

    init {
        viewModelScope.launch {
            // AI 队列临时启用的续播模式不能被音质、画像等其它设置更新覆盖。
            repository.settings
                .map { playbackModeFromSettings(it.playbackMode) }
                .distinctUntilChanged()
                .collect { mode ->
                    preferredPlaybackMode = mode
                    if (state.playbackMode != mode) {
                        applyPlaybackModePreference(mode, persist = false)
                    }
                }
        }
        controllerFuture.addListener(
            {
                runCatching {
                    val player = controllerFuture.get()
                    controller = player
                    lyricPlaybackDiscontinuitySequence += 1L
                    player.addListener(listener)
                    if (pendingAgentPlayback != null) {
                        drainPendingAgentPlayback("controller_ready")
                    } else if (player.mediaItemCount == 0) {
                        // 优先复原上次播放快照（同步立即装上，杀掉黑屏 + 跳到 playlist[0] 的尴尬）
                        val snap = savedSnapshot
                        if (snap != null && snap.queue.isNotEmpty()) {
                            viewModelScope.launch {
                                runCatching {
                                    val resolved = resolvePlayableQueue(snap.queue)
                                        .filter { it.streamUrl.isNotBlank() }
                                    if (resolved.isEmpty()) return@runCatching
                                    val targetIdx = snap.currentIndex.coerceIn(0, resolved.size - 1)
                                    state = state.copy(
                                        queue = resolved,
                                        currentIndex = targetIdx,
                                        playbackMode = preferredPlaybackMode,
                                    )
                                    player.setMediaItems(
                                        toMediaItems(resolved),
                                        targetIdx,
                                        safeResumePosition(
                                            snap.positionMs,
                                            resolved.getOrNull(targetIdx)?.durationMs ?: 0L,
                                        ),
                                    )
                                    applyPlaybackMode(player)
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
                                    state = state.copy(queue = tracks, playbackMode = preferredPlaybackMode)
                                    player.setMediaItems(toMediaItems(tracks))
                                    applyPlaybackMode(player)
                                    player.prepare()
                                    syncFrom(player)
                                }
                            }
                        }
                    } else {
                        syncFrom(player)
                    }
                    behaviorTracker.onIsPlayingChanged(player, state, player.isPlaying)
                }.onFailure { error ->
                    DiagnosticsLogStore.record(
                        area = "playback",
                        event = "controller_connect_failed",
                        fields = mapOf(
                            "error" to (error.message ?: error::class.java.simpleName),
                        ),
                    )
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun toggle() {
        val player = controller ?: return
        DiagnosticsLogStore.record(
            area = "playback",
            event = if (player.isPlaying) "pause_tap" else "play_tap",
            fields = currentTrackFields() + mapOf("positionMs" to positionMs),
        )
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
        val track = manualResumeTrackFor(player) ?: return false
        val playerMediaId = player.currentMediaItem?.mediaId
        val targetMismatch = playerMediaId != null && playerMediaId != track.id
        val needsFreshUrl = targetMismatch ||
            player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED ||
            player.playerError != null ||
            track.streamUrl.isBlank()
        if (!needsFreshUrl) return false
        val ne = track.neteaseId ?: return false
        if (targetMismatch) {
            DiagnosticsLogStore.record(
                area = "playback",
                event = "manual_resume_realign",
                fields = trackFields(track) + mapOf(
                    "playerMediaId" to playerMediaId,
                    "stateTrackId" to state.currentTrackId,
                    "queueIndex" to state.currentIndex,
                ),
            )
        }
        recoverySkipCount = 0
        urlRefreshTried.remove(track.id)
        val resumeTrack = track.copy(neteaseId = ne)
        val durationMs = if (targetMismatch) {
            track.durationMs.takeIf { it > 0 } ?: state.durationMs
        } else {
            player.duration.takeIf { it > 0 } ?: state.durationMs
        }
        val positionMs = if (targetMismatch) {
            positionMs.coerceAtLeast(0L)
        } else {
            maxOf(player.currentPosition, positionMs)
        }
        val atEnd = durationMs > 0L && positionMs >= durationMs - END_POSITION_TOLERANCE_MS
        refreshTrackUrlAndResume(
            initialPlayer = player,
            track = resumeTrack,
            reason = "manual-play",
            force = true,
            resumePositionMs = when {
                atEnd || player.playbackState == Player.STATE_ENDED -> 0L
                targetMismatch -> positionMs
                else -> null
            },
            resumeAsCurrent = targetMismatch,
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
    fun applyAgentQueueRequest(request: AgentQueueRequest): QueueCommitResult {
        val result = playbackOrchestrator.applyAgentRequest(request)
        when (result) {
            is QueueCommitResult.Success -> {
                // 前台已接管新的主队列（包括 continuous=null 的普通替换），后台 service
                // 持有的旧 source 不能在稍后完成 fetch 后再向这个队列追加。
                if (request.operation != QueueOperation.InsertNext) {
                    BackgroundAgentContinuation.clear()
                }
                rememberActiveRecommendationContext(request)
                scheduleTransitionPrepare(result.plan)
            }
            is QueueCommitResult.Rejected -> {
                DiagnosticsLogStore.record(
                    area = "playback_orchestrator",
                    event = "queue_commit_rejected",
                    fields = mapOf(
                        "requestId" to request.requestId,
                        "operation" to request.operation.name,
                        "reason" to result.reason,
                        "messages" to result.messages.joinToString("|"),
                    ),
                )
            }
        }
        return result
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun scheduleTransitionPrepare(plan: CommittedQueuePlan) {
        if (!SeamlessRuntimeFlags.current.transitionPreparerEnabled) return
        transitionPrepareJob?.cancel()
        transitionPrepareJob = viewModelScope.launch {
            val report = transitionPreparer.prepareAhead(plan)
            applyPreparedTracks(report.resolvedTracks, plan.queueVersion)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun applyPreparedTracks(
        resolvedTracks: List<NativeTrack>,
        queueVersion: Long,
    ) {
        if (resolvedTracks.isEmpty() || !PlaybackSessionClock.isCurrent(queueVersion)) return
        val live = controller ?: return
        val byId = resolvedTracks.filterNot { CrossfadeController.protectsMediaItem(it.id) }.associateBy { it.id }
        val nextQueue = state.queue.map { track ->
            byId[track.id]?.takeIf { it.streamUrl.isNotBlank() } ?: track
        }
        if (sameQueueOrder(nextQueue, state.queue) &&
            nextQueue.zip(state.queue).all { (left, right) ->
                left.streamUrl == right.streamUrl && left.streamCacheKey == right.streamCacheKey
            }
        ) return
        state = state.copy(queue = nextQueue)
        var replaced = 0
        byId.values.forEach { track ->
            val itemIndex = live.indexOfMediaId(track.id) ?: return@forEach
            if (itemIndex == live.currentMediaItemIndex) return@forEach
            val queueIndex = nextQueue.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: return@forEach
            runCatching {
                live.replaceMediaItem(itemIndex, toMediaItem(track, nextQueue, queueIndex))
                replaced += 1
            }
        }
        DiagnosticsLogStore.record(
            area = "transition",
            event = "prepare_tracks_applied",
            fields = mapOf(
                "queueVersion" to queueVersion,
                "resolvedCount" to resolvedTracks.size,
                "replacedMediaItems" to replaced,
            ),
        )
    }

    private fun manualRequestId(prefix: String): String =
        "$prefix-${SystemClock.elapsedRealtime()}"

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun insertNext(
        track: NativeTrack,
        queueVersion: Long? = null,
        requestId: String = "",
    ): Boolean {
        if (queueVersion == null) {
            return applyAgentQueueRequest(
                AgentQueueRequest(
                    requestId = manualRequestId("manual-insert-next"),
                    sourceUserText = "manual_insert_next",
                    operation = QueueOperation.InsertNext,
                    tracks = listOf(track),
                    jumpToInserted = true,
                    desiredCount = 1,
                ),
            ) is QueueCommitResult.Success
        }
        val committedQueueVersion = queueVersion
        if (track.streamUrl.isBlank() && track.neteaseId == null) return false
        if (controller == null) {
            deferAgentPlayback(PendingAgentPlayback.Insert(track, committedQueueVersion, requestId), "controller_not_ready")
            return true
        }
        DiagnosticsLogStore.record("queue", "insert_next", trackFields(track) + mapOf("queueVersion" to committedQueueVersion))
        val gen = ++playGen
        setResolvingPlayback(true)
        viewModelScope.launch {
            try {
                val resolved = resolveSinglePlayable(track) ?: run {
                    markAgentPlaybackStartFailed(requestId, committedQueueVersion, "insert_resolve_failed")
                    return@launch
                }
                if (gen != playGen) return@launch
                val live = controller ?: run {
                    deferAgentPlayback(PendingAgentPlayback.Insert(track, committedQueueVersion, requestId), "controller_lost_during_resolve")
                    return@launch
                }
                // 当前队列空（还没开始放过）→ 退化成"装队列 + 播"，避免插队进虚空
                if (live.mediaItemCount == 0) {
                    val singleQueue = listOf(resolved)
                    state = state.copy(queue = singleQueue, currentIndex = 0)
                    live.setMediaItems(listOf(toMediaItem(resolved, singleQueue, 0)), 0, 0L)
                    applyPlaybackMode(live)
                    live.volume = 1f
                    live.prepare()
                    userPausedPlayback = false
                    live.play()
                    return@launch
                }
                val baseQueue = queueMatchingPlayerTimeline(live, state.queue)
                if (!sameQueueOrder(baseQueue, state.queue)) {
                    DiagnosticsLogStore.record(
                        area = "queue",
                        event = "state_queue_reconciled",
                        fields = mapOf(
                            "reason" to "insert_next",
                            "stateQueueSize" to state.queue.size,
                            "mediaItemCount" to live.mediaItemCount,
                            "alignedQueueSize" to baseQueue.size,
                        ),
                    )
                    state = state.copy(
                        queue = baseQueue,
                        currentIndex = currentQueueIndexFor(live, baseQueue),
                    )
                }
                val curIdx = live.currentMediaItemIndex.coerceAtLeast(0)
                val insertIdx = (curIdx + 1).coerceAtMost(live.mediaItemCount)
                // state.queue 跟着 player 队列一起插
                val newQueue = baseQueue.toMutableList().apply {
                    add(insertIdx.coerceAtMost(size), resolved)
                }
                state = state.copy(queue = newQueue)
                live.addMediaItem(insertIdx, toMediaItem(resolved, newQueue, insertIdx.coerceIn(0, newQueue.lastIndex)))
                val previousIdx = insertIdx - 1
                if (previousIdx in newQueue.indices && hasContinuousAudioNeighbor(newQueue, previousIdx)) {
                    runCatching {
                        live.replaceMediaItem(previousIdx, toMediaItem(newQueue[previousIdx], newQueue, previousIdx))
                    }
                }
                // 跳过去立刻播 —— "插队"语义是"我现在就要听 X"
                live.seekTo(insertIdx, 0L)
                ensurePlayerLive(live)
                applyPlaybackMode(live)
                userPausedPlayback = false
                live.play()
            } finally {
                setResolvingPlayback(false)
            }
        }
        return true
    }

    /**
     * 只把歌排到当前歌的下一首，不立刻切过去。
     * 用于用户明确说"下一首插 X / 不要打断现在这首"的语义。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun queueNext(
        track: NativeTrack,
        queueVersion: Long? = null,
        requestId: String = "",
    ): Boolean {
        if (queueVersion == null) {
            return applyAgentQueueRequest(
                AgentQueueRequest(
                    requestId = manualRequestId("manual-queue-next"),
                    sourceUserText = "manual_queue_next",
                    operation = QueueOperation.InsertNext,
                    tracks = listOf(track),
                    jumpToInserted = false,
                    desiredCount = 1,
                ),
            ) is QueueCommitResult.Success
        }
        val committedQueueVersion = queueVersion
        if (track.streamUrl.isBlank() && track.neteaseId == null) return false
        if (controller == null) {
            deferAgentPlayback(PendingAgentPlayback.QueueNext(track, committedQueueVersion, requestId), "controller_not_ready")
            return true
        }
        DiagnosticsLogStore.record("queue", "queue_next", trackFields(track) + mapOf("queueVersion" to committedQueueVersion))
        val gen = ++playGen
        setResolvingPlayback(true)
        viewModelScope.launch {
            try {
                val resolved = resolveSinglePlayable(track) ?: run {
                    markAgentPlaybackStartFailed(requestId, committedQueueVersion, "queue_next_resolve_failed")
                    return@launch
                }
                if (gen != playGen) return@launch
                val live = controller ?: run {
                    deferAgentPlayback(PendingAgentPlayback.QueueNext(track, committedQueueVersion, requestId), "controller_lost_during_resolve")
                    return@launch
                }
                if (live.mediaItemCount == 0) {
                    val singleQueue = listOf(resolved)
                    state = state.copy(queue = singleQueue, currentIndex = 0)
                    live.setMediaItems(listOf(toMediaItem(resolved, singleQueue, 0)), 0, 0L)
                    applyPlaybackMode(live)
                    live.volume = 1f
                    live.prepare()
                    userPausedPlayback = false
                    live.play()
                    return@launch
                }
                val baseQueue = queueMatchingPlayerTimeline(live, state.queue)
                if (!sameQueueOrder(baseQueue, state.queue)) {
                    DiagnosticsLogStore.record(
                        area = "queue",
                        event = "state_queue_reconciled",
                        fields = mapOf(
                            "reason" to "queue_next",
                            "stateQueueSize" to state.queue.size,
                            "mediaItemCount" to live.mediaItemCount,
                            "alignedQueueSize" to baseQueue.size,
                        ),
                    )
                    state = state.copy(
                        queue = baseQueue,
                        currentIndex = currentQueueIndexFor(live, baseQueue),
                    )
                }
                val curIdx = live.currentMediaItemIndex.coerceAtLeast(0)
                val insertIdx = (curIdx + 1).coerceAtMost(live.mediaItemCount)
                val newQueue = baseQueue.toMutableList().apply {
                    add(insertIdx.coerceAtMost(size), resolved)
                }
                state = state.copy(queue = newQueue, currentIndex = currentQueueIndexFor(live, newQueue))
                live.addMediaItem(insertIdx, toMediaItem(resolved, newQueue, insertIdx.coerceIn(0, newQueue.lastIndex)))
                val previousIdx = insertIdx - 1
                if (previousIdx in newQueue.indices && hasContinuousAudioNeighbor(newQueue, previousIdx)) {
                    runCatching {
                        live.replaceMediaItem(previousIdx, toMediaItem(newQueue[previousIdx], newQueue, previousIdx))
                    }
                }
            } finally {
                setResolvingPlayback(false)
            }
        }
        return true
    }

    /**
     * 把一批歌按顺序插到当前歌后面（“这首听完放 X 的歌 / 下一首开始听 Y”）。
     * jumpToInserted=false 完全不打扰当前播放；true 时插完立刻切到批内第一首。
     * 不写 continuousSource：批量插播是当前主指令之上的“加塞”，不接管自动续杯。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun queueNextBatch(
        tracks: List<NativeTrack>,
        jumpToInserted: Boolean,
        queueVersion: Long,
        requestId: String = "",
    ): Boolean {
        val playable = tracks.filter { it.streamUrl.isNotBlank() || it.neteaseId != null }
        if (playable.isEmpty()) return false
        if (playable.size == 1) {
            val single = playable.first()
            return if (jumpToInserted) {
                insertNext(single, queueVersion, requestId)
            } else {
                queueNext(single, queueVersion, requestId)
            }
        }
        if (controller == null) {
            deferAgentPlayback(
                PendingAgentPlayback.InsertBatch(playable, jumpToInserted, queueVersion, requestId),
                "controller_not_ready",
            )
            return true
        }
        DiagnosticsLogStore.record(
            area = "queue",
            event = "queue_next_batch",
            fields = mapOf(
                "count" to playable.size,
                "jumpToInserted" to jumpToInserted,
                "queueVersion" to queueVersion,
            ) + trackFields(playable.first()),
        )
        val gen = ++playGen
        setResolvingPlayback(true)
        viewModelScope.launch {
            try {
                // 当前歌还在放、插入并不紧急：整批先解析，失败的丢掉不阻塞。
                val resolvedAll = resolvePlayableQueue(playable).filter { it.streamUrl.isNotBlank() }
                if (resolvedAll.isEmpty()) {
                    markAgentPlaybackStartFailed(requestId, queueVersion, "insert_batch_resolve_failed")
                    return@launch
                }
                if (gen != playGen) return@launch
                val live = controller ?: run {
                    deferAgentPlayback(
                        PendingAgentPlayback.InsertBatch(playable, jumpToInserted, queueVersion, requestId),
                        "controller_lost_during_resolve",
                    )
                    return@launch
                }
                // 队列还是空的（没开始放过）→ 退化成装队列+播
                if (live.mediaItemCount == 0) {
                    state = state.copy(queue = resolvedAll, currentIndex = 0)
                    live.setMediaItems(toMediaItems(resolvedAll, resolvedAll), 0, 0L)
                    applyPlaybackMode(live)
                    live.volume = 1f
                    live.prepare()
                    userPausedPlayback = false
                    live.play()
                    return@launch
                }
                val baseQueue = queueMatchingPlayerTimeline(live, state.queue)
                if (!sameQueueOrder(baseQueue, state.queue)) {
                    DiagnosticsLogStore.record(
                        area = "queue",
                        event = "state_queue_reconciled",
                        fields = mapOf(
                            "reason" to "queue_next_batch",
                            "stateQueueSize" to state.queue.size,
                            "mediaItemCount" to live.mediaItemCount,
                            "alignedQueueSize" to baseQueue.size,
                        ),
                    )
                    state = state.copy(
                        queue = baseQueue,
                        currentIndex = currentQueueIndexFor(live, baseQueue),
                    )
                }
                val curIdx = live.currentMediaItemIndex.coerceAtLeast(0)
                val insertIdx = (curIdx + 1).coerceAtMost(live.mediaItemCount)
                val newQueue = baseQueue.toMutableList().apply {
                    addAll(insertIdx.coerceAtMost(size), resolvedAll)
                }
                state = state.copy(queue = newQueue, currentIndex = currentQueueIndexFor(live, newQueue))
                live.addMediaItems(
                    insertIdx,
                    resolvedAll.mapIndexed { i, track ->
                        toMediaItem(track, newQueue, (insertIdx + i).coerceIn(0, newQueue.lastIndex))
                    },
                )
                val previousIdx = insertIdx - 1
                if (previousIdx in newQueue.indices && hasContinuousAudioNeighbor(newQueue, previousIdx)) {
                    runCatching {
                        live.replaceMediaItem(previousIdx, toMediaItem(newQueue[previousIdx], newQueue, previousIdx))
                    }
                }
                if (jumpToInserted) {
                    live.seekTo(insertIdx, 0L)
                    ensurePlayerLive(live)
                    applyPlaybackMode(live)
                    userPausedPlayback = false
                    live.play()
                }
            } finally {
                setResolvingPlayback(false)
            }
        }
        return true
    }

    fun removeTrack(trackId: String) {
        val player = controller ?: return
        val stateIdx = state.queue.indexOfFirst { it.id == trackId }
        if (stateIdx < 0) return
        val removedTrack = state.queue[stateIdx]
        val feedbackContext = recommendationFeedbackContext()
        val queueVersion = PlaybackSessionClock.bump("remove_track")
        CommittedQueuePlanStore.clear()
        rememberQueueRecommendationAvoidance(listOf(removedTrack))
        recordRecommendationRejection(removedTrack, feedbackContext)
        
        val playerIdx = player.indexOfMediaId(trackId)
        if (playerIdx != null) {
            player.removeMediaItem(playerIdx)
        }
        
        val newQueue = state.queue.filter { it.id != trackId }
        val newCurrentIndex = if (newQueue.isEmpty()) {
            0
        } else {
            currentQueueIndexFor(player, newQueue)
        }
        
        state = state.copy(
            queue = newQueue,
            currentIndex = newCurrentIndex
        )
        DiagnosticsLogStore.record(
            area = "queue",
            event = "remove_track",
            fields = mapOf(
                "trackId" to trackId,
                "neteaseId" to removedTrack.neteaseId,
                "title" to removedTrack.title,
                "artist" to removedTrack.artist,
                "blockedFromRecommendation" to true,
                "feedbackContext" to feedbackContext?.take(96),
                "queueVersion" to queueVersion,
                "queueSize" to newQueue.size,
            ),
        )
        syncFrom(player)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun playCurrentQueueTrack(trackId: String) {
        val player = controller ?: return
        val queueSnapshot = state.queue
        val stateIdx = queueSnapshot.indexOfFirst { it.id == trackId }
        if (stateIdx < 0) return
        PlaybackSessionClock.noteManualSelection()
        val queueVersion = PlaybackSessionClock.bump("play_current_queue_track")
        CommittedQueuePlanStore.clear()
        DiagnosticsLogStore.record(
            area = "queue",
            event = "play_current_queue_track",
            fields = trackFields(queueSnapshot[stateIdx]) + mapOf(
                "queueIndex" to stateIdx,
                "queueSize" to queueSnapshot.size,
                "queueVersion" to queueVersion,
            ),
        )

        val playerIdx = player.indexOfMediaId(trackId)
        if (playerIdx != null) {
            applyPlaybackMode(player)
            player.seekTo(playerIdx, 0L)
            ensurePlayerLive(player)
            player.prepare()
            userPausedPlayback = false
            player.play()
            state = state.copy(currentIndex = stateIdx)
            syncFrom(player)
            return
        }

        val gen = ++playGen
        setResolvingPlayback(true)
        viewModelScope.launch {
            try {
                val resolvedQueue = resolvePlayableQueue(queueSnapshot).filter { it.streamUrl.isNotBlank() }
                val resolvedIdx = resolvedQueue.indexOfFirst { it.id == trackId }
                if (gen != playGen || resolvedIdx < 0) return@launch
                val live = controller ?: return@launch
                val mergedQueue = mergeResolvedTracks(queueSnapshot, resolvedQueue)
                state = state.copy(
                    queue = mergedQueue,
                    currentIndex = stateIdx,
                )
                live.setMediaItems(toMediaItems(resolvedQueue, mergedQueue), resolvedIdx, 0L)
                applyPlaybackMode(live)
                live.volume = 1f
                live.prepare()
                userPausedPlayback = false
                live.play()
            } finally {
                setResolvingPlayback(false)
            }
        }
    }

    /**
     * 保留正在播放的 MediaItem，只替换它后面的待播队列。
     * 新计划中的 tracks 始终只是 future tracks；当前歌既不进入 Agent 计划，也不参与其约束校验。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun replaceFutureFromAgent(
        initialBatch: List<NativeTrack>,
        source: ContinuousQueueSource?,
        queueVersion: Long,
        requestId: String,
    ): Boolean {
        if (initialBatch.isEmpty()) return false
        val first = initialBatch.first()
        if (first.streamUrl.isBlank() && first.neteaseId == null) return false
        if (controller == null) {
            return playFromAgent(initialBatch, source, queueVersion, requestId)
        }
        val gen = ++playGen
        setResolvingPlayback(true)
        viewModelScope.launch {
            try {
                val initialPlayer = controller ?: run {
                    playFromAgent(initialBatch, source, queueVersion, requestId)
                    return@launch
                }
                val preservedMediaId = initialPlayer.currentMediaItem?.mediaId
                val preservedTrack = currentTrackFor(initialPlayer)
                if (preservedMediaId == null || preservedTrack == null) {
                    playFromAgent(initialBatch, source, queueVersion, requestId)
                    return@launch
                }
                val preservedPositionMs = initialPlayer.currentPosition.coerceAtLeast(0L)
                val preservedIsPlaying = initialPlayer.isPlaying
                val firstResolved = resolveSinglePlayable(first) ?: run {
                    markAgentPlaybackStartFailed(requestId, queueVersion, "preserve_current_first_resolve_failed")
                    return@launch
                }
                if (gen != playGen) return@launch
                val live = controller ?: run {
                    playFromAgent(initialBatch, source, queueVersion, requestId)
                    return@launch
                }
                if (live.currentMediaItem?.mediaId != preservedMediaId) return@launch

                val baseQueue = queueMatchingPlayerTimeline(live, state.queue)
                val currentIndex = currentQueueIndexFor(live, baseQueue)
                val preservedPrefix = baseQueue.take(currentIndex + 1)
                if (preservedPrefix.lastOrNull()?.id != preservedMediaId) return@launch
                val futureQueue = buildCommittedOrder(
                    firstResolved = firstResolved,
                    initialBatch = initialBatch,
                )
                val nextQueue = preservedPrefix + futureQueue
                val currentMediaIndex = live.currentMediaItemIndex.coerceAtLeast(0)

                // 接管后才能发送播放器事件，避免同步状态时读到旧后台队列的模式。
                BackgroundAgentContinuation.clear()
                lastAgentContinuousSource = source
                val queueMode = modeForNewQueue(source)
                continuousSource = continuousSourceForMode(queueMode, explicitSource = source)
                app.pipo.nativeapp.ui.PetBubbleStateAccessor.resetForNewQueue()
                state = state.copy(
                    queue = nextQueue,
                    currentIndex = currentIndex,
                    playbackMode = queueMode,
                )
                if (currentMediaIndex + 1 < live.mediaItemCount) {
                    live.removeMediaItems(currentMediaIndex + 1, live.mediaItemCount)
                }
                live.addMediaItem(
                    toMediaItem(firstResolved, nextQueue, (currentIndex + 1).coerceIn(0, nextQueue.lastIndex)),
                )
                applyPlaybackMode(live)
                DiagnosticsLogStore.record(
                    area = "queue",
                    event = "preserve_current_replace_future",
                    fields = mapOf(
                        "queueVersion" to queueVersion,
                        "requestId" to requestId,
                        "preservedMediaId" to preservedMediaId,
                        "preservedPositionMs" to preservedPositionMs,
                        "preservedIsPlaying" to preservedIsPlaying,
                        "futureCount" to futureQueue.size,
                    ),
                )
                maybePrepareAutoMix(live)

                for (chunk in appendResolveChunks(futureQueue.drop(1))) {
                    val resolvedChunk = resolvePlayableQueue(chunk).filter { it.streamUrl.isNotBlank() }
                    if (gen != playGen || resolvedChunk.isEmpty()) continue
                    val livePlayer = controller ?: return@launch
                    livePlayer.addMediaItems(toMediaItems(resolvedChunk, state.queue))
                    val mergedQueue = mergeResolvedTracks(state.queue, resolvedChunk)
                    state = state.copy(
                        queue = mergedQueue,
                        currentIndex = currentQueueIndexFor(livePlayer, mergedQueue),
                        playbackMode = queueMode,
                    )
                    maybePrepareAutoMix(livePlayer)
                }
            } finally {
                setResolvingPlayback(false)
            }
        }
        return true
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun playFromAgent(
        initialBatch: List<NativeTrack>,
        source: ContinuousQueueSource?,
        queueVersion: Long? = null,
        requestId: String = "",
    ): Boolean {
        if (queueVersion == null) {
            return applyAgentQueueRequest(
                AgentQueueRequest(
                    requestId = manualRequestId("manual-replace-queue"),
                    sourceUserText = "manual_replace_queue",
                    operation = QueueOperation.ReplaceQueue,
                    tracks = initialBatch,
                    continuous = source,
                    desiredCount = initialBatch.size,
                ),
            ) is QueueCommitResult.Success
        }
        val committedQueueVersion = queueVersion
        if (initialBatch.isEmpty()) return false
        val first = initialBatch.first()
        if (first.streamUrl.isBlank() && first.neteaseId == null) return false
        val player = controller ?: run {
            deferAgentPlayback(PendingAgentPlayback.Replace(initialBatch, source, committedQueueVersion, requestId), "controller_not_ready")
            return true
        }
        DiagnosticsLogStore.record(
            area = "queue",
            event = "play_from_agent",
            fields = mapOf(
                "count" to initialBatch.size,
                "hasContinuousSource" to (source != null),
                "queueMode" to modeForNewQueue(source).name,
                "preferredPlaybackMode" to preferredPlaybackMode.name,
                "queueVersion" to committedQueueVersion,
            ) + trackFields(initialBatch.first()),
        )
        // phase-1 开始前 ++ —— phase-2 在 await resolvePlayableQueue 期间用户可能再次
        // 启播(playFromAgent / playTrack / insertNext),那时 playGen 已被新一次 ++,
        // 我们这次的 phase-2 拿到 gen != playGen 就放弃 addMediaItems,避免把
        // "上一次歌单的剩余歌" 追到 "这一次新歌单" 的尾部 → 队列脏掉。
        val gen = ++playGen
        setResolvingPlayback(true)
        viewModelScope.launch {
            var firstResolvedForAppend: NativeTrack? = null
            try {
                // -------- 阶段 1：解析第 1 首立刻播 --------
                val firstResolved: NativeTrack = resolveSinglePlayable(first) ?: run {
                    markAgentPlaybackStartFailed(requestId, committedQueueVersion, "first_resolve_failed")
                    return@launch
                }
                if (gen != playGen) return@launch
                firstResolvedForAppend = firstResolved
                BackgroundAgentContinuation.clear()
                lastAgentContinuousSource = source
                val queueMode = modeForNewQueue(source)
                continuousSource = continuousSourceForMode(queueMode, explicitSource = source)
                app.pipo.nativeapp.ui.PetBubbleStateAccessor.resetForNewQueue()
                val pendingQueue = buildCommittedOrder(
                    firstResolved = firstResolved,
                    initialBatch = initialBatch,
                )
                DiagnosticsLogStore.record(
                    area = "queue",
                    event = "committed_order_preserved",
                    fields = mapOf(
                        "queueVersion" to committedQueueVersion,
                        "inputCount" to initialBatch.size,
                        "stateCount" to pendingQueue.size,
                        "reorderedAfterCommit" to false,
                    ),
                )
                state = state.copy(
                    queue = pendingQueue,
                    currentIndex = 0,
                    playbackMode = queueMode,
                )
                player.setMediaItems(listOf(toMediaItem(firstResolved, pendingQueue, 0)), 0, 0L)
                applyPlaybackMode(player)
                player.volume = 1f
                player.prepare()
                userPausedPlayback = false
                player.play()
            } finally {
                setResolvingPlayback(false)
            }

            // -------- 阶段 2：后台补 batch 剩下歌 --------
            firstResolvedForAppend ?: return@launch
            if (gen != playGen) return@launch
            val plannedQueue = state.queue
            val rest = plannedQueue.drop(1)
            if (rest.isEmpty()) return@launch
            for (chunk in appendResolveChunks(rest)) {
                val resolvedChunk = resolvePlayableQueue(chunk).filter { it.streamUrl.isNotBlank() }
                if (gen != playGen || resolvedChunk.isEmpty()) continue
                val livePlayer = controller ?: return@launch
                livePlayer.addMediaItems(toMediaItems(resolvedChunk, state.queue))
                val mergedQueue = mergeResolvedTracks(state.queue, resolvedChunk)
                state = state.copy(
                    queue = mergedQueue,
                    currentIndex = currentQueueIndexFor(livePlayer, mergedQueue),
                )
                alignLoadedQueueOrder(livePlayer)
                maybePrepareAutoMix(livePlayer)
            }
            if (gen != playGen) return@launch
            val livePlayer = controller ?: return@launch
            // 解析失败的歌未装入播放器，批次结束后按实际时间线收口，避免续播
            // 永远卡在 mediaItemCount < queue.size 的 timeline_not_ready 门禁。
            val loadedQueue = queueMatchingPlayerTimeline(livePlayer, state.queue)
            state = state.copy(
                queue = loadedQueue,
                currentIndex = currentQueueIndexFor(livePlayer, loadedQueue),
            )
            maybeExtendQueue(trigger = "initial_queue_resolved", logSkips = true)
        }
        return true
    }

    private fun buildCommittedOrder(
        firstResolved: NativeTrack,
        initialBatch: List<NativeTrack>,
    ): List<NativeTrack> {
        val out = ArrayList<NativeTrack>()
        val seen = HashSet<String>()
        out.add(firstResolved)
        seen.add(TrackDedupe.songKey(firstResolved))
        for (track in initialBatch) {
            if (seen.add(TrackDedupe.songKey(track))) {
                out.add(track)
            }
        }
        return out
    }

    private fun manualQueueFromSelection(
        selected: NativeTrack,
        contextQueue: List<NativeTrack>,
    ): List<NativeTrack> {
        val tail = contextQueue.filter { it.id != selected.id }
        val orderedTail = if (preferredPlaybackMode == PlaybackQueueMode.ShufflePlay) {
            tail.shuffledAvoidingSameOrder()
        } else {
            tail
        }
        return listOf(selected) + orderedTail
    }

    private fun List<NativeTrack>.shuffledAvoidingSameOrder(): List<NativeTrack> {
        if (size < 2) return this
        val originalIds = map { it.id }
        var shuffled = shuffled()
        if (shuffled.map { it.id } == originalIds) {
            shuffled = shuffled()
        }
        return if (shuffled.map { it.id } == originalIds) drop(1) + first() else shuffled
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
     * 随机模式只打乱用户点中歌曲之后的队列；顺序模式保留列表顺序。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun playTrack(
        track: NativeTrack,
        contextQueue: List<NativeTrack>,
        smooth: Boolean = true,
        queueVersion: Long? = null,
    ) {
        BackgroundAgentContinuation.clear()
        if (queueVersion == null) {
            val tracks = manualQueueFromSelection(track, contextQueue)
            PlaybackSessionClock.noteManualSelection()
            val manualQueueVersion = PlaybackSessionClock.bump("manual_play_track")
            CommittedQueuePlanStore.clear()
            playTrack(track, tracks, smooth, queueVersion = manualQueueVersion)
            return
        }
        val committedQueueVersion = queueVersion
        val player = controller ?: return
        val playable = contextQueue.filter { it.streamUrl.isNotBlank() || it.neteaseId != null }
        if (playable.isEmpty()) return
        DiagnosticsLogStore.record(
            area = "queue",
            event = "play_track",
            fields = trackFields(track) + mapOf(
                "contextCount" to contextQueue.size,
                "playableCount" to playable.size,
                "smooth" to smooth,
                "queueVersion" to committedQueueVersion,
            ),
        )
        // 同 playFromAgent 注释:phase-2 过程中用户可能再启播,gen 不一致就放弃。
        val gen = ++playGen
        setResolvingPlayback(true)
        viewModelScope.launch {
            var pickedResolvedForAppend: NativeTrack? = null
            try {
                // -------- 阶段 1：立刻开播"那一首" --------
                // 优先用已有 streamUrl；没有就只为这 1 个 id 发 1 次 songUrls 请求
                val pickedResolved: NativeTrack = resolveSinglePlayable(track) ?: return@launch
                if (gen != playGen) return@launch
                pickedResolvedForAppend = pickedResolved
                // phase-1 期间显式 null —— 此时 queue 只有 1 首，开 continuous 会立刻触发
                // maybeExtendQueue（remaining=0 ≤ 阈值 3），跟 phase-2 的 addMediaItems 抢插。
                // phase-2 完成后再切回 RecommendEngine，让 "歌单播完接续相似歌" 这条 UX 也能用上。
                lastAgentContinuousSource = null
                val queueMode = preferredPlaybackMode
                continuousSource = continuousSourceForMode(queueMode, explicitSource = null)
                app.pipo.nativeapp.ui.PetBubbleStateAccessor.resetForNewQueue()
                
                val plannedQueue = buildCommittedOrder(
                    firstResolved = pickedResolved,
                    initialBatch = playable,
                )
                DiagnosticsLogStore.record(
                    area = "queue",
                    event = "committed_order_preserved",
                    fields = mapOf(
                        "queueVersion" to committedQueueVersion,
                        "inputCount" to playable.size,
                        "stateCount" to plannedQueue.size,
                        "reorderedAfterCommit" to false,
                    ),
                )

                state = state.copy(
                    queue = plannedQueue,
                    currentIndex = 0,
                    playbackMode = queueMode,
                )
                player.setMediaItems(listOf(toMediaItem(pickedResolved, plannedQueue, 0)), 0, 0L)
                applyPlaybackMode(player)
                player.volume = 1f
                player.prepare()
                userPausedPlayback = false
                player.play()
            } finally {
                setResolvingPlayback(false)
            }

            // -------- 阶段 2：后台分批补完整队列 --------
            val pickedResolved = pickedResolvedForAppend ?: return@launch
            val plannedQueue = state.queue
            if (plannedQueue.size <= 1) return@launch
            val tailCandidates = plannedQueue.drop(1)
            for (chunk in appendResolveChunks(tailCandidates)) {
                // 同 playFromAgent:resolvePlayableQueue 期间用户可能切到别的歌单
                if (gen != playGen) return@launch
                val resolvedChunk = resolvePlayableQueue(chunk).filter { it.streamUrl.isNotBlank() }
                if (resolvedChunk.isEmpty()) continue
                if (gen != playGen) return@launch
                val livePlayer = controller ?: return@launch
                livePlayer.addMediaItems(toMediaItems(resolvedChunk, state.queue))
                // state.queue 保留完整计划队列，只把已解析到 URL 的条目替换进去。
                val mergedQueue = mergeResolvedTracks(state.queue, resolvedChunk)
                state = state.copy(queue = mergedQueue, currentIndex = currentQueueIndexFor(livePlayer, mergedQueue))
                maybePrepareAutoMix(livePlayer)
            }
            // continuousSource 由用户持久化的播放模式决定：AI 电台续杯，其它模式只播当前歌单。
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun next() {
        controller?.let { p ->
            val pct = currentCompletionPct()
            val queueVersion = PlaybackSessionClock.bump("manual_next")
            CommittedQueuePlanStore.clear()
            DiagnosticsLogStore.record(
                area = "playback",
                event = "next_tap",
                fields = currentTrackFields() + mapOf(
                    "completionPct" to "%.3f".format(pct),
                    "queueVersion" to queueVersion,
                ),
            )
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
            val queueVersion = PlaybackSessionClock.bump("manual_previous")
            CommittedQueuePlanStore.clear()
            DiagnosticsLogStore.record(
                area = "playback",
                event = "previous_tap",
                fields = currentTrackFields() + mapOf(
                    "completionPct" to "%.3f".format(currentCompletionPct()),
                    "queueVersion" to queueVersion,
                ),
            )
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
        val count = p.mediaItemCount
        val currentIdx = p.currentMediaItemIndex.coerceIn(0, count - 1)
        val targetIdx = offsetTargetIndex(currentIdx, count, offset) ?: run {
            if (offset < 0) {
                p.seekTo(0L)
            } else {
                p.pause()
            }
            syncFrom(p)
            return
        }
        
        if (offset > 0 && targetIdx < currentIdx && state.playbackMode == PlaybackQueueMode.AiRadio) {
            triggerForceExtendAndPlayNext(p)
            return
        }
        
        applyPlaybackMode(p)
        p.seekTo(targetIdx, 0L)
        p.prepare()
        p.play()
        syncFrom(p)
    }

    private fun triggerForceExtendAndPlayNext(player: Player) {
        if (BackgroundAgentContinuation.isActive()) return
        val anchorMediaId = player.currentMediaItem?.mediaId
        val anchorIndex = player.currentMediaItemIndex
        val sourceSnapshot = continuousSource ?: run {
            fallbackWrapAround(player, anchorMediaId, anchorIndex)
            return
        }
        val queueVersion = PlaybackSessionClock.bump("force_extend_and_play_next")
        val gen = ++playGen
        setResolvingPlayback(true)
        viewModelScope.launch {
            try {
                val queueSnapshot = queueMatchingPlayerTimeline(player, state.queue)
                if (!sameQueueOrder(queueSnapshot, state.queue)) {
                    DiagnosticsLogStore.record(
                        area = "queue",
                        event = "state_queue_reconciled",
                        fields = mapOf(
                            "reason" to "force_extend",
                            "stateQueueSize" to state.queue.size,
                            "mediaItemCount" to player.mediaItemCount,
                            "alignedQueueSize" to queueSnapshot.size,
                        ),
                    )
                    state = state.copy(
                        queue = queueSnapshot,
                        currentIndex = currentQueueIndexFor(player, queueSnapshot),
                    )
                }
                val remaining = queueSnapshot.size - state.currentIndex - 1
                val exclusions = queueRecommendationExclusions(queueSnapshot)
                val more = try {
                    sourceSnapshot.fetchMore(exclusions.trackIds)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
                if (gen != playGen || continuousSource !== sourceSnapshot) return@launch
                if (more.isEmpty()) {
                    recordQueueExtendFailure(
                        trigger = "force_extend",
                        stage = "fetch_more_empty",
                        queueSnapshot = queueSnapshot,
                        remaining = remaining,
                        extra = mapOf("source" to continuousSourceKind(sourceSnapshot)),
                    )
                    fallbackWrapAround(player, anchorMediaId, anchorIndex)
                    return@launch
                }
                val resolved = try {
                    resolvePlayableQueue(more).filter { it.streamUrl.isNotBlank() && sourceSnapshot.acceptsResolved(it) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
                if (gen != playGen || continuousSource !== sourceSnapshot) return@launch
                if (resolved.isEmpty()) {
                    recordQueueExtendFailure(
                        trigger = "force_extend",
                        stage = "resolved_empty",
                        queueSnapshot = queueSnapshot,
                        remaining = remaining,
                        extra = mapOf(
                            "candidateCount" to more.size,
                            "source" to continuousSourceKind(sourceSnapshot),
                        ),
                    )
                    fallbackWrapAround(player, anchorMediaId, anchorIndex)
                    return@launch
                }
                val append = selectQueueRecommendationAppend(
                    candidates = resolved,
                    currentQueue = queueSnapshot,
                    excludedIdsBeforeFetch = exclusions.trackIds,
                    trigger = "force_extend",
                    queueSnapshot = queueSnapshot,
                    remaining = remaining,
                )
                if (gen != playGen || continuousSource !== sourceSnapshot) return@launch
                if (append.isEmpty()) {
                    recordQueueExtendFailure(
                        trigger = "force_extend",
                        stage = "append_empty_after_filter",
                        queueSnapshot = queueSnapshot,
                        remaining = remaining,
                        extra = mapOf(
                            "candidateCount" to more.size,
                            "resolvedCount" to resolved.size,
                            "source" to continuousSourceKind(sourceSnapshot),
                        ),
                    )
                    fallbackWrapAround(player, anchorMediaId, anchorIndex)
                    return@launch
                }
                if (BackgroundAgentContinuation.isActive()) return@launch
                val live = controller ?: return@launch
                val insertIdx = live.mediaItemCount
                val smartAppend = planAgentAppendForSmartMix(queueSnapshot, append)
                val plannedQueue = queueSnapshot + smartAppend
                state = state.copy(queue = plannedQueue)
                live.addMediaItems(toMediaItems(smartAppend, plannedQueue))
                rememberQueueRecommendations(smartAppend)

                if (isPlaybackAnchorCurrent(live, anchorMediaId, anchorIndex)) {
                    live.seekTo(insertIdx, 0L)
                    ensurePlayerLive(live)
                    live.prepare()
                    userPausedPlayback = false
                    live.play()
                } else {
                    DiagnosticsLogStore.record(
                        area = "queue",
                        event = "force_extend_handoff_preserved",
                        fields = mapOf(
                            "reason" to "playback_already_advanced",
                            "anchorMediaId" to anchorMediaId,
                            "anchorIndex" to anchorIndex,
                            "currentMediaId" to live.currentMediaItem?.mediaId,
                            "currentIndex" to live.currentMediaItemIndex,
                            "positionMs" to live.currentPosition.coerceAtLeast(0L),
                            "appendCount" to smartAppend.size,
                        ),
                    )
                }
                DiagnosticsLogStore.record(
                    area = "playback_orchestrator",
                    event = "queue_commit",
                    fields = mapOf(
                        "requestId" to "force_extend",
                        "queueVersion" to queueVersion,
                        "operation" to QueueOperation.AppendQueue.name,
                        "slotCount" to plannedQueue.size,
                        "lockedCount" to 1,
                        "mixMode" to "Smart",
                        "transitionFeel" to "Natural",
                        "smoothnessAvg" to "1.000",
                        "hardPassed" to true,
                        "accepted" to true,
                        "reordered" to (smartAppend.map { it.id } != append.map { it.id }),
                        "appendCount" to smartAppend.size,
                    ) + appendedTrackFields(smartAppend),
                )
                scheduleTransitionPrepare(
                    CommittedQueuePlan.snapshot(
                        sessionId = PlaybackSessionClock.sessionId,
                        queueVersion = queueVersion,
                        requestId = "force_extend",
                        operation = QueueOperation.AppendQueue,
                        sourceUserText = "force_extend_and_play_next",
                        tracks = plannedQueue,
                        continuous = sourceSnapshot,
                    ),
                )
                resetQueueExtendBackoff()
            } finally {
                setResolvingPlayback(false)
                syncFrom(player)
            }
        }
    }

    private fun fallbackWrapAround(
        player: Player,
        anchorMediaId: String?,
        anchorIndex: Int,
    ) {
        if (!isPlaybackAnchorCurrent(player, anchorMediaId, anchorIndex)) {
            DiagnosticsLogStore.record(
                area = "queue",
                event = "fallback_wrap_suppressed",
                fields = mapOf(
                    "reason" to "playback_already_advanced",
                    "anchorMediaId" to anchorMediaId,
                    "anchorIndex" to anchorIndex,
                    "currentMediaId" to player.currentMediaItem?.mediaId,
                    "currentIndex" to player.currentMediaItemIndex,
                    "positionMs" to player.currentPosition.coerceAtLeast(0L),
                ),
            )
            return
        }
        val count = player.mediaItemCount
        if (count > 0) {
            val queueVersion = PlaybackSessionClock.bump("fallback_wrap_around")
            player.seekTo(0, 0L)
            player.prepare()
            userPausedPlayback = false
            player.play()
            DiagnosticsLogStore.record(
                area = "queue",
                event = "fallback_wrap_around",
                fields = currentTrackFields() + mapOf("queueVersion" to queueVersion),
            )
        }
    }

    private fun isPlaybackAnchorCurrent(
        player: Player,
        anchorMediaId: String?,
        anchorIndex: Int,
    ): Boolean =
        player.currentMediaItemIndex == anchorIndex &&
            player.currentMediaItem?.mediaId == anchorMediaId

    private fun recoverFromStateQueue(player: Player, offset: Int): Boolean {
        if (BackgroundAgentContinuation.isActive()) return false
        val queue = state.queue
        if (queue.size <= 1) return false
        val currentId = player.currentMediaItem?.mediaId
        val currentIdx = currentId
            ?.let { id -> queue.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: state.currentIndex.coerceIn(0, queue.lastIndex)
        val targetIdx = offsetTargetIndex(currentIdx, queue.size, offset) ?: return false
        val rotated = queue.drop(targetIdx) + queue.take(targetIdx)
        val queueVersion = PlaybackSessionClock.bump("recover_from_state_queue")
        val gen = ++playGen
        viewModelScope.launch {
            val firstResolved = resolveFirstPlayable(rotated) ?: return@launch
            if (gen != playGen) return@launch
            if (BackgroundAgentContinuation.isActive()) return@launch
            val live = controller ?: return@launch
            val resolvedIdx = rotated.indexOfFirst { it.id == firstResolved.id }.coerceAtLeast(0)
            val pendingQueue = listOf(firstResolved) +
                rotated.drop(resolvedIdx + 1) +
                rotated.take(resolvedIdx)
            state = state.copy(queue = pendingQueue, currentIndex = 0)
            live.setMediaItems(listOf(toMediaItem(firstResolved)), 0, 0L)
            applyPlaybackMode(live)
            live.volume = 1f
            live.prepare()
            userPausedPlayback = false
            live.play()
            DiagnosticsLogStore.record(
                area = "playback_orchestrator",
                event = "queue_commit",
                fields = mapOf(
                    "requestId" to "recover_from_state_queue",
                    "queueVersion" to queueVersion,
                    "operation" to QueueOperation.ReplaceQueue.name,
                    "slotCount" to pendingQueue.size,
                    "lockedCount" to 1,
                    "mixMode" to "Smart",
                    "transitionFeel" to "Natural",
                    "smoothnessAvg" to "1.000",
                    "hardPassed" to true,
                    "accepted" to true,
                    "reordered" to true,
                ),
            )
            scheduleTransitionPrepare(
                CommittedQueuePlan.snapshot(
                    sessionId = PlaybackSessionClock.sessionId,
                    queueVersion = queueVersion,
                    requestId = "recover_from_state_queue",
                    operation = QueueOperation.ReplaceQueue,
                    sourceUserText = "recover_from_state_queue",
                    tracks = pendingQueue,
                    continuous = continuousSource,
                ),
            )

            val rest = pendingQueue.drop(1)
            for (chunk in appendResolveChunks(rest)) {
                val resolvedChunk = resolvePlayableQueue(chunk).filter { it.streamUrl.isNotBlank() }
                if (gen != playGen || resolvedChunk.isEmpty()) continue
                if (BackgroundAgentContinuation.isActive()) return@launch
                val liveAfterResolve = controller ?: return@launch
                liveAfterResolve.addMediaItems(resolvedChunk.map(::toMediaItem))
                val mergedQueue = mergeResolvedTracks(state.queue, resolvedChunk)
                state = state.copy(
                    queue = mergedQueue,
                    currentIndex = currentQueueIndexFor(liveAfterResolve, mergedQueue),
                )
                maybePrepareAutoMix(liveAfterResolve)
            }
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
                applyPlaybackMode(p)
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

    fun seekToMs(positionMs: Long) {
        val player = controller ?: return
        val duration = player.duration.takeIf { it > 0 } ?: state.durationMs
        val targetMs = if (duration > 0L) {
            positionMs.coerceIn(0L, duration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        DiagnosticsLogStore.record(
            area = "lyrics",
            event = "line_seek",
            fields = currentTrackFields() + mapOf(
                "fromMs" to positionMs,
                "toMs" to targetMs,
            ),
        )
        player.seekTo(targetMs)
        syncFrom(player)
    }

    private fun setResolvingPlayback(value: Boolean) {
        resolvingPlayback = value
        val player = controller
        state = state.copy(
            isLoading = value || (
                player != null &&
                    player.playbackState == Player.STATE_BUFFERING &&
                    player.playWhenReady &&
                    player.mediaItemCount > 0
            ),
        )
    }

    private var originalQueueOrder: List<String> = emptyList()

    fun playBrowseQueue(tracks: List<NativeTrack>, source: ContinuousQueueSource?): QueueCommitResult {
        val ordered = if (preferredPlaybackMode == PlaybackQueueMode.ShufflePlay && source == null) tracks.take(1) + tracks.drop(1).shuffledAvoidingSameOrder() else tracks
        val result = applyAgentQueueRequest(AgentQueueRequest(
            requestId = "browse_${System.nanoTime()}", sourceUserText = "browse_play 按顺序",
            operation = QueueOperation.ReplaceQueue, tracks = ordered, continuous = source,
        ))
        if (result is QueueCommitResult.Success) {
            PlaybackSessionClock.noteManualSelection()
            originalQueueOrder = tracks.map { it.id }
        }
        return result
    }

    fun setPlaybackMode(mode: PlaybackQueueMode) {
        applyPlaybackModePreference(mode, persist = true)
    }

    private fun applyPlaybackModePreference(mode: PlaybackQueueMode, persist: Boolean) {
        val previousMode = state.playbackMode
        preferredPlaybackMode = mode
        if (persist && BackgroundAgentContinuation.isActive() &&
            !BackgroundAgentContinuation.setEnabled(mode == PlaybackQueueMode.AiRadio)
        ) {
            // 后台普通歌单没有专属来源，用户开启续播时按当前歌曲接通用推荐。
            BackgroundAgentContinuation.clear()
            lastAgentContinuousSource = null
        }
        continuousSource = continuousSourceForMode(mode, explicitSource = lastAgentContinuousSource)
        state = state.copy(playbackMode = mode)
        controller?.let(::applyPlaybackMode)
        // 用户在播放中切到随机播放,把当前曲后面的队列实际打乱 ——
        // applyPlaybackMode 只改 repeatMode,不改顺序,光靠它"下一首"还是原顺序的下一首。
        if (persist && mode == PlaybackQueueMode.ShufflePlay && previousMode != PlaybackQueueMode.ShufflePlay) {
            reshuffleUpcomingQueue()
        } else if (persist && previousMode == PlaybackQueueMode.ShufflePlay && mode != PlaybackQueueMode.ShufflePlay) {
            reshuffleUpcomingQueue(restoreOrder = true)
        }
        DiagnosticsLogStore.record(
            area = "settings",
            event = if (persist) "playback_mode_selected" else "playback_mode_loaded",
            fields = mapOf("mode" to mode.name),
        )
        if (persist) {
            viewModelScope.launch {
                val current = repository.settings.first()
                if (current.playbackMode != mode.name) {
                    repository.updateSettings(current.copy(playbackMode = mode.name))
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun reshuffleUpcomingQueue(restoreOrder: Boolean = false) {
        val player = controller ?: return
        val queue = state.queue
        val count = player.mediaItemCount
        if (queue.size < 2 || count < 1) return
        val currentIdx = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val firstUpcoming = currentIdx + 1
        if (firstUpcoming >= queue.size) return
        val upcomingOriginal = queue.subList(firstUpcoming, queue.size).toList()
        if (upcomingOriginal.size < 2) return
        if (originalQueueOrder.isEmpty()) originalQueueOrder = queue.map { it.id }
        val rank = originalQueueOrder.withIndex().associate { it.value to it.index }
        val upcomingShuffled = if (restoreOrder) upcomingOriginal.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
            else upcomingOriginal.shuffledAvoidingSameOrder()
        val newQueue = queue.subList(0, firstUpcoming).toList() + upcomingShuffled
        state = state.copy(queue = newQueue)
        alignLoadedQueueOrder(player)
        DiagnosticsLogStore.record(
            area = "playback",
            event = "shuffle_upcoming_reshuffled",
            fields = mapOf(
                "queueSize" to queue.size,
                "reshuffledFrom" to firstUpcoming,
            ),
        )
    }

    private fun alignLoadedQueueOrder(player: Player) {
        val rank = state.queue.withIndex().associate { it.value.id to it.index }
        val firstUpcoming = player.currentMediaItemIndex + 1
        if (firstUpcoming < 0 || firstUpcoming >= player.mediaItemCount) return
        val orderedIds = (firstUpcoming until player.mediaItemCount)
            .map { player.getMediaItemAt(it).mediaId }
            .sortedBy { rank[it] ?: Int.MAX_VALUE }
        orderedIds.forEachIndexed { offset, id ->
            val target = firstUpcoming + offset
            val current = (target until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == id }
            if (current != null && current != target) player.moveMediaItem(current, target)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun modeForNewQueue(explicitSource: ContinuousQueueSource?): PlaybackQueueMode {
        // 本轮单曲循环、播完停止和 AI 续播由 source 决定，不覆盖用户的默认播放偏好。
        // 手动歌单/插下一首仍走原偏好。
        return when {
            explicitSource?.repeatsCurrentTrack() == true -> PlaybackQueueMode.SingleLoop
            explicitSource?.startsAutomatically() == false -> PlaybackQueueMode.OrderOnce
            explicitSource != null -> PlaybackQueueMode.AiRadio
            else -> preferredPlaybackMode
        }
    }

    private fun continuousSourceForMode(
        mode: PlaybackQueueMode,
        explicitSource: ContinuousQueueSource?,
    ): ContinuousQueueSource? {
        return if (mode == PlaybackQueueMode.AiRadio) {
            explicitSource ?: lastAgentContinuousSource ?: defaultContinuousSource
        } else {
            null
        }
    }

    fun currentPlaybackPositionMs(): Long {
        // 保留可观察状态读取，暂停时的 seek/切歌仍能通知叶子 UI；播放帧直接读取
        // MediaController 的媒体时间，其内部会使用实际 playbackSpeed 推进。
        val publishedPositionMs = positionMs
        val player = controller
        return if (player != null && player.isConnected && player.currentMediaItem != null) {
            player.currentPosition.coerceAtLeast(0L)
        } else {
            publishedPositionMs
        }
    }

    fun refreshPosition() {
        // 进度/元数据同步 only。卡死自愈不在这儿:它只在前台跑(UI 帧驱动),后台失效,
        // 且和服务端看门狗对同一卡顿重复 seek+prepare —— 已全部收归 PipoPlaybackService。
        controller?.let { player ->
            syncFrom(player)
        }
    }

    override fun onCleared() {
        controller?.let { p ->
            val q = state.queue
            if (q.isNotEmpty()) {
                runCatching {
                    lastPlaybackStore.save(q, currentQueueIndexFor(p, q), currentPlaybackPositionMs())
                }
            }
        }
        controller?.removeListener(listener)
        lyricsJob?.cancel()
        currentLyricsRequest?.deferred?.cancel()
        nextLyricsRequest?.deferred?.cancel()
        nextPrewarmJob?.cancel()
        nextArtworkPrefetchJob?.cancel()
        nextTrackPrewarmer.cancelAll()
        autoMixFeatureJob?.cancel()
        stablePlaybackResetJob?.cancel()
        pendingAgentPlayback = null
        MediaController.releaseFuture(controllerFuture)
        controller = null
        super.onCleared()
    }

    private fun syncFrom(player: Player) {
        val queue = state.queue
        // 关键：trackId 以 player.currentMediaItem.mediaId 为权威源，不再相信 state.queue[index]。
        // 之前 phase-1/phase-2 期间（state.queue 还没追上 player 实际队列）会出现
        // "用 queue 里的旧 track.id 拉歌词、配上 player 实际在播的另一首" → 歌词不对。
        val playerMediaId = player.currentMediaItem?.mediaId
        val index = currentQueueIndexFor(player, queue)
        val track = playerMediaId?.let { id -> queue.firstOrNull { it.id == id } } ?: queue.getOrNull(index)
        val authoritativeTrackId = playerMediaId ?: track?.id
        if (authoritativeTrackId != null && loadedLyricsFor != authoritativeTrackId) {
            loadedLyricsFor = authoritativeTrackId
            val targetTrackId = authoritativeTrackId
            val lyricTrack = track?.takeIf { it.id == targetTrackId }?.let {
                it.copy(durationMs = player.duration.takeIf { duration -> duration > 0L } ?: it.durationMs)
            } ?: player.currentMediaItem?.takeIf { it.mediaId == targetTrackId }?.let { item ->
                NativeTrack(
                    id = targetTrackId,
                    neteaseId = targetTrackId.toLongOrNull(),
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    artist = item.mediaMetadata.artist?.toString().orEmpty(),
                    album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
                    streamUrl = "",
                    durationMs = player.duration.takeIf { it > 0L } ?: 0L,
                )
            }
            // 用单调递增 token 而不是 trackId 做竞态保护 ——
            // A→B→A 切歌时 trackId 会回到 A，老的 A 拉取已经被新 B 覆盖（loadedLyricsFor=B），
            // 然后又被新 A 覆盖（loadedLyricsFor=A），此时如果用 trackId 比对，老的 A
            // 拉取（可能比新 A 拉取先到）会通过校验，把过期的（甚至来源不对的）lines 写入。
            // counter 唯一递增，每次发起 +1，回来时只有"当前最新"的 token 能写
            lyricsRequestSeq += 1
            val mySeq = lyricsRequestSeq
            lyricsJob?.cancel()
            currentLyricsRequest?.deferred?.cancel()
            val prefetched = nextLyricsRequest?.takeIf { it.matches(targetTrackId, lyricTrack) }
            if (prefetched != null) nextLyricsRequest = null
            // 预取已失败或没有结果时允许前台重新查询；正处于查询中的预取直接接管。
            val request = prefetched?.takeIf { pending ->
                pending.result == null || pending.result?.getOrNull()?.isNotEmpty() == true
            } ?: LyricsRequest(targetTrackId, lyricTrack, viewModelScope, repository)
            currentLyricsRequest = request
            val readyLines = request.result?.getOrNull()
            state = state.copy(
                lyrics = readyLines.orEmpty(),
                isLyricsLoading = request.result == null,
            )
            lyricsJob = viewModelScope.launch {
                val lyricResult = request.deferred.await()
                lyricResult.exceptionOrNull()?.let { err ->
                    DiagnosticsLogStore.record(
                        area = "lyrics",
                        event = "load_failed",
                        fields = mapOf(
                            "trackId" to targetTrackId,
                            "error" to err::class.java.simpleName,
                            "message" to err.message,
                        ),
                    )
                }
                val lines = lyricResult.getOrDefault(emptyList())
                if (lyricsRequestSeq == mySeq) {
                    DiagnosticsLogStore.record(
                        area = "lyrics",
                        event = "loaded",
                        fields = mapOf(
                            "trackId" to targetTrackId,
                            "lineCount" to lines.size,
                            "wordLineCount" to lines.count { it.chars.isNotEmpty() },
                            "rightAlignedLineCount" to lines.count { it.alignment == PipoLyricAlignment.End },
                            "tokenCount" to lines.sumOf { it.chars.size + it.companionLines.sumOf { companion -> companion.chars.size } },
                            "timingPartCount" to lines.sumOf {
                                it.chars.sumOf { char -> char.timingParts.size.coerceAtLeast(1) } +
                                    it.companionLines.sumOf { companion ->
                                        companion.chars.sumOf { char -> char.timingParts.size.coerceAtLeast(1) }
                                    }
                            },
                            "firstLineStartMs" to lines.firstOrNull()?.startMs,
                            "firstAudioStartMs" to lines.firstOrNull()?.let { LyricTiming.audioStartMs(it) },
                            "lastLineStartMs" to lines.lastOrNull()?.startMs,
                            "lastLineDurationMs" to lines.lastOrNull()?.durationMs,
                            "focusLeadMs" to LyricTiming.focusLeadMs(lines),
                            "companionLineCount" to lines.sumOf { it.companionLines.size },
                            // 慢词诊断：有多少主词 ≥1s（可能触发 emphasis 辉光）+ 最长词时长。
                            // 若 maxTokenDurationMs 远低于 1000，则这首歌本就没有慢词、看不到辉光属正常。
                            "longTokenCountGte1000" to lines.sumOf { line -> line.chars.count { it.durationMs >= 1000L } },
                            "maxTokenDurationMs" to (lines.flatMap { it.chars }.maxOfOrNull { it.durationMs } ?: 0L),
                        ),
                    )
                    state = state.copy(lyrics = lines, isLyricsLoading = false)
                }
            }
        }
        // 进度是高频字段 —— 每帧只写独立的 positionMs holder,不进 state。
        // A connected controller can still have an empty timeline during deferred cold
        // restore. Its default zero is not the saved song's actual playback position.
        if (playerMediaId != null || queue.isEmpty()) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
        }
        lyricPlaybackSpeed = player.playbackParameters.speed

        val newTitle = player.mediaMetadata.title?.toString() ?: track?.title.orEmpty()
        val newArtist = player.mediaMetadata.artist?.toString() ?: track?.artist.orEmpty()
        val newAlbum = player.mediaMetadata.albumTitle?.toString() ?: track?.album.orEmpty()
        val newArtworkUrl = player.mediaMetadata.artworkUri?.toString()
            ?: track?.artworkUrl
            ?: embeddedArtworkDataUri(authoritativeTrackId, player.mediaMetadata.artworkData)
        val newIsPlaying = player.isPlaying
        val newDurationMs = player.duration.takeIf { it > 0 } ?: track?.durationMs ?: 0L
        val newIsLoading = resolvingPlayback || (
            player.playbackState == Player.STATE_BUFFERING &&
                player.playWhenReady &&
                player.mediaItemCount > 0
        )
        val newPlaybackMode = BackgroundAgentContinuation.playbackMode() ?: state.playbackMode
        // 仅当元数据真正变化时才替换 state —— 避免 30Hz 进度推进每帧重建 PlayerUiState、
        // 触发顶层 shell 与播放页全树重组。这里只比标量 / 字符串(不碰 queue / lyrics 大列表)。
        if (
            state.currentIndex != index ||
            state.title != newTitle ||
            state.artist != newArtist ||
            state.album != newAlbum ||
            state.currentTrackId != authoritativeTrackId ||
            state.artworkUrl != newArtworkUrl ||
            state.isPlaying != newIsPlaying ||
            state.durationMs != newDurationMs ||
            !state.isReady ||
            state.isLoading != newIsLoading ||
            state.playbackMode != newPlaybackMode
        ) {
            state = state.copy(
                currentIndex = index,
                title = newTitle,
                artist = newArtist,
                album = newAlbum,
                currentTrackId = authoritativeTrackId,
                artworkUrl = newArtworkUrl,
                isPlaying = newIsPlaying,
                durationMs = newDurationMs,
                isReady = true,
                isLoading = newIsLoading,
                playbackMode = newPlaybackMode,
            )
        }
        // 续杯：current 后剩 < 阈值时调一次 fetchMore
        maybeExtendQueue()
        maybePrepareAutoMix(player)
        // 节流持久化：杀掉冷启动黑屏 + 跳到 playlist[0] 的尴尬
        if (queue.isNotEmpty()) {
            lastPlaybackStore.saveThrottled(queue, index, positionMs)
        }
    }

    private fun maybePrepareAutoMix(player: Player) {
        maybePrefetchNextLyrics(player)
        maybePrefetchNextArtwork(player)
        maybePrewarmNextTrack(player)
        maybePrefetchAutoMixFeatures(player)
    }

    private fun maybePrefetchNextLyrics(player: Player) {
        val nextTrack = nextTrackFor(player)
        val pending = nextLyricsRequest
        if (pending != null && (nextTrack == null || !pending.matches(nextTrack.id, nextTrack))) {
            pending.deferred.cancel()
            nextLyricsRequest = null
        }
        if (!player.isPlaying || nextTrack == null || nextLyricsRequest != null ||
            currentLyricsRequest?.matches(nextTrack.id, nextTrack) == true
        ) return

        // 与封面一样在开始播放后预取，不等待音频预热的播放进度门槛。
        nextLyricsRequest = LyricsRequest(nextTrack.id, nextTrack, viewModelScope, repository)
    }

    private fun maybePrefetchNextArtwork(player: Player) {
        val nextTrack = nextTrackFor(player)
        // 与 PlayerMediaFactory 的 artworkUri 一致，避免 http/https 分占缓存。
        val url = nextTrack?.artworkUrl?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http://")) it.replaceFirst("http://", "https://") else it
        }
        val key = if (nextTrack != null && url != null) nextTrack.id to url else null
        if (key != nextArtworkPrefetchKey) {
            nextArtworkPrefetchJob?.cancel()
            nextArtworkPrefetchJob = null
            nextArtworkPrefetchKey = key
            nextArtworkPrefetched = false
            nextArtworkRetryAtMs = 0L
        }
        if (!player.isPlaying || key == null || nextArtworkPrefetched ||
            nextArtworkPrefetchJob?.isActive == true || SystemClock.elapsedRealtime() < nextArtworkRetryAtMs
        ) return

        // 开始播放就准备下一张图，独立于音频的 55% / 90 秒门槛；不等待图片再播放。
        nextArtworkPrefetchJob = viewModelScope.launch {
            val completed = try {
                withTimeoutOrNull(15_000L) {
                    val loader = Coil.imageLoader(appContext)
                    // 先缓存大封面，再从同一磁盘条目解码玻璃背景和迷你播放器缩略图。
                    for (sizePx in listOf(960, 448, 160)) {
                        val result = loader.execute(coverImageRequest(appContext, key.second, sizePx))
                        if (result !is SuccessResult) return@withTimeoutOrNull false
                    }
                    true
                } == true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            if (nextArtworkPrefetchKey == key) {
                nextArtworkPrefetched = completed
                if (!completed) nextArtworkRetryAtMs = SystemClock.elapsedRealtime() + 30_000L
            }
        }
    }

    private fun maybePrewarmNextTrack(player: Player) {
        if (!player.isPlaying) return
        // Service 持有唯一可信的主 Player 快照；UI 只用 gate 决定是否允许发起网络工作。
        if (!PlaybackPreparationGate.canSeedNextTrack()) return
        val durationMs = player.duration.takeIf { it > 0 } ?: state.durationMs
        if (durationMs <= 0L) return
        val currentPositionMs = player.currentPosition.coerceAtLeast(positionMs)
        val remainingMs = (durationMs - currentPositionMs).coerceAtLeast(0L)
        val shouldPrewarm = currentPositionMs >= (durationMs * PREWARM_AFTER_PROGRESS).toLong() ||
            remainingMs <= PREWARM_WHEN_REMAINING_MS
        if (!shouldPrewarm) return
        val nextTrack = nextTrackFor(player) ?: return
        val now = SystemClock.elapsedRealtime()
        prewarmFailureBackoffUntilMs[nextTrack.id]?.let { until ->
            if (now < until) return
            prewarmFailureBackoffUntilMs.remove(nextTrack.id)
        }
        val nextKey = prewarmKey(nextTrack)
        if (nextKey == prewarmedNextKey || nextKey == prewarmingNextKey) return

        nextPrewarmJob?.cancel()
        nextTrackPrewarmer.cancel(NextTrackPrewarmPriority.NEAR)
        prewarmingNextKey = nextKey
        val queueVersion = PlaybackSessionClock.currentQueueVersion()
        nextPrewarmJob = viewModelScope.launch {
            val playable = resolvedPrewarmTrack
                ?.takeIf { resolvedPrewarmTrackId == nextTrack.id && it.streamUrl.isNotBlank() }
                ?: refreshTrackForPrewarm(nextTrack, queueVersion)?.also { refreshed ->
                    resolvedPrewarmTrackId = nextTrack.id
                    resolvedPrewarmTrack = refreshed
                }
                ?: run {
                if (isPrewarmRequestCurrent(queueVersion, nextKey, nextKey)) {
                    prewarmFailureBackoffUntilMs[nextTrack.id] =
                        SystemClock.elapsedRealtime() + PREWARM_FAILURE_BACKOFF_MS
                }
                if (prewarmingNextKey == nextKey) prewarmingNextKey = null
                return@launch
            }
            val playableKey = prewarmKey(playable)
            when (nextTrackPrewarmer.prewarm(playable, NextTrackPrewarmPriority.NEAR)) {
                NextTrackPrewarmOutcome.FULL -> {
                    if (isPrewarmRequestCurrent(queueVersion, nextKey, playableKey)) {
                        prewarmedNextKey = playableKey
                        prewarmFailureBackoffUntilMs.remove(playable.id)
                    }
                }
                // 与刚结束的远曲头段合并时，下一轮会以 NEAR 再进入并在水位足够后补整曲。
                NextTrackPrewarmOutcome.SEEDED -> Unit
                NextTrackPrewarmOutcome.FAILED -> {
                    if (isPrewarmRequestCurrent(queueVersion, nextKey, playableKey)) {
                        resolvedPrewarmTrackId = null
                        resolvedPrewarmTrack = null
                        prewarmFailureBackoffUntilMs[playable.id] =
                            SystemClock.elapsedRealtime() + PREWARM_FAILURE_BACKOFF_MS
                    }
                }
                NextTrackPrewarmOutcome.YIELDED,
                NextTrackPrewarmOutcome.DEFERRED -> Unit
            }
            if (prewarmingNextKey == nextKey || prewarmingNextKey == playableKey) {
                prewarmingNextKey = null
            }
        }
    }

    private fun isPrewarmRequestCurrent(
        queueVersion: Long,
        requestedKey: String,
        playableKey: String,
    ): Boolean {
        if (!PlaybackSessionClock.isCurrent(queueVersion)) return false
        val live = controller ?: return false
        val currentNext = nextTrackFor(live) ?: return false
        val currentKey = prewarmKey(currentNext)
        return currentKey == requestedKey || currentKey == playableKey
    }

    private suspend fun refreshTrackForPrewarm(track: NativeTrack, queueVersion: Long): NativeTrack? {
        val refreshed = track.neteaseId
            ?.let { id -> fetchPlayable(id)?.let { resolved -> track.withPlayable(resolved) } }
            ?: track.takeIf { it.streamUrl.isNotBlank() }
            ?: return null
        if (!PlaybackSessionClock.isCurrent(queueVersion)) return null
        val player = controller ?: return refreshed
        if (nextTrackFor(player)?.id != track.id) return null
        if (CrossfadeController.protectsMediaItem(track.id)) return refreshed
        val itemIndex = player.indexOfMediaId(refreshed.id) ?: return refreshed
        val queueIndex = state.queue.indexOfFirst { it.id == refreshed.id }
        if (queueIndex >= 0 && state.queue[queueIndex].streamUrl != refreshed.streamUrl) {
            val nextQueue = state.queue.toMutableList().apply { set(queueIndex, refreshed) }
            state = state.copy(queue = nextQueue)
        }
        if (itemIndex != player.currentMediaItemIndex) {
            val replacement = if (queueIndex >= 0) {
                toMediaItem(refreshed, state.queue, queueIndex)
            } else {
                toMediaItem(refreshed)
            }
            runCatching { player.replaceMediaItem(itemIndex, replacement) }
        }
        return refreshed
    }

    private fun maybePrefetchAutoMixFeatures(player: Player) {
        if (!player.isPlaying || player.mediaItemCount <= 1) return
        if (!PlaybackPreparationGate.canSeedNextTrack()) return
        val queueVersion = PlaybackSessionClock.currentQueueVersion()
        val targets = autoMixLookaheadTracks(player).filter { track ->
            val now = SystemClock.elapsedRealtime()
            featuresStore.get(track.id) == null &&
                track.neteaseId != null &&
                autoMixFeatureBackoffUntilMs[track.id]?.let { now < it } != true
        }
        if (targets.isEmpty()) return

        val key = targets.joinToString("|") { "${it.id}:${it.streamUrl}" }
        if (autoMixFeatureJob?.isActive == true || key == autoMixFeatureKey) return
        autoMixFeatureKey = key
        autoMixFeatureJob = viewModelScope.launch {
            try {
                for (target in targets) {
                    if (!isAutoMixFeatureRequestCurrent(queueVersion, target.id) ||
                        !PlaybackPreparationGate.canSeedNextTrack()
                    ) break
                    if (featuresStore.get(target.id) != null) continue
                    val neteaseId = target.neteaseId ?: continue
                    var resolved = target.streamUrl.takeIf { it.isNotBlank() }
                        ?.let { url ->
                            PlaybackUrlResolver.ResolvedPlaybackUrl(
                                url = url,
                                cacheKey = PlaybackCacheKeys.forTrack(target) ?: url,
                            )
                    }
                    if (resolved == null) {
                        if (!PlaybackPreparationGate.canSeedNextTrack()) break
                        val fetched = fetchPlayable(neteaseId)
                        if (fetched == null) {
                            if (isAutoMixFeatureRequestCurrent(queueVersion, target.id) &&
                                PlaybackPreparationGate.canSeedNextTrack()
                            ) {
                                armAutoMixFeatureBackoff(target.id)
                            }
                            continue
                        }
                        resolved = fetched
                    }
                    val playable = resolved ?: continue
                    if (!PlaybackPreparationGate.canSeedNextTrack()) break
                    val features = withTimeoutOrNull(AUTO_MIX_FEATURE_TIMEOUT_MS) {
                        runCatching { repository.audioFeatures(neteaseId, playable.url) }.getOrNull()
                    }
                    if (features != null &&
                        PlaybackPreparationGate.canSeedNextTrack() &&
                        isAutoMixFeatureRequestCurrent(queueVersion, target.id)
                    ) {
                        featuresStore.put(target.id, features)
                        autoMixFeatureBackoffUntilMs.remove(target.id)
                        if (target.streamUrl != playable.url || target.streamCacheKey != playable.cacheKey) {
                            updateResolvedAutoMixTrack(target, playable)
                        }
                        DiagnosticsLogStore.record(
                            area = "automix",
                            event = "features_ready",
                            fields = trackFields(target) + mapOf(
                                "bpm" to features.bpm,
                                "bpmConfidence" to "%.2f".format(features.bpmConfidence),
                                "headSilenceS" to "%.2f".format(features.headSilenceS),
                                "tailSilenceS" to "%.2f".format(features.tailSilenceS),
                                "featureDurationMs" to (features.durationS * 1000).toLong(),
                                "metadataDurationMs" to target.durationMs,
                            ),
                        )
                    } else if (PlaybackPreparationGate.canSeedNextTrack() &&
                        isAutoMixFeatureRequestCurrent(queueVersion, target.id)
                    ) {
                        armAutoMixFeatureBackoff(target.id)
                    }
                }
            } finally {
                if (autoMixFeatureKey == key) autoMixFeatureKey = null
            }
        }
    }

    private fun isAutoMixFeatureRequestCurrent(queueVersion: Long, trackId: String): Boolean =
        PlaybackSessionClock.isCurrent(queueVersion) && state.queue.any { it.id == trackId }

    private fun autoMixLookaheadTracks(player: Player): List<NativeTrack> {
        val queue = state.queue
        if (queue.isEmpty()) return emptyList()
        val currentId = player.currentMediaItem?.mediaId
        val currentIndex = currentId
            ?.let { id -> queue.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: state.currentIndex.coerceIn(0, queue.lastIndex)
        return (0..AUTO_MIX_FEATURE_LOOKAHEAD).mapNotNull { offset ->
            val idx = offsetTargetIndex(currentIndex, queue.size, offset) ?: return@mapNotNull null
            queue.getOrNull(idx)
        }.distinctBy { it.id }
    }

    private fun updateResolvedAutoMixTrack(
        track: NativeTrack,
        resolved: PlaybackUrlResolver.ResolvedPlaybackUrl,
    ) {
        if (CrossfadeController.protectsMediaItem(track.id)) return
        val live = controller ?: return
        val queueIndex = state.queue.indexOfFirst { it.id == track.id }
        if (
            queueIndex < 0 ||
            (state.queue[queueIndex].streamUrl == resolved.url &&
                state.queue[queueIndex].streamCacheKey == resolved.cacheKey)
        ) return
        val updated = state.queue[queueIndex].withPlayable(resolved)
        val nextQueue = state.queue.toMutableList().apply { set(queueIndex, updated) }
        state = state.copy(queue = nextQueue)
        val itemIndex = live.indexOfMediaId(track.id) ?: return
        if (itemIndex != live.currentMediaItemIndex) {
            runCatching {
                live.replaceMediaItem(itemIndex, toMediaItem(updated, nextQueue, queueIndex))
            }
        }
    }

    private fun armAutoMixFeatureBackoff(trackId: String) {
        autoMixFeatureBackoffUntilMs[trackId] =
            SystemClock.elapsedRealtime() + AUTO_MIX_FEATURE_FAILURE_BACKOFF_MS
    }

    private fun nextTrackFor(player: Player): NativeTrack? {
        val queue = state.queue
        if (queue.size < 2) return null
        val currentId = player.currentMediaItem?.mediaId
        val currentIndex = currentId
            ?.let { id -> queue.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: state.currentIndex.coerceIn(0, queue.lastIndex)
        val nextIndex = offsetTargetIndex(currentIndex, queue.size, offset = 1) ?: return null
        if (nextIndex == currentIndex) return null
        return queue.getOrNull(nextIndex)
    }

    private fun prewarmKey(track: NativeTrack): String =
        "${track.id}:${track.streamUrl}:${track.streamCacheKey.orEmpty()}"

    private fun applyPlaybackMode(player: Player) {
        player.repeatMode = when (state.playbackMode) {
            PlaybackQueueMode.OrderOnce -> Player.REPEAT_MODE_OFF
            PlaybackQueueMode.SingleLoop -> Player.REPEAT_MODE_ONE
            PlaybackQueueMode.PlaylistLoop,
            PlaybackQueueMode.ShufflePlay,
            PlaybackQueueMode.AiRadio -> Player.REPEAT_MODE_ALL
        }
    }

    private fun offsetTargetIndex(currentIdx: Int, count: Int, offset: Int): Int? {
        if (count <= 0) return null
        val raw = currentIdx + offset
        if (state.playbackMode != PlaybackQueueMode.OrderOnce) {
            return Math.floorMod(raw, count)
        }
        return raw.takeIf { it in 0 until count }
    }

    private fun recordAutoWrapPlaybackPreserved(mediaItem: androidx.media3.common.MediaItem?) {
        val player = controller ?: return
        val queue = state.queue
        if (state.playbackMode != PlaybackQueueMode.AiRadio) return
        if (!player.playWhenReady || queue.size <= 1) return
        val previousIndex = state.currentIndex
        val newIndex = queue.indexOfFirst { it.id == mediaItem?.mediaId }
        if (newIndex != 0) return
        val tailWindowStart = (queue.lastIndex - extendThreshold).coerceAtLeast(0)
        if (previousIndex < tailWindowStart) return
        DiagnosticsLogStore.record(
            area = "queue",
            event = "ai_radio_auto_wrap_playback_preserved",
            fields = mapOf(
                "queueSize" to state.queue.size,
                "previousIndex" to previousIndex,
                "currentIndex" to newIndex,
                "mediaItemCount" to player.mediaItemCount,
                "playbackMode" to state.playbackMode.name,
                "mediaId" to mediaItem?.mediaId,
                "title" to mediaItem?.mediaMetadata?.title?.toString(),
            ),
        )
    }

    private fun queueExtendDiagFields(
        trigger: String,
        queueSnapshot: List<NativeTrack>,
        remaining: Int,
    ): Map<String, Any?> =
        mapOf(
            "trigger" to trigger,
            "queueSize" to queueSnapshot.size,
            "currentIndex" to state.currentIndex,
            "remaining" to remaining,
            "threshold" to extendThreshold,
            "playbackMode" to state.playbackMode.name,
        )

    private fun continuousSourceKind(source: ContinuousQueueSource): String =
        when {
            source === defaultContinuousSource -> "default"
            !source.permitsDefaultFallback() -> "agent_strict"
            else -> "agent"
        }

    private fun recordQueueExtendSkip(
        trigger: String,
        reason: String,
        queueSnapshot: List<NativeTrack>,
        remaining: Int,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        val now = SystemClock.elapsedRealtime()
        val key = "$trigger:$reason:${state.currentIndex}:${queueSnapshot.size}"
        if (queueExtendSkipDiagKey == key && now - queueExtendSkipDiagAtMs < QUEUE_EXTEND_SKIP_LOG_THROTTLE_MS) {
            return
        }
        queueExtendSkipDiagKey = key
        queueExtendSkipDiagAtMs = now
        DiagnosticsLogStore.record(
            area = "queue",
            event = "queue_extend_skip",
            fields = queueExtendDiagFields(trigger, queueSnapshot, remaining) + mapOf("reason" to reason) + extra,
        )
    }

    private fun recordQueueExtendFailure(
        trigger: String,
        stage: String,
        queueSnapshot: List<NativeTrack>,
        remaining: Int,
        error: Exception? = null,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        DiagnosticsLogStore.record(
            area = "queue",
            event = "queue_extend_failed",
            fields = queueExtendDiagFields(trigger, queueSnapshot, remaining) + mapOf(
                "stage" to stage,
                "error" to error?.javaClass?.simpleName,
                "message" to error?.message?.take(160),
            ) + extra,
        )
    }

    private fun recordQueueExtendAbort(
        trigger: String,
        reason: String,
        queueSnapshot: List<NativeTrack>,
        remaining: Int,
    ) {
        DiagnosticsLogStore.record(
            area = "queue",
            event = "queue_extend_aborted",
            fields = queueExtendDiagFields(trigger, queueSnapshot, remaining) + mapOf("reason" to reason),
        )
    }

    private fun maybeExtendQueue(trigger: String = "sync", logSkips: Boolean = false) {
        val queueSnapshot = state.queue
        val remaining = queueSnapshot.size - state.currentIndex - 1
        if (BackgroundAgentContinuation.isActive()) {
            if (logSkips) {
                recordQueueExtendSkip(
                    trigger = trigger,
                    reason = "background_agent_continuation_active",
                    queueSnapshot = queueSnapshot,
                    remaining = remaining,
                )
            }
            return
        }
        if (remaining > extendThreshold) return
        if (fetchingMore) {
            if (logSkips) {
                recordQueueExtendSkip(
                    trigger = trigger,
                    reason = "fetching_more",
                    queueSnapshot = queueSnapshot,
                    remaining = remaining,
                )
            }
            return
        }
        val sourceSnapshot = continuousSource ?: run {
            if (logSkips) {
                recordQueueExtendSkip(
                    trigger = trigger,
                    reason = "no_continuous_source",
                    queueSnapshot = queueSnapshot,
                    remaining = remaining,
                )
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now < queueExtendBackoffUntilMs) {
            if (logSkips) {
                recordQueueExtendSkip(
                    trigger = trigger,
                    reason = "backoff",
                    queueSnapshot = queueSnapshot,
                    remaining = remaining,
                    extra = mapOf("backoffRemainingMs" to (queueExtendBackoffUntilMs - now)),
                )
            }
            return
        }
        val mediaCountSnapshot = controller?.mediaItemCount ?: run {
            if (logSkips) {
                recordQueueExtendSkip(
                    trigger = trigger,
                    reason = "controller_missing",
                    queueSnapshot = queueSnapshot,
                    remaining = remaining,
                )
            }
            return
        }
        if (mediaCountSnapshot < queueSnapshot.size) {
            if (logSkips) {
                recordQueueExtendSkip(
                    trigger = trigger,
                    reason = "timeline_not_ready",
                    queueSnapshot = queueSnapshot,
                    remaining = remaining,
                    extra = mapOf("mediaItemCount" to mediaCountSnapshot),
                )
            }
            return
        }
        val gen = playGen
        fetchingMore = true
        DiagnosticsLogStore.record(
            area = "queue",
            event = "queue_extend_start",
            fields = queueExtendDiagFields(trigger, queueSnapshot, remaining) + mapOf(
                "source" to continuousSourceKind(sourceSnapshot),
                "mediaItemCount" to mediaCountSnapshot,
            ),
        )
        viewModelScope.launch {
            try {
                val initialExclusions = queueRecommendationExclusions(queueSnapshot)
                val more = try {
                    sourceSnapshot.fetchMore(initialExclusions.trackIds)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    recordQueueExtendFailure(trigger, "fetch_more_exception", queueSnapshot, remaining, e)
                    armQueueExtendBackoff()
                    return@launch
                }
                if (!isQueueExtendStillCurrent(gen, sourceSnapshot, queueSnapshot)) {
                    recordQueueExtendAbort(trigger, "stale_after_fetch", queueSnapshot, remaining)
                    return@launch
                }
                if (more.isEmpty()) {
                    // 对话/首页的受约束来源暂时耗尽时保留原意图，稍后在同一范围重试。
                    // 清掉来源会让再次开启 AI 续播时退回通用推荐，丢失本轮对话条件。
                    if (!sourceSnapshot.permitsDefaultFallback()) {
                        recordQueueExtendFailure(trigger, "scoped_source_empty", queueSnapshot, remaining)
                        armQueueExtendBackoff()
                        return@launch
                    }
                    // source 跑空：开放推荐可回退到 RecommendEngine 保持电台在线；精确目录 source
                    // 明确禁止降级，宁可停止补歌并循环已核验队列，也不能混入作品外曲目。
                    val fallback = defaultContinuousSource.takeIf {
                        sourceSnapshot.permitsDefaultFallback() &&
                        state.playbackMode == PlaybackQueueMode.AiRadio && sourceSnapshot !== defaultContinuousSource
                    }
                    if (continuousSource === sourceSnapshot) {
                        continuousSource = fallback
                    }
                    if (lastAgentContinuousSource === sourceSnapshot) {
                        lastAgentContinuousSource = null
                    }
                    DiagnosticsLogStore.record(
                        area = "queue",
                        event = if (fallback != null) "continuous_source_fallback_default" else "continuous_source_exhausted",
                        fields = queueExtendDiagFields(trigger, queueSnapshot, remaining) + mapOf(
                            "mode" to state.playbackMode.name,
                            "source" to continuousSourceKind(sourceSnapshot),
                        ),
                    )
                    resetQueueExtendBackoff()
                    return@launch
                }
                val playable = try {
                    resolvePlayableQueue(more).filter { it.streamUrl.isNotBlank() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    recordQueueExtendFailure(trigger, "resolve_exception", queueSnapshot, remaining, e)
                    armQueueExtendBackoff()
                    return@launch
                }
                val resolved = playable.filter(sourceSnapshot::acceptsResolved)
                if (!isQueueExtendStillCurrent(gen, sourceSnapshot, queueSnapshot)) {
                    recordQueueExtendAbort(trigger, "stale_after_resolve", queueSnapshot, remaining)
                    return@launch
                }
                if (resolved.isEmpty()) {
                    if (playable.isNotEmpty() && !sourceSnapshot.permitsDefaultFallback()) {
                        DiagnosticsLogStore.record(
                            area = "queue",
                            event = "continuous_source_guard_exhausted",
                            fields = queueExtendDiagFields(trigger, queueSnapshot, remaining) + mapOf(
                                "source" to continuousSourceKind(sourceSnapshot),
                                "candidateCount" to more.size,
                                "playableCount" to playable.size,
                            ),
                        )
                        armQueueExtendBackoff()
                        return@launch
                    }
                    recordQueueExtendFailure(trigger, "resolved_empty", queueSnapshot, remaining)
                    armQueueExtendBackoff()
                    return@launch
                }
                val currentQueue = state.queue
                val append = selectQueueRecommendationAppend(
                    candidates = resolved,
                    currentQueue = currentQueue,
                    excludedIdsBeforeFetch = initialExclusions.trackIds,
                    trigger = trigger,
                    queueSnapshot = queueSnapshot,
                    remaining = remaining,
                )
                if (append.isEmpty()) {
                    recordQueueExtendFailure(
                        trigger = trigger,
                        stage = "append_empty_after_filter",
                        queueSnapshot = queueSnapshot,
                        remaining = remaining,
                        extra = mapOf(
                            "candidateCount" to more.size,
                            "resolvedCount" to resolved.size,
                            "source" to continuousSourceKind(sourceSnapshot),
                        ),
                    )
                    armQueueExtendBackoff()
                    return@launch
                }
                if (BackgroundAgentContinuation.isActive()) return@launch
                val live = controller ?: return@launch
                if (live.mediaItemCount < currentQueue.size) {
                    recordQueueExtendAbort(
                        trigger = trigger,
                        reason = "timeline_shrank_before_append",
                        queueSnapshot = queueSnapshot,
                        remaining = remaining,
                    )
                    return@launch
                }
                val smartAppend = planAgentAppendForSmartMix(currentQueue, append)
                val plannedQueue = currentQueue + smartAppend
                val queueVersion = PlaybackSessionClock.bump("queue_extend_append")
                state = state.copy(queue = plannedQueue)
                live.addMediaItems(toMediaItems(smartAppend, plannedQueue))
                rememberQueueRecommendations(smartAppend)
                DiagnosticsLogStore.record(
                    area = "playback_orchestrator",
                    event = "queue_commit",
                    fields = mapOf(
                        "requestId" to "queue_extend",
                        "queueVersion" to queueVersion,
                        "operation" to QueueOperation.AppendQueue.name,
                        "slotCount" to plannedQueue.size,
                        "lockedCount" to 1,
                        "mixMode" to "Smart",
                        "transitionFeel" to "Natural",
                        "smoothnessAvg" to "1.000",
                        "hardPassed" to true,
                        "accepted" to true,
                        "reordered" to (smartAppend.map { it.id } != append.map { it.id }),
                        "appendCount" to smartAppend.size,
                    ) + appendedTrackFields(smartAppend),
                )
                scheduleTransitionPrepare(
                    CommittedQueuePlan.snapshot(
                        sessionId = PlaybackSessionClock.sessionId,
                        queueVersion = queueVersion,
                        requestId = "queue_extend",
                        operation = QueueOperation.AppendQueue,
                        sourceUserText = "queue_extend_append",
                        tracks = plannedQueue,
                        continuous = sourceSnapshot,
                    ),
                )
                maybePrepareAutoMix(live)
                resetQueueExtendBackoff()
            } finally {
                fetchingMore = false
            }
        }
    }

    private fun planAgentAppendForSmartMix(
        currentQueue: List<NativeTrack>,
        append: List<NativeTrack>,
    ): List<NativeTrack> {
        if (append.size <= 1) return append
        val anchor = currentQueue.lastOrNull() ?: return append
        val window = (listOf(anchor) + append.take(AGENT_SMART_ORDER_WINDOW))
        if (window.size <= 2) return append

        val smoothedWindow = SmoothQueue.smooth(
            tracks = window,
            featuresStore = featuresStore,
            startTrackId = anchor.id,
            mode = SmoothQueue.Mode.Discovery,
            force = true,
        )
        val continuousRun = continuousAudioTail(smoothedWindow, 0).drop(1)
        if (continuousRun.isEmpty()) return append

        val runIds = continuousRun.mapTo(HashSet()) { it.id }
        val planned = continuousRun + append.filter { it.id !in runIds }
        DiagnosticsLogStore.record(
            area = "queue",
            event = "agent_append_smart_order",
            fields = mapOf(
                "appendCount" to append.size,
                "continuousRunCount" to continuousRun.size,
                "windowCount" to window.size,
            ),
        )
        return planned
    }

    private fun selectQueueRecommendationAppend(
        candidates: List<NativeTrack>,
        currentQueue: List<NativeTrack>,
        excludedIdsBeforeFetch: Set<Long>,
        trigger: String,
        queueSnapshot: List<NativeTrack>,
        remaining: Int,
    ): List<NativeTrack> {
        // 推荐源会在返回前记录本批候选。历史排除沿用请求前快照，防止候选
        // 因刚写入日志而被全部误删；当前队列及主动移除记录仍按最新状态去重。
        val append = filterQueueRecommendationCandidates(
            candidates = candidates,
            exclusions = queueRecommendationExclusions(currentQueue, recentTrackIds = excludedIdsBeforeFetch),
        ).take(QUEUE_EXTEND_APPEND_TARGET)
        if (append.isNotEmpty()) {
            DiagnosticsLogStore.record(
                area = "queue",
                event = "queue_extend_candidate_selected",
                fields = queueExtendDiagFields(trigger, queueSnapshot, remaining) + mapOf(
                    "candidateCount" to candidates.size,
                    "appendCount" to append.size,
                ) + appendedTrackFields(append),
            )
        }
        return append
    }

    private fun isQueueExtendStillCurrent(
        gen: Int,
        sourceSnapshot: ContinuousQueueSource,
        queueSnapshot: List<NativeTrack>,
    ): Boolean {
        return gen == playGen &&
            continuousSource === sourceSnapshot &&
            sameQueueOrder(queueSnapshot, state.queue)
    }

    private fun sameQueueOrder(left: List<NativeTrack>, right: List<NativeTrack>): Boolean {
        if (left.size != right.size) return false
        return left.indices.all { idx -> left[idx].id == right[idx].id }
    }

    private fun queueRecommendationExclusions(
        queue: List<NativeTrack>,
        recentTrackIds: Set<Long> = runCatching {
            PipoGraph.recommendationLog.recentContext().last24hTrackIds
        }.getOrDefault(emptySet()),
    ): QueueRecommendationExclusions {
        val trackIds = LinkedHashSet<Long>()
        queue.mapNotNullTo(trackIds) { it.neteaseId }
        trackIds.addAll(queueRecommendationAvoidIds)
        trackIds.addAll(recentTrackIds)

        val songKeys = LinkedHashSet<String>()
        queue.mapTo(songKeys) { TrackDedupe.songKey(it) }
        songKeys.addAll(queueRecommendationAvoidSongKeys)
        return QueueRecommendationExclusions(trackIds = trackIds, songKeys = songKeys)
    }

    private fun filterQueueRecommendationCandidates(
        candidates: List<NativeTrack>,
        exclusions: QueueRecommendationExclusions,
    ): List<NativeTrack> {
        val seenSongKeys = exclusions.songKeys.toMutableSet()
        return candidates.filter { track ->
            val neteaseId = track.neteaseId
            (neteaseId == null || neteaseId !in exclusions.trackIds) &&
                seenSongKeys.add(TrackDedupe.songKey(track))
        }
    }

    private fun rememberQueueRecommendations(
        tracks: List<NativeTrack>,
        source: RecommendationLog.Source = RecommendationLog.Source.Radio,
    ) {
        rememberQueueRecommendationAvoidance(tracks)
        runCatching { PipoGraph.recommendationLog.logTracks(tracks, source) }
    }

    private fun rememberQueueRecommendationAvoidance(tracks: List<NativeTrack>) {
        if (tracks.isEmpty()) return
        tracks.forEach { track ->
            track.neteaseId?.let(queueRecommendationAvoidIds::add)
            queueRecommendationAvoidSongKeys.add(TrackDedupe.songKey(track))
        }
        trimQueueRecommendationAvoidSets()
    }

    private fun trimQueueRecommendationAvoidSets() {
        while (queueRecommendationAvoidIds.size > QUEUE_RECOMMENDATION_AVOID_MAX) {
            val iterator = queueRecommendationAvoidIds.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        while (queueRecommendationAvoidSongKeys.size > QUEUE_RECOMMENDATION_AVOID_MAX) {
            val iterator = queueRecommendationAvoidSongKeys.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private fun appendedTrackFields(tracks: List<NativeTrack>): Map<String, Any?> {
        if (tracks.isEmpty()) return emptyMap()
        return mapOf(
            "appendIds" to tracks.take(12).joinToString(",") { it.neteaseId?.toString() ?: it.id },
            "appendTitles" to tracks.take(8).joinToString(" | ") { track ->
                listOf(track.title, track.artist)
                    .filter { it.isNotBlank() }
                    .joinToString(" - ")
                    .take(96)
            },
        )
    }

    private fun queueMatchingPlayerTimeline(
        player: Player,
        plannedQueue: List<NativeTrack>,
    ): List<NativeTrack> {
        if (plannedQueue.isEmpty() || player.mediaItemCount <= 0) return plannedQueue
        val timelineIds = (0 until player.mediaItemCount).map { idx ->
            player.getMediaItemAt(idx).mediaId
        }
        if (timelineIds.size == plannedQueue.size &&
            timelineIds.indices.all { idx -> plannedQueue.getOrNull(idx)?.id == timelineIds[idx] }
        ) {
            return plannedQueue
        }
        val byId = plannedQueue.associateBy { it.id }
        val aligned = timelineIds.mapNotNull { id -> byId[id] }
        return if (aligned.size == timelineIds.size) aligned else plannedQueue
    }

    private fun mergeResolvedTracks(queue: List<NativeTrack>, resolved: List<NativeTrack>): List<NativeTrack> {
        if (queue.isEmpty() || resolved.isEmpty()) return queue
        val byId = resolved.associateBy { it.id }
        return queue.map { track -> byId[track.id] ?: track }
    }

    private fun appendResolveChunks(tracks: List<NativeTrack>): List<List<NativeTrack>> {
        if (tracks.isEmpty()) return emptyList()
        val firstSize = minOf(INITIAL_APPEND_RESOLVE_CHUNK_SIZE, tracks.size)
        return buildList {
            add(tracks.take(firstSize))
            tracks.drop(firstSize).chunked(PLAYLIST_APPEND_RESOLVE_CHUNK_SIZE).forEach(::add)
        }
    }

    private fun armQueueExtendBackoff() {
        val nextCount = (queueExtendFailureCount + 1).coerceAtMost(6)
        queueExtendFailureCount = nextCount
        val factor = 1L shl (nextCount - 1)
        val delayMs = minOf(QUEUE_EXTEND_BACKOFF_MAX_MS, QUEUE_EXTEND_BACKOFF_BASE_MS * factor)
        queueExtendBackoffUntilMs = SystemClock.elapsedRealtime() + delayMs
    }

    private fun resetQueueExtendBackoff() {
        queueExtendFailureCount = 0
        queueExtendBackoffUntilMs = 0L
    }

    private fun toMediaItem(track: NativeTrack) = mediaFactory.toMediaItem(track)

    private fun toMediaItem(track: NativeTrack, queueContext: List<NativeTrack>, index: Int): androidx.media3.common.MediaItem {
        val prev = queueContext.getOrNull(index - 1)
        val next = queueContext.getOrNull(index + 1)
        return mediaFactory.toMediaItem(
            track = track,
            preserveHeadBoundary = prev != null && strongAudioContinuity(prev, track),
            preserveTailBoundary = next != null && strongAudioContinuity(track, next),
        )
    }

    private fun toMediaItems(
        tracks: List<NativeTrack>,
        queueContext: List<NativeTrack> = tracks,
    ): List<androidx.media3.common.MediaItem> {
        return tracks.mapIndexed { fallbackIndex, track ->
            val contextIndex = queueContext.indexOfFirst { it.id == track.id }
                .takeIf { it >= 0 }
                ?: fallbackIndex.coerceIn(0, queueContext.lastIndex.coerceAtLeast(0))
            toMediaItem(track, queueContext, contextIndex)
        }
    }

    private fun continuousAudioTail(queue: List<NativeTrack>, startIndex: Int): List<NativeTrack> {
        if (startIndex !in queue.indices) return emptyList()
        val out = ArrayList<NativeTrack>()
        out.add(queue[startIndex])
        var idx = startIndex + 1
        while (idx in queue.indices && strongAudioContinuity(queue[idx - 1], queue[idx])) {
            out.add(queue[idx])
            idx += 1
        }
        return out
    }

    private fun hasContinuousAudioNeighbor(queue: List<NativeTrack>, index: Int): Boolean {
        if (index !in queue.indices) return false
        val prev = queue.getOrNull(index - 1)
        val next = queue.getOrNull(index + 1)
        val cur = queue[index]
        return (prev != null && strongAudioContinuity(prev, cur)) ||
            (next != null && strongAudioContinuity(cur, next))
    }

    private fun strongAudioContinuity(a: NativeTrack, b: NativeTrack): Boolean {
        val af = featuresStore.get(a.id) ?: return false
        val bf = featuresStore.get(b.id) ?: return false
        if (af.durationS < 20.0 || bf.durationS < 20.0) return false

        val bpmReliable = af.bpm != null && bf.bpm != null &&
            af.bpmConfidence > 0.25 && bf.bpmConfidence > 0.25
        val bpmClose = !bpmReliable || kotlin.math.abs(af.bpm!! - bf.bpm!!) <= 6.0
        val energyDelta = kotlin.math.abs(af.outroEnergy - bf.introEnergy)
        val boundaryIsTight = af.tailSilenceS <= 0.45 && bf.headSilenceS <= 0.45
        val fit = TransitionScore.fitScore(
            TransitionScore.Scored(a, af),
            TransitionScore.Scored(b, bf),
        )
        return boundaryIsTight &&
            bpmClose &&
            energyDelta <= 0.18 &&
            fit.style != TransitionScore.TransitionStyle.SilenceBreath &&
            fit.score >= 0.68
    }

    private fun currentTrackFields(): Map<String, Any?> {
        val track = state.queue.getOrNull(state.currentIndex)
        return trackFields(track) + mapOf(
            "queueIndex" to state.currentIndex,
            "queueSize" to state.queue.size,
            "mode" to state.playbackMode.name,
        )
    }

    private fun trackFields(track: NativeTrack?): Map<String, Any?> {
        return mapOf(
            "trackId" to track?.id,
            "neteaseId" to track?.neteaseId,
            "title" to track?.title,
            "artist" to track?.artist,
        )
    }

    private fun mediaTransitionReason(reason: Int): String = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist_changed"
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
        else -> reason.toString()
    }

    private fun playbackModeFromSettings(raw: String): PlaybackQueueMode {
        return when (raw) {
            PlaybackQueueMode.OrderOnce.name -> PlaybackQueueMode.OrderOnce
            PlaybackQueueMode.AiRadio.name -> PlaybackQueueMode.AiRadio
            PlaybackQueueMode.PlaylistLoop.name -> PlaybackQueueMode.PlaylistLoop
            PlaybackQueueMode.SingleLoop.name -> PlaybackQueueMode.SingleLoop
            else -> PlaybackQueueMode.ShufflePlay
        }
    }

    /** 已经分析过的 Symphonia 特征—— 全局 AudioFeaturesStore（跨 session 持久 + 蒸馏复用） */
    private val featuresStore by lazy { PipoGraph.audioFeaturesStore }

    private suspend fun fetchPlayable(id: Long): PlaybackUrlResolver.ResolvedPlaybackUrl? =
        urlResolver.fetchPlayable(id)

    private suspend fun fetchPlayableUrl(id: Long): String? = fetchPlayable(id)?.url

    private fun NativeTrack.withPlayable(resolved: PlaybackUrlResolver.ResolvedPlaybackUrl): NativeTrack =
        copy(streamUrl = resolved.url, streamCacheKey = resolved.cacheKey)

    private suspend fun resolveSinglePlayable(track: NativeTrack): NativeTrack? =
        urlResolver.resolveSinglePlayable(track)

    private suspend fun resolveFirstPlayable(candidates: List<NativeTrack>): NativeTrack? {
        return urlResolver.resolveFirstPlayable(candidates, RECOVERY_SCAN_LIMIT)
    }

    private suspend fun resolvePlayableQueue(queue: List<NativeTrack>): List<NativeTrack> =
        urlResolver.resolvePlayableQueue(queue)

    // 云盘上传 / 没对上库的歌没有 NetEase 封面 URL，但 ExoPlayer 会从 MP3 ID3 / FLAC
    // metadata 把内嵌封面解出来塞进 mediaMetadata.artworkData —— 系统状态栏播放器就靠
    // 这条线显示封面。in-app UI 走 String? URL pipeline，所以把字节流转成 data: URI 喂
    // 给同一个管道。按 trackId 缓存避免每次 syncFrom 重新 base64。
    private var cachedArtworkTrackId: String? = null
    private var cachedArtworkUri: String? = null

    private fun embeddedArtworkDataUri(trackId: String?, data: ByteArray?): String? {
        if (trackId == null || data == null || data.isEmpty()) return null
        if (cachedArtworkTrackId == trackId) {
            cachedArtworkUri?.let { return it }
        }
        val mime = sniffImageMime(data)
        val base64 = Base64.encodeToString(data, Base64.NO_WRAP)
        val uri = "data:$mime;base64,$base64"
        cachedArtworkTrackId = trackId
        cachedArtworkUri = uri
        return uri
    }

    private fun sniffImageMime(data: ByteArray): String {
        // 仅看魔术字节：PNG=89 50 4E 47，JPEG=FF D8 FF，WEBP=RIFF....WEBP
        return when {
            data.size >= 4 &&
                data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
                data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> "image/png"
            data.size >= 3 &&
                data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() -> "image/jpeg"
            data.size >= 12 &&
                data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
                data[2] == 0x46.toByte() && data[3] == 0x46.toByte() &&
                data[8] == 0x57.toByte() && data[9] == 0x45.toByte() &&
                data[10] == 0x42.toByte() && data[11] == 0x50.toByte() -> "image/webp"
            else -> "image/jpeg" // ID3 嵌入封面绝大多数是 JPEG；猜错最坏 Coil 解码失败
        }
    }

    companion object {
        private const val STREAM_URL_TIMEOUT_MS = 15_000L
        private const val MAX_RECOVERY_SKIPS = 2
        private const val PREWARM_AFTER_PROGRESS = 0.55f
        private const val PREWARM_WHEN_REMAINING_MS = 90_000L
        private const val PREWARM_FAILURE_BACKOFF_MS = 20_000L
        private const val AUTO_MIX_FEATURE_LOOKAHEAD = 4
        private const val AUTO_MIX_FEATURE_TIMEOUT_MS = 25_000L
        private const val AUTO_MIX_FEATURE_FAILURE_BACKOFF_MS = 5 * 60_000L
        private const val QUEUE_EXTEND_BACKOFF_BASE_MS = 30_000L
        private const val QUEUE_EXTEND_BACKOFF_MAX_MS = 5 * 60_000L
        private const val QUEUE_EXTEND_SKIP_LOG_THROTTLE_MS = 15_000L
        private const val QUEUE_EXTEND_FETCH_CANDIDATE_COUNT = 24
        private const val QUEUE_EXTEND_APPEND_TARGET = 8
        private const val QUEUE_RECOMMENDATION_AVOID_MAX = 512
        private const val PLAYLIST_APPEND_RESOLVE_CHUNK_SIZE = 40
        private const val INITIAL_APPEND_RESOLVE_CHUNK_SIZE = 8
        private const val AGENT_SMART_ORDER_WINDOW = 10
        private const val STABLE_PLAYBACK_RESET_MS = 12_000L
        private const val END_POSITION_TOLERANCE_MS = 1_500L
        private const val RECOVERY_SCAN_LIMIT = 8
    }
}
