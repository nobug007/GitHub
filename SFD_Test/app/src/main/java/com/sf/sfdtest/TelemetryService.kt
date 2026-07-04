package com.sf.sfdtest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SmsManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.round

class TelemetryService : Service(), SensorEventListener {
    private lateinit var store: TelemetryStore
    private lateinit var readers: DeviceReaders
    private lateinit var payloadFactory: PayloadFactory
    private lateinit var apiClient: ApiClient
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var latestGyro = Triple(0.0, 0.0, 0.0)
    private var outOfSafeZoneSince: Long? = null
    private var warningSent = false
    private var emergencySent = false

    private val periodicRunnable = object : Runnable {
        override fun run() {
            sendPeriodicTelemetry()
            handler.postDelayed(this, AppConfig.TELEMETRY_PERIOD_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = TelemetryStore(applicationContext)
        readers = DeviceReaders(applicationContext, store)
        store.ensureInitialDefaults(readers.currentWifiName(), readers.ownPhoneNumber())
        payloadFactory = PayloadFactory(store)
        apiClient = ApiClient()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        startForeground(NOTIFICATION_ID, notification("SFD_Test telemetry running"))
        readers.startCellSignalUpdates()
        registerGyro()
        handler.post(periodicRunnable)
        log("Telemetry service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(periodicRunnable)
        handler.post(periodicRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(periodicRunnable)
        sensorManager.unregisterListener(this)
        ioExecutor.shutdownNow()
        log("Telemetry service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        latestGyro = Triple(
            rounded(event.values.getOrNull(0)?.toDouble() ?: 0.0),
            rounded(event.values.getOrNull(1)?.toDouble() ?: 0.0),
            rounded(event.values.getOrNull(2)?.toDouble() ?: 0.0)
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun registerGyro() {
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro == null) {
            log("Gyroscope not found. Zero gyro values will be used.")
            return
        }
        sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_NORMAL)
        log("Gyroscope attached")
    }

    private fun sendPeriodicTelemetry() {
        ioExecutor.execute {
            val inSafeZone = readers.inSafeZone()
            updateSafeZoneTimers(inSafeZone)
            val eventType = if (inSafeZone) "PERIODIC" else "GEOFENCE_EXIT_HINT"
            sendTelemetryEvent(
                eventType = eventType,
                inSafeZone = inSafeZone,
                notificationText = if (inSafeZone) "SafeZone: ${readers.currentWifiName()}" else "Outside SafeZone"
            )
            broadcastChanged()
        }
    }

    private fun sendTelemetryEvent(eventType: String, inSafeZone: Boolean, notificationText: String) {
        val payload = payloadFactory.create(
            seq = store.nextSeq(),
            inSafeZone = inSafeZone,
            apName = readers.currentWifiName().ifBlank { store.safeZoneApName() },
            battery = readers.batteryPercent(),
            signal = readers.signal(),
            location = if (inSafeZone) null else readers.lastLocation(),
            gyroData = gyroPoints(),
            eventType = eventType
        )
        store.saveRequest(payload)
        runCatching {
            val result = apiClient.sendTelemetry(payload)
            val response = "$eventType HTTP ${result.code}: ${result.body.ifBlank { if (result.ok) "sent" else "empty body" }}"
            store.saveResponse(response)
            log(response)
            updateNotification(notificationText)
        }.onFailure { error ->
            val response = "$eventType telemetry failed: ${error.message ?: error.javaClass.simpleName}"
            store.saveResponse(response)
            log(response)
            updateNotification("Telemetry failed")
        }
    }

    private fun updateSafeZoneTimers(inSafeZone: Boolean) {
        val now = System.currentTimeMillis()
        if (inSafeZone) {
            if (outOfSafeZoneSince != null) log("SafeZone re-entered")
            outOfSafeZoneSince = null
            warningSent = false
            emergencySent = false
            return
        }
        val since = outOfSafeZoneSince ?: now.also {
            outOfSafeZoneSince = it
            log("SafeZone exit detected")
        }
        val elapsed = now - since
        if (!warningSent && elapsed >= AppConfig.WARNING_DELAY_MS) {
            warningSent = true
            sendTelemetryEvent(
                eventType = "GEOFENCE_EXIT_HINT",
                inSafeZone = false,
                notificationText = "Warning: outside SafeZone"
            )
            sendSms("[SafeFinder WARNING] ${store.deviceId()} has been outside SafeZone for 5 minutes.")
        }
        if (!emergencySent && elapsed >= AppConfig.EMERGENCY_DELAY_MS) {
            emergencySent = true
            sendTelemetryEvent(
                eventType = "SOS",
                inSafeZone = false,
                notificationText = "Emergency: outside SafeZone"
            )
            sendSms("[SafeFinder EMERGENCY] ${store.deviceId()} has been outside SafeZone for 30 minutes. Please check immediately.")
        }
    }

    private fun sendSms(message: String) {
        runCatching {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            val phones = store.familyPhones()
            if (phones.isEmpty()) {
                log("SMS skipped: no Family phone registered")
                return
            }
            phones.forEach { phone ->
                manager.sendTextMessage(phone, null, message, null, null)
                log("SMS sent to $phone: $message")
            }
        }.onFailure { error ->
            log("SMS failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun gyroPoints(): List<GyroPoint> {
        val (x, y, z) = latestGyro
        return (9 downTo 0).map { step ->
            GyroPoint(
                offsetMs = step * 1000,
                gyX = rounded(x),
                gyY = rounded(y),
                gyZ = rounded(z)
            )
        }
    }

    private fun log(message: String) {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        store.appendLog("[$time] $message")
        broadcastChanged()
    }

    private fun broadcastChanged() {
        sendBroadcast(Intent(ACTION_CHANGED).setPackage(packageName))
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val channelId = "sfd_test_telemetry"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "SFD Test Telemetry", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("SFD Test Telemetry")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun rounded(value: Double): Double = round(value * 100.0) / 100.0

    companion object {
        const val ACTION_CHANGED = "com.sf.sfdtest.ACTION_CHANGED"
        private const val NOTIFICATION_ID = 1208
    }
}
