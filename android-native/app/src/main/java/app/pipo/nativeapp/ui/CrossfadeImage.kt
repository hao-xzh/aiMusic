package app.pipo.nativeapp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.pipo.nativeapp.data.coverImageRequest
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.collect

private val SoftEase = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

private data class LoadedCover(val url: String?, val painter: Painter?)

/** 新图解码完成后才过渡；命中缓存也执行动画，加载期间保留已显示的封面。 */
@Composable
fun CrossfadeCoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    durationMs: Int = 720,
    maxDecodeSizePx: Int? = 960,
) {
    val context = LocalContext.current
    val latestUrl by rememberUpdatedState(url)
    val latestDuration by rememberUpdatedState(durationMs)
    var loaded by remember { mutableStateOf(LoadedCover(null, null)) }
    var current by remember { mutableStateOf(LoadedCover(null, null)) }
    var previous by remember { mutableStateOf<Painter?>(null) }
    val progress = remember { Animatable(1f) }
    val model = remember(context, url, maxDecodeSizePx) {
        coverImageRequest(context, url, maxDecodeSizePx)
    }
    LaunchedEffect(url) {
        if (url == null) loaded = LoadedCover(null, null)
    }
    LaunchedEffect(Unit) {
        // 完成正在显示的两层混合，再接最新结果；连续切歌不重置到半途消失的旧图。
        snapshotFlow { loaded }.collect { next ->
            if (next.url != latestUrl || next == current) return@collect
            previous = current.painter
            current = next
            progress.snapTo(0f)
            progress.animateTo(1f, tween(latestDuration.coerceAtLeast(0), easing = SoftEase))
            previous = null
        }
    }
    Box(modifier = modifier) {
        // 收到成功回调前不让新请求覆盖可见层，并核对结果所属 URL。
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0f },
            onSuccess = { state ->
                if (state.result.request.data == latestUrl) loaded = LoadedCover(latestUrl, state.painter)
            },
            onError = { state ->
                if (state.result.request.data == latestUrl) loaded = LoadedCover(latestUrl, null)
            },
        )
        previous?.let { painter ->
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    // 不同时降低两层透明度，避免混合中途露底、发暗。
                    alpha = if (current.painter == null) 1f - progress.value else 1f
                },
            )
        }
        current.painter?.let { painter ->
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = progress.value },
            )
        }
    }
}
