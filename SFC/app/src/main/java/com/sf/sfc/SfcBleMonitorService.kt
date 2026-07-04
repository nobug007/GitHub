package com.sf.sfc

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class SfcBleMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bleManager: BleProvisioningManager
    private var targetAddress: String = ""
    private var isScanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: return
            val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
            if (address == targetAddress || name.contains("SFD", ignoreCase = true)) {
                stopScan()
                bleManager.connectForSafeZone(result.device)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        bleManager = BleProvisioningManager(
            context = this,
            onDeviceFound = {},
            onStatus = {}
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("sfc_monitor", MODE_PRIVATE)
        targetAddress = prefs.getString("last_device_address", "").orEmpty()
        scheduleScan()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopScan()
        bleManager.release()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun scheduleScan() {
        handler.removeCallbacksAndMessages(null)
        handler.post(scanRunnable)
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            startScan()
            handler.postDelayed({ stopScan() }, 12_000L)
            handler.postDelayed(this, 60_000L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasPermissions() || isScanning) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanCallback)
        isScanning = true
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanning) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        isScanning = false
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
