package com.sf.hotspot

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Foreground service that replaces the old blind 1-minute toggle: it scans BLE
 * for the tablet's hotspot-trigger advertisement (shared service UUID) and drives
 * the hotspot on presence.
 *   - advertisement seen        -> request hotspot ON  (rising edge)
 *   - advertisement absent >grace -> request hotspot OFF (falling edge)
 * The actual toggle is performed by HotspotAccessibilityService via
 * HotspotController, exactly as before — only the trigger changed.
 */
class BleTriggerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var hotspotOn = false
    private var lastSeenAt = 0L

    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private var scanner: BluetoothLeScanner? = null

    private val presenceCheck = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (hotspotOn && lastSeenAt != 0L && now - lastSeenAt > ABSENCE_GRACE_MS) {
                Log.d(TAG, "advertisement absent ${ABSENCE_GRACE_MS}ms -> hotspot OFF")
                setHotspot(false)
            }
            handler.postDelayed(this, PRESENCE_CHECK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            startScan()
            handler.postDelayed(presenceCheck, PRESENCE_CHECK_MS)
            Log.d(TAG, "BLE trigger started")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(presenceCheck)
        stopScan()
        Log.d(TAG, "BLE trigger stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            updateNotification("블루투스가 꺼져 있습니다 — 켠 뒤 다시 시작하세요")
            return
        }
        val le = adapter.bluetoothLeScanner ?: return
        scanner = le
        val filter = ScanFilter.Builder()
            .setServiceUuid(BleConstants.HOTSPOT_TRIGGER_PARCEL_UUID)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { le.startScan(listOf(filter), settings, scanCallback) }
            .onFailure { updateNotification("스캔 시작 실패: ${it.message ?: it.javaClass.simpleName}") }
        updateNotification("태블릿 BLE 광고를 찾는 중…")
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        runCatching { scanner?.stopScan(scanCallback) }
        scanner = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            lastSeenAt = SystemClock.elapsedRealtime()
            if (!hotspotOn) {
                Log.d(TAG, "advertisement detected (rssi=${result.rssi}) -> hotspot ON")
                setHotspot(true)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            updateNotification("스캔 실패(코드 $errorCode)")
            Log.w(TAG, "scan failed: $errorCode")
        }
    }

    private fun setHotspot(enable: Boolean) {
        hotspotOn = enable
        HotspotController.request(this, enable)
        updateNotification(
            if (enable) "태블릿 BLE 감지 → 핫스팟 켜는 중" else "광고 사라짐 → 핫스팟 끄는 중"
        )
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "BLE Hotspot Trigger", NotificationManager.IMPORTANCE_LOW)
            )
        }
        startForegroundWithNotification(buildNotification("BLE 감지 대기 중"))
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BLE 감지 → 핫스팟")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun startForegroundWithNotification(notif: Notification) {
        // connectedDevice matches BLE scanning; fall back cleanly if the type is
        // rejected so the service never crashes the way an unguarded start would.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                return
            } catch (e: Exception) {
                Log.w(TAG, "connectedDevice FGS rejected: ${e.message}")
                runCatching { startForeground(NOTIFICATION_ID, notif) }.onFailure { stopSelf() }
            }
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    companion object {
        private const val TAG = "BleTrigger"
        private const val CHANNEL_ID = "ble_hotspot_trigger"
        private const val NOTIFICATION_ID = 4102
        const val ACTION_STOP = "com.sf.hotspot.BLE_STOP"
        // How long the advertisement must be gone before we turn the hotspot off.
        const val ABSENCE_GRACE_MS = 15_000L
        private const val PRESENCE_CHECK_MS = 3_000L
    }
}
