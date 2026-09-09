package app.pipo.nativeapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.HapticFeedbackConstants
import app.pipo.nativeapp.data.AiPetCommandBus
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.pipo.nativeapp.ui.PipoNativeApp

class MainActivity : ComponentActivity() {
    private val wakeHandler = Handler(Looper.getMainLooper())
    private var wakeEvent: MotionEvent? = null
    private var wakeConsumed = false
    private var wakePending = false
    private var wakePointerIds = intArrayOf()
    private var wakeOrigins = floatArrayOf()
    private val wakeAssistant = Runnable {
        val cancel = wakeEvent ?: return@Runnable
        wakePending = false
        wakeConsumed = true
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        wakeEvent = null
        window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        AiPetCommandBus.openAssistant()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            clearWakeCandidate()
            wakeConsumed = false
        }
        if (wakeConsumed) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) wakeConsumed = false
            return true
        }
        if (!AiPetCommandBus.isOpen.value && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 2) {
            clearWakeCandidate()
            wakePointerIds = intArrayOf(event.getPointerId(0), event.getPointerId(1))
            wakeOrigins = floatArrayOf(event.getX(0), event.getY(0), event.getX(1), event.getY(1))
            wakeEvent = MotionEvent.obtain(event)
            wakePending = true
            wakeHandler.postDelayed(wakeAssistant, ViewConfiguration.getLongPressTimeout().toLong())
        } else if (wakePending) {
            val slop = ViewConfiguration.get(this).scaledTouchSlop * 2f
            val moved = wakePointerIds.indices.any { slot ->
                val index = event.findPointerIndex(wakePointerIds[slot])
                index < 0 || kotlin.math.abs(event.getX(index) - wakeOrigins[slot * 2]) > slop ||
                    kotlin.math.abs(event.getY(index) - wakeOrigins[slot * 2 + 1]) > slop
            }
            if (event.pointerCount != 2 || moved || event.actionMasked in listOf(MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) clearWakeCandidate()
        }
        return super.dispatchTouchEvent(event)
    }

    private fun clearWakeCandidate() {
        wakeHandler.removeCallbacks(wakeAssistant)
        wakeEvent?.recycle()
        wakeEvent = null
        wakePending = false
    }

    override fun onStop() {
        clearWakeCandidate()
        wakeConsumed = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val launchIntent = intent
        val lyricSandbox = launchIntent.isLyricSandboxLaunch()
        val lyricSandboxPositionMs = launchIntent?.getLongExtra(EXTRA_LYRIC_SANDBOX_POSITION_MS, 0L)
            ?: launchIntent?.data?.getQueryParameter("positionMs")?.toLongOrNull()
            ?: 0L
        val lyricSandboxPlaying = launchIntent?.getBooleanExtra(EXTRA_LYRIC_SANDBOX_PLAYING, true)
            ?: (launchIntent?.data?.getQueryParameter("playing")?.toBooleanStrictOrNull() ?: true)
        val lyricSandboxProbe = launchIntent?.getBooleanExtra(EXTRA_LYRIC_SANDBOX_PROBE, false)
            ?: (launchIntent?.data?.getQueryParameter("probe")?.toBooleanStrictOrNull() ?: false)
        if (!lyricSandbox) requestNotificationPermissionIfNeeded()
        setContent {
            PipoNativeApp(
                lyricSandbox = lyricSandbox,
                lyricSandboxPositionMs = lyricSandboxPositionMs,
                lyricSandboxPlaying = lyricSandboxPlaying,
                lyricSandboxProbe = lyricSandboxProbe,
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001,
            )
        }
    }

    private companion object {
        const val ACTION_LYRIC_SANDBOX = "app.pipo.nativeapp.action.LYRIC_SANDBOX"
        const val EXTRA_LYRIC_SANDBOX = "pipo.lyricSandbox"
        const val EXTRA_LYRIC_SANDBOX_POSITION_MS = "pipo.lyricSandboxPositionMs"
        const val EXTRA_LYRIC_SANDBOX_PLAYING = "pipo.lyricSandboxPlaying"
        const val EXTRA_LYRIC_SANDBOX_PROBE = "pipo.lyricSandboxProbe"
    }
}

private fun Intent?.isLyricSandboxLaunch(): Boolean {
    if (this == null) return false
    return getBooleanExtra("pipo.lyricSandbox", false) ||
        action == "app.pipo.nativeapp.action.LYRIC_SANDBOX" ||
        data?.scheme == "pipo-lyric-sandbox"
}
