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
 *   - 顶 / 底 / 左 / 右边取边缘 + 内圈带，找稳定主底色而不是硬平均
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
    // 全图稳定底色：用于沉浸页背景/封面融合。它偏向大面积、低噪声颜色，
    // 避免黑字、贴纸、局部高饱和块把背景揉脏。
    val ambient: IntArray? = null,
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
    val w = 48
    val h = 48
    val small: Bitmap = bitmap.scale(w, h)
    val pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)

    fun dominant(
        yStart: Int,
        yEnd: Int,
        xStart: Int = 0,
        xEnd: Int = w,
        edge: EdgeSide? = null,
    ): IntArray? {
        val bucketWeight = DoubleArray(16 * 16 * 16)
        val bucketR = DoubleArray(bucketWeight.size)
        val bucketG = DoubleArray(bucketWeight.size)
        val bucketB = DoubleArray(bucketWeight.size)
        for (y in yStart.coerceIn(0, h) until yEnd.coerceIn(0, h)) {
            val row = y * w
            for (x in xStart.coerceIn(0, w) until xEnd.coerceIn(0, w)) {
                val c = pixels[row + x]
                val alpha = (c ushr 24) and 0xFF
                if (alpha < 16) continue
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                val chroma = maxOf(r, g, b) - minOf(r, g, b)
                val distanceWeight = when (edge) {
                    EdgeSide.Top -> 1.0 - (y.toDouble() / h.toDouble()) * 0.42
                    EdgeSide.Bottom -> 1.0 - ((h - 1 - y).toDouble() / h.toDouble()) * 0.42
                    EdgeSide.Left -> 1.0 - (x.toDouble() / w.toDouble()) * 0.42
                    EdgeSide.Right -> 1.0 - ((w - 1 - x).toDouble() / w.toDouble()) * 0.42
                    null -> 1.0
                }.coerceIn(0.58, 1.0)
                // 黑字/纯白高光/高饱和小贴纸通常面积不大但很抢眼，降低它们对"背景底色"
                // 的投票权；真正大面积的强色仍会靠面积胜出。
                val noiseWeight = when {
                    luma < 28.0 -> 0.28
                    luma > 246.0 -> 0.46
                    chroma > 156 -> 0.68
                    else -> 1.0
                }
                val weight = distanceWeight * noiseWeight
                val idx = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                bucketWeight[idx] += weight
                bucketR[idx] += r * weight
                bucketG[idx] += g * weight
                bucketB[idx] += b * weight
            }
        }
        var best = -1
        var bestWeight = 0.0
        for (i in bucketWeight.indices) {
            if (bucketWeight[i] > bestWeight) {
                best = i
                bestWeight = bucketWeight[i]
            }
        }
        if (best < 0 || bestWeight <= 0.0) return null
        return intArrayOf(
            (bucketR[best] / bestWeight).toInt(),
            (bucketG[best] / bestWeight).toInt(),
            (bucketB[best] / bestWeight).toInt(),
        )
    }

    val band = 14
    val top = dominant(0, band, edge = EdgeSide.Top)
    val bottom = dominant(h - band, h, edge = EdgeSide.Bottom)
    val right = dominant(0, h, w - band, w, EdgeSide.Right)
    val left = dominant(0, h, 0, band, EdgeSide.Left)
    val accent = extractVibrant(pixels)
    val ambient = dominant(0, h)
    if (small !== bitmap) small.recycle()
    return EdgeColors(top, bottom, right, left, accent, ambient)
}

private enum class EdgeSide { Top, Bottom, Left, Right }

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

fun blendRgb(a: IntArray?, b: IntArray?, amount: Float): IntArray? {
    val left = a?.takeIf { it.size >= 3 } ?: return b
    val right = b?.takeIf { it.size >= 3 } ?: return left
    val t = amount.coerceIn(0f, 1f)
    return intArrayOf(
        (left[0] + (right[0] - left[0]) * t).toInt().coerceIn(0, 255),
        (left[1] + (right[1] - left[1]) * t).toInt().coerceIn(0, 255),
        (left[2] + (right[2] - left[2]) * t).toInt().coerceIn(0, 255),
    )
}
