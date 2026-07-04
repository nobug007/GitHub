package com.sf.sfdtest

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var store: TelemetryStore
    private lateinit var readers: DeviceReaders
    private lateinit var deviceIdInput: EditText
    private lateinit var stateView: TextView
    private lateinit var requestView: TextView
    private lateinit var responseView: TextView
    private lateinit var logView: TextView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = TelemetryStore(this)
        readers = DeviceReaders(this, store)
        buildUi()
        requestPermissionsIfNeeded()
        store.ensureInitialDefaults(readers.currentWifiName(), readers.ownPhoneNumber())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(TelemetryService.ACTION_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        refresh()
    }

    override fun onPause() {
        runCatching { unregisterReceiver(receiver) }
        super.onPause()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = ScrollView(this).apply { setBackgroundColor(0xFFF8FAFC.toInt()) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        content.addView(TextView(this).apply {
            text = "SFD_Test Telemetry"
            textSize = 26f
            setTextColor(0xFF0F172A.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "SafeZone / Family / GPS Exit SMS"
            textSize = 14f
            setTextColor(0xFF475569.toInt())
            setPadding(0, dp(6), 0, dp(12))
        })

        deviceIdInput = edit("Device ID", store.deviceId())
        content.addView(deviceIdInput)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        content.addView(row1)
        row1.addView(button("Save") { saveDeviceId() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row1.addView(button("Start") { startTelemetry() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        row1.addView(button("Stop") { stopTelemetry() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
        content.addView(row2)
        row2.addView(button("SafeZone Add") { addCurrentSafeZone() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row2.addView(button("Family Add") { showFamilyDialog() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        row2.addView(button("Reset") { resetAll() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })

        stateView = block(210)
        requestView = block(260)
        responseView = block(140)
        logView = block(240)
        content.addView(section("State")); content.addView(stateView)
        content.addView(section("Last Request")); content.addView(requestView)
        content.addView(section("Last Response")); content.addView(responseView)
        content.addView(section("Log")); content.addView(logView)
        setContentView(root)
    }

    private fun edit(hint: String, value: String): EditText = EditText(this).apply {
        setText(value)
        this.hint = hint
        textSize = 15f
        setSingleLine(true)
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setAllCaps(false)
        setOnClickListener { action() }
    }

    private fun section(label: String): TextView {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        return TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(0xFF334155.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(6))
        }
    }

    private fun block(heightDp: Int): TextView {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        return TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF0F172A.toInt())
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            movementMethod = ScrollingMovementMethod()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 200)
    }

    private fun saveDeviceId() {
        store.saveDeviceId(deviceIdInput.text.toString())
        refresh()
    }

    private fun addCurrentSafeZone() {
        val wifiName = readers.currentWifiName()
        if (wifiName.isBlank()) store.appendLog("SafeZone add failed: no attached Wi-Fi") else store.addSafeZone(wifiName)
        refresh()
    }

    private fun showFamilyDialog() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        val nameInput = edit("Name", "")
        val phoneInput = edit("Phone", readers.ownPhoneNumber()).apply { inputType = InputType.TYPE_CLASS_PHONE }
        layout.addView(nameInput)
        layout.addView(phoneInput)
        AlertDialog.Builder(this)
            .setTitle("Family Add")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                store.addFamily(nameInput.text.toString(), phoneInput.text.toString())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetAll() {
        stopTelemetry()
        store.reset(readers.currentWifiName(), readers.ownPhoneNumber())
        deviceIdInput.setText(store.deviceId())
        refresh()
    }

    private fun startTelemetry() {
        saveDeviceId()
        store.ensureInitialDefaults(readers.currentWifiName(), readers.ownPhoneNumber())
        val intent = Intent(this, TelemetryService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        store.appendLog("Start requested")
        refresh()
    }

    private fun stopTelemetry() {
        stopService(Intent(this, TelemetryService::class.java))
        store.appendLog("Stop requested")
        refresh()
    }

    private fun refresh() {
        val status = store.status()
        val currentWifi = readers.currentWifiName()
        val inSafeZone = readers.inSafeZone()
        stateView.text = buildString {
            appendLine("deviceId: ${status.deviceId}")
            appendLine("fwVersion: ${AppConfig.FW_VERSION}")
            appendLine("current Wi-Fi: ${currentWifi.ifBlank { "not attached" }}")
            appendLine("inSafeZone: $inSafeZone")
            appendLine("next seq: ${store.peekNextSeq()}")
            appendLine("SafeZones:")
            if (status.safeZones.isEmpty()) appendLine("  none") else status.safeZones.forEach { appendLine("  - ${it.name}") }
            appendLine("Family:")
            if (status.families.isEmpty()) appendLine("  none") else status.families.forEach { appendLine("  - ${it.name}: ${it.phone}") }
        }
        requestView.text = status.lastRequest ?: "No request yet"
        responseView.text = status.lastResponse ?: "No response yet"
        logView.text = if (status.logs.isEmpty()) "No logs yet" else status.logs.joinToString("\n")
    }
}

