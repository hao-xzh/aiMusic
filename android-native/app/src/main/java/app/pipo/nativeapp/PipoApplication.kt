package app.pipo.nativeapp

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import app.pipo.nativeapp.data.JsonRustPipoBridge
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.RustBridgeRepository
import app.pipo.nativeapp.runtime.AppForeground

class PipoApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsLogStore.install(this)
        CrashLogStore.install(this)
        StabilityDiagnostics.install(this)
        PipoGraph.installContext(this)
        registerForegroundTracker()
        installRustBridgeWhenPackaged()
        // Repository installation must finish before a recovered task can reach the model/tools.
        PipoGraph.agentTasks.scheduleRecovery()
    }

    @Suppress("DEPRECATION")
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

    // Coil 单例 ImageLoader 工厂入口。注册 Base64UriFetcher 是为了让 PlayerViewModel
    // 用 data URI 喂出来的 ExoPlayer 内嵌封面（MP3 ID3 / FLAC / M4A）能被 AsyncImage
    // 解出来 —— 默认 Coil 2.7 不带 data: scheme fetcher，不注册的话云盘上传歌的封面
    // 在 in-app UI 永远是空的（系统状态栏走 MediaSession 不受影响）。
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // 复用全 app 共享的 OkHttp（连接池 / DNS 与歌词、天气共用）。Coil 自己的内存 /
            // 磁盘图片缓存是独立的一层，不受这里影响，封面缓存行为不变。
            .okHttpClient(app.pipo.nativeapp.data.PipoHttp.client)
            .components { add(Base64UriFetcher.Factory()) }
            .build()

    private fun installRustBridgeWhenPackaged() {
        try {
            PipoGraph.installRepository(
                RustBridgeRepository(
                    bridge = JsonRustPipoBridge(filesDir.absolutePath),
                    appContext = applicationContext,
                ),
            )
            DiagnosticsLogStore.record("app", "repository_installed")
        } catch (_: UnsatisfiedLinkError) {
            // The native bridge is optional during UI migration and local desktop edits.
            DiagnosticsLogStore.record("app", "repository_native_bridge_missing")
        } catch (_: SecurityException) {
            // Keep demo data if the runtime blocks native library loading.
            DiagnosticsLogStore.record("app", "repository_install_blocked")
        }
    }

    private fun registerForegroundTracker() {
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                AppForeground.onActivityResumed(activity.applicationContext)
            }

            override fun onActivityPaused(activity: Activity) {
                AppForeground.onActivityPaused(activity.applicationContext)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
