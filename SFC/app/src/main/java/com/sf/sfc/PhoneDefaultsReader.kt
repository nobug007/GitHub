package com.sf.sfc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

class PhoneDefaultsReader(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun read(): PhoneDefaults {
        val wifiInfo = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.connectionInfo
        val bluetoothName = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                ""
            } else {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                manager.adapter?.name.orEmpty()
            }
        }.getOrDefault("")

        val ssid = wifiInfo?.cleanSsid().orEmpty()
        return PhoneDefaults(
            ownerName = "방효식",
            phoneNumber = "010-7260-8813",
            wifiName = ssid.ifBlank { "nobug_home" },
            wifiSsid = ssid.ifBlank { "******" },
            wifiBssid = wifiInfo?.bssid?.takeIf { it != "02:00:00:00:00:00" }.orEmpty().ifBlank { "*******" },
            bluetoothName = bluetoothName.ifBlank { Build.MODEL ?: "nobug_phone" }
        )
    }

    private fun WifiInfo.cleanSsid(): String {
        val raw = ssid ?: return ""
        return raw.trim('"').takeUnless { it == "<unknown ssid>" }.orEmpty()
    }
}
