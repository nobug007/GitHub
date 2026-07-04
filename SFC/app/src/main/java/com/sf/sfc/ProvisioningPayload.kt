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
    val bluetoothSsid: String
) {
    fun toJson(): String {
        val safeZones = JSONArray()
            .put(
                JSONObject()
                    .put("zoneType", "WIFI")
                    .put("name", wifiName)
                    .put("password", wifiPassword)
                    .put("bssid", wifiBssid)
                    .put("ssid", wifiSsid)
            )
            .put(
                JSONObject()
                    .put("zoneType", "BLE")
                    .put("name", bluetoothName)
                    .put("bssid", bluetoothBssid)
                    .put("ssid", bluetoothSsid)
            )

        return JSONObject()
            .put("deviceId", deviceId)
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
