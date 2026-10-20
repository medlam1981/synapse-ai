package com.synapse.localai.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.hardware.usb.UsbManager
import android.app.ActivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface

object TelemetryProbe {

    private val domains = setOf("system", "network", "battery", "storage", "all")

    suspend fun probe(context: Context, requestedDomain: String): String = withContext(Dispatchers.IO) {
        val domain = requestedDomain.trim().lowercase()
        require(domain in domains) {
            "Unsupported telemetry domain '$requestedDomain'. Use: ${domains.sorted().joinToString()}."
        }

        val appContext = context.applicationContext
        JSONObject().apply {
            put("domain", domain)
            put("captured_at_epoch_ms", System.currentTimeMillis())
            if (domain == "system" || domain == "all") put("system", systemMetrics(appContext))
            if (domain == "network" || domain == "all") put("network", networkMetrics(appContext))
            if (domain == "battery" || domain == "all") put("battery", batteryMetrics(appContext))
            if (domain == "storage" || domain == "all") put("storage", storageMetrics(appContext))
        }.toString()
    }

    private fun systemMetrics(context: Context): JSONObject {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val metrics = JSONObject()
            .put("available_ram_bytes", memory.availMem)
            .put("total_ram_bytes", memory.totalMem)
            .put("low_memory", memory.lowMemory)
            .put("processor_count", Runtime.getRuntime().availableProcessors())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            metrics.put("thermal_status", power.currentThermalStatus)
        }
        return metrics
    }

    private fun networkMetrics(context: Context): JSONObject {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val transports = JSONArray()
        if (capabilities != null) {
            listOf(
                NetworkCapabilities.TRANSPORT_WIFI to "wifi",
                NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
                NetworkCapabilities.TRANSPORT_VPN to "vpn"
            ).forEach { (transport, name) ->
                if (capabilities.hasTransport(transport)) transports.put(name)
            }
        }
        val interfaces = JSONArray()
        val interfaceEnumeration = NetworkInterface.getNetworkInterfaces()
        while (interfaceEnumeration?.hasMoreElements() == true) {
            val networkInterface = interfaceEnumeration.nextElement()
            interfaces.put(
                JSONObject()
                    .put("name", networkInterface.name)
                    .put("up", networkInterface.isUp)
                    .put("mtu", networkInterface.mtu)
            )
        }
        val socketCounts = JSONObject()
            .put("tcp", procTableEntryCount("/proc/net/tcp") ?: JSONObject.NULL)
            .put("tcp6", procTableEntryCount("/proc/net/tcp6") ?: JSONObject.NULL)
            .put("udp", procTableEntryCount("/proc/net/udp") ?: JSONObject.NULL)
            .put("udp6", procTableEntryCount("/proc/net/udp6") ?: JSONObject.NULL)
        return JSONObject()
            .put("connected", capabilities != null)
            .put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
            .put("metered", manager.isActiveNetworkMetered)
            .put("transports", transports)
            .put("interfaces", interfaces)
            .put("route_entries", procTableEntryCount("/proc/net/route") ?: JSONObject.NULL)
            .put("socket_counts", socketCounts)
    }

    private fun batteryMetrics(context: Context): JSONObject {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percentage = if (level >= 0 && scale > 0) level * 100 / scale else null
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val temperatureTenthsC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return JSONObject()
            .put("percentage", percentage ?: JSONObject.NULL)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL)
            .put("plugged", plugged != 0)
            .put("voltage_mv", voltageMv.takeIf { it >= 0 } ?: JSONObject.NULL)
            .put("battery_temperature_c", temperatureTenthsC.takeIf { it >= 0 }?.div(10f) ?: JSONObject.NULL)
            .put(
                "thermal_status",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
                } else {
                    JSONObject.NULL
                }
            )
            .put("current_now_microamps", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            .put("charge_counter_microamp_hours", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            .put("usb_otg_devices", (context.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.size)
    }

    private fun storageMetrics(context: Context): JSONObject {
        val files = File(context.filesDir.absolutePath)
        val stat = StatFs(files.absolutePath)
        return JSONObject()
            .put("path", files.absolutePath)
            .put("total_bytes", stat.totalBytes)
            .put("available_bytes", stat.availableBytes)
            .put("used_bytes", stat.totalBytes - stat.availableBytes)
    }

    private fun procTableEntryCount(path: String): Int? {
        val file = File(path)
        if (!file.isFile || !file.canRead()) return null
        return (file.useLines { lines -> lines.count() } - 1).coerceAtLeast(0)
    }
}
