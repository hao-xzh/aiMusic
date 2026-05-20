package app.pipo.nativeapp.runtime

import android.content.Context
import app.pipo.nativeapp.StabilityDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

object AppForeground {
    private val resumedCount = AtomicInteger(0)
    private val foregroundState = MutableStateFlow(false)

    val isForeground: StateFlow<Boolean> = foregroundState.asStateFlow()

    fun onActivityResumed(context: Context) {
        val count = resumedCount.incrementAndGet()
        if (count > 0 && !foregroundState.value) {
            foregroundState.value = true
            StabilityDiagnostics.recordAppForeground(context, foreground = true, resumedCount = count)
        }
    }

    fun onActivityPaused(context: Context) {
        val count = resumedCount.decrementAndGet().coerceAtLeast(0)
        if (count == 0) {
            resumedCount.set(0)
            if (foregroundState.value) {
                foregroundState.value = false
                StabilityDiagnostics.recordAppForeground(context, foreground = false, resumedCount = 0)
            }
        }
    }
}
