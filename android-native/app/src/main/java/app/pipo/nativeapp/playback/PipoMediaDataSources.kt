package app.pipo.nativeapp.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource

@UnstableApi
object PipoMediaDataSources {
    fun httpFactory(): DefaultHttpDataSource.Factory {
        // 超时要明显小于 LoadControl 的 minBuffer(25s)：CDN 静默掐断空闲连接时，
        // 从死 socket 读数据要等满 readTimeout 才报错重连。30s > 缓冲量 = 缓冲必耗尽、
        // 用户必听到断流；8s 之内报错则 ExoPlayer 自动按 Range 重连，缓冲足以盖住。
        return DefaultHttpDataSource.Factory()
            .setUserAgent("PipoNative/0.1")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to "https://music.163.com/"))
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(8_000)
    }

    fun cacheFactory(context: Context): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(PipoMediaCache.get(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, httpFactory()))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
