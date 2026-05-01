package app.pipo.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 跨状态共享的"封面"。镜像 src/components/PlayerCard.tsx 的 ImmersiveLyrics
 * FLIP 入场：同一个 Box 在屏幕坐标系里，从 compact rect 物理形变到 immersive rect。
 *
 *   - compactRect: PlayerScreen 里 `Modifier.reportCoverRect()` 上报的 window rect
 *   - immersive 目标：(left=0, top=0, w=screenW, h=screenW) —— 顶部全宽 1:1 方块
 *   - progress: 0=compact，1=immersive，PipoNativeApp 用 animateFloatAsState 驱动
 *     · 0→1 用 620ms cubic-bezier(0.32, 0.72, 0, 1)（FlipEase）
 *     · 1→0 用 540ms cubic-bezier(0.6, 0.04, 0.22, 1)（CloseEase）
 *
 * 渲染层级：在 ImmersiveBackdrop 之上、歌词文字之下。
 */
@Composable
fun TransitioningCover(
    compactRect: Rect?,
    coverUrl: String?,
    progress: Float,
) {
    if (compactRect == null) return
    if (progress <= 0.001f && coverUrl == null) return

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWPx = with(density) { configuration.screenWidthDp.dp.toPx() }

    // 目标矩形（immersive）：顶部贴屏幕、左右贴屏幕、square
    val targetLeft = 0f
    val targetTop = 0f
    val targetW = screenWPx
    val targetH = screenWPx

    // 线性插值 compact ↔ immersive
    val left = compactRect.left + (targetLeft - compactRect.left) * progress
    val top = compactRect.top + (targetTop - compactRect.top) * progress
    val w = compactRect.width + (targetW - compactRect.width) * progress
    val h = compactRect.height + (targetH - compactRect.height) * progress

    // 圆角同步收：compact 12dp → immersive 0dp
    val cornerDp = (12f * (1f - progress)).coerceAtLeast(0f).dp

    val widthDp = with(density) { w.toDp() }
    val heightDp = with(density) { h.toDp() }

    // 底部 fade mask 跟 progress 同步：compact 时不做 mask（封面完整显示）；
    // immersive 时底部大段渐隐到透明，让封面"溶进"下方同源重模糊背景，
    // 跟 Mac PlayerCard 那种"封面和背景是一张图"的整体感一致。
    val maskStrength = progress

    // ⚠ .background 必须在 .graphicsLayer { Offscreen } 之"内"才能跟 mask 一起被裁。
    // 之前 .background 在 .graphicsLayer 之前 = 黑底直接画到屏幕，mask 透明区只能
    // 露出黑色（看起来"封面没融合到背景"）。现在去掉外部 background，把它挪到内层。
    Box(
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(widthDp, heightDp)
            .clip(RoundedCornerShape(cornerDp))
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (maskStrength > 0.001f) {
                    // 底部从 55% 开始就慢慢溶进背景（之前 68% 才开始，过渡只剩 32%
                    // 视觉上像"硬边 + 一小段模糊带"）；现在 45% 的渐隐带 + 顶部
                    // 也加一点入溶，整张图融化得更自然。
                    drawRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Black.copy(alpha = 1f - 0.45f * maskStrength),
                                0.06f to Color.Black,
                                0.55f to Color.Black,
                                0.72f to Color.Black.copy(alpha = 1f - 0.45f * maskStrength),
                                0.86f to Color.Black.copy(alpha = 1f - 0.80f * maskStrength),
                                1f to Color.Black.copy(alpha = 1f - 0.98f * maskStrength),
                            ),
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    ) {
        if (coverUrl != null) {
            CrossfadeCoverImage(
                url = coverUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF11151D)),  // 兜底色在 offscreen 层之内，跟 mask 一起裁
                contentScale = ContentScale.Crop,
                durationMs = 520,
            )
        }
    }
}
