package com.sf.ble

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tablet-side BLE advertiser test app. Two buttons:
 *   - "BLE 광고 시작": advertise the shared hotspot-trigger service UUID
 *   - "BLE 광고 중지": stop advertising
 * The phone's hotSpot app scans for this advertisement and turns Mobile Hotspot
 * on while it is present.
 */
class MainActivity : Activity() {
    private lateinit var advertiser: BleAdvertiser
    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        advertiser = BleAdvertiser(this)
        advertiser.onState = { advertising, message -> runOnUiThread { render(advertising, message) } }

        requestNeededPermissions()

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "BLE 광고 테스트"
            textSize = 24f
            setTextColor(Color.BLACK)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF444444.toInt())
            setPadding(0, dp(12), 0, dp(20))
        }
        root.addView(status)

        startBtn = button("BLE 광고 시작") { advertiser.start() }
        stopBtn = button("BLE 광고 중지") { advertiser.stop() }
        root.addView(startBtn)
        root.addView(stopBtn)

        setContentView(root)
        render(false, "대기 중 — 시작 버튼을 누르면 핫스팟 트리거 광고를 보냅니다.")
    }

    override fun onDestroy() {
        advertiser.stop()
        super.onDestroy()
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun render(advertising: Boolean, message: String) {
        if (!::status.isInitialized) return
        status.text = "상태: ${if (advertising) "광고 중 ✓" else "중지됨 ✗"}\n$message"
        startBtn.isEnabled = !advertising
        stopBtn.isEnabled = advertising
        startBtn.alpha = if (advertising) 0.5f else 1f
        stopBtn.alpha = if (advertising) 1f else 0.5f
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (64 * density).toInt()
            ).apply { topMargin = (16 * density).toInt() }
            setOnClickListener { onClick() }
        }
    }
}
