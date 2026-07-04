package com.sf.sfd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors

class BlePeripheralManager(
    private val context: Context,
    private val store: SfdStore,
    private val apiClient: SfdApiClient,
    private val onStatus: (String) -> Unit,
    private val onProvisioned: () -> Unit = {},
    private val onConnectionChanged: (Boolean, String) -> Unit = { _, _ -> }
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val configBuffer = ByteArrayOutputStream()
    private var gattServer: BluetoothGattServer? = null
    private var isAdvertising = false
    private var configReceived = false
    private var configAckMessage = "No config received yet"
    private var registrationState = "WAITING"
    private var serverStatusCode: Int? = null
    private var serverResponseBody = ""
    private var isReceivingConfig = false
    private var expectedConfigBytes = 0
    private var advertiseName = resolveDeviceName()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            onStatus("BLE advertising started as $advertiseName")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            onStatus("BLE advertising failed: ${advertiseErrorText(errorCode)}")
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val connected = newState == BluetoothGatt.STATE_CONNECTED
            val name = device.name ?: device.address ?: "SFC"
            store.saveBleSafeZone(connected, device.address ?: "", name)
            onConnectionChanged(connected, name)
            onStatus("SFC ${device.address} ${if (connected) "connected" else "disconnected"}")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = when (characteristic.uuid.toString()) {
                SfdConfig.DEVICE_INFO_UUID -> deviceInfoJson().toByteArray(Charsets.UTF_8)
                SfdConfig.STATUS_UUID -> statusJson().toByteArray(Charsets.UTF_8)
                else -> ByteArray(0)
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.drop(offset).toByteArray())
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid.toString() == SfdConfig.CONFIG_WRITE_UUID) handleConfigWrite(value)
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val btAdapter = adapter
        if (btAdapter == null) {
            onStatus("Bluetooth adapter not found")
            return
        }
        if (!btAdapter.isEnabled) {
            onStatus("Bluetooth is disabled")
            return
        }
        if (!hasBluetoothPermissions()) {
            onStatus("Bluetooth permissions are required")
            return
        }
        if (isAdvertising) {
            onStatus("BLE advertising is already running")
            return
        }

        advertiseName = resolveDeviceName()
        btAdapter.name = advertiseName
        openGattServerIfNeeded()
        startAdvertising(btAdapter)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        isAdvertising = false
        runCatching { gattServer?.close() }
        gattServer = null
        onStatus("BLE advertising stopped")
    }

    @SuppressLint("MissingPermission")
    private fun openGattServerIfNeeded() {
        if (gattServer != null) return
        val service = BluetoothGattService(UUID.fromString(SfdConfig.SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val deviceInfo = BluetoothGattCharacteristic(
            UUID.fromString(SfdConfig.DEVICE_INFO_UUID),
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val configCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(SfdConfig.CONFIG_WRITE_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val statusCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(SfdConfig.STATUS_UUID),
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(deviceInfo)
        service.addCharacteristic(configCharacteristic)
        service.addCharacteristic(statusCharacteristic)
        gattServer = bluetoothManager.openGattServer(context, gattCallback)
        gattServer?.addService(service)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(btAdapter: BluetoothAdapter) {
        val advertiser = btAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            onStatus("This phone does not support BLE advertising")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID.fromString(SfdConfig.SERVICE_UUID)))
            .build()
        advertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
    }

    private fun handleConfigWrite(value: ByteArray) {
        val marker = value.toString(Charsets.US_ASCII)
        when {
            marker.startsWith("BEGIN:") -> {
                expectedConfigBytes = marker.removePrefix("BEGIN:").toIntOrNull() ?: 0
                configBuffer.reset()
                isReceivingConfig = true
                configReceived = false
                configAckMessage = "Receiving config JSON"
                registrationState = "RECEIVING"
                onStatus("Config transfer started (${expectedConfigBytes} bytes)")
            }
            marker == "END" -> {
                if (!isReceivingConfig) {
                    onStatus("Config END received without BEGIN")
                    return
                }
                isReceivingConfig = false
                val bytes = configBuffer.toByteArray()
                if (expectedConfigBytes > 0 && bytes.size != expectedConfigBytes) {
                    configAckMessage = "Config length mismatch: ${bytes.size}/$expectedConfigBytes"
                    registrationState = "FAILED"
                    onStatus(configAckMessage)
                    return
                }
                processCompleteConfig(bytes.toString(Charsets.UTF_8))
            }
            isReceivingConfig -> {
                configBuffer.write(value)
                onStatus("Config chunk received (${configBuffer.size()}/$expectedConfigBytes bytes)")
            }
            else -> {
                val json = value.toString(Charsets.UTF_8)
                runCatching { JSONObject(json) }
                    .onSuccess { processCompleteConfig(json) }
                    .onFailure { onStatus("Config chunk ignored without BEGIN (${value.size} bytes)") }
            }
        }
    }

    private fun processCompleteConfig(json: String) {
        val parsed = runCatching { JSONObject(json) }
            .onFailure {
                configAckMessage = "Invalid config JSON: ${it.message}"
                registrationState = "FAILED"
                onStatus(configAckMessage)
                return
            }
            .getOrThrow()

        if (parsed.optString("messageType").equals("safeZoneUpdate", ignoreCase = true)) {
            val applied = runCatching { store.applySafeZoneUpdate(json) }.getOrDefault(false)
            configReceived = true
            configAckMessage = if (applied) "SafeZone update received and saved" else "SafeZone update ignored"
            registrationState = if (applied) "SAFEZONE_UPDATED" else "FAILED"
            onStatus(configAckMessage)
            if (applied) onProvisioned()
            return
        }

        configBuffer.reset()
        store.saveConfig(json)
        configReceived = true
        configAckMessage = "Config JSON received and saved"
        registrationState = "REGISTERING"
        serverStatusCode = null
        serverResponseBody = ""
        onStatus("Config received from SFC. Registering device...")
        ioExecutor.execute {
            runCatching {
                val registerJson = createRegisterPayload(json)
                store.appendMessage("REGISTER REQUEST", registerJson)
                val result = apiClient.registerDevice(registerJson)
                serverStatusCode = result.code
                serverResponseBody = result.body
                registrationState = if (result.code in 200..299) "SUCCESS" else "FAILED"
                val label = if (result.code in 200..299) "SERVER ${result.code} registration success" else "SERVER ${result.code} registration failed"
                val message = "$label ${result.body}"
                store.saveRegisterResult(message)
                onStatus(message)
                if (result.code in 200..299) onProvisioned()
            }.onFailure { error ->
                registrationState = "FAILED"
                serverStatusCode = null
                serverResponseBody = error.message ?: error.javaClass.simpleName
                val message = "SERVER registration error: ${error.message ?: error.javaClass.simpleName}"
                store.saveRegisterResult(message)
                onStatus(message)
            }
        }
    }

    private fun createRegisterPayload(configJson: String): String {
        val config = JSONObject(configJson)
        val zones = config.optJSONArray("safeZones")
        val registerZones = JSONArray()
        if (zones != null) {
            for (index in 0 until zones.length()) {
                val zone = zones.optJSONObject(index) ?: continue
                if (!zone.optString("zoneType").equals("WIFI", ignoreCase = true)) continue
                registerZones.put(
                    JSONObject()
                        .put("zoneType", "WIFI")
                        .put("name", zone.optString("name", zone.optString("ssid", "HomeWiFi")))
                        .put("bssid", zone.optString("bssid"))
                        .put("ssid", zone.optString("ssid", zone.optString("apName")))
                )
            }
        }
        return JSONObject()
            .put("deviceId", config.optString("deviceId", SfdConfig.DEFAULT_DEVICE_ID))
            .put("elderName", config.optString("elderName", "elder"))
            .put("guardian", config.optJSONObject("guardian") ?: JSONObject())
            .put("safeZones", registerZones)
            .toString()
    }

    private fun deviceInfoJson(): String = JSONObject()
        .put("deviceId", store.currentState().deviceId)
        .put("serialNo", store.currentState().deviceId)
        .put("name", advertiseName)
        .toString()

    private fun statusJson(): String {
        val state = store.currentState()
        val body = serverResponseBody.take(180)
        return JSONObject()
            .put("deviceId", state.deviceId)
            .put("configReceived", configReceived)
            .put("ackMessage", configAckMessage)
            .put("registrationState", registrationState)
            .put("serverStatusCode", serverStatusCode ?: JSONObject.NULL)
            .put("serverResponseBody", if (body.isBlank()) JSONObject.NULL else body)
            .put("registered", (serverStatusCode ?: 0) in 200..299 || state.lastRegisterResult?.contains("registration success", ignoreCase = true) == true)
            .toString()
    }

    fun currentAdvertiseName(): String = advertiseName

    private fun resolveDeviceName(): String {
        val settingsName = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        val bluetoothName = runCatching { adapter?.name }.getOrNull()
        return listOf(settingsName, bluetoothName, Build.MODEL, SfdConfig.BLE_DEVICE_NAME)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?: SfdConfig.BLE_DEVICE_NAME
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun advertiseErrorText(errorCode: Int): String = when (errorCode) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE(1)"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS(2)"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED(3)"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR(4)"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED(5)"
        else -> "UNKNOWN($errorCode)"
    }
}
