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
        val result = postJson("https://sf-api.ese-lab.com/api/v1/devices/register", request)
        return ServerRegistrationResult(request, result.first, result.second)
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
