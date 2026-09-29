package com.sf.sfw

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Ported from SFD's TelemetryPayloadFactory. The JSON schema (deviceId,
 * fwVersion, sentAt, readings[] with seq/timestamp/locationType/inSafeZone/
 * battery/signal/deviceStatus/eventType/verb/gyro/apName/lat/lng/accuracy/
 * result/object) is kept field-identical so the server and dashboard treat
 * SFW devices exactly like SFD devices. SFW never emits locationType "BLE",
 * which is a per-reading value, not a schema difference.
 */
class TelemetryPayloadFactory(private val store: SfwStore) {
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
        val deviceId = store.deviceId()
        val gyroData = JSONArray()
        val sorted = samples.sortedBy { it.timestampMs }.takeLast(SfwConfig.GYRO_REPORT_SAMPLE_COUNT)
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
            .put("gyro", JSONObject().put("intervalMs", SfwConfig.GYRO_SAMPLE_PERIOD_MS).put("data", gyroData))

        if (location.locationType == "WIFI") {
            val apName = location.apName.ifBlank { store.firstWifiSsid().ifBlank { location.bssid.ifBlank { store.firstWifiBssid() } } }
            reading.put("apName", apName)
            // Also report the BSSID so the server can match the WiFi safezone by BSSID, not just SSID.
            val bssid = location.bssid.ifBlank { store.firstWifiBssid() }
            if (bssid.isNotBlank()) reading.put("bssid", bssid)
        }
        // GPS coordinates: included whenever present — GPS tracking, or a hotspot WiFi connection
        // (mobile). A fixed home AP carries no GPS, so nothing is added there.
        location.latitude?.let { reading.put("lat", it) }
        location.longitude?.let { reading.put("lng", it) }
        location.accuracy?.let { reading.put("accuracy", it.toInt()) }

        // Attach the safe zone's server id so the backend matches by id instead of re-deriving
        // (and mis-flagging) the zone. Only meaningful while inside a WiFi safe zone.
        if (inSafeZone && location.locationType == "WIFI") {
            val safeZoneId = store.safeZoneIdForSsid(location.apName.ifBlank { store.firstWifiSsid() })
            if (safeZoneId.isNotBlank()) reading.put("safeZoneId", safeZoneId)
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
            .put("fwVersion", SfwConfig.FW_VERSION)
            .put("sentAt", now)
            .put("readings", JSONArray().put(reading))
            .toString()
    }
}
