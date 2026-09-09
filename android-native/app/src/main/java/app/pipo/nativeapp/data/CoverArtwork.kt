package app.pipo.nativeapp.data

import android.content.Context
import coil.request.ImageRequest
import coil.size.Scale

/** 播放前预取与可见封面使用同一请求尺寸和缓存 key。 */
internal fun coverImageRequest(context: Context, url: String?, maxDecodeSizePx: Int?): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .diskCacheKey(url)
        .scale(Scale.FILL)
        .crossfade(false)
        .apply {
            if (maxDecodeSizePx != null) {
                size(maxDecodeSizePx, maxDecodeSizePx)
                memoryCacheKey("cover:$maxDecodeSizePx:$url")
            }
        }
        .build()
