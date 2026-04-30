package app.claudio.desktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
// MediaButtonReceiver 在 androidx.media 1.x 时迁了包：原先 v4 的
// android.support.v4.media.session.MediaButtonReceiver 已经空壳，必须用
// androidx.media.session.MediaButtonReceiver。MediaSessionCompat / PlaybackStateCompat
// / MediaMetadataCompat 留在 android.support.v4.* 是 androidx.media 库故意保留的
// 兼容包名，能直接用。
import androidx.media.session.MediaButtonReceiver

/**
 * 媒体前台服务。
 *
 * 三个职责：
 *   1. 钉住 app 进程：foregroundServiceType=mediaPlayback + WAKE_LOCK，保 WebView /
 *      AudioContext / <audio> 在切后台、锁屏后能继续输出。
 *   2. 持有 MediaSessionCompat：所有锁屏卡片、通知抽屉媒体控件、耳机线控按键的
 *      系统级路由都靠这一只 session。会话本体的设置 + 回调全在 MediaController。
 *   3. 维护 MediaStyle 通知：拿 session 的 metadata + state 渲染锁屏 / 抽屉那张
 *      "正在播放"卡片，prev / play-pause / next 三个 Action 通过 MediaButton-
 *      Receiver 发回到 session 的 callback。
 *
 * 跟旧版的差别：
 *   - 通知从 IMPORTANCE_LOW 的纯文本"正在播放" → IMPORTANCE_LOW + MediaStyle，
 *     仍然不响铃，但会在系统的"媒体卡片"槽位显示出来（系统抽屉顶部 / 锁屏正中）
 *   - 多了 MediaSessionCompat 全套；MainActivity 在 onWebViewCreate 时把 WebView
 *     交给 MediaController，service 在 onCreate 时把 session 交给 MediaController，
 *     双向桥才算闭环
 */
class MediaPlaybackService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var session: MediaSessionCompat

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        setupSession()
        startForegroundWithNotification()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 处理来自通知按钮 / 耳机线控的 ACTION_MEDIA_BUTTON intent ——
        // MediaButtonReceiver 会把它正确路由到我们 session 的 callback
        MediaButtonReceiver.handleIntent(session, intent)
        // START_STICKY: 系统因为内存压力 kill 服务后，会自动尝试重启
        return START_STICKY
    }

    override fun onDestroy() {
        MediaController.detachService()
        session.isActive = false
        session.release()
        releaseWakeLock()
        super.onDestroy()
    }

    /** MediaController 在 metadata / state 变化时调到这里来重画通知 */
    fun refreshNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pipo 后台播放",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Pipo 在后台 / 锁屏播放时的媒体卡片"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // MediaStyle 卡片自带停止 / 播放 / 切歌按钮，不需要再震动响铃
                setSound(null, null)
                enableVibration(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun setupSession() {
        session = MediaSessionCompat(this, "ClaudioMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            // 默认给一个空 PlaybackState，避免 setMediaSession() 时系统拿到 null
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO,
                    )
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0L, 1.0f)
                    .build(),
            )
            isActive = true
        }
        // 把 session 交给 MediaController；它会安装 callback 并把 JS 端缓存的
        // metadata / state 重发一次（如果有的话）
        MediaController.attachService(this, session)
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        // 三个媒体按钮的 PendingIntent —— MediaButtonReceiver 会把它们包装成
        // ACTION_MEDIA_BUTTON intent 投递到我们 service 的 onStartCommand
        val prevPi = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
        )
        val playPausePi = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this, PlaybackStateCompat.ACTION_PLAY_PAUSE,
        )
        val nextPi = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
        )

        val playing = MediaController.currentPlaying
        val title = MediaController.currentTitle.ifBlank { "Pipo" }
        val artist = MediaController.currentArtist.ifBlank { "—" }

        val style = MediaStyle()
            .setMediaSession(session.sessionToken)
            // 紧凑视图（折叠通知 / Android 13- 锁屏小卡片）显示三个按钮的索引：
            // 0=prev, 1=play-pause, 2=next，跟下面 addAction 顺序一致
            .setShowActionsInCompactView(0, 1, 2)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setStyle(style)
            .setSmallIcon(MediaController.smallIconRes(this))
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(MediaController.currentArtwork)
            .setContentIntent(contentPi)
            .setDeleteIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_STOP,
                ),
            )
            // CATEGORY_TRANSPORT：MIUI / HyperOS 用 category 把通知归到"音乐 /
            // 焦点模式"那一档；锁屏卡片 + 车载投屏 + Mi Band 都靠这个标签识别。
            // 没设的话即使有 MediaSession Token，部分 HyperOS 版本仍可能把它当
            // 普通通知处理，不升级到 Now Playing 卡片。
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_previous, "上一首", prevPi,
                ),
            )
            .addAction(
                NotificationCompat.Action(
                    if (playing) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play,
                    if (playing) "暂停" else "播放",
                    playPausePi,
                ),
            )
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_next, "下一首", nextPi,
                ),
            )

        return builder.build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Pipo:MediaPlaybackService",
        ).apply {
            setReferenceCounted(false)
            // 不带超时 —— 服务存活期间一直持有；服务销毁时 release
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                } catch (_: Throwable) {
                }
            }
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "claudio_playback"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java)
            context.stopService(intent)
        }
    }
}
