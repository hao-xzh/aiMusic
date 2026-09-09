package app.pipo.nativeapp.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The same material across the app; only image-backed surfaces need a sampled blur. */
@Composable
internal fun BrowseGlassSurface(
    modifier: Modifier = Modifier,
    backdropBounds: Rect = Rect.Zero,
    backdrop: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val shape = RoundedCornerShape(PipoDimens.SurfaceCornerDp)
    Box(modifier.fillMaxWidth().onGloballyPositioned { bounds = it.boundsInRoot() }.clip(shape)
        .border(0.7.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), PipoColors.GlassStroke)), shape)) {
        if (backdrop != null && backdropBounds.width > 0f && bounds.width > 0f) {
            Box(Modifier.matchParentSize()) {
                Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                    .offset { IntOffset((backdropBounds.left - bounds.left).roundToInt(), (backdropBounds.top - bounds.top).roundToInt()) }
                    .requiredSize(with(density) { backdropBounds.width.toDp() }, with(density) { backdropBounds.height.toDp() })
                    .blur(PipoDimens.GlassBlurDp)) { backdrop() }
            }
        }
        val tint = if (backdrop != null && Build.VERSION.SDK_INT < 31) PipoColors.GlassFill.copy(alpha = 0.82f) else PipoColors.GlassFill
        Box(Modifier.matchParentSize().background(tint))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.09f), Color.Transparent))))
        content()
    }
}
