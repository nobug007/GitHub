package com.sf.sfw

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Operational-mode loop for SFW, ported from SFD's SfdTelemetryService and
 * adapted for Wear OS:
 * - no WifiConnector (Wear manages WiFi itself; passive safe-zone detection only)
 * - no BLE safe-zone concept (SFW has no SFC-proximity feature)
 * - SMS degrades to log-only when the watch has no telephony
 * Everything else — 60s sample loop, WiFi grace, GPS fallback, WARNING at 5min /
 * SOS at 30min with 60s SOS repeats, persisted escalation timers — matches SFD.
 */
class SfwTelemetryService : Service(), SensorEventListener {
    private lateinit var store: SfwStore
    private lateinit var apiClient: SfwApiClient
    private lateinit var payloadFactory: TelemetryPayloadFactory
    private lateinit var wifiStatusReader: WifiStatusReader
    private lateinit var gpsStatusReader: GpsStatusReader
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    // Wear OS aggressively enters Doze the moment the screen turns off, which power-saves the Wi-Fi
    // radio (dropping the hotspot/home connection) and suspends our timers — the watch was seen
    // connecting/disconnecting repeatedly whenever the screen slept. A high-perf WifiLock keeps the
    // Wi-Fi link up and a partial WakeLock keeps the CPU running so the telemetry loop survives Doze.
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var movementX = 0.0
    private var movementY = 0.0
    private var movementZ = 0.0
    private var sampleStarted = false
    private var lastPeriodicReportAt: Long = 0L
    private var gpsModeStartedAt: Long? = null
    private var warningSent = false
    private var emergencySent = false
    private var lastSosTelemetryAt: Long = 0L
    private var lastTransitionSmsAt: Long = 0L
    private var lastAnySmsAt: Long = 0L   // hard floor across ALL alert SMS (anti-spam safety net)
    private var awayAdvertising = false
    private val awayAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            awayAdvertising = false
            log("이탈 BLE 광고 실패: $errorCode")
        }
    }
    private var lastLocationType: String? = null
    private var safeZoneStateStartedAt: Long? = null
    private var outsideStateStartedAt: Long? = null
    private var lastConfirmedWifiAt: Long = 0L
    private val serviceStartedAt: Long = System.currentTimeMillis()
    private var lastConfirmedWifi: WifiStatus? = null
    private var wifiHoldSince: Long = 0L
    private var movedSinceWifiLoss = false
    private var latestGyro = GyroSample(System.currentTimeMillis(), 0.0, 0.0, 0.0)
    private var lastValidGps: GpsStatus? = null
    private var lastValidGpsReadAt: Long = 0L
    private var lastGpsVerbLocation: LocationSnapshot? = null
    private var gpsStayedStartedAt: Long? = null
    private var foregroundType = 0

    private val sampleRunnable = object : Runnable {
        override fun run() {
            collectGyroSample()
            handler.postDelayed(this, SfwConfig.GYRO_SAMPLE_PERIOD_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = SfwStore(applicationContext)
        restoreEscalationState()
        apiClient = SfwApiClient()
        payloadFactory = TelemetryPayloadFactory(store)
        wifiStatusReader = WifiStatusReader(applicationContext, store)
        gpsStatusReader = GpsStatusReader(applicationContext)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        promoteToForeground("SFW is waiting for configuration")
        if (store.isConfigured()) startOperationalMode() else log("Setup mode: waiting for SFC BLE provisioning")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundType != ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) {
            promoteToForeground("SFW telemetry active")
        }
        if (store.isConfigured()) startOperationalMode() else log("Setup mode: device config is not saved yet")
        return START_STICKY
    }

    // Same pattern as SfdTelemetryService.promoteToForeground: location type needs
    // ACCESS_BACKGROUND_LOCATION when started from background, and dataSync is killed
    // after its 6h timeout, so prefer location and keep dataSync only as a fallback.
    // Never crash on a rejected type.
    private fun promoteToForeground(text: String) {
        val notif = notification(text)
        val candidates = intArrayOf(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        for (type in candidates) {
            try {
                startForeground(NOTIFICATION_ID, notif, type)
                foregroundType = type
                if (type == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) {
                    log("Foreground started without location type; will retry when app is opened")
                }
                return
            } catch (e: Exception) {
                log("Foreground type $type rejected: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        log("Unable to enter foreground state; stopping until next launch")
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(sampleRunnable)
        setAwayAdvertising(false)
        sensorManager.unregisterListener(this)
        releaseLocks()
        scheduleRestart("destroy")
        ioExecutor.shutdownNow()
        log("Telemetry background service stopped")
        super.onDestroy()
    }

    /**
     * Keep Wi-Fi and the CPU alive while operational so a screen-off Doze cycle does not drop the
     * hotspot/home connection and stall the telemetry loop. Idempotent — safe to call repeatedly
     * (onStartCommand may re-enter startOperationalMode).
     */
    private fun acquireLocks() {
        runCatching {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "sfw:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (wakeLock == null) {
                val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sfw:cpu").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            log("Wi-Fi/CPU locks acquired (keep alive during screen-off Doze)")
        }.onFailure { log("Lock acquire failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun releaseLocks() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wifiLock = null
        wakeLock = null
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
        // SFD calls WifiConnector.requestConnection() here; Wear OS manages its own
        // WiFi connections, so SFW only observes the current network passively.
        acquireLocks()
        registerGyroSensor()
        startLoops()
        syncZoneIdsFromServer()
        updateNotification("Operational mode: ${store.deviceId()}")
    }

    /**
     * Provisioning does not carry server zoneIds, so back-fill them from the server (device →
     * elder → safezones) and store them. This lets telemetry report safeZoneId so the backend can
     * match the zone by id instead of re-deriving it. Runs off the main thread, once when needed.
     */
    private fun syncZoneIdsFromServer() {
        if (!store.hasMissingZoneIds()) return
        ioExecutor.execute {
            runCatching {
                val elderId = apiClient.getElder(store.deviceId()).optString("elderId")
                if (elderId.isBlank()) return@runCatching
                val zones = apiClient.getSafeZones(elderId)
                if (store.ensureZoneIds(zones)) log("Safe zone IDs synced from server")
            }.onFailure { log("Zone ID sync failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    /** Sends telemetry and self-heals a seq collision (bumps seq past server's latest on duplicate). */
    private fun sendTelemetryChecked(payload: String): ApiResult {
        val result = apiClient.sendTelemetry(payload)
        if (result.ok) {
            val data = runCatching { org.json.JSONObject(result.body).optJSONObject("data") }.getOrNull()
            if (data != null) {
                if (data.optInt("accepted", -1) == 0 && data.optInt("duplicated", 0) > 0) {
                    runCatching {
                        val latest = apiClient.getLatestSeq(store.deviceId())
                        if (store.ensureSeqAtLeast(latest)) log("Seq re-synced after duplicate (server=$latest)")
                    }
                }
                // Server-driven config sync: the telemetry ACK carries configChanged +
                // serverConfigVersion. Re-pull /devices/{id}/config (+ guardians) and update the
                // local safe zones / guardian info whenever the server flags a change OR reports a
                // version we don't hold yet, so the watch stays in sync even across missed changes.
                val serverVersion = data.optInt("serverConfigVersion", -1)
                val configChanged = data.optBoolean("configChanged", false)
                if (configChanged || (serverVersion >= 0 && serverVersion != store.configVersion())) {
                    syncConfigFromServer(serverVersion, configChanged)
                }
            }
        }
        return result
    }

    /**
     * Pull the authoritative config (safe zones) and guardians from the server and persist them,
     * plus the new configVersion. Runs inline on the telemetry io thread. Best-effort: a failure
     * just leaves the old version so the next telemetry ACK triggers another attempt.
     */
    private fun syncConfigFromServer(serverVersion: Int, configChanged: Boolean) {
        runCatching {
            val deviceId = store.deviceId()
            val config = apiClient.getConfig(deviceId)
            val safeZones = config.optJSONArray("safeZones") ?: org.json.JSONArray()
            val version = config.optInt("configVersion", serverVersion)
            val elder = apiClient.getElder(deviceId)
            val elderId = elder.optString("elderId").ifBlank { config.optString("elderId") }
            val guardians = if (elderId.isNotBlank()) apiClient.getGuardians(elderId) else org.json.JSONArray()
            store.saveServerSync(elder, guardians, safeZones)
            store.saveConfigVersion(version)
            log("Config synced (v$version, changed=$configChanged): safeZones=${safeZones.length()}, guardians=${guardians.length()}")
            broadcastStateChanged()
        }.onFailure { log("Config sync failed: ${it.message ?: it.javaClass.simpleName}") }
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
        movementX = 0.0
        movementY = 0.0
        movementZ = 0.0
        latestGyro = sample
        store.addGyroSample(sample)
        val wifi = effectiveWifiStatus(wifiStatusReader.read())
        val location = locationSnapshot(wifi)
        val state = if (wifi.isAttached) "attached" else "not attached"
        log("Gyro stored gyX=${sample.gyX}, gyY=${sample.gyY}, gyZ=${sample.gyZ}; location=${location.locationType}; Wi-Fi $state ${wifi.apName}")
        if (store.hasMissingZoneIds()) syncZoneIdsFromServer()
        val eventSent = updateLocationState(wifi, location)
        if (!eventSent) sendScheduledTelemetry(wifi, location)
        updateNotification("${location.locationType}: ${locationLabel(location)}")
        // Advertise over BLE while away so SFC can detect the watch and raise its hotspot.
        setAwayAdvertising(!isInSafeZone(wifi))
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
                val result = sendTelemetryChecked(payload)
                store.saveTelemetryResult("Telemetry ${result.code}: ${result.body.ifBlank { if (result.ok) "sent" else "empty error body" }}")
                broadcastStateChanged()
                updateNotification("Last telemetry: ${result.code}")
            }.onFailure { error ->
                store.saveTelemetryResult("Telemetry failed: ${error.message ?: error.javaClass.simpleName}")
                broadcastStateChanged()
                updateNotification("Telemetry failed")
            }
        }
    }

    private fun updateLocationState(wifi: WifiStatus, location: LocationSnapshot): Boolean {
        val now = System.currentTimeMillis()
        val previousLocationType = lastLocationType
        val inSafeZone = isInSafeZone(wifi)
        val wasInSafeZone = previousLocationType == "WIFI"

        if (previousLocationType == null) {
            lastLocationType = location.locationType
            if (inSafeZone) {
                safeZoneStateStartedAt = now
            } else {
                outsideStateStartedAt = now
                gpsModeStartedAt = now
            }
            persistEscalationState()
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
            store.clearEscalationState()
            if (wifi.isAttached) store.saveLastZoneName(zoneDisplayName(wifi))
            log("Safe zone entered: ${locationLabel(location)}")
            sendStatusSms("entered", location)
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
            persistEscalationState()
            log("Safe zone exited: ${locationLabel(location)}")
            sendStatusSms("exited", location)
            sendZoneTransitionTelemetry("exited", wifi)
            return true
        }

        if (inSafeZone) {
            lastLocationType = location.locationType
            gpsModeStartedAt = null
            warningSent = false
            emergencySent = false
            lastSosTelemetryAt = 0L
            if (wifi.isAttached) store.saveLastZoneName(zoneDisplayName(wifi))
            persistEscalationState()
            return false
        }

        lastLocationType = location.locationType
        val startedAt = gpsModeStartedAt ?: now.also { gpsModeStartedAt = it }
        val elapsed = now - startedAt

        if (!emergencySent && elapsed >= SfwConfig.EMERGENCY_DELAY_MS) {
            warningSent = true
            emergencySent = true
            persistEscalationState()
            sendStatusSms("EMERGENCY", location)
            sendRiskTelemetry("SOS", "EMERGENCY", wifi)
            lastSosTelemetryAt = now
            return true
        }
        if (!warningSent && elapsed >= SfwConfig.WARNING_DELAY_MS) {
            warningSent = true
            persistEscalationState()
            sendStatusSms("WARNING", location)
            sendRiskTelemetry("GEOFENCE_EXIT_HINT", "WARNING", wifi)
            return true
        }
        persistEscalationState()
        return false
    }

    private fun persistEscalationState() {
        store.saveEscalationState(
            outsideStateStartedAt = outsideStateStartedAt,
            gpsModeStartedAt = gpsModeStartedAt,
            warningSent = warningSent,
            emergencySent = emergencySent,
            lastLocationType = lastLocationType
        )
    }

    private fun restoreEscalationState() {
        outsideStateStartedAt = store.escalationOutsideStartedAt()
        gpsModeStartedAt = store.escalationGpsModeStartedAt()
        warningSent = store.escalationWarningSent()
        emergencySent = store.escalationEmergencySent()
        lastLocationType = store.escalationLastLocationType()
        val hasState = outsideStateStartedAt != null || gpsModeStartedAt != null ||
            warningSent || emergencySent || lastLocationType != null
        if (!hasState) return
        val outsideText = outsideStateStartedAt?.let { "outside since ${clockText(it)}" } ?: "not outside"
        log(
            "Restored escalation state: $outsideText, warningSent=$warningSent, " +
                "emergencySent=$emergencySent, lastLocationType=${lastLocationType ?: "-"}"
        )
    }

    private fun clockText(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            .toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

    private fun sendScheduledTelemetry(wifi: WifiStatus, location: LocationSnapshot) {
        val now = System.currentTimeMillis()
        if (location.locationType == "GPS" && emergencySent && !isInSafeZone(wifi)) {
            if (lastSosTelemetryAt == 0L || now - lastSosTelemetryAt >= SfwConfig.SOS_REPEAT_PERIOD_MS) {
                lastSosTelemetryAt = now
                sendRiskTelemetry("SOS", "EMERGENCY", wifi)
            }
            return
        }
        val interval = if (location.locationType == "GPS") SfwConfig.GPS_TELEMETRY_REPORT_PERIOD_MS else SfwConfig.TELEMETRY_REPORT_PERIOD_MS
        if (lastPeriodicReportAt != 0L && now - lastPeriodicReportAt < interval) return
        lastPeriodicReportAt = now
        sendTelemetryReport(wifi, location)
    }

    private fun consumeZoneVerb(wifi: WifiStatus): ZoneVerb {
        val now = System.currentTimeMillis()
        return if (isInSafeZone(wifi)) {
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
                val result = sendTelemetryChecked(payload)
                store.saveTelemetryResult("$verb telemetry ${result.code}: ${result.body.ifBlank { if (result.ok) "sent" else "empty error body" }}")
                broadcastStateChanged()
                updateNotification("$verb telemetry: ${result.code}")
            }.onFailure { error ->
                store.saveTelemetryResult("$verb telemetry failed: ${error.message ?: error.javaClass.simpleName}")
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
                val result = sendTelemetryChecked(payload)
                store.saveTelemetryResult("$label telemetry ${result.code}: ${result.body.ifBlank { if (result.ok) "sent" else "empty error body" }}")
                broadcastStateChanged()
                updateNotification("$label telemetry: ${result.code}")
            }.onFailure { error ->
                store.saveTelemetryResult("$label telemetry failed: ${error.message ?: error.javaClass.simpleName}")
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
        return if (averageMagnitude >= SfwConfig.GYRO_MOVEMENT_THRESHOLD) "moved" else "stayed"
    }

    private fun eventTypeForVerb(verb: String): String = when {
        // Report a flat battery as its own event so the server raises the operational alert even
        // while the location side of the report looks routine.
        currentBatteryLevel() in 1..SfwConfig.LOW_BATTERY_PCT -> "LOW_BATTERY"
        verb == "exited" -> "GEOFENCE_EXIT_HINT"
        else -> "PERIODIC"
    }

    /**
     * RSSI of the link telemetry actually travels over: Wi-Fi RSSI while associated, else the
     * cellular RSSI (the watch reports over mobile data then, so a stale Wi-Fi value would lie).
     */
    private fun linkSignal(wifi: WifiStatus): Int {
        if (wifi.hasWifiConnection) return wifi.signal
        return runCatching {
            getSystemService(TelephonyManager::class.java)
                ?.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm ?: wifi.signal
        }.getOrDefault(wifi.signal)
    }

    private fun verbForLocation(verb: String, location: LocationSnapshot): String {
        if (location.locationType == "GPS") return gpsVerb(location, verb)
        return verb
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
            wifiHoldSince = 0L
            movedSinceWifiLoss = false
            learnHomeCenterIfPossible(current)
            return current
        }
        val last = lastConfirmedWifi ?: run {
            // No confirmed Wi-Fi yet this session (e.g. right after registration). The watch starts
            // at home, so report the safe zone while Wi-Fi associates — never a false "이탈/moved" —
            // when the home AP is in scan range OR during the brief startup grace.
            val homeNearby = wifiStatusReader.isZoneApNearby()
            val withinStartupGrace = System.currentTimeMillis() - serviceStartedAt <= SfwConfig.STARTUP_SAFE_GRACE_MS
            if (homeNearby || withinStartupGrace) {
                store.wifiSafeZones().firstOrNull()?.let { zone ->
                    return WifiStatus(
                        apName = zone.optString("ssid").ifBlank { zone.optString("name") },
                        bssid = zone.optString("bssid"),
                        signal = current.signal,
                        isAttached = true,
                        hasWifiConnection = false,
                        wifiEnabled = current.wifiEnabled
                    )
                }
            }
            return current
        }
        val movementNow = latestGyro.gyX + latestGyro.gyY + latestGyro.gyZ
        if (movementNow >= SfwConfig.GYRO_MOVEMENT_THRESHOLD) movedSinceWifiLoss = true
        val held = last.copy(signal = current.signal.takeIf { it > -127 } ?: last.signal)
        if (!current.hasWifiConnection && now - lastConfirmedWifiAt <= SfwConfig.WIFI_SAFEZONE_GRACE_MS) {
            log("Wi-Fi SafeZone reading missed once; keeping last confirmed AP ${last.apName}")
            return held
        }
        // POSITIVE departure evidence overrides every hold below. A valid, fresh GPS fix well
        // outside every known home center proves the watch really left, even if Wi-Fi still claims
        // an association (stale/held link) or the home AP lingers in a scan — otherwise the holds
        // mask real exits and the device keeps reporting the home zone while out.
        gpsDepartureEvidence()?.let { away ->
            log("GPS proves departure (${away}m from home); exiting safe zone despite Wi-Fi holds")
            return current.copy(isAttached = false)
        }
        // Grace expired. A dropped association alone is not exit evidence — the link
        // flaps while the watch never leaves the house (same false-exit class fixed
        // in SFD on 2026-07-06).
        if (wifiStatusReader.isZoneApNearby()) {
            lastConfirmedWifiAt = now
            log("Wi-Fi not attached but zone AP still in scan range; holding safe zone ${last.apName}")
            return held
        }
        // Brief disconnection (Wear Doze power-save / 2.4G↔5G roam DEAUTH): a wrist-worn watch
        // moves CONSTANTLY, so gyro motion is NOT a departure signal — gating the hold on
        // "no movement" was exactly why a worn watch flapped out of the zone and spammed exit/SOS
        // SMS. Hold the zone for a bounded window regardless of motion; only a sustained full
        // disconnection (home AP gone from scans for this long) falls through to the GPS check.
        if (!current.hasWifiConnection && now - lastConfirmedWifiAt <= SfwConfig.WIFI_DISCONNECT_HOLD_MAX_MS) {
            return held
        }
        // Connected to *some* Wi-Fi but the OS redacted its SSID/BSSID and scans are throttled
        // (routine on Wear OS when the screen is off / the app is backgrounded), so we can't
        // re-confirm the zone by name/bssid/scan. An ACTIVE Wi-Fi association is itself strong
        // proof we have not left — you cannot be "away/이탈" while still associated to the home AP.
        // Hold rather than falling through to a GPS-based false exit (this was the cause of the
        // repeated GPS "이탈" → WARNING/SOS SMS storm while the watch sat on home Wi-Fi).
        if (current.hasWifiConnection) {
            log("Wi-Fi connected but unidentifiable; holding last safe zone ${last.apName}")
            return held
        }
        // GPS-based departure decision (Wi-Fi gave no answer). A worn watch at home routinely has
        // Wi-Fi off and weak indoor GPS, so require POSITIVE evidence to exit: only a VALID GPS fix
        // that is far from every home zone counts as a real departure. No fix, or within radius →
        // HOLD the safe zone. This kills the frequent false "이탈" while at home.
        val gps = effectiveGpsStatus(gpsStatusReader.read())
        val gLat = gps.latitude
        val gLng = gps.longitude
        if (gLat == null || gLng == null) {
            log("Wi-Fi off & GPS unavailable; holding safe zone (no departure evidence)")
            return held
        }
        val centers = store.wifiSafeZones().mapNotNull { zone ->
            val cLat = zone.optDouble("centerLat", Double.NaN)
            val cLng = zone.optDouble("centerLng", Double.NaN)
            if (!cLat.isNaN() && !cLng.isNaN()) Pair(cLat, cLng) else null
        }
        // No home zone has a geofence center (server strips centerLat; none learned yet). Without a
        // reference point a GPS fix cannot prove departure, so HOLD rather than false-exit. This is
        // what caused the ~40 exit/enter SMS while the watch sat at home: Wi-Fi dropped in Doze, the
        // hold expired, and with no center every GPS check fell through to "이탈".
        if (centers.isEmpty()) {
            log("Wi-Fi off & no home geofence center known; holding safe zone (cannot prove departure)")
            return held
        }
        val nearHome = centers.any { (cLat, cLng) ->
            distanceMeters(gLat, gLng, cLat, cLng) <= SfwConfig.GEOFENCE_RADIUS_M
        }
        if (nearHome) {
            log("Wi-Fi off but GPS within home radius; holding safe zone")
            return held
        }
        return current
    }

    /**
     * Distance from the nearest home center when GPS PROVES the watch has left, else null.
     * Strict on purpose so it can override the Wi-Fi holds without reintroducing the false-exit
     * storm: the fix must be well beyond the geofence (margin covers GPS jitter), and at least one
     * home center must be known — with none, departure is unprovable.
     */
    private fun gpsDepartureEvidence(): Int? {
        val centers = store.wifiSafeZones().mapNotNull { zone ->
            val cLat = zone.optDouble("centerLat", Double.NaN)
            val cLng = zone.optDouble("centerLng", Double.NaN)
            if (!cLat.isNaN() && !cLng.isNaN()) Pair(cLat, cLng) else null
        }
        if (centers.isEmpty()) return null
        val gps = effectiveGpsStatus(gpsStatusReader.read())
        val lat = gps.latitude ?: return null
        val lng = gps.longitude ?: return null
        val nearest = centers.minOf { (cLat, cLng) -> distanceMeters(lat, lng, cLat, cLng) }
        val threshold = SfwConfig.GEOFENCE_RADIUS_M + SfwConfig.GPS_DEPARTURE_MARGIN_M
        return if (nearest > threshold) nearest.toInt() else null
    }

    private fun isInSafeZone(wifi: WifiStatus): Boolean = wifi.isAttached

    private fun isHotspotAp(apName: String): Boolean =
        apName.trim().trim('"').equals(SfwConfig.HOTSPOT_SSID, ignoreCase = true)

    private fun locationSnapshot(wifi: WifiStatus): LocationSnapshot = if (wifi.isAttached) {
        // On the guardian hotspot the location is mobile, so include GPS coords too;
        // a fixed home AP does not need them.
        // REGISTERED_HOTSPOT (guardian hotspot) → live mobile GPS. FIXED_AP (home) → stored center.
        val gps = if (isHotspotAp(wifi.apName)) effectiveGpsStatus(gpsStatusReader.read()) else null
        LocationSnapshot(
            locationType = "WIFI",
            apName = wifi.apName,
            bssid = wifi.bssid,
            signal = wifi.signal,
            latitude = gps?.latitude ?: store.firstWifiCenterLat(),
            longitude = gps?.longitude ?: store.firstWifiCenterLng(),
            accuracy = gps?.accuracy
        )
    } else {
        val gps = effectiveGpsStatus(gpsStatusReader.read())
        LocationSnapshot(
            locationType = "GPS",
            latitude = gps.latitude,
            longitude = gps.longitude,
            accuracy = gps.accuracy,
            signal = linkSignal(wifi)
        )
    }

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
            now - lastValidGpsReadAt <= SfwConfig.GPS_LOCATION_CACHE_MS
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
        return if (distance <= SfwConfig.GPS_STAY_DISTANCE_M) {
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
        // A nearly-flat battery is the most actionable operational state, and LOW_BATTERY is the
        // spec enum for it, so it takes priority over the location-derived states below.
        if (currentBatteryLevel() in 1..SfwConfig.LOW_BATTERY_PCT) return "LOW_BATTERY"
        if (location.locationType == "GPS" && (location.latitude == null || location.longitude == null)) return "GPS_WEAK"
        if (location.locationType == "GPS") {
            val elapsed = gpsModeStartedAt?.let { System.currentTimeMillis() - it } ?: 0L
            if (elapsed >= SfwConfig.EMERGENCY_DELAY_MS) return "EMERGENCY"
            if (elapsed >= SfwConfig.WARNING_DELAY_MS) return "WARNING"
        }
        return "NORMAL"
    }

    private fun locationLabel(location: LocationSnapshot): String {
        return when (location.locationType) {
            "WIFI" -> location.apName.ifBlank { store.firstWifiSsid().ifBlank { location.bssid.ifBlank { store.firstWifiZoneName() } } }
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

    // Korean guardian SMS templates — kept identical to SFD's sendStatusSms so
    // guardians receive the same messages regardless of device type.
    private fun sendStatusSms(event: String, location: LocationSnapshot) {
        val now = System.currentTimeMillis()
        // Hard floor across ALL alert SMS: even a pathological state flap can never spam the
        // guardian (root cause of the "수십 건" storm). A real WARNING→SOS is 25 min apart, so this
        // floor never blocks a legitimate escalation.
        if (lastAnySmsAt != 0L && now - lastAnySmsAt < SfwConfig.MIN_ANY_SMS_INTERVAL_MS) {
            log("SMS 억제($event): 최근 발송 후 ${(now - lastAnySmsAt) / 1000}s (전역 스팸 방지)")
            return
        }
        // Debounce entry/exit SMS: even if the zone state briefly flaps, never send more than one
        // transition SMS per interval so a guardian is not spammed.
        if (event == "entered" || event == "exited") {
            if (now - lastTransitionSmsAt < SfwConfig.MIN_TRANSITION_SMS_INTERVAL_MS) {
                log("SMS 억제($event): 최근 전환 SMS 후 ${(now - lastTransitionSmsAt) / 1000}s (플래핑 방지)")
                return
            }
            lastTransitionSmsAt = now
        }
        lastAnySmsAt = now
        val zone = store.lastZoneName().ifBlank { "안전 구역" }
        val locationLine = mapLink(location)?.let { "\n현재 위치: $it" } ?: ""
        val message = when (event) {
            "entered" -> "[SafeFinder] ${elderLabel()}이 안전 구역 ${zone}에 들어가셨습니다."
            "exited" -> "[SafeFinder] ${elderLabel()}이 안전 구역 ${zone}을 벗어났습니다.$locationLine"
            "WARNING" -> "[SafeFinder] 주의: ${elderLabel()}이 안전 구역을 벗어난 지 5분이 넘어 관심이 필요합니다.$locationLine"
            else -> "[SafeFinder] SOS: ${elderLabel()}이 안전 구역을 벗어난 지 30분이 지났습니다. 지금 바로 위치를 확인해 주세요.$locationLine"
        }
        sendSms(event, message)
    }

    private fun elderLabel(): String {
        val name = store.elderName().trim()
        if (name.isBlank() || name.equals("elder", ignoreCase = true)) {
            return "어르신(${store.deviceId()})"
        }
        return if (name.endsWith("님")) name else "${name}님"
    }

    private fun zoneDisplayName(wifi: WifiStatus): String {
        val named = store.zoneNameForSsid(wifi.apName)
        if (named.isNotBlank()) return named
        return wifi.apName.ifBlank { store.firstWifiZoneName() }
    }

    private fun mapLink(location: LocationSnapshot): String? {
        val lat = location.latitude ?: return null
        val lng = location.longitude ?: return null
        return "https://maps.google.com/?q=${"%.5f".format(lat)},${"%.5f".format(lng)}"
    }

    private fun sendSms(label: String, message: String) {
        val phones = store.guardianPhones()
        if (phones.isEmpty()) {
            log("SMS skipped for $label: no guardian phone configured")
            return
        }
        // This watch reports telephony + messaging + eSIM, so attempt the send and report the REAL
        // delivery result (not just "queued"). If a device truly lacks telephony, the send throws
        // and we log that instead of silently dropping it.
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            log("SMS: telephony 기능 없음 — $label 메시지 로그만: $message")
        }
        phones.forEach { phone ->
            runCatching {
                val sms = smsManager() ?: error("SmsManager unavailable")
                val parts = sms.divideMessage(message)
                // One sentIntent per part so the actual send result (OK / no-service / radio-off /
                // no-SIM …) is captured and logged — this is what makes SMS verifiable.
                val sentIntents = ArrayList<PendingIntent>(parts.size)
                for (i in parts.indices) {
                    val action = "com.sf.sfw.SMS_SENT.${System.currentTimeMillis()}.$i"
                    registerSmsResultReceiver(action, label, phone)
                    sentIntents.add(
                        PendingIntent.getBroadcast(
                            this, 0, Intent(action).setPackage(packageName),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
                sms.sendMultipartTextMessage(phone, null, parts, sentIntents, null)
                log("SMS 발송 시도: $phone ($label, ${parts.size}조각)")
            }.onFailure { log("SMS 발송 오류 [$label → $phone]: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    /** Registers a one-shot receiver that logs the real SMS send result for one message part. */
    private fun registerSmsResultReceiver(action: String, label: String, phone: String) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val result = when (resultCode) {
                    android.app.Activity.RESULT_OK -> "성공(RESULT_OK)"
                    SmsManager.RESULT_ERROR_NO_SERVICE -> "실패: 무서비스"
                    SmsManager.RESULT_ERROR_RADIO_OFF -> "실패: 무선 꺼짐"
                    SmsManager.RESULT_ERROR_NULL_PDU -> "실패: NULL_PDU"
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "실패: 일반 오류"
                    else -> "실패: 코드 $resultCode"
                }
                log("SMS 결과 [$label → $phone]: $result")
                runCatching { context?.unregisterReceiver(this) }
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

    /** Advertise the shared service UUID over BLE while away so SFC detects the watch and raises
     * its hotspot (same signal SFD uses). Stops once back in the safe zone. */
    @SuppressLint("MissingPermission")
    private fun setAwayAdvertising(enabled: Boolean) {
        if (enabled == awayAdvertising) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        ) return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        if (!adapter.isEnabled) return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        if (enabled) {
            runCatching { adapter.name = SfwConfig.BLE_DEVICE_NAME }
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(java.util.UUID.fromString(SfwConfig.SERVICE_UUID)))
                .build()
            runCatching { advertiser.startAdvertising(settings, data, awayAdvertiseCallback) }
            awayAdvertising = true
            log("이탈: BLE 광고 시작 (SFC 감지 → 핫스팟)")
        } else {
            runCatching { advertiser.stopAdvertising(awayAdvertiseCallback) }
            awayAdvertising = false
            log("안전구역 복귀: BLE 광고 중지")
        }
    }

    private var lastCenterLearnAt = 0L

    /**
     * While genuinely at home (attached to a FIXED_AP home Wi-Fi zone) with a real GPS fix, record
     * that fix as the zone's geofence center if it has none. This gives the GPS geofence a reference
     * so a real departure can be detected, and stops the false "이탈" storm caused by home zones that
     * carry no centerLat (the server strips it). No-op once any zone has a center; throttled otherwise.
     */
    private fun learnHomeCenterIfPossible(attached: WifiStatus) {
        if (store.hasAnyWifiZoneCenter()) return
        val now = System.currentTimeMillis()
        if (now - lastCenterLearnAt < SfwConfig.CENTER_LEARN_INTERVAL_MS) return
        lastCenterLearnAt = now
        val gps = effectiveGpsStatus(gpsStatusReader.read())
        val lat = gps.latitude ?: return
        val lng = gps.longitude ?: return
        if (store.rememberZoneCenter(attached.apName, attached.bssid, lat, lng)) {
            log("Learned home geofence center for ${attached.apName}: ${"%.5f".format(lat)}, ${"%.5f".format(lng)}")
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
        runCatching { manager.notify(NOTIFICATION_ID, notification(text)) }
    }

    private fun scheduleRestart(reason: String) {
        if (!::store.isInitialized || !store.isConfigured()) return
        runCatching {
            val intent = Intent(this, SfwRestartReceiver::class.java)
                .setAction(ACTION_RESTART_TELEMETRY)
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                2107,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + 10_000L
            val alarmManager = getSystemService(AlarmManager::class.java)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            store.appendLog("Telemetry restart scheduled after $reason")
        }
    }

    private fun notification(text: String): Notification {
        val channelId = "sfw_telemetry"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "SFW Telemetry", NotificationManager.IMPORTANCE_LOW)
        )
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("SFW Watch")
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
        const val ACTION_STATE_CHANGED = "com.sf.sfw.STATE_CHANGED"
        const val ACTION_RESTART_TELEMETRY = "com.sf.sfw.RESTART_TELEMETRY"
        private const val NOTIFICATION_ID = 1107
    }
}
