package com.sf.sfc

import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ProvisionResult(
    val requestJson: String,
    val responseCode: Int,
    val responseBody: String,
    val deviceId: String?
)

data class ElderInfo(
    val elderId: String,
    val name: String,
    val deviceId: String,
    val rawJson: String
)

data class SafeZoneInfo(
    val zoneId: String,
    val zoneType: String,
    val name: String,
    val bssid: String,
    val ssid: String,
    val enabled: Boolean,
    val rawJson: String
)

data class SafeZoneDraft(
    val zoneType: String,
    val name: String,
    val bssid: String,
    val ssid: String,
    val enabled: Boolean
) {
    fun toJson(): String = JSONObject()
        .put("zoneType", zoneType)
        .put("name", name)
        .put("bssid", bssid)
        .put("ssid", ssid)
        .put("enabled", enabled)
        .toString()
}

data class SafeZoneCreateResult(
    val requestJson: String,
    val responseCode: Int,
    val responseBody: String,
    val zoneId: String?
)

data class SafeZoneMutationResult(
    val requestJson: String,
    val responseCode: Int,
    val responseBody: String
)

data class GuardianInfo(
    val id: Int,
    val elderId: String,
    val name: String,
    val phone: String,
    val createdAt: String,
    val rawJson: String
)

data class GuardianDraft(
    val name: String,
    val phone: String,
    val relation: String = ""
) {
    fun toJson(): String {
        val json = JSONObject()
            .put("name", name)
            .put("phone", phone)
        if (relation.isNotBlank()) json.put("relation", relation)
        return json.toString()
    }
}

data class GuardianMutationResult(
    val requestJson: String,
    val responseCode: Int,
    val responseBody: String
)

data class DeviceLogCount(
    val deviceId: String,
    val total: Int,
    val normal: Int,
    val warning: Int,
    val emergency: Int,
    val rawJson: String
)

data class DailyLogSummary(
    val date: String,
    val total: Int,
    val normal: Int,
    val warning: Int,
    val emergency: Int
)

data class DeviceLogCalendar(
    val deviceId: String,
    val month: String,
    val total: Int,
    val days: List<DailyLogSummary>,
    val normal: Int,
    val warning: Int,
    val emergency: Int,
    val rawJson: String
)

data class DeviceLogEntry(
    val id: Int,
    val seq: Int,
    val fwVersion: String?,
    val eventTimestamp: String,
    val eventType: String,
    val locationType: String,
    val inSafeZone: Boolean?,
    val safeZoneId: String?,
    val battery: Int?,
    val signal: Int?,
    val deviceStatus: String,
    val latitude: Double?,
    val longitude: Double?,
    val apName: String?,
    val verb: String,
    val rawJson: String
)

data class DeviceLogPage(
    val deviceId: String,
    val logs: List<DeviceLogEntry>,
    val rawJson: String
)

class SfcApiClient {
    fun provisionDevice(bleName: String, bleAddress: String): ProvisionResult {
        val request = JSONObject()
            .put("bleName", bleName)
            .put("bleAddress", bleAddress)
            .toString()
        val result = postJson("https://sf-api.ese-lab.com/api/v1/devices/provision", request)
        val deviceId = runCatching {
            JSONObject(result.second).optJSONObject("data")?.optString("deviceId")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return ProvisionResult(request, result.first, result.second, deviceId)
    }

    fun getElder(deviceId: String): ElderInfo {
        val body = getText("https://sf-api.ese-lab.com/api/v1/devices/$deviceId/elder")
        val json = JSONObject(body)
        return ElderInfo(
            elderId = json.optString("elderId"),
            name = json.optString("name"),
            deviceId = json.optString("deviceId"),
            rawJson = body
        )
    }

    fun getSafeZones(elderId: String): List<SafeZoneInfo> {
        val body = getText("https://sf-api.ese-lab.com/api/v1/elders/$elderId/safezones")
        val zones = when {
            body.trim().startsWith("[") -> JSONArray(body)
            else -> JSONArray().put(JSONObject(body))
        }
        return (0 until zones.length()).mapNotNull { index ->
            val json = zones.optJSONObject(index) ?: return@mapNotNull null
            SafeZoneInfo(
                zoneId = json.optString("zoneId"),
                zoneType = json.optString("zoneType"),
                name = json.optString("name"),
                bssid = json.optString("bssid"),
                ssid = json.optString("ssid"),
                enabled = json.optBoolean("enabled"),
                rawJson = json.toString()
            )
        }
    }

    fun createSafeZone(elderId: String, draft: SafeZoneDraft): SafeZoneCreateResult {
        val request = draft.toJson()
        val result = postJson("https://sf-api.ese-lab.com/api/v1/elders/$elderId/safezones", request)
        val zoneId = runCatching {
            val json = JSONObject(result.second)
            when {
                json.has("zoneId") -> json.optString("zoneId")
                json.has("data") -> json.optJSONObject("data")?.optString("zoneId").orEmpty()
                else -> ""
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return SafeZoneCreateResult(request, result.first, result.second, zoneId)
    }

    fun updateSafeZoneEnabled(zoneId: String, enabled: Boolean): SafeZoneMutationResult {
        val request = JSONObject()
            .put("enabled", enabled)
            .toString()
        val result = sendJson("PATCH", "https://sf-api.ese-lab.com/api/v1/safezones/$zoneId", request)
        return SafeZoneMutationResult(request, result.first, result.second)
    }

    fun deleteSafeZone(zoneId: String): SafeZoneMutationResult {
        val result = sendJson("DELETE", "https://sf-api.ese-lab.com/api/v1/safezones/$zoneId", "")
        return SafeZoneMutationResult("", result.first, result.second)
    }

    fun getGuardians(elderId: String): List<GuardianInfo> {
        val body = getText("https://sf-api.ese-lab.com/api/v1/elders/$elderId/guardians")
        val guardians = when {
            body.trim().startsWith("[") -> JSONArray(body)
            else -> JSONArray().put(JSONObject(body))
        }
        return (0 until guardians.length()).mapNotNull { index ->
            val json = guardians.optJSONObject(index) ?: return@mapNotNull null
            GuardianInfo(
                id = json.optInt("id"),
                elderId = json.optString("elderId"),
                name = json.optString("name"),
                phone = json.optString("phone"),
                createdAt = json.optString("createdAt"),
                rawJson = json.toString()
            )
        }
    }

    fun createGuardian(elderId: String, draft: GuardianDraft): GuardianMutationResult {
        val request = draft.toJson()
        val result = postJson("https://sf-api.ese-lab.com/api/v1/elders/$elderId/guardians", request)
        return GuardianMutationResult(request, result.first, result.second)
    }

    fun updateGuardian(guardianId: Int, draft: GuardianDraft): GuardianMutationResult {
        val request = draft.toJson()
        val result = sendJson("PUT", "https://sf-api.ese-lab.com/api/v1/guardians/$guardianId", request)
        return GuardianMutationResult(request, result.first, result.second)
    }

    fun deleteGuardian(guardianId: Int): GuardianMutationResult {
        val result = sendJson("DELETE", "https://sf-api.ese-lab.com/api/v1/guardians/$guardianId", "")
        return GuardianMutationResult("", result.first, result.second)
    }

    fun getDeviceLogCount(deviceId: String): DeviceLogCount {
        val body = getText("https://sf-api.ese-lab.com/api/v1/devices/$deviceId/logs/count")
        val json = JSONObject(body)
        val breakdown = json.optJSONObject("breakdown") ?: JSONObject()
        return DeviceLogCount(
            deviceId = json.optString("deviceId", deviceId),
            total = json.optInt("total", 0),
            normal = breakdown.optInt("PERIODIC", 0),
            warning = breakdown.optInt("GEOFENCE_EXIT_HINT", 0),
            emergency = breakdown.optInt("SOS", 0),
            rawJson = body
        )
    }

    fun getDeviceLogCalendar(deviceId: String, month: String): DeviceLogCalendar {
        val body = getText("https://sf-api.ese-lab.com/api/v1/devices/$deviceId/logs/calendar?month=$month")
        val json = JSONObject(body)
        val daily = json.optJSONArray("daily") ?: JSONArray()
        val days = (0 until daily.length()).mapNotNull { index ->
            val day = daily.optJSONObject(index) ?: return@mapNotNull null
            val breakdown = day.optJSONObject("breakdown") ?: JSONObject()
            DailyLogSummary(
                date = day.optString("date"),
                total = day.optInt("total", 0),
                normal = breakdown.optInt("PERIODIC", 0),
                warning = breakdown.optInt("GEOFENCE_EXIT_HINT", 0),
                emergency = breakdown.optInt("SOS", 0)
            )
        }
        return DeviceLogCalendar(
            deviceId = json.optString("deviceId", deviceId),
            month = json.optString("month", month),
            total = json.optInt("total", days.sumOf { it.total }),
            days = days,
            normal = days.sumOf { it.normal },
            warning = days.sumOf { it.warning },
            emergency = days.sumOf { it.emergency },
            rawJson = body
        )
    }

    fun getDeviceLogsByDate(deviceId: String, date: String, page: Int = 0, size: Int = 50): DeviceLogPage {
        val body = getText("https://sf-api.ese-lab.com/api/v1/devices/$deviceId/logs?date=$date&page=$page&size=$size")
        val root = JSONObject(body)
        val data = root.optJSONObject("data") ?: root
        val logs = data.optJSONArray("logs") ?: JSONArray()
        return DeviceLogPage(
            deviceId = data.optString("deviceId", deviceId),
            logs = (0 until logs.length()).mapNotNull { index ->
                val item = logs.optJSONObject(index) ?: return@mapNotNull null
                DeviceLogEntry(
                    id = item.optInt("id"),
                    seq = item.optInt("seq"),
                    fwVersion = item.optNullableString("fwVersion"),
                    eventTimestamp = item.optString("eventTimestamp"),
                    eventType = item.optString("eventType"),
                    locationType = item.optString("locationType"),
                    inSafeZone = if (item.isNull("inSafeZone")) null else item.optBoolean("inSafeZone"),
                    safeZoneId = item.optNullableString("safeZoneId"),
                    battery = item.optNullableInt("battery"),
                    signal = item.optNullableInt("signal"),
                    deviceStatus = item.optString("deviceStatus"),
                    latitude = item.optNullableDouble("lat"),
                    longitude = item.optNullableDouble("lng"),
                    apName = item.optNullableString("apName"),
                    verb = item.optString("verb"),
                    rawJson = item.toString()
                )
            },
            rawJson = body
        )
    }

    private fun postJson(urlText: String, body: String): Pair<Int, String> {
        return sendJson("POST", urlText, body)
    }

    private fun sendJson(method: String, urlText: String, body: String): Pair<Int, String> {
        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = body.isNotBlank()
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            if (body.isNotBlank()) OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            code to responseBody
        } finally {
            connection.disconnect()
        }
    }

    private fun getText(urlText: String): String {
        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) error("GET $urlText failed: $code $responseBody")
            responseBody
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (isNull(name) || !has(name)) null else optInt(name)

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (isNull(name) || !has(name)) null else optDouble(name).takeIf { !it.isNaN() }
}
