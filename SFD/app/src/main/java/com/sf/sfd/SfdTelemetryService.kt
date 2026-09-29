package com.sf.sfd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AlarmManager
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

class SfdTelemetryService : Service(), SensorEventListener {
    private lateinit var store: SfdStore
    private lateinit var apiClient: SfdApiClient
    private lateinit var payloadFactory: TelemetryPayloadFactory
    private lateinit var wifiStatusReader: WifiStatusReader
    private lateinit var gpsStatusReader: GpsStatusReader
    private lateinit var wifiConnector: WifiConnector
    private lateinit var bleManager: BlePeripheralManager
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
    private val serviceStartedAt: Long = System.currentTimeMillis()
    private var lastConfirmedWifi: WifiStatus? = null
    private var wifiHoldSince: Long = 0L
    private var movedSinceWifiLoss = false
    private var lastValidGps: GpsStatus? = null
    private var lastValidGpsReadAt: Long = 0L
    private var lastGpsVerbLocation: LocationSnapshot? = null
    private var gpsStayedStartedAt: Long? = null
    private var foregroundType = 0
    private var connectivityStarted = false
    private var advertisingActive = false
    private var hotspotSafeActive = false
    private var awayPhase: AwayPhase? = null
    private var awayPhaseStartedAt: Long = 0L

    private val sampleRunnable = object : Runnable {
        override fun run() {
            collectGyroSample()
            handler.postDelayed(this, SfdConfig.GYRO_SAMPLE_PERIOD_MS)
        }
    }

    private val connectivityRunnable = object : Runnable {
        override fun run() {
            runCatching { connectivityTick() }
                .onFailure { log("Connectivity tick error: ${it.message ?: it.javaClass.simpleName}") }
            handler.postDelayed(this, SfdConfig.CONNECTIVITY_TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = SfdStore(applicationContext)
        restoreEscalationState()
        apiClient = SfdApiClient()
        payloadFactory = TelemetryPayloadFactory(store)
        wifiStatusReader = WifiStatusReader(applicationContext, store)
        gpsStatusReader = GpsStatusReader(applicationContext)
        wifiConnector = WifiConnector(applicationContext, store)
        bleManager = BlePeripheralManager(
            context = applicationContext,
            store = store,
            apiClient = apiClient,
            onStatus = { log(it) }
        )
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        promoteToForeground("SFD is waiting for configuration")
        if (store.isConfigured()) startOperationalMode() else log("Setup mode: waiting for SFC BLE provisioning")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            foregroundType != ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        ) {
            promoteToForeground("SFD telemetry active")
        }
        if (store.isConfigured()) startOperationalMode() else log("Setup mode: device info is not saved yet")
        return START_STICKY
    }

    private fun promoteToForeground(text: String) {
        val notif = notification(text)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif)
            return
        }
        // location type needs ACCESS_BACKGROUND_LOCATION when started from background,
        // and dataSync is killed by the system after its 6h timeout, so prefer location
        // and keep dataSync only as a fallback until the app is opened again.
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
        handler.removeCallbacks(connectivityRunnable)
        runCatching { if (advertisingActive) bleManager.stop() }
        advertisingActive = false
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
        // BLE advertising is off in operational mode (MainActivity disables it after
        // provisioning), so a persisted "connected" flag can only be stale — left as-is
        // it pins the device in a fake BLE safe zone even after WiFi is gone.
        store.saveBleSafeZone(false)
        hotspotSafeActive = false
        val wifiMessage = wifiConnector.requestConnection()
        log(wifiMessage)
        registerGyroSensor()
        startLoops()
        startConnectivityLoop()
        syncZoneIdsFromServer()
        updateNotification("Operational mode: ${store.currentState().deviceId}")
    }

    /**
     * Provisioning does not carry server zoneIds, so back-fill them from the server (device →
     * elder → safezones) and store them. This lets telemetry report safeZoneId so the backend can
     * match the zone by id instead of re-deriving it. Runs off the main thread, once when needed.
     */
    private fun syncZoneIdsFromServer() {
        ioExecutor.execute {
            runCatching {
                val deviceId = store.currentState().deviceId
                if (!store.hasMissingZoneIds()) return@runCatching
                val elderId = apiClient.getElder(deviceId).optString("elderId")
                if (elderId.isBlank()) return@runCatching
                val zones = apiClient.getSafeZones(elderId)
                if (store.ensureZoneIds(zones)) log("Safe zone IDs synced from server")
            }.onFailure { log("Server sync failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    /**
     * Sends telemetry and self-heals a seq collision: if the server accepted 0 and flagged the
     * report as a duplicate (our seq is at/behind the server's, e.g. after a reset), re-sync the
     * seq past the server's latest so the next report is accepted.
     */
    private fun sendTelemetryChecked(payload: String): ApiResult {
        val result = apiClient.sendTelemetry(payload)
        if (result.ok) {
            val data = runCatching { org.json.JSONObject(result.body).optJSONObject("data") }.getOrNull()
            if (data != null) {
                if (data.optInt("accepted", -1) == 0 && data.optInt("duplicated", 0) > 0) {
                    runCatching {
                        val latest = apiClient.getLatestSeq(store.currentState().deviceId)
                        if (store.ensureSeqAtLeast(latest)) log("Seq re-synced after duplicate (server=$latest)")
                    }
                }
                // Server-driven config sync: the telemetry ACK carries configChanged +
                // serverConfigVersion. Re-pull /devices/{id}/config (+ guardians) and update the
                // local safe zones / guardian info whenever the server flags a change OR reports a
                // version we don't hold yet, so the device stays in sync even across missed changes.
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
     * plus the new configVersion. Runs inline on the telemetry io thread (sendTelemetryChecked is
     * already off the main thread). Best-effort: a failure just leaves the old version so the next
     * telemetry ACK triggers another attempt.
     */
    private fun syncConfigFromServer(serverVersion: Int, configChanged: Boolean) {
        runCatching {
            val deviceId = store.currentState().deviceId
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

    private fun startConnectivityLoop() {
        if (connectivityStarted) return
        connectivityStarted = true
        handler.post(connectivityRunnable)
        log("Connectivity manager started (tick=${SfdConfig.CONNECTIVITY_TICK_MS}ms)")
    }

    /**
     * Autonomous connectivity management. Runs every CONNECTIVITY_TICK_MS in operational
     * mode. Keeps SFD joined to a safezone AP or the guardian hotspot, and advertises over
     * BLE (implicit signaling) whenever it has no managed connection so SFC can enable its
     * hotspot. Additive: it never touches telemetry/SOS state except to mark the hotspot as
     * a safe zone so the existing state machine does not escalate while docked to SFC.
     */
    private fun connectivityTick() {
        if (!store.isConfigured()) return
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            // Android Q+ apps cannot force-enable Wi-Fi; log and keep advertising so SFC
            // can still find SFD. Do not crash. Force the advertising phase so the loop
            // never wastes a connect attempt while Wi-Fi cannot be joined.
            log("Connectivity: Wi-Fi is disabled (cannot auto-enable on Q+)")
            // Wi-Fi off => cannot be on the guardian hotspot => not a safe zone, no grace.
            hotspotSafeActive = false
            store.resetBleSafeZone()
            forceAwayAdvertising("Wi-Fi disabled")
            return
        }

        val status = wifiStatusReader.read()
        val connectedToSafezone = status.isAttached
        val connectedToHotspot = wifiStatusReader.connectedToSsid(status, SfdConfig.HOTSPOT_SSID)

        if (connectedToSafezone || connectedToHotspot) {
            // Connected to a managed network: this is a safe zone. Stop advertising (SFC
            // reads "advertising off" as "SFD connected") and reset the away loop.
            exitAwayState()
            setAdvertising(false)
            if (connectedToHotspot && !connectedToSafezone) {
                // Docked to the guardian hotspot: treat as "safe with guardian" so the
                // state machine's isInSafeZone() stays true and SOS does not escalate.
                store.saveBleSafeZone(true, "", "SFC-Hotspot")
                if (!hotspotSafeActive) {
                    hotspotSafeActive = true
                    log("Connectivity: connected to hotspot ${SfdConfig.HOTSPOT_SSID}; marked as safe zone")
                }
            } else {
                clearHotspotSafeIfNeeded()
            }
            return
        }

        // No managed connection: this is the "away" state. Run the advertise <-> connect loop.
        clearHotspotSafeIfNeeded()
        runAwayStateMachine()
    }

    /**
     * Away-from-safezone BLE presence loop (req 10-12). Driven by connectivityTick (every
     * CONNECTIVITY_TICK_MS) so it shares the same wiring as false-exit suppression and never
     * touches the SOS/escalation state directly.
     *   ADVERTISING: BLE advertising is on so SFC can detect SFD. After BLE_ADVERTISE_DURATION_MS
     *     -> stop advertising and try to join the hotspot/safezone WiFi (CONNECTING).
     *   CONNECTING: advertising off while the OS attempts the join. If still not connected after
     *     CONNECT_GRACE_MS -> resume advertising (back to ADVERTISING). A successful join is caught
     *     by connectivityTick's connected branch, which calls exitAwayState().
     */
    private fun runAwayStateMachine() {
        val now = System.currentTimeMillis()
        when (awayPhase) {
            null -> {
                // Entering the away loop. If a known safe-zone AP (home Wi-Fi) or the guardian
                // hotspot is already in range, this is almost certainly the moment right after
                // registration while the tablet reconnects to home — do NOT advertise, just
                // connect (req: once registered, keep BLE off). Only advertise when no known AP
                // is reachable, i.e. genuinely away, so SFC can raise its hotspot (req 10).
                val knownApInRange = wifiStatusReader.isSsidNearby(SfdConfig.HOTSPOT_SSID) ||
                    wifiStatusReader.bestSafezoneInRange() != null
                if (knownApInRange) {
                    awayPhase = AwayPhase.CONNECTING
                    awayPhaseStartedAt = now
                    setAdvertising(false)
                    attemptSafeZoneConnect()
                    log("Away: known AP in range; connecting without BLE advertising")
                } else {
                    awayPhase = AwayPhase.ADVERTISING
                    awayPhaseStartedAt = now
                    setAdvertising(true)
                    log("Away: left safe zone; BLE advertising started so SFC can detect SFD")
                }
            }
            AwayPhase.ADVERTISING -> {
                setAdvertising(true)
                if (now - awayPhaseStartedAt >= SfdConfig.BLE_ADVERTISE_DURATION_MS) {
                    // req 11: after 3 min, stop advertising and attempt a SafeZone join.
                    awayPhase = AwayPhase.CONNECTING
                    awayPhaseStartedAt = now
                    setAdvertising(false)
                    attemptSafeZoneConnect()
                }
            }
            AwayPhase.CONNECTING -> {
                // Advertising stays off while the OS tries to join (req 11).
                if (now - awayPhaseStartedAt >= SfdConfig.CONNECT_GRACE_MS) {
                    // req 12: not connected within the grace window -> re-advertise.
                    awayPhase = AwayPhase.ADVERTISING
                    awayPhaseStartedAt = now
                    setAdvertising(true)
                    log("Away: SafeZone connect failed within ${SfdConfig.CONNECT_GRACE_MS / 1000}s grace; resuming BLE advertising")
                }
            }
        }
    }

    private fun attemptSafeZoneConnect() {
        val hotspotInRange = wifiStatusReader.isSsidNearby(SfdConfig.HOTSPOT_SSID)
        val bestZone = wifiStatusReader.bestSafezoneInRange()
        val outcome = when {
            hotspotInRange -> "hotspot ${SfdConfig.HOTSPOT_SSID}: ${wifiConnector.connectToHotspot()}"
            bestZone != null -> {
                val zoneName = bestZone.optString("name", bestZone.optString("ssid"))
                "safezone '$zoneName': ${wifiConnector.connectToZone(bestZone)}"
            }
            // Nothing detected in a fresh scan; still register suggestions (hotspot first) so
            // the OS auto-joins if the AP appears during the grace window.
            else -> "no SafeZone AP in range; ${wifiConnector.connectToHotspot()}"
        }
        log("Away: advertised ${SfdConfig.BLE_ADVERTISE_DURATION_MS / 1000}s, stopping to attempt SafeZone connect -> $outcome")
    }

    private fun exitAwayState() {
        if (awayPhase == null) return
        awayPhase = null
        awayPhaseStartedAt = 0L
        log("Away: SafeZone connection established; BLE advertising loop stopped")
    }

    private fun forceAwayAdvertising(reason: String) {
        if (awayPhase != AwayPhase.ADVERTISING) {
            awayPhase = AwayPhase.ADVERTISING
            log("Away: $reason; BLE advertising")
        }
        // Keep the timer fresh so we never step into CONNECTING while a join is impossible.
        awayPhaseStartedAt = System.currentTimeMillis()
        setAdvertising(true)
    }

    private fun setAdvertising(desired: Boolean) {
        if (desired == advertisingActive) return
        advertisingActive = desired
        if (desired) bleManager.start() else bleManager.stop()
    }

    private fun clearHotspotSafeIfNeeded() {
        if (!hotspotSafeActive) return
        hotspotSafeActive = false
        store.saveBleSafeZone(false)
        log("Connectivity: left hotspot; cleared hotspot safe-zone marker")
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
        // Keep retrying the zoneId back-fill until every zone has its server id, so telemetry can
        // carry safeZoneId (the sync at startup can fail on the transient network right after
        // registration). Cheap no-op once all ids are present.
        if (store.hasMissingZoneIds()) syncZoneIdsFromServer()
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
                val result = sendTelemetryChecked(payload)
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

        if (!emergencySent && elapsed >= SfdConfig.EMERGENCY_DELAY_MS) {
            warningSent = true
            emergencySent = true
            persistEscalationState()
            sendStatusSms("EMERGENCY", location)
            sendRiskTelemetry("SOS", "EMERGENCY", wifi)
            lastSosTelemetryAt = now
            return true
        }
        if (!warningSent && elapsed >= SfdConfig.WARNING_DELAY_MS) {
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
            if (lastSosTelemetryAt == 0L || now - lastSosTelemetryAt >= SfdConfig.SOS_REPEAT_PERIOD_MS) {
                lastSosTelemetryAt = now
                sendRiskTelemetry("SOS", "EMERGENCY", wifi)
            }
            return
        }
        // Safe zone (WiFi/BLE): report every TELEMETRY_REPORT_PERIOD_MS (10 min).
        // Outside (GPS): report every GPS_TELEMETRY_REPORT_PERIOD_MS (1 min).
        // Zone transitions (entered/exited) are pushed immediately by updateLocationState.
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
                val result = sendTelemetryChecked(payload)
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
                val result = sendTelemetryChecked(payload)
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

    private fun eventTypeForVerb(verb: String): String = when {
        // Report a flat battery as its own event so the server raises the operational alert even
        // while the location side of the report looks routine.
        currentBatteryLevel() in 1..SfdConfig.LOW_BATTERY_PCT -> "LOW_BATTERY"
        verb == "exited" -> "GEOFENCE_EXIT_HINT"
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
            wifiHoldSince = 0L
            movedSinceWifiLoss = false
            learnHomeCenterIfPossible(current)
            return current
        }
        val last = lastConfirmedWifi ?: run {
            // No confirmed Wi-Fi yet this session (e.g. right after registration, while Wi-Fi is
            // still associating). Devices are registered/started at home, so report the safe zone —
            // never a false "이탈/moved" — while the home AP is visible in a scan OR during the brief
            // startup grace. This kills the spurious inSafeZone=false telemetry at registration time.
            val homeNearby = wifiStatusReader.isZoneApNearby()
            val withinStartupGrace = System.currentTimeMillis() - serviceStartedAt <= SfdConfig.STARTUP_SAFE_GRACE_MS
            if (homeNearby || withinStartupGrace) {
                store.wifiSafeZone()?.let { zone ->
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
        if (movementNow >= SfdConfig.GYRO_MOVEMENT_THRESHOLD) movedSinceWifiLoss = true
        val held = last.copy(signal = current.signal.takeIf { it > -127 } ?: last.signal)
        if (!current.hasWifiConnection && now - lastConfirmedWifiAt <= SfdConfig.WIFI_SAFEZONE_GRACE_MS) {
            log("Wi-Fi SafeZone reading missed once; keeping last confirmed AP ${last.apName}")
            return held
        }
        // POSITIVE departure evidence overrides every hold below. A valid, fresh GPS fix that is far
        // outside every known home center proves the device really left, even if Wi-Fi still claims
        // an association (stale/held link) or the home AP lingers in a scan. Without this the holds
        // masked real exits: the tablet kept reporting "nobug_home" the whole time it was out.
        gpsDepartureEvidence()?.let { away ->
            log("GPS proves departure (${away}m from home); exiting safe zone despite Wi-Fi holds")
            return current.copy(isAttached = false)
        }
        // Grace expired. A dropped association alone is not exit evidence — Samsung
        // auto-reconnect and idle power saving flap the link while the phone never
        // leaves the house (observed 2026-07-05/06: exit SMS with home GPS coords).
        if (wifiStatusReader.isZoneApNearby()) {
            lastConfirmedWifiAt = now
            log("Wi-Fi not attached but zone AP still in scan range; holding safe zone ${last.apName}")
            return held
        }
        // Stationary hold only bridges brief scan blackouts while the Wi-Fi radio is ON (so the
        // zone-AP scan was actually attempted and just missed). If Wi-Fi is turned OFF we cannot
        // verify home presence at all, so "not moving" is no evidence of still being home — fall
        // through to exit instead of masking a real departure.
        if (current.hasWifiConnection) {
            // We can READ the live SSID and it is not the AP we were holding: the device genuinely
            // moved to another network (typically the guardian hotspot, whose BSSID no longer
            // matches the registered zone because Android randomises it). Report what we are really
            // on — holding here is what made telemetry keep saying "nobug_home" while the tablet was
            // actually on the hotspot. The hotspot is itself a safe zone, so staying on it is "in zone".
            if (current.apName.isNotBlank() && !wifiStatusReader.connectedToSsid(current, last.apName)) {
                val onHotspot = wifiStatusReader.connectedToSsid(current, SfdConfig.HOTSPOT_SSID)
                log("Now on '${current.apName}' (was ${last.apName}); reporting actual AP, inZone=$onHotspot")
                return current.copy(isAttached = onHotspot)
            }
            // SSID/BSSID unidentifiable (redacted/roaming) → HOLD. A device associated to an AP
            // cannot be "away", so never exit while a connection exists and we cannot name it.
            log("Wi-Fi connected but unidentifiable; holding safe zone ${last.apName}")
            return held
        }
        // Brief full disconnection: hold the zone for a bounded window regardless of motion. A worn
        // device moves constantly, so movement is NOT a departure signal; only sustained loss is.
        if (current.wifiEnabled && !current.hasWifiConnection &&
            now - lastConfirmedWifiAt <= SfdConfig.WIFI_DISCONNECT_HOLD_MAX_MS) {
            if (wifiHoldSince == 0L) wifiHoldSince = now
            log("Wi-Fi lost briefly (${(now - lastConfirmedWifiAt) / 1000}s); holding safe zone ${last.apName}")
            return held
        }
        // GPS-based departure decision: require POSITIVE evidence to exit. Only a VALID GPS fix far
        // from every home zone is a real departure; no fix or within radius → HOLD the safe zone.
        // This prevents the frequent false "이탈" when Wi-Fi drops/off but the device is still home.
        val gps = nonWifiGpsStatus()
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
        // reference a GPS fix cannot prove departure → HOLD rather than false-exit (this was the
        // cause of the repeated GPS "이탈" SMS while the device sat at home on dropped Wi-Fi).
        if (centers.isEmpty()) {
            log("Wi-Fi off & no home geofence center known; holding safe zone (cannot prove departure)")
            return held
        }
        val nearHome = centers.any { (cLat, cLng) ->
            distanceMeters(gLat, gLng, cLat, cLng) <= SfdConfig.GEOFENCE_RADIUS_M
        }
        if (nearHome) {
            log("Wi-Fi off but GPS within home radius; holding safe zone")
            return held
        }
        return current
    }

    /**
     * Distance from the nearest home center when GPS PROVES the device has left, else null.
     * Deliberately strict so it can override the Wi-Fi holds without reintroducing false exits:
     * a fix is only accepted when the device is well beyond the geofence (extra margin covers GPS
     * jitter). Requires at least one known home center — with none, departure is unprovable.
     */
    private fun gpsDepartureEvidence(): Int? {
        val centers = store.wifiSafeZones().mapNotNull { zone ->
            val cLat = zone.optDouble("centerLat", Double.NaN)
            val cLng = zone.optDouble("centerLng", Double.NaN)
            if (!cLat.isNaN() && !cLng.isNaN()) Pair(cLat, cLng) else null
        }
        if (centers.isEmpty()) return null
        val gps = nonWifiGpsStatus()
        val lat = gps.latitude ?: return null
        val lng = gps.longitude ?: return null
        val nearest = centers.minOf { (cLat, cLng) -> distanceMeters(lat, lng, cLat, cLng) }
        val threshold = SfdConfig.GEOFENCE_RADIUS_M + SfdConfig.GPS_DEPARTURE_MARGIN_M
        return if (nearest > threshold) nearest.toInt() else null
    }

    private fun isInSafeZone(wifi: WifiStatus): Boolean = wifi.isAttached || store.isBleSafeZoneActive()

    private fun locationSnapshot(wifi: WifiStatus): LocationSnapshot = when {
        wifi.isAttached -> {
            // REGISTERED_HOTSPOT (guardian hotspot) → live mobile GPS. FIXED_AP (home router) →
            // the stored home GPS center, so a fixed AP still carries coordinates for the map.
            val gps = if (wifiStatusReader.connectedToSsid(wifi, SfdConfig.HOTSPOT_SSID)) nonWifiGpsStatus() else null
            LocationSnapshot(
                locationType = "WIFI",
                apName = wifi.apName,
                bssid = wifi.bssid,
                signal = wifi.signal,
                latitude = gps?.latitude ?: store.safeZoneCenterLat(),
                longitude = gps?.longitude ?: store.safeZoneCenterLng(),
                accuracy = gps?.accuracy
            )
        }
        store.isBleSafeZoneActive() -> {
            val gps = nonWifiGpsStatus()
            // locationType stays inside the spec set (WIFI | GPS). The guardian-proximity zone is
            // reported as WIFI while an association exists (we are on the hotspot) and GPS otherwise.
            LocationSnapshot(
                locationType = if (wifi.hasWifiConnection) "WIFI" else "GPS",
                apName = wifi.apName,
                bssid = wifi.bssid,
                bluetoothName = store.bleSafeZoneName(),
                bluetoothAddress = store.bleSafeZoneAddress(),
                latitude = gps.latitude,
                longitude = gps.longitude,
                accuracy = gps.accuracy,
                signal = linkSignal(wifi)
            )
        }
        else -> {
            val gps = nonWifiGpsStatus()
            LocationSnapshot(
                locationType = "GPS",
                latitude = gps.latitude,
                longitude = gps.longitude,
                accuracy = gps.accuracy,
                signal = linkSignal(wifi)
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

    /**
     * RSSI of the link telemetry is actually travelling over: Wi-Fi RSSI while associated, else the
     * cellular RSSI (the report goes out over mobile data then, so a stale Wi-Fi value would lie).
     */
    private fun linkSignal(wifi: WifiStatus): Int {
        if (wifi.hasWifiConnection) return wifi.signal
        return runCatching {
            val tm = getSystemService(TelephonyManager::class.java)
            tm?.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm ?: wifi.signal
        }.getOrDefault(wifi.signal)
    }

    private fun deviceStatusFor(location: LocationSnapshot): String {
        // A nearly-flat battery is the most actionable operational state, and LOW_BATTERY is the
        // spec enum for it, so it takes priority over the location-derived states below.
        if (currentBatteryLevel() in 1..SfdConfig.LOW_BATTERY_PCT) return "LOW_BATTERY"
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

    private fun sendStatusSms(event: String, location: LocationSnapshot) {
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
            return "어르신(${store.currentState().deviceId})"
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
        if (phones.isEmpty()) return
        phones.forEach { phone ->
            runCatching {
                val sms = SmsManager.getDefault()
                val parts = sms.divideMessage(message)
                sms.sendMultipartTextMessage(phone, null, parts, null, null)
                log("SMS sent to $phone for $label")
            }.onFailure { log("SMS failed for $label to $phone: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private var lastCenterLearnAt = 0L

    /**
     * While attached to a FIXED_AP home Wi-Fi zone with a real GPS fix, record that fix as the
     * zone's geofence center if it has none. Gives the GPS geofence a reference (server strips
     * centerLat), so real departures are detectable and false "이탈" on Wi-Fi drop is avoided.
     * No-op once any zone has a center; throttled otherwise.
     */
    private fun learnHomeCenterIfPossible(attached: WifiStatus) {
        if (store.hasAnyWifiZoneCenter()) return
        val now = System.currentTimeMillis()
        if (now - lastCenterLearnAt < SfdConfig.CENTER_LEARN_INTERVAL_MS) return
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

    private enum class AwayPhase { ADVERTISING, CONNECTING }

    companion object {
        const val ACTION_STATE_CHANGED = "com.sf.sfd.STATE_CHANGED"
        const val ACTION_RESTART_TELEMETRY = "com.sf.sfd.RESTART_TELEMETRY"
        private const val NOTIFICATION_ID = 1007
    }
}
