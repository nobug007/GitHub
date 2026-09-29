package com.sf.hotspot

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Entry point the rest of the app uses to request a hotspot state change.
 * Persists the desired state and opens the tether settings page; the
 * AccessibilityService performs the actual switch toggle.
 */
object HotspotController {
    const val PREFS = "hotspot_ctl"
    const val KEY_DESIRED = "desired"
    const val KEY_PENDING = "pending"

    private const val TETHER_SETTINGS_ACTION = "android.settings.TETHER_SETTINGS"

    fun request(context: Context, enable: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DESIRED, enable)
            .putBoolean(KEY_PENDING, true)
            .apply()
        // CLEAR_TOP so a stale settings sub-page doesn't surface instead of the
        // tether page (which would put the wrong Switch at the top of the window).
        val intent = Intent(TETHER_SETTINGS_ACTION).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { app.startActivity(intent) }
            .onFailure { Log.w(TAG, "Unable to open tether settings: ${it.message}") }
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${HotspotAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.applicationContext.startActivity(intent) }
    }

    private const val TAG = "HotspotCtl"
}
