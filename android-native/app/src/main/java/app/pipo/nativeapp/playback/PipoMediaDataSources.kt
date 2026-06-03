package app.pipo.nativeapp.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource

@UnstableApi
object PipoMediaDataSources {
    fun httpFactory(): DefaultHttpDataSource.Factory {
        return DefaultHttpDataSource.Factory()
            .setUserAgent("PipoNative/0.1")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to "https://music.163.com/"))
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
    }

    fun cacheFactory(context: Context): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(PipoMediaCache.get(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, httpFactory()))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
