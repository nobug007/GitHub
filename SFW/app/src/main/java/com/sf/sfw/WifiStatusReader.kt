package com.sf.sfw

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager

/**
 * Ported from SFD's WifiStatusReader. Passive detection only: SFW never adds
 * network suggestions (SFD's WifiConnector is intentionally not ported because
 * Wear OS manages WiFi itself and suggestion behavior is unreliable there);
 * detecting whether the watch is currently on a safe-zone AP is what matters.
 */
class WifiStatusReader(private val context: Context, private val store: SfwStore) {
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
        // Wear OS frequently redacts the connected SSID to "<unknown ssid>" even for our own app.
        // The connected BSSID is still available, and the AP we are on is guaranteed to be in the
        // scan results — so recover the REAL SSID from the scan entry with that BSSID. Without this,
        // the signal-similarity fallback below can pick a different in-range safe-zone AP (e.g. the
        // guardian hotspot "Nobug" that is also nearby at home) and mislabel the location.
        if (currentSsid.isBlank() && currentBssid.isNotBlank() && currentBssid != "00:00:00:00:00:00" && currentBssid != "<none>") {
            resolveSsidByBssid(wifiManager, currentBssid)?.let { currentSsid = it }
        }
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
            val matched = safeZoneScanMatch(wifiManager, safeZones, currentBssid, info.rssi, info.frequency)
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
            hasWifiConnection = hasWifiTransport && hasWifiConnectionInfo,
            wifiEnabled = wifiEnabled
        )
    }

    /**
     * True when a registered safe-zone AP shows up in a recent scan with usable
     * signal — physical proximity evidence even when the connection dropped.
     */
    @SuppressLint("MissingPermission")
    fun isZoneApNearby(): Boolean {
        val safeZones = store.wifiSafeZones()
        if (safeZones.isEmpty()) return false
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) return false
        val freshLimitUs = (android.os.SystemClock.elapsedRealtime() - SfwConfig.WIFI_SCAN_FRESH_MS) * 1000
        return runCatching {
            wifiManager.scanResults.any { scan ->
                if (scan.timestamp < freshLimitUs) return@any false
                if (scan.level < SfwConfig.WIFI_NEARBY_MIN_RSSI) return@any false
                val scanSsid = cleanSsid(scan.SSID)
                val scanBssid = cleanBssid(scan.BSSID)
                safeZones.any { zone ->
                    val expectedSsid = cleanSsid(zone.optString("ssid"))
                    val expectedName = cleanSsid(zone.optString("name"))
                    val expectedBssid = cleanBssid(zone.optString("bssid"))
                    val ssidMatches = scanSsid.isNotBlank() && (namesMatch(scanSsid, expectedSsid) || namesMatch(scanSsid, expectedName))
                    val bssidMatches = scanBssid.isNotBlank() && expectedBssid.isNotBlank() && scanBssid.equals(expectedBssid, ignoreCase = true)
                    ssidMatches || bssidMatches
                }
            }
        }.getOrDefault(false)
    }

    /** Real SSID of the AP we are physically connected to, recovered from the scan by its BSSID. */
    @SuppressLint("MissingPermission")
    private fun resolveSsidByBssid(wifiManager: WifiManager, bssid: String): String? = runCatching {
        wifiManager.scanResults
            .firstOrNull { cleanBssid(it.BSSID) == bssid }
            ?.let { cleanSsid(it.SSID).takeIf { s -> s.isNotBlank() } }
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun safeZoneScanMatch(wifiManager: WifiManager, safeZones: List<org.json.JSONObject>, currentBssid: String, currentRssi: Int, currentFrequency: Int) =
        runCatching {
            val scans = wifiManager.scanResults
            fun matchesZone(scanSsid: String, scanBssid: String) = safeZones.any { zone ->
                val expectedSsid = cleanSsid(zone.optString("ssid"))
                val expectedName = cleanSsid(zone.optString("name"))
                val expectedBssid = cleanBssid(zone.optString("bssid"))
                val ssidMatches = scanSsid.isNotBlank() && (namesMatch(scanSsid, expectedSsid) || namesMatch(scanSsid, expectedName))
                val bssidMatches = scanBssid.isNotBlank() && expectedBssid.isNotBlank() && scanBssid.equals(expectedBssid, ignoreCase = true)
                ssidMatches || bssidMatches
            }
            // Prefer the scan entry that IS the connected AP (exact BSSID) so a different in-range
            // safe-zone AP (e.g. the guardian hotspot near home) can never be mistaken for it.
            if (currentBssid.isNotBlank()) {
                scans.firstOrNull { cleanBssid(it.BSSID) == currentBssid && matchesZone(cleanSsid(it.SSID), cleanBssid(it.BSSID)) }
                    ?.let { return@runCatching it }
            }
            // Fallback: an AP with connection-like signal on the same channel that matches a zone.
            scans.firstOrNull { scan ->
                val signalLooksConnected = currentRssi > -90 &&
                    scan.frequency == currentFrequency &&
                    kotlin.math.abs(scan.level - currentRssi) <= 10
                signalLooksConnected && matchesZone(cleanSsid(scan.SSID), cleanBssid(scan.BSSID))
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
