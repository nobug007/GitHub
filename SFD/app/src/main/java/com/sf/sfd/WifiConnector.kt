package com.sf.sfd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import org.json.JSONObject

class WifiConnector(private val context: Context, private val store: SfdStore) {

    private fun wifiManager(): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Initial connection call, kept for compatibility with existing callers. On Android Q+
     * this registers suggestions for every known network (hotspot + safezones); on legacy
     * platforms it falls back to the addNetwork/enableNetwork/reconnect path for the first
     * saved safezone.
     */
    @SuppressLint("MissingPermission")
    fun requestConnection(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return registerAllSuggestions()
        }
        val ssid = store.firstWifiSsid()
        if (ssid.isBlank()) return "Wi-Fi connect skipped: no saved SSID"
        return legacyConnect(ssid, store.firstWifiPassword())
    }

    /**
     * Adds WifiNetworkSuggestions for the guardian hotspot plus every saved safezone (in
     * saved order). The OS auto-joins available suggested networks by its own policy, so
     * strict save-order priority is best-effort; the tablet's already-saved networks
     * ("Nobug" is pre-saved) do most of the work and these suggestions cover the rest.
     */
    @SuppressLint("MissingPermission")
    fun registerAllSuggestions(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val ssid = store.firstWifiSsid()
            if (ssid.isBlank()) return "Wi-Fi suggestion skipped: no saved SSID"
            return legacyConnect(ssid, store.firstWifiPassword())
        }
        val suggestions = buildSuggestions()
        if (suggestions.isEmpty()) return "Wi-Fi suggestion skipped: no networks to register"
        val wifiManager = wifiManager()
        // Clear our previously registered suggestions first to avoid ADD_DUPLICATE errors
        // when the safezone list changes between ticks.
        runCatching { wifiManager.removeNetworkSuggestions(emptyList<WifiNetworkSuggestion>()) }
        val result = wifiManager.addNetworkSuggestions(suggestions)
        return if (result == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            "Wi-Fi suggestions registered (${suggestions.size})"
        } else {
            "Wi-Fi suggestion result $result (${suggestions.size})"
        }
    }

    /** Prefer joining the guardian hotspot (SSID = SfdConfig.HOTSPOT_SSID). */
    @SuppressLint("MissingPermission")
    fun connectToHotspot(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val msg = registerAllSuggestions()
            return "Hotspot join requested (${SfdConfig.HOTSPOT_SSID}); $msg"
        }
        return legacyConnect(SfdConfig.HOTSPOT_SSID, hotspotPassword())
    }

    /** Prefer joining a specific safezone (used for save-order priority when away from hotspot). */
    @SuppressLint("MissingPermission")
    fun connectToZone(zone: JSONObject): String {
        val ssid = zone.optString("ssid", zone.optString("name"))
        if (ssid.isBlank()) return "Wi-Fi zone connect skipped: no SSID"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val msg = registerAllSuggestions()
            return "Safezone join requested ($ssid); $msg"
        }
        return legacyConnect(ssid, zone.optString("password"))
    }

    private fun buildSuggestions(): List<WifiNetworkSuggestion> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val list = mutableListOf<WifiNetworkSuggestion>()
        val seen = mutableSetOf<String>()
        // Hotspot first: use a passphrase only if a safezone entry named/ssid'd "Nobug"
        // carries one (detecting/joining it does not strictly require the password because
        // the tablet already has "Nobug" saved).
        buildSuggestion(SfdConfig.HOTSPOT_SSID, hotspotPassword())?.let {
            list += it
            seen += SfdConfig.HOTSPOT_SSID.lowercase()
        }
        store.wifiSafeZones().forEach { zone ->
            val ssid = zone.optString("ssid", zone.optString("name"))
            if (ssid.isBlank()) return@forEach
            if (!seen.add(ssid.lowercase())) return@forEach
            buildSuggestion(ssid, zone.optString("password"))?.let { list += it }
        }
        return list
    }

    private fun buildSuggestion(ssid: String, password: String): WifiNetworkSuggestion? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .apply { if (password.isNotBlank()) setWpa2Passphrase(password) }
                .build()
        }.getOrNull()
    }

    /** Password for the guardian hotspot if a safezone entry named/ssid'd "Nobug" carries one. */
    private fun hotspotPassword(): String {
        return store.wifiSafeZones().firstOrNull { zone ->
            zone.optString("ssid").equals(SfdConfig.HOTSPOT_SSID, ignoreCase = true) ||
                zone.optString("name").equals(SfdConfig.HOTSPOT_SSID, ignoreCase = true)
        }?.optString("password").orEmpty()
    }

    @SuppressLint("MissingPermission")
    private fun legacyConnect(ssid: String, password: String): String {
        if (context.checkSelfPermission(Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            return "Wi-Fi connect skipped: CHANGE_WIFI_STATE permission missing"
        }
        val wifiManager = wifiManager()
        @Suppress("DEPRECATION")
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (password.isBlank()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = "\"$password\""
            }
        }
        @Suppress("DEPRECATION")
        val networkId = wifiManager.addNetwork(config)
        if (networkId < 0) return "Wi-Fi network add failed: $ssid"
        @Suppress("DEPRECATION")
        wifiManager.enableNetwork(networkId, true)
        @Suppress("DEPRECATION")
        wifiManager.reconnect()
        return "Wi-Fi connection requested: $ssid"
    }
}
