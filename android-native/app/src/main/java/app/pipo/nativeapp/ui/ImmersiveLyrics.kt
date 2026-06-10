package app.pipo.nativeapp.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.pipo.nativeapp.data.LyricTiming
import app.pipo.nativeapp.data.PipoLyricLine

/**
 * 仅渲染沉浸式的"底"：黑兜底 + 同源模糊封面 + 顶/底采样色渐变。
 * 在 PipoNativeApp 里被 TransitioningCover 叠在上面，再叠上标题 / 歌词。
 */
@Composable
fun ImmersiveBackdrop(
    progress: Float,
    coverUrl: String?,
) {
    if (progress <= 0.001f) return
    val edges = useCoverEdgeColors(coverUrl)
    val topColor = rgbToColor(edges.top, fallback = PipoColors.Bg1)
    val rightColor = rgbToColor(edges.right, fallback = PipoColors.Bg1)
    val seamColor = rgbToColor(edges.bottom, fallback = PipoColors.Bg1)
    // Apple Music 的"封面就是页"做法：根本不用 blur，靠从封面边缘采样的多个色块
    // 在屏幕上形成"色彩云"。封面 sharp 边缘 fade 进色彩里就完成了"图融化进页"。
    //
    //   底层：bg color cloud —— 多个 radialGradient 叠出 mesh-like 效果
    //     · 屏幕左上：topColor 半径 70% → 淡掉
    //     · 屏幕右上：rightColor 半径 65% → 淡掉
    //     · 屏幕底中：seamColor 半径 80% → 淡掉
    //   这三个 radial 叠加 + 互相填补，结果是封面色调温柔铺满整屏，没有可见的图样。
    //
    //   不再叠 blurred cover：blur 出来的图永远有 pattern 残留，跟 sharp 封面拼一起
    //   总有"两张图叠在一起"的视觉断层。Apple Music 的诀窍就是直接用 SOLID color，
    //   没有第二张图，所以 sharp 封面边缘 fade 时直接溶进色彩本身，浑然一体。
    // 用 drawBehind 直接画圆（中心 = 屏幕分数坐标 × 尺寸），避免 Brush.radialGradient
    // 的 center 必须是 px 坐标的繁琐
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress }
            .background(PipoColors.Bg0)  // 纯黑兜底，渐变叠到上面
            .drawWithContent {
                drawContent()
                val w = size.width
                val h = size.height
                val maxDim = kotlin.math.max(w, h) * 1.4f
                // 三个色块叠合形成 mesh-like 色彩云
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(topColor.copy(alpha = 0.95f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(w * 0.20f, h * 0.18f),
                        radius = maxDim * 0.85f,
                    ),
                    radius = maxDim,
                    center = androidx.compose.ui.geometry.Offset(w * 0.20f, h * 0.18f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(rightColor.copy(alpha = 0.85f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.30f),
                        radius = maxDim * 0.80f,
                    ),
                    radius = maxDim,
                    center = androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.30f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(seamColor.copy(alpha = 0.95f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.95f),
                        radius = maxDim * 0.95f,
                    ),
                    radius = maxDim,
                    center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.95f),
                )
            },
    )
}

// 歌词扫描交界处的"封面色微光"。每首歌从封面提一抹主色，沿 sweepX 连续随字母流动。
// 默认 Color.Unspecified = 不染色（灰度封面 / 非沉浸场景）。
internal val LocalLyricAccent = androidx.compose.runtime.compositionLocalOf { Color.Unspecified }

@Composable
fun ImmersiveLyricsOverlay(
    progress: Float,                  // 0=compact, 1=immersive（封面 FLIP 时间线）
    contentProgress: Float,           // 0=未入场, 1=已入场（标题/歌词的独立内容时间线）
    coverUrl: String?,
    title: String,
    artist: String,
    trackId: String?,
    lyrics: List<PipoLyricLine>,
    positionProvider: () -> Long,
    isPlaying: Boolean,
    showTranslation: Boolean,
    hasTranslation: Boolean,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onToggleTranslation: () -> Unit,
    onSeekToMs: (Long) -> Unit,
) {
    if (progress <= 0.001f) return

    val edges = useCoverEdgeColors(coverUrl)
    val tone = computeTone(edges.bottom)
    val fg = pickFg(tone)
    val fgDim = pickFgDim(tone)
    val fgUnsung = pickFgUnsung(tone)
    val lyricAccentColor = lyricAccent(edges.accent)

    // 切歌淡出淡入：title 变了就 fade 0 → 1。跟入场/出场的 contentProgress 解耦。
    var lastTitle by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val contentFade = remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(title) {
        if (lastTitle != null && lastTitle != title) {
            contentFade.snapTo(0f)
            contentFade.animateTo(
                1f,
                animationSpec = androidx.compose.animation.core.tween(360),
            )
        }
        lastTitle = title
    }

    // 布局：标题 / 歌词列的位置**固定在 immersive 终态**（不再跟着封面 progress 走），
    // 入场只做小幅 translateY + alpha。封面 FLIP 时其它东西不再跟着大幅平移 = "丝滑" 的关键。
    val configuration = LocalConfiguration.current
    val screenWDp = configuration.screenWidthDp.dp
    // tap 关闭区域仍跟着封面 progress 增长，避免点空封面尚未飞到的位置
    val coverTapHeight = screenWDp * progress.coerceIn(0f, 1f)
    val density = LocalDensity.current
    val cp = contentProgress.coerceIn(0f, 1f)
    val titleTopPadding = (screenWDp - 84.dp).coerceAtLeast(14.dp)
    val lyricsTopPadding = (screenWDp - 28.dp).coerceAtLeast(80.dp)
    val coverCloseHeight = minOf(
        coverTapHeight,
        lyricsTopPadding,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 只承载切歌的 cross-fade；入场 alpha 由内层每个元素独立处理
            .graphicsLayer { alpha = contentFade.value },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(coverCloseHeight)
                .zIndex(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
        // 标题 / 歌词列绝对定位到 immersive 终态。封面 FLIP 期间它们还是 alpha 0，
        // 由 contentProgress（延后 120ms 起跳）控制软入。

        // 标题 + 副标题 + 控件条 —— 固定在封面下 1/4
        val titleRiseDp = 16.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = titleTopPadding, start = 24.dp, end = 24.dp)
                .zIndex(2f)
                .graphicsLayer {
                    alpha = cp
                    translationY = (1f - cp) * titleRiseDp.toPx()
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            ) {
                Text(
                    text = title.ifBlank { "—" },
                    color = fg,
                    style = TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = artist,
                    color = fgDim,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasTranslation) {
                    ImmersiveIconButton(
                        onClick = onToggleTranslation,
                        active = showTranslation,
                        activeColor = fg,
                    ) {
                        TranslateGlyph(
                            color = if (showTranslation) fg else fgDim,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                ImmersiveIconButton(onClick = onToggle) {
                    if (isPlaying) PauseGlyph(color = fg, modifier = Modifier.size(24.dp))
                    else PlayGlyph(color = fg, modifier = Modifier.size(24.dp))
                }
                ImmersiveIconButton(onClick = onNext) {
                    SkipForwardGlyph(color = fg, modifier = Modifier.size(25.dp))
                }
            }
        }

        // 歌词列 —— 固定在封面终态底部下方 28dp。封面 FLIP 期间整列 translateY 抬起 24dp，
        // 内部各行按距 active 行的索引差 stagger 入场（cascade 焦点感）。
        val lyricsRiseDp = 24.dp
        // 内容淡入前就挂载歌词列：它会先用 alpha=0 完成行高/锚点校准，
        // 等校准完成后才随 contentProgress 淡入，避免首屏可见跳动。
        ImmersiveLyricsColumnLayer(
            lyrics = lyrics,
            trackId = trackId,
            positionProvider = positionProvider,
            isPlaying = isPlaying,
            fg = fg,
            fgDim = fgDim,
            fgUnsung = fgUnsung,
            lyricAccentColor = lyricAccentColor,
            showTranslation = showTranslation,
            onSeekToMs = onSeekToMs,
            enterProgress = cp,
            lyricsTopPadding = lyricsTopPadding,
            lyricsRiseDp = lyricsRiseDp,
        )
    }
}

@Composable
private fun ImmersiveLyricsColumnLayer(
    lyrics: List<PipoLyricLine>,
    trackId: String?,
    positionProvider: () -> Long,
    isPlaying: Boolean,
    fg: Color,
    fgDim: Color,
    fgUnsung: Color,
    lyricAccentColor: Color,
    showTranslation: Boolean,
    onSeekToMs: (Long) -> Unit,
    enterProgress: Float,
    lyricsTopPadding: Dp,
    lyricsRiseDp: Dp,
) {
    // 进度是 30Hz 热路径，只在歌词列子树读取，避免标题、控件、backdrop 跟着重组。
    val lyricClock = LyricTiming.resolve(
        positionMs = positionProvider(),
        lines = lyrics,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalLyricAccent provides lyricAccentColor) {
        AppleMusicLyricColumn(
            lines = lyrics,
            sessionId = trackId,
            activeLyricIndex = lyricClock.activeIndex,
            positionMs = lyricClock.positionMs,
            isPlaying = isPlaying,
            fg = fg,
            fgDim = fgDim,
            fgUnsung = fgUnsung,
            showTranslation = showTranslation,
            onSeekToMs = onSeekToMs,
            enterProgress = enterProgress,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = lyricsTopPadding, bottom = 20.dp)
                .navigationBarsPadding()
                .graphicsLayer {
                    translationY = (1f - enterProgress) * lyricsRiseDp.toPx()
                },
        )
    }
}

// BackdropBlurredCover 已删除：换成 ImmersiveBackdrop 里 drawBehind 多个 radialGradient
// 的"色彩云"做法，没有 blurred image 这第二层贴图，跟 Apple Music 的视觉一致。

@Composable
private fun ImmersiveIconButton(
    onClick: () -> Unit,
    active: Boolean = false,
    activeColor: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    val activeEase = remember { CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f) }
    val activeProgress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = activeEase),
        label = "immersiveIconActive",
    )
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(50))
            .background(activeColor.copy(alpha = 0.13f * activeProgress))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
