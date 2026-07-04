package com.sf.sfc

import android.bluetooth.BluetoothDevice

data class ScannedDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int
)

data class PhoneDefaults(
    val ownerName: String,
    val phoneNumber: String,
    val wifiName: String,
    val wifiSsid: String,
    val wifiBssid: String,
    val bluetoothName: String
)
