package com.sf.sfc

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import java.net.NetworkInterface
import java.util.Collections

/** Best-effort defaults for registering the guardian phone's own Mobile Hotspot as a safe zone. */
data class HotspotDefaults(
    val ssid: String,
    val password: String,
    val bssid: String
)

/**
 * Reads what it can of the phone's Mobile Hotspot configuration so the "내 폰 추가" form can be
 * pre-filled. On modern Android (11+) the hotspot SSID/password and the Wi-Fi MAC are hidden from
 * ordinary apps, so every field is best-effort only — the form keeps them editable so the guardian
 * can type whatever could not be read automatically.
 */
class PhoneHotspotReader(private val context: Context) {

    fun read(): HotspotDefaults {
        val (ssid, password) = readApConfig()
        return HotspotDefaults(
            ssid = ssid.ifBlank { SfcConfig.HOTSPOT_SSID },
            password = password,
            bssid = readPhoneMac()
        )
    }

    // getWifiApConfiguration() is a hidden API; on Android 11+ it is blocked for normal apps
    // (throws SecurityException / returns null), so this yields blanks there and the user fills in.
    private fun readApConfig(): Pair<String, String> = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val method = wm.javaClass.getMethod("getWifiApConfiguration")
        @Suppress("DEPRECATION")
        val config = method.invoke(wm) as? WifiConfiguration
        val ssid = config?.SSID?.trim('"').orEmpty()
        val pass = config?.preSharedKey?.trim('"').orEmpty()
        ssid to pass
    }.getOrDefault("" to "")

    // The hotspot BSSID equals the phone's Wi-Fi/AP MAC. Android 6+ hides wlan0's real MAC
    // (returns null or 02:00:00:00:00:00), but the ap0/swlan interface is occasionally readable
    // while the hotspot is on. Falls back to blank for the user to enter manually.
    private fun readPhoneMac(): String = runCatching {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        val preferred = listOf("ap0", "swlan0", "wlan1", "wlan0")
        val match = preferred.firstNotNullOfOrNull { name ->
            interfaces.firstOrNull { it.name.equals(name, ignoreCase = true) && it.hardwareAddress != null }
        } ?: interfaces.firstOrNull { it.hardwareAddress != null }
        match?.hardwareAddress
            ?.joinToString(":") { "%02x".format(it) }
            ?.takeUnless { it == "02:00:00:00:00:00" }
            .orEmpty()
    }.getOrDefault("")
}
