package app.pipo.nativeapp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.pipo.nativeapp.data.PipoLyricLine

// 歌词扫描交界处的"封面色微光"。Local 传递稳定的 State，而不是逐帧变化的 Color；
// 歌词在 draw 阶段读取 value，切歌颜色动画只触发重绘，不让整棵歌词子树逐帧重组。
private val NoLyricAccentState = object : State<Color> {
    override val value: Color = Color.Unspecified
}
internal val LocalLyricAccent = staticCompositionLocalOf<State<Color>> { NoLyricAccentState }

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
    // 封面采样在 IO 完成后才更新。颜色与背景使用同一条 1100ms 过渡，
    // 避免扫色带在切歌时从上一首硬跳到新主色。
    val lyricAccentState = animateColorAsState(
        targetValue = lyricAccent(edges.accent),
        animationSpec = tween(PipoMotion.CoverFadeMs, easing = PipoMotion.FlipEase),
        label = "immersiveLyricAccent",
    )

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
            lyricAccentState = lyricAccentState,
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
    lyricAccentState: State<Color>,
    showTranslation: Boolean,
    onSeekToMs: (Long) -> Unit,
    enterProgress: Float,
    lyricsTopPadding: Dp,
    lyricsRiseDp: Dp,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalLyricAccent provides lyricAccentState) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = lyricsTopPadding, bottom = 20.dp)
                .navigationBarsPadding()
                .graphicsLayer {
                    translationY = (1f - enterProgress) * lyricsRiseDp.toPx()
                },
        ) {
            val typography = nativeLyricTypography(
                contentWidth = (maxWidth - 48.dp).coerceAtLeast(0.dp),
                viewportHeight = maxHeight,
            )
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
                lyricFontSize = typography.fontSize,
                lyricLineHeight = typography.lineHeight,
                lyricFontWeight = FontWeight.Bold,
                rowVerticalPadding = 7.75.dp,
                useMobileAppleProfile = true,
                // 当前页面的歌词 viewport 只有封面/标题下方半屏，不能继续按整屏 25%
                // 下压锚点。14dp + 行内约 31dp 顶部间距后，字形从约 45dp 开始，刚好
                // 越过 40dp 顶部渐隐区；相比上一版再上移一整行，同时保持当前句完整。
                anchorTopCapDp = 14.dp,
                // 父容器已经扣掉封面/标题和导航栏，只使用真实歌词 viewport。
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

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
