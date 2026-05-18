package app.pipo.nativeapp.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.AppContext
import app.pipo.nativeapp.data.AiPetCommandBus
import app.pipo.nativeapp.data.BehaviorEvent
import app.pipo.nativeapp.data.BehaviorSummary
import app.pipo.nativeapp.data.BehaviorType
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.LyricTiming
import app.pipo.nativeapp.data.NativeSettings
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoLyricRole
import app.pipo.nativeapp.data.PetAgent
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.Weather
import app.pipo.nativeapp.playback.PlayerUiState
import app.pipo.nativeapp.playback.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 应用根 —— 镜像 src/app/layout.tsx + page.tsx 的根组合。
 *
 * 关键：immersive 进出动画用 coverProgress (0=compact, 1=immersive) 同时驱动：
 *   - TransitioningCover 形变（compact rect → 顶部全宽方块）
 *   - ImmersiveLyrics 的 backdrop / 标题 / 歌词 fade
 *   - PlayerScreen 的 compact 封面 / nav 图标 hide
 */
@Composable
fun PipoNativeApp() {
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
        var route by remember { mutableStateOf<Route>(Route.Player) }
        var immersive by remember { mutableStateOf(false) }
        val viewModel: PlayerViewModel = viewModel()
        val playerState = viewModel.state
        val settings by PipoGraph.repository.settings.collectAsState(initial = NativeSettings())
        var smartSessionProposal by remember { mutableStateOf<SmartSessionProposal?>(null) }
        var smartSessionRefreshNonce by remember { mutableStateOf(0) }
        var smartSessionTrigger by remember { mutableStateOf<String?>(null) }
        var smartSessionCanReplace by remember { mutableStateOf(false) }
        var smartSessionAutoPlay by remember { mutableStateOf(false) }
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
                    line.companionLines.any { it.role == PipoLyricRole.Translation }
                }
            }
        }

        // 600ms FlipEase 入 / 540ms CloseEase 出，驱动整个沉浸式过渡
        val coverProgress by animateFloatAsState(
            targetValue = if (immersive) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (immersive) PipoMotion.FlipDurationMs else PipoMotion.CloseDurationMs,
                easing = if (immersive) PipoMotion.FlipEase else PipoMotion.CloseEase,
            ),
            label = "coverProgress",
        )
        // compact 封面 / nav 图标在过渡期间都隐藏（0.02 阈值给浮点误差留余量）
        val coverInTransition by remember {
            derivedStateOf { coverProgress > 0.02f }
        }

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
                    positionMs = playerState.positionMs,
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

        val hideSystemBars = route == Route.Player && isLandscape
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

        SmartSessionPlannerEffect(
            settings = settings,
            route = route,
            immersive = immersive,
            isLandscape = isLandscape,
            playerState = playerState,
            refreshNonce = smartSessionRefreshNonce,
            extraIntent = smartSessionTrigger,
            canReplaceNow = smartSessionCanReplace,
            onProposal = { proposal ->
                if (proposal != null && smartSessionAutoPlay) {
                    DiagnosticsLogStore.record(
                        area = "smart_session",
                        event = "auto_play",
                        fields = mapOf(
                            "trackCount" to proposal.tracks.size,
                            "canReplace" to proposal.canReplaceNow,
                        ),
                    )
                    smartSessionAutoPlay = false
                    smartSessionProposal = null
                    viewModel.playFromAgent(proposal.tracks, proposal.continuous)
                } else {
                    if (proposal != null) {
                        DiagnosticsLogStore.record(
                            area = "smart_session",
                            event = "proposal_visible",
                            fields = mapOf(
                                "trackCount" to proposal.tracks.size,
                                "canReplace" to proposal.canReplaceNow,
                            ),
                        )
                    }
                    smartSessionProposal = proposal
                }
            },
        )

        SmartPlaybackRuleEffect(
            settings = settings,
            route = route,
            immersive = immersive,
            isLandscape = isLandscape,
            playerState = playerState,
            onTrigger = { intent, canReplace, autoPlay ->
                DiagnosticsLogStore.record(
                    area = "smart_session",
                    event = "rule_triggered",
                    fields = mapOf(
                        "canReplace" to canReplace,
                        "autoPlay" to autoPlay,
                        "intentLen" to intent.length,
                    ),
                )
                smartSessionTrigger = intent
                smartSessionCanReplace = canReplace
                smartSessionAutoPlay = autoPlay
                smartSessionProposal = null
                smartSessionRefreshNonce += 1
            },
        )

        CompositionLocalProvider(LocalCoverAnchor provides coverAnchor) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 主页：Player（compact 封面在过渡期间 alpha 0）
                PlayerScreen(
                    onOpenLyrics = {
                        viewModel.refreshPosition()
                        immersive = true
                    },
                    onOpenDistill = { route = Route.Distill },
                    onOpenSettings = { route = Route.Settings },
                    immersiveActive = coverInTransition,
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
                    // 沉浸式 backdrop（仅黑兜底 + 模糊封面 + 顶/底渐变压底，不含标题歌词）
                    ImmersiveBackdrop(
                        progress = coverProgress,
                        coverUrl = viewModel.state.artworkUrl,
                    )

                    // 真 FLIP 封面 —— 在 backdrop 之上、标题歌词之下
                    TransitioningCover(
                        compactRect = coverAnchor.state.value.rect,
                        coverUrl = viewModel.state.artworkUrl,
                        progress = coverProgress,
                    )

                    // 标题 + 控件 + 歌词列 —— 在封面之上（标题压在封面下 1/4 处，歌词溶进封面底）
                    // 歌词时钟只使用歌词源自己的时间轴：YRC 逐字、LRC 行级、offset 按解析层修正。
                    val lyricClock = LyricTiming.resolve(
                        positionMs = viewModel.state.positionMs,
                        lines = viewModel.state.lyrics,
                    )
                    ImmersiveLyricsOverlay(
                        progress = coverProgress,
                        coverUrl = viewModel.state.artworkUrl,
                        title = viewModel.state.title,
                        artist = viewModel.state.artist,
                        trackId = viewModel.state.currentTrackId,
                        lyrics = viewModel.state.lyrics,
                        activeLyricIndex = lyricClock.activeIndex,
                        positionMs = lyricClock.positionMs,
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
                        ),
                    ) {
                        when (route) {
                            Route.Distill -> DistillScreen()
                            Route.Settings -> SettingsScreen()
                            Route.Taste -> TasteScreen(
                                onPlayTracks = { tracks ->
                                    if (tracks.isNotEmpty()) {
                                        route = Route.Player
                                        viewModel.playFromAgent(tracks, null)
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
                        currentTrackKey = currentTrack?.id,
                        currentTitle = playerState.title,
                        currentArtist = playerState.artist,
                        coverUrl = playerState.artworkUrl,
                        onPlayFromAgent = { batch, continuous ->
                            viewModel.playFromAgent(batch, continuous)
                        },
                        onInsertFromAgent = { track ->
                            viewModel.insertNext(track)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (route == Route.Player && !immersive && !isLandscape && settings.hideAiPetOrb) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(112.dp)
                            .statusBarsPadding()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        AiPetCommandBus.openChat()
                                        DiagnosticsLogStore.record(
                                            area = "ai_pet",
                                            event = "open_from_top_double_tap",
                                        )
                                    },
                                )
                            },
                    )
                }

                AnimatedVisibility(
                    visible = route == Route.Player &&
                        !immersive &&
                        !isLandscape &&
                        smartSessionProposal != null &&
                        (
                            (!playerState.isPlaying && playerState.queue.isEmpty()) ||
                                smartSessionProposal?.canReplaceNow == true
                            ),
                    enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 4 },
                    exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 4 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 96.dp)
                        .navigationBarsPadding(),
                ) {
                    smartSessionProposal?.let { proposal ->
                        SmartSessionProposalCard(
                            onPlay = {
                                DiagnosticsLogStore.record(
                                    area = "smart_session",
                                    event = "proposal_play_tap",
                                    fields = mapOf(
                                        "trackCount" to proposal.tracks.size,
                                        "canReplace" to proposal.canReplaceNow,
                                    ),
                                )
                                smartSessionProposal = null
                                viewModel.playFromAgent(proposal.tracks, proposal.continuous)
                            },
                            onRefresh = {
                                DiagnosticsLogStore.record(
                                    area = "smart_session",
                                    event = "proposal_refresh_tap",
                                    fields = mapOf("trackCount" to proposal.tracks.size),
                                )
                                smartSessionTrigger = "换一版，和上一版避开同一批歌。"
                                smartSessionCanReplace = proposal.canReplaceNow
                                smartSessionAutoPlay = false
                                smartSessionProposal = null
                                smartSessionRefreshNonce += 1
                            },
                        )
                    }
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

@Composable
private fun SmartSessionPlannerEffect(
    settings: NativeSettings,
    route: Route,
    immersive: Boolean,
    isLandscape: Boolean,
    playerState: PlayerUiState,
    refreshNonce: Int,
    extraIntent: String?,
    canReplaceNow: Boolean,
    onProposal: (SmartSessionProposal?) -> Unit,
) {
    var requestedNonce by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(
        settings.smartSessionPlanner,
        settings.workdayAutoplay,
        settings.lunchRelaxMode,
        settings.lateNightCalmMode,
        settings.promptedRadioRule,
        route,
        immersive,
        isLandscape,
        playerState.isPlaying,
        playerState.queue.size,
        refreshNonce,
        extraIntent,
        canReplaceNow,
    ) {
        if (requestedNonce == refreshNonce || !settings.smartSessionPlanner) return@LaunchedEffect
        if (route != Route.Player || immersive || isLandscape) return@LaunchedEffect
        if ((playerState.isPlaying || playerState.queue.isNotEmpty()) && !canReplaceNow) {
            onProposal(null)
            return@LaunchedEffect
        }

        val slot = currentSmartSessionSlot()
        if (refreshNonce == 0) delay(9000)
        if ((playerState.isPlaying || playerState.queue.isNotEmpty()) && !canReplaceNow) return@LaunchedEffect
        requestedNonce = refreshNonce

        DiagnosticsLogStore.record(
            area = "smart_session",
            event = "request_start",
            fields = mapOf(
                "slot" to slot.name,
                "refresh" to (refreshNonce > 0),
                "canReplace" to canReplaceNow,
                "hasPromptedRule" to settings.promptedRadioRule.isNotBlank(),
                "extraIntent" to (!extraIntent.isNullOrBlank()),
            ),
        )
        try {
            val weather = runCatching { Weather.get() }.getOrNull()
            val contextLine = AppContext.describe(weather)
            val digest = runCatching { AppContext.memoryDigest(settings.userFacts) }.getOrNull()
            val behaviorEvents = runCatching { PipoGraph.behaviorLog.readAll() }.getOrNull().orEmpty()
            val behavior = runCatching { PipoGraph.behaviorLog.summary() }.getOrNull()
            val lastEvent = behaviorEvents.maxByOrNull { it.tsMs }
            val brief = smartSessionBrief(
                settings = settings,
                slot = slot,
                contextLine = contextLine,
                digest = digest,
                behavior = behavior,
                lastEvent = lastEvent,
            )
            val prompt = smartSessionPrompt(
                brief = brief,
                refresh = refreshNonce > 0,
                extraIntent = extraIntent,
            )
            val response = PetAgent(PipoGraph.repository).chat(
                userText = prompt,
                history = emptyList(),
                currentTrack = null,
                userFacts = settings.userFacts,
            )
            if (response.action == PetAgent.Action.Play && response.initialBatch.isNotEmpty()) {
                DiagnosticsLogStore.record(
                    area = "smart_session",
                    event = "request_ready",
                    fields = mapOf(
                        "slot" to slot.name,
                        "trackCount" to response.initialBatch.size,
                        "hasContinuous" to (response.continuous != null),
                    ),
                )
                onProposal(
                    SmartSessionProposal(
                        reply = response.reply.ifBlank { "这段可以直接开始。" },
                        tracks = response.initialBatch,
                        continuous = response.continuous,
                        canReplaceNow = canReplaceNow,
                    ),
                )
            } else {
                DiagnosticsLogStore.record(
                    area = "smart_session",
                    event = "request_empty",
                    fields = mapOf(
                        "slot" to slot.name,
                        "action" to response.action.name,
                        "trackCount" to response.initialBatch.size,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLogStore.record(
                area = "smart_session",
                event = "request_failed",
                fields = mapOf(
                    "slot" to slot.name,
                    "errorType" to e::class.java.simpleName,
                    "errorMessage" to e.message.orEmpty().take(180),
                ),
            )
            requestedNonce = null
        }
    }
}

private data class SmartSessionProposal(
    val reply: String,
    val tracks: List<NativeTrack>,
    val continuous: ContinuousQueueSource?,
    val canReplaceNow: Boolean = false,
)

@Composable
private fun SmartPlaybackRuleEffect(
    settings: NativeSettings,
    route: Route,
    immersive: Boolean,
    isLandscape: Boolean,
    playerState: PlayerUiState,
    onTrigger: (String, Boolean, Boolean) -> Unit,
) {
    var lastRuleKey by remember { mutableStateOf("") }
    LaunchedEffect(
        settings.smartSessionPlanner,
        settings.promptedRadioRule,
        settings.workdayAutoplay,
        settings.lunchRelaxMode,
        settings.lateNightCalmMode,
        route,
        immersive,
        isLandscape,
        playerState.isPlaying,
        playerState.queue.size,
    ) {
        while (settings.smartSessionPlanner && route == Route.Player && !immersive && !isLandscape) {
            delay(45_000)
            val behaviorEvents = runCatching { PipoGraph.behaviorLog.readAll() }.getOrNull().orEmpty()
            val correction = skipCorrectionRule(behaviorEvents)
            val rule = correction ?: activePlaybackRule(settings, playerState)
            if (rule != null && rule.key != lastRuleKey) {
                lastRuleKey = rule.key
                DiagnosticsLogStore.record(
                    area = "smart_session",
                    event = "rule_match",
                    fields = mapOf(
                        "ruleKey" to rule.key,
                        "canReplace" to rule.canReplaceNow,
                        "autoPlay" to rule.autoPlay,
                        "isCorrection" to rule.key.startsWith("skip-correction"),
                    ),
                )
                onTrigger(rule.intent, rule.canReplaceNow, rule.autoPlay)
            }
        }
    }
}

private data class SmartPlaybackRule(
    val key: String,
    val intent: String,
    val canReplaceNow: Boolean,
    val autoPlay: Boolean,
)

private fun activePlaybackRule(settings: NativeSettings, playerState: PlayerUiState): SmartPlaybackRule? {
    val slot = currentSmartSessionSlot()
    val today = Calendar.getInstance()
    val dayKey = "${today.get(Calendar.YEAR)}-${today.get(Calendar.DAY_OF_YEAR)}"
    val prompted = settings.promptedRadioRule.trim()
    val idle = !playerState.isPlaying && playerState.queue.isEmpty()
    if (prompted.isNotBlank() && !playerState.isPlaying) {
        return SmartPlaybackRule(
            key = "prompted:$dayKey:${slot.name}",
            intent = "执行长期电台规则：$prompted",
            canReplaceNow = false,
            autoPlay = idle,
        )
    }
    if (slot == SmartSessionSlot.Lunch && settings.lunchRelaxMode) {
        return SmartPlaybackRule(
            key = "lunch:$dayKey",
            intent = "午休到了，切到柔和、低注意力占用的队列。",
            canReplaceNow = playerState.queue.isNotEmpty(),
            autoPlay = idle,
        )
    }
    if (slot == SmartSessionSlot.Night && settings.lateNightCalmMode) {
        return SmartPlaybackRule(
            key = "night:$dayKey",
            intent = "深夜降低 BPM、能量和鼓点密度，避免突然炸起来。",
            canReplaceNow = playerState.queue.isNotEmpty(),
            autoPlay = idle,
        )
    }
    if (slot == SmartSessionSlot.Work && settings.workdayAutoplay && !playerState.isPlaying) {
        return SmartPlaybackRule(
            key = "work:$dayKey",
            intent = "工作时段自动安排低打扰背景队列。",
            canReplaceNow = false,
            autoPlay = idle,
        )
    }
    return null
}

private fun skipCorrectionRule(events: List<BehaviorEvent>): SmartPlaybackRule? {
    val skipped = events
        .filter { it.type == BehaviorType.Skipped }
        .sortedBy { it.tsMs }
        .takeLast(3)
    if (skipped.size < 3) return null
    val newest = skipped.last()
    val oldest = skipped.first()
    if (newest.tsMs - oldest.tsMs > 15L * 60 * 1000) return null
    return SmartPlaybackRule(
        key = "skip-correction:${newest.tsMs}",
        intent = "用户刚连续跳过三首，主动降能量、减少同类歌，换一段更贴耳的队列。",
        canReplaceNow = true,
        autoPlay = false,
    )
}

private enum class SmartSessionSlot { Work, Lunch, Night, General }

private fun currentSmartSessionSlot(now: Calendar = Calendar.getInstance()): SmartSessionSlot {
    val hour = now.get(Calendar.HOUR_OF_DAY)
    val day = now.get(Calendar.DAY_OF_WEEK)
    val isWeekday = day in Calendar.MONDAY..Calendar.FRIDAY
    return when {
        hour in 12..13 -> SmartSessionSlot.Lunch
        hour >= 22 || hour <= 5 -> SmartSessionSlot.Night
        isWeekday && hour in 8..17 -> SmartSessionSlot.Work
        else -> SmartSessionSlot.General
    }
}

private data class SmartSessionBrief(
    val mode: String,
    val contextLine: String,
    val behaviorLine: String,
)

private fun smartSessionBrief(
    settings: NativeSettings,
    slot: SmartSessionSlot,
    contextLine: String,
    digest: String?,
    behavior: BehaviorSummary?,
    lastEvent: BehaviorEvent?,
): SmartSessionBrief {
    val intent = when {
        settings.promptedRadioRule.isNotBlank() ->
            "Prompted Radio：${settings.promptedRadioRule.take(240)}"
        slot == SmartSessionSlot.Lunch && settings.lunchRelaxMode ->
            "午休模式：轻、松、不抢注意力，能恢复精力。"
        slot == SmartSessionSlot.Night && settings.lateNightCalmMode ->
            "深夜模式：低刺激、音量感柔和、不要突然炸起来。"
        slot == SmartSessionSlot.Work ->
            "工作模式：专注、干净、低打扰。"
        else ->
            "日常模式：自然开场，后面慢慢展开。"
    }
    val behaviorLine = buildList {
        lastEvent?.takeIf { it.title.isNotBlank() }?.let {
            add("上次播放:${it.title} — ${it.artist}(${behaviorTypeLabel(it.type)})")
        }
        digest?.takeIf { it.isNotBlank() }?.let { add("TA 的人:$it") }
        behavior?.loveArtists?.takeIf { it.isNotEmpty() }
            ?.let { add("最近更能听完:${it.take(4).joinToString("、")}") }
        behavior?.skipHotArtists?.takeIf { it.isNotEmpty() }
            ?.let { add("最近反复跳过:${it.take(4).joinToString("、")}") }
        if (behavior != null && behavior.total > 0) {
            add("近况:听过${behavior.total}次,跳过${behavior.skipped}次")
        }
    }.joinToString("；").ifBlank { "最近行为样本还少，先保守安排。" }
    return SmartSessionBrief(
        mode = intent,
        contextLine = contextLine,
        behaviorLine = behaviorLine,
    )
}

private fun behaviorTypeLabel(type: BehaviorType): String = when (type) {
    BehaviorType.PlayStarted -> "开始"
    BehaviorType.Completed -> "听完"
    BehaviorType.Skipped -> "跳过"
    BehaviorType.ManualCut -> "切走"
}

private fun smartSessionPrompt(
    brief: SmartSessionBrief,
    refresh: Boolean,
    extraIntent: String?,
): String {
    return buildString {
        append(if (refresh) "给我换一版 Claudio 电台，不要重复刚才那版。" else "你现在替我安排一段 Claudio 电台。")
        append('\n')
        append("当下:")
        append(brief.contextLine)
        append('\n')
        append("规则:")
        append(brief.mode)
        append('\n')
        if (!extraIntent.isNullOrBlank()) {
            append("临时纠偏:")
            append(extraIntent)
            append('\n')
        }
        append("近期行为:")
        append(brief.behaviorLine)
        append('\n')
        append("队列 10 到 15 首即可，能续杯，接歌要顺。回复只要一句很短的话；不要解释。")
    }
}

@Composable
private fun SmartSessionProposalCard(
    onPlay: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PipoColors.GlassFill)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPlay) {
                Text("播放", color = PipoColors.Mint, style = TextStyle(fontSize = 13.sp))
            }
            TextButton(onClick = onRefresh) {
                Text("换一版", color = PipoColors.Ink, style = TextStyle(fontSize = 13.sp))
            }
        }
    }
}
