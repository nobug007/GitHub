package com.sf.hotspot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Hotspot Toggle Test"
            textSize = 24f
            setTextColor(Color.BLACK)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF444444.toInt())
            setPadding(0, dp(12), 0, dp(20))
        }
        root.addView(status)

        root.addView(button("1) 접근성 서비스 켜기") { HotspotController.openAccessibilitySettings(this) })
        root.addView(button("2) BLE 감지 → 핫스팟 시작") {
            val i = Intent(this, BleTriggerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            refresh()
        })
        root.addView(button("중지") {
            startService(Intent(this, BleTriggerService::class.java).setAction(BleTriggerService.ACTION_STOP))
            refresh()
        })

        setContentView(root)
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val a11y = HotspotController.isAccessibilityEnabled(this)
        status.text = "접근성 서비스: ${if (a11y) "켜짐 ✓" else "꺼짐 ✗ (먼저 1번을 눌러 켜세요)"}\n" +
            "태블릿 BLE 광고를 감지하면 핫스팟을 켜고,\n" +
            "광고가 사라지면(${BleTriggerService.ABSENCE_GRACE_MS / 1000}초) 끕니다."
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
            setOnClickListener { onClick() }
        }
    }
}
