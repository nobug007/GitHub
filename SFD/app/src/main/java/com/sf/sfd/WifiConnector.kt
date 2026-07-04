package com.sf.sfd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build

class WifiConnector(private val context: Context, private val store: SfdStore) {
    @SuppressLint("MissingPermission")
    fun requestConnection(): String {
        val ssid = store.firstWifiSsid()
        val password = store.firstWifiPassword()
        if (ssid.isBlank()) return "Wi-Fi connect skipped: no saved SSID"
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .apply { if (password.isNotBlank()) setWpa2Passphrase(password) }
                .build()
            val result = wifiManager.addNetworkSuggestions(listOf(suggestion))
            return if (result == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                "Wi-Fi suggestion added: $ssid"
            } else {
                "Wi-Fi suggestion result $result: $ssid"
            }
        }

        if (context.checkSelfPermission(Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            return "Wi-Fi connect skipped: CHANGE_WIFI_STATE permission missing"
        }
        @Suppress("DEPRECATION")
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (password.isBlank()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = "\"$password\""
            }
        }
        @Suppress("DEPRECATION")
        val networkId = wifiManager.addNetwork(config)
        if (networkId < 0) return "Wi-Fi network add failed: $ssid"
        @Suppress("DEPRECATION")
        wifiManager.enableNetwork(networkId, true)
        @Suppress("DEPRECATION")
        wifiManager.reconnect()
        return "Wi-Fi connection requested: $ssid"
    }
}
