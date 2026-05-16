package app.pipo.nativeapp.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

object AppForeground {
    private val resumedCount = AtomicInteger(0)
    private val foregroundState = MutableStateFlow(false)

    val isForeground: StateFlow<Boolean> = foregroundState.asStateFlow()

    fun onActivityResumed() {
        if (resumedCount.incrementAndGet() > 0) {
            foregroundState.value = true
        }
    }

    fun onActivityPaused() {
        val count = resumedCount.decrementAndGet().coerceAtLeast(0)
        if (count == 0) {
            resumedCount.set(0)
            foregroundState.value = false
        }
    }
}
