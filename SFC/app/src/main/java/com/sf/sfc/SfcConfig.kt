package com.sf.sfc

object SfcConfig {
    // App version shown/reported by SFC. Starts at 0.9.0 and is bumped on each significant update
    // (SFC/SFD/SFW/SFA share the same versioning line).
    const val APP_VERSION = "0.9.0"
    const val DEFAULT_DEVICE_ID = "SF-000010"

    const val SERVICE_UUID = "7d9f0001-4f5d-4a6e-8d6a-534644544553"
    const val DEVICE_INFO_UUID = "7d9f0002-4f5d-4a6e-8d6a-534644544553"
    const val CONFIG_WRITE_UUID = "7d9f0003-4f5d-4a6e-8d6a-534644544553"
    const val STATUS_UUID = "7d9f0004-4f5d-4a6e-8d6a-534644544553"

    // SSID that the guardian phone broadcasts when its Mobile Hotspot is enabled.
    // Must never be treated as a home/safezone Wi-Fi.
    const val HOTSPOT_SSID = "Nobug"
}




