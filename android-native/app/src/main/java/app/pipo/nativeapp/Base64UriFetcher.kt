package app.pipo.nativeapp

import android.net.Uri
import android.util.Base64
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.Buffer

/**
 * 让 Coil 2.7 能直接吃 `data:image/...;base64,xxx` 这种 URI。
 *
 * Coil 2.7 默认 fetchers 只有 http / file / content / asset / resource，data URI 没有对
 * 应 fetcher 就算 String 模型也只会走到 "scheme=data 没人接" → SuccessResult 永远不出。
 * 我们把 ExoPlayer 从 MP3 ID3 抽到的内嵌封面 (ByteArray) 转成 data URI 喂给同一个 String?
 * artworkUrl 管线，所以必须在 ImageLoader 上注册这个 fetcher，否则 in-app UI 拿不到封面
 * （系统状态栏不走 Coil，照样能显示）。
 *
 * 在 [PipoApplication] 通过 [coil.ImageLoaderFactory] 注册：
 * ```
 *   override fun newImageLoader(): ImageLoader =
 *       ImageLoader.Builder(this).components { add(Base64UriFetcher.Factory()) }.build()
 * ```
 */
class Base64UriFetcher private constructor(
    private val data: Uri,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val raw = data.toString()
        // data:[<mediatype>][;base64],<payload>
        val commaIndex = raw.indexOf(',')
        require(commaIndex > 0) { "invalid data URI (no payload comma): $raw" }
        val header = raw.substring(DATA_PREFIX.length, commaIndex)
        val payload = raw.substring(commaIndex + 1)
        val isBase64 = header.endsWith(";base64", ignoreCase = true)
        val mime = header
            .removeSuffix(";base64")
            .removeSuffix(";BASE64")
            .ifEmpty { "image/*" }
        val bytes = if (isBase64) {
            // URL 在 base64 段里允许出现 \n / 空格；NO_WRAP/CRLF/URL_SAFE 都兼容。
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            Uri.decode(payload).toByteArray()
        }
        return SourceResult(
            source = ImageSource(
                source = Buffer().apply { write(bytes) },
                context = options.context,
            ),
            mimeType = mime,
            dataSource = DataSource.MEMORY,
        )
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!data.toString().startsWith(DATA_PREFIX, ignoreCase = true)) return null
            return Base64UriFetcher(data, options)
        }
    }

    private companion object {
        const val DATA_PREFIX = "data:"
    }
}
