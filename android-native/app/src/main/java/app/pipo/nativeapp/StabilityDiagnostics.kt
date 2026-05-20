package app.pipo.nativeapp

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

object StabilityDiagnostics {
    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        val app = context.applicationContext
        if (!installed.compareAndSet(false, true)) return
        DiagnosticsLogStore.record(
            area = AREA,
            event = "environment_start",
            fields = environmentFields(app),
        )
        registerScreenAndPowerReceiver(app)
        registerNetworkCallback(app)
    }

    fun recordAppForeground(context: Context, foreground: Boolean, resumedCount: Int) {
        DiagnosticsLogStore.record(
            area = AREA,
            event = if (foreground) "app_foreground" else "app_background",
            fields = environmentFields(context.applicationContext) + mapOf("resumedCount" to resumedCount),
        )
    }

    private fun registerScreenAndPowerReceiver(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val event = when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> "screen_off"
                    Intent.ACTION_SCREEN_ON -> "screen_on"
                    Intent.ACTION_USER_PRESENT -> "user_present"
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "device_idle_changed"
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "power_save_changed"
                    else -> "environment_changed"
                }
                DiagnosticsLogStore.record(
                    area = AREA,
                    event = event,
                    fields = environmentFields(ctx.applicationContext) + mapOf("action" to intent.action),
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun registerNetworkCallback(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        DiagnosticsLogStore.record(
            area = AREA,
            event = "network_initial",
            fields = environmentFields(context),
        )
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    DiagnosticsLogStore.record(
                        area = AREA,
                        event = "network_available",
                        fields = environmentFields(context) + networkFields(manager, network),
                    )
                }

                override fun onLost(network: Network) {
                    DiagnosticsLogStore.record(
                        area = AREA,
                        event = "network_lost",
                        fields = environmentFields(context) + mapOf("network" to network.toString()),
                    )
                }

                override fun onLosing(network: Network, maxMsToLive: Int) {
                    DiagnosticsLogStore.record(
                        area = AREA,
                        event = "network_losing",
                        fields = environmentFields(context) +
                            networkFields(manager, network) +
                            mapOf("maxMsToLive" to maxMsToLive),
                    )
                }

                override fun onUnavailable() {
                    DiagnosticsLogStore.record(
                        area = AREA,
                        event = "network_unavailable",
                        fields = environmentFields(context),
                    )
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    DiagnosticsLogStore.record(
                        area = AREA,
                        event = "network_capabilities",
                        fields = environmentFields(context) + capabilityFields(networkCapabilities),
                    )
                }
            })
        }.onFailure { err ->
            DiagnosticsLogStore.record(
                area = AREA,
                event = "network_monitor_failed",
                fields = mapOf(
                    "errorType" to err::class.java.simpleName,
                    "message" to err.message,
                ),
            )
        }
    }

    private fun environmentFields(context: Context): Map<String, Any?> {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return mapOf(
            "interactive" to power?.isInteractive,
            "deviceIdle" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && power?.isDeviceIdleMode == true),
            "powerSave" to power?.isPowerSaveMode,
            "keyguardLocked" to keyguard?.isKeyguardLocked,
        ) + activeNetworkFields(context)
    }

    private fun activeNetworkFields(context: Context): Map<String, Any?> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyMap()
        val network = manager.activeNetwork ?: return mapOf(
            "hasNetwork" to false,
            "metered" to manager.isActiveNetworkMetered,
        )
        return mapOf(
            "hasNetwork" to true,
            "metered" to manager.isActiveNetworkMetered,
            "network" to network.toString(),
        ) + networkFields(manager, network)
    }

    private fun networkFields(manager: ConnectivityManager, network: Network): Map<String, Any?> {
        val caps = manager.getNetworkCapabilities(network) ?: return mapOf("network" to network.toString())
        return mapOf("network" to network.toString()) + capabilityFields(caps)
    }

    private fun capabilityFields(caps: NetworkCapabilities): Map<String, Any?> {
        return mapOf(
            "transport" to transports(caps),
            "internet" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            "validated" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            "notMetered" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            "notRoaming" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
            "downKbps" to caps.linkDownstreamBandwidthKbps,
            "upKbps" to caps.linkUpstreamBandwidthKbps,
        )
    }

    private fun transports(caps: NetworkCapabilities): String {
        val names = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        }
        return names.joinToString("|").ifBlank { "unknown" }
    }

    private const val AREA = "stability"
}
