package com.sf.sfc

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log

class SfcBleMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bleManager: BleProvisioningManager
    private var targetAddress: String = ""
    private var isScanning = false

    // Last time an SFD advertisement was seen (BLE presence, for logging/diagnostics).
    private var lastSfdSeenMs = 0L

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // The scan is filtered on the shared SafeFinder SERVICE_UUID (advertised in
            // SFD's scan response), so any result that reaches this callback is an SFD.
            // The stored address is only a secondary hint for logging — Android's rotating
            // RPA means it must never be a requirement.
            val address = result.device.address ?: return
            val firstSeen = lastSfdSeenMs == 0L
            lastSfdSeenMs = SystemClock.elapsedRealtime()
            // Do NOT open a GATT connection here. SFD advertises with a connectable set, so
            // connecting would make its advertising auto-stop and we'd lose continuous presence.
            if (firstSeen || address != targetAddress) {
                Log.d(TAG, "SFD present via service UUID at $address (last known: $targetAddress)")
            }
        }
    }

    private val gpsServer = GpsHttpServer(this)

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
        bleManager = BleProvisioningManager(
            context = this,
            onDeviceFound = {},
            onStatus = {}
        )
        // Always-on GPS endpoint for hotspot-connected devices (SFA/SFD/SFW) to fetch phone GPS+time.
        gpsServer.start()
    }

    private fun promoteToForeground() {
        val notif = notification()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif)
            return
        }
        // Prefer the connectedDevice type (matches the manifest declaration and the
        // BLE keep-alive purpose). Fall back to the two-arg form, and never crash if
        // the system rejects the foreground start (e.g. FGS-from-background limits).
        try {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            return
        } catch (e: Exception) {
            Log.w(TAG, "connectedDevice foreground type rejected: ${e.message ?: e.javaClass.simpleName}")
        }
        try {
            startForeground(NOTIFICATION_ID, notif)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to enter foreground state; stopping monitor service: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun notification(): Notification {
        val channelId = "sfc_ble_monitor"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "SFC BLE Monitor", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SafeFinder Companion")
            .setContentText("기기 연결 상태 모니터링 중")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
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
        gpsServer.stop()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // --- Continuous scanning (10s on / 5s off duty cycle, effectively always on) ---

    private fun scheduleScan() {
        handler.removeCallbacks(scanRunnable)
        handler.post(scanRunnable)
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            startScan()
            // Restart the scanner on a short duty cycle. Stopping briefly avoids the
            // "app scanning too frequently" throttle while staying effectively always-on.
            handler.postDelayed({ stopScan() }, SCAN_ON_MS)
            handler.postDelayed(this, SCAN_CYCLE_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasPermissions() || isScanning) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(SfcConfig.SERVICE_UUID))
                .build()
        )
        // BALANCED (not LOW_POWER): LOW_POWER's ~10% duty cycle frequently misses the SFD/SFA
        // away-advertising bursts, so the hotspot would fail to raise when the device leaves the
        // safe zone. BALANCED scans often enough to catch the advertisement reliably.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanner.startScan(filters, settings, scanCallback)
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

    companion object {
        private const val TAG = "SfcBleMonitorService"
        private const val NOTIFICATION_ID = 3001

        private const val SCAN_ON_MS = 10_000L
        private const val SCAN_CYCLE_MS = 15_000L
    }
}
