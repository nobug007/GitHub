package com.sf.sfdtest

import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.round

class PayloadFactory(private val store: TelemetryStore) {
    fun create(
        seq: Int,
        inSafeZone: Boolean,
        apName: String,
        battery: Int,
        signal: Int,
        location: Location?,
        gyroData: List<GyroPoint>,
        eventType: String
    ): String {
        val now = OffsetDateTime.now(ZoneOffset.ofHours(9)).toString()
        val reading = JSONObject()
            .put("seq", seq)
            .put("timestamp", now)
            .put("inSafeZone", inSafeZone)
            .put("battery", battery)
            .put("signal", signal)
            .put("deviceStatus", if (!inSafeZone && location == null) "GPS_WEAK" else "NORMAL")
            .put("eventType", eventType)
            .put("gyro", JSONObject().put("intervalMs", AppConfig.GYRO_INTERVAL_MS).put("data", gyroArray(gyroData)))

        if (inSafeZone) {
            reading
                .put("locationType", "WIFI")
                .put("apName", apName.ifBlank { store.safeZoneApName() })
        } else {
            reading
                .put("locationType", "GPS")
                .put("lat", rounded(location?.latitude ?: 0.0, 6))
                .put("lng", rounded(location?.longitude ?: 0.0, 6))
                .put("accuracy", (location?.accuracy ?: 999f).toInt())
        }

        return JSONObject()
            .put("deviceId", store.deviceId())
            .put("fwVersion", AppConfig.FW_VERSION)
            .put("sentAt", now)
            .put("readings", JSONArray().put(reading))
            .toString()
    }

    private fun gyroArray(points: List<GyroPoint>): JSONArray {
        val array = JSONArray()
        points.forEach {
            array.put(
                JSONObject()
                    .put("offsetMs", it.offsetMs)
                    .put("gyX", it.gyX)
                    .put("gyY", it.gyY)
                    .put("gyZ", it.gyZ)
            )
        }
        return array
    }

    private fun rounded(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return round(value * scale) / scale
    }
}

