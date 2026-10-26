package app.pipo.nativeapp.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pipo.nativeapp.data.coverImageRequest
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val SoftEase = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

private data class LoadedCover(val url: String?, val painter: Painter?)

internal val LocalCoverTransitionVisible = staticCompositionLocalOf { true }

/** 可见时在新图解码后过渡；不可见期间直接同步，恢复页面不补播旧封面动画。 */
@Composable
fun CrossfadeCoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    durationMs: Int = 720,
    maxDecodeSizePx: Int? = 960,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isVisible = LocalCoverTransitionVisible.current
    val latestVisible by rememberUpdatedState(isVisible)
    val latestUrl by rememberUpdatedState(url)
    val latestDuration by rememberUpdatedState(durationMs)
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(LoadedCover(null, null)) }
    var previous by remember { mutableStateOf<Painter?>(null) }
    var progress by remember { mutableFloatStateOf(1f) }
    var animationJob by remember { mutableStateOf<Job?>(null) }
    var needsSync by remember { mutableStateOf(true) }
    var wasActive by remember { mutableStateOf(false) }
    var immediateUrl by remember { mutableStateOf(url) }
    val isActive = isVisible && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    val model = remember(context, url, maxDecodeSizePx) {
        coverImageRequest(context, url, maxDecodeSizePx)
    }

    fun finishTransition() {
        animationJob?.cancel()
        animationJob = null
        previous = null
        progress = 1f
    }

    fun showCover(next: LoadedCover) {
        if (next.url != latestUrl || next == current) return
        val animateChange = latestVisible && wasActive && !needsSync && immediateUrl != next.url &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        // 连续切歌从当前占主导的图接入最新结果，不排队等待历史动画。
        val outgoing = if (previous != null && progress < 0.5f) previous else current.painter
        finishTransition()
        current = next
        if (animateChange && outgoing != null && latestDuration > 0) {
            previous = outgoing
            progress = 0f
            animationJob = scope.launch {
                animate(0f, 1f, animationSpec = tween(latestDuration, easing = SoftEase)) { value, _ ->
                    progress = value
                }
                previous = null
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_RESUME) {
                // 生命周期回调直接结束动画，不依赖后台已经暂停的 Compose 帧时钟。
                needsSync = true
                finishTransition()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            animationJob?.cancel()
        }
    }
    SideEffect {
        if (!isActive || !wasActive || needsSync) {
            // 即使这首歌的图到恢复后才解码成功，也只直接显示这一次同步的目标。
            immediateUrl = url
            needsSync = false
            finishTransition()
        } else if (immediateUrl != url) {
            immediateUrl = null
        }
        wasActive = isActive
        if (url == null) showCover(LoadedCover(null, null))
    }
    // 恢复时新图尚未就绪，不把后台前留下的封面重新画到当前歌曲上。
    val showCurrent = current.url == url || (isActive && wasActive && !needsSync && immediateUrl != url)
    Box(modifier = modifier) {
        // 收到成功回调前不让新请求覆盖可见层，并核对结果所属 URL。
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0f },
            onSuccess = { state ->
                if (state.result.request.data == latestUrl) showCover(LoadedCover(latestUrl, state.painter))
            },
            onError = { state ->
                if (state.result.request.data == latestUrl) showCover(LoadedCover(latestUrl, null))
            },
        )
        previous?.takeIf { showCurrent }?.let { painter ->
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    // 不同时降低两层透明度，避免混合中途露底、发暗。
                    alpha = if (current.painter == null) 1f - progress else 1f
                },
            )
        }
        current.painter?.takeIf { showCurrent }?.let { painter ->
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = progress },
            )
        }
    }
}
