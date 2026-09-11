package app.pipo.nativeapp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Expands a bounded mini-player surface without stretching the full-player controls. */
@Composable
internal fun PlayerExpansionSurface(
    expanded: Boolean,
    miniBounds: Rect,
    artworkUrl: String?,
    canCollapse: Boolean,
    isLandscape: Boolean,
    onReturnPortrait: (() -> Unit)?,
    onCollapse: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAi: (() -> Unit)?,
    compactContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val latestCollapse by rememberUpdatedState(onCollapse)
    val latestOpenAi by rememberUpdatedState(onOpenAi)
    LaunchedEffect(expanded, dragging) {
        if (!dragging) progress.animateTo(
            if (expanded) 1f else 0f,
            spring(dampingRatio = 0.94f, stiffness = if (expanded) 300f else 380f),
        )
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().zIndex(1f)
            // Keep playback state composed, but remove collapsed controls from hit testing.
            .layout { measurable, constraints ->
                val child = measurable.measure(constraints)
                layout(child.width, child.height) {
                    if (progress.value > 0f) child.placeRelative(0, 0)
                }
            }
            .graphicsLayer {
                clip = true
                shape = ExpandingPlayerShape(miniBounds, progress.value)
            }
            // Parent gestures only receive unconsumed taps; child buttons retain their handlers.
            .pointerInput(onOpenAi != null) {
                detectTapGestures(onTap = {}, onDoubleTap = if (onOpenAi != null) {
                    { latestOpenAi?.invoke() }
                } else null)
            }
            .background(Color(0xFF142130)),
    ) {
        val viewportHeight = constraints.maxHeight.toFloat()
        val dragModifier = if (canCollapse && expanded) Modifier.pointerInput(viewportHeight) {
            val velocity = VelocityTracker()
            detectVerticalDragGestures(
                onDragStart = { dragging = true; velocity.resetTracking() },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    velocity.addPosition(change.uptimeMillis, change.position)
                    scope.launch { progress.snapTo((progress.value - amount / viewportHeight).coerceIn(0f, 1f)) }
                },
                onDragEnd = {
                    val dismiss = progress.value < 0.84f ||
                        (progress.value < 0.97f && velocity.calculateVelocity().y > 1000f)
                    if (dismiss) latestCollapse()
                    dragging = false
                },
                onDragCancel = { dragging = false },
            )
        } else Modifier
        Box(Modifier.fillMaxSize().then(dragModifier).graphicsLayer {
            val p = progress.value.coerceIn(0f, 1f)
            // The surface carries the expansion; content only settles a short distance.
            translationY = 48.dp.toPx() * (1f - p)
            scaleX = 0.96f + 0.04f * p
            scaleY = scaleX
            val reveal = ((p - 0.12f) / 0.78f).coerceIn(0f, 1f)
            alpha = reveal * reveal * (3f - 2f * reveal)
        }) {
            content()
            if (onReturnPortrait != null) {
                Box(Modifier.fillMaxWidth().statusBarsPadding().height(48.dp)) {
                    IconButton(onClick = onReturnPortrait,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(44.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回竖屏", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            } else if (!isLandscape && (canCollapse || (!expanded && progress.value > 0.001f))) {
                val controlEdges = useCoverEdgeColors(artworkUrl)
                val controlColor = pickFg(toneForColor(appleMusicPureSurfaceColor(controlEdges)))
                Box(Modifier.fillMaxWidth().statusBarsPadding().height(48.dp)) {
                    IconButton(
                        onClick = latestCollapse,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)
                            .size(44.dp).semantics { contentDescription = "收起播放器" },
                    ) {
                        Canvas(Modifier.size(28.dp)) {
                            val chevron = Path().apply {
                                moveTo(size.width * 0.18f, size.height * 0.36f)
                                lineTo(size.width * 0.50f, size.height * 0.66f)
                                lineTo(size.width * 0.82f, size.height * 0.36f)
                            }
                            drawPath(chevron, controlColor, style = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                            .size(44.dp).semantics { contentDescription = "设置" },
                    ) {
                        GearIcon(color = controlColor, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        if (progress.value < 0.28f && miniBounds.width > 0f) {
            val density = LocalDensity.current
            Box(Modifier.offset {
                IntOffset((miniBounds.left * (1f - progress.value)).roundToInt(),
                    (miniBounds.top * (1f - progress.value)).roundToInt())
            }.width(with(density) { miniBounds.width.toDp() }).graphicsLayer {
                alpha = (1f - progress.value / 0.28f).coerceIn(0f, 1f)
            }) { compactContent() }
        }
    }
}

private class ExpandingPlayerShape(private val origin: Rect, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val start = if (origin.width > 0f && origin.height > 0f) origin else
            Rect(size.width * 0.04f, size.height * 0.85f, size.width * 0.96f, size.height * 0.93f)
        val p = progress.coerceIn(0f, 1f)
        val radius = with(density) { PipoDimens.SurfaceCornerDp.toPx() } * (1f - p)
        return Outline.Rounded(RoundRect(
            left = start.left * (1f - p), top = start.top * (1f - p),
            right = start.right + (size.width - start.right) * p,
            bottom = start.bottom + (size.height - start.bottom) * p,
            cornerRadius = CornerRadius(radius),
        ))
    }
}
