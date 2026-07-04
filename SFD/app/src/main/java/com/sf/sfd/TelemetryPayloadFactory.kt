package com.sf.sfd

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class TelemetryPayloadFactory(private val store: SfdStore) {
    fun createPayload(
        samples: List<GyroSample>,
        location: LocationSnapshot,
        inSafeZone: Boolean,
        battery: Int,
        eventType: String = "PERIODIC",
        verb: String = "stayed",
        durationMs: Long = 0L,
        deviceStatus: String = "NORMAL"
    ): String {
        val now = OffsetDateTime.now(ZoneOffset.ofHours(9)).toString()
        val deviceId = store.currentState().deviceId
        val gyroData = JSONArray()
        val sorted = samples.sortedBy { it.timestampMs }.takeLast(SfdConfig.GYRO_REPORT_SAMPLE_COUNT)
        val newestTimestamp = sorted.lastOrNull()?.timestampMs ?: System.currentTimeMillis()

        sorted.forEach { sample ->
            gyroData.put(
                JSONObject()
                    .put("offsetMs", sample.timestampMs - newestTimestamp)
                    .put("gyX", sample.gyX)
                    .put("gyY", sample.gyY)
                    .put("gyZ", sample.gyZ)
            )
        }

        val reading = JSONObject()
            .put("seq", store.nextTelemetrySeq())
            .put("timestamp", Instant.ofEpochMilli(newestTimestamp).atOffset(ZoneOffset.ofHours(9)).toString())
            .put("locationType", location.locationType)
            .put("inSafeZone", inSafeZone)
            .put("battery", battery.coerceIn(0, 100))
            .put("signal", location.signal)
            .put("deviceStatus", deviceStatus)
            .put("eventType", eventType)
            .put("verb", verb)
            .put("gyro", JSONObject().put("intervalMs", SfdConfig.GYRO_SAMPLE_PERIOD_MS).put("data", gyroData))

        when (location.locationType) {
            "WIFI" -> {
                val apName = location.apName.ifBlank { store.firstWifiSsid().ifBlank { location.bssid.ifBlank { store.firstWifiBssid() } } }
                reading.put("apName", apName)
            }
            "BLE" -> {
                reading
                    .put("bluetoothName", location.bluetoothName.ifBlank { store.bleSafeZoneName() })
                    .put("bluetoothAddress", location.bluetoothAddress.ifBlank { store.bleSafeZoneAddress() })
            }
            "GPS" -> {
                // GPS coordinates are added below for every non-WiFi report.
            }
        }

        if (location.locationType != "WIFI") {
            location.latitude?.let { reading.put("lat", it) }
            location.longitude?.let { reading.put("lng", it) }
            location.accuracy?.let { reading.put("accuracy", it.toInt()) }
        }

        when (verb) {
            "stayed" -> reading.put(
                "result",
                JSONObject().put("duration", (durationMs / 1000L).coerceAtLeast(0L))
            )
            "moved" -> reading.put(
                "result",
                JSONObject()
                    .put("distance-m", 0)
                    .put("speed-kmh", 0)
            )
            "entered", "exited" -> reading.put("object", "zone")
        }

        return JSONObject()
            .put("deviceId", deviceId)
            .put("fwVersion", SfdConfig.FW_VERSION)
            .put("sentAt", now)
            .put("readings", JSONArray().put(reading))
            .toString()
    }
}
