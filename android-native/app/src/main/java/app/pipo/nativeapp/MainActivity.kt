package app.pipo.nativeapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.pipo.nativeapp.ui.PipoNativeApp

class MainActivity : ComponentActivity() {
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
