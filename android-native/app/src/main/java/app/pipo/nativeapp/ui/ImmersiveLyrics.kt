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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
    val ambientRgb = edges.ambient ?: edges.bottom ?: edges.top ?: edges.right ?: edges.left
    val ambientColor = rgbToColor(ambientRgb, fallback = PipoColors.Bg1)
    val topColor = rgbToColor(blendRgb(edges.top, ambientRgb, 0.40f), fallback = ambientColor)
    val rightColor = rgbToColor(blendRgb(edges.right, ambientRgb, 0.48f), fallback = ambientColor)
    val seamColor = rgbToColor(blendRgb(edges.bottom, ambientRgb, 0.34f), fallback = ambientColor)
    val accentColor = rgbToColor(blendRgb(edges.accent, ambientRgb, 0.28f), fallback = seamColor)
    // Apple Music 的"封面就是页"做法：同源重模糊只提供纹理连续性，稳定底色云决定大面积色调。
    // 这样不会被黑字/emoji/局部强色揉脏，同时 sharp 封面底部仍能自然溶进同一张图的模糊层。
    //
    //   底层：bg color cloud —— 多个 radialGradient 叠出 mesh-like 效果
    //     · 屏幕左上：topColor 半径 70% → 淡掉
    //     · 屏幕右上：rightColor 半径 65% → 淡掉
    //     · 屏幕底中：seamColor 半径 80% → 淡掉
    //   这三个 radial 叠加 + 互相填补，结果是封面色调温柔铺满整屏，没有可见的图样。
    //
    // 用 drawBehind 直接画圆（中心 = 屏幕分数坐标 × 尺寸），避免 Brush.radialGradient
    // 的 center 必须是 px 坐标的繁琐
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress }
            .background(ambientColor)
            .drawWithContent {
                drawContent()
                val w = size.width
                val h = size.height
                val maxDim = kotlin.math.max(w, h) * 1.4f
                // 三个色块叠合形成 mesh-like 色彩云
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(topColor.copy(alpha = 0.62f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(w * 0.20f, h * 0.18f),
                        radius = maxDim * 0.85f,
                    ),
                    radius = maxDim,
                    center = androidx.compose.ui.geometry.Offset(w * 0.20f, h * 0.18f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(rightColor.copy(alpha = 0.46f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.30f),
                        radius = maxDim * 0.80f,
                    ),
                    radius = maxDim,
                    center = androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.30f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(seamColor.copy(alpha = 0.72f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.95f),
                        radius = maxDim * 0.95f,
                    ),
                    radius = maxDim,
                    center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.95f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accentColor.copy(alpha = 0.16f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.22f),
                        radius = maxDim * 0.58f,
                    ),
                    radius = maxDim,
                    center = androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.22f),
                )
            },
    ) {
        if (coverUrl != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.34f
                        scaleX = 1.16f
                        scaleY = 1.16f
                    }
                    .blur(26.dp),
            ) {
                CrossfadeCoverImage(
                    url = coverUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    durationMs = PipoMotion.CoverFadeMs,
                    maxDecodeSizePx = 720,
                )
            }
        }
    }
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
    val tone = toneForColor(appleMusicPureSurfaceColor(edges))
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
    val cp = contentProgress.coerceIn(0f, 1f)
    fun smoothRange(start: Float, end: Float): Float {
        val t = ((cp - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    val titleEnter = smoothRange(0.70f, 0.86f)
    val lyricControlsEnter = smoothRange(0.88f, 1.00f)
    val lyricListEnter = smoothRange(0.90f, 1.00f)
    val titleTopPadding = immersiveLyricsTitleTop(screenWDp)
    val lyricsTopPadding = (titleTopPadding + 50.dp).coerceAtLeast(104.dp)
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = titleTopPadding, start = 24.dp, end = 24.dp)
                .zIndex(2f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
                    .graphicsLayer { alpha = titleEnter },
            ) {
                Text(
                    text = title.ifBlank { "—" },
                    color = fg,
                    style = TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 26.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = artist.ifBlank { " " },
                    color = fgDim,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 18.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.graphicsLayer {
                    alpha = lyricControlsEnter
                    translationY = (1f - lyricControlsEnter) * 8.dp.toPx()
                },
            ) {
                if (hasTranslation) {
                    ImmersiveIconButton(
                        onClick = onToggleTranslation,
                        active = showTranslation,
                        activeColor = fg,
                    ) {
                        TranslateGlyph(
                            color = if (showTranslation) fg else fgDim,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                ImmersiveIconButton(onClick = onToggle) {
                    if (isPlaying) {
                        PauseGlyph(color = fg, modifier = Modifier.size(23.dp))
                    } else {
                        PlayGlyph(color = fg, modifier = Modifier.size(23.dp))
                    }
                }
                ImmersiveIconButton(onClick = onNext) {
                    SkipForwardGlyph(color = fg, modifier = Modifier.size(22.dp))
                }
            }
        }

        // 歌词列共享播放页的封面/毛玻璃背景：进入歌词页时只让下方播放控件淡出、
        // 歌词列表淡入，背景不重绘第二套封面，避免出现上下分界。
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
            enterProgress = lyricListEnter,
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
    androidx.compose.runtime.CompositionLocalProvider(LocalLyricAccent provides lyricAccentColor) {
        AppleMusicLyricColumn(
            lines = lyrics,
            sessionId = trackId,
            isPlaying = isPlaying,
            positionProvider = positionProvider,
            fg = fg,
            fgDim = fgDim,
            fgUnsung = fgUnsung,
            showTranslation = showTranslation,
            onSeekToMs = onSeekToMs,
            enterProgress = enterProgress,
            topFadeTransparentEnd = 0.00f,
            topFadePartialEnd = 0.00f,
            topFadeSolidEnd = 0.00f,
            anchorTopCapDp = 2.dp,
            topHardClipDp = 8.dp,
            hideRowsAboveAnchor = true,
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

// BackdropBlurredCover 独立组件已删除：ImmersiveBackdrop 内联同源模糊层 + 柔和色彩云，
// 让清晰封面底部 fade 时仍接到同一张图的氛围纹理。

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

private fun immersiveLyricsTitleTop(screenWidth: Dp): Dp =
    (screenWidth - 18.dp).coerceAtLeast(52.dp)
