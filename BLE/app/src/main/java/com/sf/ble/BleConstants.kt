package com.sf.ble

import android.os.ParcelUuid

/**
 * Shared identity between this advertiser (tablet) and the hotSpot scanner (phone).
 * The phone's hotSpot app filters BLE scan results on this exact service UUID and
 * enables Mobile Hotspot when it appears. Keep this value identical in both apps.
 */
object BleConstants {
    const val HOTSPOT_TRIGGER_UUID = "7d9f00b1-4f5d-4a6e-8d6a-534644544553"
    val HOTSPOT_TRIGGER_PARCEL_UUID: ParcelUuid = ParcelUuid.fromString(HOTSPOT_TRIGGER_UUID)
}
