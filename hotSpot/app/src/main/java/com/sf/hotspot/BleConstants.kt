package com.sf.hotspot

import android.os.ParcelUuid

/**
 * Shared identity with the tablet's BLE advertiser app (com.sf.ble). This
 * scanner filters BLE results on this exact service UUID and enables Mobile
 * Hotspot while the advertisement is present. Keep identical in both apps.
 */
object BleConstants {
    const val HOTSPOT_TRIGGER_UUID = "7d9f00b1-4f5d-4a6e-8d6a-534644544553"
    val HOTSPOT_TRIGGER_PARCEL_UUID: ParcelUuid = ParcelUuid.fromString(HOTSPOT_TRIGGER_UUID)
}
