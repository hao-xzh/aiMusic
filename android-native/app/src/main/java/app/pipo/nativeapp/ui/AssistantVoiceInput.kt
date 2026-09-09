package app.pipo.nativeapp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale

/** One explicitly requested recognition session; never records while hidden or in background. */
internal class AssistantVoiceInput(private val context: Context, private val onText: (String) -> Unit) {
    var listening by mutableStateOf(false)
        private set
    var processing by mutableStateOf(false)
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { cancel(); status = "语音识别超时，请重试或直接输入" }

    fun permissionDenied() { status = "未获得麦克风权限，可以继续输入文字" }

    fun start() {
        cancel()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status = "当前设备没有可用的语音识别服务，请输入文字"
            return
        }
        val session = generation
        try {
            val service = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = service
            service.setRecognitionListener(object : RecognitionListener {
                private fun current() = session == generation
                override fun onReadyForSpeech(params: Bundle?) { if (current()) status = "正在听，点麦克风结束" }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) { if (current()) level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (!current()) return
                    listening = false
                    processing = true
                    level = 0f
                    status = "正在识别…"
                    handler.removeCallbacks(timeout)
                    handler.postDelayed(timeout, 12_000)
                }
                override fun onError(error: Int) {
                    if (!current()) return
                    cancel()
                    status = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "未获得麦克风权限，可以继续输入文字"
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听清，请重试或直接输入"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音服务连接失败，请重试或直接输入"
                        else -> "语音识别暂时不可用，请重试或直接输入"
                    }
                }
                override fun onResults(results: Bundle?) {
                    if (!current()) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    cancel()
                    if (text.isNotBlank()) { onText(text); status = "识别完成，可修改后发送" }
                    else status = "没有听清，请重试或直接输入"
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (!current()) return
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }?.let(onText)
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            listening = true
            status = "正在准备麦克风…"
            service.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
            handler.postDelayed(timeout, 45_000)
        } catch (_: Exception) {
            cancel()
            status = "无法启动语音识别，请使用文字输入"
        }
    }

    fun finish() {
        if (!listening) return
        listening = false
        processing = true
        level = 0f
        status = "正在识别…"
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 12_000)
        try { recognizer?.stopListening() }
        catch (_: Exception) { cancel(); status = "语音识别中断，请重试" }
    }

    fun cancel() {
        generation++
        handler.removeCallbacks(timeout)
        val previous = recognizer
        recognizer = null
        runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
        listening = false
        processing = false
        level = 0f
        status = null
    }
}

@Composable
internal fun rememberAssistantVoiceInput(active: Boolean, onText: (String) -> Unit): Pair<AssistantVoiceInput, () -> Unit> {
    val context = LocalContext.current
    val latestText by rememberUpdatedState(onText)
    val latestActive by rememberUpdatedState(active)
    val voice = remember(context) { AssistantVoiceInput(context.applicationContext) { latestText(it) } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (latestActive) {
            if (granted) voice.start() else voice.permissionDenied()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, voice) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) voice.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); voice.cancel() }
    }
    LaunchedEffect(active) { if (!active) voice.cancel() }
    val toggle: () -> Unit = {
        if (active) {
            when {
                voice.listening -> voice.finish()
                voice.processing -> voice.cancel()
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> voice.start()
                else -> permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    return voice to toggle
}
