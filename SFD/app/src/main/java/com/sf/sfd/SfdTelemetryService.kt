package com.sf.sfd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AlarmManager
import android.content.IntentFilter
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.telephony.SmsManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

class SfdTelemetryService : Service(), SensorEventListener {
    private lateinit var store: SfdStore
    private lateinit var apiClient: SfdApiClient
    private lateinit var payloadFactory: TelemetryPayloadFactory
    private lateinit var wifiStatusReader: WifiStatusReader
    private lateinit var gpsStatusReader: GpsStatusReader
    private lateinit var wifiConnector: WifiConnector
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var latestGyro = GyroSample(System.currentTimeMillis(), 0.0, 0.0, 0.0)
    private var movementX = 0.0
    private var movementY = 0.0
    private var movementZ = 0.0
    private var sampleStarted = false
    private var lastPeriodicReportAt: Long = 0L
    private var gpsModeStartedAt: Long? = null
    private var warningSent = false
    private var emergencySent = false
    private var lastSosTelemetryAt: Long = 0L
    private var lastLocationType: String? = null
    private var safeZoneStateStartedAt: Long? = null
    private var outsideStateStartedAt: Long? = null
    private var pendingZoneVerb: String? = null
    private var pendingZoneVerbStartedAt: Long = System.currentTimeMillis()
    private var lastConfirmedWifiAt: Long = 0L
    private var lastConfirmedWifi: WifiStatus? = null
    private var lastValidGps: GpsStatus? = null
    private var lastValidGpsReadAt: Long = 0L
    private var lastGpsVerbLocation: LocationSnapshot? = null
    private var gpsStayedStartedAt: Long? = null

    private val sampleRunnable = object : Runnable {
        override fun run() {
            collectGyroSample()
            handler.postDelayed(this, SfdConfig.GYRO_SAMPLE_PERIOD_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = SfdStore(applicationContext)
        apiClient = SfdApiClient()
        payloadFactory = TelemetryPayloadFactory(store)
        wifiStatusReader = WifiStatusReader(applicationContext, store)
        gpsStatusReader = GpsStatusReader(applicationContext)
        wifiConnector = WifiConnector(applicationContext, store)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        startForeground(NOTIFICATION_ID, notification("SFD is waiting for configuration"))
        if (store.isConfigured()) startOperationalMode() else log("Setup mode: waiting for SFC BLE provisioning")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (store.isConfigured()) startOperationalMode() else log("Setup mode: device info is not saved yet")
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(sampleRunnable)
        sensorManager.unregisterListener(this)
        scheduleRestart("destroy")
        ioExecutor.shutdownNow()
        log("Telemetry background service stopped")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestart("task removed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE && event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        movementX += kotlin.math.abs(event.values.getOrNull(0)?.toDouble() ?: 0.0)
        movementY += kotlin.math.abs(event.values.getOrNull(1)?.toDouble() ?: 0.0)
        movementZ += kotlin.math.abs(event.values.getOrNull(2)?.toDouble() ?: 0.0)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun startOperationalMode() {
        val wifiMessage = wifiConnector.requestConnection()
        log(wifiMessage)
        registerGyroSensor()
        startLoops()
        updateNotification("Operational mode: ${store.currentState().deviceId}")
    }

    private fun registerGyroSensor() {
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val sensor = gyro ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            log("Gyroscope/accelerometer sensor not found. Zero values will be stored.")
            return
        }
        sensorManager.unregisterListener(this)
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        log("${sensor.name} sensor attached")
    }

    private fun startLoops() {
        if (!sampleStarted) {
            sampleStarted = true
            handler.post(sampleRunnable)
        }
    }

    private fun collectGyroSample() {
        if (!store.isConfigured()) {
            log("Gyro skipped: setup is not complete")
            return
        }
        val sample = GyroSample(
            timestampMs = System.currentTimeMillis(),
            gyX = rounded(movementX),
            gyY = rounded(movementY),
            gyZ = rounded(movementZ)
        )
        latestGyro = sample
        movementX = 0.0
        movementY = 0.0
        movementZ = 0.0
        store.addGyroSample(sample)
        val wifi = effectiveWifiStatus(wifiStatusReader.read())
        val location = locationSnapshot(wifi)
        val state = if (wifi.isAttached) "attached" else "not attached"
        log("Gyro stored gyX=${sample.gyX}, gyY=${sample.gyY}, gyZ=${sample.gyZ}; location=${location.locationType}; Wi-Fi $state ${wifi.apName}")
        val eventSent = updateLocationState(wifi, location)
        if (!eventSent) sendScheduledTelemetry(wifi, location)
        updateNotification("${location.locationType}: ${locationLabel(location)}")
    }

    private fun sendTelemetryReport(wifi: WifiStatus, location: LocationSnapshot, eventType: String? = null) {
        ioExecutor.execute {
            if (!store.isConfigured()) {
                log("Telemetry skipped: setup is not complete")
                return@execute
            }
            val samples = store.recentGyroSamples()
            if (samples.isEmpty()) {
                log("Telemetry skipped: no gyro samples yet")
                return@execute
            }
            runCatching {
                val inSafeZone = isInSafeZone(wifi)
                val zoneVerb = consumeZoneVerb(wifi)
                val normalizedVerb = verbForLocation(zoneVerb.verb, location)
                val durationMs = if (location.locationType == "GPS" && normalizedVerb == "stayed") {
                    val startedAt = gpsStayedStartedAt ?: System.currentTimeMillis().also { gpsStayedStartedAt = it }
                    System.currentTimeMillis() - startedAt
                } else {
                    zoneVerb.durationMs
                }
                notifyVerbChanged(normalizedVerb)
                val payload = payloadFactory.createPayload(
                    samples = samples,
                    location = location,
                    inSafeZone = inSafeZone,
                    battery = currentBatteryLevel(),
                    eventType = eventType ?: eventTypeForVerb(normalizedVerb),
                    verb = normalizedVerb,
                    durationMs = durationMs,
                    deviceStatus = deviceStatusFor(location)
                )
                store.saveOutgoingTelemetry(payload)
                val result = apiClient.sendTelemetry(payload)
                val message = "Telemetry ${result.code}: ${result.body.ifBlank { if (result.ok) "sent" else "empty error body" }}"
                store.saveTelemetryResult(message)
                store.saveServerResponse(message)
                broadcastStateChanged()
                updateNotification("Last telemetry: ${result.code}")
            }.onFailure { error ->
                val message = "Telemetry failed: ${error.message ?: error.javaClass.simpleName}"
                store.saveTelemetryResult(message)
                store.saveServerResponse(message)
                broadcastStateChanged()
                updateNotification("Telemetry failed")
            }
        }
    }

    private fun updateLocationState(wifi: WifiStatus, location: LocationSnapshot): Boolean {
        val now = System.currentTimeMillis()
        val previousLocationType = lastLocationType
        val inSafeZone = isInSafeZone(wifi)
        val wasInSafeZone = previousLocationType == "WIFI" || previousLocationType == "BLE"

        if (previousLocationType == null) {
            lastLocationType = location.locationType
            if (inSafeZone) {
                safeZoneStateStartedAt = now
            } else {
                outsideStateStartedAt = now
                gpsModeStartedAt = now
            }
            return false
        }

        if (!wasInSafeZone && inSafeZone) {
            lastLocationType = location.locationType
            safeZoneStateStartedAt = now
            outsideStateStartedAt = null
            gpsModeStartedAt = null
            warningSent = false
            emergencySent = false
            lastSosTelemetryAt = 0L
            lastPeriodicReportAt = 0L
            log("Safe zone entered: ${locationLabel(location)}")
            sendStatusSms("entered", "entered")
            sendZoneTransitionTelemetry("entered", wifi)
            return true
        }

        if (wasInSafeZone && !inSafeZone) {
            lastLocationType = location.locationType
            safeZoneStateStartedAt = null
            outsideStateStartedAt = now
            gpsModeStartedAt = now
            warningSent = false
            emergencySent = false
            lastSosTelemetryAt = 0L
            lastPeriodicReportAt = 0L
            log("Safe zone exited: ${locationLabel(location)}")
            sendStatusSms("exited", "exited")
            sendZoneTransitionTelemetry("exited", wifi)
            return true
        }

        if (inSafeZone) {
            lastLocationType = location.locationType
            gpsModeStartedAt = null
            warningSent = false
            emergencySent = false
            lastSosTelemetryAt = 0L
            return false
        }

        lastLocationType = location.locationType
        val startedAt = gpsModeStartedAt ?: now.also { gpsModeStartedAt = it }
        val elapsed = now - startedAt

        if (!emergencySent && elapsed >= SfdConfig.EMERGENCY_DELAY_MS) {
            warningSent = true
            emergencySent = true
            sendStatusSms("EMERGENCY", "SOS")
            sendRiskTelemetry("SOS", "EMERGENCY", wifi)
            lastSosTelemetryAt = now
            return true
        }
        if (!warningSent && elapsed >= SfdConfig.WARNING_DELAY_MS) {
            warningSent = true
            sendStatusSms("WARNING", "approached-boundary")
            sendRiskTelemetry("GEOFENCE_EXIT_HINT", "WARNING", wifi)
            return true
        }
        return false
    }

    private fun sendScheduledTelemetry(wifi: WifiStatus, location: LocationSnapshot) {
        val now = System.currentTimeMillis()
        if (location.locationType == "GPS" && emergencySent && !isInSafeZone(wifi)) {
            if (lastSosTelemetryAt == 0L || now - lastSosTelemetryAt >= SfdConfig.SOS_REPEAT_PERIOD_MS) {
                lastSosTelemetryAt = now
                sendRiskTelemetry("SOS", "EMERGENCY", wifi)
            }
            return
        }
        val interval = if (location.locationType == "GPS") SfdConfig.GPS_TELEMETRY_REPORT_PERIOD_MS else SfdConfig.TELEMETRY_REPORT_PERIOD_MS
        if (lastPeriodicReportAt != 0L && now - lastPeriodicReportAt < interval) return
        lastPeriodicReportAt = now
        sendTelemetryReport(wifi, location)
    }

    private fun consumeZoneVerb(wifi: WifiStatus): ZoneVerb {
        val now = System.currentTimeMillis()
        val pending = pendingZoneVerb
        if (pending != null) {
            pendingZoneVerb = null
            return ZoneVerb(pending, now - pendingZoneVerbStartedAt)
        }
        return if (isInSafeZone(wifi)) {
            if (!wifi.isAttached && store.isBleSafeZoneActive()) return ZoneVerb("moved", 0L)
            val startedAt = safeZoneStateStartedAt ?: now.also { safeZoneStateStartedAt = it }
            ZoneVerb("stayed", now - startedAt)
        } else {
            val startedAt = outsideStateStartedAt ?: now.also { outsideStateStartedAt = it }
            ZoneVerb("moved", now - startedAt)
        }
    }

    private fun sendZoneTransitionTelemetry(verb: String, wifi: WifiStatus) {
        ioExecutor.execute {
            if (!store.isConfigured()) return@execute
            val samples = store.recentGyroSamples()
            if (samples.isEmpty()) {
                log("$verb telemetry skipped: no gyro samples yet")
                return@execute
            }
            runCatching {
                val location = locationSnapshot(wifi)
                val normalizedVerb = verbForLocation(verb, location)
                notifyVerbChanged(normalizedVerb)
                val payload = payloadFactory.createPayload(
                    samples = samples,
                    location = location,
                    inSafeZone = isInSafeZone(wifi),
                    battery = currentBatteryLevel(),
                    eventType = eventTypeForVerb(normalizedVerb),
                    verb = normalizedVerb,
                    deviceStatus = deviceStatusFor(location)
                )
                store.saveOutgoingTelemetry(payload)
                val result = apiClient.sendTelemetry(payload)
                val message = "$verb telemetry ${result.code}: ${result.body.ifBlank { if (result.ok) "sent" else "empty error body" }}"
                store.saveTelemetryResult(message)
                store.saveServerResponse(message)
                broadcastStateChanged()
                updateNotification("$verb telemetry: ${result.code}")
            }.onFailure { error ->
                val message = "$verb telemetry failed: ${error.message ?: error.javaClass.simpleName}"
                store.saveTelemetryResult(message)
                store.saveServerResponse(message)
                broadcastStateChanged()
                updateNotification("$verb telemetry failed")
            }
        }
    }

    private fun sendRiskTelemetry(eventType: String, label: String, wifi: WifiStatus) {
        ioExecutor.execute {
            if (!store.isConfigured()) return@execute
            val samples = store.recentGyroSamples()
            if (samples.isEmpty()) {
                log("$label telemetry skipped: no gyro samples yet")
                return@execute
            }
            runCatching {
                val location = locationSnapshot(wifi)
                val zoneVerb = consumeZoneVerb(wifi)
                val normalizedVerb = riskVerb(eventType, zoneVerb.verb, location, samples)
                notifyVerbChanged(normalizedVerb)
                val riskStatus = when (eventType) {
                    "SOS" -> "EMERGENCY"
                    "GEOFENCE_EXIT_HINT" -> "WARNING"
                    else -> label
                }
                val payload = payloadFactory.createPayload(
                    samples = samples,
                    location = location,
                    inSafeZone = isInSafeZone(wifi),
                    battery = currentBatteryLevel(),
                    eventType = eventType,
                    verb = normalizedVerb,
                    durationMs = zoneVerb.durationMs,
                    deviceStatus = riskStatus
                )
                store.saveOutgoingTelemetry(payload)
                val result = apiClient.sendTelemetry(payload)
                val message = "$label telemetry ${result.code}: ${result.body.ifBlank { if (result.ok) "sent" else "empty error body" }}"
                store.saveTelemetryResult(message)
                store.saveServerResponse(message)
                broadcastStateChanged()
                updateNotification("$label telemetry: ${result.code}")
            }.onFailure { error ->
                val message = "$label telemetry failed: ${error.message ?: error.javaClass.simpleName}"
                store.saveTelemetryResult(message)
                store.saveServerResponse(message)
                broadcastStateChanged()
                updateNotification("$label telemetry failed")
            }
        }
    }

    private fun riskVerb(eventType: String, fallbackVerb: String, location: LocationSnapshot, samples: List<GyroSample>): String = when (eventType) {
        "SOS" -> gyroMovementVerb(samples)
        "GEOFENCE_EXIT_HINT" -> "approached-boundary"
        else -> verbForLocation(fallbackVerb, location)
    }

    private fun gyroMovementVerb(samples: List<GyroSample>): String {
        if (samples.isEmpty()) return "stayed"
        val averageMagnitude = samples.map { sqrt(it.gyX * it.gyX + it.gyY * it.gyY + it.gyZ * it.gyZ) }.average()
        return if (averageMagnitude >= SfdConfig.GYRO_MOVEMENT_THRESHOLD) "moved" else "stayed"
    }

    private fun eventTypeForVerb(verb: String): String = when (verb) {
        "exited" -> "GEOFENCE_EXIT_HINT"
        else -> "PERIODIC"
    }

    private fun verbForLocation(verb: String, location: LocationSnapshot): String {
        if (location.locationType == "GPS") return gpsVerb(location, verb)
        return if (location.locationType == "BLE") "moved" else verb
    }

    private fun currentBatteryLevel(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return 0
        return ((level * 100f) / scale).toInt().coerceIn(0, 100)
    }

    private fun effectiveWifiStatus(current: WifiStatus): WifiStatus {
        val now = System.currentTimeMillis()
        if (current.isAttached) {
            lastConfirmedWifiAt = now
            lastConfirmedWifi = current
            return current
        }
        val last = lastConfirmedWifi ?: return current
        if (!current.hasWifiConnection && now - lastConfirmedWifiAt <= SfdConfig.WIFI_SAFEZONE_GRACE_MS) {
            log("Wi-Fi SafeZone reading missed once; keeping last confirmed AP ${last.apName}")
            return last.copy(signal = current.signal.takeIf { it > -127 } ?: last.signal)
        }
        return current
    }

    private fun isInSafeZone(wifi: WifiStatus): Boolean = wifi.isAttached || store.isBleSafeZoneActive()

    private fun locationSnapshot(wifi: WifiStatus): LocationSnapshot = when {
        wifi.isAttached -> LocationSnapshot(
            locationType = "WIFI",
            apName = wifi.apName,
            bssid = wifi.bssid,
            signal = wifi.signal
        )
        store.isBleSafeZoneActive() -> {
            val gps = nonWifiGpsStatus()
            LocationSnapshot(
                locationType = "BLE",
                bluetoothName = store.bleSafeZoneName(),
                bluetoothAddress = store.bleSafeZoneAddress(),
                latitude = gps.latitude,
                longitude = gps.longitude,
                accuracy = gps.accuracy,
                signal = wifi.signal
            )
        }
        else -> {
            val gps = nonWifiGpsStatus()
            LocationSnapshot(
                locationType = "GPS",
                latitude = gps.latitude,
                longitude = gps.longitude,
                accuracy = gps.accuracy,
                signal = wifi.signal
            )
        }
    }

    private fun nonWifiGpsStatus(): GpsStatus = effectiveGpsStatus(gpsStatusReader.read())

    private fun effectiveGpsStatus(current: GpsStatus): GpsStatus {
        val now = System.currentTimeMillis()
        if (current.latitude != null && current.longitude != null) {
            lastValidGps = current
            lastValidGpsReadAt = now
            return current
        }
        val cached = lastValidGps
        return if (cached?.latitude != null &&
            cached.longitude != null &&
            now - lastValidGpsReadAt <= SfdConfig.GPS_LOCATION_CACHE_MS
        ) {
            log("GPS reading missed; keeping last known location ${rounded(cached.latitude)},${rounded(cached.longitude)}")
            cached
        } else {
            current
        }
    }

    private fun gpsVerb(location: LocationSnapshot, fallback: String): String {
        val lat = location.latitude
        val lng = location.longitude
        if (lat == null || lng == null) {
            gpsStayedStartedAt = null
            return fallback
        }
        val previous = lastGpsVerbLocation
        lastGpsVerbLocation = location
        if (previous?.latitude == null || previous.longitude == null) {
            gpsStayedStartedAt = System.currentTimeMillis()
            return fallback
        }
        val distance = distanceMeters(previous.latitude, previous.longitude, lat, lng)
        return if (distance <= SfdConfig.GPS_STAY_DISTANCE_M) {
            if (gpsStayedStartedAt == null) gpsStayedStartedAt = System.currentTimeMillis()
            "stayed"
        } else {
            gpsStayedStartedAt = null
            "moved"
        }
    }

    private fun distanceMeters(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(toLat - fromLat)
        val dLng = Math.toRadians(toLng - fromLng)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) *
            sin(dLng / 2) * sin(dLng / 2)
        return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun deviceStatusFor(location: LocationSnapshot): String {
        if (location.locationType == "GPS" && (location.latitude == null || location.longitude == null)) return "GPS_WEAK"
        if (location.locationType == "GPS") {
            val elapsed = gpsModeStartedAt?.let { System.currentTimeMillis() - it } ?: 0L
            if (elapsed >= SfdConfig.EMERGENCY_DELAY_MS) return "EMERGENCY"
            if (elapsed >= SfdConfig.WARNING_DELAY_MS) return "WARNING"
        }
        return "NORMAL"
    }

    private fun locationLabel(location: LocationSnapshot): String {
        return when (location.locationType) {
            "WIFI" -> location.apName.ifBlank { store.firstWifiSsid().ifBlank { location.bssid.ifBlank { store.firstWifiZoneName() } } }
            "BLE" -> location.bluetoothName.ifBlank { location.bluetoothAddress.ifBlank { "SFC" } }
            else -> {
                val lat = location.latitude
                val lng = location.longitude
                if (lat != null && lng != null) "$lat,$lng" else "GPS unavailable"
            }
        }
    }

    private fun notifyVerbChanged(verb: String) {
        val previous = store.lastVerb()
        if (previous == verb) return
        store.saveLastVerb(verb)
    }

    private fun sendStatusSms(level: String, reason: String) {
        sendSms(level, reason)
    }

    private fun sendSms(label: String, body: String) {
        val phones = store.guardianPhones()
        if (phones.isEmpty()) return
        phones.forEach { phone ->
            runCatching {
                SmsManager.getDefault().sendTextMessage(phone, null, "SafeFinder ${store.currentState().deviceId} $body", null, null)
                log("SMS sent to $phone for $label")
            }.onFailure { log("SMS failed for $label to $phone: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun log(message: String) {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        store.appendLog("[$time] $message")
        broadcastStateChanged()
    }

    private fun broadcastStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun scheduleRestart(reason: String) {
        if (!::store.isInitialized || !store.isConfigured()) return
        runCatching {
            val intent = Intent(this, SfdRestartReceiver::class.java)
                .setAction(ACTION_RESTART_TELEMETRY)
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                2007,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + 10_000L
            val alarmManager = getSystemService(AlarmManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
            store.appendLog("Telemetry restart scheduled after $reason")
        }
    }

    private fun notification(text: String): Notification {
        val channelId = "sfd_telemetry"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "SFD Telemetry", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("SFD Test Device")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun rounded(value: Double): Double = round(value * 100.0) / 100.0

    private data class ZoneVerb(
        val verb: String,
        val durationMs: Long
    )

    companion object {
        const val ACTION_STATE_CHANGED = "com.sf.sfd.STATE_CHANGED"
        const val ACTION_RESTART_TELEMETRY = "com.sf.sfd.RESTART_TELEMETRY"
        private const val NOTIFICATION_ID = 1007
    }
}
