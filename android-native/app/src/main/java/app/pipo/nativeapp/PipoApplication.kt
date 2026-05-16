package app.pipo.nativeapp

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import coil.Coil
import app.pipo.nativeapp.data.JsonRustPipoBridge
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.RustBridgeRepository
import app.pipo.nativeapp.runtime.AppForeground

class PipoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogStore.install(this)
        PipoGraph.installContext(this)
        registerForegroundTracker()
        installRustBridgeWhenPackaged()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Coil.imageLoader(this).memoryCache?.clear()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Coil.imageLoader(this).memoryCache?.clear()
    }

    private fun installRustBridgeWhenPackaged() {
        try {
            PipoGraph.installRepository(
                RustBridgeRepository(
                    bridge = JsonRustPipoBridge(filesDir.absolutePath),
                    appContext = applicationContext,
                ),
            )
        } catch (_: UnsatisfiedLinkError) {
            // The native bridge is optional during UI migration and local desktop edits.
        } catch (_: SecurityException) {
            // Keep demo data if the runtime blocks native library loading.
        }
    }

    private fun registerForegroundTracker() {
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                AppForeground.onActivityResumed()
            }

            override fun onActivityPaused(activity: Activity) {
                AppForeground.onActivityPaused()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
