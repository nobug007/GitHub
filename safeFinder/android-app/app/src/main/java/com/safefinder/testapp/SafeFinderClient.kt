package com.safefinder.testapp

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class SafeFinderClient {
    fun send(serverUrl: String, payload: ReadingPayload): String {
        val connection = URL(serverUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { stream ->
            stream.write(toJson(payload).toString().toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.use { BufferedReader(InputStreamReader(it)).readText() }.orEmpty()
        if (responseCode !in 200..299) {
            throw IllegalStateException("HTTP $responseCode $body")
        }
        return body
    }

    private fun toJson(payload: ReadingPayload): JSONObject {
        val reading = JSONObject()
            .put("seq", payload.seq)
            .put("timestamp", payload.timestamp)
            .put("locationType", payload.locationType)

        if (payload.locationType == "WIFI") {
            reading.put("apName", payload.apName)
            reading.put("safeZoneId", payload.safeZoneId)
        } else {
            reading.put("lat", payload.lat)
            reading.put("lng", payload.lng)
            reading.put("accuracy", payload.accuracy)
        }
        reading
            .put("inSafeZone", payload.inSafeZone)
            .put("battery", payload.battery)
            .put("signal", payload.signal)
            .put("deviceStatus", payload.deviceStatus)
            .put("eventType", payload.eventType)
            .put("lteStatus", payload.lteStatus)
            .put("wifiStatus", payload.wifiStatus)

        return JSONObject()
            .put("deviceId", payload.deviceId)
            .put("fwVersion", payload.fwVersion)
            .put("sentAt", payload.sentAt)
            .put("readings", JSONArray().put(reading))
    }
}
