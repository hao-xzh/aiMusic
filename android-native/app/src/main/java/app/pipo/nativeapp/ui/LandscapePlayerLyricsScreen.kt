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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
    val seamColor = rgbToColor(edges.right, fallback = PipoColors.Bg1)

    Box(modifier = Modifier.fillMaxSize()) {
        ImmersiveBackdrop(progress = 1f, coverUrl = coverUrl)

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
        ) {
            val coverSide = maxHeight
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
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(start = 10.dp, end = 26.dp, top = 4.dp, bottom = 2.dp),
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
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
                lineHeight = 23.sp,
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
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp),
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
            .heightIn(min = 62.dp)
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LandscapeTimeText(positionMs, fgDim)
        PipoProgressBar(
            progress = progress,
            onSeek = onSeek,
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
