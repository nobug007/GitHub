package com.sf.sfw

// Field-compatible with SFD's Models.kt so telemetry payloads built from these
// are indistinguishable from SFD payloads on the server/dashboard side.

data class ApiResult(
    val code: Int,
    val body: String,
    val ok: Boolean
)

data class GyroSample(
    val timestampMs: Long,
    val gyX: Double,
    val gyY: Double,
    val gyZ: Double
)

data class WifiStatus(
    val apName: String,
    val bssid: String,
    val signal: Int,
    val isAttached: Boolean,
    val hasWifiConnection: Boolean = false,
    val wifiEnabled: Boolean = false
)

data class GpsStatus(
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Float?,
    val timestampMs: Long?
)

data class LocationSnapshot(
    val locationType: String,
    val apName: String = "",
    val bssid: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val signal: Int = -127
)
