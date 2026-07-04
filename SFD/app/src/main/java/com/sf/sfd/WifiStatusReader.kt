package com.sf.sfd

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager

class WifiStatusReader(private val context: Context, private val store: SfdStore) {
    @SuppressLint("MissingPermission")
    fun read(): WifiStatus {
        val safeZones = store.wifiSafeZones()
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiEnabled = wifiManager.isWifiEnabled
        val wifiNetworkInfo = connectivityManager.allNetworks
            .asSequence()
            .mapNotNull { network -> connectivityManager.getNetworkCapabilities(network) }
            .firstOrNull { capabilities -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            ?.transportInfo as? WifiInfo
        val managerInfo = wifiManager.connectionInfo
        val hasWifiTransport = wifiEnabled && (wifiNetworkInfo != null || connectivityManager.allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        })
        val info = if (hasWifiTransport) {
            listOfNotNull(wifiNetworkInfo, managerInfo)
                .firstOrNull { candidate ->
                    val ssid = cleanSsid(candidate.ssid.orEmpty())
                    val bssid = cleanBssid(candidate.bssid.orEmpty())
                    ssid.isNotBlank() || (bssid.isNotBlank() && bssid != "00:00:00:00:00:00" && bssid != "<none>")
                }
                ?: wifiNetworkInfo
                ?: managerInfo
        } else {
            null
        }
        var currentSsid = cleanSsid(info?.ssid.orEmpty())
        var currentBssid = cleanBssid(info?.bssid.orEmpty())
        val usableBssid = currentBssid.isNotBlank() && currentBssid != "00:00:00:00:00:00" && currentBssid != "<none>"
        val hasWifiConnectionInfo = currentSsid.isNotBlank() || usableBssid
        var attached = hasWifiTransport && hasWifiConnectionInfo && safeZones.any { zone ->
            val expectedSsid = cleanSsid(zone.optString("ssid"))
            val expectedName = cleanSsid(zone.optString("name"))
            val expectedBssid = cleanBssid(zone.optString("bssid"))
            val ssidMatches = currentSsid.isNotBlank() && (namesMatch(currentSsid, expectedSsid) || namesMatch(currentSsid, expectedName))
            val bssidMatches = usableBssid && expectedBssid.isNotBlank() && currentBssid.equals(expectedBssid, ignoreCase = true)
            ssidMatches || bssidMatches
        }

        if (hasWifiTransport && !attached && info != null) {
            val matched = safeZoneScanMatch(wifiManager, safeZones, info.rssi, info.frequency)
            if (matched != null) {
                currentSsid = cleanSsid(matched.SSID)
                currentBssid = cleanBssid(matched.BSSID)
                attached = true
            }
        }

        val apName = if (hasWifiTransport && currentSsid.isNotBlank()) currentSsid else ""
        return WifiStatus(
            apName = apName,
            bssid = currentBssid,
            signal = info?.rssi ?: -127,
            isAttached = attached,
            hasWifiConnection = hasWifiTransport && hasWifiConnectionInfo
        )
    }

    @SuppressLint("MissingPermission")
    private fun safeZoneScanMatch(wifiManager: WifiManager, safeZones: List<org.json.JSONObject>, currentRssi: Int, currentFrequency: Int) =
        runCatching {
            wifiManager.scanResults.firstOrNull { scan ->
                val scanSsid = cleanSsid(scan.SSID)
                val scanBssid = cleanBssid(scan.BSSID)
                val signalLooksConnected = currentRssi > -90 &&
                    scan.frequency == currentFrequency &&
                    kotlin.math.abs(scan.level - currentRssi) <= 10
                if (!signalLooksConnected) return@firstOrNull false
                safeZones.any { zone ->
                    val expectedSsid = cleanSsid(zone.optString("ssid"))
                    val expectedName = cleanSsid(zone.optString("name"))
                    val expectedBssid = cleanBssid(zone.optString("bssid"))
                    val ssidMatches = scanSsid.isNotBlank() && (namesMatch(scanSsid, expectedSsid) || namesMatch(scanSsid, expectedName))
                    val bssidMatches = scanBssid.isNotBlank() && expectedBssid.isNotBlank() && scanBssid.equals(expectedBssid, ignoreCase = true)
                    ssidMatches || bssidMatches
                }
            }
        }.getOrNull()

    private fun namesMatch(current: String, expected: String): Boolean {
        if (expected.isBlank()) return false
        return current.equals(expected, ignoreCase = true) || normalizeWifiName(current).equals(normalizeWifiName(expected), ignoreCase = true)
    }

    private fun normalizeWifiName(value: String): String = cleanSsid(value)
        .removeSuffix("_5G")
        .removeSuffix("_2G")
        .removeSuffix("-5G")
        .removeSuffix("-2G")
        .removeSuffix(" 5G")
        .removeSuffix(" 2G")

    private fun cleanSsid(value: String): String = value.trim().trim('"').takeIf { it != "<unknown ssid>" }.orEmpty()

    private fun cleanBssid(value: String): String = value.trim().lowercase().takeIf { it != "<none>" }.orEmpty()
}
