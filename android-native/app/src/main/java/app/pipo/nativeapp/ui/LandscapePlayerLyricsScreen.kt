package app.pipo.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.PipoLyricLine

/**
 * 横屏播放/歌词共用布局：
 * 左侧封面占满可用高度，右侧承载歌词列表和播放控制。封面右边缘用同源色彩云衔接，
 * 保持竖屏沉浸歌词页那种“封面融进背景”的感觉。
 */
@Composable
internal fun LandscapePlayerLyricsScreen(
    coverUrl: String?,
    title: String,
    artist: String,
    album: String,
    trackId: String?,
    lyrics: List<PipoLyricLine>,
    durationMs: Long,
    positionProvider: () -> Long,
    progressProvider: () -> Float,
    isPlaying: Boolean,
    isLoading: Boolean,
    controlsEnabled: Boolean,
    showTranslation: Boolean,
    hasTranslation: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onToggleTranslation: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekToMs: (Long) -> Unit,
) {
    val edges = useCoverEdgeColors(coverUrl)
    val ambientRgb = edges.ambient ?: edges.right ?: edges.top ?: edges.bottom ?: edges.left
    val ambientColor = rgbToColor(ambientRgb, fallback = PipoColors.Bg1)
    val seamColor = appleMusicLandscapeSurfaceColor(edges, fallback = ambientColor)
    val coverEdgeColor = appleMusicLandscapeCoverColor(edges, fallback = seamColor)
    val accentRgb = blendRgb(edges.accent ?: edges.right, ambientRgb, 0.22f)
    val tone = toneForColor(seamColor)
    val fg = pickFg(tone)
    val fgDim = pickFgDim(tone)
    val fgUnsung = pickFgUnsung(tone)
    val landscapeAccent = rgbToColor(accentRgb, fallback = ambientColor)

    Box(modifier = Modifier.fillMaxSize()) {
        LandscapeBackdrop(
            coverUrl = coverUrl,
            baseColor = seamColor,
            coverEdgeColor = coverEdgeColor,
        )

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
        ) {
            val coverSide = maxHeight
            Row(modifier = Modifier.fillMaxSize()) {
                LandscapeCoverPane(
                    coverUrl = coverUrl,
                    seamColor = coverEdgeColor,
                    modifier = Modifier
                        .width(coverSide + 42.dp)
                        .fillMaxHeight(),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 10.dp, end = 26.dp, top = 8.dp, bottom = 0.dp),
                ) {
                    LandscapeTrackHeader(
                        title = title,
                        artist = artist,
                        album = album,
                        fg = fg,
                        fgDim = fgDim,
                        showTranslation = showTranslation,
                        hasTranslation = hasTranslation,
                        onToggleTranslation = onToggleTranslation,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    androidx.compose.runtime.CompositionLocalProvider(LocalLyricAccent provides landscapeAccent) {
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
                            horizontalPadding = 0.dp,
                            rowMinHeight = 52.dp,
                            rowVerticalPadding = 6.dp,
                            lyricFontSize = 25.sp,
                            lyricLineHeight = 30.sp,
                            lyricFontWeight = FontWeight.Bold,
                            bottomFadeStart = 0.90f,
                            bottomFadeSoftEnd = 0.98f,
                            // 锚点改到顶部后同步收窄渐隐区；否则高横屏按百分比计算的
                            // 20% mask 会把已经贴近标题的当前句也压暗。
                            topFadeTransparentEnd = 0f,
                            topFadePartialEnd = 0.02f,
                            topFadeSolidEnd = 0.05f,
                            // 90dp 只保护极短横屏/分屏不把当前句裁到 viewport 外；常规与
                            // 平板横屏均由 8dp 上限决定，锚点不会随可用高度越长越远离标题。
                            anchorBiasDp = 90.dp,
                            anchorTopCapDp = 8.dp,
                            // 横屏也按真实可用宽度排满再换行，不为“两行等长”提前折行。
                            naturalSyllableWrap = true,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    }

                    LandscapeBottomControls(
                        progressProvider = progressProvider,
                        positionProvider = positionProvider,
                        durationMs = durationMs,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        enabled = controlsEnabled,
                        fg = fg,
                        fgDim = fgDim,
                        onToggle = onToggle,
                        onNext = onNext,
                        onSeek = onSeek,
                    )
                }
            }
        }
    }
}

@Composable
private fun LandscapeBackdrop(
    coverUrl: String?,
    baseColor: Color,
    coverEdgeColor: Color,
) {
    val seamAccent = lerp(coverEdgeColor, baseColor, 0.26f)
    val panelAccent = lerp(coverEdgeColor, baseColor, 0.72f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to seamAccent,
                        0.34f to seamAccent,
                        0.68f to panelAccent,
                        1.0f to baseColor,
                    ),
                ),
            ),
    ) {
        if (coverUrl != null) {
            CrossfadeCoverImage(
                url = coverUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 0.30f
                        scaleX = 1.74f
                        scaleY = 1.74f
                    }
                    .blur(64.dp),
                contentScale = ContentScale.Crop,
                durationMs = PipoMotion.CoverFadeMs,
                maxDecodeSizePx = 448,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.36f to Color.Transparent,
                            0.62f to baseColor.copy(alpha = 0.18f),
                            1.00f to baseColor.copy(alpha = 0.46f),
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun LandscapeCoverPane(
    coverUrl: String?,
    seamColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .reportCoverRect()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black,
                            0.54f to Color.Black,
                            0.72f to Color.Black.copy(alpha = 0.84f),
                            0.90f to Color.Black.copy(alpha = 0.28f),
                            1.0f to Color.Transparent,
                        ),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        if (coverUrl != null) {
            CrossfadeCoverImage(
                url = coverUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF11151D)),
                contentScale = ContentScale.Crop,
                durationMs = 520,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PipoColors.Bg1),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.58f to Color.Transparent,
                            0.82f to seamColor.copy(alpha = 0.08f),
                            1.0f to seamColor.copy(alpha = 0.18f),
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun LandscapeTrackHeader(
    title: String,
    artist: String,
    album: String,
    fg: Color,
    fgDim: Color,
    showTranslation: Boolean,
    hasTranslation: Boolean,
    onToggleTranslation: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
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
                text = buildString {
                    append(artist)
                    if (album.isNotBlank()) append(" · $album")
                }.ifBlank { " " },
                color = fgDim,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hasTranslation) {
            LandscapeRoundButton(
                enabled = true,
                active = showTranslation,
                activeColor = fg,
                onClick = onToggleTranslation,
            ) {
                TranslateGlyph(
                    color = if (showTranslation) fg else fgDim,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun LandscapeBottomControls(
    progressProvider: () -> Float,
    positionProvider: () -> Long,
    durationMs: Long,
    isPlaying: Boolean,
    isLoading: Boolean,
    enabled: Boolean,
    fg: Color,
    fgDim: Color,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LandscapeTimeText(positionProvider, fgDim)
        PipoProgressBar(
            progress = progressProvider,
            onSeek = onSeek,
            trackColor = fg.copy(alpha = 0.18f),
            fillColor = fg,
            modifier = Modifier.weight(1f),
        )
        LandscapeTimeText(
            { (durationMs - positionProvider()).coerceAtLeast(0L) },
            fgDim,
            prefix = "-",
        )
        LandscapeRoundButton(
            enabled = enabled,
            onClick = onToggle,
        ) {
            if (isLoading) {
                LoadingRing(modifier = Modifier.size(15.dp))
            } else if (isPlaying) {
                PauseGlyph(color = fg, modifier = Modifier.size(22.dp))
            } else {
                PlayGlyph(color = fg, modifier = Modifier.size(22.dp))
            }
        }
        LandscapeRoundButton(
            enabled = enabled,
            onClick = onNext,
        ) {
            SkipForwardGlyph(color = fg, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun LandscapeRoundButton(
    enabled: Boolean,
    active: Boolean = false,
    activeColor: Color = Color.Transparent,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (active) activeColor.copy(alpha = 0.13f) else Color.Transparent)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.35f }) {
            content()
        }
    }
}

@Composable
private fun LandscapeTimeText(ms: () -> Long, color: Color, prefix: String = "") {
    val total = (ms() / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    Text(
        text = "$prefix${m}:${s.toString().padStart(2, '0')}",
        color = color,
        style = TextStyle(
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        ),
        maxLines = 1,
        modifier = Modifier.widthIn(min = 36.dp),
    )
}
