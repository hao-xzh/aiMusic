package app.pipo.nativeapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pipo.nativeapp.data.PipoLyricLine
import app.pipo.nativeapp.playback.PlayerViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun PlayerScreen(viewModel: PlayerViewModel = viewModel()) {
    val state = viewModel.state
    var immersiveLyrics by remember { mutableStateOf(false) }

    LaunchedEffect(state.isPlaying) {
        while (true) {
            viewModel.refreshPosition()
            delay(if (state.isPlaying) 250L else 900L)
        }
    }

    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PipoColors.Background),
    ) {
        AmbientNativeField(isPlaying = state.isPlaying)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 18.dp)
                .padding(bottom = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(0.38f))
            NativeCover(
                artworkUrl = state.artworkUrl,
                isPlaying = state.isPlaying,
            )
            Spacer(modifier = Modifier.height(26.dp))
            Text(
                text = state.title,
                color = PipoColors.Text,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${state.artist} - ${state.album}",
                color = PipoColors.TextMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(30.dp))
            Slider(
                value = progress,
                onValueChange = viewModel::seekTo,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFE9EFFF),
                    activeTrackColor = Color(0xFFE9EFFF),
                    inactiveTrackColor = Color(0x33E9EFFF),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TimeText(state.positionMs)
                TimeText((state.durationMs - state.positionMs).coerceAtLeast(0L), prefix = "-")
            }
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TransportButton(onClick = viewModel::previous) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
                }
                IconButton(
                    onClick = viewModel::toggle,
                    modifier = Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE9EFFF)),
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = Color(0xFF080B10),
                        modifier = Modifier.size(38.dp),
                    )
                }
                TransportButton(onClick = viewModel::next) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                }
            }
            Spacer(modifier = Modifier.height(26.dp))
            LyricStrip(
                line = state.lyrics.getOrNull(state.activeLyricIndex)?.text
                    ?: "Let the next track find the room",
                nextLine = state.lyrics.getOrNull(state.activeLyricIndex + 1)?.text
                    ?: "while Pipo keeps the fade breathing",
                onClick = { immersiveLyrics = true },
            )
            QueueStrip(
                queue = state.queue.map { it.title },
                currentIndex = state.currentIndex,
            )
            Spacer(modifier = Modifier.weight(0.62f))
        }
        ImmersiveLyricsOverlay(
            visible = immersiveLyrics,
            title = state.title,
            artist = state.artist,
            artworkUrl = state.artworkUrl,
            isPlaying = state.isPlaying,
            lyrics = state.lyrics,
            activeLyricIndex = state.activeLyricIndex,
            positionMs = state.positionMs,
            onClose = { immersiveLyrics = false },
        )
    }
}

@Composable
private fun QueueStrip(
    queue: List<String>,
    currentIndex: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        queue.take(3).forEachIndexed { index, title ->
            val active = index == currentIndex
            Text(
                text = title,
                color = if (active) PipoColors.Background else PipoColors.TextDim,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (active) PipoColors.Text else Color(0x10FFFFFF))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TransportButton(onClick: () -> Unit, icon: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(Color(0x14FFFFFF)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            icon()
        }
    }
}

@Composable
private fun NativeCover(
    artworkUrl: String?,
    isPlaying: Boolean,
) {
    val pulse by rememberInfiniteTransition(label = "coverPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isPlaying) 4200 else 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "coverPulseValue",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF8EF0C3),
                        Color(0xFF7FA7FF),
                        Color(0xFFF1C46C),
                    ),
                    start = Offset(200f * sin(pulse * PI.toFloat()), 0f),
                    end = Offset(800f, 900f * cos(pulse * PI.toFloat())),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (artworkUrl != null) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x33000000)),
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawNativeCoverMarks(pulse)
        }
        Text(
            text = "Pipo",
            color = Color(0xE60A0D12),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LyricStrip(
    line: String,
    nextLine: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .background(Color(0x12FFFFFF))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = line,
            color = PipoColors.Text,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = nextLine,
            color = PipoColors.TextDim,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ImmersiveLyricsOverlay(
    visible: Boolean,
    title: String,
    artist: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    lyrics: List<PipoLyricLine>,
    activeLyricIndex: Int,
    positionMs: Long,
    onClose: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(180)),
        exit = fadeOut(animationSpec = tween(160)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PipoColors.Background)
                .clickable(onClick = onClose),
        ) {
            AmbientNativeField(isPlaying = isPlaying)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NativeCover(
                    artworkUrl = artworkUrl,
                    isPlaying = isPlaying,
                )
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = title,
                    color = PipoColors.Text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artist,
                    color = PipoColors.TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(38.dp))
                val safeLyrics = lyrics.ifEmpty { fallbackLyrics() }
                safeLyrics.forEachIndexed { index, line ->
                    ImmersiveLyricLine(
                        line = line,
                        active = index == activeLyricIndex,
                        positionMs = positionMs,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImmersiveLyricLine(
    line: PipoLyricLine,
    active: Boolean = false,
    positionMs: Long,
) {
    if (active && line.chars.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        ) {
            line.chars.forEach { char ->
                val done = positionMs >= char.startMs + char.durationMs
                val current = positionMs in char.startMs..(char.startMs + char.durationMs)
                Text(
                    text = char.text,
                    color = when {
                        done -> PipoColors.Text
                        current -> PipoColors.Mint
                        else -> PipoColors.Text.copy(alpha = 0.34f)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    } else {
        Text(
            text = line.text,
            color = if (active) PipoColors.Text else PipoColors.Text.copy(alpha = 0.34f),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            textAlign = TextAlign.Start,
        )
    }
}

private fun fallbackLyrics(): List<PipoLyricLine> {
    return listOf(
        PipoLyricLine(0, 2800, "Let the next track find the room"),
        PipoLyricLine(2800, 2600, "and leave the noise outside"),
        PipoLyricLine(5400, 3200, "one sample into another"),
        PipoLyricLine(8600, 3000, "no gap, no rush, just motion"),
    )
}

private fun DrawScope.drawNativeCoverMarks(pulse: Float) {
    val lineColor = Color(0x66090B10)
    val w = size.width
    val h = size.height
    for (i in 0 until 9) {
        val y = h * (0.2f + i * 0.075f)
        val wave = sin((pulse * 2f * PI + i * 0.55f).toFloat()) * w * 0.045f
        drawRoundRect(
            color = lineColor,
            topLeft = Offset(w * 0.18f + wave, y),
            size = Size(w * (0.64f - i * 0.018f), 5.dp.toPx()),
            cornerRadius = CornerRadius(10.dp.toPx()),
        )
    }
}

@Composable
private fun AmbientNativeField(isPlaying: Boolean) {
    val drift by rememberInfiniteTransition(label = "field").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isPlaying) 9000 else 15000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "fieldValue",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val colors = listOf(Color(0x228EF0C3), Color(0x227FA7FF), Color(0x18F1C46C))
        colors.forEachIndexed { idx, color ->
            val a = drift * 2f * PI.toFloat() + idx * 2.1f
            drawCircle(
                color = color,
                radius = size.minDimension * (0.34f + idx * 0.08f),
                center = Offset(
                    x = size.width * (0.5f + cos(a) * 0.28f),
                    y = size.height * (0.42f + sin(a * 0.8f) * 0.24f),
                ),
            )
        }
    }
}

@Composable
private fun TimeText(ms: Long, prefix: String = "") {
    Text(
        text = prefix + formatTime(ms),
        color = Color(0x99E9EFFF),
        style = MaterialTheme.typography.labelMedium,
    )
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return "%d:%02d".format(minutes, seconds)
}
