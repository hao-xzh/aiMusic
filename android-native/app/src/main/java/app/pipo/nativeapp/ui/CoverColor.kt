package app.pipo.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面边缘色采样 + 明暗判断 —— 镜像 src/lib/cover-color.ts。
 *
 *   - 顶 / 底 / 左 / 右边各采样 5px，平均 RGB
 *   - luma 145 阈值 → "dark"（用深色文字）/ "light"（用浅色文字）
 */
data class EdgeColors(
    val top: IntArray?,
    val bottom: IntArray?,
    val right: IntArray?,
    val left: IntArray? = null,
    // 封面主色调（最鲜艳的一簇颜色）—— 用来给歌词扫描交界处染"随歌曲变化的微光"。
    // 灰度封面提不出主色时为 null（此时歌词不染色，保持纯白/黑）。
    val accent: IntArray? = null,
) {
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
                // 之前每次切歌都 ImageLoader.Builder(context).build() 新建一个 loader,
                // Coil ImageLoader 内部带线程池 + 内存/磁盘缓存,是重对象。频繁切歌时
                // 旧 loader 持有 native bitmap 不释放 → 长时间听歌内存稳定上涨。
                // 用 Coil 的全局单例 —— ImageLoader 全 app 共享一份,缓存命中率也更高。
                val loader = Coil.imageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val res = loader.execute(request)
                (res as? SuccessResult)?.drawable?.let { drawable ->
                    (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        ?: drawable.toBitmap(
                            width = drawable.intrinsicWidth.coerceAtLeast(64),
                            height = drawable.intrinsicHeight.coerceAtLeast(64),
                            config = Config.ARGB_8888,
                        )
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
    val left = avg(0, h, 0, 5)
    val accent = extractVibrant(pixels)
    if (small !== bitmap) small.recycle()
    return EdgeColors(top, bottom, right, left, accent)
}

/**
 * 从缩略图里提取"最鲜艳的一簇颜色"作为封面主色调。
 *   - 跳过太灰（饱和度低）、太暗、太亮的像素，按饱和度加权平均剩下的。
 *   - 灰度封面（几乎没有彩色像素）→ 返回 null（歌词不染色）。
 */
private fun extractVibrant(pixels: IntArray): IntArray? {
    val hsv = FloatArray(3)
    var rSum = 0.0; var gSum = 0.0; var bSum = 0.0; var wSum = 0.0
    for (c in pixels) {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val s = hsv[1]
        val v = hsv[2]
        if (s < 0.35f || v < 0.25f || v > 0.96f) continue
        // 权重偏向高饱和 + 中等明度，避开发白/发黑的像素。
        val w = (s * (1f - kotlin.math.abs(v - 0.62f))).toDouble()
        rSum += r * w; gSum += g * w; bSum += b * w; wSum += w
    }
    if (wSum < 0.5) return null
    return intArrayOf((rSum / wSum).toInt(), (gSum / wSum).toInt(), (bSum / wSum).toInt())
}

/**
 * 歌词扫描交界处的"封面色微光"。把主色调拉到统一的鲜艳度/明度，深浅主题下都看得见；
 * 灰度封面没有可采样 accent 时回落到品牌薄荷色，保证中间色不会消失。
 */
fun lyricAccent(accent: IntArray?): Color {
    if (accent == null || accent.size < 3) return PipoColors.Accent
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(accent[0], accent[1], accent[2], hsv)
    // 提饱和、定明度，让交界处的微光是一抹明确的彩色，而不是灰扑扑的。
    hsv[1] = (hsv[1] * 1.15f).coerceIn(0.55f, 1f)
    hsv[2] = hsv[2].coerceIn(0.70f, 0.92f)
    return Color(android.graphics.Color.HSVToColor(hsv))
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

/** 未唱字符色：YRC/慢词/普通逐字都从这里取底色。
 *  Apple Music 的"灰一档"观感：浅底（白字）压到 ~0.44，深底（黑字）压到 ~0.40，
 *  跟已唱字符（fg=1.0）形成清晰对比；同时守住 distance=1 行 rowAlpha(0.36) < fgUnsung
 *  的红线（未来邻句不会比 active 行未唱字符更亮）。 */
fun pickFgUnsung(tone: Tone): Color =
    if (tone == Tone.Dark) Color(0x66000000) else Color(0x70FFFFFF)

fun rgbToColor(rgb: IntArray?, fallback: Color = PipoColors.Bg1): Color {
    if (rgb == null || rgb.size < 3) return fallback
    return Color(red = rgb[0] / 255f, green = rgb[1] / 255f, blue = rgb[2] / 255f)
}
