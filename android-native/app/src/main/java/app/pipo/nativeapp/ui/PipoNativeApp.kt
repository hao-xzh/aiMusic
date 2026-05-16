package app.pipo.nativeapp.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pipo.nativeapp.data.LyricTiming
import app.pipo.nativeapp.playback.PlayerViewModel

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
        val coverAnchor = rememberCoverAnchorState()

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

        LaunchedEffect(immersive, route) {
            if (immersive || route != Route.Player) coverAnchor.releaseCoverRect()
        }

        // 进入沉浸式歌词页时给 window 加 FLAG_KEEP_SCREEN_ON，退出时立刻清掉。
        // DisposableEffect 的 onDispose 在整个 composable 被销毁时也会清理，防止 leak。
        val view = LocalView.current
        DisposableEffect(immersive) {
            val window = (view.context as? Activity)?.window
            if (immersive) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        CompositionLocalProvider(LocalCoverAnchor provides coverAnchor) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 主页：Player（compact 封面在过渡期间 alpha 0）
                PlayerScreen(
                    onOpenLyrics = { immersive = true },
                    onOpenDistill = { route = Route.Distill },
                    onOpenSettings = { route = Route.Settings },
                    immersiveActive = coverInTransition,
                    viewModel = viewModel,
                )

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
                    lyrics = viewModel.state.lyrics,
                    activeLyricIndex = lyricClock.activeIndex,
                    positionMs = lyricClock.positionMs,
                    isPlaying = viewModel.state.isPlaying,
                    onClose = { immersive = false },
                    onToggle = viewModel::toggle,
                    onNext = viewModel::next,
                    onSeekToMs = { targetMs ->
                        viewModel.seekToMs(targetMs)
                    },
                )

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
                            Route.Taste -> TasteScreen()
                            Route.Login -> LoginScreen(onBack = { route = Route.Player })
                            Route.Player -> Unit
                        }
                    }
                }

                // 后台蒸馏的浮条 —— 跨所有 route 都可见，不阻碍交互
                DistillStatusChip()

                // 全局 AiPet（仅在 Player root + 非沉浸式时显示）
                if (route == Route.Player && !immersive) {
                    val state = viewModel.state
                    val currentTrack = state.queue.getOrNull(state.currentIndex)
                    NativeAiPet(
                        isPlaying = state.isPlaying,
                        currentTrack = currentTrack,
                        currentTrackKey = currentTrack?.id,
                        currentTitle = state.title,
                        currentArtist = state.artist,
                        coverUrl = state.artworkUrl,
                        onPlayFromAgent = { batch, continuous ->
                            viewModel.playFromAgent(batch, continuous)
                        },
                        onInsertFromAgent = { track ->
                            viewModel.insertNext(track)
                        },
                        modifier = Modifier.fillMaxSize(),
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
