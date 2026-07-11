package app.pipo.nativeapp.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import app.pipo.nativeapp.DiagnosticsLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    private var generation = 0L
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
        val jobGeneration = generation
        activeKey = key
        val mediaId = item.mediaId
        job = scope.launch {
            try {
                // 给启动缓冲让路 / 计费网络上等过跳歌高发窗口
                val graceMs = if (isActiveNetworkMetered()) {
                    val playedMs = player.currentPosition.coerceAtLeast(0L)
                    (METERED_MIN_PLAYED_MS - playedMs).coerceAtLeast(WARM_START_GRACE_MS)
                } else {
                    WARM_START_GRACE_MS
                }
                delay(graceMs)
                // 必须仍在 READY + 想播，并先攒够安全水位。弱网/重新 BUFFERING 时服务会
                // cancel writer，把 HTTP 带宽立即还给真实播放，而不是让整曲缓存反客为主。
                if (!awaitPlaybackHeadroom(player, uri)) return@launch
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
                currentCoroutineContext().ensureActive()
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
            } finally {
                if (generation == jobGeneration && activeKey == key) {
                    activeWriter = null
                    activeKey = null
                }
            }
        }
    }

    private suspend fun awaitPlaybackHeadroom(player: Player, uri: android.net.Uri): Boolean {
        var waitedMs = 0L
        while (waitedMs <= BUFFER_HEADROOM_MAX_WAIT_MS) {
            val liveLocal = player.currentMediaItem?.localConfiguration
            if (liveLocal?.uri != uri || !player.playWhenReady || player.playbackState != Player.STATE_READY) {
                return false
            }
            if (!isActiveNetworkValidated()) return false
            val positionMs = player.currentPosition.coerceAtLeast(0L)
            val durationMs = player.duration.takeIf { it > 0L }
            val remainingMs = durationMs?.let { (it - positionMs).coerceAtLeast(0L) }
            if (remainingMs != null && remainingMs <= MIN_REMAINING_TO_WARM_MS) return false
            val requiredHeadroomMs = minOf(
                MIN_BUFFERED_AHEAD_BEFORE_WARM_MS,
                remainingMs ?: MIN_BUFFERED_AHEAD_BEFORE_WARM_MS,
            )
            val bufferedAheadMs = (player.bufferedPosition - positionMs).coerceAtLeast(0L)
            if (bufferedAheadMs >= requiredHeadroomMs) return true
            delay(BUFFER_HEADROOM_POLL_MS)
            waitedMs += BUFFER_HEADROOM_POLL_MS
        }
        return false
    }

    fun cancel() {
        generation += 1L
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

    private fun isActiveNetworkValidated(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        private const val WARM_START_GRACE_MS = 1_500L
        private const val MIN_BUFFERED_AHEAD_BEFORE_WARM_MS = 12_000L
        private const val MIN_REMAINING_TO_WARM_MS = 5_000L
        private const val BUFFER_HEADROOM_POLL_MS = 500L
        private const val BUFFER_HEADROOM_MAX_WAIT_MS = 30_000L
        /** 计费网络：至少播过这么久才整曲下载（跳歌高发窗口不花全量流量） */
        private const val METERED_MIN_PLAYED_MS = 20_000L
        private const val MAX_ATTEMPTS_PER_KEY = 2
        private const val COMPLETED_KEYS_MAX = 64
    }
}
