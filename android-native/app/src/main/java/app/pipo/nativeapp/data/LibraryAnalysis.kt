package app.pipo.nativeapp.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger

/**
 * 批量预分析库 —— 镜像 src/lib/library-analysis.ts。
 *
 * 把整个蒸馏库的歌全跑一遍 audio_get_features，让宠物排歌单时一开口就有
 * 真 BPM/energy 可用。
 *
 * Android 端跟 React 关键差别：
 *   - JS 那边 dual-pipeline（JS WebAudio decodeAudioData + native Symphonia），
 *     这里只走 native（Rust JNI），没有备线
 *   - urlBatch 30 走 songUrls 批量；并发 3
 *   - 全局 inflight 单例：避免重复触发
 */
class LibraryAnalysis(
    private val repository: PipoRepository,
    private val featuresStore: AudioFeaturesStore,
) {
    data class Progress(val total: Int, val done: Int, val skipped: Int, val failed: Int)

    @Volatile
    private var inflight: kotlinx.coroutines.Deferred<Progress>? = null

    suspend fun analyze(
        library: List<NativeTrack>,
        concurrency: Int = 3,
        urlBatch: Int = 30,
        onProgress: ((Progress) -> Unit)? = null,
    ): Progress {
        inflight?.let { return it.await() }

        return coroutineScope {
            val deferred = async {
                runAnalysis(library, concurrency, urlBatch, onProgress)
            }
            inflight = deferred
            try {
                deferred.await()
            } finally {
                inflight = null
            }
        }
    }

    fun isInflight(): Boolean = inflight != null

    private suspend fun runAnalysis(
        library: List<NativeTrack>,
        concurrency: Int,
        urlBatch: Int,
        onProgress: ((Progress) -> Unit)?,
    ): Progress {
        var done = 0
        var skipped = 0
        var failed = 0
        val total = library.size

        // 1) 已缓存的剔掉
        val pending = library.filter { t ->
            val cached = featuresStore.get(t.id)
            if (cached != null) {
                skipped++; done++
                onProgress?.invoke(Progress(total, done, skipped, failed))
                false
            } else true
        }

        // 2) 批量取直链（缺什么补什么）
        val idToUrl = HashMap<Long, String>()
        pending.filter { it.streamUrl.isBlank() && it.neteaseId != null }
            .chunked(urlBatch)
            .forEach { slice ->
                runCatching {
                    val urls = repository.songUrls(slice.mapNotNull { it.neteaseId })
                    urls.forEach { u -> u.url?.takeIf { it.isNotBlank() }?.let { idToUrl[u.id] = it } }
                }
            }

        // 3) 并发跑 audio_get_features。固定 worker 数，避免大曲库一次创建几千个协程。
        val workerCount = concurrency.coerceAtLeast(1).coerceAtMost(pending.size.coerceAtLeast(1))
        val nextIndex = AtomicInteger(0)
        coroutineScope {
            List(workerCount) {
                async {
                    while (true) {
                        val index = nextIndex.getAndIncrement()
                        if (index >= pending.size) break
                        val t = pending[index]
                        val ne = t.neteaseId
                        val url = if (t.streamUrl.isNotBlank()) t.streamUrl else ne?.let { idToUrl[it] }
                        if (ne == null || url.isNullOrBlank()) {
                            synchronized(this@LibraryAnalysis) {
                                failed++; done++
                                onProgress?.invoke(Progress(total, done, skipped, failed))
                            }
                            continue
                        }
                        val ok = runCatching {
                            val f = repository.audioFeatures(ne, url)
                            if (f != null) featuresStore.put(t.id, f)
                            f != null
                        }.getOrDefault(false)
                        synchronized(this@LibraryAnalysis) {
                            if (!ok) failed++
                            done++
                            onProgress?.invoke(Progress(total, done, skipped, failed))
                        }
                    }
                }
            }.awaitAll()
        }
        return Progress(total, done, skipped, failed)
    }
}
