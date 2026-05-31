package app.pipo.nativeapp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * AdaptiveDotField —— 当前曲目封面驱动的全屏背景。
 *
 *   - 底层：模糊封面（CrossfadeCoverImage 处理切歌 1100ms 溶解过渡）
 *   - 中层：暗化压底渐变
 *
 * 原本上层还有一层 DotField 游动点阵，因常驻全屏 60fps 重绘、拖着底层模糊每帧重合成，
 * 是播放期发热主力，已整体移除（连带设置里的"隐藏点阵"开关）。背景现在是静态的。
 */
@Composable
fun AdaptiveDotField(
    coverUrl: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PipoColors.Bg0),
    ) {
        BlurredCoverLayer(coverUrl = coverUrl)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x73050709), // 0.45 alpha 黑
                            Color(0x9E050709), // 0.62 alpha 黑
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun BlurredCoverLayer(coverUrl: String?) {
    val targetAlpha = if (coverUrl != null) 0.78f else 0f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = PipoMotion.CoverFadeMs),
        label = "coverAlpha",
    )
    val targetScale = if (coverUrl != null) 1.10f else 1.15f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = PipoMotion.CoverFadeMs),
        label = "coverScale",
    )
    if (coverUrl == null) return

    // React `blur(70px) saturate(1.3)` —— Compose `Modifier.blur` 单层上限 25dp。
    // 原本叠两层 blur(25dp) 近似 ~50dp 高斯，但这张全屏模糊纹理被上层 DotField 每帧
    // 拖着重新合成，两层 = 两张全屏 RenderEffect 纹理每帧重采样，是播放期 GPU 发热主力。
    // 收成单层 blur(25dp) + scale 1.10 扩大 sampling，视觉略锐但去掉一整层全屏混合开销。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha)
            .scale(scale)
            .blur(25.dp),
    ) {
        CrossfadeCoverImage(
            url = coverUrl,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            durationMs = PipoMotion.CoverFadeMs,
        )
    }
}
