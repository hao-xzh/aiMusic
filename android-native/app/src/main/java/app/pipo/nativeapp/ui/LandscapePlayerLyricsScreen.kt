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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
    lyrics: List<PipoLyricLine>,
    activeLyricIndex: Int,
    positionMs: Long,
    durationMs: Long,
    progress: Float,
    isPlaying: Boolean,
    isLoading: Boolean,
    controlsEnabled: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekToMs: (Long) -> Unit,
) {
    val edges = useCoverEdgeColors(coverUrl)
    val tone = computeTone(edges.right ?: edges.bottom ?: edges.top)
    val fg = pickFg(tone)
    val fgDim = pickFgDim(tone)
    val fgUnsung = pickFgUnsung(tone)
    val landscapeAccent = rgbToColor(edges.right ?: edges.top ?: edges.left ?: edges.bottom, fallback = PipoColors.Bg1)
    val seamColor = landscapeAccent
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize()) {
        LandscapeBackdrop(
            accentColor = landscapeAccent,
            edgeColor = landscapeAccent,
        )

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
        ) {
            val coverSide = maxHeight
            val statusTopPadding = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
            val compactStatusTopPadding = maxOf(0.dp, statusTopPadding - 6.dp)
            Row(modifier = Modifier.fillMaxSize()) {
                LandscapeCoverPane(
                    coverUrl = coverUrl,
                    seamColor = seamColor,
                    modifier = Modifier
                        .width(coverSide + 42.dp)
                        .fillMaxHeight(),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .navigationBarsPadding()
                        .padding(start = 10.dp, end = 26.dp, top = compactStatusTopPadding, bottom = 0.dp),
                ) {
                    LandscapeTrackHeader(
                        title = title,
                        artist = artist,
                        album = album,
                        fg = fg,
                        fgDim = fgDim,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    AppleMusicLyricColumn(
                        lines = lyrics,
                        activeLyricIndex = activeLyricIndex,
                        positionMs = positionMs,
                        isPlaying = isPlaying,
                        fg = fg,
                        fgDim = fgDim,
                        fgUnsung = fgUnsung,
                        onSeekToMs = onSeekToMs,
                        horizontalPadding = 0.dp,
                        rowMinHeight = 52.dp,
                        rowVerticalPadding = 6.dp,
                        lyricFontSize = 25.sp,
                        lyricLineHeight = 39.sp,
                        lyricFontWeight = FontWeight.ExtraBold,
                        bottomFadeStart = 0.90f,
                        bottomFadeSoftEnd = 0.98f,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )

                    LandscapeBottomControls(
                        progress = progress,
                        positionMs = positionMs,
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
    accentColor: Color,
    edgeColor: Color,
) {
    val seamAccent = lerp(accentColor, Color.Black, 0.10f)
    val panelAccent = lerp(accentColor, Color.Black, 0.22f)
    val farAccent = lerp(accentColor, Color(0xFF10131B), 0.32f)
    val quietEdge = lerp(edgeColor, panelAccent, 0.34f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to seamAccent,
                        0.42f to seamAccent,
                        0.76f to panelAccent,
                        1.0f to farAccent,
                    ),
                ),
            )
            .drawWithContent {
                drawContent()
                val w = size.width
                val h = size.height
                val maxDim = kotlin.math.max(w, h)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accentColor.copy(alpha = 0.26f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.22f),
                        radius = maxDim * 0.90f,
                    ),
                    radius = maxDim,
                    center = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.22f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(quietEdge.copy(alpha = 0.14f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(w * 0.88f, h * 0.26f),
                        radius = maxDim * 0.78f,
                    ),
                    radius = maxDim,
                    center = androidx.compose.ui.geometry.Offset(w * 0.88f, h * 0.26f),
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.08f)),
                    ),
                )
            },
    )
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
                            0.58f to Color.Black,
                            0.78f to Color.Black.copy(alpha = 0.78f),
                            0.91f to Color.Black.copy(alpha = 0.24f),
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
                            0.46f to Color.Transparent,
                            0.78f to seamColor.copy(alpha = 0.26f),
                            1.0f to seamColor.copy(alpha = 0.62f),
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
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
}

@Composable
private fun LandscapeBottomControls(
    progress: Float,
    positionMs: Long,
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
        LandscapeTimeText(positionMs, fgDim)
        PipoProgressBar(
            progress = progress,
            onSeek = onSeek,
            trackColor = fg.copy(alpha = 0.18f),
            fillColor = fg,
            modifier = Modifier.weight(1f),
        )
        LandscapeTimeText((durationMs - positionMs).coerceAtLeast(0L), fgDim, prefix = "-")
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
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
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
private fun LandscapeTimeText(ms: Long, color: Color, prefix: String = "") {
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
        maxLines = 1,
        modifier = Modifier.widthIn(min = 36.dp),
    )
}
