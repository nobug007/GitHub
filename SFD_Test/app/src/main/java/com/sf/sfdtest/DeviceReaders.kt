package com.sf.sfdtest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager

class DeviceReaders(private val context: Context, private val store: TelemetryStore) {
    private var lastCellSignal = -100

    @Suppress("DEPRECATION")
    fun startCellSignalUpdates() {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        runCatching {
            telephony.listen(object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    lastCellSignal = if (signalStrength.isGsm) {
                        val asu = signalStrength.gsmSignalStrength
                        if (asu == 99) -100 else -113 + 2 * asu
                    } else {
                        signalStrength.cdmaDbm
                    }
                }
            }, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        }
    }

    fun batteryPercent(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else 0
    }

    @SuppressLint("MissingPermission")
    fun currentWifiName(): String {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return cleanWifiName(wifi.connectionInfo?.ssid.orEmpty())
    }

    @SuppressLint("MissingPermission")
    fun signal(): Int {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val caps = connectivity.getNetworkCapabilities(network)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            return wifi.connectionInfo?.rssi ?: -100
        }
        return lastCellSignal
    }

    fun inSafeZone(): Boolean {
        val current = currentWifiName()
        return store.safeZoneNames().any { namesMatch(current, it) }
    }

    @SuppressLint("MissingPermission")
    fun ownPhoneNumber(): String {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return ""
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return runCatching { telephony.line1Number.orEmpty() }.getOrDefault("")
    }

    @SuppressLint("MissingPermission")
    fun lastLocation(): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            ?: runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
    }

    private fun namesMatch(current: String, expected: String): Boolean {
        if (current.isBlank() || expected.isBlank()) return false
        return current.equals(expected, ignoreCase = true) || normalize(current).equals(normalize(expected), ignoreCase = true)
    }

    private fun normalize(value: String): String = cleanWifiName(value)
        .removeSuffix("_5G")
        .removeSuffix("_2G")
        .removeSuffix("-5G")
        .removeSuffix("-2G")
        .removeSuffix(" 5G")
        .removeSuffix(" 2G")

    private fun cleanWifiName(value: String): String = value.trim().trim('"').takeIf { it != "<unknown ssid>" }.orEmpty()
}
