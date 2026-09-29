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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.text.InputType
import android.widget.Button
import android.widget.EditText
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
    private val handler = Handler(Looper.getMainLooper())
    // Keep the provisioning GATT/advertising up this long after a successful registration so SFC
    // can finish reading the STATUS ACK before we switch to operational mode (stop advertising).
    private val POST_REGISTER_BLE_HOLD_MS = 25_000L

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
            onProvisioned = { runOnUiThread { onRegisteredHoldBle() } },
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
        handler.removeCallbacks(enterOperationalRunnable)
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

    /**
     * Called the moment server registration succeeds. SFC is still polling this device's STATUS
     * characteristic (~15s) to read the registration ACK, so we must NOT tear the BLE GATT server
     * down yet — doing so drops SFC's connection and makes it report a registration-confirmation
     * timeout. Keep advertising + the GATT server alive for a grace window, then switch fully to
     * operational mode. renderOperationScreen() still runs now so the tablet shows it registered.
     */
    private fun onRegisteredHoldBle() {
        renderOperationScreen()
        store.appendLog("Registered. Holding BLE ${POST_REGISTER_BLE_HOLD_MS / 1000}s so SFC can read the ACK.")
        handler.removeCallbacks(enterOperationalRunnable)
        handler.postDelayed(enterOperationalRunnable, POST_REGISTER_BLE_HOLD_MS)
    }

    private val enterOperationalRunnable = Runnable {
        setBleAdvertisingEnabled(false)
        startTelemetryService()
        store.appendLog("Operation mode active. BLE provisioning stopped.")
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

        // --- Mode 2: activate an already-registered device by its Device ID -------------------
        root.addView(TextView(this).apply {
            text = "또는 등록된 Device ID로 활성화"
            textSize = 15f
            setTextColor(0xFF546070.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(8))
        })
        val idInput = EditText(this).apply {
            hint = "예: SF-XXXXXXXX"
            textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true)
            gravity = Gravity.CENTER
            background = rounded(0xFFFFFFFF.toInt(), 0xFFD8D8D8.toInt(), dp(1), dp(7))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(idInput, LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Button(this).apply {
            text = "활성화"
            textSize = 17f
            setAllCaps(false)
            setOnClickListener { activateByDeviceId(idInput.text.toString().trim().uppercase()) }
        }, LinearLayout.LayoutParams(dp(240), dp(48)).apply { topMargin = dp(10) })

        setContentView(root)
        isRendering = false
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
                val config = apiClient.getConfig(deviceId)
                val elderId = config.optString("elderId")
                val safeZones = config.optJSONArray("safeZones") ?: org.json.JSONArray()
                if (safeZones.length() == 0) error("등록된 안전구역이 없습니다.")
                val elder = apiClient.getElder(deviceId).apply { put("deviceId", deviceId) }
                val guardians = if (elderId.isNotBlank()) apiClient.getGuardians(elderId) else org.json.JSONArray()
                store.saveServerSync(elder, guardians, safeZones)
            }.onSuccess {
                runOnUiThread {
                    Toast.makeText(this, "활성화 완료. 운영 모드로 전환합니다.", Toast.LENGTH_SHORT).show()
                    startOperationalMode()
                }
            }.onFailure { e ->
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("활성화 실패")
                        .setMessage("$deviceId 설정을 불러오지 못했습니다.\n${e.message ?: e.javaClass.simpleName}\n\nID를 확인하거나 BLE 등록을 이용해 주세요.")
                        .setPositiveButton("확인", null)
                        .show()
                }
            }
        }.start()
    }

    /** Debug view: the most recent telemetry JSON we sent and the server's raw response, with times. */
    private fun showTelemetryDebug() {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val ex = store.lastTelemetryExchange() // [sentAtMs, payload, respAtMs, response]
        val fmt = { s: String -> (s.toLongOrNull() ?: 0L).let { if (it > 0) java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date(it)) else "-" } }
        val sentTime = fmt(ex.getOrElse(0) { "0" })
        val respTime = fmt(ex.getOrElse(2) { "0" })
        val rawPayload = ex.getOrElse(1) { "" }
        val pretty = if (rawPayload.isBlank()) "(전송 기록 없음)" else runCatching { org.json.JSONObject(rawPayload).toString(2) }.getOrDefault(rawPayload)
        val response = ex.getOrElse(3) { "" }.ifBlank { "(응답 없음)" }
        val text = "■ 보낸 시각: $sentTime\n\n■ 보낸 JSON\n$pretty\n\n────────────\n\n■ 받은 시각: $respTime\n\n■ 받은 응답\n$response"
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(0xFF111827.toInt())
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("최근 전송 확인")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("기기 초기화")
            .setMessage("등록된 디바이스 정보가 모두 삭제되고 설정(BLE 등록) 화면으로 돌아갑니다.\n정말 초기화하시겠습니까?")
            .setPositiveButton("초기화") { _, _ -> resetDevice() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun renderOperationScreen() {
        if (isRendering) return
        isRendering = true
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        // --- Gather current device state -------------------------------------------------
        val state = store.currentState()
        val wifi = wifiStatusReader.read()
        val gps = runCatching { GpsStatusReader(this).read() }.getOrNull()
        val inSafeZone = wifi.isAttached || store.isBleSafeZoneActive()
        val statusKey = when {
            inSafeZone -> "NORMAL"
            store.escalationEmergencySent() -> "EMERGENCY"
            store.escalationWarningSent() -> "WARNING"
            else -> "NORMAL"
        }
        val statusColor = when (statusKey) {
            "EMERGENCY" -> 0xFFC0392B.toInt()
            "WARNING" -> 0xFFE8A013.toInt()
            else -> if (inSafeZone) 0xFF2C5F2D.toInt() else 0xFF667066.toInt()
        }
        val statusKo = when (statusKey) { "EMERGENCY" -> "\uAE34\uAE09"; "WARNING" -> "\uC8FC\uC758"; else -> "\uC815\uC0C1" }
        val zoneKo = if (inSafeZone) "\uC548\uC804\uAD6C\uC5ED" else "\uC774\uD0C8"
        val locKo = when {
            wifi.isAttached -> "WiFi \uC548\uC804\uAD6C\uC5ED"
            store.isBleSafeZoneActive() -> "BLE \uC548\uC804\uAD6C\uC5ED (\uBCF4\uD638\uC790 \uADFC\uC811)"
            else -> "GPS \uCD94\uC801 \uC911"
        }
        val verbKo = when (store.lastVerb()) {
            "stayed" -> "\uCCB4\uB958 \uC911"; "moved" -> "\uC774\uB3D9 \uC911"; "entered" -> "\uB3C4\uCC29"; "exited" -> "\uBC97\uC5B4\uB0A8"; else -> "-"
        }
        val battery = runCatching {
            (getSystemService(BATTERY_SERVICE) as android.os.BatteryManager)
                .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
        val guardianName = runCatching { org.json.JSONObject(state.configJson ?: "{}").optJSONObject("guardian")?.optString("name").orEmpty() }.getOrDefault("")
        val guardianPhone = store.guardianPhone()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(topOffsetDp), dp(18), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFF3FAF3.toInt(), 0xFFFFFBF5.toInt()))
        }

        // --- Status header (colored by current status) ----------------------------------
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(statusColor, 0, 0, dp(18))
        }
        head.addView(TextView(this).apply {
            text = store.elderName().ifBlank { "\uC5B4\uB974\uC2E0" }
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        })
        head.addView(TextView(this).apply {
            text = "$zoneKo \u00B7 $statusKo"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        })
        head.addView(TextView(this).apply {
            text = "$locKo \u00B7 $verbKo"
            textSize = 14f
            setTextColor(0xFFEFF7EF.toInt())
            gravity = Gravity.CENTER
        })
        content.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // --- Location / connection card (context-aware) ----------------------------------
        val gpsText = if (gps?.latitude != null && gps.longitude != null)
            "%.5f, %.5f".format(gps.latitude, gps.longitude) + (gps.accuracy?.let { " (\u00B1${it.toInt()}m)" } ?: "")
        else "- (\uC2E0\uD638 \uC5C6\uC74C)"
        val locCard = infoCard("\uD604\uC7AC \uC704\uCE58 \u00B7 \uC5F0\uACB0")
        locCard.addView(infoRow("\uC0C1\uD0DC", locKo, statusColor))
        when {
            wifi.isAttached -> {
                // At home / on a registered WiFi safe zone \u2192 show the AP and the matched zone.
                locCard.addView(infoRow("\uD604\uC7AC WiFi", "${wifi.apName} \u00B7 ${wifi.signal}dBm", 0xFF111827.toInt()))
                locCard.addView(infoRow("\uB4F1\uB85D \uC548\uC804\uAD6C\uC5ED", "${store.firstWifiZoneName()} (${store.firstWifiSsid()})", 0xFF111827.toInt()))
            }
            store.isBleSafeZoneActive() -> {
                // \uBCF4\uD638\uC790 \uADFC\uC811 (guardian hotspot) \u2192 mobile, show GPS + the hotspot connection.
                locCard.addView(infoRow("\uD604\uC7AC GPS", gpsText, 0xFF111827.toInt()))
                locCard.addView(infoRow("\uC5F0\uACB0", "\uBCF4\uD638\uC790 \uD56B\uC2A4\uD31F", 0xFF2C5F2D.toInt()))
            }
            else -> {
                // Away / moving (GPS tracking) \u2192 show GPS; home coord only as a faint reference.
                locCard.addView(infoRow("\uD604\uC7AC GPS", gpsText, 0xFF111827.toInt()))
                val homeCoord = store.safeZoneCenterLat()?.let { lat -> store.safeZoneCenterLng()?.let { lng -> "%.5f, %.5f".format(lat, lng) } } ?: "-"
                locCard.addView(infoRow("\uC9D1 \uC88C\uD45C(\uCC38\uACE0)", homeCoord, 0xFF9CA3AF.toInt()))
            }
        }
        content.addView(locCard, cardParams(dp(12)))

        // --- Device / guardian card ------------------------------------------------------
        val devCard = infoCard("\uAE30\uAE30 \u00B7 \uBCF4\uD638\uC790")
        devCard.addView(infoRow("\uAE30\uAE30 ID", state.deviceId, 0xFF111827.toInt()))
        devCard.addView(infoRow("BLE \uAD11\uACE0", if (bleAdvertisingEnabled) "\uCF1C\uC9D0" else "\uAEBC\uC9D0", if (bleAdvertisingEnabled) 0xFF2C5F2D.toInt() else 0xFF6B7280.toInt()))
        // Connected phone only makes sense when actually linked (BLE or guardian hotspot); at home
        // on WiFi there is no phone link, so the row is omitted rather than showing a stale default.
        val phoneConn = when {
            bleConnected && connectedPhoneName.isNotBlank() -> connectedPhoneName
            store.isBleSafeZoneActive() -> "\uBCF4\uD638\uC790 \uD56B\uC2A4\uD31F"
            else -> ""
        }
        if (phoneConn.isNotBlank()) devCard.addView(infoRow("\uC5F0\uACB0\uB41C \uD3F0", phoneConn, 0xFF111827.toInt()))
        devCard.addView(infoRow("\uBCF4\uD638\uC790", listOf(guardianName, guardianPhone).filter { it.isNotBlank() }.joinToString(" \u00B7 ").ifBlank { "-" }, 0xFF111827.toInt()))
        devCard.addView(infoRow("\uBC30\uD130\uB9AC", if (battery in 0..100) "$battery%" else "-", 0xFF111827.toInt()))
        devCard.addView(infoRow("\uB9C8\uC9C0\uB9C9 \uC804\uC1A1", store.currentState().lastTelemetryResult?.take(48) ?: "-", 0xFF6B7280.toInt()))
        content.addView(devCard, cardParams(dp(12)))

        // --- Recent activity -------------------------------------------------------------
        val logCard = infoCard("\uCD5C\uADFC \uD65C\uB3D9")
        val recent = store.logs().take(4)
        if (recent.isEmpty()) {
            logCard.addView(TextView(this).apply { text = "\uAE30\uB85D \uC5C6\uC74C"; textSize = 13f; setTextColor(0xFF9CA3AF.toInt()) })
        } else {
            recent.forEach { line ->
                logCard.addView(TextView(this).apply {
                    text = "\u00B7 ${line.take(70)}"
                    textSize = 13f
                    setTextColor(0xFF4B5563.toInt())
                    setPadding(0, dp(3), 0, dp(3))
                })
            }
        }
        content.addView(logCard, cardParams(dp(12)))

        // --- Buttons ---------------------------------------------------------------------
        content.addView(opButton("\uC0C8\uB85C\uACE0\uCE68", 0xFF4F8FE7.toInt()) { renderOperationScreen() }, cardParams(dp(14)))
        content.addView(opButton("\uC804\uC1A1 \uD655\uC778 (\uBCF4\uB0B8/\uBC1B\uC740 \uB370\uC774\uD130)", 0xFF6B7280.toInt()) { showTelemetryDebug() }, cardParams(dp(10)))
        content.addView(opButton("\uC11C\uBC84 \uB3D9\uAE30\uD654", 0xFF16A34A.toInt()) { syncConfigFromServer() }, cardParams(dp(10)))
        content.addView(opButton("\uAE30\uAE30 \uCD08\uAE30\uD654", 0xFFE53935.toInt()) { confirmReset() }, cardParams(dp(16)))

        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        isRendering = false
    }

    private fun cardParams(topDp: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = topDp }

    private fun infoCard(title: String): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(0xFFFFFFFF.toInt(), 0xFFE5E7EB.toInt(), dp(1), dp(14))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF2C5F2D.toInt())
                setPadding(0, 0, 0, dp(8))
            })
        }
    }

    private fun infoRow(labelText: String, valueText: String, valueColor: Int): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
            addView(TextView(this@MainActivity).apply {
                text = labelText
                textSize = 14f
                setTextColor(0xFF6B7280.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(this@MainActivity).apply {
                text = valueText
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(valueColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun opButton(text: String, color: Int, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        return Button(this).apply {
            this.text = text
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            setAllCaps(false)
            background = rounded(color, 0, 0, dp(24))
            minHeight = dp(54)
            setOnClickListener { onClick() }
        }
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
        // Reset = wipe all device info and return to BLE advertising (setup mode).
        // Tear down the operational service and both BLE peripherals FIRST: the service owns
        // its own BlePeripheralManager (away-mode advertising) and this activity owns another,
        // so starting setup advertising before the service releases its advertiser/GATT server
        // makes the second advertiser fail. Clear, then re-advertise after a short settle.
        stopService(Intent(this, SfdTelemetryService::class.java))
        runCatching { bleManager.stop() }
        bleAdvertisingEnabled = false
        bleConnected = false
        store.clearAll()
        Toast.makeText(this, "디바이스 정보를 초기화했습니다. BLE 광고를 시작합니다.", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ startSetupMode() }, 800)
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

