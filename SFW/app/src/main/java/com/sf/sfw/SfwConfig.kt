package com.sf.sfw

object SfwConfig {
    const val DISPLAY_NAME = "SFW"
    const val BLE_DEVICE_NAME = "SFW"
    const val PREFS_NAME = "sfw_config"
    const val PREF_CONFIG_JSON = "config_json"
    const val PREF_CONFIG_UPDATED_AT = "config_updated_at"

    // Reported in every telemetry + register payload. Starts at 0.9.0, bumped on each significant
    // update (SFC/SFD/SFW/SFA share the same versioning line).
    const val FW_VERSION = "0.9.0"
    const val DEFAULT_DEVICE_ID = "SF-000020"
    // At or below this battery level telemetry reports deviceStatus/eventType = LOW_BATTERY.
    const val LOW_BATTERY_PCT = 10
    const val REGISTER_URL = "https://sf-api.ese-lab.com/api/v1/devices/register"
    const val TELEMETRY_URL = "https://sf-api.ese-lab.com/api/v1/telemetry"

    // Operational-loop timing/thresholds, copied from SFD's SfdConfig so both device
    // types escalate identically (60s sampling, 5min WARNING, 30min SOS, 60s SOS repeat).
    const val GYRO_SAMPLE_PERIOD_MS = 60_000L
    const val GPS_TELEMETRY_REPORT_PERIOD_MS = 60_000L
    const val TELEMETRY_REPORT_PERIOD_MS = 600_000L
    const val WIFI_SAFEZONE_GRACE_MS = 2 * 60 * 1000L
    // Right after start (registration/restart), report the safe zone while Wi-Fi associates so no
    // false "이탈" telemetry goes out during setup. Devices start at home.
    const val STARTUP_SAFE_GRACE_MS = 90 * 1000L
    // false-exit suppression, same as SFD: hold the zone while the home AP is still visible in
    // scans (self-refreshing). The stationary fallback only bridges brief scan blackouts while
    // Wi-Fi is ON, so it is deliberately short — a long hold masks real exits.
    const val WIFI_STATIONARY_HOLD_MAX_MS = 5 * 60 * 1000L
    const val WIFI_SCAN_FRESH_MS = 5 * 60 * 1000L
    const val WIFI_NEARBY_MIN_RSSI = -85
    const val GPS_LOCATION_CACHE_MS = 5 * 60 * 1000L
    // A last-known GPS fix older than this is treated as "no current signal" rather than shown/sent
    // as the live position (prevents a home coordinate cached at registration sticking as current).
    const val GPS_MAX_FIX_AGE_MS = 2 * 60 * 1000L
    const val GPS_STAY_DISTANCE_M = 10.0
    // Wearables sleep Wi-Fi frequently. When Wi-Fi is unavailable but GPS still places the watch
    // within this radius of a registered home zone's center, keep it in the safe zone rather than
    // false-exiting to 이탈.
    const val GEOFENCE_RADIUS_M = 150.0
    const val GYRO_REPORT_SAMPLE_COUNT = 10
    const val WARNING_DELAY_MS = 5 * 60 * 1000L
    const val EMERGENCY_DELAY_MS = 30 * 60 * 1000L
    const val SOS_REPEAT_PERIOD_MS = 60 * 1000L
    const val GYRO_MOVEMENT_THRESHOLD = 1.0
    // Never send more than one entry/exit SMS within this window (anti-flap / anti-spam). Kept
    // short: the stationary hold already prevents idle flapping, so a real exit must still alert.
    const val MIN_TRANSITION_SMS_INTERVAL_MS = 2 * 60 * 1000L
    // Absolute minimum spacing between ANY two alert SMS (anti-spam floor across all event types).
    const val MIN_ANY_SMS_INTERVAL_MS = 3 * 60 * 1000L
    // Hold the safe zone this long after Wi-Fi fully disconnects, regardless of motion — bridges
    // Doze/roam dropouts on a worn watch so brief disconnections never trigger a false exit.
    const val WIFI_DISCONNECT_HOLD_MAX_MS = 8 * 60 * 1000L
    // How often to attempt learning the home geofence center while attached at home (until one is
    // known). Server /config strips centerLat, so the watch self-learns it from a real GPS fix.
    const val CENTER_LEARN_INTERVAL_MS = 60 * 1000L
    // Extra distance beyond GEOFENCE_RADIUS_M before a GPS fix counts as PROOF of departure.
    // Positive proof overrides the Wi-Fi holds (which otherwise mask real exits); the margin keeps
    // GPS jitter near the geofence edge from flipping the state.
    const val GPS_DEPARTURE_MARGIN_M = 100.0

    // SSID the guardian phone broadcasts as its Mobile Hotspot. When the watch is on this AP the
    // location is mobile (guardian proximity), so telemetry also carries GPS coordinates.
    const val HOTSPOT_SSID = "Nobug"

    const val SERVICE_UUID = "7d9f0001-4f5d-4a6e-8d6a-534644544553"
    const val DEVICE_INFO_UUID = "7d9f0002-4f5d-4a6e-8d6a-534644544553"
    const val CONFIG_WRITE_UUID = "7d9f0003-4f5d-4a6e-8d6a-534644544553"
    const val STATUS_UUID = "7d9f0004-4f5d-4a6e-8d6a-534644544553"
}
