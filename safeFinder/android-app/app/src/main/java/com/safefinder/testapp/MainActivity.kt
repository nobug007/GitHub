package com.safefinder.testapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : android.app.Activity() {
    private lateinit var collector: DataCollector
    private lateinit var apNameView: TextView
    private lateinit var statusView: TextView
    private lateinit var serverUrlInput: EditText
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collector = DataCollector(applicationContext)
        setContentView(buildUi())
        requestNeededPermissions()
        refreshApName()
    }

    override fun onResume() {
        super.onResume()
        refreshApName()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout {
        val prefs = getSharedPreferences("safe_finder", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 48, 32, 32)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        apNameView = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(0xFF111827.toInt())
        }
        root.addView(apNameView, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener { sendOnce("MANUAL") }
        }
        root.addView(sendButton, buttonParams())

        val autoButton = Button(this).apply {
            text = "Auto"
            setOnClickListener { startAuto() }
        }
        root.addView(autoButton, buttonParams())

        val stopButton = Button(this).apply {
            text = "Stop Auto"
            setOnClickListener { stopService(Intent(this@MainActivity, AutoSendService::class.java)); statusView.text = "Auto stopped" }
        }
        root.addView(stopButton, buttonParams())

        serverUrlInput = EditText(this).apply {
            hint = "Server URL"
            setSingleLine(true)
            setText(prefs.getString("server_url", SafeFinderConfig.DEFAULT_SERVER_URL))
        }
        root.addView(serverUrlInput, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        statusView = TextView(this).apply {
            text = "Ready"
            textSize = 16f
            setPadding(0, 24, 0, 0)
            setTextColor(0xFF374151.toInt())
        }
        root.addView(statusView, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        return root
    }

    private fun buttonParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 24
        }
    }

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) refreshApName()
    }

    private fun refreshApName() {
        apNameView.text = collector.currentApName()
    }

    private fun sendOnce(eventType: String) {
        saveServerUrl()
        statusView.text = "Sending..."
        executor.execute {
            try {
                val payload = collector.buildPayload(eventType)
                val response = SafeFinderClient().send(serverUrl(), payload)
                runOnUiThread {
                    refreshApName()
                    statusView.text = "Sent seq ${payload.seq}: $response"
                }
            } catch (error: Exception) {
                runOnUiThread { statusView.text = "Send failed: ${error.message}" }
            }
        }
    }

    private fun startAuto() {
        saveServerUrl()
        val intent = Intent(this, AutoSendService::class.java)
            .putExtra(AutoSendService.EXTRA_SERVER_URL, serverUrl())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusView.text = "Auto started. Sending every 10 minutes."
    }

    private fun serverUrl(): String = serverUrlInput.text.toString().ifBlank { SafeFinderConfig.DEFAULT_SERVER_URL }

    private fun saveServerUrl() {
        getSharedPreferences("safe_finder", MODE_PRIVATE)
            .edit()
            .putString("server_url", serverUrl())
            .apply()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 2001
    }
}
