package com.safefinder.testapp

data class ReadingPayload(
    val deviceId: String,
    val fwVersion: String,
    val sentAt: String,
    val seq: Long,
    val timestamp: String,
    val locationType: String,
    val apName: String?,
    val lat: Double?,
    val lng: Double?,
    val accuracy: Float?,
    val inSafeZone: Boolean,
    val safeZoneId: String?,
    val battery: Int,
    val signal: Int,
    val deviceStatus: String,
    val lteStatus: String,
    val wifiStatus: String,
    val eventType: String
)
