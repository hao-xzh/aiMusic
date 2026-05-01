package app.pipo.nativeapp.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
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
                // lyricLeadMs：把 positionMs 提前喂给歌词。600ms 是综合：
                //   - MediaController 通过 IPC 拿 ExoPlayer 位置，本身可能延迟 ~50-100ms
                //   - 网易 YRC 时间戳本身就偏晚于实际唱速 ~100-200ms
                //   - 人眼"看到颜色变 → 听到那个字唱出来"之间约 100ms 感知阈值
                val lyricPositionMs = viewModel.state.positionMs + 600L
                val activeLyricIndex = viewModel.state.lyrics
                    .indexOfLast { line -> lyricPositionMs >= line.startMs }
                    .coerceAtLeast(0)
                ImmersiveLyricsOverlay(
                    progress = coverProgress,
                    coverUrl = viewModel.state.artworkUrl,
                    title = viewModel.state.title,
                    artist = viewModel.state.artist,
                    lyrics = viewModel.state.lyrics,
                    activeLyricIndex = activeLyricIndex,
                    positionMs = lyricPositionMs,
                    isPlaying = viewModel.state.isPlaying,
                    onClose = { immersive = false },
                    onToggle = viewModel::toggle,
                    onNext = viewModel::next,
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
