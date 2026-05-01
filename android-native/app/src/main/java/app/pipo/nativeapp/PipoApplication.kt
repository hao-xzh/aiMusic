package app.pipo.nativeapp

import android.app.Application
import app.pipo.nativeapp.data.JsonRustPipoBridge
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.RustBridgeRepository

class PipoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PipoGraph.installContext(this)
        installRustBridgeWhenPackaged()
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
}
