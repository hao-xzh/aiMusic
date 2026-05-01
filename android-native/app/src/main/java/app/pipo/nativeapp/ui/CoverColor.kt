package app.pipo.nativeapp.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.scale
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面边缘色采样 + 明暗判断 —— 镜像 src/lib/cover-color.ts。
 *
 *   - 顶 / 底 / 右三边各采样 5px，平均 RGB
 *   - luma 145 阈值 → "dark"（用深色文字）/ "light"（用浅色文字）
 */
data class EdgeColors(val top: IntArray?, val bottom: IntArray?, val right: IntArray?) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

enum class Tone { Light, Dark }

@Composable
fun useCoverEdgeColors(url: String?): EdgeColors {
    val context = LocalContext.current
    var colors by remember(url) { mutableStateOf(EdgeColors(null, null, null)) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            colors = EdgeColors(null, null, null)
            return@LaunchedEffect
        }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val loader = ImageLoader.Builder(context).build()
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val res = loader.execute(request)
                (res as? SuccessResult)?.drawable?.let { drawable ->
                    val src = drawable as? android.graphics.drawable.BitmapDrawable
                    src?.bitmap
                }
            }.getOrNull()
        } ?: return@LaunchedEffect

        val sampled = withContext(Dispatchers.Default) {
            sampleEdges(bitmap)
        }
        colors = sampled
    }
    return colors
}

private fun sampleEdges(bitmap: Bitmap): EdgeColors {
    val w = 32
    val h = 32
    val small: Bitmap = bitmap.scale(w, h)
    val pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)

    fun avg(yStart: Int, yEnd: Int, xStart: Int = 0, xEnd: Int = w): IntArray? {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in yStart until yEnd) {
            val row = y * w
            for (x in xStart until xEnd) {
                val c = pixels[row + x]
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
            }
        }
        if (n == 0) return null
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    val top = avg(0, 5)
    val bottom = avg(h - 5, h)
    val right = avg(0, h, w - 5, w)
    return EdgeColors(top, bottom, right)
}

fun computeTone(rgb: IntArray?): Tone {
    if (rgb == null || rgb.size < 3) return Tone.Light
    val luma = 0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2]
    // 145 阈值同 cover-color.ts
    return if (luma > 145f) Tone.Dark else Tone.Light
}

/** 已唱字符色：full opaque，灰底封面下也能拉开对比度。
 *  之前 0xEB / 0xF5（α 0.92 / 0.96）在 luma ≈ 145 的灰封面上对比不够 → 高亮看不清 */
fun pickFg(tone: Tone): Color =
    if (tone == Tone.Dark) Color(0xFF000000) else Color(0xFFFFFFFF)

fun pickFgDim(tone: Tone): Color =
    if (tone == Tone.Dark) Color(0x8C000000) else Color(0x9EFFFFFF)

/** 未唱字符色：active 行里要明显比已唱字段暗一档，但保留可读性。
 *  之前 0x8C/0x9E（α 0.55/0.62）跟已唱字对比不够，看起来"全行差不多色"。
 *  调到 0x59/0x66（α 0.35/0.40）—— 已唱跟未唱对比更清晰。 */
fun pickFgUnsung(tone: Tone): Color =
    if (tone == Tone.Dark) Color(0x59000000) else Color(0x66FFFFFF)

fun rgbToColor(rgb: IntArray?, fallback: Color = PipoColors.Bg1): Color {
    if (rgb == null || rgb.size < 3) return fallback
    return Color(red = rgb[0] / 255f, green = rgb[1] / 255f, blue = rgb[2] / 255f)
}

/**
 * 灰封面"看不清"补偿：只在 sampled 边缘色 luma 接近 tone 阈值（145）时启动，
 * 其他色调返回完全透明，不影响视觉。
 *
 *   - luma 远离 145（>210 / <80）→ Transparent，浅色 / 深色封面完全不动
 *   - luma 接近 145 → 输出一个对比 scrim：
 *       · luma > 145（黑字 tone=Dark）→ 浅色 scrim 把歌词区 bg 进一步拉亮，黑字更清
 *       · luma ≤ 145（白字 tone=Light）→ 深色 scrim 把歌词区 bg 进一步压暗，白字更清
 *   - alpha 按距 145 的远近线性插值，最强 0.42
 */
fun pickContrastScrim(rgb: IntArray?): Color {
    if (rgb == null || rgb.size < 3) return Color.Transparent
    val luma = 0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2]
    val dangerCenter = 145f
    val dangerWidth = 65f
    val dist = kotlin.math.abs(luma - dangerCenter)
    if (dist >= dangerWidth) return Color.Transparent
    val intensity = 1f - dist / dangerWidth
    val alpha = intensity * 0.42f
    return if (luma > dangerCenter) {
        Color(1f, 1f, 1f, alpha)
    } else {
        Color(0f, 0f, 0f, alpha)
    }
}
