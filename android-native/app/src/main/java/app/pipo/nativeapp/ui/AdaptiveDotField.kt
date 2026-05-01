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
 * 镜像 src/components/AdaptiveDotField.tsx：
 *   - 底层：模糊封面（CrossfadeCoverImage 处理切歌 1100ms 溶解过渡）
 *   - 中层：暗化压底渐变
 *   - 上层：DotField 浅色颗粒
 */
@Composable
fun AdaptiveDotField(
    coverUrl: String?,
    isPlaying: Boolean,
    showDots: Boolean = true,
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

        if (showDots) {
            DotField(
                playing = isPlaying && coverUrl != null,
                modifier = Modifier.fillMaxSize(),
            )
        }
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

    // React `blur(70px) saturate(1.3)` —— Compose `Modifier.blur` 单层上限 25dp，
    // 这里"叠两层 + 高 scale 让 sampling 区域扩大"近似 React 70dp 的视觉烈度。
    // 嵌套两个 Box 各 blur(25.dp) 等价于一次 ~50dp 的高斯，配合 scale 1.10 让封面
    // 边缘"溢出 inset:-80"区域，整体观感跟 React 的 saturate + brightness 接近。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha)
            .blur(25.dp), // 第二层
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(scale)
                .blur(25.dp), // 第一层
        ) {
            CrossfadeCoverImage(
                url = coverUrl,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                durationMs = PipoMotion.CoverFadeMs,
            )
        }
    }
}
