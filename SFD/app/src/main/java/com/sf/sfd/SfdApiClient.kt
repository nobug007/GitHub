package com.sf.sfd

import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class SfdApiClient {
    fun registerDevice(configJson: String): ApiResult = postJson(SfdConfig.REGISTER_URL, configJson)

    fun sendTelemetry(payloadJson: String): ApiResult = postJson(SfdConfig.TELEMETRY_URL, payloadJson)

    fun getElder(deviceId: String): JSONObject =
        JSONObject(getText("https://sf-api.ese-lab.com/api/v1/devices/$deviceId/elder"))

    fun getGuardians(elderId: String): JSONArray {
        val body = getText("https://sf-api.ese-lab.com/api/v1/elders/$elderId/guardians")
        return if (body.trim().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
    }

    fun getSafeZones(elderId: String): JSONArray {
        val body = getText("https://sf-api.ese-lab.com/api/v1/elders/$elderId/safezones")
        return if (body.trim().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
    }

    private fun postJson(urlText: String, body: String): ApiResult {
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
            ApiResult(code = code, body = responseBody, ok = code in 200..299)
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
            if (code !in 200..299) throw IllegalStateException("HTTP $code: $responseBody")
            responseBody
        } finally {
            connection.disconnect()
        }
    }
}
