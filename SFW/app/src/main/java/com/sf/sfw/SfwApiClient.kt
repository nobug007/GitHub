package com.sf.sfw

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ServerRegistrationResult(
    val requestJson: String,
    val responseCode: Int,
    val responseBody: String
)

class SfwApiClient {
    fun registerDevice(configJson: String): ServerRegistrationResult {
        val request = buildRegisterPayload(JSONObject(configJson)).toString()
        val result = postJson(SfwConfig.REGISTER_URL, request)
        return ServerRegistrationResult(request, result.first, result.second)
    }

    fun sendTelemetry(payloadJson: String): ApiResult {
        val result = postJson(SfwConfig.TELEMETRY_URL, payloadJson)
        return ApiResult(code = result.first, body = result.second, ok = result.first in 200..299)
    }

    fun getElder(deviceId: String): JSONObject =
        JSONObject(getText("https://sf-api.ese-lab.com/api/v1/devices/$deviceId/elder"))

    fun getSafeZones(elderId: String): JSONArray {
        val body = getText("https://sf-api.ese-lab.com/api/v1/elders/$elderId/safezones")
        return if (body.trim().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
    }

    fun getGuardians(elderId: String): JSONArray {
        val body = getText("https://sf-api.ese-lab.com/api/v1/elders/$elderId/guardians")
        return if (body.trim().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
    }

    /** GET /devices/{id}/config → the `data` object ({deviceId, elderId, safeZones[...]}). */
    fun getConfig(deviceId: String): JSONObject {
        val root = JSONObject(getText("https://sf-api.ese-lab.com/api/v1/devices/$deviceId/config"))
        return root.optJSONObject("data") ?: root
    }

    /** The highest telemetry seq the server already recorded for this device (0 if none/unknown). */
    fun getLatestSeq(deviceId: String): Int = runCatching {
        val root = JSONObject(getText("https://sf-api.ese-lab.com/api/v1/devices/$deviceId/logs?size=5"))
        val logs = root.optJSONObject("data")?.optJSONArray("logs") ?: return 0
        var max = 0
        for (i in 0 until logs.length()) max = maxOf(max, logs.optJSONObject(i)?.optInt("seq", 0) ?: 0)
        max
    }.getOrDefault(0)

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
            if (code !in 200..299) throw IllegalStateException("HTTP $code: $responseBody")
            responseBody
        } finally {
            connection.disconnect()
        }
    }

    private fun buildRegisterPayload(config: JSONObject): JSONObject {
        val safeZones = JSONArray()
        val sourceZones = config.optJSONArray("safeZones") ?: JSONArray()
        for (index in 0 until sourceZones.length()) {
            val zone = sourceZones.optJSONObject(index) ?: continue
            if (!zone.optString("zoneType").equals("WIFI", ignoreCase = true)) continue
            safeZones.put(
                JSONObject()
                    .put("zoneType", "WIFI")
                    .put("name", zone.optString("name"))
                    .put("bssid", zone.optString("bssid"))
                    .put("ssid", zone.optString("ssid"))
            )
        }

        return JSONObject()
            .put("deviceId", config.optString("deviceId"))
            // Spec B-1 requires elderId + fwVersion at registration; the remaining fields stay as
            // the provisioning extras this server already consumes.
            .put("elderId", config.optString("elderId"))
            .put("fwVersion", SfwConfig.FW_VERSION)
            .put("elderName", config.optString("elderName"))
            .put("guardian", config.optJSONObject("guardian") ?: JSONObject())
            .put("safeZones", safeZones)
    }

    private fun postJson(urlText: String, body: String): Pair<Int, String> {
        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            code to responseBody
        } finally {
            connection.disconnect()
        }
    }
}
