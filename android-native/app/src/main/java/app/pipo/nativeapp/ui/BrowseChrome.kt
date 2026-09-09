package app.pipo.nativeapp.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.playback.PlayerViewModel
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected

internal val LocalBrowseBottomInset = staticCompositionLocalOf { 0.dp }

private val LocalMiniPlayerInk = staticCompositionLocalOf { Color(0xFFF3F5F7) }

internal enum class BrowseTab(val label: String) { Home("首页"), Library("资料库") }

/** Browsing has its own bottom inset; the full player keeps its existing layout. */
@Composable
internal fun BrowseChrome(
    selected: BrowseTab,
    player: PlayerViewModel,
    onSelect: (BrowseTab) -> Unit,
    onPlayer: () -> Unit,
    onQueue: () -> Unit,
    onMiniBounds: (Rect) -> Unit = {},
    showMiniPlayer: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        if (showMiniPlayer) BrowseMiniPlayerDock(player, onPlayer, onQueue, onMiniBounds)
        Row(Modifier.fillMaxWidth().background(Color(0xF2070B12)).navigationBarsPadding(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BrowseTab.entries.forEach { tab ->
                val tint = if (selected == tab) Color(0xFFF3F5F7) else Color(0xFF939DA8)
                Column(Modifier.weight(1f).semantics { this.selected = selected == tab }.clickable { onSelect(tab) }.padding(top = 10.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (tab == BrowseTab.Home) Icons.Rounded.Home else Icons.Rounded.LibraryMusic, tab.label, tint = tint, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(tab.label, color = tint, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
internal fun BrowseMiniPlayerDock(
    player: PlayerViewModel,
    onPlayer: () -> Unit,
    onQueue: () -> Unit,
    onMiniBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (player.state.currentTrackId == null) return
    MiniPlayerSurface(
        artworkUrl = player.state.artworkUrl,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            .onGloballyPositioned { onMiniBounds(it.boundsInRoot()) }
            .clickable(onClick = onPlayer),
    ) { BrowseMiniPlayerContents(player, onQueue) }
}

@Composable
internal fun MiniPlayerSurface(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val edges = useCoverEdgeColors(artworkUrl)
    val rgb = edges.accent ?: edges.ambient ?: edges.seam
    val surface = if (rgb != null) {
        val source = Color(rgb[0], rgb[1], rgb[2])
        androidx.compose.ui.graphics.lerp(source, Color.Black, 0.68f)
    } else Color(0xFF10151B)
    // The same accent extraction as the lyrics page, lightened for readable small text.
    val accent = lyricAccent(rgb).takeIf { it != Color.Transparent } ?: BrowseInk
    val targetInk = generateSequence(accent) { lerp(it, Color.White, 0.12f) }
        .first { (it.luminance() + 0.05f) / (surface.luminance() + 0.05f) >= 4.5f }
    val animatedSurface by animateColorAsState(surface, tween(PipoMotion.CoverFadeMs), label = "miniSurface")
    val ink by animateColorAsState(targetInk, tween(PipoMotion.CoverFadeMs), label = "miniInk")
    val shape = RoundedCornerShape(PipoDimens.SurfaceCornerDp)
    CompositionLocalProvider(LocalMiniPlayerInk provides ink) {
        Box(modifier.fillMaxWidth().clip(shape).background(animatedSurface).border(0.5.dp, ink.copy(alpha = 0.30f), shape)) {
            content()
        }
    }
}

@Composable
internal fun BrowseMiniPlayerContents(player: PlayerViewModel, onQueue: () -> Unit) {
    val state = player.state
    val ink = LocalMiniPlayerInk.current
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CrossfadeCoverImage(
                url = state.artworkUrl,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(PipoDimens.ThumbnailCornerDp)),
                contentScale = ContentScale.Crop,
                durationMs = 720,
                maxDecodeSizePx = 160,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(state.title, color = ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.artist, color = ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = player::toggle, modifier = Modifier.semantics { contentDescription = if (state.isPlaying) "暂停" else "播放" }) {
                if (state.isPlaying) PauseGlyph(ink, Modifier.size(26.dp)) else PlayGlyph(ink, Modifier.size(26.dp))
            }
            IconButton(onClick = onQueue, modifier = Modifier.semantics { contentDescription = "播放队列" }) { ListIcon(ink, Modifier.size(26.dp)) }
        }
        val progress = if (state.durationMs > 0) (player.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
        Box(
            Modifier.padding(start = 12.dp, end = 12.dp, bottom = 7.dp)
                .fillMaxWidth().height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(ink.copy(alpha = 0.16f))
                .progressSemantics(progress),
        ) {
            Box(
                Modifier.fillMaxWidth(progress).fillMaxHeight()
                    .clip(RoundedCornerShape(1.dp))
                    .background(ink),
            )
        }
    }
}
