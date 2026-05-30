package app.pipo.nativeapp.data

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全 app 共享的 OkHttpClient —— 统一连接池 + DNS 缓存 + HTTP/2 复用。
 *
 * 之前的状况：封面走 Coil 自带的一份 OkHttp，歌词（AMLL）和天气各自 `HttpURLConnection`
 * 且在 finally 里 `disconnect()` 直接关掉 keep-alive。三套互不相干的 HTTP 栈、三份连接池。
 * 统一到这一份后，同主机的重复请求（如反复抓 raw.githubusercontent.com 上的逐字歌词）
 * 复用已有连接，省掉重复的 TCP/TLS 握手。
 *
 * 注意：音频流（Media3 `DefaultHttpDataSource`）刻意不接这里 —— 不改动播放链路的 HTTP 行为，
 * 避免影响缓冲 / range 请求 / 重定向等已调好的流媒体表现。各调用方按需用 `client.newBuilder()`
 * 叠加自己的超时（newBuilder 共享同一个连接池 / dispatcher，开销极小）。
 */
object PipoHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(
                ConnectionPool(
                    maxIdleConnections = 6,
                    keepAliveDuration = 5,
                    timeUnit = TimeUnit.MINUTES,
                ),
            )
            .retryOnConnectionFailure(true)
            .build()
    }
}
