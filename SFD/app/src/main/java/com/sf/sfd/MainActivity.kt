package com.sf.sfd

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var store: SfdStore
    private lateinit var bleManager: BlePeripheralManager
    private lateinit var wifiStatusReader: WifiStatusReader
    private lateinit var apiClient: SfdApiClient
    private var bleAdvertisingEnabled = false
    private var bleConnected = false
    private var connectedPhoneName = ""
    private var isRendering = false
    private var setupConnectedView: TextView? = null
    private var setupDeviceBox: TextView? = null
    private val topOffsetDp = 38

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (store.isConfigured()) renderOperationScreen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = SfdStore(this)
        apiClient = SfdApiClient()
        bleManager = BlePeripheralManager(
            context = this,
            store = store,
            apiClient = apiClient,
            onStatus = { runOnUiThread { handleBleStatus(it) } },
            onProvisioned = { runOnUiThread { startOperationalMode() } },
            onConnectionChanged = { connected, name -> runOnUiThread { updateBleConnection(connected, name) } }
        )
        wifiStatusReader = WifiStatusReader(this, store)
        requestNeededPermissions()
        noteBatteryOptimizationState()
        applyMode()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(SfdTelemetryService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(stateReceiver, filter)
        applyMode()
    }

    override fun onPause() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onPause()
    }

    override fun onDestroy() {
        if (!store.isConfigured()) bleManager.stop()
        super.onDestroy()
    }

    private fun applyMode() {
        if (store.isConfigured()) startOperationalMode() else startSetupMode()
    }

    private fun startSetupMode() {
        stopService(Intent(this, SfdTelemetryService::class.java))
        renderSetupScreen()
        setBleAdvertisingEnabled(true)
        store.appendLog("Setup screen active. BLE provisioning enabled.")
    }

    private fun startOperationalMode() {
        setBleAdvertisingEnabled(false)
        startTelemetryService()
        renderOperationScreen()
        store.appendLog("Operation screen active. Device is configured.")
    }

    private fun renderSetupScreen() {
        if (isRendering) return
        isRendering = true
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28 + topOffsetDp), dp(28), dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFF4FAFF.toInt(), 0xFFFFFBF4.toInt()))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        titleRow.addView(TextView(this).apply {
            text = "SFD"
            textSize = 48f
            setTextColor(0xFF000000.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        titleRow.addView(TextView(this).apply {
            text = " v0.5"
            textSize = 23f
            setTextColor(0xFF000000.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(18), 0, 0)
        })
        root.addView(titleRow)

        root.addView(TextView(this).apply {
            text = "SAT"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFF546070.toInt())
            background = oval(0xFFE9EFF9.toInt(), 0)
        }, LinearLayout.LayoutParams(dp(86), dp(86)).apply { topMargin = dp(28); bottomMargin = dp(26) })

        setupDeviceBox = TextView(this).apply {
            text = "Device Name : ${bleManager.currentAdvertiseName()}"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF111111.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0xFFFFFFFF.toInt(), 0xFFD8D8D8.toInt(), dp(1), dp(7))
        }
        root.addView(setupDeviceBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setupConnectedView = TextView(this).apply {
            text = if (bleConnected) "Connected" else ""
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF2563EB.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(setupConnectedView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        isRendering = false
    }

    private fun renderOperationScreen() {
        if (isRendering) return
        isRendering = true
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val state = store.currentState()
        val wifi = wifiStatusReader.read()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(topOffsetDp), dp(20), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFFFF5E7.toInt(), 0xFFFFFBF5.toInt()))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, dp(18))
            setBackgroundColor(0xFFFFD844.toInt())
        }
        header.addView(statusChip(if (bleAdvertisingEnabled) "BLE ON" else "BLE OFF"))
        header.addView(statusChip(if (wifi.isAttached) "GPS OFF" else "GPS ON"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginStart = dp(10) })
        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(18))
            background = rounded(0xFFFFFFFF.toInt(), 0xFFE5E7EB.toInt(), dp(1), dp(14))
        }
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        card.addView(TextView(this).apply {
            text = "Operation Mode"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF111111.toInt())
        })
        card.addView(TextView(this).apply {
            text = if (wifi.isAttached) "WiFi SafeZone active" else if (store.isBleSafeZoneActive()) "BLE SafeZone active" else "GPS tracking active"
            textSize = 16f
            setTextColor(0xFF1E5EA8.toInt())
            setPadding(0, dp(16), 0, dp(10))
        })

        val phoneText = if (connectedPhoneName.isNotBlank()) connectedPhoneName else "SFC Phone"
        card.addView(TextView(this).apply {
            text = "Connected Phone:\n$phoneText"
            textSize = 16f
            setTextColor(0xFF111827.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0xFFF4F4F5.toInt(), 0xFFD9D9DE.toInt(), dp(1), dp(12))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(label("WiFi AP Info:", dp(16)))
        card.addView(valueBox("WiFi: ${store.firstWifiZoneName()}"))
        card.addView(label("Device Name:", dp(14)))
        card.addView(valueBox(state.deviceId))
        card.addView(Button(this).apply {
            text = "Refresh"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setAllCaps(false)
            background = rounded(0xFF4F8FE7.toInt(), 0, 0, dp(24))
            setOnClickListener { renderOperationScreen() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(22) })

        card.addView(Button(this).apply {
            text = "Config Sync"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setAllCaps(false)
            background = rounded(0xFF16A34A.toInt(), 0, 0, dp(24))
            setOnClickListener { syncConfigFromServer() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(12) })

        content.addView(Button(this).apply {
            text = "Device \uCD08\uAE30\uD654"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setAllCaps(false)
            background = rounded(0xFFE53935.toInt(), 0, 0, dp(24))
            setOnClickListener { resetDevice() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(18) })

        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        isRendering = false
    }

    private fun syncConfigFromServer() {
        val deviceId = store.currentState().deviceId
        store.appendLog("Config sync started for $deviceId")
        Thread {
            runCatching {
                val savedElderId = store.elderId()
                val elder = if (savedElderId.isBlank()) {
                    store.appendLog("Config sync: elder lookup by deviceId")
                    runCatching { apiClient.getElder(deviceId) }
                        .getOrElse { throw IllegalStateException("Elder 조회 실패: ${it.message ?: it.javaClass.simpleName}") }
                } else {
                    store.appendLog("Config sync: using saved elderId=$savedElderId")
                    org.json.JSONObject()
                        .put("deviceId", deviceId)
                        .put("elderId", savedElderId)
                        .put("name", store.elderName())
                }
                val elderId = elder.optString("elderId")
                if (elderId.isBlank()) throw IllegalStateException("Elder 조회 실패: Elder ID is empty")

                store.appendLog("Config sync: guardians lookup elderId=$elderId")
                val guardians = runCatching { apiClient.getGuardians(elderId) }
                    .getOrElse { throw IllegalStateException("보호자 조회 실패: ${it.message ?: it.javaClass.simpleName}") }

                store.appendLog("Config sync: safezones lookup elderId=$elderId")
                val safeZones = runCatching { apiClient.getSafeZones(elderId) }
                    .getOrElse { throw IllegalStateException("SafeZone 조회 실패: ${it.message ?: it.javaClass.simpleName}") }

                store.saveServerSync(elder, guardians, safeZones)
                "Config sync completed: guardians=${guardians.length()}, safeZones=${safeZones.length()}"
            }.onSuccess { message ->
                store.appendLog(message)
                runOnUiThread {
                    startTelemetryService()
                    renderOperationScreen()
                    showSyncDialog("Config Sync 완료", message)
                }
            }.onFailure { error ->
                val message = "Config sync failed: ${error.message ?: error.javaClass.simpleName}"
                store.appendLog(message)
                runOnUiThread {
                    renderOperationScreen()
                    showSyncDialog("Config Sync 실패", message)
                }
            }
        }.start()
    }

    private fun showSyncDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun label(textValue: String, topDp: Int): TextView {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        return TextView(this).apply {
            text = textValue
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF111111.toInt())
            setPadding(0, topDp, 0, dp(6))
        }
    }

    private fun valueBox(textValue: String): TextView {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        return TextView(this).apply {
            text = textValue
            textSize = 16f
            setTextColor(0xFF2D2D2D.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0xFFFFFFFF.toInt(), 0xFFD8D8D8.toInt(), dp(1), dp(18))
        }
    }

    private fun statusChip(textValue: String): TextView = TextView(this).apply {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        text = textValue
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(0xFF111111.toInt())
        setPadding(dp(10), 0, dp(10), 0)
        background = rounded(0xFFFFE984.toInt(), 0xFF9E8B2B.toInt(), dp(1), dp(4))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
    }

    private fun setBleAdvertisingEnabled(enabled: Boolean) {
        if (bleAdvertisingEnabled == enabled) return
        bleAdvertisingEnabled = enabled
        if (enabled) {
            bleManager.start()
            setupDeviceBox?.text = "Device Name : ${bleManager.currentAdvertiseName()}"
        } else {
            bleManager.stop()
        }
    }

    private fun updateBleConnection(connected: Boolean, name: String) {
        bleConnected = connected
        connectedPhoneName = if (connected) name else ""
        setupConnectedView?.text = if (connected) "Connected" else ""
        if (store.isConfigured()) renderOperationScreen()
    }

    private fun handleBleStatus(message: String) {
        store.appendLog(message)
        if (!store.isConfigured()) {
            if (message.contains("connected", ignoreCase = true)) setupConnectedView?.text = "Connected"
        }
    }

    private fun resetDevice() {
        stopService(Intent(this, SfdTelemetryService::class.java))
        bleManager.stop()
        bleAdvertisingEnabled = false
        bleConnected = false
        store.clearAll()
        startSetupMode()
    }

    private fun startTelemetryService() {
        val intent = Intent(this, SfdTelemetryService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100)
    }

    private fun noteBatteryOptimizationState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(PowerManager::class.java)
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) store.appendLog("Battery optimization is active")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) applyMode()
    }

    private fun rounded(color: Int, strokeColor: Int, strokeWidth: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

    private fun oval(color: Int, strokeColor: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokeColor != 0) setStroke(2, strokeColor)
    }
}

