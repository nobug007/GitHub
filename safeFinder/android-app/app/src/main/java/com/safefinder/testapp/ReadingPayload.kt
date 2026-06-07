package com.safefinder.testapp

data class ReadingPayload(
    val deviceId: String,
    val fwVersion: String,
    val sentAt: String,
    val seq: Long,
    val timestamp: String,
    val lat: Double?,
    val lng: Double?,
    val accuracy: Float?,
    val battery: Int,
    val signal: Int,
    val status: String,
    val lteStatus: String,
    val wifiStatus: String,
    val eventType: String
)
