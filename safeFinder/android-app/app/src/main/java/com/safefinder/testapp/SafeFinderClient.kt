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
            .put("lat", payload.lat)
            .put("lng", payload.lng)
            .put("accuracy", payload.accuracy)
            .put("battery", payload.battery)
            .put("signal", payload.signal)
            .put("status", payload.status)
            .put("eventType", payload.eventType)

        return JSONObject()
            .put("deviceId", payload.deviceId)
            .put("fwVersion", payload.fwVersion)
            .put("sentAt", payload.sentAt)
            .put("readings", JSONArray().put(reading))
    }
}
