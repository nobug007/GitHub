package com.sf.sfd

data class ApiResult(
    val code: Int,
    val body: String,
    val ok: Boolean
)

data class SfdState(
    val isConfigured: Boolean,
    val deviceId: String,
    val configJson: String?,
    val lastRegisterResult: String?,
    val lastTelemetryResult: String?,
    val lastServerResponse: String?,
    val logs: List<String>,
    val messageHistory: List<String>,
    val gyroSamples: List<GyroSample>
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
    val hasWifiConnection: Boolean = false
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
    val bluetoothName: String = "",
    val bluetoothAddress: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val signal: Int = -127
)
