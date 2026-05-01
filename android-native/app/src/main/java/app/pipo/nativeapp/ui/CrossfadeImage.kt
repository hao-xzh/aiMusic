package app.pipo.nativeapp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

/**
 * 双层 cross-fade 封面 —— 镜像 src/components/PlayerCard.tsx CoverImageLayer。
 *
 * 切 url 时：
 *   - 旧 url 在 prev 层从 alpha 1 淡到 0
 *   - 新 url 在 cur 层从 alpha 0 淡到 1（同时 scale 1.018→1）
 *   - 720ms cubic-bezier(0.22, 1, 0.36, 1)
 *
 * 关键：每次 url 变化用 `key(url)` 强制 Animatable 重新创建，避免之前那一版用
 * phaseKey + floor() 算 frac 在动画结束瞬间归 0 导致新封面消失的 bug。
 */
private val SoftEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
fun CrossfadeCoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    durationMs: Int = 720,
) {
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var previousUrl by remember { mutableStateOf<String?>(null) }

    if (url != currentUrl) {
        previousUrl = currentUrl
        currentUrl = url
    }

    Box(modifier = modifier) {
        // 旧封面 alpha 1 → 0（同时 scale 不动）
        previousUrl?.let { prev ->
            key(prev) {
                FadingImage(
                    url = prev,
                    contentScale = contentScale,
                    durationMs = durationMs,
                    initialAlpha = 1f,
                    targetAlpha = 0f,
                    initialScale = 1f,
                    targetScale = 1f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // 新封面 alpha 0 → 1，scale 1.018 → 1
        currentUrl?.let { cur ->
            key(cur) {
                FadingImage(
                    url = cur,
                    contentScale = contentScale,
                    durationMs = durationMs,
                    initialAlpha = 0f,
                    targetAlpha = 1f,
                    initialScale = 1.018f,
                    targetScale = 1f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun FadingImage(
    url: String,
    contentScale: ContentScale,
    durationMs: Int,
    initialAlpha: Float,
    targetAlpha: Float,
    initialScale: Float,
    targetScale: Float,
    modifier: Modifier = Modifier,
) {
    val alpha = remember { Animatable(initialAlpha) }
    val scale = remember { Animatable(initialScale) }
    LaunchedEffect(Unit) {
        alpha.animateTo(targetAlpha, animationSpec = tween(durationMs, easing = SoftEase))
    }
    LaunchedEffect(Unit) {
        scale.animateTo(targetScale, animationSpec = tween(durationMs, easing = SoftEase))
    }
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier.graphicsLayer {
            this.alpha = alpha.value
            scaleX = scale.value
            scaleY = scale.value
        },
    )
}
