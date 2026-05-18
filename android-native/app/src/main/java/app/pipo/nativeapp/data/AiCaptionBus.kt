package app.pipo.nativeapp.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object AiCaptionBus {
    data class Caption(
        val id: Long,
        val text: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _caption = MutableStateFlow<Caption?>(null)
    private var nextId = 0L
    private var clearJob: Job? = null

    val caption: StateFlow<Caption?> = _caption.asStateFlow()

    fun show(text: String?, ttlMs: Long = 5600L) {
        val compact = compact(text)
        if (compact.isBlank()) return
        val id = ++nextId
        clearJob?.cancel()
        _caption.value = Caption(id = id, text = compact)
        clearJob = scope.launch {
            delay(ttlMs)
            if (_caption.value?.id == id) {
                _caption.value = null
            }
        }
    }

    fun compact(text: String?): String {
        val normalized = text
            .orEmpty()
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('「', '」', '『', '』', '"', '\'', '“', '”', '-', '—', ' ')
        if (normalized.length <= 30) return normalized
        return normalized.take(29).trimEnd() + "…"
    }
}
