package com.safefinder.testapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.telephony.TelephonyManager
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class DataCollector(private val context: Context) {
    private val prefs = context.getSharedPreferences("safe_finder", Context.MODE_PRIVATE)

    fun currentApName(): String {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        val ssid = wifiManager?.connectionInfo?.ssid?.trim('"')
        return when {
            ssid.isNullOrBlank() -> "WiFi not connected"
            ssid.equals("<unknown ssid>", ignoreCase = true) -> "WiFi connected: unknown SSID"
            else -> ssid
        }
    }

    fun currentSignal(): Int {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        return wifiManager?.connectionInfo?.rssi ?: 0
    }

    fun currentBattery(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }

    fun currentLteStatus(): String {
        return try {
            val telephonyManager = context.getSystemService(TelephonyManager::class.java)
            val operator = telephonyManager?.networkOperator.orEmpty()
            if (operator.length >= 5) {
                val mcc = operator.take(3)
                val mnc = operator.drop(3)
                "MCC=$mcc,MNC=$mnc"
            } else {
                "Fail"
            }
        } catch (error: SecurityException) {
            "Fail"
        }
    }

    @SuppressLint("MissingPermission")
    fun buildPayload(eventType: String): ReadingPayload {
        val location = latestLocation()
        val now = nowText()
        val seq = prefs.getLong("seq", 10481L) + 1L
        prefs.edit().putLong("seq", seq).apply()
        return ReadingPayload(
            deviceId = SafeFinderConfig.DEVICE_ID,
            fwVersion = SafeFinderConfig.FW_VERSION,
            sentAt = now,
            seq = seq,
            timestamp = now,
            lat = location?.latitude,
            lng = location?.longitude,
            accuracy = location?.accuracy,
            battery = currentBattery(),
            signal = currentSignal(),
            status = "NORMAL",
            lteStatus = currentLteStatus(),
            wifiStatus = currentApName(),
            eventType = eventType
        )
    }

    @SuppressLint("MissingPermission")
    private fun latestLocation(): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers.mapNotNull { provider ->
            if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
        }.maxByOrNull { it.time }
    }

    private fun nowText(): String {
        return OffsetDateTime.now(ZoneOffset.ofHours(9)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
