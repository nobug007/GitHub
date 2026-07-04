package com.sf.sfdtest

data class GyroPoint(
    val offsetMs: Int,
    val gyX: Double,
    val gyY: Double,
    val gyZ: Double
)

data class SafeZone(
    val name: String
)

data class FamilyContact(
    val name: String,
    val phone: String
)

data class RuntimeStatus(
    val deviceId: String,
    val safeZones: List<SafeZone>,
    val families: List<FamilyContact>,
    val lastRequest: String?,
    val lastResponse: String?,
    val logs: List<String>
)

data class ApiResult(
    val code: Int,
    val body: String,
    val ok: Boolean
)
