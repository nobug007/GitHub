package com.sf.sfd

object SfdConfig {
    const val BLE_DEVICE_NAME = "Safe Finder 0.1"
    // Reported in every telemetry + register payload. Starts at 0.9.0, bumped on each significant
    // update (SFC/SFD/SFW/SFA share the same versioning line).
    const val FW_VERSION = "0.9.0"
    // At or below this battery level telemetry reports deviceStatus/eventType = LOW_BATTERY.
    const val LOW_BATTERY_PCT = 10
    const val REGISTER_URL = "https://sf-api.ese-lab.com/api/v1/devices/register"
    const val TELEMETRY_URL = "https://sf-api.ese-lab.com/api/v1/telemetry"
    const val DEFAULT_DEVICE_ID = "SF-000010"
    const val GYRO_SAMPLE_PERIOD_MS = 60_000L
    const val GPS_TELEMETRY_REPORT_PERIOD_MS = 60_000L
    const val TELEMETRY_REPORT_PERIOD_MS = 600_000L
    const val WIFI_SAFEZONE_GRACE_MS = 2 * 60 * 1000L
    // Right after the service starts (registration/restart), report the safe zone while Wi-Fi is
    // still associating so no false "이탈" telemetry goes out during setup. Devices start at home.
    const val STARTUP_SAFE_GRACE_MS = 90 * 1000L
    // Only a valid GPS fix beyond this radius from a home zone counts as a real departure; no fix
    // or within-radius holds the safe zone (prevents false "이탈" when Wi-Fi drops but still home).
    const val GEOFENCE_RADIUS_M = 150.0
    // false-exit suppression: hold the zone while the home AP is still visible in scans (that
    // check refreshes on its own indefinitely). The stationary fallback only bridges brief scan
    // blackouts while Wi-Fi is ON, so it is deliberately short — a long hold masks real exits.
    const val WIFI_STATIONARY_HOLD_MAX_MS = 5 * 60 * 1000L
    // Hold the safe zone this long after Wi-Fi fully disconnects, regardless of motion — bridges
    // Doze/roam dropouts so brief disconnections never trigger a false exit / SMS spam.
    const val WIFI_DISCONNECT_HOLD_MAX_MS = 8 * 60 * 1000L
    // How often to attempt learning the home geofence center while attached at home (until one is
    // known). Server /config strips centerLat, so the device self-learns it from a real GPS fix.
    const val CENTER_LEARN_INTERVAL_MS = 60 * 1000L
    // Extra distance beyond GEOFENCE_RADIUS_M before a GPS fix is accepted as PROOF of departure.
    // Positive proof overrides the Wi-Fi holds (which otherwise masked real exits), so the margin
    // keeps GPS jitter near the edge of the geofence from flipping the state.
    const val GPS_DEPARTURE_MARGIN_M = 100.0
    const val WIFI_SCAN_FRESH_MS = 5 * 60 * 1000L
    const val WIFI_NEARBY_MIN_RSSI = -85
    const val BLE_SAFEZONE_GRACE_MS = 2 * 60 * 1000L
    const val GPS_LOCATION_CACHE_MS = 5 * 60 * 1000L
    // A last-known GPS fix older than this is treated as "no current signal" rather than
    // shown/sent as the live position. Without it, the home coordinate cached at registration
    // keeps showing as "current location" even after the device has moved away.
    const val GPS_MAX_FIX_AGE_MS = 2 * 60 * 1000L
    const val GPS_STAY_DISTANCE_M = 10.0
    const val GYRO_REPORT_SAMPLE_COUNT = 10
    const val WARNING_DELAY_MS = 5 * 60 * 1000L
    const val EMERGENCY_DELAY_MS = 30 * 60 * 1000L
    const val SOS_REPEAT_PERIOD_MS = 60 * 1000L
    const val GYRO_MOVEMENT_THRESHOLD = 1.0

    // Autonomous connectivity management: SFD keeps itself connected to home Wi-Fi in a
    // safezone or to the guardian phone's Mobile Hotspot (this SSID) when away, and
    // advertises over BLE when it has no managed connection so SFC can enable its hotspot.
    const val HOTSPOT_SSID = "Nobug"
    const val CONNECTIVITY_TICK_MS = 15_000L

    // Away-from-safezone BLE presence loop (req 10-12): once SFD leaves every safe zone it
    // advertises for BLE_ADVERTISE_DURATION_MS so SFC can detect it (and raise its hotspot),
    // then stops advertising and tries to (re)join the hotspot/safezone WiFi. If it is still
    // not connected after CONNECT_GRACE_MS it resumes advertising -> the loop repeats.
    const val BLE_ADVERTISE_DURATION_MS = 3 * 60 * 1000L
    const val CONNECT_GRACE_MS = 45_000L

    const val SERVICE_UUID = "7d9f0001-4f5d-4a6e-8d6a-534644544553"
    const val DEVICE_INFO_UUID = "7d9f0002-4f5d-4a6e-8d6a-534644544553"
    const val CONFIG_WRITE_UUID = "7d9f0003-4f5d-4a6e-8d6a-534644544553"
    const val STATUS_UUID = "7d9f0004-4f5d-4a6e-8d6a-534644544553"
}
