package com.sf.sfc

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private lateinit var bleManager: BleProvisioningManager
    private lateinit var defaultsReader: PhoneDefaultsReader
    private val apiClient = SfcApiClient()
    private val scannedDevices = linkedMapOf<String, ScannedDevice>()
    private val logLines = ArrayDeque<String>()

    private var selectedDevice: BluetoothDevice? = null
    private var selectedDeviceName: String = ""
    private var scanButton: Button? = null
    private var registerButton: Button? = null
    private var dialogDeviceList: LinearLayout? = null
    /** 주소 → 그 기기 줄의 "신호" 라벨 뷰. 스캔 결과가 올 때 이 뷰만 갱신한다. */
    private val dialogDeviceButtons = linkedMapOf<String, TextView>()
    private var scanDialog: AlertDialog? = null
    private var selectedSafeZoneIndex: Int = -1
    private var selectedGuardianIndex: Int = -1
    private var selectedLogMonth: YearMonth = YearMonth.now()
    private var latestHomeLog: DeviceLogEntry? = null
    private var latestHomeLogs: List<DeviceLogEntry> = emptyList()
    // Wall-clock time of the last successful server sync (last message fetched), shown on the home screen.
    private var lastSyncedAtMillis: Long = 0L
    private var homeVisible: Boolean = false
    private var foregroundVisible: Boolean = false

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val homeRefreshRunnable = object : Runnable {
        override fun run() {
            if (foregroundVisible && isRegistered() && homeVisible) {
                loadHomeLog()
                refreshHandler.postDelayed(this, HOME_REFRESH_INTERVAL_MS)
            }
        }
    }

    // Registration-waiting state so the "잠시 기다려 주세요" spinner cannot hang forever if the
    // SFD/backend never returns an ACK (e.g. SFD crashed mid-provisioning).
    private var registrationWaiting = false
    private var pendingConfigDevice: BluetoothDevice? = null
    private var pendingConfigJson: String? = null
    private val registrationTimeoutRunnable = Runnable { onRegistrationTimeout() }

    private val appPrefs by lazy { getSharedPreferences("sfc_app", MODE_PRIVATE) }
    private val monitorPrefs by lazy { getSharedPreferences("sfc_monitor", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        defaultsReader = PhoneDefaultsReader(this)
        bleManager = BleProvisioningManager(
            context = this,
            onDeviceFound = { runOnUiThread { addOrUpdateDevice(it) } },
            onStatus = { appendStatus(it) },
            onRegistrationConfirmed = { runOnUiThread { completeRegistration() } }
        )
        requestNeededPermissions()
        if (isRegistered()) {
            ensureMonitorRunning()
            showHome()
        } else {
            showPairing()
        }
    }

    private fun ensureMonitorRunning() {
        if (!isRegistered()) return
        val monitorIntent = Intent(this, SfcBleMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(monitorIntent)
        } else {
            startService(monitorIntent)
        }
    }

    override fun onDestroy() {
        stopHomeRefresh()
        scanDialog?.dismiss()
        bleManager.release()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        foregroundVisible = true
        if (isRegistered()) ensureMonitorRunning()
        if (isRegistered() && homeVisible) {
            loadHomeLog()
            startHomeRefresh()
        }
    }

    override fun onPause() {
        foregroundVisible = false
        stopHomeRefresh()
        super.onPause()
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) appendStatus("권한 확인 완료")
    }

    private fun isRegistered(): Boolean = appPrefs.getBoolean("registered", false)

    private fun showPairing() {
        homeVisible = false
        selectedDevice = null
        selectedDeviceName = ""
        val content = page()
        content.gravity = Gravity.CENTER_HORIZONTAL
        // 등록 여정의 시작 — 차분한 새벽빛에서 출발해 완료 화면의 골든아워로 옮겨간다.
        content.addView(ambientGlow(SfTheme.GLOW_DAWN))
        content.addView(appLogo(dp(104)))
        content.addView(title("Safe Finder", 30f).apply { gravity = Gravity.CENTER })
        content.addView(card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(text("아직 등록된 기기가 없어요.", 23f, SfTheme.INK, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(4))
            })
            addView(text("아래 순서대로 진행해 주세요.", 16f, SfTheme.INK_SOFT).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(18))
            })
            scanButton = primaryButton("기기 찾기") { showBlePickerDialog() }
            registerButton = primaryButton("찾은 기기 등록하기") { beginProvisionFlow() }
            addView(scanButton, rowParams(top = 4))
            addView(registerButton, rowParams(top = 12))
            addView(secondaryButton("신규 보호자 등록") { showNewGuardianRegistration() }, rowParams(top = 12))
        }, narrowCardParams(top = 42))
        setContentView(content.root())
    }

    private fun showNewGuardianRegistration() {
        homeVisible = false
        var resolvedElder: ElderInfo? = null
        val content = page()
        content.addView(header("신규 보호자 등록", showBack = true))
        val deviceId = input("Device ID", "")
        val elderNameView = text("Elder name: 조회 전", 16f, SfTheme.INK_SOFT, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        val guardianName = input("보호자 이름", "")
        val guardianPhone = input("보호자 연락처", "", InputType.TYPE_CLASS_PHONE)
        val relation = input("관계", "")

        content.addView(card().apply {
            addView(label("Device ID"))
            addView(deviceId, rowParams(height = 52))
            addView(primaryButton("Send") {
                val id = deviceId.text.toString().trim()
                if (id.isBlank()) {
                    toast("Device ID를 입력해 주세요.")
                    return@primaryButton
                }
                elderNameView.text = "Elder name: 조회 중..."
                appendStatus("ELDER LOOKUP REQUEST deviceId=$id")
                Thread {
                    runCatching { apiClient.getElder(id) }
                        .onSuccess { elder ->
                            appendStatus("ELDER LOOKUP RESPONSE ${elder.rawJson}")
                            runOnUiThread {
                                if (elder.elderId.isBlank()) {
                                    resolvedElder = null
                                    elderNameView.text = "Elder name: 조회 실패"
                                    AlertDialog.Builder(this@MainActivity)
                                        .setTitle("조회 실패")
                                        .setMessage("서버에서 Elder ID를 받지 못했습니다.")
                                        .setPositiveButton("확인", null)
                                        .show()
                                } else {
                                    resolvedElder = elder
                                    appPrefs.edit()
                                        .putString("device_id", elder.deviceId.ifBlank { id })
                                        .putString("elder_id", elder.elderId)
                                        .putString("elder_name", elder.name)
                                        .apply()
                                    elderNameView.text = "Elder name: ${elder.name.ifBlank { "-" }}"
                                }
                            }
                        }
                        .onFailure { error ->
                            appendStatus("ELDER LOOKUP ERROR ${error.message ?: error.javaClass.simpleName}")
                            runOnUiThread {
                                resolvedElder = null
                                elderNameView.text = "Elder name: 조회 실패"
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("조회 실패")
                                    .setMessage(error.message ?: error.javaClass.simpleName)
                                    .setPositiveButton("확인", null)
                                    .show()
                            }
                        }
                }.start()
            }, rowParams(top = 12))
            addView(elderNameView, rowParams(top = 6))
            addView(label("보호자 이름"))
            addView(guardianName, rowParams(height = 52))
            addView(label("보호자 연락처"))
            addView(guardianPhone, rowParams(height = 52))
            addView(label("관계"))
            addView(relation, rowParams(height = 52))
            addView(primaryButton("보호자 등록") {
                val elder = resolvedElder
                if (elder == null) {
                    toast("Device ID를 먼저 조회해 주세요.")
                    return@primaryButton
                }
                val missing = listOf(
                    "보호자 이름" to guardianName.text.toString(),
                    "보호자 연락처" to guardianPhone.text.toString(),
                    "관계" to relation.text.toString()
                ).filter { it.second.isBlank() }.map { it.first }
                if (missing.isNotEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("입력 필요")
                        .setMessage("${missing.joinToString(", ")} 항목을 채워 주세요.")
                        .setPositiveButton("확인", null)
                        .show()
                    return@primaryButton
                }
                val draft = GuardianDraft(
                    name = guardianName.text.toString().trim(),
                    phone = guardianPhone.text.toString().trim(),
                    relation = relation.text.toString().trim()
                )
                createNewPhoneGuardian(elder, draft)
            }, rowParams(top = 24))
        }, narrowCardParams(top = 24))
        setContentView(content.root())
    }

    private fun createNewPhoneGuardian(elder: ElderInfo, draft: GuardianDraft) {
        appendStatus("NEW GUARDIAN CREATE REQUEST elderId=${elder.elderId} ${draft.toJson()}")
        Thread {
            runCatching { apiClient.createGuardian(elder.elderId, draft) }
                .onSuccess { result ->
                    appendStatus("NEW GUARDIAN CREATE RESPONSE ${result.responseCode}: ${result.responseBody}")
                    runOnUiThread {
                        if (result.responseCode in 200..299) {
                            appPrefs.edit()
                                .putString("device_id", elder.deviceId)
                                .putString("elder_id", elder.elderId)
                                .putString("elder_name", elder.name)
                                .putString("guardian_name", draft.name)
                                .putString("guardian_phone", draft.phone)
                                .putString("guardian_relation", draft.relation)
                                .putString("last_guardian_create_response", result.responseBody)
                                .putBoolean("registered", true)
                                .apply()
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("보호자 등록 완료")
                                .setMessage("${elder.name} 보호자로 등록되었습니다.")
                                .setPositiveButton("확인") { _, _ -> showHome() }
                                .show()
                        } else {
                            showGuardianMutationError("보호자 등록 실패", result)
                        }
                    }
                }
                .onFailure { error ->
                    appendStatus("NEW GUARDIAN CREATE ERROR ${error.message ?: error.javaClass.simpleName}")
                    runOnUiThread { showGuardianError(error) }
                }
        }.start()
    }

    private fun showBlePickerDialog() {
        scannedDevices.clear()
        dialogDeviceButtons.clear()
        dialogDeviceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(emptyState(
                "주변에서 기기를 찾고 있어요.",
                "기기 전원이 켜져 있고 가까이 있는지 확인해 주세요."
            ))
        }
        scanDialog = bottomSheet { dialog ->
            addView(text("주변 기기", 21f, SfTheme.INK, true))
            addView(text("등록할 기기를 골라 주세요.", 16f, SfTheme.INK_SOFT).apply {
                setPadding(0, dp(6), 0, dp(4))
            })
            addView(ScrollView(this@MainActivity).apply {
                addView(dialogDeviceList, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))
            addView(secondaryButton("닫기") { dialog.dismiss() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                    topMargin = dp(10)
                })
        }
        scanDialog?.setOnDismissListener {
            dialogDeviceList = null
            bleManager.stopScan()
        }
        bleManager.startScan()
    }

    @SuppressLint("MissingPermission")
    private fun addOrUpdateDevice(scanned: ScannedDevice) {
        scannedDevices[scanned.address] = scanned
        val list = dialogDeviceList ?: return
        // Update rows in place instead of rebuilding the whole list on every scan result.
        // Rebuilding on each callback (many per second) was destroying the buttons under the
        // user's finger, so taps never registered. Keep each device's Button stable — only add
        // a row for a newly-seen address and refresh the label of existing rows.
        val existing = dialogDeviceButtons[scanned.address]
        if (existing != null) {
            existing.text = signalLabel(scanned.rssi)
            return
        }
        // First real device → drop the "탐색 중" placeholder before adding the first row.
        if (dialogDeviceButtons.isEmpty()) list.removeAllViews()

        // dBm 은 보호자에게 아무 의미가 없다. "가까움/보통/멂"으로만 보여준다.
        val signalView = text(signalLabel(scanned.rssi), 13f, SfTheme.PRIMARY_DARK, true).apply {
            background = rounded(SfTheme.PRIMARY_SOFT, 999)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedStroke(SfTheme.SURFACE, 13, SfTheme.LINE, 2)
            setOnClickListener { onDevicePicked(scanned) }
            addView(text("◉", 20f, SfTheme.PRIMARY).apply {
                gravity = Gravity.CENTER
                background = rounded(SfTheme.SURFACE_ALT, 9)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(11) })
            addView(text(scanned.name.ifBlank { "이름 없는 기기" }, 18f, SfTheme.INK, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(signalView)
        }
        dialogDeviceButtons[scanned.address] = signalView
        list.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
    }

    /** A device row was tapped: select it, connect over BLE, and start the registration flow. */
    private fun onDevicePicked(item: ScannedDevice) {
        selectedDevice = item.device
        selectedDeviceName = item.name.ifBlank { item.address }
        scanButton?.text = selectedDeviceName
        bleManager.stopScan()
        scanDialog?.dismiss()
        appendStatus("BLE 선택: $selectedDeviceName — 연결 및 등록을 시작합니다")
        beginProvisionFlow()
    }

    private fun beginProvisionFlow() {
        val device = selectedDevice
        if (device == null) {
            confirmSheet(
                headline = "먼저 기기를 골라 주세요",
                message = "등록하려면 주변 기기 목록에서 기기를 하나 선택해야 해요.",
                confirmLabel = "기기 찾기"
            ) { showBlePickerDialog() }
            return
        }

        registerButton?.isEnabled = false
        registerButton?.text = "기기 번호를 받는 중..."
        appendStatus("기기 등록 요청 시작")
        Thread {
            runCatching { apiClient.provisionDevice(selectedDeviceName, device.address) }
                .onSuccess { result ->
                    appendStatus("PROVISION REQUEST ${result.requestJson}")
                    appendStatus("PROVISION RESPONSE ${result.responseCode}: ${result.responseBody}")
                    runOnUiThread {
                        registerButton?.isEnabled = true
                        registerButton?.text = "찾은 기기 등록하기"
                        val deviceId = result.deviceId
                        if (result.responseCode !in 200..299 || deviceId.isNullOrBlank()) {
                            confirmSheet(
                                headline = "기기 번호를 받지 못했어요",
                                message = "인터넷 연결을 확인한 뒤 다시 시도해 주세요.",
                                confirmLabel = "다시 시도"
                            ) { beginProvisionFlow() }
                        } else {
                            appPrefs.edit().putString("device_id", deviceId).apply()
                            showRegistrationForm(deviceId)
                        }
                    }
                }
                .onFailure { error ->
                    appendStatus("PROVISION ERROR ${error.message ?: error.javaClass.simpleName}")
                    runOnUiThread {
                        registerButton?.isEnabled = true
                        registerButton?.text = "찾은 기기 등록하기"
                        confirmSheet(
                            headline = "기기 번호를 받지 못했어요",
                            message = "인터넷 연결을 확인한 뒤 다시 시도해 주세요.",
                            confirmLabel = "다시 시도"
                        ) { beginProvisionFlow() }
                    }
                }
        }.start()
    }

    private fun showRegistrationForm(deviceId: String) {
        homeVisible = false
        val defaults = defaultsReader.read()
        val content = page()
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(ambientGlow(SfTheme.GLOW_MORNING))
        content.addView(title(deviceId, 30f).apply { gravity = Gravity.CENTER })
        val elder = input("환자 성함", appPrefs.getString("elder_name", "아버님").orEmpty())
        val guardian = input("보호자 성함", defaults.ownerName.ifBlank { "방효식" })
        val phone = input("전화번호", defaults.phoneNumber, InputType.TYPE_CLASS_PHONE)
        val wifiApName = input("연결된 WiFi", defaults.wifiSsid.ifBlank { defaults.wifiName }).apply {
            isEnabled = false
            background = rounded(SfTheme.SURFACE_ALT, 13)
            setTextColor(SfTheme.INK_SOFT)
        }
        val safeZoneName = input("이 장소 이름 (예: 집)", appPrefs.getString("wifi_name", "집").orEmpty())
        val password = input("WiFi 비밀번호", "bang8813", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val wifiSsid = defaults.wifiSsid.ifBlank { defaults.wifiName }
        val wifiBssid = defaults.wifiBssid

        content.addView(card().apply {
            addView(label("환자 성함"))
            addView(elder, rowParams(height = 52))
            addView(label("보호자 성함"))
            addView(guardian, rowParams(height = 52))
            addView(label("전화번호"))
            addView(phone, rowParams(height = 52))
            addView(label("연결된 WiFi"))
            addView(wifiApName, rowParams(height = 52))
            addView(text("지금 이 폰이 연결된 WiFi 입니다. 바꿀 수 없어요.", 14f, SfTheme.INK_SOFT).apply {
                setPadding(dp(4), dp(5), 0, 0)
            })
            addView(label("이 장소 이름"))
            addView(safeZoneName, rowParams(height = 52))
            addView(label("WiFi 비밀번호"))
            addView(password, rowParams(height = 52))
            addView(text("비밀번호는 기기에만 전달되고 서버에 저장되지 않습니다.", 14f, SfTheme.INK_SOFT).apply {
                setPadding(dp(4), dp(5), 0, 0)
            })
            addView(primaryButton("기기 등록") {
                val device = selectedDevice
                if (device == null) {
                    toast("기기를 먼저 선택해 주세요.")
                    showPairing()
                    return@primaryButton
                }
                val missing = listOf(
                    "환자 성함" to elder.text.toString(),
                    "보호자 성함" to guardian.text.toString(),
                    "전화번호" to phone.text.toString(),
                    "연결된 WiFi" to wifiApName.text.toString(),
                    "이 장소 이름" to safeZoneName.text.toString(),
                    "WiFi 비밀번호" to password.text.toString()
                ).filter { it.second.isBlank() }.map { it.first }
                if (missing.isNotEmpty()) {
                    confirmSheet(
                        headline = "아직 비어 있는 항목이 있어요",
                        message = "${missing.joinToString(", ")}을(를) 채운 뒤 다시 등록해 주세요.",
                        confirmLabel = "확인"
                    ) { }
                    return@primaryButton
                }
                val phoneFix = readPhoneLocation()
                // Best-effort read of the phone's own Mobile Hotspot password so a headless device
                // (SFA) can auto-join it when away. Hidden on Android 11+ → SFA falls back to its
                // compile-time default / the manually-registered "내 폰" zone password.
                val hotspotDefaults = runCatching { PhoneHotspotReader(this@MainActivity).read() }.getOrNull()
                val form = ProvisioningForm(
                    deviceId = deviceId,
                    elderName = elder.text.toString().ifBlank { "아버님" },
                    guardianName = guardian.text.toString().ifBlank { "방효식" },
                    guardianPhone = phone.text.toString().ifBlank { "010-7260-8813" },
                    wifiName = safeZoneName.text.toString().ifBlank { "집" },
                    wifiPassword = password.text.toString(),
                    wifiBssid = wifiBssid.ifBlank { "*******" },
                    wifiSsid = wifiSsid.ifBlank { "******" },
                    bluetoothName = selectedDeviceName.ifBlank { defaults.bluetoothName },
                    bluetoothBssid = device.address,
                    bluetoothSsid = selectedDeviceName.ifBlank { defaults.bluetoothName },
                    bleId = device.address,
                    hotspotSsid = SfcConfig.HOTSPOT_SSID,
                    hotspotPassword = hotspotDefaults?.password.orEmpty(),
                    centerLat = phoneFix?.latitude,
                    centerLng = phoneFix?.longitude
                )
                appendStatus(
                    if (phoneFix != null) "폰 GPS 좌표 첨부: ${phoneFix.latitude}, ${phoneFix.longitude}"
                    else "폰 GPS fix 없음 (centerLat/centerLng 생략)"
                )
                sendConfig(device, form)
            }, rowParams(top = 24))
        }, narrowCardParams(top = 22))
        setContentView(content.root())
    }

    private fun sendConfig(device: BluetoothDevice, form: ProvisioningForm) {
        val json = form.toJson()
        appPrefs.edit()
            .putString("device_id", form.deviceId)
            .putString("elder_name", form.elderName)
            .putString("guardian_name", form.guardianName)
            .putString("guardian_phone", form.guardianPhone)
            .putString("wifi_name", form.wifiName)
            .putBoolean("registered", false)
            .apply()
        monitorPrefs.edit()
            .putString("last_config_json", json)
            .putString("last_device_address", device.address)
            .apply()
        appendStatus("SFD로 기기 등록 정보 전송")
        pendingConfigDevice = device
        pendingConfigJson = json
        bleManager.sendConfig(device, json)
        val monitorIntent = Intent(this, SfcBleMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(monitorIntent)
        } else {
            startService(monitorIntent)
        }
        // Config bytes are on the wire; wait for SFD/server registration ACK. The spinner
        // is replaced by showComplete() once onRegistrationConfirmed → completeRegistration fires.
        showRegistrationWaiting()
    }

    /** Waiting screen shown while SFD relays the config and the backend confirms registration. */
    private fun showRegistrationWaiting() {
        homeVisible = false
        registrationWaiting = true
        refreshHandler.removeCallbacks(registrationTimeoutRunnable)
        refreshHandler.postDelayed(registrationTimeoutRunnable, REGISTRATION_TIMEOUT_MS)
        val content = page()
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(ambientGlow(SfTheme.GOLD_SOFT))
        content.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(SfTheme.PRIMARY)
        }, LinearLayout.LayoutParams(dp(88), dp(88)).apply { topMargin = dp(120) })
        content.addView(title("잠시 기다려 주세요", 30f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(30), 0, dp(8))
        })
        content.addView(text("기기를 등록하고 있습니다.\n서버 확인이 끝나면 자동으로\n다음 화면으로 넘어갑니다.", 20f, SfTheme.INK).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.18f)
        })
        // 여기까지 오면 최대 40초를 기다리게 되는데, 그동안 아무 안내가 없으면 멈춘 것처럼 보인다.
        // 얼마나 걸리는지와 오래 걸릴 때 할 일을 처음부터 함께 보여 준다.
        content.addView(noticeCard(
            "보통 10초 안에 끝납니다.",
            "1분이 지나도 이 화면이 그대로면 기기 전원이 켜져 있는지 확인한 뒤 다시 시도해 주세요."
        ), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(6), dp(28), dp(6), 0)
        })
        setContentView(content.root())
    }

    /**
     * Last known phone location using fine providers. Returns null when no permission or no fix,
     * in which case centerLat/centerLng are omitted from the provisioning payload.
     */
    @SuppressLint("MissingPermission")
    private fun readPhoneLocation(): Location? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        for (provider in providers) {
            val loc = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        return best
    }

    private fun onRegistrationTimeout() {
        if (!registrationWaiting || isRegistered()) return
        AlertDialog.Builder(this)
            .setTitle("등록이 오래 걸리고 있어요")
            .setMessage("기기 전원이 켜져 있는지 확인한 뒤 다시 시도해 주세요.")
            .setPositiveButton("다시 시도") { _, _ ->
                val device = pendingConfigDevice
                val json = pendingConfigJson
                if (device != null && json != null) {
                    appendStatus("등록 재시도: 설정 재전송")
                    bleManager.sendConfig(device, json)
                    showRegistrationWaiting()
                } else {
                    registrationWaiting = false
                    showPairing()
                }
            }
            .setNegativeButton("처음으로") { _, _ ->
                registrationWaiting = false
                showPairing()
            }
            .setCancelable(false)
            .show()
    }

    private fun completeRegistration() {
        if (isRegistered()) return
        registrationWaiting = false
        refreshHandler.removeCallbacks(registrationTimeoutRunnable)
        // Record when registration finished so the BLE monitor can suppress the hotspot for a
        // short settle window — otherwise the leftover SFD sightings from provisioning would
        // raise the hotspot the instant the monitor starts, before the SFD has reconnected to
        // home Wi-Fi and stopped advertising.
        appPrefs.edit()
            .putBoolean("registered", true)
            .putLong("registered_at", System.currentTimeMillis())
            .apply()
        showComplete()
    }

    private fun showComplete() {
        homeVisible = false
        val content = page()
        content.gravity = Gravity.CENTER_HORIZONTAL
        // 여정의 끝 — 골든아워.
        content.addView(ambientGlow(SfTheme.GLOW_DONE))
        content.addView(TextView(this).apply {
            text = "✓"
            textSize = 66f
            gravity = Gravity.CENTER
            // 골드 위의 흰 체크는 2.1:1 로 거의 안 보인다. 골드 채움 + INK 체크가 유일하게 읽히는 조합.
            setTextColor(SfTheme.INK)
            background = oval(SfTheme.GOLD)
        }, LinearLayout.LayoutParams(dp(118), dp(118)).apply { topMargin = dp(92) })
        content.addView(title("기기 등록 완료", 34f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(8))
        })
        content.addView(text("기기 등록이 끝났습니다.\n이제 '${elderName()}'의 안전과 위치를\n확인하실 수 있습니다.", 21f, SfTheme.INK).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.18f)
        })
        content.addView(primaryButton("확인") { showHome() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply {
            setMargins(dp(28), dp(34), dp(28), 0)
        })
        setContentView(content.root())
    }

    private fun showHome() {
        homeVisible = true
        renderHome(latestHomeLog, isLoading = true)
        loadHomeLog()
        startHomeRefresh()
    }

    private fun startHomeRefresh() {
        refreshHandler.removeCallbacks(homeRefreshRunnable)
        if (foregroundVisible && isRegistered() && homeVisible) {
            refreshHandler.postDelayed(homeRefreshRunnable, HOME_REFRESH_INTERVAL_MS)
        }
    }

    private fun stopHomeRefresh() {
        refreshHandler.removeCallbacks(homeRefreshRunnable)
    }

    /**
     * Cache the registered safe zones (with their names) from the server into monitorPrefs so the
     * home label and the hotspot monitor can resolve zone names. Devices adopted via Device-ID
     * reactivation never went through BLE sendConfig, so their monitorPrefs is otherwise empty and
     * the zone label falls back to the raw SSID (e.g. "Nobug") instead of the name ("집"/"내폰").
     */
    private fun ensureConfigCached() {
        val existing = monitorPrefs.getString("last_config_json", null)
        if (!existing.isNullOrBlank() && existing.contains("safeZones")) return
        runCatching {
            val elder = apiClient.getElder(currentDeviceId())
            val zones = apiClient.getSafeZones(elder.elderId)
            if (zones.isEmpty()) return
            val arr = org.json.JSONArray()
            zones.forEach { arr.put(org.json.JSONObject(it.rawJson)) }
            val cfg = org.json.JSONObject().put("safeZones", arr)
            monitorPrefs.edit().putString("last_config_json", cfg.toString()).apply()
            appendStatus("SAFEZONE CONFIG CACHED (${zones.size} zones) from server")
        }.onFailure { appendStatus("SAFEZONE CONFIG CACHE FAIL ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun loadHomeLog() {
        val deviceId = currentDeviceId()
        val date = LocalDate.now().toString()
        Thread {
            ensureConfigCached()
            runCatching {
                apiClient.getDeviceLogsByDate(deviceId, date, page = 0, size = 50)
            }.onSuccess { page ->
                val latest = page.logs.maxByOrNull { it.eventTimestamp }
                latestHomeLogs = page.logs
                latestHomeLog = latest
                lastSyncedAtMillis = System.currentTimeMillis()
                appendStatus("HOME LOG REQUEST /devices/$deviceId/logs?date=$date&page=0&size=50")
                runOnUiThread { renderHome(latest, isLoading = false) }
            }.onFailure { error ->
                appendStatus("HOME LOG ERROR ${error.message ?: error.javaClass.simpleName}")
                runOnUiThread { renderHome(latestHomeLog, isLoading = false, error = error) }
            }
        }.start()
    }

    /**
     * 홈 화면. 구성 순서(헤더 → 기준 시각 → 어르신 이름 → 큰 원형 상태 → 구역 이름 → 상태 카드 →
     * 액션 → 배터리)는 그대로 두고, 색과 질감만 시안에 맞춘다. 상태는 상단 글로우 → 원형 테두리 →
     * 상태 배지 순으로 세 번 반복해 전달되므로, 색을 구분하지 못해도 문구만으로 읽힌다.
     */
    private fun renderHome(log: DeviceLogEntry?, isLoading: Boolean = false, error: Throwable? = null) {
        homeVisible = true
        val state = homeAccentKind(log, error)
        val accent = homeAccentColor(state)
        val accentSoft = homeAccentSoftColor(state)
        val accentText = homeAccentTextColor(state)

        val content = page()
        content.addView(header(glow = homeGlowColor(state)))
        content.addView(text(homeSyncLine(log, isLoading), 15f, SfTheme.INK_FAINT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        })
        content.addView(title(elderName(), 34f).apply { gravity = Gravity.CENTER })

        // 상태 오브: 흰 원판 + 상태색 테두리 + 선화 아이콘. 색 하나에 기대지 않도록 아이콘 모양도 바뀐다.
        content.addView(ImageView(this).apply {
            setImageResource(homeIconRes(log))
            setColorFilter(accent)
            background = ovalStroke(SfTheme.SURFACE, accentSoft, 5)
            setPadding(dp(56), dp(56), dp(56), dp(56))
        }, LinearLayout.LayoutParams(dp(206), dp(206)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(20)
        })
        content.addView(title(homeZoneLabel(log), 30f).apply {
            gravity = Gravity.CENTER
            setTextColor(if (state == HomeAccentKind.SOS) SfTheme.DANGER else SfTheme.INK)
            setPadding(0, dp(16), 0, 0)
        })

        content.addView(card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(statusPill(homeStatusLabel(log, isLoading, error), accentText, accentSoft))
            addView(text(homeDetailLabel(log), 14f, SfTheme.INK_SOFT).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            })
            addView(text(homeDurationLabel(log), 15f, accentText, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(14), 0, 0)
        })

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        if (state == HomeAccentKind.SOS) {
            // 긴급 상황에서는 기록을 뒤져볼 일이 없다. 바로 행동할 수 있는 두 가지만 남긴다.
            actionRow.addView(
                dangerButton("전화 걸기") { callFirstGuardian() },
                LinearLayout.LayoutParams(0, dp(62), 1f).apply { marginEnd = dp(8) }
            )
            actionRow.addView(
                secondaryButton("위치 보기") { showMap() },
                LinearLayout.LayoutParams(0, dp(62), 1f).apply { marginStart = dp(8) }
            )
        } else {
            // 주의 상태에서는 지도 타일만 앰버로 물들어 지금 볼 곳을 가리킨다.
            val mapAccent = if (state == HomeAccentKind.WARNING) accent else SfTheme.PRIMARY
            val mapAccentSoft = if (state == HomeAccentKind.WARNING) accentSoft else SfTheme.PRIMARY_SOFT
            actionRow.addView(
                tile("지도 보기", "⌖", mapAccent, mapAccentSoft) { showMap() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
            )
            actionRow.addView(
                tile("기록 보기", "▦") { showLogs() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
            )
        }
        content.addView(actionRow)
        content.addView(batteryBar(log?.battery), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(22)
        })
        setContentView(content.root())
    }

    /** 홈 화면 상단에 한 줄로 합친 "기준 시각 · 동기화" 안내. */
    private fun homeSyncLine(log: DeviceLogEntry?, isLoading: Boolean): String {
        val basis = homeTimestampLabel(log, isLoading)
        val sync = when {
            lastSyncedAtMillis == 0L -> "동기화 확인 중"
            else -> {
                val elapsed = System.currentTimeMillis() - lastSyncedAtMillis
                val minutes = elapsed / 60000L
                if (minutes < 1) "방금 동기화" else "${minutes}분 전 동기화"
            }
        }
        return "$basis 기준 · $sync"
    }

    /** 긴급 상황에서 등록된 첫 보호자에게 바로 전화를 건다. */
    private fun callFirstGuardian() {
        Thread {
            val number = runCatching {
                val elder = apiClient.getElder(currentDeviceId())
                apiClient.getGuardians(elder.elderId).firstOrNull()?.phone
            }.getOrNull()
            runOnUiThread {
                if (number.isNullOrBlank()) {
                    toast("등록된 보호자 연락처가 없습니다. 메뉴에서 보호자를 등록해 주세요.")
                } else {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                }
            }
        }.start()
    }

    private fun showSafeZoneForm() {
        homeVisible = false
        val content = page()
        content.addView(header("안전구역 등록", showBack = true))
        content.addView(title(currentDeviceId(), 26f).apply { gravity = Gravity.CENTER })
        content.addView(card().apply {
            addView(text("안전구역 정보를 불러오는 중입니다.", 17f, SfTheme.INK_SOFT).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
        }, narrowCardParams(top = 24))
        setContentView(content.root())

        Thread {
            runCatching {
                val elder = apiClient.getElder(currentDeviceId())
                val zones = apiClient.getSafeZones(elder.elderId)
                elder to zones
            }.onSuccess { (elder, zones) ->
                runOnUiThread {
                    selectedSafeZoneIndex = -1
                    showSafeZoneList(elder, zones)
                }
            }.onFailure { error ->
                runOnUiThread { showSafeZoneError(error) }
            }
        }.start()
    }

    private fun showSafeZoneList(elder: ElderInfo, zones: List<SafeZoneInfo>) {
        homeVisible = false
        val content = page()
        content.addView(header("안전구역 등록", showBack = true))
        content.addView(title(currentDeviceId(), 25f).apply { gravity = Gravity.CENTER })
        content.addView(text(elderName(), 16f, SfTheme.INK_SOFT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        })

        if (zones.isEmpty()) {
            content.addView(emptyState("등록된 안전구역이 없습니다.", "아래 ‘추가’로 집이나 자주 가는 곳의 Wi-Fi를 등록해 주세요."))
        } else {
            zones.forEachIndexed { index, zone ->
                content.addView(
                    listItem(
                        checked = selectedSafeZoneIndex == index,
                        glyph = if (zone.zoneType.equals("BLE", ignoreCase = true)) "◉" else "⌂",
                        titleText = zone.name.ifBlank { zone.ssid },
                        subText = "${zoneTypeLabel(zone.zoneType)} · ${zone.ssid.ifBlank { "이름 없음" }}",
                        pillText = if (zone.enabled) "사용 중" else "꺼짐",
                        pillOn = zone.enabled
                    ) {
                        selectedSafeZoneIndex = if (selectedSafeZoneIndex == index) -1 else index
                        showSafeZoneList(elder, zones)
                    }
                )
            }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        // 아무것도 고르지 않았을 때는 "추가"만 보인다. 하나를 고르면 그때 켜기·끄기/삭제가 나타난다.
        if (selectedSafeZoneIndex in zones.indices) {
            val selected = zones[selectedSafeZoneIndex]
            buttons.addView(
                text(if (selected.enabled) "이 안전구역 끄기" else "이 안전구역 켜기", 17f, SfTheme.PRIMARY_DARK, true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(SfTheme.PRIMARY_SOFT, 12)
                    setOnClickListener { confirmUpdateSafeZoneEnabled(elder, selected) }
                },
                LinearLayout.LayoutParams(0, dp(56), 1.6f)
            )
            buttons.addView(
                text("삭제", 17f, SfTheme.DANGER, true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(SfTheme.DANGER_SOFT, 12)
                    setOnClickListener { confirmDeleteSafeZone(elder, selected) }
                },
                LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(8) }
            )
        } else {
            buttons.addView(primaryButton("안전구역 추가") { showSafeZoneAddForm(elder) },
                LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        content.addView(buttons)
        content.addView(secondaryButton("내 폰을 안전구역으로 추가") { showMyPhoneAddForm(elder) }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) }
        })
        content.addView(text("어르신이 보호자 폰 곁에 있을 때도 안전구역으로 인정됩니다.", 14f, SfTheme.INK_SOFT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        setContentView(content.root())
    }

    private fun showSafeZoneAddForm(elder: ElderInfo) {
        homeVisible = false
        val defaults = defaultsReader.read()
        val wifiSsid = defaults.wifiSsid.ifBlank { defaults.wifiName }
        val wifiBssid = defaults.wifiBssid
        val content = page()
        content.addView(header("안전구역 추가", showBack = true))
        content.addView(title(currentDeviceId(), 25f).apply { gravity = Gravity.CENTER })
        content.addView(text(elderName(), 16f, SfTheme.INK_SOFT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        })

        val zoneType = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                ZONE_TYPE_LABELS
            )
            background = roundedStroke(SfTheme.SURFACE, 13, SfTheme.LINE, 2)
            setPadding(dp(14), 0, dp(14), 0)
        }
        val name = input("이 장소 이름 (예: 집)", appPrefs.getString("wifi_name", "집").orEmpty().ifBlank { "집" })
        val ssid = input("WiFi 이름", wifiSsid).apply {
            isEnabled = false
            background = rounded(SfTheme.SURFACE_ALT, 13)
            setTextColor(SfTheme.INK_SOFT)
        }
        val password = input("WiFi 비밀번호", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val bssid = input("기기 식별값 (자동)", wifiBssid).apply {
            isEnabled = false
            background = rounded(SfTheme.SURFACE_ALT, 13)
            setTextColor(SfTheme.INK_SOFT)
        }
        val enabled = CheckBox(this).apply {
            text = "지금부터 이 안전구역 사용"
            textSize = 17f
            isChecked = true
            setTextColor(SfTheme.INK)
            buttonTintList = android.content.res.ColorStateList.valueOf(SfTheme.PRIMARY)
            setPadding(dp(6), dp(12), 0, dp(12))
        }

        content.addView(card().apply {
            addView(label("안전구역 종류"))
            addView(zoneType, rowParams(height = 52))
            addView(label("이 장소 이름"))
            addView(name, rowParams(height = 52))
            addView(label("WiFi 이름"))
            addView(ssid, rowParams(height = 52))
            addView(label("WiFi 비밀번호"))
            addView(password, rowParams(height = 52))
            addView(text("비밀번호는 기기에만 전달되고 서버에 저장되지 않습니다.", 14f, SfTheme.INK_SOFT).apply {
                setPadding(dp(4), dp(5), 0, 0)
            })
            addView(label("기기 식별값"))
            addView(bssid, rowParams(height = 52))
            addView(text("이 WiFi 공유기를 구분하는 값입니다. 자동으로 채워집니다.", 14f, SfTheme.INK_SOFT).apply {
                setPadding(dp(4), dp(5), 0, 0)
            })
            addView(enabled, rowParams(height = 52))
            addView(primaryButton("등록") {
                val zoneTypeValue = zoneTypeCode(zoneType.selectedItem?.toString().orEmpty())
                val missing = listOf(
                    "이 장소 이름" to name.text.toString(),
                    "WiFi 이름" to ssid.text.toString(),
                    "WiFi 비밀번호" to password.text.toString(),
                    "기기 식별값" to bssid.text.toString()
                ).filter { it.second.isBlank() }.map { it.first }
                if (missing.isNotEmpty()) {
                    confirmSheet(
                        headline = "아직 비어 있는 항목이 있어요",
                        message = "${missing.joinToString(", ")}을(를) 채운 뒤 다시 등록해 주세요.",
                        confirmLabel = "확인"
                    ) { }
                    return@primaryButton
                }
                // Capture the guardian phone's GPS as the Home geofence center so the map can show
                // the Home location for this fixed AP (the tracked device has no GPS while at home).
                val fix = if (zoneTypeValue == "WIFI") readPhoneLocation() else null
                if (fix != null) toast("Home 좌표 첨부: ${"%.5f".format(fix.latitude)}, ${"%.5f".format(fix.longitude)}")
                val draft = SafeZoneDraft(
                    zoneType = zoneTypeValue,
                    name = name.text.toString(),
                    bssid = bssid.text.toString(),
                    ssid = ssid.text.toString(),
                    enabled = enabled.isChecked,
                    apType = "FIXED_AP", // manual/initial AP registration is always a fixed router
                    centerLat = fix?.latitude,
                    centerLng = fix?.longitude
                )
                registerSafeZone(elder, draft, password.text.toString())
            }, rowParams(top = 24))
        }, narrowCardParams(top = 18))
        setContentView(content.root())
    }

    /**
     * Returns the BSSID used for the "내 폰" hotspot safe zone. The phone's real Wi-Fi/hotspot MAC
     * is unreadable on modern Android, so we mint a stable synthetic locally-administered MAC once
     * and persist it in SFC, reusing the same value on every subsequent open and server request.
     */
    private fun getOrCreateMyPhoneBssid(): String {
        appPrefs.getString("my_phone_bssid", null)?.takeIf { it.isNotBlank() }?.let { return it }
        val rnd = java.util.Random()
        val tail = (0 until 5).joinToString(":") { "%02x".format(rnd.nextInt(256)) }
        val mac = "02:$tail" // 0x02 => unicast, locally administered (safe synthetic MAC)
        appPrefs.edit().putString("my_phone_bssid", mac).apply()
        return mac
    }

    /**
     * Registers the guardian phone's own Mobile Hotspot as a WIFI safe zone ("보호자 근접").
     * SSID / password are pre-filled best-effort (see [PhoneHotspotReader]); the phone MAC is
     * unreadable on modern Android, so the BSSID is a stable synthetic value assigned & persisted
     * by SFC (see [getOrCreateMyPhoneBssid]) and sent to the server. Every field stays editable.
     */
    private fun showMyPhoneAddForm(elder: ElderInfo) {
        homeVisible = false
        val hotspot = runCatching { PhoneHotspotReader(this).read() }.getOrNull()
        val content = page()
        content.addView(header("내 폰 추가", showBack = true))
        content.addView(title("내 폰을 안전구역으로", 24f).apply { gravity = Gravity.CENTER })
        content.addView(text(elderName(), 16f, SfTheme.INK_SOFT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(4))
        })

        val name = input("이 장소 이름", "내 폰")
        val ssid = input("핫스팟 이름", hotspot?.ssid.orEmpty().ifBlank { SfcConfig.HOTSPOT_SSID })
        val password = input("핫스팟 비밀번호", hotspot?.password.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        // Phone MAC is unreadable on modern Android; use the real value if by chance available,
        // otherwise the stable synthetic BSSID SFC assigned and persisted for this phone.
        val bssid = input("기기 식별값 (자동)", hotspot?.bssid?.takeIf { it.isNotBlank() } ?: getOrCreateMyPhoneBssid()).apply {
            isEnabled = false
            background = rounded(SfTheme.SURFACE_ALT, 13)
            setTextColor(SfTheme.INK_SOFT)
        }
        val enabled = CheckBox(this).apply {
            text = "지금부터 이 안전구역 사용"
            textSize = 17f
            isChecked = true
            setTextColor(SfTheme.INK)
            buttonTintList = android.content.res.ColorStateList.valueOf(SfTheme.PRIMARY)
            setPadding(dp(6), dp(12), 0, dp(12))
        }

        content.addView(card().apply {
            addView(noticeCard(
                "보호자 폰 곁도 안전구역이 됩니다.",
                "어르신이 이 폰의 핫스팟에 연결되어 있는 동안에는 집 밖이어도 안전한 것으로 봅니다."
            ))
            addView(label("이 장소 이름"))
            addView(name, rowParams(height = 52))
            addView(label("핫스팟 이름"))
            addView(ssid, rowParams(height = 52))
            addView(label("핫스팟 비밀번호"))
            addView(password, rowParams(height = 52))
            addView(text("비밀번호는 기기에만 전달되고 서버에 저장되지 않습니다.", 14f, SfTheme.INK_SOFT).apply {
                setPadding(dp(4), dp(5), 0, 0)
            })
            addView(label("기기 식별값"))
            addView(bssid, rowParams(height = 52))
            addView(text("이 폰을 구분하는 값입니다. 자동으로 채워집니다.", 14f, SfTheme.INK_SOFT).apply {
                setPadding(dp(4), dp(5), 0, 0)
            })
            addView(enabled, rowParams(height = 52))
            addView(primaryButton("등록하기") {
                val missing = listOf(
                    "이 장소 이름" to name.text.toString(),
                    "핫스팟 이름" to ssid.text.toString(),
                    "핫스팟 비밀번호" to password.text.toString(),
                    "기기 식별값" to bssid.text.toString()
                ).filter { it.second.isBlank() }.map { it.first }
                if (missing.isNotEmpty()) {
                    confirmSheet(
                        headline = "아직 비어 있는 항목이 있어요",
                        message = "${missing.joinToString(", ")}을(를) 채운 뒤 다시 등록해 주세요.",
                        confirmLabel = "확인"
                    ) { }
                    return@primaryButton
                }
                // Persist the assigned/edited BSSID in SFC so the same value is reused next time.
                appPrefs.edit().putString("my_phone_bssid", bssid.text.toString()).apply()
                val draft = SafeZoneDraft(
                    zoneType = "WIFI",
                    name = name.text.toString(),
                    bssid = bssid.text.toString(),
                    ssid = ssid.text.toString(),
                    enabled = enabled.isChecked,
                    apType = "REGISTERED_HOTSPOT" // 내 폰 hotspot: mobile, uses live GPS
                )
                // Remember this hotspot's BSSID so the monitor can gate/verify the hotspot later.
                appPrefs.edit().putString("hotspot_bssid", bssid.text.toString()).apply()
                registerSafeZone(elder, draft, password.text.toString())
            }, rowParams(top = 24))
        }, narrowCardParams(top = 18))
        setContentView(content.root())
    }

    private fun registerSafeZone(elder: ElderInfo, draft: SafeZoneDraft, wifiPassword: String) {
        appendStatus("SAFEZONE CREATE REQUEST ${draft.toJson()}")
        Thread {
            runCatching { apiClient.createSafeZone(elder.elderId, draft) }
                .onSuccess { result ->
                    appendStatus("SAFEZONE CREATE RESPONSE ${result.responseCode}: ${result.responseBody}")
                    runOnUiThread {
                        if (result.responseCode in 200..299 && !result.zoneId.isNullOrBlank()) {
                            appPrefs.edit()
                                .putString("last_safezone_id", result.zoneId)
                                .putString("last_safezone_json", result.responseBody)
                                .apply()
                            sendSafeZoneUpdateToSfd(
                                action = "CREATE",
                                zoneId = result.zoneId,
                                zoneType = draft.zoneType,
                                name = draft.name,
                                bssid = draft.bssid,
                                ssid = draft.ssid,
                                enabled = draft.enabled,
                                password = wifiPassword,
                                centerLat = draft.centerLat,
                                centerLng = draft.centerLng
                            )
                            confirmSheet(
                                headline = "‘${draft.name}’ 안전구역을 등록했습니다",
                                message = "이제 이 장소에 계시면 안전한 것으로 봅니다.",
                                confirmLabel = "확인"
                            ) { showSafeZoneForm() }
                        } else {
                            failureSheet("안전구역을 등록하지 못했어요", result.responseBody)
                        }
                    }
                }
                .onFailure { error ->
                    appendStatus("SAFEZONE CREATE ERROR ${error.message ?: error.javaClass.simpleName}")
                    runOnUiThread {
                        failureSheet("안전구역을 등록하지 못했어요", error.message ?: error.javaClass.simpleName)
                    }
                }
        }.start()
    }

    private fun confirmUpdateSafeZoneEnabled(elder: ElderInfo, zone: SafeZoneInfo) {
        if (zone.zoneId.isBlank()) {
            toast("이 안전구역은 아직 서버에 등록되지 않아 바꿀 수 없습니다.")
            return
        }
        val nextEnabled = !zone.enabled
        // 끄는 쪽은 결과를 분명히 알려야 한다 — 그 장소에 있어도 알림을 못 받게 되기 때문이다.
        // 삭제가 아니므로 빨강이 아니라 앰버 계열로 확인만 받는다.
        confirmSheet(
            headline = if (nextEnabled) "‘${zone.name}’ 안전구역을 켤까요?" else "‘${zone.name}’ 안전구역을 끌까요?",
            message = if (nextEnabled) {
                "이제 이 장소에 계시면 안전한 것으로 봅니다."
            } else {
                "끄면 이 장소에 계셔도 안전구역 밖으로 보고 알림을 보냅니다."
            },
            confirmLabel = if (nextEnabled) "켜기" else "끄기"
        ) { updateSafeZoneEnabled(elder, zone, nextEnabled) }
    }

    private fun updateSafeZoneEnabled(elder: ElderInfo, zone: SafeZoneInfo, enabled: Boolean) {
        appendStatus("SAFEZONE PATCH REQUEST ${zone.zoneId} enabled=$enabled")
        Thread {
            runCatching { apiClient.updateSafeZoneEnabled(zone.zoneId, enabled) }
                .onSuccess { result ->
                    appendStatus("SAFEZONE PATCH BODY ${result.requestJson}")
                    appendStatus("SAFEZONE PATCH RESPONSE ${result.responseCode}: ${result.responseBody}")
                    runOnUiThread {
                        if (result.responseCode in 200..299) {
                            appPrefs.edit()
                                .putString("last_safezone_id", zone.zoneId)
                                .putString("last_safezone_patch_json", result.requestJson)
                                .putString("last_safezone_patch_response", result.responseBody)
                                .apply()
                            selectedSafeZoneIndex = -1
                            sendSafeZoneUpdateToSfd(
                                action = "PATCH",
                                zoneId = zone.zoneId,
                                zoneType = zone.zoneType,
                                name = zone.name,
                                bssid = zone.bssid,
                                ssid = zone.ssid,
                                enabled = enabled
                            )
                            showSafeZoneForm()
                        } else {
                            failureSheet("안전구역 설정을 바꾸지 못했어요", result.responseBody.ifBlank { "HTTP ${result.responseCode}" })
                        }
                    }
                }
                .onFailure { error ->
                    appendStatus("SAFEZONE PATCH ERROR ${error.message ?: error.javaClass.simpleName}")
                    runOnUiThread {
                        failureSheet("안전구역 설정을 바꾸지 못했어요", error.message ?: error.javaClass.simpleName)
                    }
                }
        }.start()
    }

    private fun confirmDeleteSafeZone(elder: ElderInfo, zone: SafeZoneInfo) {
        if (zone.zoneId.isBlank()) {
            toast("이 안전구역은 아직 서버에 등록되지 않아 삭제할 수 없습니다.")
            return
        }
        confirmSheet(
            headline = "‘${zone.name}’ 안전구역을 삭제할까요?",
            message = "삭제하면 되돌릴 수 없고, 이 장소에 계셔도 안전구역 밖으로 보고 알림을 보냅니다.",
            confirmLabel = "삭제",
            destructive = true
        ) { deleteSafeZone(elder, zone) }
    }

    private fun deleteSafeZone(elder: ElderInfo, zone: SafeZoneInfo) {
        appendStatus("SAFEZONE DELETE REQUEST ${zone.zoneId}")
        Thread {
            runCatching { apiClient.deleteSafeZone(zone.zoneId) }
                .onSuccess { result ->
                    appendStatus("SAFEZONE DELETE RESPONSE ${result.responseCode}: ${result.responseBody}")
                    runOnUiThread {
                        if (result.responseCode in 200..299) {
                            appPrefs.edit()
                                .putString("last_safezone_deleted_id", zone.zoneId)
                                .putString("last_safezone_delete_response", result.responseBody)
                                .apply()
                            selectedSafeZoneIndex = -1
                            sendSafeZoneUpdateToSfd(
                                action = "DELETE",
                                zoneId = zone.zoneId,
                                zoneType = zone.zoneType,
                                name = zone.name,
                                bssid = zone.bssid,
                                ssid = zone.ssid,
                                enabled = zone.enabled
                            )
                            showSafeZoneForm()
                        } else {
                            failureSheet("안전구역을 삭제하지 못했어요", result.responseBody.ifBlank { "HTTP ${result.responseCode}" })
                        }
                    }
                }
                .onFailure { error ->
                    appendStatus("SAFEZONE DELETE ERROR ${error.message ?: error.javaClass.simpleName}")
                    runOnUiThread {
                        failureSheet("안전구역을 삭제하지 못했어요", error.message ?: error.javaClass.simpleName)
                    }
                }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun sendSafeZoneUpdateToSfd(
        action: String,
        zoneId: String,
        zoneType: String,
        name: String,
        bssid: String,
        ssid: String,
        enabled: Boolean,
        password: String = "",
        centerLat: Double? = null,
        centerLng: Double? = null
    ) {
        val address = monitorPrefs.getString("last_device_address", "").orEmpty()
        if (address.isBlank()) {
            appendStatus("SFD update skipped: no saved BLE address")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            appendStatus("SFD update skipped: BLUETOOTH_CONNECT permission missing")
            return
        }
        val payload = JSONObject()
            .put("messageType", "safeZoneUpdate")
            .put("action", action)
            .put("deviceId", currentDeviceId())
            .put(
                "zone",
                JSONObject()
                    .put("zoneId", zoneId)
                    .put("zoneType", zoneType)
                    .put("name", name)
                    .put("bssid", bssid)
                    .put("ssid", ssid)
                    .put("enabled", enabled)
                    .apply {
                        if (password.isNotBlank()) put("password", password)
                        if (centerLat != null && centerLng != null) {
                            put("centerLat", centerLat); put("centerLng", centerLng)
                        }
                    }
            )
            .toString()
        monitorPrefs.edit().putString("last_safezone_update_json", payload).apply()
        appendStatus("SFD SAFEZONE UPDATE $payload")

        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            appendStatus("SFD update skipped: invalid BLE address $address")
            return
        }
        bleManager.sendConfig(device, payload)
    }

    private fun showSafeZoneError(error: Throwable) {
        homeVisible = false
        val content = page()
        content.addView(header("안전구역 등록", showBack = true))
        content.addView(card().apply {
            addView(noticeCard(
                "안전구역 정보를 불러오지 못했어요.",
                "인터넷 연결을 확인한 뒤 다시 시도해 주세요.",
                warn = true
            ))
            addView(text(error.message ?: error.javaClass.simpleName, 14f, SfTheme.INK_SOFT).apply {
                setPadding(0, dp(12), 0, dp(12))
            })
            addView(primaryButton("다시 시도") { showSafeZoneForm() }, rowParams(top = 6))
        }, narrowCardParams(top = 24))
        setContentView(content.root())
    }

    private fun showGuardianForm() {
        homeVisible = false
        val content = page()
        content.addView(header("보호자 등록", showBack = true))
        content.addView(card().apply {
            addView(text("보호자 정보를 불러오는 중입니다.", 17f, SfTheme.INK_SOFT).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
        }, narrowCardParams(top = 24))
        setContentView(content.root())

        Thread {
            runCatching {
                val elder = apiClient.getElder(currentDeviceId())
                val guardians = apiClient.getGuardians(elder.elderId)
                elder to guardians
            }.onSuccess { (elder, guardians) ->
                runOnUiThread {
                    selectedGuardianIndex = -1
                    showGuardianList(elder, guardians)
                }
            }.onFailure { error ->
                runOnUiThread { showGuardianError(error) }
            }
        }.start()
    }

    private fun showGuardianList(elder: ElderInfo, guardians: List<GuardianInfo>) {
        homeVisible = false
        val content = page()
        content.addView(header("보호자 등록", showBack = true))
        content.addView(title(currentDeviceId(), 25f).apply { gravity = Gravity.CENTER })
        content.addView(text(elderName(), 16f, SfTheme.INK_SOFT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        })

        if (guardians.isEmpty()) {
            content.addView(emptyState("등록된 보호자가 없습니다.", "긴급 상황 문자를 받을 분을 ‘추가’로 등록해 주세요."))
        } else {
            guardians.forEachIndexed { index, guardian ->
                content.addView(
                    listItem(
                        checked = selectedGuardianIndex == index,
                        glyph = "☏",
                        titleText = guardian.name,
                        subText = guardian.phone,
                        pillText = "알림 받음",
                        pillOn = true
                    ) {
                        selectedGuardianIndex = if (selectedGuardianIndex == index) -1 else index
                        showGuardianList(elder, guardians)
                    }
                )
            }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        buttons.addView(secondaryButton("추가") { showGuardianEditForm(elder, null) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        buttons.addView(secondaryButton("수정") {
            if (selectedGuardianIndex < 0) {
                toast("수정할 보호자를 선택해 주세요.")
            } else {
                showGuardianEditForm(elder, guardians[selectedGuardianIndex])
            }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        buttons.addView(secondaryButton("삭제") {
            if (selectedGuardianIndex < 0) {
                toast("삭제할 보호자를 선택해 주세요.")
            } else {
                confirmDeleteGuardian(elder, guardians[selectedGuardianIndex])
            }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        content.addView(buttons)
        setContentView(content.root())
    }

    private fun showGuardianEditForm(elder: ElderInfo, guardian: GuardianInfo?) {
        homeVisible = false
        val isEdit = guardian != null
        val content = page()
        content.addView(header(if (isEdit) "보호자 수정" else "보호자 추가", showBack = true))
        content.addView(title(currentDeviceId(), 25f).apply { gravity = Gravity.CENTER })
        content.addView(text(elderName(), 16f, SfTheme.INK_SOFT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        })

        val name = input("보호자 성함", guardian?.name ?: appPrefs.getString("guardian_name", "방효식").orEmpty())
        val phone = input("전화번호", guardian?.phone ?: appPrefs.getString("guardian_phone", "010-7260-8813").orEmpty(), InputType.TYPE_CLASS_PHONE)
        content.addView(card().apply {
            addView(label("보호자 성함"))
            addView(name, rowParams(height = 52))
            addView(label("전화번호"))
            addView(phone, rowParams(height = 52))
            addView(primaryButton(if (isEdit) "수정" else "등록") {
                val missing = listOf(
                    "보호자 성함" to name.text.toString(),
                    "전화번호" to phone.text.toString()
                ).filter { it.second.isBlank() }.map { it.first }
                if (missing.isNotEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("항목 확인")
                        .setMessage("${missing.joinToString(", ")} 항목을 채워 주세요.")
                        .setPositiveButton("확인", null)
                        .show()
                    return@primaryButton
                }
                val draft = GuardianDraft(name.text.toString(), phone.text.toString())
                if (guardian == null) createGuardian(elder, draft) else updateGuardian(elder, guardian, draft)
            }, rowParams(top = 26))
        }, narrowCardParams(top = 18))
        setContentView(content.root())
    }

    private fun createGuardian(elder: ElderInfo, draft: GuardianDraft) {
        appendStatus("GUARDIAN CREATE REQUEST ${draft.toJson()}")
        Thread {
            runCatching { apiClient.createGuardian(elder.elderId, draft) }
                .onSuccess { result ->
                    appendStatus("GUARDIAN CREATE RESPONSE ${result.responseCode}: ${result.responseBody}")
                    runOnUiThread {
                        if (result.responseCode in 200..299) {
                            appPrefs.edit()
                                .putString("guardian_name", draft.name)
                                .putString("guardian_phone", draft.phone)
                                .putString("last_guardian_create_response", result.responseBody)
                                .apply()
                            selectedGuardianIndex = -1
                            showGuardianForm()
                        } else {
                            showGuardianMutationError("보호자 등록 실패", result)
                        }
                    }
                }
                .onFailure { error ->
                    appendStatus("GUARDIAN CREATE ERROR ${error.message ?: error.javaClass.simpleName}")
                    runOnUiThread { showGuardianError(error) }
                }
        }.start()
    }

    private fun updateGuardian(elder: ElderInfo, guardian: GuardianInfo, draft: GuardianDraft) {
        appendStatus("GUARDIAN PUT REQUEST id=${guardian.id} ${draft.toJson()}")
        Thread {
            runCatching { apiClient.updateGuardian(guardian.id, draft) }
                .onSuccess { result ->
                    appendStatus("GUARDIAN PUT RESPONSE ${result.responseCode}: ${result.responseBody}")
                    runOnUiThread {
                        if (result.responseCode in 200..299) {
                            appPrefs.edit()
                                .putString("guardian_name", draft.name)
                                .putString("guardian_phone", draft.phone)
                                .putString("last_guardian_put_response", result.responseBody)
                                .apply()
                            selectedGuardianIndex = -1
                            showGuardianForm()
                        } else {
                            showGuardianMutationError("보호자 수정 실패", result)
                        }
                    }
                }
                .onFailure { error ->
                    appendStatus("GUARDIAN PUT ERROR ${error.message ?: error.javaClass.simpleName}")
                    runOnUiThread { showGuardianError(error) }
                }
        }.start()
    }

    private fun confirmDeleteGuardian(elder: ElderInfo, guardian: GuardianInfo) {
        confirmSheet(
            headline = "‘${guardian.name}’ 보호자를 삭제할까요?",
            message = "삭제하면 되돌릴 수 없고, 긴급 상황이 생겨도 이분께는 문자가 가지 않습니다.",
            confirmLabel = "삭제",
            destructive = true
        ) { deleteGuardian(elder, guardian) }
    }

    private fun deleteGuardian(elder: ElderInfo, guardian: GuardianInfo) {
        appendStatus("GUARDIAN DELETE REQUEST id=${guardian.id}")
        Thread {
            runCatching { apiClient.deleteGuardian(guardian.id) }
                .onSuccess { result ->
                    appendStatus("GUARDIAN DELETE RESPONSE ${result.responseCode}: ${result.responseBody}")
                    runOnUiThread {
                        if (result.responseCode in 200..299) {
                            appPrefs.edit()
                                .putString("last_guardian_delete_response", result.responseBody)
                                .apply()
                            selectedGuardianIndex = -1
                            showGuardianForm()
                        } else {
                            showGuardianMutationError("보호자 삭제 실패", result)
                        }
                    }
                }
                .onFailure { error ->
                    appendStatus("GUARDIAN DELETE ERROR ${error.message ?: error.javaClass.simpleName}")
                    runOnUiThread { showGuardianError(error) }
                }
        }.start()
    }

    private fun showGuardianError(error: Throwable) {
        homeVisible = false
        val content = page()
        content.addView(header("보호자 등록", showBack = true))
        content.addView(card().apply {
            addView(noticeCard(
                "보호자 정보를 불러오지 못했어요.",
                "인터넷 연결을 확인한 뒤 다시 시도해 주세요.",
                warn = true
            ))
            addView(text(error.message ?: error.javaClass.simpleName, 14f, SfTheme.INK_SOFT).apply {
                setPadding(0, dp(12), 0, dp(12))
            })
            addView(primaryButton("다시 시도") { showGuardianForm() }, rowParams(top = 6, height = 56))
        }, narrowCardParams(top = 24))
        setContentView(content.root())
    }

    private fun showGuardianMutationError(title: String, result: GuardianMutationResult) {
        failureSheet(title, result.responseBody.ifBlank { "HTTP ${result.responseCode}" })
    }

    private fun showLogs() {
        showLogs(selectedLogMonth)
    }

    private fun showLogs(month: YearMonth) {
        selectedLogMonth = month
        showLogsLoading(month)
        Thread {
            runCatching { apiClient.getDeviceLogCalendar(currentDeviceId(), month.toString()) }
                .onSuccess { calendar ->
                    appendStatus("LOG CALENDAR RESPONSE ${calendar.rawJson}")
                    runOnUiThread { showLogs(calendar, null) }
                }
                .onFailure { error ->
                    appendStatus("LOG CALENDAR ERROR ${error.message ?: error.javaClass.simpleName}")
                    runOnUiThread { showLogs(null, error) }
                }
        }.start()
    }

    private fun showLogsLoading(month: YearMonth) {
        homeVisible = false
        val content = page()
        content.addView(header("기록 보기", showBack = true))
        content.addView(calendarCard(month, emptyMap()))
        content.addView(label("로그 요약").apply { setPadding(dp(10), dp(24), 0, dp(8)) })
        content.addView(card().apply {
            addView(text("${month} 로그를 불러오는 중입니다.", 17f, SfTheme.INK_SOFT).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, dp(18))
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(content.root())
    }

    private fun showLogs(calendar: DeviceLogCalendar?, error: Throwable?) {
        homeVisible = false
        val month = calendar?.month?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: selectedLogMonth
        val days = calendar?.days?.associateBy { it.date } ?: emptyMap()
        val content = page()
        content.addView(header("기록 보기", showBack = true))
        content.addView(calendarCard(month, days))
        content.addView(text("날짜를 누르면 그날의 이동 경로를 지도에서 볼 수 있어요.", 14f, SfTheme.INK_SOFT).apply {
            setPadding(dp(10), dp(8), dp(10), 0)
        })
        content.addView(label("이번 달 요약").apply { setPadding(dp(10), dp(24), 0, dp(8)) })
        content.addView(text("전체 ${calendar?.total ?: 0}건", 18f, SfTheme.INK, true).apply {
            setPadding(dp(10), 0, 0, dp(10))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(summaryTile("${calendar?.normal ?: 0}건", "정상", SfTheme.PRIMARY), LinearLayout.LayoutParams(0, dp(136), 1f))
        row.addView(summaryTile("${calendar?.warning ?: 0}건", "주의", SfTheme.AMBER), LinearLayout.LayoutParams(0, dp(136), 1f).apply { marginStart = dp(8) })
        row.addView(summaryTile("${calendar?.emergency ?: 0}건", "긴급", SfTheme.DANGER), LinearLayout.LayoutParams(0, dp(136), 1f).apply { marginStart = dp(8) })
        content.addView(row)
        content.addView(card().apply {
            val serverLines = when {
                calendar != null -> listOf(
                    "Device ID: ${calendar.deviceId}",
                    "Month: ${calendar.month}",
                    "Total: ${calendar.total}건",
                    "PERIODIC → 정상: ${calendar.normal}건",
                    "GEOFENCE_EXIT_HINT → 주의: ${calendar.warning}건",
                    "SOS → 긴급: ${calendar.emergency}건"
                )
                error != null -> listOf(
                    "서버 월별 로그를 불러오지 못했습니다.",
                    error.message ?: error.javaClass.simpleName
                )
                else -> emptyList()
            }
            val localLines = if (logLines.isEmpty()) listOf("아직 표시할 내부 로그가 없습니다.") else logLines.toList()
            // 보호자에게 원문 로그는 첫 화면에 필요 없다. 기본은 접어 두고 눌렀을 때만 펼친다.
            val raw = text((serverLines + "" + localLines).joinToString("\n"), 14f, SfTheme.INK_SOFT).apply {
                visibility = View.GONE
                setPadding(0, dp(10), 0, 0)
            }
            addView(text("자세히 보기", 16f, SfTheme.PRIMARY_DARK, true).apply {
                minimumHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    raw.visibility = if (raw.visibility == View.GONE) View.VISIBLE else View.GONE
                }
            })
            addView(raw)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        setContentView(content.root())
    }

    /** "마지막 동기화: yyyy.MM.dd HH:mm:ss" — when SFC last fetched the latest message from the server. */
    private fun lastSyncLabel(): String {
        if (lastSyncedAtMillis == 0L) return "마지막 동기화: 확인 중"
        val t = Instant.ofEpochMilli(lastSyncedAtMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"))
        return "마지막 동기화: $t"
    }

    private fun homeTimestampLabel(log: DeviceLogEntry?, isLoading: Boolean): String {
        if (log?.eventTimestamp?.isNotBlank() == true) return runCatching {
            val localTime = Instant.parse(log.eventTimestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
            localTime.format(DateTimeFormatter.ofPattern("yyyy.MM.dd E HH:mm"))
        }.getOrDefault(log.eventTimestamp)
        return if (isLoading) {
            "서버 상태 확인 중..."
        } else {
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd E HH:mm"))
        }
    }

    /** Friendly name of the registered safe zone whose SSID/name matches the current AP. */
    private fun safeZoneNameForAp(apName: String?): String? {
        if (apName.isNullOrBlank()) return null
        val json = monitorPrefs.getString("last_config_json", null) ?: return null
        fun norm(s: String) = s.trim().trim('"').removeSuffix("_5G").removeSuffix("_2G")
            .removeSuffix("-5G").removeSuffix("-2G")
        return runCatching {
            val zones = org.json.JSONObject(json).optJSONArray("safeZones") ?: return null
            for (i in 0 until zones.length()) {
                val z = zones.optJSONObject(i) ?: continue
                val ssid = z.optString("ssid")
                val name = z.optString("name")
                if (ssid.equals(apName, true) || name.equals(apName, true) ||
                    norm(ssid).equals(norm(apName), ignoreCase = true)
                ) return name.takeIf { it.isNotBlank() }
            }
            null
        }.getOrNull()
    }

    /** Registered BLE / hotspot ("내폰") zone name from the stored config, as shown in the list. */
    private fun bleZoneName(): String? {
        val json = monitorPrefs.getString("last_config_json", null) ?: return null
        return runCatching {
            val zones = org.json.JSONObject(json).optJSONArray("safeZones") ?: return null
            for (i in 0 until zones.length()) {
                val z = zones.optJSONObject(i) ?: continue
                val type = z.optString("zoneType")
                val apType = z.optString("apType")
                if (type.equals("BLE", ignoreCase = true) || type.equals("HOTSPOT", ignoreCase = true) ||
                    apType.equals("REGISTERED_HOTSPOT", ignoreCase = true)
                ) {
                    val name = z.optString("name").trim().trim('"')
                    if (name.isNotBlank()) return name
                }
            }
            null
        }.getOrNull()
    }

    /** First registered WIFI safe zone's name (falls back to its SSID) from the stored config. */
    private fun firstConfiguredWifiZoneName(): String? {
        val json = monitorPrefs.getString("last_config_json", null) ?: return null
        return runCatching {
            val zones = org.json.JSONObject(json).optJSONArray("safeZones") ?: return null
            for (i in 0 until zones.length()) {
                val z = zones.optJSONObject(i) ?: continue
                if (!z.optString("zoneType").equals("WIFI", ignoreCase = true)) continue
                val name = z.optString("name").trim().trim('"')
                if (name.isNotBlank()) return name
                val ssid = z.optString("ssid").trim().trim('"')
                if (ssid.isNotBlank()) return ssid
            }
            null
        }.getOrNull()
    }

    private fun homeZoneLabel(log: DeviceLogEntry?): String {
        // Always prefer the registered WiFi Zone's own name; only fall back to the stored default.
        val zoneName = { firstConfiguredWifiZoneName() ?: appPrefs.getString("wifi_name", null)?.takeIf { it.isNotBlank() && it != "집" } }
        if (log == null) return zoneName() ?: appPrefs.getString("wifi_name", "집").orEmpty().ifBlank { "집" }
        if (log.eventType == "SOS") return "SOS"
        if (log.inSafeZone == false) return when (log.locationType) {
            "GPS" -> "GPS 이동"
            "BLE" -> "BLE 안전구역"
            else -> "안전구역 밖"
        }
        return when (log.locationType) {
            // Show the registered zone name AND the actual connected AP SSID so it is unambiguous,
            // e.g. "집 (nobug_home_5G)" or "내 폰 (Nobug)". The SSID is the AP the device reports.
            "WIFI" -> {
                val ssid = log.apName?.takeIf { it.isNotBlank() }
                val zone = safeZoneNameForAp(log.apName) ?: firstConfiguredWifiZoneName()
                when {
                    zone != null && ssid != null && !zone.equals(ssid, ignoreCase = true) -> "$zone ($ssid)"
                    zone != null -> zone
                    ssid != null -> ssid
                    else -> appPrefs.getString("wifi_name", "집").orEmpty().ifBlank { "집" }
                }
            }
            // Hotspot/BLE zone → its registered list name (e.g. "내폰").
            "BLE" -> bleZoneName() ?: "BLE 안전구역"
            "GPS" -> "GPS"
            else -> zoneName() ?: "집"
        }
    }

    private fun homeStatusLabel(log: DeviceLogEntry?, isLoading: Boolean, error: Throwable?): String {
        // 못 불러왔을 때는 "언제 것인지"를 반드시 함께 보여 준다. 그래야 화면의 상태가 지금 것인지
        // 아까 것인지 보호자가 판단할 수 있다.
        if (error != null) {
            val last = if (lastSyncedAtMillis == 0L) "없음" else formatSinceTime(lastSyncedAtMillis)
            return "최신 상태를 못 받았어요 · 마지막 확인 $last"
        }
        if (isLoading && log == null) return "오늘 기록을 불러오는 중"
        if (log == null) return "오늘 받은 기록이 없어요"
        if (log.eventType == "SOS") return "긴급 (SOS)"
        val safeText = if (log.inSafeZone == true) "안전구역" else "이탈"
        val stateText = when (log.eventType) {
            "SOS" -> "긴급"
            "GEOFENCE_EXIT_HINT" -> "주의"
            else -> if (log.inSafeZone == false) "주의" else statusKorean(log.deviceStatus)
        }
        return "$safeText · $stateText"
    }

    /** 서버가 보내는 동작 표현(stayed/entered/…)을 그대로 보여주지 않는다. */
    private fun verbKorean(verb: String): String = when (verb.lowercase()) {
        "stayed" -> "머무는 중"
        "entered" -> "들어옴"
        "exited", "left" -> "나감"
        "moved", "moving" -> "이동 중"
        else -> statusKorean(verb)
    }

    /** dBm 대신 읽을 수 있는 말로. 임계값은 BLE 쪽 signalLabel 과 같은 기준이다. */
    private fun wifiSignalLabel(rssi: Int): String = when {
        rssi >= -60 -> "좋음"
        rssi >= -75 -> "보통"
        else -> "약함"
    }

    private fun statusKorean(status: String): String = when (status.uppercase()) {
        "", "NORMAL" -> "정상"
        "WARNING" -> "주의"
        "EMERGENCY" -> "긴급"
        "GPS_WEAK" -> "GPS 신호 약함"
        else -> status
    }

    private fun homeIconRes(log: DeviceLogEntry?): Int = when {
        log?.eventType == "SOS" -> R.drawable.ic_sos_status
        log?.inSafeZone == false -> R.drawable.ic_gps_moving
        log?.locationType == "GPS" -> R.drawable.ic_gps_moving
        else -> R.drawable.ic_home_zone
    }

    /**
     * 홈 화면이 어떤 색으로 물들지 정한다.
     *
     * 서버를 못 읽었을 때는 일부러 UNKNOWN(회색)이다. 통신 실패를 빨강으로 칠하면 보호자가 SOS로
     * 오인할 수 있는데, 그건 이 앱에서 가장 위험한 오해다.
     */
    private fun homeAccentKind(log: DeviceLogEntry?, error: Throwable?): HomeAccentKind = when {
        error != null || log == null -> HomeAccentKind.UNKNOWN
        log.eventType == "SOS" || log.deviceStatus == "EMERGENCY" -> HomeAccentKind.SOS
        log.eventType == "GEOFENCE_EXIT_HINT" || log.deviceStatus == "WARNING" -> HomeAccentKind.WARNING
        log.inSafeZone == false -> HomeAccentKind.WARNING
        log.inSafeZone == true -> HomeAccentKind.NORMAL
        else -> HomeAccentKind.UNKNOWN
    }

    private fun homeAccentColor(kind: HomeAccentKind): Int = when (kind) {
        HomeAccentKind.NORMAL -> SfTheme.PRIMARY
        HomeAccentKind.WARNING -> SfTheme.AMBER
        HomeAccentKind.SOS -> SfTheme.DANGER
        HomeAccentKind.UNKNOWN -> SfTheme.INK_FAINT
    }

    private fun homeAccentSoftColor(kind: HomeAccentKind): Int = when (kind) {
        HomeAccentKind.NORMAL -> SfTheme.PRIMARY_SOFT
        HomeAccentKind.WARNING -> SfTheme.AMBER_SOFT
        HomeAccentKind.SOS -> SfTheme.DANGER_SOFT
        HomeAccentKind.UNKNOWN -> SfTheme.SURFACE_ALT
    }

    /**
     * 글자에 쓰는 상태색. 채도가 높은 AMBER/GOLD 는 흰 배경에서 대비가 3:1 도 안 나오므로 글자에는
     * 쓰지 않고, 같은 계열의 진한 색을 쓴다. 색은 배경·테두리 쪽에서만 밝게 쓴다.
     */
    private fun homeAccentTextColor(kind: HomeAccentKind): Int = when (kind) {
        HomeAccentKind.NORMAL -> SfTheme.PRIMARY_DARK
        HomeAccentKind.WARNING -> SfTheme.AMBER_INK
        HomeAccentKind.SOS -> SfTheme.DANGER
        HomeAccentKind.UNKNOWN -> SfTheme.INK_SOFT
    }

    private fun homeGlowColor(kind: HomeAccentKind): Int? = when (kind) {
        HomeAccentKind.NORMAL -> SfTheme.GLOW_CALM
        HomeAccentKind.WARNING -> SfTheme.GLOW_WARNING
        HomeAccentKind.SOS -> SfTheme.GLOW_SOS
        HomeAccentKind.UNKNOWN -> null
    }

    private fun homeDetailLabel(log: DeviceLogEntry?): String {
        if (log == null) return "Device ID: ${currentDeviceId()}"
        val location = when (log.locationType) {
            "WIFI" -> "WiFi ${log.apName ?: "-"}"
            "GPS" -> if (log.latitude != null && log.longitude != null) {
                "GPS %.4f, %.4f".format(log.latitude, log.longitude)
            } else {
                "GPS 이동 중"
            }
            "BLE" -> "Bluetooth"
            else -> statusKorean(log.deviceStatus)
        }
        val battery = log.battery?.let { "배터리 ${it}%" } ?: "배터리 확인 중"
        val signal = log.signal?.let { "신호 ${wifiSignalLabel(it)}" } ?: "신호 확인 중"
        return "$location · ${verbKorean(log.verb.ifBlank { log.eventType })} · $battery · $signal"
    }

    private fun homeDurationLabel(log: DeviceLogEntry?): String {
        if (log == null) return "상태 지속 시간: 확인 중"
        val state = homeStateKind(log)
        val startMillis = stateStartMillis(state) ?: parseLogMillis(log.eventTimestamp)
        val startLabel = startMillis?.let { formatSinceTime(it) } ?: "확인 중"
        val durationLabel = startMillis?.let { formatElapsed(System.currentTimeMillis() - it) } ?: "확인 중"
        return when (state) {
            HomeStateKind.SAFE_WIFI -> "WiFi 안전구역 체류: $startLabel 부터 · $durationLabel"
            HomeStateKind.WARNING -> "주의 지속: $startLabel 부터 · $durationLabel"
            HomeStateKind.SOS -> "긴급 지속: $startLabel 부터 · $durationLabel"
            HomeStateKind.SAFE_OTHER -> "안전구역 체류: $startLabel 부터 · $durationLabel"
            HomeStateKind.OTHER -> "현재 상태 시작: $startLabel 부터 · $durationLabel"
        }
    }

    private fun homeStateKind(log: DeviceLogEntry): HomeStateKind = when {
        log.eventType == "SOS" || log.deviceStatus == "EMERGENCY" -> HomeStateKind.SOS
        log.eventType == "GEOFENCE_EXIT_HINT" || log.deviceStatus == "WARNING" || log.inSafeZone == false -> HomeStateKind.WARNING
        log.inSafeZone == true && log.locationType == "WIFI" -> HomeStateKind.SAFE_WIFI
        log.inSafeZone == true -> HomeStateKind.SAFE_OTHER
        else -> HomeStateKind.OTHER
    }

    private fun stateStartMillis(state: HomeStateKind): Long? {
        val sorted = latestHomeLogs
            .mapNotNull { entry -> parseLogMillis(entry.eventTimestamp)?.let { it to entry } }
            .sortedByDescending { it.first }
        if (sorted.isEmpty()) return null
        var start = sorted.first().first
        for ((millis, entry) in sorted) {
            if (homeStateKind(entry) != state) break
            start = millis
        }
        return start
    }

    private fun parseLogMillis(timestamp: String): Long? = runCatching {
        Instant.parse(timestamp).toEpochMilli()
    }.getOrNull()

    private fun formatSinceTime(millis: Long): String = Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun formatElapsed(durationMs: Long): String {
        val totalMinutes = (durationMs.coerceAtLeast(0L) / 60000L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0) "${hours}시간 ${minutes}분" else "${minutes}분"
    }

    /**
     * In-app map view (OpenStreetMap via Leaflet in a WebView). Fetches the elder's location
     * history (GET /elders/{elderId}/history) and draws the traveled route as a polyline with a
     * colored dot per point: green=safe zone, orange=away/moving, yellow=warning, red=SOS.
     */
    private fun showMap(date: LocalDate? = null) {
        homeVisible = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(8))
            setBackgroundColor(SfTheme.BG)
        }
        val headerTitle = if (date != null)
            "이동 경로 (${date.format(DateTimeFormatter.ofPattern("MM.dd"))})" else "이동 경로"
        root.addView(header(headerTitle, showBack = true))
        root.addView(mapLegendRow())
        val status = text("경로를 불러오는 중입니다...", 15f, SfTheme.INK_SOFT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        }
        root.addView(status)
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(SfTheme.SURFACE_ALT)
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(8)
        })
        setContentView(root)

        Thread {
            runCatching {
                val elderId = currentElderIdBlocking()
                if (elderId.isBlank()) error("elderId를 찾을 수 없습니다")
                // A specific date → that day's route for this device; otherwise the recent history.
                val logs = if (date != null)
                    apiClient.getDeviceLogsByDate(currentDeviceId(), date.toString(), 0, 300).logs
                else
                    apiClient.getElderHistory(elderId, 0, 50).logs
                // Home markers come from the registered safe-zone centers (captured once at
                // registration), NOT from telemetry — a fixed-AP device sends no GPS while at home.
                val homes = runCatching { apiClient.getSafeZones(elderId) }.getOrDefault(emptyList())
                    .filter { it.centerLat != null && it.centerLng != null }
                Pair(logs, homes)
            }.onSuccess { (logs, homes) ->
                // Show every reading the server marks as having a location (hasLocation == true) —
                // the watch, tablet, and Arduino all send coordinates even on Wi-Fi, so filtering by
                // locationType == "GPS" wrongly dropped them. Fall back to coordinate presence for
                // older records that predate the hasLocation field. Coordinates are still required
                // to actually plot a dot. Ordered oldest → newest for the route.
                val pts = logs.filter {
                    (it.hasLocation ?: (it.latitude != null && it.longitude != null)) &&
                        it.latitude != null && it.longitude != null
                }.sortedBy { it.eventTimestamp }
                runOnUiThread {
                    if (pts.isEmpty() && homes.isEmpty()) {
                        status.text = "표시할 위치 기록이 없습니다."
                        return@runOnUiThread
                    }
                    status.visibility = View.GONE
                    web.loadDataWithBaseURL(
                        "https://sf-api.ese-lab.com/", buildMapHtml(pts, homes), "text/html", "utf-8", null
                    )
                }
            }.onFailure { e ->
                runOnUiThread { status.text = "경로 불러오기 실패: ${e.message ?: e.javaClass.simpleName}" }
            }
        }.start()
    }

    /** elderId: from the cached config (each zone carries it), else looked up from the server. */
    private fun currentElderIdBlocking(): String {
        val cached = monitorPrefs.getString("last_config_json", null)?.let { json ->
            runCatching {
                org.json.JSONObject(json).optJSONArray("safeZones")?.optJSONObject(0)?.optString("elderId")
            }.getOrNull()
        }
        if (!cached.isNullOrBlank()) return cached
        return runCatching { apiClient.getElder(currentDeviceId()).elderId }.getOrDefault("")
    }

    private fun mapLegendRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(4), 0, dp(4))
        addView(legendItem("안전구역", SfTheme.PRIMARY))
        addView(legendItem("이동중", SfTheme.GOLD))
        addView(legendItem("경고", SfTheme.AMBER))
        addView(legendItem("SOS", SfTheme.DANGER))
    }

    private fun legendItem(label: String, color: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(6), 0)
        addView(View(this@MainActivity).apply { background = oval(color) }, LinearLayout.LayoutParams(dp(12), dp(12)))
        addView(text(label, 14f, SfTheme.INK_SOFT).apply { setPadding(dp(6), 0, 0, 0) })
    }

    private fun mapPointColor(e: DeviceLogEntry): String = when {
        e.eventType == "SOS" || e.deviceStatus == "EMERGENCY" -> SfTheme.MAP_SOS
        e.eventType == "GEOFENCE_EXIT_HINT" || e.deviceStatus == "WARNING" -> SfTheme.MAP_WARNING
        e.inSafeZone == false -> SfTheme.MAP_MOVING
        e.inSafeZone == true -> SfTheme.MAP_SAFE
        else -> SfTheme.MAP_UNKNOWN
    }

    /**
     * 점 테두리 색. 새 팔레트에서는 "경고"와 "이동중"이 둘 다 따뜻한 색이라 지도 위에서 비슷해 보인다.
     * 경고에만 진한 테두리를 둘러 색이 아니라 형태로도 구분되게 한다.
     */
    private fun mapPointRing(e: DeviceLogEntry): String = when {
        e.eventType == "GEOFENCE_EXIT_HINT" || e.deviceStatus == "WARNING" -> "#8A5C25"
        else -> "#FFFFFF"
    }

    private fun mapStatusLabel(e: DeviceLogEntry): String = when {
        e.eventType == "SOS" || e.deviceStatus == "EMERGENCY" -> "SOS"
        e.eventType == "GEOFENCE_EXIT_HINT" || e.deviceStatus == "WARNING" -> "경고"
        e.inSafeZone == false -> "이동중"
        e.inSafeZone == true -> "안전구역"
        else -> "-"
    }

    /** Build a self-contained Leaflet/OSM page: the route polyline + colored dots, plus a Home
     *  marker per registered safe zone (drawn from its center, captured once at registration). */
    private fun buildMapHtml(points: List<DeviceLogEntry>, homes: List<SafeZoneInfo> = emptyList()): String {
        val fmt = DateTimeFormatter.ofPattern("MM.dd HH:mm:ss")
        val arr = org.json.JSONArray()
        for (p in points) {
            val t = runCatching {
                Instant.parse(p.eventTimestamp).atZone(ZoneId.systemDefault()).toLocalDateTime().format(fmt)
            }.getOrDefault(p.eventTimestamp)
            arr.put(
                org.json.JSONObject()
                    .put("lat", p.latitude)
                    .put("lng", p.longitude)
                    .put("color", mapPointColor(p))
                    .put("ring", mapPointRing(p))
                    .put("t", t)
                    .put("s", mapStatusLabel(p))
                    .put("ap", p.apName ?: "")
            )
        }
        val data = arr.toString()
        val homeArr = org.json.JSONArray()
        for (h in homes) {
            val lat = h.centerLat ?: continue
            val lng = h.centerLng ?: continue
            homeArr.put(org.json.JSONObject().put("lat", lat).put("lng", lng).put("name", h.name.ifBlank { "집" }))
        }
        val homeData = homeArr.toString()
        return """
<!DOCTYPE html><html><head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"/>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#map{height:100%;margin:0;padding:0}.leaflet-container{background:#F2ECE2}
.home-ico{font-size:22px;line-height:22px;text-align:center}</style>
</head><body>
<div id="map"></div>
<script>
var pts = $data;
var homes = $homeData;
var map = L.map('map');
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom:19, attribution:'© OpenStreetMap'}).addTo(map);
var bounds = [];
var latlngs = pts.map(function(p){return [p.lat, p.lng];});
if (latlngs.length > 1) { L.polyline(latlngs, {color:'${SfTheme.MAP_SAFE}', weight:4, opacity:0.55}).addTo(map); }
pts.forEach(function(p, i){
  var last = (i === pts.length - 1);
  L.circleMarker([p.lat, p.lng], {radius: last?9:6, color:p.ring, weight:2, fillColor:p.color, fillOpacity:1})
    .addTo(map)
    .bindPopup('<b>'+p.s+'</b><br>'+p.t+(p.ap?('<br>'+p.ap):''));
  bounds.push([p.lat, p.lng]);
});
homes.forEach(function(h){
  var icon = L.divIcon({className:'', html:'<div class="home-ico">🏠</div>', iconSize:[22,22], iconAnchor:[11,11]});
  L.marker([h.lat, h.lng], {icon:icon}).addTo(map).bindPopup('<b>'+h.name+'</b> (집)');
  bounds.push([h.lat, h.lng]);
});
if (bounds.length > 0) { map.fitBounds(L.latLngBounds(bounds).pad(0.25)); } else { map.setView([37.5,127],13); }
</script>
</body></html>
        """.trimIndent()
    }

    private fun openMap(latitude: Double, longitude: Double, label: String = "Safe Finder") {
        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude($label)")
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"))
        }.onFailure {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    @SuppressLint("MissingPermission")
    private fun currentLocation(): Location? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        return manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * 화면 상단. 앰비언트 글로우를 뒤에 깔고 그 위에 로고·제목·메뉴를 올린다. 글로우는 page() 의
     * 좌우 여백 바깥까지 번져야 자연스러우므로 음수 마진으로 화면 끝까지 늘린다.
     * [glow] 가 null 이면 빛 없이 배경만 남는다(상태를 단정할 수 없을 때).
     */
    private fun header(
        titleText: String = "Safe Finder",
        showBack: Boolean = false,
        glow: Int? = SfTheme.GLOW_CALM
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(12))
        }
        if (showBack) {
            row.addView(ImageButton(this).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(SfTheme.INK)
                contentDescription = "뒤로 가기"
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { if (isRegistered()) showHome() else showPairing() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
        } else {
            row.addView(appLogo(dp(34)), LinearLayout.LayoutParams(dp(38), dp(38)))
        }
        row.addView(title(titleText, 24f).apply {
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!showBack) {
            row.addView(text(currentDeviceId(), 13f, SfTheme.INK_SOFT, true).apply {
                gravity = Gravity.CENTER
                background = rounded(0x9AFFFFFF.toInt(), 999)
                setPadding(dp(10), dp(5), dp(10), dp(5))
            })
        }
        row.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_menu_more)
            setColorFilter(SfTheme.INK_SOFT)
            contentDescription = "메뉴 열기"
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showMenu(this) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))

        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = -dp(22)
                rightMargin = -dp(22)
                topMargin = -dp(26)
            }
            if (glow != null) {
                addView(
                    AmbientGlowView(this@MainActivity, glow),
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120))
                )
            }
            addView(row, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    /** 메뉴. 터치 타겟이 작은 PopupMenu 대신 바텀시트를 쓴다(각 줄 56dp 이상). */
    private fun showMenu(anchor: View) {
        bottomSheet { dialog ->
            addView(text("메뉴", 21f, SfTheme.INK, true).apply { setPadding(0, 0, 0, dp(6)) })
            val items = listOf<Pair<String, () -> Unit>>(
                "안전구역 관리" to { showSafeZoneForm() },
                "보호자 관리" to { showGuardianForm() },
                "기기 정보" to { showDeviceInfo() },
                "기기 등록 초기화" to { confirmResetDevice() }
            )
            items.forEach { (menuLabel, action) ->
                addView(text(menuLabel, 18f, SfTheme.INK, true).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(56)
                    setPadding(dp(6), dp(16), dp(6), dp(16))
                    setOnClickListener { dialog.dismiss(); action() }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            addView(secondaryButton("닫기") { dialog.dismiss() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                    topMargin = dp(10)
                })
        }
    }

    private fun confirmResetDevice() {
        confirmSheet(
            headline = "기기 등록을 초기화할까요?",
            message = "등록된 기기·안전구역·보호자 정보가 이 폰에서 모두 지워지고 처음 화면으로 돌아갑니다. 되돌릴 수 없습니다.",
            confirmLabel = "초기화",
            destructive = true
        ) { resetDeviceRegistration() }
    }

    private fun resetDeviceRegistration() {
        stopHomeRefresh()
        scanDialog?.dismiss()
        bleManager.release()
        selectedDevice = null
        selectedDeviceName = ""
        selectedSafeZoneIndex = -1
        selectedGuardianIndex = -1
        latestHomeLog = null
        latestHomeLogs = emptyList()
        appPrefs.edit().clear().apply()
        monitorPrefs.edit().clear().apply()
        toast("디바이스 등록 정보가 초기화되었습니다.")
        showPairing()
    }

    private fun showDeviceInfo() {
        homeVisible = false
        val appVersion = appVersionName()
        val fwVersion = latestHomeLog?.fwVersion?.takeIf { it.isNotBlank() } ?: "확인 중"
        bottomSheet { dialog ->
            addView(text("기기 정보", 21f, SfTheme.INK, true).apply { setPadding(0, 0, 0, dp(6)) })
            addView(infoRow("기기 번호", currentDeviceId()))
            addView(infoRow("앱 버전", appVersion))
            addView(infoRow("기기 소프트웨어 버전", fwVersion))
            addView(primaryButton("확인") { dialog.dismiss() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
                    topMargin = dp(14)
                })
        }
    }

    /** 시트 안에서 "라벨 / 값" 한 줄. */
    private fun infoRow(labelText: String, value: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(text(labelText, 14f, SfTheme.INK_SOFT))
        addView(text(value, 17f, SfTheme.INK, true).apply { setPadding(0, dp(3), 0, 0) })
    }

    private fun appVersionName(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        }.getOrDefault("Unknown")
    }

    private fun calendarCard(month: YearMonth, days: Map<String, DailyLogSummary>): LinearLayout = card().apply {
        val monthRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 이전/다음 달 버튼은 같은 성격의 동작이므로 같은 모양이어야 한다(이전에는 색이 서로 달랐다).
        monthRow.addView(monthStepButton("‹", "이전 달") { showLogs(month.minusMonths(1)) })
        monthRow.addView(title("${month.year}년 ${month.monthValue}월", 24f).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        monthRow.addView(monthStepButton("›", "다음 달") { showLogs(month.plusMonths(1)) })
        addView(monthRow)

        val weekDays = listOf("일", "월", "화", "수", "목", "금", "토")
        val header = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        weekDays.forEach { header.addView(text(it, 14f, SfTheme.INK, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, dp(30), 1f)) }
        addView(header)

        val today = LocalDate.now()
        val firstDay = month.atDay(1)
        val startOffset = firstDay.dayOfWeek.value % 7
        val startDate = firstDay.minusDays(startOffset.toLong())
        repeat(6) { week ->
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(7) { dayOfWeek ->
                val date = startDate.plusDays((week * 7 + dayOfWeek).toLong())
                val summary = days[date.toString()]
                row.addView(calendarDayCell(date, month, today, summary), LinearLayout.LayoutParams(0, dp(58), 1f))
            }
            addView(row)
        }
    }

    private fun monthStepButton(glyph: String, description: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = glyph
            textSize = 28f
            setAllCaps(false)
            contentDescription = description
            setTextColor(SfTheme.INK)
            background = rounded(SfTheme.SURFACE_ALT, 10)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        }

    private fun calendarDayCell(
        date: LocalDate,
        month: YearMonth,
        today: LocalDate,
        summary: DailyLogSummary?
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val inMonth = YearMonth.from(date) == month
        setPadding(dp(1), dp(2), dp(1), dp(2))
        if (date == today) background = rounded(SfTheme.PRIMARY_SOFT, 8)
        // Tap a day (of this month, not in the future) to see that day's movement route on the map.
        if (inMonth && !date.isAfter(today)) {
            isClickable = true
            setOnClickListener { showMap(date) }
        }

        addView(text(date.dayOfMonth.toString(), 17f, if (inMonth) SfTheme.INK else SfTheme.INK_FAINT, date == today).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(27)))

        val markerRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        if (inMonth && summary != null) {
            addLogMarker(markerRow, "N", summary.normal, SfTheme.PRIMARY)
            addLogMarker(markerRow, "W", summary.warning, SfTheme.AMBER)
            addLogMarker(markerRow, "E", summary.emergency, SfTheme.DANGER)
        }
        addView(markerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(23)))
    }

    /**
     * 달력 날짜 아래의 상태 표시. 앰버 위의 흰 글씨는 대비가 모자라므로 앰버 배지만 INK 글자를 쓴다.
     */
    private fun addLogMarker(row: LinearLayout, label: String, count: Int, color: Int) {
        if (count <= 0) return
        val onColor = if (color == SfTheme.AMBER) SfTheme.INK else Color.WHITE
        row.addView(text(label, 12f, onColor, true).apply {
            gravity = Gravity.CENTER
            background = oval(color)
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
    }

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(26), dp(22), dp(28))
        setBackgroundColor(SfTheme.BG)
    }

    /**
     * 화면 맨 위에 깔리는 앰비언트 글로우. 시안의 시그니처 요소로, 보호자가 글자를 읽기 전에 상태를
     * 색으로 먼저 알아채게 한다. [glowColor] 가 null 이면 (서버 오류처럼 상태를 단정할 수 없을 때)
     * 빛 없이 배경만 남긴다.
     */
    private fun ambientGlow(glowColor: Int?, height: Int = 132): View {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (glowColor == null) dp(1) else dp(height)
        ).apply {
            // page() 의 좌우/위 여백 바깥까지 번지게 한다.
            leftMargin = -dp(22)
            rightMargin = -dp(22)
            topMargin = -dp(26)
            bottomMargin = -dp(height - 8)   // 빛만 남기고 자리는 차지하지 않는다
        }
        return if (glowColor == null) View(this).apply { layoutParams = params }
        else AmbientGlowView(this, glowColor).apply { layoutParams = params }
    }

    /**
     * 상태 문구를 감싸는 알약 배지. 색 하나에만 기대지 않도록 배경·글자색·문구 세 가지로 상태를
     * 전달한다. [textColor] 는 대비 때문에 반드시 진한 쪽(AMBER_INK, PRIMARY_DARK, DANGER)을 쓴다.
     */
    private fun statusPill(value: String, textColor: Int, backgroundColor: Int): TextView =
        text(value, 15f, textColor, true).apply {
            background = rounded(backgroundColor, 999)
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }


    /**
     * targetSdk 35 부터는 화면이 시스템 바 뒤까지 그려진다. 그래서 마지막 요소(홈 화면의 배터리 막대)가
     * 내비게이션 바에 가려졌다. 아래쪽 인셋만큼 여백을 넣어 준다. 위쪽은 글로우가 상태 표시줄 뒤까지
     * 번지는 게 의도한 모습이라 그대로 둔다.
     */
    private fun LinearLayout.root(): ScrollView {
        val content = this
        return ScrollView(this@MainActivity).apply {
            setBackgroundColor(SfTheme.BG)
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // 여백은 스크롤되는 내용 쪽에 준다. ScrollView 자체에 주면 마지막 요소가 여전히
            // 내비게이션 바에 가린다.
            setOnApplyWindowInsetsListener { _, insets ->
                val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(android.view.WindowInsets.Type.systemBars()).bottom
                } else {
                    @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                }
                content.setPadding(
                    content.paddingLeft,
                    content.paddingTop,
                    content.paddingRight,
                    dp(28) + bottom
                )
                insets
            }
            requestApplyInsets()
        }
    }

    private fun appLogo(size: Int): ImageView = ImageView(this).apply {
        setImageResource(R.drawable.ic_safe_finder)
        background = rounded(SfTheme.SURFACE, 24)
        elevation = dp(8).toFloat()
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(48)
            bottomMargin = dp(18)
        }
    }

    private fun title(value: String, size: Float): TextView = text(value, size, SfTheme.INK, true)

    private fun label(value: String): TextView = text(value, 18f, SfTheme.INK, true).apply {
        setPadding(dp(4), dp(12), 0, dp(4))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun input(hintValue: String, value: String, inputTypeValue: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(this).apply {
        hint = hintValue
        setText(value)
        textSize = 18f
        inputType = inputTypeValue
        setSingleLine(true)
        setTextColor(SfTheme.INK)
        setHintTextColor(SfTheme.INK_FAINT)
        background = roundedStroke(SfTheme.SURFACE, 13, SfTheme.LINE, 2)
        setPadding(dp(16), 0, dp(16), 0)
    }

    private fun primaryButton(label: String, params: LinearLayout.LayoutParams? = null, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 19f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setAllCaps(false)
        background = rounded(SfTheme.PRIMARY, 14)
        setOnClickListener { onClick() }
        if (params != null) layoutParams = params
    }

    /** 테두리만 있는 보조 버튼 (시안의 ghost 버튼). */
    private fun secondaryButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 16f
        setTextColor(SfTheme.INK_SOFT)
        setTypeface(typeface, Typeface.BOLD)
        setAllCaps(false)
        background = roundedStroke(Color.TRANSPARENT, 14, SfTheme.LINE, 2)
        setOnClickListener { onClick() }
    }

    /** 되돌릴 수 없는 동작(삭제·초기화)에만 쓴다. 단순 실패·경고에는 앰버를 쓴다. */
    private fun dangerButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 17f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setAllCaps(false)
        background = rounded(SfTheme.DANGER, 14)
        setOnClickListener { onClick() }
    }

    /**
     * 안전구역·보호자 목록의 한 줄. 시안 규격의 리스트 아이템으로, 선택되면 테두리와 배경이 함께
     * 바뀐다. BSSID 나 내부 ID 같은 기술값은 보호자에게 아무 의미가 없어 화면에 싣지 않는다.
     */
    private fun listItem(
        checked: Boolean,
        glyph: String,
        titleText: String,
        subText: String,
        pillText: String,
        pillOn: Boolean,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = roundedStroke(
            if (checked) SfTheme.PRIMARY_SOFT else SfTheme.SURFACE,
            13,
            if (checked) SfTheme.PRIMARY else SfTheme.LINE,
            2
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }

        addView(CheckBox(this@MainActivity).apply {
            isChecked = checked
            buttonTintList = android.content.res.ColorStateList.valueOf(SfTheme.PRIMARY)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(dp(34), dp(40)))

        addView(text(glyph, 22f, SfTheme.PRIMARY).apply {
            gravity = Gravity.CENTER
            background = rounded(if (checked) SfTheme.SURFACE else SfTheme.SURFACE_ALT, 9)
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(11) })

        val main = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        main.addView(text(titleText, 18f, SfTheme.INK, true))
        main.addView(text(subText, 14f, SfTheme.INK_SOFT).apply { setPadding(0, dp(2), 0, 0) })
        addView(main, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        addView(text(pillText, 13f, if (pillOn) SfTheme.PRIMARY_DARK else SfTheme.INK_SOFT, true).apply {
            background = rounded(if (pillOn) SfTheme.PRIMARY_SOFT else SfTheme.SURFACE_ALT, 999)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        })
    }

    /**
     * 안내 카드. 실패·지연·주의는 전부 앰버이고, 빨강(DANGER)은 SOS 와 삭제 확정에만 쓴다.
     * 문구는 "무엇이 일어났는가 + 지금 무엇을 하면 되는가" 두 문장으로 적는다.
     */
    private fun noticeCard(headline: String, guide: String, warn: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(if (warn) SfTheme.AMBER_SOFT else SfTheme.SURFACE_ALT, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            addView(text(headline, 16f, SfTheme.INK, true))
            addView(text(guide, 14f, if (warn) SfTheme.AMBER_INK else SfTheme.INK_SOFT).apply {
                setPadding(0, dp(5), 0, 0)
                setLineSpacing(0f, 1.25f)
            })
        }

    /**
     * 하단에서 올라오는 시트. 이 앱은 AppCompat/Material 을 쓰지 않으므로 기본 Dialog 를 아래쪽에
     * 붙여 시안의 바텀시트(radius 22, 36x4 핸들)를 직접 만든다.
     */
    private fun bottomSheet(build: LinearLayout.(AlertDialog) -> Unit): AlertDialog {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(22))
            background = GradientDrawable().apply {
                setColor(SfTheme.SURFACE)
                cornerRadii = floatArrayOf(
                    dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
            addView(View(this@MainActivity).apply {
                background = rounded(SfTheme.LINE, 999)
            }, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            })
        }
        val dialog = AlertDialog.Builder(this).setView(body).create()
        body.build(dialog)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }

    /**
     * 확인 시트. [destructive] 가 true 일 때만 빨강 버튼을 쓴다(실제 삭제·초기화). 그 밖의 확인은
     * 앰버 계열로 두어, 빨강을 봤을 때 "되돌릴 수 없는 일"이라는 뜻이 유지되게 한다.
     */
    private fun confirmSheet(
        headline: String,
        message: String,
        confirmLabel: String,
        destructive: Boolean = false,
        onConfirm: () -> Unit
    ) {
        bottomSheet { dialog ->
            addView(text(headline, 21f, SfTheme.INK, true))
            addView(text(message, 16f, SfTheme.INK_SOFT).apply {
                setPadding(0, dp(8), 0, dp(18))
                setLineSpacing(0f, 1.3f)
            })
            val confirm = if (destructive) {
                dangerButton(confirmLabel) { dialog.dismiss(); onConfirm() }
            } else {
                primaryButton(confirmLabel) { dialog.dismiss(); onConfirm() }
            }
            addView(confirm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
            addView(secondaryButton("취소") { dialog.dismiss() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                    topMargin = dp(8)
                })
        }
    }

    /**
     * 실패 안내 시트. 보호자에게 보이는 문구는 "무엇이 안 됐는가 + 지금 뭘 하면 되는가" 두 줄이고,
     * 서버 원문은 '자세히' 안에 접어 둔다. 실패는 빨강이 아니라 앰버다 — 빨강은 SOS 와 삭제 전용.
     */
    private fun failureSheet(headline: String, detail: String, onRetry: (() -> Unit)? = null) {
        bottomSheet { dialog ->
            addView(text(headline, 21f, SfTheme.INK, true))
            addView(text("인터넷 연결을 확인한 뒤 다시 시도해 주세요.", 16f, SfTheme.INK_SOFT).apply {
                setPadding(0, dp(8), 0, dp(14))
                setLineSpacing(0f, 1.3f)
            })
            val detailView = text(detail.ifBlank { "추가 정보가 없습니다." }, 14f, SfTheme.INK_SOFT).apply {
                visibility = View.GONE
                background = rounded(SfTheme.SURFACE_ALT, 10)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setLineSpacing(0f, 1.25f)
            }
            addView(text("자세히", 15f, SfTheme.PRIMARY_DARK, true).apply {
                setPadding(0, 0, 0, dp(8))
                setOnClickListener {
                    detailView.visibility = if (detailView.visibility == View.GONE) View.VISIBLE else View.GONE
                }
            })
            addView(detailView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) })

            if (onRetry != null) {
                addView(primaryButton("다시 시도") { dialog.dismiss(); onRetry() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
                addView(secondaryButton("닫기") { dialog.dismiss() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                        topMargin = dp(8)
                    })
            } else {
                addView(primaryButton("확인") { dialog.dismiss() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
            }
        }
    }

    /**
     * 안전구역 종류의 사용자 표기 ↔ 서버 코드. 화면에는 WIFI/BLE/GPS 같은 코드를 노출하지 않는다.
     */
    private fun zoneTypeCode(label: String): String = when (label) {
        ZONE_TYPE_LABELS[1] -> "BLE"
        ZONE_TYPE_LABELS[2] -> "GPS"
        else -> "WIFI"
    }

    /** 안전구역 종류를 보호자에게 보여줄 말로 바꾼다. */
    private fun zoneTypeLabel(code: String): String = when (code.uppercase()) {
        "BLE" -> ZONE_TYPE_LABELS[1]
        "GPS" -> ZONE_TYPE_LABELS[2]
        else -> ZONE_TYPE_LABELS[0]
    }

    /**
     * BLE 신호 세기를 보호자가 읽을 수 있는 말로 바꾼다. dBm 은 화면에 싣지 않는다.
     * 임계값은 실내 1~2m(-60 이상) / 같은 방(-75 이상) 기준으로 잡았다.
     */
    private fun signalLabel(rssi: Int): String = when {
        rssi >= -60 -> "가까움"
        rssi >= -75 -> "보통"
        else -> "멂"
    }

    /** 목록이 비었을 때: 사실만 알리지 말고 다음에 할 일을 함께 적는다. */
    private fun emptyState(headline: String, guide: String): LinearLayout = card().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        addView(text(headline, 18f, SfTheme.INK, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        addView(text(guide, 14f, SfTheme.INK_SOFT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(10))
            setLineSpacing(0f, 1.2f)
        })
    }

    /**
     * 홈 화면 하단의 큰 액션 타일. [accent] 는 상태에 따라 바뀌어(주의 상태의 "지도 보기"처럼)
     * 지금 봐야 할 쪽을 가리킨다.
     */
    private fun tile(
        label: String,
        mark: String,
        accent: Int = SfTheme.PRIMARY,
        accentSoft: Int = SfTheme.PRIMARY_SOFT,
        onClick: () -> Unit
    ): LinearLayout = card().apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(16), dp(14), dp(16))
        setOnClickListener { onClick() }
        addView(text(mark, 34f, accent).apply {
            gravity = Gravity.CENTER
            background = rounded(accentSoft, 12)
        }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(text(label, 20f, SfTheme.INK, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
    }

    private fun summaryTile(count: String, label: String, color: Int): LinearLayout = card().apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        addView(text(count, 23f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = oval(color)
        }, LinearLayout.LayoutParams(dp(70), dp(70)))
        addView(text(label, 15f, SfTheme.INK, true).apply { gravity = Gravity.CENTER })
    }

    /** 배터리 표시. 값과 라벨을 위에 두고, 그 아래 가느다란 막대만 남긴다(시안 규격). */
    private fun batteryBar(percentValue: Int? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val percent = percentValue?.coerceIn(0, 100)
        val filled = (percent ?: 0).toFloat()
        val empty = (100 - (percent ?: 0)).toFloat()
        val fillColor = when {
            percent == null -> SfTheme.INK_FAINT
            percent <= 20 -> SfTheme.DANGER
            percent <= 50 -> SfTheme.AMBER
            else -> SfTheme.PRIMARY
        }
        val head = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            text("기기 배터리", 15f, SfTheme.INK_SOFT),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(text(percent?.let { "$it%" } ?: "-", 19f, SfTheme.INK, true))
        addView(head)

        val track = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(SfTheme.SURFACE_ALT, 999)
        }
        track.addView(View(this@MainActivity).apply {
            background = rounded(fillColor, 999)
        }, LinearLayout.LayoutParams(0, dp(10), filled))
        track.addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(10), empty))
        addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply {
            topMargin = dp(7)
        })
    }

    /** 시안 규격의 카드: 흰 배경 + 1px 테두리 + radius 14. 그림자는 쓰지 않는다. */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = roundedStroke(SfTheme.SURFACE, 14, SfTheme.LINE)
    }

    private fun narrowCardParams(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), dp(top), dp(12), 0)
        }

    private fun rowParams(top: Int = 0, height: Int = 56): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)).apply { topMargin = dp(top) }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun roundedStroke(color: Int, radius: Int, stroke: Int, strokeWidth: Int = 1): GradientDrawable =
        rounded(color, radius).apply { setStroke(dp(strokeWidth), stroke) }

    /** 상태 오브에 쓰는 원판: 안은 흰색, 테두리는 상태색의 옅은 톤. */
    private fun ovalStroke(color: Int, stroke: Int, strokeWidth: Int): GradientDrawable =
        oval(color).apply { setStroke(dp(strokeWidth), stroke) }

    private fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun elderName(): String = appPrefs.getString("elder_name", "아버님").orEmpty().ifBlank { "아버님" }

    private fun currentDeviceId(): String = appPrefs.getString("device_id", SfcConfig.DEFAULT_DEVICE_ID).orEmpty().ifBlank { SfcConfig.DEFAULT_DEVICE_ID }

    private enum class HomeStateKind {
        SAFE_WIFI,
        SAFE_OTHER,
        WARNING,
        SOS,
        OTHER
    }

    /** 홈 화면을 물들이는 색 단계. HomeStateKind 가 "무슨 상태인가"라면 이쪽은 "어떤 색인가"이다. */
    private enum class HomeAccentKind {
        NORMAL,
        WARNING,
        SOS,
        UNKNOWN
    }

    private fun appendStatus(message: String) {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        logLines.addFirst("[$time] $message")
        while (logLines.size > 80) logLines.removeLast()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val HOME_REFRESH_INTERVAL_MS = 60 * 1000L
        // How long to wait for the SFD/backend registration ACK before offering retry/back.
        private const val REGISTRATION_TIMEOUT_MS = 40 * 1000L

        /** 안전구역 종류의 화면 표기. 순서는 서버 코드 WIFI / BLE / GPS 와 맞춘다. */
        private val ZONE_TYPE_LABELS = listOf("WiFi", "블루투스", "위치(GPS)")
    }
}

