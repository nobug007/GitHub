package com.sf.sfw

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
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * SFW watch UI. Before provisioning it shows the BLE Enable control; once configured it shows the
 * same information SFD's operation screen shows (status, location, safe zone, battery, guardian,
 * recent activity), laid out compactly and scrollably for the round watch face.
 */
class MainActivity : Activity() {
    private lateinit var bleConfigServer: BleConfigServer
    private lateinit var store: SfwStore
    private var bleRunning = false
    private var bleStatus = "Idle"

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SfwStore(this)
        bleConfigServer = BleConfigServer(
            context = this,
            onStateChanged = { running -> runOnUiThread { bleRunning = running; render() } },
            onStatusChanged = { status -> runOnUiThread { bleStatus = status; render() } }
        )
        requestNeededPermissions()
        registerStateReceiver()
        startTelemetryServiceIfConfigured()
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
        bleConfigServer.stop()
        super.onDestroy()
    }

    private fun registerStateReceiver() {
        val filter = IntentFilter(SfwTelemetryService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
    }

    private fun startTelemetryServiceIfConfigured() {
        if (!store.isConfigured()) return
        runCatching { startForegroundService(Intent(this, SfwTelemetryService::class.java)) }
    }

    // ---- rendering -------------------------------------------------------------------------

    private fun render() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xFF000000.toInt())
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Extra top/bottom padding keeps content clear of the round watch bezel.
            setPadding(dp(14), dp(36), dp(14), dp(40))
        }
        if (store.isConfigured()) buildOperationalUi(root) else buildProvisioningUi(root)
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
    }

    private fun buildProvisioningUi(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = SfwConfig.DISPLAY_NAME
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "아직 등록되지 않았습니다"
            textSize = 12f
            setTextColor(0xFF9AC4FF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        })
        root.addView(Button(this).apply {
            text = if (bleRunning) "BLE 대기 중" else "BLE Enable"
            textSize = 13f
            setAllCaps(false)
            alpha = if (bleRunning) 1.0f else 0.85f
            setOnClickListener { toggleConfigSync() }
        }, LinearLayout.LayoutParams(dp(128), dp(40)))
        root.addView(TextView(this).apply {
            text = bleStatus
            textSize = 10f
            setTextColor(0xFFB7F7E8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        // Mode 2: activate an already-registered device by its Device ID.
        root.addView(TextView(this).apply {
            text = "또는 등록된 ID로 활성화"
            textSize = 11f
            setTextColor(0xFF9AC4FF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(6))
        })
        val idInput = EditText(this).apply {
            hint = "Device ID"
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF6B7B8C.toInt())
        }
        root.addView(idInput, LinearLayout.LayoutParams(dp(158), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Button(this).apply {
            text = "활성화"
            textSize = 13f
            setAllCaps(false)
            setOnClickListener { activateByDeviceId(idInput.text.toString().trim().uppercase()) }
        }, LinearLayout.LayoutParams(dp(158), dp(40)).apply { topMargin = dp(8) })
    }

    private fun buildOperationalUi(root: LinearLayout) {
        val config = runCatching { JSONObject(store.configJson() ?: "{}") }.getOrDefault(JSONObject())
        val wifi = runCatching { WifiStatusReader(this, store).read() }.getOrNull()
        val gps = runCatching { GpsStatusReader(this).read() }.getOrNull()
        val inSafeZone = wifi?.isAttached == true
        val statusKey = when {
            inSafeZone -> "NORMAL"
            store.escalationEmergencySent() -> "EMERGENCY"
            store.escalationWarningSent() -> "WARNING"
            else -> "NORMAL"
        }
        val statusColor = when (statusKey) {
            "EMERGENCY" -> 0xFFC0392B.toInt()
            "WARNING" -> 0xFFE8A013.toInt()
            else -> if (inSafeZone) 0xFF2C5F2D.toInt() else 0xFF556155.toInt()
        }
        val statusKo = when (statusKey) { "EMERGENCY" -> "긴급"; "WARNING" -> "주의"; else -> "정상" }
        val zoneKo = if (inSafeZone) "안전구역" else "이탈"
        val verbKo = when (store.lastVerb()) {
            "stayed" -> "체류 중"; "moved" -> "이동 중"; "entered" -> "도착"; "exited" -> "벗어남"; else -> "-"
        }
        val locText = when {
            wifi?.isAttached == true -> "WiFi ${wifi.apName.ifBlank { store.firstWifiSsid() }}"
            gps?.latitude != null && gps.longitude != null -> "GPS %.4f, %.4f".format(gps.latitude, gps.longitude)
            else -> "GPS 신호 없음"
        }
        val guardianName = config.optJSONObject("guardian")?.optString("name").orEmpty()
        val guardianPhone = store.guardianPhones().firstOrNull().orEmpty()
        val battery = currentBattery()

        // Status header (colored by state)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(statusColor)
            }
        }
        head.addView(TextView(this).apply {
            text = store.elderName().ifBlank { "어르신" }
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        })
        head.addView(TextView(this).apply {
            text = "$zoneKo · $statusKo"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        })
        head.addView(TextView(this).apply {
            text = verbKo
            textSize = 11f
            setTextColor(0xFFEFF7EF.toInt())
            gravity = Gravity.CENTER
        })
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Info rows
        root.addView(infoRow("위치", locText))
        root.addView(infoRow("안전구역", store.firstWifiZoneName()))
        root.addView(infoRow("기기 ID", store.deviceId()))
        root.addView(infoRow("보호자", listOf(guardianName, guardianPhone).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "-" }))
        root.addView(infoRow("배터리", if (battery in 0..100) "$battery%" else "-"))
        root.addView(infoRow("최근", store.logs().firstOrNull()?.take(60) ?: "-"))

        // Re-provision control
        root.addView(Button(this).apply {
            text = if (bleRunning) "BLE 대기 중" else "재등록 (BLE)"
            textSize = 12f
            setAllCaps(false)
            alpha = if (bleRunning) 1.0f else 0.85f
            setOnClickListener { toggleConfigSync() }
        }, LinearLayout.LayoutParams(dp(128), dp(38)).apply { topMargin = dp(12) })
        if (bleRunning) {
            root.addView(TextView(this).apply {
                text = bleStatus
                textSize = 9f
                setTextColor(0xFFB7F7E8.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
        }
        // Device reset (with confirmation)
        root.addView(Button(this).apply {
            text = "기기 초기화"
            textSize = 12f
            setAllCaps(false)
            setTextColor(0xFFFF8A80.toInt())
            setOnClickListener { confirmReset() }
        }, LinearLayout.LayoutParams(dp(128), dp(38)).apply { topMargin = dp(8) })
    }

    /** Mode 2 setup: pull an already-registered device's config from the server and go operational. */
    private fun activateByDeviceId(deviceId: String) {
        if (deviceId.isBlank()) {
            Toast.makeText(this, "Device ID를 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "$deviceId 서버 설정을 불러옵니다...", Toast.LENGTH_SHORT).show()
        Thread {
            runCatching {
                val api = SfwApiClient()
                val cfg = api.getConfig(deviceId)
                val elderId = cfg.optString("elderId")
                val safeZones = cfg.optJSONArray("safeZones") ?: JSONArray()
                if (safeZones.length() == 0) error("등록된 안전구역이 없습니다.")
                val elder = api.getElder(deviceId)
                val guardians = if (elderId.isNotBlank()) api.getGuardians(elderId) else JSONArray()
                val guardian = guardians.optJSONObject(0) ?: JSONObject()
                val configJson = JSONObject()
                    .put("deviceId", deviceId)
                    .put("elderName", elder.optString("name"))
                    .put("guardian", JSONObject().put("name", guardian.optString("name")).put("phone", guardian.optString("phone")))
                    .put("guardians", guardians)
                    .put("safeZones", safeZones)
                store.saveConfigJson(configJson.toString())
            }.onSuccess {
                runOnUiThread {
                    Toast.makeText(this, "활성화 완료.", Toast.LENGTH_SHORT).show()
                    startTelemetryServiceIfConfigured()
                    render()
                }
            }.onFailure { e ->
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("활성화 실패")
                        .setMessage("$deviceId 설정을 불러오지 못했습니다.\n${e.message ?: e.javaClass.simpleName}")
                        .setPositiveButton("확인", null)
                        .show()
                }
            }
        }.start()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("기기 초기화")
            .setMessage("등록된 디바이스 정보가 모두 삭제되고 처음 화면으로 돌아갑니다.\n정말 초기화하시겠습니까?")
            .setPositiveButton("초기화") { _, _ -> resetDevice() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun resetDevice() {
        runCatching { stopService(Intent(this, SfwTelemetryService::class.java)) }
        store.clearAll()
        Toast.makeText(this, "디바이스 정보를 초기화했습니다.", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun infoRow(label: String, value: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, 0)
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 10f
            setTextColor(0xFF8AA0B6.toInt())
            gravity = Gravity.CENTER
        })
        addView(TextView(this@MainActivity).apply {
            text = value
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
    }

    private fun currentBattery(): Int = runCatching {
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)

    // ---- provisioning + permissions --------------------------------------------------------

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), BLE_PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLE_PERMISSION_REQUEST) render()
    }

    private fun toggleConfigSync() {
        if (bleConfigServer.isRunning()) bleConfigServer.stop() else bleConfigServer.start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val BLE_PERMISSION_REQUEST = 200
    }
}
