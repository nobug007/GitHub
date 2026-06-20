package com.safefinder.testapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SafeZoneInfo(
    val safeZoneId: String,
    val type: String,
    val name: String,
    val familyId: String?
)

data class FamilyInfo(
    val familyId: String,
    val name: String,
    val phoneNo: String,
    val notificationEnabled: Boolean
)

class SafeFinderStore(context: Context) {
    private val prefs = context.getSharedPreferences("safe_finder_sync", Context.MODE_PRIVATE)

    fun applyServerResponse(responseBody: String) {
        val root = JSONObject(responseBody)
        val data = root.optJSONObject("data") ?: return
        val safeZones = data.optJSONArray("safeZones") ?: JSONArray()
        val families = data.optJSONArray("families") ?: JSONArray()
        saveMergedArray(KEY_SAFE_ZONES, safeZones, "safeZoneId")
        saveMergedArray(KEY_FAMILIES, families, "familyId")
        prefs.edit()
            .putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis())
            .putInt(KEY_INTERVAL, root.optInt("interval", SafeFinderConfig.AUTO_INTERVAL_MS.toInt()))
            .apply()
    }

    fun safeZones(): List<SafeZoneInfo> {
        return storedArray(KEY_SAFE_ZONES).toObjectList().mapNotNull { item ->
            val id = item.optString("safeZoneId")
            if (id.isBlank()) return@mapNotNull null
            SafeZoneInfo(
                safeZoneId = id,
                type = item.optString("type"),
                name = item.optString("name", id),
                familyId = item.optString("familyId").ifBlank { null }
            )
        }
    }

    fun families(): List<FamilyInfo> {
        return storedArray(KEY_FAMILIES).toObjectList().mapNotNull { item ->
            val id = item.optString("familyId")
            val phoneNo = item.optString("phoneNo")
            if (id.isBlank() || phoneNo.isBlank()) return@mapNotNull null
            FamilyInfo(
                familyId = id,
                name = item.optString("name", id),
                phoneNo = phoneNo,
                notificationEnabled = item.optBoolean("notificationEnabled", true)
            )
        }
    }

    fun registeredWifiZones(): List<SafeZoneInfo> {
        return safeZones().filter { it.type.equals("wifi", ignoreCase = true) }
    }

    fun matchingWifiZone(apName: String?): SafeZoneInfo? {
        if (apName.isNullOrBlank()) return null
        return registeredWifiZones().firstOrNull { it.name.equals(apName, ignoreCase = true) }
    }

    fun hasAnyRegistration(): Boolean {
        return safeZones().isNotEmpty() && families().isNotEmpty()
    }

    fun situationText(currentApName: String): String {
        val zones = safeZones()
        val families = families()
        if (zones.isEmpty() && families.isEmpty()) return "Safe Zone / 가족이 등록되지 않았습니다."
        if (zones.isEmpty()) return "Safe Zone이 등록되지 않았습니다. 가족 ${families.size}명 등록됨."
        if (families.isEmpty()) return "가족이 등록되지 않았습니다. Safe Zone ${zones.size}개 등록됨."

        val wifiZone = matchingWifiZone(currentApName)
        return if (wifiZone != null) {
            "현재 상황: SafeZone ${wifiZone.name} 안에 있습니다. 가족 ${families.size}명 등록됨."
        } else {
            val zoneNames = zones.joinToString(", ") { it.name }
            "현재 상황: SafeZone 밖입니다. 등록 SafeZone: $zoneNames / 가족 ${families.size}명."
        }
    }

    private fun saveMergedArray(key: String, updates: JSONArray, idField: String) {
        if (updates.length() == 0) {
            prefs.edit().putString(key, "[]").apply()
            return
        }

        val current = linkedMapOf<String, JSONObject>()
        for (item in storedArray(key).toObjectList()) {
            val id = item.optString(idField)
            if (id.isNotBlank()) current[id] = item
        }

        for (item in updates.toObjectList()) {
            val id = item.optString(idField)
            if (id.isBlank()) continue
            when (item.optString("operation").uppercase()) {
                "DELETE" -> current.remove(id)
                else -> current[id] = item
            }
        }

        val merged = JSONArray()
        current.values.forEach { merged.put(it) }
        prefs.edit().putString(key, merged.toString()).apply()
    }

    private fun storedArray(key: String): JSONArray {
        return JSONArray(prefs.getString(key, "[]"))
    }

    private fun JSONArray.toObjectList(): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        for (index in 0 until length()) {
            optJSONObject(index)?.let { result.add(it) }
        }
        return result
    }

    companion object {
        private const val KEY_SAFE_ZONES = "safe_zones"
        private const val KEY_FAMILIES = "families"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_INTERVAL = "interval"
    }
}
