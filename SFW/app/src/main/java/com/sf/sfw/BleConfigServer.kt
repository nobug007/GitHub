package com.sf.sfw

import android.Manifest
import android.annotation.SuppressLint
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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

@SuppressLint("MissingPermission")
class BleConfigServer(
    private val context: Context,
    private val onStateChanged: (Boolean) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private val apiClient = SfwApiClient()
    private var gattServer: BluetoothGattServer? = null
    private var running = false
    private val incomingConfig = ByteArrayOutputStream()
    private var isReceivingConfig = false
    private var expectedConfigBytes: Int? = null
    private var receivedConfigBytes = 0
    private var lastStatus = "Idle"
    private var configReceived = false
    private var registrationState = "WAITING"
    private var serverStatusCode: Int? = null
    private var serverResponseBody = ""

    private val serviceUuid: UUID = UUID.fromString(SfwConfig.SERVICE_UUID)
    private val deviceInfoUuid: UUID = UUID.fromString(SfwConfig.DEVICE_INFO_UUID)
    private val configWriteUuid: UUID = UUID.fromString(SfwConfig.CONFIG_WRITE_UUID)
    private val statusUuid: UUID = UUID.fromString(SfwConfig.STATUS_UUID)

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = when (characteristic.uuid) {
                deviceInfoUuid -> "SFW;name=${SfwConfig.BLE_DEVICE_NAME};config=ble"
                statusUuid -> statusJson()
                else -> ""
            }.toByteArray(Charsets.UTF_8)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.drop(offset).toByteArray())
        }

        override fun onCharacteristicWriteRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid != configWriteUuid) {
                sendWriteResponse(device, requestId, responseNeeded, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
                return
            }

            // Decode only for marker detection; payload bytes are accumulated raw and
            // decoded to UTF-8 once when the transfer completes (multi-byte safe).
            val marker = value.toString(Charsets.UTF_8)
            when {
                marker.startsWith("BEGIN:") -> {
                    incomingConfig.reset()
                    expectedConfigBytes = marker.removePrefix("BEGIN:").trim().toIntOrNull()
                    receivedConfigBytes = 0
                    isReceivingConfig = true
                    updateStatus("Receiving config 0/${expectedConfigBytes ?: "?"} bytes")
                    sendWriteResponse(device, requestId, responseNeeded, BluetoothGatt.GATT_SUCCESS)
                    return
                }
                marker == "END" -> {
                    if (isReceivingConfig) {
                        isReceivingConfig = false
                        val expected = expectedConfigBytes
                        if (expected != null && incomingConfig.size() != expected) {
                            registrationState = "FAILED"
                            updateStatus("Config length mismatch: ${incomingConfig.size()}/$expected bytes")
                            incomingConfig.reset()
                            expectedConfigBytes = null
                            receivedConfigBytes = 0
                        } else {
                            trySaveCompleteJson(force = true)
                        }
                    }
                    // If a config was already accepted mid-stream (buffer consumed),
                    // END is a no-op acknowledgement.
                    sendWriteResponse(device, requestId, responseNeeded, BluetoothGatt.GATT_SUCCESS)
                    return
                }
                !isReceivingConfig && offset == 0 && looksLikeJsonStart(marker) -> {
                    // BEGIN-less raw JSON fallback: only when idle, never mid-transfer.
                    incomingConfig.reset()
                    expectedConfigBytes = null
                    receivedConfigBytes = 0
                    isReceivingConfig = true
                }
            }

            incomingConfig.write(value)
            receivedConfigBytes += value.size
            updateStatus("Receiving config $receivedConfigBytes/${expectedConfigBytes ?: "?"} bytes")

            if (incomingConfig.size() > MAX_CONFIG_BYTES) {
                incomingConfig.reset()
                expectedConfigBytes = null
                receivedConfigBytes = 0
                isReceivingConfig = false
                updateStatus("Config too large")
                sendWriteResponse(device, requestId, responseNeeded, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH)
                return
            }

            val expected = expectedConfigBytes
            if (expected == null || receivedConfigBytes >= expected) {
                trySaveCompleteJson(force = false)
            }
            sendWriteResponse(device, requestId, responseNeeded, BluetoothGatt.GATT_SUCCESS)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            running = true
            updateStatus("BLE config ready")
            onStateChanged(true)
        }

        override fun onStartFailure(errorCode: Int) {
            running = false
            updateStatus("Advertise failed: $errorCode")
            onStateChanged(false)
        }
    }

    fun canUseBle(): Boolean {
        if (adapter == null || !adapter.isEnabled || !adapter.isMultipleAdvertisementSupported) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun isRunning(): Boolean = running

    fun start() {
        if (!canUseBle()) {
            running = false
            updateStatus("BLE unavailable")
            onStateChanged(false)
            return
        }

        stop()
        runCatching { adapter.name = SfwConfig.BLE_DEVICE_NAME }
        incomingConfig.reset()
        isReceivingConfig = false
        expectedConfigBytes = null
        receivedConfigBytes = 0
        configReceived = false
        registrationState = "WAITING"
        serverStatusCode = null
        serverResponseBody = ""

        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    deviceInfoUuid,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )
            )
            addCharacteristic(
                BluetoothGattCharacteristic(
                    configWriteUuid,
                    BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
                )
            )
            addCharacteristic(
                BluetoothGattCharacteristic(
                    statusUuid,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )
            )
        }

        gattServer = bluetoothManager.openGattServer(context, gattCallback).also { server ->
            server?.addService(service)
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()

        adapter.bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    fun stop() {
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { gattServer?.close() }
        gattServer = null
        running = false
        onStateChanged(false)
    }

    private fun sendWriteResponse(
        device: android.bluetooth.BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
        status: Int
    ) {
        if (responseNeeded) gattServer?.sendResponse(device, requestId, status, 0, null)
    }

    private fun looksLikeJsonStart(value: String): Boolean = value.trimStart().startsWith("{")

    private fun trySaveCompleteJson(force: Boolean) {
        val json = incomingConfig.toByteArray().toString(Charsets.UTF_8).trim()
        if (!json.startsWith("{") || !json.endsWith("}")) {
            if (force) updateStatus("Config receive incomplete")
            return
        }

        runCatching { JSONObject(json) }
            .onSuccess { obj ->
                incomingConfig.reset()
                isReceivingConfig = false
                expectedConfigBytes = null
                receivedConfigBytes = 0

                // A later "safeZoneUpdate" (zone add/edit/remove from SFC) must be MERGED into the
                // existing config — not saved as the whole config, which would wipe the provisioning
                // data and drop the device out of operational mode. The zone is already on the
                // server (SFC created it), so no re-registration is needed here.
                if (obj.optString("messageType").equals("safeZoneUpdate", ignoreCase = true)) {
                    val applied = SfwStore(context).applySafeZoneUpdate(json)
                    updateStatus(if (applied) "Safe zone update applied" else "Safe zone update ignored")
                    if (applied) startOperationalService()
                    return@onSuccess
                }

                context.getSharedPreferences(SfwConfig.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(SfwConfig.PREF_CONFIG_JSON, json)
                    .putString(SfwConfig.PREF_CONFIG_UPDATED_AT, Instant.now().toString())
                    .apply()
                configReceived = true
                registrationState = "REGISTERING"
                serverStatusCode = null
                serverResponseBody = ""
                updateStatus("Config synced. Registering server...")
                registerConfigWithServer(json)
            }
            .onFailure {
                if (force) updateStatus("Config JSON invalid")
            }
    }

    private fun registerConfigWithServer(json: String) {
        Thread {
            runCatching { apiClient.registerDevice(json) }
                .onSuccess { result ->
                    serverStatusCode = result.responseCode
                    serverResponseBody = result.responseBody
                    registrationState = when {
                        result.responseCode in 200..299 -> "REGISTERED"
                        result.responseBody.contains("이미 등록된 디바이스입니다") -> "ALREADY_REGISTERED"
                        else -> "FAILED"
                    }
                    updateStatus("Server register $registrationState (${result.responseCode})")
                    if (registrationState == "REGISTERED" || registrationState == "ALREADY_REGISTERED") {
                        startOperationalService()
                    }
                }
                .onFailure { error ->
                    serverStatusCode = null
                    serverResponseBody = error.message ?: error.javaClass.simpleName
                    registrationState = "FAILED"
                    updateStatus("Server register failed")
                }
        }.start()
    }

    // Registration succeeded (or the device was already known): switch to the
    // SFD-style operational loop. Deliberately does not touch lastStatus on
    // success so SFC still reads the register ack it expects.
    private fun startOperationalService() {
        runCatching {
            context.startForegroundService(Intent(context, SfwTelemetryService::class.java))
        }.onFailure {
            SfwStore(context).appendLog("Telemetry service start failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun statusJson(): String {
        val prefs = context.getSharedPreferences(SfwConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val savedJson = prefs.getString(SfwConfig.PREF_CONFIG_JSON, "").orEmpty()
        val updatedAt = prefs.getString(SfwConfig.PREF_CONFIG_UPDATED_AT, "").orEmpty()
        return JSONObject()
            .put("configReceived", configReceived || savedJson.isNotBlank())
            .put("ackMessage", lastStatus)
            .put("registrationState", registrationState)
            .put("serverStatusCode", serverStatusCode ?: JSONObject.NULL)
            .put("serverResponseBody", serverResponseBody.take(180))
            .put("updatedAt", updatedAt)
            .toString()
    }

    private fun updateStatus(message: String) {
        lastStatus = message
        onStatusChanged(message)
    }

    companion object {
        private const val MAX_CONFIG_BYTES = 64 * 1024
    }
}
