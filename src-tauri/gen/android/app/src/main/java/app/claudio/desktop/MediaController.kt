package app.claudio.desktop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// MIUI / HyperOS 兼容大图最大边长 —— 大于这个值 MIUI 会静默丢弃 Bitmap，
// 锁屏卡片显示不出封面。512 ARGB_8888 ≈ 1MB 上限，安全裕量足
private const val ART_MAX_DIM = 512

/**
 * 整个"系统媒体卡片"的中枢。
 *
 *   JS 侧（player-state.tsx）        ──→  this.setMetadata / setPlaybackState
 *                                          │
 *                                          ▼
 *                                  MediaSessionCompat
 *                                          │
 *               ┌──────────────────────────┼─────────────────────────┐
 *               ▼                          ▼                         ▼
 *      锁屏 Now Playing 卡片        通知抽屉 MediaStyle      耳机线控按键
 *
 *   反向：用户在锁屏 / 抽屉点 prev / play / next / 拖进度
 *                                          │
 *                                          ▼
 *                       MediaSessionCompat.Callback (本类内匿名实现)
 *                                          │
 *                                          ▼
 *                       evaluateJavascript("window.__claudioMediaAction(...)")
 *                                          │
 *                                          ▼
 *                       JS 侧已有的 mediaSession action handler 闭环
 *
 * 设计取舍：
 * - 不走 Tauri 命令 / JNI from Rust：JavascriptInterface + evaluateJavascript 已经
 *   是同进程同 JVM 的双向直连，加 Rust 一层只会徒增延迟和编排成本。
 * - 单例：MainActivity 在 onWebViewCreate 时挂 WebView 引用 + addJavascriptInterface，
 *   MediaPlaybackService 在 onCreate 时挂 service 引用；JS 侧直接 window.__ClaudioMedia
 *   全局拿到。Activity / Service 重启时各自把引用重置。
 * - 封面：网易 CDN 防盗链，只要没 Referer 就放行；HttpURLConnection 默认不发 Referer
 *   正合适。下载放线程池里跑，不阻 JS 线程。
 */
object MediaController {
    private const val TAG = "ClaudioMedia"

    @Volatile private var webView: WebView? = null
    @Volatile private var session: MediaSessionCompat? = null
    @Volatile private var service: MediaPlaybackService? = null

    // 当前 metadata + state 缓存：service 重启 / session 重建时用来重发，
    // 不必让 JS 侧每次都重新推一遍
    @Volatile var currentTitle: String = ""
        private set
    @Volatile var currentArtist: String = ""
        private set
    @Volatile var currentAlbum: String = ""
        private set
    @Volatile var currentCoverUrl: String? = null
        private set
    @Volatile var currentDurationMs: Long = 0
        private set
    @Volatile var currentPlaying: Boolean = false
        private set
    @Volatile var currentPositionMs: Long = 0
        private set
    @Volatile var currentArtwork: Bitmap? = null
        private set

    private val uiHandler = Handler(Looper.getMainLooper())
    private val coverIo = Executors.newSingleThreadExecutor { r ->
        Thread(r, "claudio-media-cover").apply { isDaemon = true }
    }

    // ----------- 装配（MainActivity / MediaPlaybackService 调用）-----------

    fun attachWebView(wv: WebView) {
        webView = wv
        wv.addJavascriptInterface(this, "__ClaudioMedia")
        Log.d(TAG, "JavascriptInterface 已挂到 WebView")
    }

    fun attachService(svc: MediaPlaybackService, sess: MediaSessionCompat) {
        service = svc
        session = sess
        sess.setCallback(callback)
        // service 刚拉起时把上次缓存的状态再喂一次，给"切后台再回前台"或服务因
        // 内存压力被重启的情况兜底
        if (currentTitle.isNotEmpty()) {
            applyMetadataToSession()
            applyStateToSession()
            service?.refreshNotification()
        }
    }

    fun detachService() {
        session?.setCallback(null)
        service = null
        session = null
    }

    // ----------- JS → Kotlin（@JavascriptInterface 表面）-----------
    //
    // 注意：所有 @JavascriptInterface 方法都跑在 WebView 的 binder 线程，
    // 不在主线程；任何 UI / Notification / Bitmap 操作都要丢回 uiHandler。

    @JavascriptInterface
    fun setMetadata(json: String) {
        try {
            val o = JSONObject(json)
            val title = o.optString("title", "")
            val artist = o.optString("artist", "")
            val album = o.optString("album", "")
            val cover = o.optString("coverUrl").takeIf { it.isNotBlank() }
            val durMs = (o.optDouble("durationSec", 0.0) * 1000).toLong().coerceAtLeast(0)

            val coverChanged = cover != currentCoverUrl
            currentTitle = title
            currentArtist = artist
            currentAlbum = album
            currentCoverUrl = cover
            currentDurationMs = durMs
            Log.d(TAG, "setMetadata title=$title artist=$artist dur=${durMs}ms cover=${cover != null}")

            uiHandler.post {
                applyMetadataToSession()
                val svc = service
                svc?.refreshNotification()
                svc?.let { broadcastMeta(it) }
            }

            // 封面下载放线程池，回主线程更新 metadata + 通知。decodeStream 是相对
            // 重的活，别堵 binder。
            if (coverChanged) {
                currentArtwork = null
                if (cover != null) loadCoverAsync(cover)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "setMetadata 失败: $json", e)
        }
    }

    @JavascriptInterface
    fun setPlaybackState(json: String) {
        try {
            val o = JSONObject(json)
            currentPlaying = o.optBoolean("playing", false)
            currentPositionMs =
                (o.optDouble("positionSec", 0.0) * 1000).toLong().coerceAtLeast(0)
            uiHandler.post {
                applyStateToSession()
                val svc = service
                svc?.refreshNotification()
                svc?.let { broadcastState(it) }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "setPlaybackState 失败: $json", e)
        }
    }

    /** 给 JS 侧自检用：返回 "1" 表示 bridge 已挂上 */
    @JavascriptInterface
    fun ping(): String = "1"

    // ----------- 内部工具 -----------

    private fun applyMetadataToSession() {
        val s = session ?: return
        val b = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDurationMs)
        currentArtwork?.let {
            b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            b.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
        }
        s.setMetadata(b.build())
    }

    private fun applyStateToSession() {
        val s = session ?: return
        val state = if (currentPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED
        // ACTIONS：决定锁屏卡片上哪些控件可点。我们全开，让系统按布局决定怎么显示。
        val actions = (
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP
        )
        // playbackSpeed=1.0：拖进度后系统会以 lastUpdate + speed*(now-lastUpdate)
        // 估算 currentPosition；只要 JS 侧每秒推一次就够顺
        val pb = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, currentPositionMs, 1.0f)
            .build()
        s.setPlaybackState(pb)
        s.isActive = currentTitle.isNotEmpty() || currentArtist.isNotEmpty()
    }

    private fun loadCoverAsync(url: String) {
        coverIo.execute {
            val bmp = downloadBitmap(url) ?: return@execute
            currentArtwork = bmp
            uiHandler.post {
                if (currentCoverUrl == url) {
                    applyMetadataToSession()
                    service?.refreshNotification()
                }
            }
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val u = URL(url)
            val conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            // 网易 CDN 防盗链：只要 Referer 字段不存在就放行；HttpURLConnection 默认不发，
            // 这里显式置空双保险
            conn.setRequestProperty("Referer", "")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 Claudio")
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "封面下载 HTTP $code: $url")
                return null
            }
            // 1) 先 decodeBounds 探尺寸，2) inSampleSize 粗略缩放（2 的幂）到接近
            //    ART_MAX_DIM，3) 完整 decode，4) 必要时再 createScaledBitmap 精确缩到
            //    边界内。MIUI / HyperOS 对 Notification.LargeIcon 有大小上限（~1MB
            //    ARGB_8888），过大就静默丢弃 → 锁屏 / 通知抽屉显示不出封面。
            //    封面源边长一般 1024-2048，一步 inSampleSize 就能进 512 内。
            val bytes = conn.inputStream.use { it.readBytes() }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val srcMax = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
            var sample = 1
            while (srcMax / sample > ART_MAX_DIM * 2) sample *= 2
            val opts2 = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts2)
                ?: return null
            val maxDim = maxOf(raw.width, raw.height)
            if (maxDim <= ART_MAX_DIM) return raw
            val scale = ART_MAX_DIM.toFloat() / maxDim
            val scaled = Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).toInt().coerceAtLeast(1),
                (raw.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== raw) raw.recycle()
            scaled
        } catch (e: Throwable) {
            Log.w(TAG, "封面下载异常: $url", e)
            null
        }
    }

    /** 老牌兼容路径：emit com.android.music.* 系列广播。
     *  Mi Band / Xiaomi Watch / 车载投屏 / MIUI "专注模式" 还在监听这些。
     *  代价就是每次 metadata / state 变化多发一条 broadcast，没副作用。 */
    private fun emitLegacyBroadcasts(ctx: Context, kind: LegacyKind) {
        val action = when (kind) {
            LegacyKind.META -> "com.android.music.metachanged"
            LegacyKind.STATE -> "com.android.music.playstatechanged"
            LegacyKind.COMPLETE -> "com.android.music.playbackcomplete"
        }
        try {
            val i = Intent(action).apply {
                putExtra("track", currentTitle)
                putExtra("artist", currentArtist)
                putExtra("album", currentAlbum)
                putExtra("duration", currentDurationMs)
                putExtra("position", currentPositionMs)
                // 两个 key 都塞 —— MIUI / 华为 / Mi Band 各取所需
                putExtra("playing", currentPlaying)
                putExtra("playstate", currentPlaying)
                putExtra("id", (currentTitle + currentArtist).hashCode().toLong())
            }
            ctx.sendBroadcast(i)
        } catch (e: Throwable) {
            Log.w(TAG, "legacy broadcast $action 失败", e)
        }
    }

    private enum class LegacyKind { META, STATE, COMPLETE }

    fun broadcastMeta(ctx: Context) = emitLegacyBroadcasts(ctx, LegacyKind.META)
    fun broadcastState(ctx: Context) = emitLegacyBroadcasts(ctx, LegacyKind.STATE)

    // ----------- Kotlin → JS（MediaSession 回调）-----------

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = dispatch("play")
        override fun onPause() = dispatch("pause")
        override fun onSkipToNext() = dispatch("next")
        override fun onSkipToPrevious() = dispatch("prev")
        override fun onStop() = dispatch("pause")
        override fun onSeekTo(pos: Long) {
            dispatch("seek", "{\"positionSec\":${pos / 1000.0}}")
        }
    }

    private fun dispatch(action: String, payloadJson: String = "{}") {
        val wv = webView ?: return
        // evaluateJavascript 必须在主线程
        uiHandler.post {
            // \" 转义 action；payloadJson 已经是完整 JSON 字面量
            val js =
                "window.__claudioMediaAction && window.__claudioMediaAction(\"$action\", $payloadJson);"
            try {
                wv.evaluateJavascript(js, null)
            } catch (e: Throwable) {
                Log.w(TAG, "evaluateJavascript 失败: $action", e)
            }
        }
    }

    fun smallIconRes(ctx: Context): Int = R.mipmap.ic_launcher
}
