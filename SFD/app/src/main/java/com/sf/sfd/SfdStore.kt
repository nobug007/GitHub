package com.sf.sfd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SfdStore(context: Context) {
    private val prefs = context.getSharedPreferences("sfd_store", Context.MODE_PRIVATE)

    fun clearAll() {
        prefs.edit().clear().apply()
        appendLog("Device data reset")
    }
    fun saveConfig(json: String) {
        val deviceId = runCatching { JSONObject(json).optString("deviceId", SfdConfig.DEFAULT_DEVICE_ID) }
            .getOrDefault(SfdConfig.DEFAULT_DEVICE_ID)
        prefs.edit()
            .putString("config_json", json)
            .putString("device_id", deviceId)
            .apply()
        appendLog("Config saved for $deviceId")
    }

    fun applySafeZoneUpdate(updateJson: String): Boolean {
        val update = JSONObject(updateJson)
        val action = update.optString("action").uppercase()
        val incoming = update.optJSONObject("zone") ?: return false
        val zoneId = incoming.optString("zoneId")
        val currentConfig = JSONObject(prefs.getString("config_json", null) ?: JSONObject().toString())
        if (!currentConfig.has("deviceId")) {
            currentConfig.put("deviceId", update.optString("deviceId", SfdConfig.DEFAULT_DEVICE_ID))
        }
        val zones = currentConfig.optJSONArray("safeZones") ?: JSONArray().also { currentConfig.put("safeZones", it) }

        when (action) {
            "CREATE" -> {
                var replaced = false
                for (index in 0 until zones.length()) {
                    val zone = zones.optJSONObject(index) ?: continue
                    if (zone.optString("zoneId") == zoneId && zoneId.isNotBlank()) {
                        zones.put(index, incoming)
                        replaced = true
                        break
                    }
                }
                if (!replaced) zones.put(incoming)
            }
            "PATCH", "UPDATE" -> {
                for (index in 0 until zones.length()) {
                    val zone = zones.optJSONObject(index) ?: continue
                    if (zone.optString("zoneId") == zoneId || sameZone(zone, incoming)) {
                        incoming.keys().forEach { key -> zone.put(key, incoming.opt(key)) }
                        zones.put(index, zone)
                        break
                    }
                }
            }
            "DELETE" -> {
                val next = JSONArray()
                for (index in 0 until zones.length()) {
                    val zone = zones.optJSONObject(index) ?: continue
                    if (zone.optString("zoneId") == zoneId || sameZone(zone, incoming)) continue
                    next.put(zone)
                }
                currentConfig.put("safeZones", next)
            }
            else -> return false
        }

        prefs.edit()
            .putString("config_json", currentConfig.toString())
            .putString("device_id", currentConfig.optString("deviceId", SfdConfig.DEFAULT_DEVICE_ID))
            .apply()
        appendLog("SafeZone update applied: $action ${incoming.optString("name", zoneId)}")
        appendMessage("SAFEZONE UPDATE", updateJson)
        return true
    }

    fun saveBleSafeZone(connected: Boolean, address: String = "", name: String = "") {
        prefs.edit()
            .putBoolean("ble_safe_connected", connected)
            .putString("ble_safe_address", address)
            .putString("ble_safe_name", name)
            .putLong("ble_safe_at", System.currentTimeMillis())
            .apply()
    }

    fun isBleSafeZoneActive(): Boolean {
        if (!prefs.getBoolean("ble_safe_connected", false)) return false
        val ageMs = System.currentTimeMillis() - prefs.getLong("ble_safe_at", 0L)
        return ageMs <= 2 * 60 * 1000L
    }

    fun bleSafeZoneName(): String = prefs.getString("ble_safe_name", "") ?: ""

    fun bleSafeZoneAddress(): String = prefs.getString("ble_safe_address", "") ?: ""

    fun guardianPhone(): String {
        val json = prefs.getString("config_json", null) ?: return ""
        return runCatching { JSONObject(json).optJSONObject("guardian")?.optString("phone").orEmpty() }.getOrDefault("")
    }

    fun guardianPhones(): List<String> {
        val json = prefs.getString("config_json", null) ?: return emptyList()
        return runCatching {
            val config = JSONObject(json)
            val phones = linkedSetOf<String>()
            config.optJSONObject("guardian")?.optString("phone").orEmpty().takeIf { it.isNotBlank() }?.let { phones += it }
            val guardians = config.optJSONArray("guardians") ?: JSONArray()
            for (index in 0 until guardians.length()) {
                val phone = guardians.optJSONObject(index)?.optString("phone").orEmpty()
                if (phone.isNotBlank()) phones += phone
            }
            phones.toList()
        }.getOrDefault(emptyList())
    }

    fun elderId(): String {
        val json = prefs.getString("config_json", null) ?: return ""
        return runCatching { JSONObject(json).optString("elderId") }.getOrDefault("")
    }

    fun elderName(): String {
        val json = prefs.getString("config_json", null) ?: return ""
        return runCatching { JSONObject(json).optString("elderName") }.getOrDefault("")
    }

    fun saveServerSync(elder: JSONObject, guardians: JSONArray, safeZones: JSONArray) {
        val deviceId = elder.optString("deviceId", prefs.getString("device_id", SfdConfig.DEFAULT_DEVICE_ID))
        val normalizedZones = JSONArray()
        for (index in 0 until safeZones.length()) {
            val zone = safeZones.optJSONObject(index) ?: continue
            normalizedZones.put(JSONObject()
                .put("zoneId", zone.optString("zoneId"))
                .put("zoneType", zone.optString("zoneType"))
                .put("name", zone.optString("name"))
                .put("bssid", zone.optString("bssid"))
                .put("ssid", zone.optString("ssid"))
                .put("enabled", zone.optBoolean("enabled", true))
            )
        }
        val normalizedGuardians = JSONArray()
        for (index in 0 until guardians.length()) {
            val guardian = guardians.optJSONObject(index) ?: continue
            normalizedGuardians.put(JSONObject()
                .put("id", guardian.optInt("id"))
                .put("name", guardian.optString("name"))
                .put("phone", guardian.optString("phone"))
                .put("relation", guardian.optString("relation"))
            )
        }
        val primaryGuardian = normalizedGuardians.optJSONObject(0) ?: JSONObject()
        val config = JSONObject(prefs.getString("config_json", null) ?: "{}")
            .put("deviceId", deviceId)
            .put("elderName", elder.optString("name"))
            .put("elderId", elder.optString("elderId"))
            .put("guardian", JSONObject()
                .put("name", primaryGuardian.optString("name"))
                .put("phone", primaryGuardian.optString("phone"))
                .put("relation", primaryGuardian.optString("relation"))
            )
            .put("guardians", normalizedGuardians)
            .put("safeZones", normalizedZones)

        prefs.edit()
            .putString("config_json", config.toString())
            .putString("device_id", deviceId)
            .apply()
        appendLog("Config sync saved: guardians=${normalizedGuardians.length()}, safeZones=${normalizedZones.length()}")
        appendMessage("CONFIG SYNC", config.toString())
    }

    fun saveLastVerb(verb: String) {
        prefs.edit().putString("last_verb", verb).apply()
    }

    fun lastVerb(): String = prefs.getString("last_verb", "") ?: ""

    fun saveRegisterResult(text: String) {
        prefs.edit().putString("last_register_result", text).apply()
        appendLog(text)
        appendMessage("REGISTER RESPONSE", text)
    }

    fun saveTelemetryResult(text: String) {
        prefs.edit()
            .putString("last_telemetry_result", text)
            .putString("last_server_response", text)
            .apply()
        appendLog(text)
        appendMessage("TELEMETRY RESPONSE", text)
    }

    fun saveOutgoingTelemetry(payload: String) {
        appendMessage("TELEMETRY REQUEST", payload)
    }

    fun nextTelemetrySeq(): Int {
        val next = prefs.getInt("next_telemetry_seq", 10500)
        prefs.edit().putInt("next_telemetry_seq", next + 1).apply()
        return next
    }

    fun saveServerResponse(text: String) {
        prefs.edit().putString("last_server_response", text).apply()
    }

    fun isConfigured(): Boolean = prefs.getString("config_json", null) != null && hasWifiSafeZone()

    fun addGyroSample(sample: GyroSample) {
        val samples = recentGyroSamples().toMutableList()
        samples += sample
        while (samples.size > SfdConfig.GYRO_REPORT_SAMPLE_COUNT) samples.removeAt(0)
        val array = JSONArray()
        samples.forEach {
            array.put(
                JSONObject()
                    .put("timestampMs", it.timestampMs)
                    .put("gyX", it.gyX)
                    .put("gyY", it.gyY)
                    .put("gyZ", it.gyZ)
            )
        }
        prefs.edit().putString("gyro_samples", array.toString()).apply()
    }

    fun recentGyroSamples(): List<GyroSample> {
        val raw = prefs.getString("gyro_samples", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                GyroSample(
                    timestampMs = item.optLong("timestampMs"),
                    gyX = item.optDouble("gyX"),
                    gyY = item.optDouble("gyY"),
                    gyZ = item.optDouble("gyZ")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun appendLog(text: String) {
        val logs = logs().toMutableList()
        logs.add(0, text)
        while (logs.size > 10) logs.removeAt(logs.lastIndex)
        prefs.edit().putString("logs", JSONArray(logs).toString()).apply()
    }

    fun logs(): List<String> = readStringArray("logs")

    fun appendMessage(kind: String, text: String) {
        val messages = messageHistory().toMutableList()
        messages.add(0, "[$kind]\n${text.take(3000)}")
        while (messages.size > 20) messages.removeAt(messages.lastIndex)
        prefs.edit().putString("message_history", JSONArray(messages).toString()).apply()
    }

    fun messageHistory(): List<String> = readStringArray("message_history")

    fun currentState(): SfdState = SfdState(
        isConfigured = isConfigured(),
        deviceId = prefs.getString("device_id", SfdConfig.DEFAULT_DEVICE_ID) ?: SfdConfig.DEFAULT_DEVICE_ID,
        configJson = prefs.getString("config_json", null),
        lastRegisterResult = prefs.getString("last_register_result", null),
        lastTelemetryResult = prefs.getString("last_telemetry_result", null),
        lastServerResponse = prefs.getString("last_server_response", null),
        logs = logs(),
        messageHistory = messageHistory(),
        gyroSamples = recentGyroSamples()
    )

    fun hasWifiSafeZone(): Boolean = wifiSafeZone() != null

    fun wifiSafeZones(): List<JSONObject> {
        val json = prefs.getString("config_json", null) ?: return emptyList()
        return runCatching {
            val zones = JSONObject(json).optJSONArray("safeZones") ?: return emptyList()
            val result = mutableListOf<JSONObject>()
            for (index in 0 until zones.length()) {
                val zone = zones.optJSONObject(index) ?: continue
                if (zone.optString("zoneType").equals("WIFI", ignoreCase = true) && zone.optBoolean("enabled", true)) {
                    result += zone
                }
            }
            result
        }.getOrDefault(emptyList())
    }

    fun wifiSafeZone(): JSONObject? {
        return wifiSafeZones().firstOrNull()
    }

    fun firstWifiZoneName(): String {
        val zone = wifiSafeZone() ?: return "No Wi-Fi Config"
        return zone.optString("name", zone.optString("ssid", "No Wi-Fi Config"))
    }

    fun firstWifiSsid(): String {
        val zone = wifiSafeZone() ?: return ""
        return zone.optString("ssid", zone.optString("name", ""))
    }

    fun firstWifiPassword(): String {
        val zone = wifiSafeZone() ?: return ""
        return zone.optString("password", "")
    }

    fun firstWifiBssid(): String {
        val zone = wifiSafeZone() ?: return ""
        return zone.optString("bssid", "")
    }

    private fun readStringArray(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.optString(it) }
        }.getOrDefault(emptyList())
    }

    private fun sameZone(left: JSONObject, right: JSONObject): Boolean {
        val leftBssid = left.optString("bssid")
        val rightBssid = right.optString("bssid")
        if (leftBssid.isNotBlank() && rightBssid.isNotBlank() && leftBssid.equals(rightBssid, ignoreCase = true)) return true
        val leftSsid = left.optString("ssid")
        val rightSsid = right.optString("ssid")
        return leftSsid.isNotBlank() && rightSsid.isNotBlank() && leftSsid == rightSsid
    }
}

