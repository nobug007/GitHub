package com.sf.sfd

object SfdConfig {
    const val BLE_DEVICE_NAME = "Safe Finder 0.1"
    const val FW_VERSION = "1.2.0"
    const val REGISTER_URL = "https://sf-api.ese-lab.com/api/v1/devices/register"
    const val TELEMETRY_URL = "https://sf-api.ese-lab.com/api/v1/telemetry"
    const val DEFAULT_DEVICE_ID = "SF-000010"
    const val GYRO_SAMPLE_PERIOD_MS = 60_000L
    const val GPS_TELEMETRY_REPORT_PERIOD_MS = 60_000L
    const val TELEMETRY_REPORT_PERIOD_MS = 600_000L
    const val WIFI_SAFEZONE_GRACE_MS = 2 * 60 * 1000L
    const val GPS_LOCATION_CACHE_MS = 5 * 60 * 1000L
    const val GPS_STAY_DISTANCE_M = 10.0
    const val GYRO_REPORT_SAMPLE_COUNT = 10
    const val WARNING_DELAY_MS = 5 * 60 * 1000L
    const val EMERGENCY_DELAY_MS = 30 * 60 * 1000L
    const val SOS_REPEAT_PERIOD_MS = 60 * 1000L
    const val GYRO_MOVEMENT_THRESHOLD = 1.0

    const val SERVICE_UUID = "7d9f0001-4f5d-4a6e-8d6a-534644544553"
    const val DEVICE_INFO_UUID = "7d9f0002-4f5d-4a6e-8d6a-534644544553"
    const val CONFIG_WRITE_UUID = "7d9f0003-4f5d-4a6e-8d6a-534644544553"
    const val STATUS_UUID = "7d9f0004-4f5d-4a6e-8d6a-534644544553"
}
