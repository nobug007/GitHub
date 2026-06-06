package com.safefinder.testapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

class AutoSendService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var collector: DataCollector
    private var serverUrl: String = SafeFinderConfig.DEFAULT_SERVER_URL

    private val sendRunnable = object : Runnable {
        override fun run() {
            executor.execute {
                try {
                    val payload = collector.buildPayload("PERIODIC")
                    SafeFinderClient().send(serverUrl, payload)
                    Log.i(TAG, "Auto payload sent: seq=${payload.seq}")
                } catch (error: Exception) {
                    Log.e(TAG, "Auto payload send failed", error)
                }
            }
            handler.postDelayed(this, SafeFinderConfig.AUTO_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        collector = DataCollector(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: SafeFinderConfig.DEFAULT_SERVER_URL
        startForeground(NOTIFICATION_ID, notification())
        handler.removeCallbacks(sendRunnable)
        handler.post(sendRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(sendRunnable)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "SafeFinder Auto", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("SafeFinder Auto")
            .setContentText("Sending WiFi and GPS readings every 10 minutes")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_SERVER_URL = "serverUrl"
        private const val TAG = "AutoSendService"
        private const val CHANNEL_ID = "safe_finder_auto"
        private const val NOTIFICATION_ID = 1001
    }
}
