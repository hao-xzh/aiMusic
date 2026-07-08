package app.pipo.nativeapp.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.AiCaptionBus
import app.pipo.nativeapp.data.AiPetCommandBus
import app.pipo.nativeapp.data.BehaviorType
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.LyricTiming
import app.pipo.nativeapp.data.NativeSettings
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoLyricRole
import app.pipo.nativeapp.data.PetPersona
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.agent.domain.ActionExecutionResult
import app.pipo.nativeapp.data.agent.domain.AgentTurnInput
import app.pipo.nativeapp.data.agent.domain.AgentUiCard
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.execute.AgentActionExecutor
import app.pipo.nativeapp.data.agent.memory.AgentLedgerStore
import app.pipo.nativeapp.data.agent.runtime.AgentRuntime
import app.pipo.nativeapp.playback.PlayerUiState
import app.pipo.nativeapp.playback.PlayerViewModel
import app.pipo.nativeapp.playback.orchestrator.AgentQueueRequest
import app.pipo.nativeapp.playback.orchestrator.QueueCommitResult
import app.pipo.nativeapp.playback.orchestrator.QueueOperation
import app.pipo.nativeapp.runtime.AppForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 应用根 —— 镜像 src/app/layout.tsx + page.tsx 的根组合。
 *
 * 关键：immersive 进出动画用 coverProgress (0=compact, 1=immersive) 同时驱动：
 *   - TransitioningCover 形变（compact rect → 顶部全宽方块）
 *   - ImmersiveLyrics 的 backdrop / 标题 / 歌词 fade
 *   - PlayerScreen 的 compact 封面 / nav 图标 hide
 */
@Composable
fun PipoNativeApp(
    lyricSandbox: Boolean = false,
    lyricSandboxPositionMs: Long = 0L,
    lyricSandboxPlaying: Boolean = true,
    lyricSandboxProbe: Boolean = false,
) {
    val pipoColors = darkColorScheme(
        background = PipoColors.Bg0,
        surface = PipoColors.Bg1,
        primary = PipoColors.Accent,
        secondary = PipoColors.Blue,
        tertiary = PipoColors.Gold,
        onBackground = PipoColors.Ink,
        onSurface = PipoColors.Ink,
        onPrimary = Color(0xFF062014),
    )
    MaterialTheme(colorScheme = pipoColors) {
        if (lyricSandbox) {
            LyricSandboxScreen(
                initialPositionMs = lyricSandboxPositionMs,
                isPlaying = lyricSandboxPlaying,
                probeEnabled = lyricSandboxProbe,
            )
            return@MaterialTheme
        }
        var route by remember { mutableStateOf<Route>(Route.Player) }
        var immersive by remember { mutableStateOf(false) }
        val viewModel: PlayerViewModel = viewModel()
        val playerState = viewModel.state
        val settings by PipoGraph.repository.settings.collectAsState(initial = NativeSettings())
        val scope = rememberCoroutineScope()
        val showLyricTranslation = settings.lyricTranslation
        val toggleLyricTranslation: () -> Unit = {
            val enabled = !settings.lyricTranslation
            DiagnosticsLogStore.record(
                area = "lyrics",
                event = "translation_toggle",
                fields = mapOf("enabled" to enabled),
            )
            scope.launch {
                PipoGraph.repository.updateSettings(settings.copy(lyricTranslation = enabled))
            }
        }
        val coverAnchor = rememberCoverAnchorState()
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            configuration.screenWidthDp > configuration.screenHeightDp
        val hasLyricTranslation by remember(playerState.lyrics) {
            derivedStateOf {
                playerState.lyrics.any { line ->
                    line.companionLines.any {
                        it.role == PipoLyricRole.Translation || it.role == PipoLyricRole.Romaji
                    }
                }
            }
        }

        // 封面 FLIP 形变：spring 物理，~360ms 软着陆 + 半路反向时无跳变。
        // 入场 ζ≈0.86 / k=380 是"沉一点、稳一点"的 iOS 手感；
        // 出场 ζ≈0.95 / k=520 更快收，避免回到 compact 时还有人能感受到的滞留。
        val coverProgress by animateFloatAsState(
            targetValue = if (immersive) 1f else 0f,
            animationSpec = if (immersive) {
                spring(
                    dampingRatio = 0.86f,
                    stiffness = 380f,
                    visibilityThreshold = 0.001f,
                )
            } else {
                spring(
                    dampingRatio = 0.95f,
                    stiffness = 520f,
                    visibilityThreshold = 0.001f,
                )
            },
            label = "coverProgress",
        )

        // 内容时间线（标题 / 控件 / 歌词列）—— 与封面解耦：
        //   入场：延后 120ms 让封面先飞，再 340ms ease-out 软入
        //   出场：无延迟，180ms ease-in，"内容先消失、封面再收回"
        // 这样封面在飞时其它东西不会跟着大幅平移 / 透明度阶跃，"丝滑感"的关键。
        val contentProgressAnim = remember { Animatable(0f) }
        LaunchedEffect(immersive) {
            if (immersive) {
                delay(90)
                contentProgressAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 620,
                        easing = CubicBezierEasing(0.2f, 0.9f, 0.15f, 1f),
                    ),
                )
            } else {
                contentProgressAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = 220,
                        easing = CubicBezierEasing(0.4f, 0f, 1f, 1f),
                    ),
                )
            }
        }
        val contentProgress = contentProgressAnim.value

        LaunchedEffect(immersive, route, isLandscape) {
            DiagnosticsLogStore.record(
                area = "ui",
                event = "surface_state",
                fields = mapOf(
                    "route" to route.name,
                    "immersive" to immersive,
                    "landscape" to isLandscape,
                    "screenWidthDp" to configuration.screenWidthDp,
                    "screenHeightDp" to configuration.screenHeightDp,
                ),
            )
            if ((immersive && !isLandscape) || route != Route.Player) coverAnchor.releaseCoverRect()
            if (immersive && route == Route.Player && !isLandscape) {
                val lyricClock = LyricTiming.resolve(
                    positionMs = viewModel.positionMs,
                    lines = playerState.lyrics,
                )
                val activeLine = playerState.lyrics.getOrNull(lyricClock.activeIndex)
                DiagnosticsLogStore.record(
                    area = "lyrics",
                    event = "surface_open",
                    fields = mapOf(
                        "positionMs" to lyricClock.positionMs,
                        "activeIndex" to lyricClock.activeIndex,
                        "lineStartMs" to activeLine?.let { LyricTiming.audioStartMs(it) },
                        "lineElapsedMs" to activeLine?.let { lyricClock.positionMs - LyricTiming.audioStartMs(it) },
                        "lineCount" to playerState.lyrics.size,
                        "wordLineCount" to playerState.lyrics.count { it.chars.isNotEmpty() },
                    ),
                )
            }
        }

        // 播放页 / 歌词页保持屏幕常亮；用户手动电源键锁屏仍由系统处理。
        // DisposableEffect 的 onDispose 在整个 composable 被销毁时也会清理，防止 leak。
        val view = LocalView.current
        val keepScreenOn = route == Route.Player
        DisposableEffect(keepScreenOn) {
            val window = (view.context as? Activity)?.window
            if (keepScreenOn) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        // 横屏 Player 全程隐藏；竖屏只有沉浸式歌词页隐藏（让 cover 真正贴到屏幕顶）。
        val hideSystemBars = route == Route.Player && (isLandscape || immersive)
        DisposableEffect(hideSystemBars) {
            val window = (view.context as? Activity)?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            if (hideSystemBars) {
                controller?.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller?.show(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                controller?.show(WindowInsetsCompat.Type.systemBars())
            }
        }

        SkipCorrectionEffect(
            settings = settings,
            route = route,
            immersive = immersive,
            isLandscape = isLandscape,
            playerState = playerState,
            onApplyAgentQueueRequest = { request -> viewModel.applyAgentQueueRequest(request) },
        )

        val aiOverlayOpen by AiPetCommandBus.isOpen.collectAsState()
        val aiPlayerBlur by animateDpAsState(
            targetValue = if (aiOverlayOpen) 6.dp else 0.dp,
            animationSpec = tween(320, easing = PipoMotion.FlipEase),
            label = "aiPlayerBlur",
        )

        CompositionLocalProvider(LocalCoverAnchor provides coverAnchor) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 主页 + 独立封面 FLIP 层一起作为 AI 背景。
                // TransitioningCover 不在 PlayerScreen 里面；所以 blur 必须包住这整个播放组，
                // 否则 compact cover 会清晰地浮在 AI 覆盖层上方。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(aiPlayerBlur),
                ) {
                    PlayerScreen(
                        onOpenLyrics = {
                            viewModel.refreshPosition()
                            immersive = true
                        },
                        onOpenDistill = { route = Route.Distill },
                        onOpenSettings = { route = Route.Settings },
                        immersiveProgress = contentProgress,
                        showTranslation = showLyricTranslation && hasLyricTranslation,
                        hasTranslation = hasLyricTranslation,
                        onToggleTranslation = toggleLyricTranslation,
                        viewModel = viewModel,
                    )
                    AnimatedVisibility(
                        visible = !isLandscape,
                        enter = fadeIn(tween(240, easing = PipoMotion.FlipEase)),
                        exit = fadeOut(tween(180, easing = PipoMotion.CloseEase)),
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // 播放页本身已经绘制 Apple Music 式清晰封面 + 同源毛玻璃背景。
                            // 歌词页共享这层底图，只让播放控件淡出、歌词列表淡入，避免两套封面层叠出分界。
                            val lyricPositionProvider = remember(viewModel) { { viewModel.positionMs } }
                            ImmersiveLyricsOverlay(
                                progress = coverProgress,
                                contentProgress = contentProgress,
                                coverUrl = viewModel.state.artworkUrl,
                                title = viewModel.state.title,
                                artist = viewModel.state.artist,
                                trackId = viewModel.state.currentTrackId,
                                lyrics = viewModel.state.lyrics,
                                positionProvider = lyricPositionProvider,
                                isPlaying = viewModel.state.isPlaying,
                                showTranslation = showLyricTranslation && hasLyricTranslation,
                                hasTranslation = hasLyricTranslation,
                                onClose = { immersive = false },
                                onToggle = viewModel::toggle,
                                onNext = viewModel::next,
                                onToggleTranslation = toggleLyricTranslation,
                                onSeekToMs = { targetMs ->
                                    viewModel.seekToMs(targetMs)
                                },
                            )
                        }
                    }
                }

                // 子页面 push 动画（distill / settings / taste / login）
                AnimatedVisibility(
                    visible = route != Route.Player,
                    enter = slideInVertically(tween(240)) { it } + fadeIn(tween(240)),
                    exit = slideOutVertically(tween(240)) { it } + fadeOut(tween(240)),
                ) {
                    CompositionLocalProvider(
                        LocalOnBack provides { route = Route.Player },
                        LocalNav provides PipoNav(
                            openTaste = { route = Route.Taste },
                            openSettings = { route = Route.Settings },
                            openDistill = { route = Route.Distill },
                            openLogin = { route = Route.Login },
                        ),
                    ) {
                        when (route) {
                            Route.Distill -> DistillScreen()
                            Route.Settings -> SettingsScreen()
                            Route.Taste -> TasteScreen(
                                onPlayTracks = { tracks ->
                                    if (tracks.isNotEmpty()) {
                                        route = Route.Player
                                        scope.launch {
                                            viewModel.applyAgentQueueRequest(
                                                AgentQueueRequest(
                                                    requestId = "taste_${System.currentTimeMillis()}",
                                                    sourceUserText = "taste_screen_play",
                                                    operation = QueueOperation.ReplaceQueue,
                                                    tracks = tracks,
                                                    desiredCount = tracks.size,
                                                ),
                                            )
                                        }
                                    }
                                },
                            )
                            Route.Login -> LoginScreen(onBack = { route = Route.Player })
                            Route.Player -> Unit
                        }
                    }
                }

                // 后台蒸馏的浮条 —— 跨所有 route 都可见，不阻碍交互
                DistillStatusChip()

                // 全局 AiPet（仅在 Player root + 非沉浸式 + 竖屏时显示）
                if (route == Route.Player && !immersive && !isLandscape) {
                    val currentTrack = playerState.queue.getOrNull(playerState.currentIndex)
                    NativeAiPet(
                        isPlaying = playerState.isPlaying,
                        currentTrack = currentTrack,
                        currentQueue = playerState.queue,
                        currentTrackKey = currentTrack?.id,
                        currentTitle = playerState.title,
                        currentArtist = playerState.artist,
                        coverUrl = playerState.artworkUrl,
                        onApplyAgentQueueRequest = { request -> viewModel.applyAgentQueueRequest(request) },
                        onSkipFromAgent = { viewModel.next() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (route == Route.Player && !immersive && !isLandscape && settings.hideAiPetOrb) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(104.dp)
                            .navigationBarsPadding()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        AiPetCommandBus.openChat()
                                        DiagnosticsLogStore.record(
                                            area = "ai_pet",
                                            event = "open_from_bottom_double_tap",
                                        )
                                    },
                                )
                            },
                    )
                }
            }

            BackHandler(enabled = immersive || route != Route.Player) {
                when {
                    immersive -> immersive = false
                    else -> route = Route.Player
                }
            }
        }
    }
}

private enum class Route { Player, Distill, Settings, Taste, Login }

/**
 * Skip-Correction —— 用户连跳 3 首 / 15 分钟内 → 主动换队列。
 *
 * 跟被删的 4 个"时段自动播放"完全不同：这是**响应用户行为**（连跳是明确的不爱听信号），
 * 不是 AI 凭时钟决定"现在该放什么"。所以保留 + 增强，不挂任何 settings 开关。
 *
 * - 默认开启（AI 的本职工作）
 * - 30 分钟冷却防循环纠偏
 * - 增强：检测"同艺人连跳" → Planner 上下文里点名让新队列避开
 */
@Composable
private fun SkipCorrectionEffect(
    settings: NativeSettings,
    route: Route,
    immersive: Boolean,
    isLandscape: Boolean,
    playerState: PlayerUiState,
    onApplyAgentQueueRequest: suspend (AgentQueueRequest) -> QueueCommitResult,
) {
    val context = LocalContext.current
    val runtime = remember(context) {
        AgentRuntime(
            repository = PipoGraph.repository,
            ledger = AgentLedgerStore(context),
        )
    }
    val appInForeground by AppForeground.isForeground.collectAsState()
    val queueSignature = remember(playerState.queue) { skipCorrectionQueueSignature(playerState.queue) }
    var lastTriggerTs by remember { mutableStateOf(0L) }
    var queueStartedAtMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val latestSettings by rememberUpdatedState(settings)
    val latestPlayerState by rememberUpdatedState(playerState)
    val latestAppInForeground by rememberUpdatedState(appInForeground)
    val latestQueueStartedAtMs by rememberUpdatedState(queueStartedAtMs)

    LaunchedEffect(queueSignature) {
        queueStartedAtMs = System.currentTimeMillis()
    }

    LaunchedEffect(route, immersive, isLandscape) {
        while (route == Route.Player && !immersive && !isLandscape) {
            delay(20_000)
            val activeQueueStartedAtMs = latestQueueStartedAtMs
            val events = runCatching { PipoGraph.behaviorLog.readAll() }.getOrDefault(emptyList())
            val skipped = events
                .filter { it.type == BehaviorType.Skipped && it.tsMs >= activeQueueStartedAtMs }
                .sortedBy { it.tsMs }
                .takeLast(3)
            if (skipped.size < 3) continue
            val newest = skipped.last()
            val oldest = skipped.first()
            // 15 分钟窗口内连跳 3 首
            if (newest.tsMs - oldest.tsMs > SKIP_CORRECTION_WINDOW_MS) continue
            if (!latestAppInForeground) {
                DiagnosticsLogStore.record(
                    area = "skip_correction",
                    event = "suppressed",
                    fields = mapOf(
                        "reason" to "background",
                        "skipCount" to skipped.size,
                    ),
                )
                continue
            }
            if (System.currentTimeMillis() - activeQueueStartedAtMs < SKIP_CORRECTION_FRESH_QUEUE_GRACE_MS) {
                DiagnosticsLogStore.record(
                    area = "skip_correction",
                    event = "suppressed",
                    fields = mapOf(
                        "reason" to "fresh_queue",
                        "skipCount" to skipped.size,
                    ),
                )
                continue
            }
            // 30 分钟冷却防循环
            if (newest.tsMs - lastTriggerTs < SKIP_CORRECTION_COOLDOWN_MS) continue

            lastTriggerTs = newest.tsMs

            // 增强：识别"同艺人连跳" → Planner 上下文里点名让新队列避开
            val skipArtists = skipped.mapNotNull { it.artist.takeIf { a -> a.isNotBlank() } }
            val artistRepeated = skipArtists.size >= 2 && skipArtists.toSet().size == 1
            val hintLine = if (artistRepeated) {
                "我刚连跳了 3 首 ${skipArtists.first()} 的歌。换个艺人，能量再降一点。"
            } else {
                "我刚连跳了 3 首。这队列不对劲，换一段更稳更不闹的，能量降一点、风格换一下。"
            }

            DiagnosticsLogStore.record(
                area = "skip_correction",
                event = "trigger",
                fields = mapOf(
                    "skipCount" to skipped.size,
                    "windowMs" to (newest.tsMs - oldest.tsMs),
                    "artistRepeated" to artistRepeated,
                ),
            )

            val outcome = try {
                val executor = object : AgentActionExecutor {
                    override suspend fun playQueue(
                        actionId: String,
                        mode: PlayMode,
                        tracks: List<NativeTrack>,
                        continuous: ContinuousQueueSource?,
                        primaryGoal: MusicGoal,
                        target: TrackRequirement?,
                        similar: Boolean,
                    ): ActionExecutionResult {
                        if (tracks.isEmpty()) {
                            return ActionExecutionResult(actionId, "play_queue", success = false, message = "这次没排出能播的歌。")
                        }
                        val request = AgentQueueRequest(
                            requestId = actionId,
                            sourceUserText = hintLine,
                            operation = when {
                                similar -> QueueOperation.PlaySimilar
                                mode == PlayMode.PlayNow -> QueueOperation.PlayNow
                                else -> QueueOperation.ReplaceQueue
                            },
                            tracks = tracks,
                            continuous = continuous,
                            desiredCount = tracks.size,
                        )
                        return when (val commit = onApplyAgentQueueRequest(request)) {
                            is QueueCommitResult.Success -> ActionExecutionResult(
                                actionId = actionId,
                                type = "play_queue",
                                success = true,
                                message = "纠偏队列已接收",
                                tracks = commit.plan.tracks,
                                queueSnapshot = commit.plan.tracks,
                                similar = similar,
                                committedQueueSummary = commit.plan.toSummary(accepted = true),
                            )
                            is QueueCommitResult.Rejected -> ActionExecutionResult(
                                actionId = actionId,
                                type = "play_queue",
                                success = false,
                                message = commit.messages.joinToString("、").ifBlank { "纠偏队列没有被播放器接收" },
                                errorMessage = commit.reason,
                            )
                        }
                    }

                    override suspend fun insertNext(
                        actionId: String,
                        tracks: List<NativeTrack>,
                        jumpToInserted: Boolean,
                    ): ActionExecutionResult {
                        if (tracks.isEmpty()) {
                            return ActionExecutionResult(actionId, "insert_next", success = false, message = "这次没排出能插播的歌。")
                        }
                        val request = AgentQueueRequest(
                            requestId = actionId,
                            sourceUserText = hintLine,
                            operation = QueueOperation.InsertNext,
                            tracks = tracks,
                            jumpToInserted = jumpToInserted,
                            desiredCount = tracks.size,
                        )
                        return when (val commit = onApplyAgentQueueRequest(request)) {
                            is QueueCommitResult.Success -> ActionExecutionResult(
                                actionId = actionId,
                                type = "insert_next",
                                success = true,
                                message = if (tracks.size == 1) "纠偏单曲已接收" else "纠偏插播队列已接收",
                                tracks = commit.plan.tracks,
                                queueSnapshot = commit.plan.tracks,
                                insert = true,
                                committedQueueSummary = commit.plan.toSummary(accepted = true),
                            )
                            is QueueCommitResult.Rejected -> ActionExecutionResult(
                                actionId = actionId,
                                type = "insert_next",
                                success = false,
                                message = commit.messages.joinToString("、").ifBlank { "纠偏插播没有被播放器接收" },
                                errorMessage = commit.reason,
                            )
                        }
                    }

                    override suspend fun skip(actionId: String): ActionExecutionResult =
                        ActionExecutionResult(actionId, "skip", success = false, message = "后台纠偏不执行跳过。")

                    override suspend fun likeCurrent(actionId: String, like: Boolean): ActionExecutionResult =
                        ActionExecutionResult(actionId, "like", success = false, message = "后台纠偏不执行收藏。")

                    override suspend fun likeTrack(
                        actionId: String,
                        like: Boolean,
                        target: TrackRequirement,
                    ): ActionExecutionResult =
                        ActionExecutionResult(actionId, "like", success = false, message = "后台纠偏不执行收藏。")

                    override suspend fun modifyPlaylist(
                        actionId: String,
                        add: Boolean,
                        playlistName: String,
                    ): ActionExecutionResult =
                        ActionExecutionResult(actionId, "playlist", success = false, message = "后台纠偏不操作歌单。")
                }
                runtime.handle(
                    input = AgentTurnInput(
                        userText = hintLine,
                        history = emptyList(),
                        historySummary = "",
                        musicReferences = emptyList(),
                        currentTrack = null,
                        currentQueue = latestPlayerState.queue,
                        userFacts = latestSettings.userFacts,
                        persona = PetPersona.fromId(latestSettings.personaId),
                    ),
                    executor = executor,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticsLogStore.record(
                    area = "skip_correction",
                    event = "failed",
                    fields = mapOf(
                        "errorType" to e::class.java.simpleName,
                        "errorMessage" to e.message.orEmpty().take(180),
                    ),
                )
                null
            } ?: continue

            val applied = outcome.cards.firstOrNull { it.kind == AgentUiCard.Kind.Play && it.ok }
            if (applied != null) {
                DiagnosticsLogStore.record(
                    area = "skip_correction",
                    event = "applied",
                    fields = mapOf("trackCount" to applied.count),
                )
                AiCaptionBus.show(outcome.reply.ifBlank { "这队不对，我换一组。" })
            }
        }
    }
}

private fun skipCorrectionQueueSignature(queue: List<NativeTrack>): String =
    queue.joinToString(separator = "|") { track ->
        track.neteaseId?.toString() ?: track.id
    }

private const val SKIP_CORRECTION_WINDOW_MS = 15L * 60 * 1000
private const val SKIP_CORRECTION_COOLDOWN_MS = 30L * 60 * 1000
private const val SKIP_CORRECTION_FRESH_QUEUE_GRACE_MS = 30_000L
