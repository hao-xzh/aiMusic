package app.pipo.nativeapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pipo.nativeapp.R
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.roundToInt

@Composable
internal fun PlaylistDetailBackdrop(
    coverUrl: String?,
    edges: EdgeColors,
    showTopCover: Boolean,
    topCoverHeight: Dp,
    topCoverOffsetPx: Float,
) {
    val surfaceColor = appleMusicPureSurfaceColor(edges)
    val bridgeColor = appleMusicDissolveBridgeColor(edges, fallback = surfaceColor)
    val topColor = appleMusicPureTopColor(edges, fallback = bridgeColor)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to topColor,
                        0.30f to bridgeColor,
                        0.54f to lerp(bridgeColor, surfaceColor, 0.28f),
                        0.76f to lerp(bridgeColor, surfaceColor, 0.72f),
                        1.00f to surfaceColor,
                    ),
                ),
            ),
    ) {
        PlaylistDetailLowerGlassWash(coverUrl = coverUrl)
        PlaylistDetailTopCover(
            coverUrl = coverUrl,
            height = topCoverHeight,
            offsetPx = topCoverOffsetPx,
            visible = showTopCover,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.50f to Color.Transparent,
                            0.74f to surfaceColor.copy(alpha = 0.20f),
                            1.00f to surfaceColor.copy(alpha = 0.52f),
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun PlaylistDetailLowerGlassWash(coverUrl: String?) {
    if (coverUrl == null) return
    Box(modifier = Modifier.fillMaxSize()) {
        InstantBackdropCoverImage(
            url = coverUrl,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 0.30f
                    scaleX = 1.78f
                    scaleY = 1.78f
                }
                .blur(64.dp),
            contentScale = ContentScale.Crop,
            maxDecodeSizePx = 960,
        )
    }
}

@Composable
private fun InstantBackdropCoverImage(
    url: String,
    modifier: Modifier,
    contentScale: ContentScale,
    maxDecodeSizePx: Int,
) {
    val context = LocalContext.current
    val model = remember(context, url, maxDecodeSizePx) {
        ImageRequest.Builder(context)
            .data(url)
            .size(maxDecodeSizePx, maxDecodeSizePx)
            .memoryCacheKey("cover:$maxDecodeSizePx:$url")
            .diskCacheKey(url)
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    )
}

@Composable
private fun PlaylistDetailTopCover(
    coverUrl: String?,
    height: Dp,
    offsetPx: Float,
    visible: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .offset { IntOffset(0, offsetPx.roundToInt()) }
            .graphicsLayer {
                alpha = if (visible) 1f else 0f
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black,
                            0.48f to Color.Black,
                            0.64f to Color.Black.copy(alpha = 0.92f),
                            0.80f to Color.Black.copy(alpha = 0.54f),
                            0.94f to Color.Black.copy(alpha = 0.13f),
                            1.00f to Color.Transparent,
                        ),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        if (coverUrl != null) {
            InstantBackdropCoverImage(
                url = coverUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.025f
                        scaleY = 1.025f
                    }
                    .background(Color(0xFF11151D)),
                contentScale = ContentScale.Crop,
                maxDecodeSizePx = 960,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                PipoColors.Accent.copy(alpha = 0.18f),
                                PipoColors.Bg1,
                            ),
                        ),
                    ),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_round),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.22f),
                )
            }
        }
    }
}
