package app.pipo.nativeapp.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import app.pipo.nativeapp.DiagnosticsLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 当前曲整曲缓存预热：播放进入 READY 后，把当前条目的完整音频文件后台灌进
 * SimpleCache。播放端经 CacheDataSource 读同一个 cache key，整曲落盘后当前曲
 * 的播放彻底脱离网络 —— CDN 掐断空闲连接 / 弱网抖动 / URL 中途过期都不再产生
 * 听感断流（"播放中突然停顿又继续"的根）。
 *
 * 流量保护：不计费网络 READY 后 1.5s 开灌（给首播缓冲让路）；计费网络等播过
 * 20s 再灌 —— 跳歌高发段不为整首无损（20-40MB）买单。延迟醒来后校验仍在播
 * 同一条目才真正开始。
 *
 * 与 NextTrackPrewarmer（下一首整曲）互补：那个保切歌起步和下一首连续，
 * 这个保当前曲连续。
 * CacheWriter 自动跳过已缓存区段，重复触发只补洞，幂等。
 */
@UnstableApi
internal class TrackCacheWarmer(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private var job: Job? = null
    private var activeWriter: CacheWriter? = null
    private var activeKey: String? = null
    /** 本进程内已整曲灌完的 key —— 避免每次 READY 重复起协程（CacheWriter 幂等但起任务有成本） */
    private val completedKeys = LinkedHashSet<String>()
    private val failedAttempts = HashMap<String, Int>()

    /** READY / 换曲时调用（主线程）。同 key 已在灌或已灌完则为 no-op。 */
    fun maybeWarmCurrent(player: Player) {
        val item = player.currentMediaItem ?: return
        val local = item.localConfiguration ?: return
        val uri = local.uri
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        val key = local.customCacheKey ?: uri.toString()
        if (key == activeKey && job?.isActive == true) return
        if (key in completedKeys) return
        if ((failedAttempts[key] ?: 0) >= MAX_ATTEMPTS_PER_KEY) return

        cancel()
        activeKey = key
        val mediaId = item.mediaId
        job = scope.launch {
            // 给启动缓冲让路 / 计费网络上等过跳歌高发窗口
            val graceMs = if (isActiveNetworkMetered()) {
                val playedMs = player.currentPosition.coerceAtLeast(0L)
                (METERED_MIN_PLAYED_MS - playedMs).coerceAtLeast(WARM_START_GRACE_MS)
            } else {
                WARM_START_GRACE_MS
            }
            delay(graceMs)
            // 醒来后还在播同一条目（同 URI）才开始 —— 已经跳走就不为旧歌花流量
            val liveLocal = player.currentMediaItem?.localConfiguration
            if (liveLocal == null || liveLocal.uri != uri) return@launch
            val dataSpec = DataSpec.Builder()
                .setUri(uri)
                .setKey(key)
                .setPosition(0L)
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                .build()
            val dataSource = PipoMediaDataSources.cacheFactory(appContext).createDataSourceForDownloading()
            val writer = CacheWriter(dataSource, dataSpec, null, null)
            activeWriter = writer
            val result = withContext(Dispatchers.IO) {
                runCatching { writer.cache() }
            }
            activeWriter = null
            result.fold(
                onSuccess = {
                    completedKeys.add(key)
                    failedAttempts.remove(key)
                    if (completedKeys.size > COMPLETED_KEYS_MAX) {
                        completedKeys.remove(completedKeys.first())
                    }
                    DiagnosticsLogStore.record(
                        area = "playback",
                        event = "track_cache_warmed",
                        fields = mapOf(
                            "mediaId" to mediaId,
                            "cacheKey" to key,
                        ),
                    )
                },
                onFailure = { err ->
                    if (err is CancellationException) throw err
                    // 失败不致命：播放仍走正常流式。记一次，超过上限不再为这首重试。
                    failedAttempts[key] = (failedAttempts[key] ?: 0) + 1
                    DiagnosticsLogStore.record(
                        area = "playback",
                        event = "track_cache_warm_failed",
                        fields = mapOf(
                            "mediaId" to mediaId,
                            "cacheKey" to key,
                            "attempt" to failedAttempts[key],
                            "errorType" to err::class.java.simpleName,
                            "message" to err.message,
                        ),
                    )
                },
            )
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        runCatching { activeWriter?.cancel() }
        activeWriter = null
        activeKey = null
    }

    private fun isActiveNetworkMetered(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching { manager.isActiveNetworkMetered }.getOrDefault(false)
    }

    private companion object {
        private const val WARM_START_GRACE_MS = 1_500L
        /** 计费网络：至少播过这么久才整曲下载（跳歌高发窗口不花全量流量） */
        private const val METERED_MIN_PLAYED_MS = 20_000L
        private const val MAX_ATTEMPTS_PER_KEY = 2
        private const val COMPLETED_KEYS_MAX = 64
    }
}
