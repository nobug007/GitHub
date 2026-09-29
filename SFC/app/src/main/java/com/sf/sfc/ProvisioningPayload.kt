package com.sf.sfc

import org.json.JSONArray
import org.json.JSONObject

data class ProvisioningForm(
    val deviceId: String,
    val elderName: String,
    val guardianName: String,
    val guardianPhone: String,
    val wifiName: String,
    val wifiPassword: String,
    val wifiBssid: String,
    val wifiSsid: String,
    val bluetoothName: String,
    val bluetoothBssid: String,
    val bluetoothSsid: String,
    // BLE address of the SFD device connected during provisioning (shared JSON contract).
    val bleId: String = "",
    // Guardian hotspot SSID advertised when the phone's Mobile Hotspot is on.
    val hotspotSsid: String = "",
    // Guardian hotspot password so a headless device (SFA) can auto-join the hotspot when away.
    val hotspotPassword: String = "",
    // Phone GPS fix captured at provisioning time. Omitted from JSON when no fix is available.
    val centerLat: Double? = null,
    val centerLng: Double? = null
) {
    fun toJson(): String {
        val wifiZone = JSONObject()
            .put("zoneType", "WIFI")
            .put("name", wifiName)
            .put("password", wifiPassword)
            .put("bssid", wifiBssid)
            .put("ssid", wifiSsid)
            // Home Wi-Fi (a fixed router) → FIXED_AP: uses the stored GPS center.
            .put("apType", "FIXED_AP")
        // Only attach the phone GPS center when an actual fix was captured.
        if (centerLat != null && centerLng != null) {
            wifiZone.put("centerLat", centerLat)
            wifiZone.put("centerLng", centerLng)
        }

        val bleZone = JSONObject()
            .put("zoneType", "BLE")
            .put("name", bluetoothName)
            .put("bssid", bluetoothBssid)
            // The mobile/hotspot zone advertises the guardian hotspot SSID.
            .put("ssid", hotspotSsid.ifBlank { bluetoothSsid })
            // Hotspot password so a headless device (SFA) can auto-join it when away.
            .put("password", hotspotPassword)
            // SFD BLE address so the backend/SFD can bind the mobile zone to this device.
            .put("bleId", bleId)
            // bleId != null → a registered (guardian) hotspot: mobile, uses live GPS.
            .put("apType", "REGISTERED_HOTSPOT")

        val safeZones = JSONArray()
            .put(wifiZone)
            .put(bleZone)

        return JSONObject()
            .put("deviceId", deviceId)
            // Version of the SFC build that provisioned this device, carried alongside the data so
            // the device (and the server, via registration) can tell which app version set it up.
            .put("provisionedByVersion", SfcConfig.APP_VERSION)
            .put("elderName", elderName)
            .put(
                "guardian",
                JSONObject()
                    .put("name", guardianName)
                    .put("phone", guardianPhone)
            )
            .put("safeZones", safeZones)
            .toString()
    }
}
