package com.sf.sfc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.UUID

@SuppressLint("MissingPermission")
class BleProvisioningManager(
    private val context: Context,
    private val onDeviceFound: (ScannedDevice) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onRegistrationConfirmed: () -> Unit = {}
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var pendingJson: String? = null
    private var statusReadsRemaining = 0

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.scanRecord?.deviceName ?: result.device.name ?: "Unknown BLE"
            val address = result.device.address ?: ""
            if (address.isNotBlank()) {
                onDeviceFound(ScannedDevice(result.device, deviceName, address, result.rssi))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            onStatus("BLE scan failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatus("Connected. Discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onStatus("Disconnected")
                closeGatt()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("Service discovery failed: $status")
                return
            }
            val json = pendingJson
            if (json == null) {
                onStatus("Connected. No payload to send.")
                return
            }
            writeConfigInChunks(gatt, json)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("Config write failed: $status")
                return
            }
            if (writeQueue.isEmpty()) {
                onStatus("Config bytes sent. Waiting for SFD ACK...")
                pendingJson = null
                statusReadsRemaining = 15
                mainHandler.postDelayed({ readSfdStatus(gatt) }, 300)
            } else {
                writeNextChunk(gatt)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid.toString() != SfcConfig.STATUS_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("SFD status read failed: $status")
                mainHandler.postDelayed({ closeGatt() }, 500)
                return
            }
            val text = characteristic.value?.toString(Charsets.UTF_8).orEmpty()
            handleStatusJson(text)
            statusReadsRemaining -= 1
            if (statusReadsRemaining > 0) {
                mainHandler.postDelayed({ readSfdStatus(gatt) }, 1_000)
            } else {
                mainHandler.postDelayed({ closeGatt() }, 500)
            }
        }
    }

    private val writeQueue = ArrayDeque<ByteArray>()

    fun canUseBle(): Boolean {
        if (adapter == null || !adapter.isEnabled) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun startScan() {
        if (!canUseBle()) {
            onStatus("Bluetooth permissions or Bluetooth ON are required")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onStatus("BLE scanner is not available")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        onStatus("Scanning BLE devices...")
    }

    fun stopScan() {
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
        onStatus("Scan stopped")
    }

    fun sendConfig(device: BluetoothDevice, json: String) {
        if (!canUseBle()) {
            onStatus("Bluetooth permissions or Bluetooth ON are required")
            return
        }
        stopScan()
        pendingJson = json
        writeQueue.clear()
        closeGatt()
        onStatus("Connecting to ${device.address}...")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun connectForSafeZone(device: BluetoothDevice) {
        if (!canUseBle()) {
            onStatus("Bluetooth permissions or Bluetooth ON are required")
            return
        }
        stopScan()
        pendingJson = null
        writeQueue.clear()
        closeGatt()
        onStatus("Connecting SafeZone BLE to ${device.address}...")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun release() {
        stopScan()
        closeGatt()
    }

    private fun writeConfigInChunks(gatt: BluetoothGatt, json: String) {
        val service: BluetoothGattService? = gatt.getService(UUID.fromString(SfcConfig.SERVICE_UUID))
        val characteristic = service?.getCharacteristic(UUID.fromString(SfcConfig.CONFIG_WRITE_UUID))
        if (characteristic == null) {
            onStatus("SFD config characteristic not found")
            return
        }

        val bytes = json.toByteArray(Charsets.UTF_8)
        val chunkSize = 20
        writeQueue.add("BEGIN:${bytes.size}".toByteArray(Charsets.UTF_8))
        var index = 0
        while (index < bytes.size) {
            writeQueue.add(bytes.copyOfRange(index, minOf(index + chunkSize, bytes.size)))
            index += chunkSize
        }
        writeQueue.add("END".toByteArray(Charsets.UTF_8))
        onStatus("Writing config JSON (${bytes.size} bytes)...")
        writeNextChunk(gatt)
    }

    private fun writeNextChunk(gatt: BluetoothGatt) {
        val service = gatt.getService(UUID.fromString(SfcConfig.SERVICE_UUID))
        val characteristic = service?.getCharacteristic(UUID.fromString(SfcConfig.CONFIG_WRITE_UUID))
        val next = writeQueue.removeFirstOrNull()
        if (characteristic == null || next == null) {
            onStatus("Config write queue is empty or characteristic missing")
            return
        }
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            characteristic.value = next
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun readSfdStatus(gatt: BluetoothGatt) {
        val service = gatt.getService(UUID.fromString(SfcConfig.SERVICE_UUID))
        val characteristic = service?.getCharacteristic(UUID.fromString(SfcConfig.STATUS_UUID))
        if (characteristic == null) {
            onStatus("SFD status characteristic not found")
            closeGatt()
            return
        }
        gatt.readCharacteristic(characteristic)
    }

    private fun handleStatusJson(text: String) {
        runCatching {
            val json = JSONObject(text)
            val received = json.optBoolean("configReceived", false)
            val ack = json.optString("ackMessage", "")
            val registrationState = json.optString("registrationState", "WAITING")
            val serverCode = if (json.isNull("serverStatusCode")) "waiting" else json.optInt("serverStatusCode").toString()
            val serverBody = json.optString("serverResponseBody", "")
            if (received) {
                onStatus("SFD ACK: $ack")
                if (registrationState.equals("REGISTERED", ignoreCase = true) ||
                    registrationState.equals("SUCCESS", ignoreCase = true) ||
                    registrationState.equals("ALREADY_REGISTERED", ignoreCase = true) ||
                    serverCode == "200" ||
                    serverBody.contains("이미 등록된 디바이스입니다")
                ) {
                    onRegistrationConfirmed()
                }
            } else {
                onStatus("SFD ACK waiting")
            }
            onStatus("SFD server state: $registrationState, code=$serverCode")
            if (serverBody.isNotBlank()) onStatus("SFD server body: $serverBody")
        }.onFailure {
            onStatus("SFD status: ${text.ifBlank { "empty" }}")
        }
    }

    private fun closeGatt() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }
}

