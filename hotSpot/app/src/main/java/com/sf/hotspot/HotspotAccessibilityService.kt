package com.sf.hotspot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Drives the system "Mobile Hotspot and Tethering" settings page to flip the
 * hotspot toggle, because a normal app cannot toggle internet-sharing tethering
 * through any public API on Android 8+.
 *
 * Trigger paths (either is enough):
 *  - onAccessibilityEvent for the settings package.
 *  - a same-process SharedPreferences listener on KEY_PENDING (so a repeat
 *    request while settings is already foreground is not missed).
 * Application uses a bounded retry loop because the tether page and its Switch
 * rows render asynchronously, and rootInActiveWindow is often null on Samsung
 * (so we also scan the interactive windows list for the settings window).
 */
class HotspotAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var applyTries = 0

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == HotspotController.KEY_PENDING) kickApply()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "onServiceConnected")
        getSharedPreferences(HotspotController.PREFS, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        kickApply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != SETTINGS_PACKAGE) return
        kickApply()
    }

    override fun onInterrupt() {
        handler.removeCallbacks(applyRunnable)
    }

    override fun onDestroy() {
        runCatching {
            getSharedPreferences(HotspotController.PREFS, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        super.onDestroy()
    }

    private fun kickApply() {
        val prefs = getSharedPreferences(HotspotController.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(HotspotController.KEY_PENDING, false)) return
        applyTries = 0
        handler.removeCallbacks(applyRunnable)
        handler.postDelayed(applyRunnable, 300L)
    }

    private val applyRunnable = Runnable { attemptApply() }

    private fun attemptApply() {
        val prefs = getSharedPreferences(HotspotController.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(HotspotController.KEY_PENDING, false)) return
        val desired = prefs.getBoolean(HotspotController.KEY_DESIRED, false)

        val toggle = findHotspotSwitchAnyWindow()
        if (toggle == null) {
            applyTries++
            if (applyTries < MAX_APPLY_TRIES) {
                handler.postDelayed(applyRunnable, RETRY_MS)
            } else {
                Log.w(TAG, "Hotspot switch not found after $applyTries tries; giving up")
                prefs.edit().putBoolean(HotspotController.KEY_PENDING, false).apply()
            }
            return
        }
        val checked = toggle.isChecked
        Log.d(TAG, "Hotspot switch found: checked=$checked desired=$desired")
        if (checked != desired) {
            val target = nearestClickable(toggle)
            if (target != null) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Toggled hotspot switch -> desired=$desired")
            } else {
                Log.w(TAG, "No clickable node for hotspot switch")
            }
        }
        prefs.edit().putBoolean(HotspotController.KEY_PENDING, false).apply()
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 400L)
    }

    /**
     * Collect the roots of every window the service can see (active window plus
     * the interactive windows list) WITHOUT filtering on packageName — on Samsung
     * the settings window root often reports a null packageName even though its
     * content (the Switch rows) is fully retrievable.
     */
    private fun windowRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        runCatching { windows.forEach { w -> w.root?.let { roots.add(it) } } }
        return roots
    }

    /**
     * The Mobile Hotspot switch is the top-most android.widget.Switch node visible
     * on screen (smallest bounds.top) across all windows. On the tether page that
     * is the Mobile Hotspot toggle; the nav/status bars carry no Switch.
     */
    private fun findHotspotSwitchAnyWindow(): AccessibilityNodeInfo? {
        val roots = windowRoots()
        val switches = ArrayList<AccessibilityNodeInfo>()
        for (r in roots) collectSwitches(r, switches)
        Log.d(TAG, "apply try=$applyTries windows=${roots.size} switches=${switches.size}")
        if (switches.isEmpty()) return null
        var best: AccessibilityNodeInfo? = null
        var bestTop = Int.MAX_VALUE
        val rect = Rect()
        for (node in switches) {
            node.getBoundsInScreen(rect)
            if (rect.top < bestTop) {
                bestTop = rect.top
                best = node
            }
        }
        return best
    }

    private fun collectSwitches(node: AccessibilityNodeInfo?, out: ArrayList<AccessibilityNodeInfo>) {
        if (node == null) return
        val cls = node.className
        if (cls != null && TextUtils.equals(cls, SWITCH_CLASS)) out.add(node)
        for (i in 0 until node.childCount) collectSwitches(node.getChild(i), out)
    }

    private fun nearestClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return node
    }

    companion object {
        private const val TAG = "HotspotA11y"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val SWITCH_CLASS = "android.widget.Switch"
        private const val MAX_APPLY_TRIES = 14
        private const val RETRY_MS = 500L
    }
}
