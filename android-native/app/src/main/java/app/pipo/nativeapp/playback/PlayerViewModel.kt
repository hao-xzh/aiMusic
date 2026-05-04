package app.pipo.nativeapp.playback

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.pipo.nativeapp.data.BehaviorEvent
import app.pipo.nativeapp.data.BehaviorType
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.Discovery
import app.pipo.nativeapp.data.LastPlaybackStore
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoLyricLine
import app.pipo.nativeapp.data.TrackDedupe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.min

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
    private val discovery = Discovery(repository)
    /** 默认续杯源 —— Discovery 兜底（同 artist + 当前 taste tags） */
    private val defaultContinuousSource = ContinuousQueueSource { excludeIds ->
        val current = state.queue.getOrNull(state.currentIndex)
        val profile = PipoGraph.tasteProfileStore.flow.value
        val tags = if (profile != null) {
            buildList {
                profile.topArtists.take(3).forEach { add(it.name) }
                profile.genres.take(3).forEach { add(it.tag) }
            }
        } else emptyList()
        val raw = discovery.fetchMore(around = current, tags = tags, excludeIds = excludeIds, wantCount = 16)
        // 跨版本去重 + 排除当前队列里已存在的"同一首歌"。
        // 之前只按 neteaseId 去重，所以"浮夸 (Live)" / "浮夸 (Karaoke)" 都能进来。
        // 现在用 TrackDedupe.songKey（normalize 标题 + 第 1 个 artist，去掉
        // Live/Remix/伴奏/Cover 这些版本词），同一首只能留 1 个。
        val existingSongKeys = state.queue.mapTo(HashSet()) { TrackDedupe.songKey(it) }
        val seen = HashSet<String>()
        raw.filter { t ->
            val k = TrackDedupe.songKey(t)
            if (k in existingSongKeys) return@filter false
            seen.add(k)
        }.take(8)
    }
    private var continuousSource: ContinuousQueueSource? = defaultContinuousSource
    /** 续杯调用是否在飞行中 —— 防短时间内重复触发 */
    private var fetchingMore = false
    /** 续杯耗尽后置 true → 队尾不再 wraparound，让播放自然结束 */
    private var noLoop = false
    /** current 后剩 < N 首时触发续杯。3 = 至少留 2 首缓冲 */
    private val extendThreshold = 3

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
                positionMs = snap.positionMs,
                durationMs = cur?.durationMs ?: 0L,
                isReady = false,
            )
        } ?: PlayerUiState(),
    )
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFrom(player)
        }

        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int,
        ) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                logEventForPrev(BehaviorType.Completed, completionPctOverride = 1f)
                logEventForCurrent(BehaviorType.PlayStarted)
            } else {
                // 用户主动切：next/previous 里已经 log 过 Skipped/ManualCut
                logEventForCurrent(BehaviorType.PlayStarted)
            }
        }
    }

    private val behaviorLog by lazy { PipoGraph.behaviorLog }

    /** 计算当前曲目的完成进度（0..1），用于 BehaviorEvent.completionPct */
    private fun currentCompletionPct(): Float {
        val player = controller ?: return 0f
        val dur = player.duration.takeIf { it > 0 } ?: return 0f
        return (player.currentPosition.toFloat() / dur).coerceIn(0f, 1f)
    }

    private fun logEventForCurrent(type: BehaviorType, completionPctOverride: Float? = null) {
        val player = controller ?: return
        val track = state.queue.getOrNull(player.currentMediaItemIndex) ?: return
        viewModelScope.launch {
            behaviorLog.log(
                BehaviorEvent(
                    type = type,
                    trackId = track.id,
                    neteaseId = track.neteaseId,
                    title = track.title,
                    artist = track.artist,
                    tsMs = System.currentTimeMillis(),
                    completionPct = completionPctOverride ?: currentCompletionPct(),
                )
            )
        }
    }

    private fun logEventForPrev(type: BehaviorType, completionPctOverride: Float? = null) {
        val player = controller ?: return
        val prev = state.queue.getOrNull(player.currentMediaItemIndex - 1) ?: return
        viewModelScope.launch {
            behaviorLog.log(
                BehaviorEvent(
                    type = type,
                    trackId = prev.id,
                    neteaseId = prev.neteaseId,
                    title = prev.title,
                    artist = prev.artist,
                    tsMs = System.currentTimeMillis(),
                    completionPct = completionPctOverride ?: 1f,
                )
            )
        }
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
                                    // 冷启动：复位上次 session 留下的"队列跑空"标记 + 默认循环
                                    noLoop = false
                                    state = state.copy(queue = resolved, currentIndex = targetIdx)
                                    player.setMediaItems(
                                        resolved.map(::toMediaItem),
                                        targetIdx,
                                        snap.positionMs.coerceAtLeast(0L),
                                    )
                                    player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                                    player.prepare()
                                    // 不要 play()——等用户点
                                    syncFrom(player)
                                    startFeaturePrefetch()
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
                                    noLoop = false
                                    state = state.copy(queue = tracks)
                                    player.setMediaItems(tracks.map(::toMediaItem))
                                    player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                                    player.prepare()
                                    syncFrom(player)
                                    startFeaturePrefetch()
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
        if (player.isPlaying) player.pause() else player.play()
        syncFrom(player)
    }

    /**
     * 装入 AI 选好的播放队列。镜像 src/lib/player-state.tsx playNetease(t, queue, { continuous }) 的语义。
     *   - 整 batch 装为新队列，从第 0 首播
     *   - 把 AI 给的续杯 source 装上，队尾接近时自动从 reservoir / refill 取下一批
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun playFromAgent(initialBatch: List<NativeTrack>, source: ContinuousQueueSource?) {
        if (initialBatch.isEmpty()) return
        val player = controller ?: return
        viewModelScope.launch {
            // -------- 阶段 1：解析第 1 首立刻播 --------
            val first = initialBatch.first()
            val firstResolved: NativeTrack = if (first.streamUrl.isNotBlank()) {
                first
            } else {
                val id = first.neteaseId ?: return@launch
                val url = runCatching { repository.songUrls(listOf(id)) }
                    .getOrNull()?.firstOrNull { it.id == id }?.url
                    ?.takeIf { it.isNotBlank() }
                    ?: return@launch
                first.copy(streamUrl = url)
            }
            noLoop = false
            continuousSource = source
            app.pipo.nativeapp.ui.PetBubbleStateAccessor.resetForNewQueue()
            state = state.copy(queue = listOf(firstResolved), currentIndex = 0)
            player.setMediaItems(listOf(toMediaItem(firstResolved)), 0, 0L)
            player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            player.volume = 1f
            player.prepare()
            player.play()
            startFeaturePrefetch()

            // -------- 阶段 2：后台补 batch 剩下歌 --------
            val rest = initialBatch.drop(1)
            if (rest.isEmpty()) return@launch
            val resolvedRest = resolvePlayableQueue(rest).filter { it.streamUrl.isNotBlank() }
            if (resolvedRest.isEmpty()) return@launch
            player.addMediaItems(resolvedRest.map(::toMediaItem))
            state = state.copy(queue = listOf(firstResolved) + resolvedRest, currentIndex = 0)
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
        viewModelScope.launch {
            // -------- 阶段 1：立刻开播"那一首" --------
            // 优先用已有 streamUrl；没有就只为这 1 个 id 发 1 次 songUrls 请求
            val pickedResolved: NativeTrack = if (track.streamUrl.isNotBlank()) {
                track
            } else {
                val id = track.neteaseId ?: return@launch
                val url = runCatching { repository.songUrls(listOf(id)) }
                    .getOrNull()?.firstOrNull { it.id == id }?.url
                    ?.takeIf { it.isNotBlank() }
                    ?: return@launch
                track.copy(streamUrl = url)
            }
            noLoop = false
            // 歌单点歌 = 用户明确选了某张歌单，要的是"这张歌单"，不是"这首歌 + Discovery 续杯"。
            // 之前默认挂 defaultContinuousSource，加上现在 phase-1 队列只有 1 首，
            // maybeExtendQueue 立刻命中（remaining=0 ≤ 阈值 3），Discovery 用
            // around=picked 去搜，常常拿回 "同一首的 Live/Karaoke/Acoustic 多版本"
            // 灌进队尾 → 用户感觉"一直放这个歌的其他版本"。
            // 改：歌单 tap 不挂 continuousSource，让 REPEAT_MODE_ALL 在歌单尾部循环。
            continuousSource = null
            app.pipo.nativeapp.ui.PetBubbleStateAccessor.resetForNewQueue()
            // 状态先置 "1 首歌的队列" —— UI 立刻有正确状态可显示
            state = state.copy(queue = listOf(pickedResolved), currentIndex = 0)
            player.setMediaItems(listOf(toMediaItem(pickedResolved)), 0, 0L)
            player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            player.volume = 1f
            player.prepare()
            player.play()
            startFeaturePrefetch()

            // -------- 阶段 2：后台补完整队列 --------
            // 解析剩下歌的 URL（分批 ≤ 50 个），smooth 排序，append 到播放器
            // 这一段几秒钟内完成，期间用户已经在听；REPEAT_MODE_ALL 会自然衔接
            val rest = playable.filter { it.id != track.id }
            if (rest.isEmpty()) return@launch
            val resolvedRest = resolvePlayableQueue(rest).filter { it.streamUrl.isNotBlank() }
            if (resolvedRest.isEmpty()) return@launch
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
            if (tail.isNotEmpty()) {
                player.addMediaItems(tail.map(::toMediaItem))
            }
            state = state.copy(queue = listOf(pickedResolved) + tail, currentIndex = 0)
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
            p.seekToNextMediaItem()
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun previous() {
        controller?.let { p ->
            logEventForCurrent(BehaviorType.ManualCut, completionPctOverride = currentCompletionPct())
            p.seekToPreviousMediaItem()
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
            viewModelScope.launch {
                val lines = runCatching {
                    repository.lyricsForTrack(targetTrackId)
                }.getOrDefault(emptyList())
                // 切歌竞态保护：拉取期间可能已切下一首 → loadedLyricsFor 被覆盖，
                // 旧 lines 不能再写入 state，否则会用 A 的歌词盖掉 B 的歌词
                if (loadedLyricsFor == targetTrackId) {
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
        // 节流持久化：杀掉冷启动黑屏 + 跳到 playlist[0] 的尴尬
        if (queue.isNotEmpty()) {
            lastPlaybackStore.saveThrottled(queue, index, state.positionMs)
        }
    }

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
                    noLoop = true
                    player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
                    return@launch
                }
                val resolved = resolvePlayableQueue(more).filter { it.streamUrl.isNotBlank() }
                if (resolved.isEmpty()) return@launch
                state = state.copy(queue = queue + resolved)
                player.addMediaItems(resolved.map(::toMediaItem))
                startFeaturePrefetch()
            } finally {
                fetchingMore = false
            }
        }
    }

    private fun toMediaItem(track: NativeTrack): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.artworkUrl?.let(Uri::parse))
            .build()

        // gapless 裁切 —— 只在 Symphonia 真检测到头尾静音时才裁。
        //
        // ⚠️ 之前版本在没静音时也加 30/80~250ms padding，结果在 mp3/aac HTTP 流上
        //   ClippingMediaSource 不稳，第一首播完不自动接下一首。回退到只裁实测静音。
        val builder = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.streamUrl)
            .setMediaMetadata(metadata)
        val features = audioFeaturesFor(track.id)
        if (features != null) {
            val headMs = (features.headSilenceS * 1000).toLong().coerceAtLeast(0L)
            val tailMs = (features.tailSilenceS * 1000).toLong().coerceAtLeast(0L)
            val durMs = (features.durationS * 1000).toLong()
            // 静音比例上限：Symphonia 偶尔会把"很安静的间奏 / 长 outro"误判成静音，
            // 导致 tailSilenceS = 30+ 秒，clipping 把曲子末段砍掉。这里加保险：
            //   - durMs 至少 30 秒（避免错位极短样片）
            //   - head ≤ 10% 时长且 ≤ 5 秒
            //   - tail ≤ 15% 时长且 ≤ 8 秒
            val headSafe = headMs in 1L..min(5000L, (durMs * 0.10).toLong())
            val tailSafe = tailMs in 1L..min(8000L, (durMs * 0.15).toLong())
            val canTrim = durMs >= 30_000L && (headSafe || tailSafe) &&
                (durMs - (if (headSafe) headMs else 0L) - (if (tailSafe) tailMs else 0L)) >= 20_000L
            if (canTrim) {
                builder.setClippingConfiguration(
                    androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(if (headSafe) headMs else 0L)
                        .setEndPositionMs(
                            if (tailSafe) durMs - tailMs
                            else androidx.media3.common.C.TIME_END_OF_SOURCE
                        )
                        .build(),
                )
            }
        }
        return builder.build()
    }

    /** 已经分析过的 Symphonia 特征—— 全局 AudioFeaturesStore（跨 session 持久 + 蒸馏复用） */
    private val featuresStore by lazy { PipoGraph.audioFeaturesStore }
    private fun audioFeaturesFor(trackId: String): app.pipo.nativeapp.data.AudioFeatures? =
        featuresStore.get(trackId)

    /**
     * 语义档案 + embedding 索引以前在每曲首播时叫一次 LLM —— 一首歌额外两次 AI 请求，
     * 用户反馈"太频繁"。改成只在 Distill 批量跑（蒸馏是显式 opt-in 的"昂贵分析"事件）。
     * 这里留空保持 syncFrom 调用点干净；想恢复就把这两行加回来。
     */
    private fun maybeTriggerSemantic(@Suppress("UNUSED_PARAMETER") track: app.pipo.nativeapp.data.NativeTrack?) {
        // 故意空：单曲索引集中到 Distill batch，每首歌减少 2 次 AI 调用
    }

    /**
     * 后台逐曲拉取 Symphonia features —— 拿到 head/tail 静音后写入 AudioFeaturesStore，
     * 同时用 ExoPlayer `replaceMediaItem` 替换原 MediaItem 为带 clipping 的版本。
     */
    private fun startFeaturePrefetch() {
        val player = controller ?: return
        viewModelScope.launch {
            state.queue.forEachIndexed { idx, track ->
                val ne = track.neteaseId ?: return@forEachIndexed
                if (featuresStore.get(track.id) != null) return@forEachIndexed
                if (track.streamUrl.isBlank()) return@forEachIndexed
                val features = runCatching { repository.audioFeatures(ne, track.streamUrl) }.getOrNull()
                    ?: return@forEachIndexed
                featuresStore.put(track.id, features)
                if (idx == player.currentMediaItemIndex) return@forEachIndexed
                if (idx >= player.mediaItemCount) return@forEachIndexed
                runCatching { player.replaceMediaItem(idx, toMediaItem(track)) }
            }
        }
    }

    private suspend fun resolvePlayableQueue(queue: List<NativeTrack>): List<NativeTrack> {
        val missingIds = queue.mapNotNull { track ->
            if (track.streamUrl.isBlank()) track.neteaseId else null
        }
        if (missingIds.isEmpty()) return queue
        // 分批 ≤ 50 个 id —— netease 单次大批 (200+) 偶尔会丢一半响应，
        // 表现为"队列后半段没 url，播完第一首就停"。50 是经验稳定值。
        val urls = HashMap<Long, String>()
        for (chunk in missingIds.chunked(50)) {
            runCatching { repository.songUrls(chunk) }.getOrNull()?.forEach { u ->
                u.url?.takeIf { it.isNotBlank() }?.let { urls[u.id] = it }
            }
        }
        return queue.map { track ->
            val id = track.neteaseId
            val resolved = if (id != null) urls[id] else null
            if (track.streamUrl.isBlank() && !resolved.isNullOrBlank()) {
                track.copy(streamUrl = resolved)
            } else {
                track
            }
        }
    }
}
