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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
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
    private var scanDialog: AlertDialog? = null
    private var selectedSafeZoneIndex: Int = -1
    private var selectedGuardianIndex: Int = -1
    private var selectedLogMonth: YearMonth = YearMonth.now()
    private var latestHomeLog: DeviceLogEntry? = null
    private var latestHomeLogs: List<DeviceLogEntry> = emptyList()
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
        if (isRegistered()) showHome() else showPairing()
    }

    override fun onDestroy() {
        stopHomeRefresh()
        scanDialog?.dismiss()
        bleManager.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        foregroundVisible = true
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
        content.addView(appLogo(dp(104)))
        content.addView(title("Safe Finder", 30f).apply { gravity = Gravity.CENTER })
        content.addView(card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(text("아직 Device 등록이\n안되어있습니다.", 23f, 0xFF111827.toInt(), true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(18))
            })
            scanButton = primaryButton("BLE 탐색") { showBlePickerDialog() }
            registerButton = primaryButton("기기 등록") { beginProvisionFlow() }
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
        val elderNameView = text("Elder name: 조회 전", 16f, 0xFF475569.toInt(), true).apply {
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
        dialogDeviceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(text("탐색 중입니다. SFD 앱이 실행 중이어야 합니다.", 15f, 0xFF64748B.toInt()))
        }
        val body = ScrollView(this).apply {
            addView(dialogDeviceList, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scanDialog = AlertDialog.Builder(this)
            .setTitle("BLE 디바이스 목록")
            .setView(body)
            .setNegativeButton("닫기") { dialog, _ ->
                bleManager.stopScan()
                dialog.dismiss()
            }
            .create()
        scanDialog?.setOnDismissListener {
            dialogDeviceList = null
            bleManager.stopScan()
        }
        scanDialog?.show()
        bleManager.startScan()
    }

    @SuppressLint("MissingPermission")
    private fun addOrUpdateDevice(scanned: ScannedDevice) {
        scannedDevices[scanned.address] = scanned
        val list = dialogDeviceList ?: return
        list.removeAllViews()
        scannedDevices.values
            .forEach { item ->
                list.addView(Button(this).apply {
                    text = "${item.name}\n${item.address}   RSSI ${item.rssi}"
                    textSize = 15f
                    setAllCaps(false)
                    setOnClickListener {
                        selectedDevice = item.device
                        selectedDeviceName = item.name.ifBlank { item.address }
                        scanButton?.text = selectedDeviceName
                        bleManager.stopScan()
                        scanDialog?.dismiss()
                        appendStatus("BLE 선택: $selectedDeviceName")
                    }
                }, rowParams(top = 8, height = 64))
            }
    }

    private fun beginProvisionFlow() {
        val device = selectedDevice
        if (device == null) {
            AlertDialog.Builder(this)
                .setTitle("BLE 탐색 필요")
                .setMessage("기기 등록 전에 BLE 기기를 먼저 탐색하고 선택해 주세요.")
                .setPositiveButton("BLE 탐색") { _, _ -> showBlePickerDialog() }
                .setNegativeButton("닫기", null)
                .show()
            return
        }

        registerButton?.isEnabled = false
        registerButton?.text = "ID 발급 중..."
        appendStatus("Provision request ?쒖옉")
        Thread {
            runCatching { apiClient.provisionDevice(selectedDeviceName, device.address) }
                .onSuccess { result ->
                    appendStatus("PROVISION REQUEST ${result.requestJson}")
                    appendStatus("PROVISION RESPONSE ${result.responseCode}: ${result.responseBody}")
                    runOnUiThread {
                        registerButton?.isEnabled = true
                        registerButton?.text = "기기 등록"
                        val deviceId = result.deviceId
                        if (result.responseCode !in 200..299 || deviceId.isNullOrBlank()) {
                            AlertDialog.Builder(this)
                                .setTitle("ID 발급 실패")
                                .setMessage(result.responseBody.ifBlank { "서버에서 deviceId를 받지 못했습니다." })
                                .setPositiveButton("확인", null)
                                .show()
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
                        registerButton?.text = "기기 등록"
                        AlertDialog.Builder(this)
                            .setTitle("ID 발급 실패")
                            .setMessage(error.message ?: error.javaClass.simpleName)
                            .setPositiveButton("확인", null)
                            .show()
                    }
                }
        }.start()
    }

    private fun showRegistrationForm(deviceId: String) {
        homeVisible = false
        val defaults = defaultsReader.read()
        val content = page()
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(title(deviceId, 30f).apply { gravity = Gravity.CENTER })
        val elder = input("환자 성함", appPrefs.getString("elder_name", "아버님").orEmpty())
        val guardian = input("보호자 성함", defaults.ownerName.ifBlank { "방효식" })
        val phone = input("전화번호", defaults.phoneNumber, InputType.TYPE_CLASS_PHONE)
        val wifiApName = input("WiFi: AP Name", defaults.wifiSsid.ifBlank { defaults.wifiName }).apply { isEnabled = false }
        val safeZoneName = input("AP 지정 이름", appPrefs.getString("wifi_name", "집").orEmpty())
        val password = input("Password", "bang8813", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val wifiSsid = defaults.wifiSsid.ifBlank { defaults.wifiName }
        val wifiBssid = defaults.wifiBssid

        content.addView(card().apply {
            addView(label("환자 성함"))
            addView(elder, rowParams(height = 52))
            addView(label("보호자 성함"))
            addView(guardian, rowParams(height = 52))
            addView(label("전화번호"))
            addView(phone, rowParams(height = 52))
            addView(label("WiFi: AP Name"))
            addView(wifiApName, rowParams(height = 52))
            addView(label("AP 지정 이름"))
            addView(safeZoneName, rowParams(height = 52))
            addView(label("Password"))
            addView(password, rowParams(height = 52))
            addView(primaryButton("기기 등록") {
                val device = selectedDevice
                if (device == null) {
                    toast("BLE 기기를 먼저 선택해 주세요.")
                    showPairing()
                    return@primaryButton
                }
                val missing = listOf(
                    "환자 성함" to elder.text.toString(),
                    "보호자 성함" to guardian.text.toString(),
                    "전화번호" to phone.text.toString(),
                    "WiFi AP Name" to wifiApName.text.toString(),
                    "AP 지정 이름" to safeZoneName.text.toString(),
                    "Password" to password.text.toString()
                ).filter { it.second.isBlank() }.map { it.first }
                if (missing.isNotEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("입력 필요")
                        .setMessage("항목을 채워 주세요.\n${missing.joinToString(", ")}")
                        .setPositiveButton("확인", null)
                        .show()
                    return@primaryButton
                }
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
                    bluetoothSsid = selectedDeviceName.ifBlank { defaults.bluetoothName }
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
        bleManager.sendConfig(device, json)
        startService(Intent(this, SfcBleMonitorService::class.java))
    }

    private fun completeRegistration() {
        if (isRegistered()) return
        appPrefs.edit().putBoolean("registered", true).apply()
        showComplete()
    }

    private fun showComplete() {
        homeVisible = false
        val content = page()
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(TextView(this).apply {
            text = "✓"
            textSize = 74f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = oval(0xFF77C98A.toInt())
        }, LinearLayout.LayoutParams(dp(118), dp(118)).apply { topMargin = dp(92) })
        content.addView(title("기기 등록 완료", 34f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(8))
        })
        content.addView(text("Device 등록이 성공적으로\n완료되었습니다.\n이제 '${elderName()}'의 안전과 위치를\n확인하실 수 있습니다.", 21f, 0xFF111827.toInt()).apply {
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

    private fun loadHomeLog() {
        val deviceId = currentDeviceId()
        val date = LocalDate.now().toString()
        Thread {
            runCatching {
                apiClient.getDeviceLogsByDate(deviceId, date, page = 0, size = 50)
            }.onSuccess { page ->
                val latest = page.logs.maxByOrNull { it.eventTimestamp }
                latestHomeLogs = page.logs
                latestHomeLog = latest
                appendStatus("HOME LOG REQUEST /devices/$deviceId/logs?date=$date&page=0&size=50")
                runOnUiThread { renderHome(latest, isLoading = false) }
            }.onFailure { error ->
                appendStatus("HOME LOG ERROR ${error.message ?: error.javaClass.simpleName}")
                runOnUiThread { renderHome(latestHomeLog, isLoading = false, error = error) }
            }
        }.start()
    }

    private fun renderHome(log: DeviceLogEntry?, isLoading: Boolean = false, error: Throwable? = null) {
        homeVisible = true
        val content = page()
        content.addView(header())
        content.addView(text(homeTimestampLabel(log, isLoading), 22f, 0xFF111827.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(6))
        })
        content.addView(title(elderName(), 36f).apply { gravity = Gravity.CENTER })
        content.addView(ImageView(this).apply {
            setImageResource(homeIconRes(log))
            background = oval(0xFFFFFFFF.toInt())
            elevation = dp(8).toFloat()
            setPadding(dp(34), dp(34), dp(34), dp(34))
        }, LinearLayout.LayoutParams(dp(248), dp(248)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(28)
        })
        content.addView(title(homeZoneLabel(log), 32f).apply { gravity = Gravity.CENTER })
        content.addView(card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(text(homeStatusLabel(log, isLoading, error), 18f, homeStatusColor(log, error), true).apply {
                gravity = Gravity.CENTER
            })
            addView(text(homeDetailLabel(log), 14f, 0xFF475569.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
            addView(text(homeDurationLabel(log), 14f, homeStatusColor(log, error), true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(14), dp(18), dp(14), 0)
        })

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(28), dp(10), 0)
        }
        actionRow.addView(tile("지도 보기", "⌖") { showMap() }, LinearLayout.LayoutParams(0, dp(142), 1f).apply { marginEnd = dp(8) })
        actionRow.addView(tile("Log 보기", "▦") { showLogs() }, LinearLayout.LayoutParams(0, dp(142), 1f).apply { marginStart = dp(8) })
        content.addView(actionRow)
        content.addView(batteryBar(log?.battery), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            setMargins(dp(26), dp(26), dp(26), 0)
        })
        setContentView(content.root())
    }

    private fun showSafeZoneForm() {
        homeVisible = false
        val content = page()
        content.addView(header("안전구역 등록", showBack = true))
        content.addView(title(currentDeviceId(), 26f).apply { gravity = Gravity.CENTER })
        content.addView(card().apply {
            addView(text("안전구역 정보를 불러오는 중입니다.", 17f, 0xFF475569.toInt()).apply {
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
        content.addView(text("Elder ID: ${elder.elderId}", 12f, 0xFF64748B.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        })

        content.addView(card().apply {
            addView(safeZoneHeaderRow())
            if (zones.isEmpty()) {
                addView(text("등록된 안전구역이 없습니다.", 15f, 0xFF64748B.toInt()).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24), 0, dp(24))
                })
            } else {
                zones.forEachIndexed { index, zone ->
                    addView(safeZoneRow(index, zone, elder, zones), rowParams(top = 6, height = 54))
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        buttons.addView(secondaryButton("추가") { showSafeZoneAddForm(elder) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        buttons.addView(secondaryButton("수정") {
            if (selectedSafeZoneIndex < 0) {
                toast("수정할 안전구역을 선택해 주세요.")
            } else {
                confirmUpdateSafeZoneEnabled(elder, zones[selectedSafeZoneIndex])
            }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        buttons.addView(secondaryButton("삭제") {
            if (selectedSafeZoneIndex < 0) {
                toast("삭제할 안전구역을 선택해 주세요.")
            } else {
                confirmDeleteSafeZone(elder, zones[selectedSafeZoneIndex])
            }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        content.addView(buttons)
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
        content.addView(text("Elder ID: ${elder.elderId}", 12f, 0xFF64748B.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        })

        val zoneType = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("WiFi", "BLE", "GPS")
            )
        }
        val name = input("Name", appPrefs.getString("wifi_name", "집").orEmpty().ifBlank { "집" })
        val ssid = input("ssid", wifiSsid).apply {
            isEnabled = false
            setTextColor(0xFF475569.toInt())
        }
        val password = input("WiFi Password", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val bssid = input("bssid", wifiBssid).apply {
            isEnabled = false
            setTextColor(0xFF475569.toInt())
        }
        val enabled = CheckBox(this).apply {
            text = "Enable"
            textSize = 18f
            isChecked = true
            setTextColor(0xFF111827.toInt())
            setPadding(dp(2), dp(10), 0, dp(10))
        }

        content.addView(card().apply {
            addView(label("zoneType"))
            addView(zoneType, rowParams(height = 52))
            addView(label("Name"))
            addView(name, rowParams(height = 52))
            addView(label("ssid"))
            addView(ssid, rowParams(height = 52))
            addView(label("WiFi Password"))
            addView(password, rowParams(height = 52))
            addView(label("bssid"))
            addView(bssid, rowParams(height = 52))
            addView(enabled, rowParams(height = 50))
            addView(primaryButton("등록") {
                val selectedType = zoneType.selectedItem?.toString().orEmpty()
                val zoneTypeValue = when (selectedType) {
                    "WiFi" -> "WIFI"
                    "BLE" -> "BLE"
                    "GPS" -> "GPS"
                    else -> selectedType.uppercase()
                }
                val missing = listOf(
                    "Name" to name.text.toString(),
                    "ssid" to ssid.text.toString(),
                    "WiFi Password" to password.text.toString(),
                    "bssid" to bssid.text.toString()
                ).filter { it.second.isBlank() }.map { it.first }
                if (missing.isNotEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("항목 확인")
                        .setMessage("${missing.joinToString(", ")} 항목을 채워 주세요.")
                        .setPositiveButton("확인", null)
                        .show()
                    return@primaryButton
                }
                val draft = SafeZoneDraft(
                    zoneType = zoneTypeValue,
                    name = name.text.toString(),
                    bssid = bssid.text.toString(),
                    ssid = ssid.text.toString(),
                    enabled = enabled.isChecked
                )
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
                                password = wifiPassword
                            )
                            AlertDialog.Builder(this)
                                .setTitle("안전구역 등록 완료")
                                .setMessage("ZoneID: ${result.zoneId}")
                                .setPositiveButton("확인") { _, _ -> showSafeZoneForm() }
                                .show()
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("안전구역 등록 실패")
                                .setMessage(result.responseBody.ifBlank { "서버에서 ZoneID를 받지 못했습니다." })
                                .setPositiveButton("확인", null)
                                .show()
                        }
                    }
                }
                .onFailure { error ->
                    appendStatus("SAFEZONE CREATE ERROR ${error.message ?: error.javaClass.simpleName}")
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("안전구역 등록 실패")
                            .setMessage(error.message ?: error.javaClass.simpleName)
                            .setPositiveButton("확인", null)
                            .show()
                    }
                }
        }.start()
    }

    private fun confirmUpdateSafeZoneEnabled(elder: ElderInfo, zone: SafeZoneInfo) {
        if (zone.zoneId.isBlank()) {
            toast("ZoneID가 없어 수정할 수 없습니다.")
            return
        }
        val nextEnabled = !zone.enabled
        AlertDialog.Builder(this)
            .setTitle("안전구역 수정")
            .setMessage("${zone.name} Enabled 값을 $nextEnabled 로 변경할까요?")
            .setPositiveButton("수정") { _, _ -> updateSafeZoneEnabled(elder, zone, nextEnabled) }
            .setNegativeButton("취소", null)
            .show()
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
                            AlertDialog.Builder(this)
                                .setTitle("안전구역 수정 실패")
                                .setMessage(result.responseBody.ifBlank { "HTTP ${result.responseCode}" })
                                .setPositiveButton("확인", null)
                                .show()
                        }
                    }
                }
                .onFailure { error ->
                    appendStatus("SAFEZONE PATCH ERROR ${error.message ?: error.javaClass.simpleName}")
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("안전구역 수정 실패")
                            .setMessage(error.message ?: error.javaClass.simpleName)
                            .setPositiveButton("확인", null)
                            .show()
                    }
                }
        }.start()
    }

    private fun confirmDeleteSafeZone(elder: ElderInfo, zone: SafeZoneInfo) {
        if (zone.zoneId.isBlank()) {
            toast("ZoneID가 없어 삭제할 수 없습니다.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("안전구역 삭제")
            .setMessage("${zone.name} 안전구역을 삭제할까요?")
            .setPositiveButton("삭제") { _, _ -> deleteSafeZone(elder, zone) }
            .setNegativeButton("취소", null)
            .show()
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
                            AlertDialog.Builder(this)
                                .setTitle("안전구역 삭제 실패")
                                .setMessage(result.responseBody.ifBlank { "HTTP ${result.responseCode}" })
                                .setPositiveButton("확인", null)
                                .show()
                        }
                    }
                }
                .onFailure { error ->
                    appendStatus("SAFEZONE DELETE ERROR ${error.message ?: error.javaClass.simpleName}")
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("안전구역 삭제 실패")
                            .setMessage(error.message ?: error.javaClass.simpleName)
                            .setPositiveButton("확인", null)
                            .show()
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
        password: String = ""
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
            addView(text("안전구역 정보를 불러오지 못했습니다.", 18f, 0xFF991B1B.toInt(), true))
            addView(text(error.message ?: error.javaClass.simpleName, 13f, 0xFF475569.toInt()).apply {
                setPadding(0, dp(10), 0, dp(12))
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
            addView(text("보호자 정보를 불러오는 중입니다.", 17f, 0xFF475569.toInt()).apply {
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
        content.addView(text("Elder ID: ${elder.elderId}", 12f, 0xFF64748B.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        })

        content.addView(card().apply {
            addView(guardianHeaderRow())
            if (guardians.isEmpty()) {
                addView(text("등록된 보호자가 없습니다.", 15f, 0xFF64748B.toInt()).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24), 0, dp(24))
                })
            } else {
                guardians.forEachIndexed { index, guardian ->
                    addView(guardianRow(index, guardian, elder, guardians), rowParams(top = 6, height = 54))
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

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
        content.addView(text("Elder ID: ${elder.elderId}", 12f, 0xFF64748B.toInt()).apply {
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
        AlertDialog.Builder(this)
            .setTitle("보호자 삭제")
            .setMessage("${guardian.name} 보호자를 삭제할까요?")
            .setPositiveButton("삭제") { _, _ -> deleteGuardian(elder, guardian) }
            .setNegativeButton("취소", null)
            .show()
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
            addView(text("보호자 정보를 불러오지 못했습니다.", 18f, 0xFF991B1B.toInt(), true))
            addView(text(error.message ?: error.javaClass.simpleName, 13f, 0xFF475569.toInt()).apply {
                setPadding(0, dp(10), 0, dp(12))
            })
            addView(primaryButton("다시 시도") { showGuardianForm() }, rowParams(top = 6))
        }, narrowCardParams(top = 24))
        setContentView(content.root())
    }

    private fun showGuardianMutationError(title: String, result: GuardianMutationResult) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(result.responseBody.ifBlank { "HTTP ${result.responseCode}" })
            .setPositiveButton("확인", null)
            .show()
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
        content.addView(header("Log 보기", showBack = true))
        content.addView(calendarCard(month, emptyMap()))
        content.addView(label("로그 요약").apply { setPadding(dp(10), dp(24), 0, dp(8)) })
        content.addView(card().apply {
            addView(text("${month} 로그를 불러오는 중입니다.", 17f, 0xFF475569.toInt()).apply {
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
        content.addView(header("Log 보기", showBack = true))
        content.addView(calendarCard(month, days))
        content.addView(label("로그 요약").apply { setPadding(dp(10), dp(24), 0, dp(8)) })
        content.addView(text("월 전체 로그: ${calendar?.total ?: 0}건", 18f, 0xFF111827.toInt(), true).apply {
            setPadding(dp(10), 0, 0, dp(10))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(summaryTile("${calendar?.normal ?: 0}건", "Normal", 0xFF43C96E.toInt()), LinearLayout.LayoutParams(0, dp(136), 1f))
        row.addView(summaryTile("${calendar?.warning ?: 0}건", "Warning", 0xFFFFC928.toInt()), LinearLayout.LayoutParams(0, dp(136), 1f).apply { marginStart = dp(8) })
        row.addView(summaryTile("${calendar?.emergency ?: 0}건", "Emergency", 0xFFEF4444.toInt()), LinearLayout.LayoutParams(0, dp(136), 1f).apply { marginStart = dp(8) })
        content.addView(row)
        content.addView(card().apply {
            val serverLines = when {
                calendar != null -> listOf(
                    "Device ID: ${calendar.deviceId}",
                    "Month: ${calendar.month}",
                    "Total: ${calendar.total}건",
                    "PERIODIC → Normal: ${calendar.normal}건",
                    "GEOFENCE_EXIT_HINT → Warning: ${calendar.warning}건",
                    "SOS → Emergency: ${calendar.emergency}건"
                )
                error != null -> listOf(
                    "서버 월별 로그를 불러오지 못했습니다.",
                    error.message ?: error.javaClass.simpleName
                )
                else -> emptyList()
            }
            val localLines = if (logLines.isEmpty()) listOf("아직 표시할 내부 로그가 없습니다.") else logLines.toList()
            addView(text((serverLines + "" + localLines).joinToString("\n"), 13f, 0xFF334155.toInt()))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        setContentView(content.root())
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

    private fun homeZoneLabel(log: DeviceLogEntry?): String {
        if (log == null) return appPrefs.getString("wifi_name", "집").orEmpty().ifBlank { "집" }
        if (log.eventType == "SOS") return "SOS"
        if (log.inSafeZone == false) return when (log.locationType) {
            "GPS" -> "GPS 이동"
            "BLE" -> "BLE 안전구역"
            else -> "안전구역 밖"
        }
        return when (log.locationType) {
            "WIFI" -> appPrefs.getString("wifi_name", "집").orEmpty().ifBlank { log.apName ?: "집" }
            "BLE" -> "BLE 안전구역"
            "GPS" -> "GPS"
            else -> appPrefs.getString("wifi_name", "집").orEmpty().ifBlank { "집" }
        }
    }

    private fun homeStatusLabel(log: DeviceLogEntry?, isLoading: Boolean, error: Throwable?): String {
        if (error != null) return "서버 상태를 불러오지 못했습니다."
        if (isLoading && log == null) return "오늘 최신 로그를 불러오는 중입니다."
        if (log == null) return "오늘 수신된 로그가 없습니다."
        if (log.eventType == "SOS") return "SOS"
        val safeText = if (log.inSafeZone == true) "안전구역" else "이탈"
        val stateText = when (log.eventType) {
            "SOS" -> "Emergency"
            "GEOFENCE_EXIT_HINT" -> "Warning"
            else -> if (log.inSafeZone == false) "Warning" else log.deviceStatus.ifBlank { "Normal" }
        }
        return "$safeText · $stateText"
    }

    private fun homeIconRes(log: DeviceLogEntry?): Int = when {
        log?.eventType == "SOS" -> R.drawable.ic_sos_status
        log?.inSafeZone == false -> R.drawable.ic_gps_moving
        log?.locationType == "GPS" -> R.drawable.ic_gps_moving
        else -> R.drawable.ic_home_zone
    }

    private fun homePrimaryColor(log: DeviceLogEntry?, error: Throwable? = null): Int = when {
        error != null -> 0xFFEF4444.toInt()
        log?.eventType == "SOS" -> 0xFFDC2626.toInt()
        log?.eventType == "GEOFENCE_EXIT_HINT" -> 0xFFF59E0B.toInt()
        log?.inSafeZone == false -> 0xFFF59E0B.toInt()
        log?.locationType == "GPS" -> 0xFFF59E0B.toInt()
        log?.inSafeZone == true -> 0xFF16A34A.toInt()
        else -> 0xFF475569.toInt()
    }

    private fun homeStatusColor(log: DeviceLogEntry?, error: Throwable?): Int = homePrimaryColor(log, error)

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
            else -> log.deviceStatus.ifBlank { "Normal" }
        }
        val battery = log.battery?.let { "배터리 ${it}%" } ?: "배터리 -"
        val signal = log.signal?.let { "신호 ${it} dBm" } ?: "신호 -"
        return "$location · ${log.verb.ifBlank { log.eventType }} · $battery · $signal"
    }

    private fun homeDurationLabel(log: DeviceLogEntry?): String {
        if (log == null) return "상태 지속 시간: 확인 중"
        val state = homeStateKind(log)
        val startMillis = stateStartMillis(state) ?: parseLogMillis(log.eventTimestamp)
        val startLabel = startMillis?.let { formatSinceTime(it) } ?: "확인 중"
        val durationLabel = startMillis?.let { formatElapsed(System.currentTimeMillis() - it) } ?: "확인 중"
        return when (state) {
            HomeStateKind.SAFE_WIFI -> "WiFi 안전구역 체류: $startLabel 부터 · $durationLabel"
            HomeStateKind.WARNING -> "Warning 지속: $startLabel 부터 · $durationLabel"
            HomeStateKind.SOS -> "SOS 지속: $startLabel 부터 · $durationLabel"
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

    private fun showMap() {
        homeVisible = false
        val latest = latestHomeLog
        if (latest?.latitude != null && latest.longitude != null) {
            openMap(latest.latitude, latest.longitude)
            return
        }
        val location = currentLocation()
        if (location != null) {
            openMap(location.latitude, location.longitude)
        } else {
            openMap(0.0, 0.0, "현재 위치")
        }
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

    private fun header(titleText: String = "Safe Finder", showBack: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        if (showBack) {
            row.addView(ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_media_previous)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { if (isRegistered()) showHome() else showPairing() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        } else {
            row.addView(appLogo(dp(38)), LinearLayout.LayoutParams(dp(42), dp(42)))
        }
        row.addView(title(titleText, 24f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!showBack) {
            row.addView(text(currentDeviceId(), 12f, 0xFF475569.toInt(), true).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, dp(4), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        }
        row.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_sort_by_size)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showMenu(this) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        return row
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("안전구역 등록")
            menu.add("보호자 등록")
            menu.add("셋팅")
            menu.add("디바이스 정보")
            menu.add("디바이스 초기화")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "안전구역 등록" -> showSafeZoneForm()
                    "보호자 등록" -> showGuardianForm()
                    "셋팅" -> toast("셋팅 화면은 다음 단계에서 연결하겠습니다.")
                    "디바이스 정보" -> showDeviceInfo()
                    "디바이스 초기화" -> resetDeviceRegistration()
                }
                true
            }
        }.show()
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
        AlertDialog.Builder(this)
            .setTitle("디바이스 정보")
            .setMessage(
                "Device ID: ${currentDeviceId()}\n" +
                    "앱 버전: $appVersion\n" +
                    "FW 버전: $fwVersion"
            )
            .setPositiveButton("확인", null)
            .show()
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
        monthRow.addView(Button(this@MainActivity).apply {
            text = "‹"
            textSize = 28f
            setAllCaps(false)
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFFCBD5E1.toInt(), 10)
            setOnClickListener { showLogs(month.minusMonths(1)) }
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        monthRow.addView(title("${month.year}년 ${month.monthValue}월", 24f).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        monthRow.addView(Button(this@MainActivity).apply {
            text = "›"
            textSize = 28f
            setAllCaps(false)
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFF5B7ED5.toInt(), 10)
            setOnClickListener { showLogs(month.plusMonths(1)) }
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        addView(monthRow)

        val weekDays = listOf("일", "월", "화", "수", "목", "금", "토")
        val header = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        weekDays.forEach { header.addView(text(it, 14f, 0xFF111827.toInt(), true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, dp(30), 1f)) }
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
        if (date == today) background = rounded(0xFFE2E8F0.toInt(), 4)

        addView(text(date.dayOfMonth.toString(), 17f, if (inMonth) 0xFF111827.toInt() else 0xFF94A3B8.toInt(), date == today).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(27)))

        val markerRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        if (inMonth && summary != null) {
            addLogMarker(markerRow, "N", summary.normal, 0xFF7CCB74.toInt())
            addLogMarker(markerRow, "W", summary.warning, 0xFFE7C82F.toInt())
            addLogMarker(markerRow, "E", summary.emergency, 0xFFC85E52.toInt())
        }
        addView(markerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(23)))
    }

    private fun addLogMarker(row: LinearLayout, label: String, count: Int, color: Int) {
        if (count <= 0) return
        row.addView(text(label, 9f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = oval(color)
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply {
            marginStart = dp(1)
            marginEnd = dp(1)
        })
    }

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(26), dp(22), dp(28))
        background = gradient(0xFFEAF8FF.toInt(), 0xFFFFFBF2.toInt())
    }

    private fun LinearLayout.root(): ScrollView = ScrollView(this@MainActivity).apply {
        addView(this@root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun appLogo(size: Int): ImageView = ImageView(this).apply {
        setImageResource(R.drawable.ic_safe_finder)
        background = rounded(0xFFFFFFFF.toInt(), 24)
        elevation = dp(8).toFloat()
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(48)
            bottomMargin = dp(18)
        }
    }

    private fun title(value: String, size: Float): TextView = text(value, size, 0xFF111827.toInt(), true)

    private fun label(value: String): TextView = text(value, 18f, 0xFF111827.toInt(), true).apply {
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
        background = roundedStroke(0x00FFFFFF, 22, 0xFFCBD5E1.toInt())
        setPadding(dp(18), 0, dp(18), 0)
    }

    private fun primaryButton(label: String, params: LinearLayout.LayoutParams? = null, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 19f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setAllCaps(false)
        background = gradient(0xFF70C39A.toInt(), 0xFF4C7FC0.toInt(), 24)
        setOnClickListener { onClick() }
        if (params != null) layoutParams = params
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 16f
        setTextColor(0xFF111827.toInt())
        setTypeface(typeface, Typeface.BOLD)
        setAllCaps(false)
        background = roundedStroke(0xFFFFFFFF.toInt(), 14, 0xFFCBD5E1.toInt())
        setOnClickListener { onClick() }
    }

    private fun safeZoneHeaderRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(4))
        addView(tableCell("선택", 13f, true), LinearLayout.LayoutParams(0, dp(34), 0.8f))
        addView(tableCell("Type", 13f, true), LinearLayout.LayoutParams(0, dp(34), 1f))
        addView(tableCell("Name", 13f, true), LinearLayout.LayoutParams(0, dp(34), 1f))
        addView(tableCell("BSSID", 13f, true), LinearLayout.LayoutParams(0, dp(34), 1.8f))
        addView(tableCell("SSID", 13f, true), LinearLayout.LayoutParams(0, dp(34), 1.6f))
        addView(tableCell("Enabled", 13f, true), LinearLayout.LayoutParams(0, dp(34), 1f))
    }

    private fun safeZoneRow(index: Int, zone: SafeZoneInfo, elder: ElderInfo, zones: List<SafeZoneInfo>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(0xFFFFFFFF.toInt(), 8)
        val checkBox = CheckBox(this@MainActivity).apply {
            gravity = Gravity.CENTER
            isChecked = selectedSafeZoneIndex == index
            setOnClickListener {
                selectedSafeZoneIndex = if (selectedSafeZoneIndex == index) -1 else index
                showSafeZoneList(elder, zones)
            }
        }
        addView(checkBox, LinearLayout.LayoutParams(0, dp(54), 0.8f))
        addView(tableCell(zone.zoneType, 12f), LinearLayout.LayoutParams(0, dp(54), 1f))
        addView(tableCell(zone.name, 12f), LinearLayout.LayoutParams(0, dp(54), 1f))
        addView(tableCell(zone.bssid, 11f), LinearLayout.LayoutParams(0, dp(54), 1.8f))
        addView(tableCell(zone.ssid, 11f), LinearLayout.LayoutParams(0, dp(54), 1.6f))
        addView(tableCell(zone.enabled.toString(), 12f), LinearLayout.LayoutParams(0, dp(54), 1f))
        setOnClickListener {
            selectedSafeZoneIndex = index
            showSafeZoneList(elder, zones)
        }
    }

    private fun guardianHeaderRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(4))
        addView(tableCell("선택", 13f, true), LinearLayout.LayoutParams(0, dp(34), 0.8f))
        addView(tableCell("ID", 13f, true), LinearLayout.LayoutParams(0, dp(34), 0.7f))
        addView(tableCell("이름", 13f, true), LinearLayout.LayoutParams(0, dp(34), 1.2f))
        addView(tableCell("전화번호", 13f, true), LinearLayout.LayoutParams(0, dp(34), 1.8f))
        addView(tableCell("등록일", 13f, true), LinearLayout.LayoutParams(0, dp(34), 1.7f))
    }

    private fun guardianRow(index: Int, guardian: GuardianInfo, elder: ElderInfo, guardians: List<GuardianInfo>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(0xFFFFFFFF.toInt(), 8)
        val checkBox = CheckBox(this@MainActivity).apply {
            gravity = Gravity.CENTER
            isChecked = selectedGuardianIndex == index
            setOnClickListener {
                selectedGuardianIndex = if (selectedGuardianIndex == index) -1 else index
                showGuardianList(elder, guardians)
            }
        }
        addView(checkBox, LinearLayout.LayoutParams(0, dp(54), 0.8f))
        addView(tableCell(guardian.id.toString(), 12f), LinearLayout.LayoutParams(0, dp(54), 0.7f))
        addView(tableCell(guardian.name, 12f), LinearLayout.LayoutParams(0, dp(54), 1.2f))
        addView(tableCell(guardian.phone, 12f), LinearLayout.LayoutParams(0, dp(54), 1.8f))
        addView(tableCell(guardian.createdAt.take(10), 11f), LinearLayout.LayoutParams(0, dp(54), 1.7f))
        setOnClickListener {
            selectedGuardianIndex = index
            showGuardianList(elder, guardians)
        }
    }

    private fun tableCell(value: String, size: Float, bold: Boolean = false): TextView = text(value, size, 0xFF111827.toInt(), bold).apply {
        gravity = Gravity.CENTER
        setSingleLine(false)
        setPadding(dp(2), 0, dp(2), 0)
    }

    private fun tile(label: String, mark: String, onClick: () -> Unit): LinearLayout = card().apply {
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        addView(text(mark, 48f, 0xFF2563EB.toInt()).apply { gravity = Gravity.CENTER })
        addView(text(label, 23f, 0xFF111827.toInt(), true).apply { gravity = Gravity.CENTER })
    }

    private fun summaryTile(count: String, label: String, color: Int): LinearLayout = card().apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        addView(text(count, 23f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = oval(color)
        }, LinearLayout.LayoutParams(dp(70), dp(70)))
        addView(text(label, 15f, 0xFF111827.toInt(), true).apply { gravity = Gravity.CENTER })
    }

    private fun batteryBar(percentValue: Int? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val percent = percentValue?.coerceIn(0, 100)
        val filled = (percent ?: 0).toFloat()
        val empty = (100 - (percent ?: 0)).toFloat()
        val fillColor = when {
            percent == null -> 0xFF94A3B8.toInt()
            percent <= 20 -> 0xFFEF4444.toInt()
            percent <= 50 -> 0xFFF59E0B.toInt()
            else -> 0xFF12A150.toInt()
        }
        addView(View(this@MainActivity).apply {
            background = rounded(fillColor, 9)
        }, LinearLayout.LayoutParams(0, dp(18), filled))
        addView(View(this@MainActivity).apply {
            background = rounded(0xFFD5DDE8.toInt(), 9)
        }, LinearLayout.LayoutParams(0, dp(18), empty))
        addView(text(" ${percent?.let { "$it%" } ?: "-"}", 24f, 0xFF111827.toInt()))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(22), dp(22), dp(22))
        background = rounded(0xF7FFFFFF.toInt(), 18)
        elevation = dp(6).toFloat()
    }

    private fun narrowCardParams(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), dp(top), dp(12), 0)
        }

    private fun rowParams(top: Int = 0, height: Int = 56): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)).apply { topMargin = dp(top) }

    private fun gradient(start: Int, end: Int, radius: Int = 0): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            cornerRadius = dp(radius).toFloat()
        }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun roundedStroke(color: Int, radius: Int, stroke: Int): GradientDrawable = rounded(color, radius).apply {
        setStroke(dp(1), stroke)
    }

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
    }
}

