package com.sf.sfdtest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TelemetryStore(context: Context) {
    private val prefs = context.getSharedPreferences("sfd_test_store", Context.MODE_PRIVATE)

    fun saveDeviceId(deviceId: String) {
        prefs.edit().putString("device_id", deviceId.ifBlank { AppConfig.DEFAULT_DEVICE_ID }).apply()
        appendLog("Device ID saved: ${deviceId.ifBlank { AppConfig.DEFAULT_DEVICE_ID }}")
    }

    fun reset(currentWifiName: String, currentPhoneNumber: String) {
        prefs.edit().clear().apply()
        prefs.edit().putInt("next_seq", AppConfig.DEFAULT_SEQ).apply()
        saveDeviceId(AppConfig.DEFAULT_DEVICE_ID)
        addSafeZone(currentWifiName.ifBlank { AppConfig.DEFAULT_SAFE_ZONE_AP })
        addFamily("Phone", currentPhoneNumber.ifBlank { AppConfig.DEFAULT_GUARDIAN_PHONE })
        appendLog("Reset complete. Current Wi-Fi and phone were registered.")
    }

    fun ensureInitialDefaults(currentWifiName: String, currentPhoneNumber: String) {
        if (safeZones().isEmpty()) addSafeZone(currentWifiName.ifBlank { AppConfig.DEFAULT_SAFE_ZONE_AP })
        if (families().isEmpty()) addFamily("Phone", currentPhoneNumber.ifBlank { AppConfig.DEFAULT_GUARDIAN_PHONE })
    }

    fun addSafeZone(name: String) {
        val clean = name.trim().ifBlank { return }
        val zones = safeZones().toMutableList()
        if (zones.none { it.name.equals(clean, ignoreCase = true) }) zones += SafeZone(clean)
        saveSafeZones(zones)
        appendLog("SafeZone added: $clean")
    }

    fun addFamily(name: String, phone: String) {
        val cleanPhone = phone.trim().ifBlank { return }
        val cleanName = name.trim().ifBlank { "Family" }
        val items = families().toMutableList()
        val existing = items.indexOfFirst { it.phone == cleanPhone }
        if (existing >= 0) items[existing] = FamilyContact(cleanName, cleanPhone) else items += FamilyContact(cleanName, cleanPhone)
        saveFamilies(items)
        appendLog("Family added: $cleanName / $cleanPhone")
    }

    fun deviceId(): String = prefs.getString("device_id", AppConfig.DEFAULT_DEVICE_ID) ?: AppConfig.DEFAULT_DEVICE_ID

    fun nextSeq(): Int {
        val current = prefs.getInt("next_seq", AppConfig.DEFAULT_SEQ)
        prefs.edit().putInt("next_seq", current + 1).apply()
        return current
    }

    fun peekNextSeq(): Int = prefs.getInt("next_seq", AppConfig.DEFAULT_SEQ)

    fun safeZoneApName(): String = safeZones().firstOrNull()?.name ?: AppConfig.DEFAULT_SAFE_ZONE_AP

    fun safeZoneNames(): List<String> = safeZones().map { it.name }

    fun guardianPhone(): String = families().firstOrNull()?.phone ?: AppConfig.DEFAULT_GUARDIAN_PHONE

    fun familyPhones(): List<String> = families().map { it.phone }.filter { it.isNotBlank() }

    fun saveRequest(payload: String) {
        prefs.edit().putString("last_request", payload).apply()
    }

    fun saveResponse(response: String) {
        prefs.edit().putString("last_response", response).apply()
    }

    fun status(): RuntimeStatus = RuntimeStatus(
        deviceId = deviceId(),
        safeZones = safeZones(),
        families = families(),
        lastRequest = prefs.getString("last_request", null),
        lastResponse = prefs.getString("last_response", null),
        logs = logs()
    )

    fun appendLog(message: String) {
        val items = logs().toMutableList()
        items.add(0, message)
        while (items.size > 40) items.removeAt(items.lastIndex)
        prefs.edit().putString("logs", JSONArray(items).toString()).apply()
    }

    fun logs(): List<String> = readStringArray("logs")

    private fun safeZones(): List<SafeZone> {
        val raw = prefs.getString("safe_zones", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                SafeZone(item.optString("name"))
            }.filter { it.name.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun families(): List<FamilyContact> {
        val raw = prefs.getString("families", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                FamilyContact(item.optString("name"), item.optString("phone"))
            }.filter { it.phone.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun saveSafeZones(zones: List<SafeZone>) {
        val array = JSONArray()
        zones.forEach { array.put(JSONObject().put("name", it.name)) }
        prefs.edit().putString("safe_zones", array.toString()).apply()
    }

    private fun saveFamilies(items: List<FamilyContact>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("name", it.name).put("phone", it.phone)) }
        prefs.edit().putString("families", array.toString()).apply()
    }

    private fun readStringArray(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.optString(it) }
        }.getOrDefault(emptyList())
    }
}
