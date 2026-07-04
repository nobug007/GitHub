package com.sf.sfdtest

object AppConfig {
    const val FW_VERSION = "1.2.0"
    const val TELEMETRY_URL = "https://sf-api.ese-lab.com/api/v1/telemetry"
    const val DEFAULT_DEVICE_ID = "SF-000007"
    const val DEFAULT_SAFE_ZONE_AP = "HomeWiFi"
    const val DEFAULT_GUARDIAN_PHONE = "010-7260-8813"
    const val DEFAULT_SEQ = 10500
    const val TELEMETRY_PERIOD_MS = 60_000L
    const val GYRO_INTERVAL_MS = 1000L
    const val WARNING_DELAY_MS = 5 * 60 * 1000L
    const val EMERGENCY_DELAY_MS = 30 * 60 * 1000L
}

