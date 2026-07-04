package com.sf.sfw

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var bleConfigServer: BleConfigServer
    private lateinit var configButton: Button
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleConfigServer = BleConfigServer(
            context = this,
            onStateChanged = { running -> runOnUiThread { renderConfigButton(running) } },
            onStatusChanged = { status -> runOnUiThread { renderStatus(status) } }
        )
        buildUi()
        requestBlePermissions()
    }

    override fun onDestroy() {
        bleConfigServer.stop()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xFF000000.toInt())
        }

        val title = TextView(this).apply {
            text = SfwConfig.DISPLAY_NAME
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))

        configButton = Button(this).apply {
            text = "Config"
            textSize = 11f
            setAllCaps(false)
            minHeight = 0
            minWidth = 0
            minimumHeight = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { toggleConfigSync() }
        }
        root.addView(
            configButton,
            LinearLayout.LayoutParams(dp(86), dp(34)).apply { topMargin = dp(12) }
        )

        statusView = TextView(this).apply {
            text = "Idle"
            textSize = 10f
            setTextColor(0xFFB7F7E8.toInt())
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        root.addView(
            statusView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)).apply { topMargin = dp(10) }
        )

        setContentView(root)
    }

    private fun requestBlePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val missing = listOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), BLE_PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLE_PERMISSION_REQUEST) renderConfigButton(bleConfigServer.isRunning())
    }

    private fun toggleConfigSync() {
        if (bleConfigServer.isRunning()) {
            bleConfigServer.stop()
        } else {
            bleConfigServer.start()
        }
    }

    private fun renderConfigButton(running: Boolean) {
        if (::configButton.isInitialized) {
            configButton.text = if (running) "Config On" else "Config"
            configButton.alpha = if (running) 1.0f else 0.82f
        }
    }

    private fun renderStatus(status: String) {
        if (::statusView.isInitialized) statusView.text = status
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val BLE_PERMISSION_REQUEST = 200
    }
}
