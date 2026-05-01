package app.pipo.nativeapp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pipo.nativeapp.playback.PlayerViewModel
import app.pipo.nativeapp.runtime.Amp
import kotlinx.coroutines.delay

/**
 * 主播放卡片 —— 镜像 src/components/PlayerCard.tsx 的 compact 布局，按 React clamp() 1:1。
 */
@Composable
fun PlayerScreen(
    onOpenLyrics: () -> Unit,
    onOpenDistill: () -> Unit,
    onOpenSettings: () -> Unit,
    immersiveActive: Boolean,
    viewModel: PlayerViewModel = viewModel(),
) {
    val state = viewModel.state
    // 读用户的"隐藏点阵"开关
    val settings by app.pipo.nativeapp.data.PipoGraph.repository.settings
        .collectAsState(initial = app.pipo.nativeapp.data.NativeSettings())

    LaunchedEffect(state.isPlaying) {
        while (true) {
            viewModel.refreshPosition()
            if (!state.isPlaying) Amp.set(0f)
            delay(if (state.isPlaying) 80L else 420L)
        }
    }

    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val hideAlpha by animateFloatAsState(
        targetValue = if (immersiveActive) 0f else 1f,
        animationSpec = tween(140),
        label = "hideAlpha",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 全局背景（封面驱动 blur + DotField + 切歌 cross-fade）
        AdaptiveDotField(
            coverUrl = state.artworkUrl,
            isPlaying = state.isPlaying,
            showDots = !settings.hideDotPattern,
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(top = 28.dp, bottom = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val viewportW = maxWidth
            val viewportH = maxHeight
            val coverSize = clampDp(220.dp, viewportW * 0.86f, 400.dp).coerceAtMost(viewportH * 0.5f)
            val shellPad = clampDp(16.dp, viewportW * 0.04f, 40.dp)
            val titleFs = clampSp(17, viewportW.value * 0.04f, 22)
            val subtitleFs = clampSp(12, viewportW.value * 0.032f, 14)
            // 标题块到封面 / 到进度条的距离相等 —— 都用 titleGap
            // 用户要求加高 1/2（×1.5）让封面 / 标题 / 进度条不再挤
            // 标题 → 副标题之间（titleSubGap）保持 6dp 不动
            val titleGap = clampDp(21.dp, viewportH * 0.033f, 30.dp)
            val controlsMarginTop = clampDp(22.dp, viewportH * 0.034f, 32.dp)
            val navMarginTop = controlsMarginTop

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = shellPad)
                    .widthIn(max = 600.dp), // shell maxWidth
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .width(coverSize) // trackColumn width = COVER_SIZE
                        .align(Alignment.CenterHorizontally),
                ) {
                    CompactCover(
                        coverUrl = state.artworkUrl,
                        isPlaying = state.isPlaying,
                        hidden = immersiveActive,
                    )

                    Spacer(modifier = Modifier.height(titleGap))
                    if (state.title.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(hideAlpha),
                        ) {
                            Text(
                                text = state.title,
                                color = Color(0xFFF5F7FF),
                                style = TextStyle(
                                    fontSize = titleFs,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-0.4).sp,
                                    lineHeight = (titleFs.value * 1.25f).sp,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = buildString {
                                    append(state.artist)
                                    if (state.album.isNotBlank()) append(" · ${state.album}")
                                },
                                color = Color(0x8CE9EFFF),
                                style = TextStyle(
                                    fontSize = subtitleFs,
                                    fontWeight = FontWeight.Medium,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Spacer(modifier = Modifier.height(titleGap))
                    PipoProgressBar(
                        progress = progress,
                        onSeek = viewModel::seekTo,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MonoTime(state.positionMs, color = Color(0xB8E9EFFF))
                        MonoTime(
                            (state.durationMs - state.positionMs).coerceAtLeast(0L),
                            color = Color(0x6BE9EFFF),
                            prefix = "-",
                        )
                    }

                    Spacer(modifier = Modifier.height(controlsMarginTop))
                    TriCol(
                        a = {
                            FlatBtn(
                                onClick = viewModel::previous,
                                enabled = state.queue.isNotEmpty(),
                                size = clampDp(50.dp, viewportW * 0.115f, 64.dp),
                            ) {
                                SkipBackGlyph(modifier = Modifier.size(clampDp(32.dp, viewportW * 0.085f, 44.dp)))
                            }
                        },
                        b = {
                            FlatBtn(
                                onClick = viewModel::toggle,
                                enabled = state.queue.isNotEmpty(),
                                size = clampDp(64.dp, viewportW * 0.155f, 80.dp),
                            ) {
                                if (state.isPlaying) PauseGlyph(modifier = Modifier.size(clampDp(44.dp, viewportW * 0.12f, 60.dp)))
                                else PlayGlyph(modifier = Modifier.size(clampDp(44.dp, viewportW * 0.12f, 60.dp)))
                            }
                        },
                        c = {
                            FlatBtn(
                                onClick = viewModel::next,
                                enabled = state.queue.isNotEmpty(),
                                size = clampDp(50.dp, viewportW * 0.115f, 64.dp),
                            ) {
                                SkipForwardGlyph(modifier = Modifier.size(clampDp(32.dp, viewportW * 0.085f, 44.dp)))
                            }
                        },
                    )

                    Spacer(modifier = Modifier.height(navMarginTop))
                    TriCol(
                        modifier = Modifier.alpha(hideAlpha),
                        a = {
                            NavIconBtn(onClick = onOpenLyrics, enabled = state.lyrics.isNotEmpty()) {
                                LyricsIcon(modifier = Modifier.size(24.dp))
                            }
                        },
                        b = {
                            NavIconBtn(onClick = onOpenDistill) { ListIcon(modifier = Modifier.size(24.dp)) }
                        },
                        c = {
                            NavIconBtn(onClick = onOpenSettings) { GearIcon(modifier = Modifier.size(24.dp)) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactCover(coverUrl: String?, isPlaying: Boolean, hidden: Boolean) {
    val hideAlpha by animateFloatAsState(
        targetValue = if (hidden) 0f else 1f,
        animationSpec = tween(140),
        label = "coverHide",
    )
    val playScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.012f else 1f,
        animationSpec = tween(2800),
        label = "playScale",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .reportCoverRect()
            // React boxShadow: 0 24px 64px rgba(0,0,0,0.5)
            // Compose shadow elevation 不能直接给 offset/blur，elevation=24dp 是
            // 视觉上最接近的近似（自带 alpha 0.5 的黑色 spotShadow）。
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(PipoDimens.CompactCornerDp),
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.5f),
                spotColor = Color.Black.copy(alpha = 0.5f),
            )
            // 0 0 0 1px rgba(255,255,255,0.04) ring
            .border(
                width = 1.dp,
                color = Color(0x0AFFFFFF),
                shape = RoundedCornerShape(PipoDimens.CompactCornerDp),
            )
            .clip(RoundedCornerShape(PipoDimens.CompactCornerDp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0x1A9BE3C6),
                        Color(0x059BE3C6),
                    ),
                ),
            )
            .alpha(hideAlpha),
    ) {
        if (coverUrl != null) {
            CrossfadeCoverImage(
                url = coverUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = playScale
                        scaleY = playScale
                    },
                contentScale = ContentScale.Crop,
                durationMs = 720, // COVER_TRANSITION_MS
            )
        }
    }
}

/**
 * 自绘进度条 —— 4dp 实心圆角条，无 thumb，按下任意位置跳到对应进度。
 *   - 轨道：rgba(233,239,255,0.12)
 *   - 填充：rgba(245,247,255,0.92)
 *   - 跟 React `progressFill { transition: width 120ms linear }` 同款
 */
@Composable
private fun PipoProgressBar(progress: Float, onSeek: (Float) -> Unit) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 120),
        label = "progress",
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0x1FE9EFFF))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = animated)
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xEBF5F7FF)),
        )
    }
}

@Composable
private fun MonoTime(ms: Long, color: Color, prefix: String = "") {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    Text(
        text = "$prefix${m}:${s.toString().padStart(2, '0')}",
        color = color,
        style = TextStyle(
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        ),
    )
}

@Composable
private fun TriCol(
    modifier: Modifier = Modifier,
    a: @Composable () -> Unit,
    b: @Composable () -> Unit,
    c: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { a() }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { b() }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { c() }
    }
}

@Composable
private fun FlatBtn(
    onClick: () -> Unit,
    enabled: Boolean,
    size: Dp = PipoDimens.SkipButtonSize,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.32f),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun NavIconBtn(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(50))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .alpha(if (enabled) 0.82f else 0.32f),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ---------- clamp 工具 ----------

/** CSS clamp(min, prefer, max) 在 dp 上的等价 */
private fun clampDp(min: Dp, prefer: Dp, max: Dp): Dp {
    return when {
        prefer < min -> min
        prefer > max -> max
        else -> prefer
    }
}

private fun clampSp(min: Int, preferDp: Float, max: Int): androidx.compose.ui.unit.TextUnit {
    val v = preferDp.coerceIn(min.toFloat(), max.toFloat())
    return v.sp
}
