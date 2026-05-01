package app.pipo.nativeapp.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 蒸馏后台协调器 —— 把 distill 从 Composable 局部 scope 提到 app 全局 scope。
 *
 * 之前问题：DistillScreen 用 rememberCoroutineScope() 起的 launch + 全屏 overlay 锁住界面。
 *   - 用户切到播放页 → DistillScreen 退出 composition → scope cancel → distill 中途死
 *   - overlay 直接 fillMaxSize 把所有交互吃掉
 *
 * 现在：
 *   - start(playlists) 跑在 [appScope]（SupervisorJob + Dispatchers.Default）—— 跟 Application
 *     生命周期绑，Composable 进出无关
 *   - progress / error / running 暴露成 StateFlow，shell 层订阅画一条小浮条
 *   - cancel() 让用户能主动停
 *
 * 同时只允许一个 distill 在飞行 —— 重复调 start 直接 noop。
 */
class DistillCoordinator(private val engine: DistillEngine) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _progress = MutableStateFlow<DistillProgress?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private val _running = MutableStateFlow(false)
    private var currentJob: Job? = null

    val progress: StateFlow<DistillProgress?> = _progress.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 启动一次蒸馏。如果已经在跑，直接 noop（防重入）。返回 true 表示真启动了一次。 */
    fun start(
        playlists: List<PipoPlaylist>,
        sampleSize: Int = 200,
        onComplete: () -> Unit = {},
    ): Boolean {
        if (_running.value) return false
        if (playlists.isEmpty()) return false
        _error.value = null
        _running.value = true
        currentJob = appScope.launch {
            try {
                engine.distill(
                    playlists = playlists,
                    sampleSize = sampleSize,
                    onProgress = { _progress.value = it },
                )
                onComplete()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                _error.value = t.message ?: "蒸馏失败"
            } finally {
                _progress.value = null
                _running.value = false
                currentJob = null
            }
        }
        return true
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        _running.value = false
        _progress.value = null
    }

    fun dismissError() {
        _error.value = null
    }
}
