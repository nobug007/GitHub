package com.safefinder.testapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager

class SmsAlertManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("safe_finder_sms", Context.MODE_PRIVATE)

    fun sendWifiExitWarningIfNeeded(apName: String): Int {
        val store = SafeFinderStore(context)
        val wifiZones = store.registeredWifiZones()
        val families = store.families().filter { it.notificationEnabled }
        if (wifiZones.isEmpty() || families.isEmpty()) return 0
        if (store.matchingWifiZone(apName) != null) return 0
        if (!hasSmsPermission()) return 0
        if (!canSendNow(apName)) return 0

        val zoneNames = wifiZones.joinToString(", ") { it.name }
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        var sent = 0
        for (family in families) {
            val message = "${family.name}님, SafeZone 인 , $zoneNames 에서 벗어나 있습니다."
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(family.phoneNo, null, parts, null, null)
            sent += 1
        }
        markSent(apName)
        return sent
    }

    private fun hasSmsPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun canSendNow(apName: String): Boolean {
        val key = "last_warning_$apName"
        val lastSentAt = prefs.getLong(key, 0L)
        return System.currentTimeMillis() - lastSentAt > WARNING_COOLDOWN_MS
    }

    private fun markSent(apName: String) {
        prefs.edit().putLong("last_warning_$apName", System.currentTimeMillis()).apply()
    }

    companion object {
        private const val WARNING_COOLDOWN_MS = 10 * 60 * 1000L
    }
}
