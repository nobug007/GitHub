package com.sf.hotspot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Background foreground-service that flips the desired hotspot state every
 * TOGGLE_PERIOD_MS and asks HotspotController to apply it. This is the test
 * harness: with the accessibility service enabled it should turn the phone
 * Mobile Hotspot on and off on a 10s cadence with no user interaction.
 */
class HotspotToggleService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var desired = false
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            desired = !desired
            Log.d(TAG, "tick -> request hotspot desired=$desired")
            HotspotController.request(this@HotspotToggleService, desired)
            handler.postDelayed(this, TOGGLE_PERIOD_MS)
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
            handler.postDelayed(tick, 2_000L)
            Log.d(TAG, "toggle loop started (period=${TOGGLE_PERIOD_MS}ms)")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        Log.d(TAG, "toggle loop stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground() {
        val channelId = "hotspot_toggle"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Hotspot Toggle Test", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("핫스팟 토글 테스트 실행 중")
            .setContentText("1분마다 핫스팟을 켜고 끕니다")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    companion object {
        private const val TAG = "HotspotToggle"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_STOP = "com.sf.hotspot.STOP"
        const val TOGGLE_PERIOD_MS = 60_000L
    }
}
