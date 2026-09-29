package com.sf.sfd

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

class WifiStatusReader(private val context: Context, private val store: SfdStore) {
    @SuppressLint("MissingPermission")
    fun read(): WifiStatus {
        val safeZones = store.wifiSafeZones()
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiEnabled = wifiManager.isWifiEnabled
        // NetworkCapabilities.getTransportInfo() is API 29+ (crashes with NoSuchMethodError on
        // Android 9 / API 28 tablets). Only use it on Q+; on older platforms we fall back to
        // wifiManager.connectionInfo below, which is available on all supported API levels.
        val wifiNetworkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectivityManager.allNetworks
                .asSequence()
                .mapNotNull { network -> connectivityManager.getNetworkCapabilities(network) }
                .firstOrNull { capabilities -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
                ?.transportInfo as? WifiInfo
        } else {
            null
        }
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
        // Android may redact the connected SSID to "<unknown ssid>". The connected BSSID is still
        // available and our AP is guaranteed to be in the scan results, so recover the REAL SSID
        // from the scan entry with that BSSID. Without this, the signal-similarity fallback can pick
        // a different in-range safe-zone AP (e.g. the guardian hotspot nearby at home) and mislabel it.
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
        val freshLimitUs = (android.os.SystemClock.elapsedRealtime() - SfdConfig.WIFI_SCAN_FRESH_MS) * 1000
        return runCatching {
            wifiManager.scanResults.any { scan ->
                if (scan.timestamp < freshLimitUs) return@any false
                if (scan.level < SfdConfig.WIFI_NEARBY_MIN_RSSI) return@any false
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

    /**
     * True when the current managed Wi-Fi connection matches the given SSID (case- and
     * suffix-insensitive). Used to recognize "connected to hotspot"; the hotspot is not a
     * registered safezone so isAttached is false for it, but apName still carries the SSID.
     */
    fun connectedToSsid(status: WifiStatus, target: String): Boolean {
        if (!status.hasWifiConnection) return false
        val cleanTarget = cleanSsid(target)
        return status.apName.isNotBlank() && namesMatch(status.apName, cleanTarget)
    }

    /** True when an AP with the given SSID appears in a recent scan (e.g. the guardian hotspot). */
    @SuppressLint("MissingPermission")
    fun isSsidNearby(target: String): Boolean {
        val cleanTarget = cleanSsid(target)
        if (cleanTarget.isBlank()) return false
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) return false
        val freshLimitUs = (android.os.SystemClock.elapsedRealtime() - SfdConfig.WIFI_SCAN_FRESH_MS) * 1000
        return runCatching {
            wifiManager.scanResults.any { scan ->
                if (scan.timestamp < freshLimitUs) return@any false
                val scanSsid = cleanSsid(scan.SSID)
                scanSsid.isNotBlank() && namesMatch(scanSsid, cleanTarget)
            }
        }.getOrDefault(false)
    }

    /**
     * First safezone (in saved priority order) whose ssid/name/bssid appears in a recent
     * scan, or null when none is nearby. Drives save-order safezone selection when away
     * from the guardian hotspot.
     */
    @SuppressLint("MissingPermission")
    fun bestSafezoneInRange(): org.json.JSONObject? {
        val safeZones = store.wifiSafeZones()
        if (safeZones.isEmpty()) return null
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) return null
        val freshLimitUs = (android.os.SystemClock.elapsedRealtime() - SfdConfig.WIFI_SCAN_FRESH_MS) * 1000
        val fresh = runCatching { wifiManager.scanResults }.getOrDefault(emptyList())
            .filter { it.timestamp >= freshLimitUs }
        if (fresh.isEmpty()) return null
        for (zone in safeZones) {
            val expectedSsid = cleanSsid(zone.optString("ssid"))
            val expectedName = cleanSsid(zone.optString("name"))
            val expectedBssid = cleanBssid(zone.optString("bssid"))
            val match = fresh.any { scan ->
                val scanSsid = cleanSsid(scan.SSID)
                val scanBssid = cleanBssid(scan.BSSID)
                val ssidMatches = scanSsid.isNotBlank() && (namesMatch(scanSsid, expectedSsid) || namesMatch(scanSsid, expectedName))
                val bssidMatches = scanBssid.isNotBlank() && expectedBssid.isNotBlank() && scanBssid.equals(expectedBssid, ignoreCase = true)
                ssidMatches || bssidMatches
            }
            if (match) return zone
        }
        return null
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
