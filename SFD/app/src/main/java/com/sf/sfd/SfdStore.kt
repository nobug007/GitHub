package com.sf.sfd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SfdStore(context: Context) {
    private val prefs = context.getSharedPreferences("sfd_store", Context.MODE_PRIVATE)

    fun clearAll() {
        // Preserve the telemetry seq across a reset so a re-registered device does not restart at a
        // lower seq and get its reports rejected as duplicates by the server (dedupes by seq).
        val keepSeq = prefs.getInt("next_telemetry_seq", 10500)
        prefs.edit().clear().putInt("next_telemetry_seq", keepSeq).apply()
        appendLog("Device data reset")
    }
    fun saveConfig(json: String) {
        var deviceId = SfdConfig.DEFAULT_DEVICE_ID
        val editor = prefs.edit().putString("config_json", json)
        runCatching {
            val config = JSONObject(json)
            deviceId = config.optString("deviceId", SfdConfig.DEFAULT_DEVICE_ID)
            // Provisioning payload (req 4): a fixed-AP WIFI zone may carry the home GPS
            // coordinates (centerLat/centerLng) and a movable/hotspot zone may carry this
            // SFD's own BLE address (bleId). Persist them for later GPS/telemetry use.
            val zones = config.optJSONArray("safeZones")
            var centerLat = Double.NaN
            var centerLng = Double.NaN
            var bleId = ""
            if (zones != null) {
                for (index in 0 until zones.length()) {
                    val zone = zones.optJSONObject(index) ?: continue
                    if (zone.has("centerLat") && zone.has("centerLng")) {
                        centerLat = zone.optDouble("centerLat", Double.NaN)
                        centerLng = zone.optDouble("centerLng", Double.NaN)
                    }
                    val zoneBleId = zone.optString("bleId")
                    if (zoneBleId.isNotBlank()) bleId = zoneBleId
                }
            }
            if (bleId.isBlank()) bleId = config.optString("bleId")
            if (!centerLat.isNaN() && !centerLng.isNaN()) {
                editor.putString("safezone_center_lat", centerLat.toString())
                editor.putString("safezone_center_lng", centerLng.toString())
            }
            if (bleId.isNotBlank()) editor.putString("device_ble_id", bleId)
        }
        editor.putString("device_id", deviceId).apply()
        appendLog("Config saved for $deviceId")
    }

    fun safeZoneCenterLat(): Double? = prefs.getString("safezone_center_lat", null)?.toDoubleOrNull()

    fun safeZoneCenterLng(): Double? = prefs.getString("safezone_center_lng", null)?.toDoubleOrNull()

    fun deviceBleId(): String = prefs.getString("device_ble_id", "") ?: ""

    fun applySafeZoneUpdate(updateJson: String): Boolean {
        val update = JSONObject(updateJson)
        val action = update.optString("action").uppercase()
        val incoming = update.optJSONObject("zone") ?: return false
        val zoneId = incoming.optString("zoneId")
        val currentConfig = JSONObject(prefs.getString("config_json", null) ?: JSONObject().toString())
        if (!currentConfig.has("deviceId")) {
            currentConfig.put("deviceId", update.optString("deviceId", SfdConfig.DEFAULT_DEVICE_ID))
        }
        val zones = currentConfig.optJSONArray("safeZones") ?: JSONArray().also { currentConfig.put("safeZones", it) }

        when (action) {
            "CREATE" -> {
                var replaced = false
                for (index in 0 until zones.length()) {
                    val zone = zones.optJSONObject(index) ?: continue
                    if (zone.optString("zoneId") == zoneId && zoneId.isNotBlank()) {
                        zones.put(index, incoming)
                        replaced = true
                        break
                    }
                }
                if (!replaced) zones.put(incoming)
            }
            "PATCH", "UPDATE" -> {
                for (index in 0 until zones.length()) {
                    val zone = zones.optJSONObject(index) ?: continue
                    if (zone.optString("zoneId") == zoneId || sameZone(zone, incoming)) {
                        incoming.keys().forEach { key -> zone.put(key, incoming.opt(key)) }
                        zones.put(index, zone)
                        break
                    }
                }
            }
            "DELETE" -> {
                val next = JSONArray()
                for (index in 0 until zones.length()) {
                    val zone = zones.optJSONObject(index) ?: continue
                    if (zone.optString("zoneId") == zoneId || sameZone(zone, incoming)) continue
                    next.put(zone)
                }
                currentConfig.put("safeZones", next)
            }
            else -> return false
        }

        prefs.edit()
            .putString("config_json", currentConfig.toString())
            .putString("device_id", currentConfig.optString("deviceId", SfdConfig.DEFAULT_DEVICE_ID))
            .apply()
        appendLog("SafeZone update applied: $action ${incoming.optString("name", zoneId)}")
        appendMessage("SAFEZONE UPDATE", updateJson)
        return true
    }

    fun saveBleSafeZone(connected: Boolean, address: String = "", name: String = "") {
        val editor = prefs.edit()
            .putBoolean("ble_safe_connected", connected)
            .putString("ble_safe_address", address)
            .putString("ble_safe_name", name)
            .putLong("ble_safe_at", System.currentTimeMillis())
        if (connected) {
            editor.putLong("ble_safe_confirmed_at", System.currentTimeMillis())
        }
        editor.apply()
    }

    /**
     * Hard-clears the BLE/hotspot ("보호자 근접") safe zone including the grace window. Used when
     * the device cannot possibly be on the guardian hotspot (e.g. Wi-Fi is off), so no stale grace
     * keeps reporting a safe zone. The proximity safe zone is only real while joined to the hotspot.
     */
    fun resetBleSafeZone() {
        prefs.edit()
            .putBoolean("ble_safe_connected", false)
            .putLong("ble_safe_confirmed_at", 0L)
            .apply()
    }

    fun isBleSafeZoneActive(): Boolean {
        if (prefs.getBoolean("ble_safe_connected", false)) return true
        // SFC only rescans for SFD every ~60s (SfcBleMonitorService), so a live BLE link
        // drops and reconnects routinely even while the phone never left the safe zone.
        // Mirror the WiFi grace period (effectiveWifiStatus) instead of flipping to
        // "exited" on every transient disconnect.
        val confirmedAt = prefs.getLong("ble_safe_confirmed_at", 0L)
        if (confirmedAt == 0L) return false
        return System.currentTimeMillis() - confirmedAt <= SfdConfig.BLE_SAFEZONE_GRACE_MS
    }

    fun saveEscalationState(
        outsideStateStartedAt: Long?,
        gpsModeStartedAt: Long?,
        warningSent: Boolean,
        emergencySent: Boolean,
        lastLocationType: String?
    ) {
        prefs.edit()
            .putLong("esc_outside_started_at", outsideStateStartedAt ?: 0L)
            .putLong("esc_gps_mode_started_at", gpsModeStartedAt ?: 0L)
            .putBoolean("esc_warning_sent", warningSent)
            .putBoolean("esc_emergency_sent", emergencySent)
            .putString("esc_last_location_type", lastLocationType ?: "")
            .apply()
    }

    fun clearEscalationState() {
        prefs.edit()
            .remove("esc_outside_started_at")
            .remove("esc_gps_mode_started_at")
            .remove("esc_warning_sent")
            .remove("esc_emergency_sent")
            .remove("esc_last_location_type")
            .apply()
    }

    fun escalationOutsideStartedAt(): Long? =
        prefs.getLong("esc_outside_started_at", 0L).takeIf { it > 0L }

    fun escalationGpsModeStartedAt(): Long? =
        prefs.getLong("esc_gps_mode_started_at", 0L).takeIf { it > 0L }

    fun escalationWarningSent(): Boolean = prefs.getBoolean("esc_warning_sent", false)

    fun escalationEmergencySent(): Boolean = prefs.getBoolean("esc_emergency_sent", false)

    fun escalationLastLocationType(): String? =
        prefs.getString("esc_last_location_type", null)?.takeIf { it.isNotBlank() }

    fun bleSafeZoneName(): String = prefs.getString("ble_safe_name", "") ?: ""

    fun bleSafeZoneAddress(): String = prefs.getString("ble_safe_address", "") ?: ""

    fun guardianPhone(): String {
        val json = prefs.getString("config_json", null) ?: return ""
        return runCatching { JSONObject(json).optJSONObject("guardian")?.optString("phone").orEmpty() }.getOrDefault("")
    }

    fun guardianPhones(): List<String> {
        val json = prefs.getString("config_json", null) ?: return emptyList()
        return runCatching {
            val config = JSONObject(json)
            // Dedupe by digits only: the same guardian arrives as "010-7260-8813"
            // from provisioning and "01072608813" from server sync.
            val seenDigits = linkedSetOf<String>()
            val phones = mutableListOf<String>()
            fun add(phone: String) {
                val digits = phone.filter { it.isDigit() }
                if (digits.isNotBlank() && seenDigits.add(digits)) phones += phone
            }
            config.optJSONObject("guardian")?.optString("phone").orEmpty().takeIf { it.isNotBlank() }?.let { add(it) }
            val guardians = config.optJSONArray("guardians") ?: JSONArray()
            for (index in 0 until guardians.length()) {
                val phone = guardians.optJSONObject(index)?.optString("phone").orEmpty()
                if (phone.isNotBlank()) add(phone)
            }
            phones.toList()
        }.getOrDefault(emptyList())
    }

    fun elderId(): String {
        val json = prefs.getString("config_json", null) ?: return ""
        return runCatching { JSONObject(json).optString("elderId") }.getOrDefault("")
    }

    fun elderName(): String {
        val json = prefs.getString("config_json", null) ?: return ""
        return runCatching { JSONObject(json).optString("elderName") }.getOrDefault("")
    }

    fun saveServerSync(elder: JSONObject, guardians: JSONArray, safeZones: JSONArray) {
        val deviceId = elder.optString("deviceId", prefs.getString("device_id", SfdConfig.DEFAULT_DEVICE_ID))
        val normalizedZones = JSONArray()
        for (index in 0 until safeZones.length()) {
            val zone = safeZones.optJSONObject(index) ?: continue
            val normalized = JSONObject()
                .put("zoneId", zone.optString("zoneId"))
                .put("zoneType", zone.optString("zoneType"))
                .put("name", zone.optString("name"))
                .put("bssid", zone.optString("bssid"))
                .put("ssid", zone.optString("ssid"))
                .put("enabled", zone.optBoolean("enabled", true))
            // Preserve GPS center + bleId when present so geofence/apType logic keeps working.
            if (!zone.isNull("centerLat")) normalized.put("centerLat", zone.optDouble("centerLat"))
            if (!zone.isNull("centerLng")) normalized.put("centerLng", zone.optDouble("centerLng"))
            if (!zone.isNull("bleId") && zone.optString("bleId").isNotBlank()) normalized.put("bleId", zone.optString("bleId"))
            if (zone.has("apType")) normalized.put("apType", zone.optString("apType"))
            normalizedZones.put(normalized)
        }
        val normalizedGuardians = JSONArray()
        for (index in 0 until guardians.length()) {
            val guardian = guardians.optJSONObject(index) ?: continue
            normalizedGuardians.put(JSONObject()
                .put("id", guardian.optInt("id"))
                .put("name", guardian.optString("name"))
                .put("phone", guardian.optString("phone"))
                .put("relation", guardian.optString("relation"))
            )
        }
        val primaryGuardian = normalizedGuardians.optJSONObject(0) ?: JSONObject()
        val config = JSONObject(prefs.getString("config_json", null) ?: "{}")
            .put("deviceId", deviceId)
            .put("elderName", elder.optString("name"))
            .put("elderId", elder.optString("elderId"))
            .put("guardian", JSONObject()
                .put("name", primaryGuardian.optString("name"))
                .put("phone", primaryGuardian.optString("phone"))
                .put("relation", primaryGuardian.optString("relation"))
            )
            .put("guardians", normalizedGuardians)
            .put("safeZones", normalizedZones)

        prefs.edit()
            .putString("config_json", config.toString())
            .putString("device_id", deviceId)
            .apply()
        appendLog("Config sync saved: guardians=${normalizedGuardians.length()}, safeZones=${normalizedZones.length()}")
        appendMessage("CONFIG SYNC", config.toString())
    }

    fun saveLastVerb(verb: String) {
        prefs.edit().putString("last_verb", verb).apply()
    }

    fun lastVerb(): String = prefs.getString("last_verb", "") ?: ""

    fun saveRegisterResult(text: String) {
        prefs.edit().putString("last_register_result", text).apply()
        appendLog(text)
        appendMessage("REGISTER RESPONSE", text)
    }

    fun saveTelemetryResult(text: String) {
        prefs.edit()
            .putString("last_telemetry_result", text)
            .putString("last_server_response", text)
            .apply()
        appendLog(text)
        appendMessage("TELEMETRY RESPONSE", text)
    }

    fun saveOutgoingTelemetry(payload: String) {
        appendMessage("TELEMETRY REQUEST", payload)
        prefs.edit()
            .putString("last_tx_payload", payload)
            .putLong("last_tx_at", System.currentTimeMillis())
            .apply()
    }

    fun nextTelemetrySeq(): Int {
        val next = prefs.getInt("next_telemetry_seq", 10500)
        prefs.edit().putInt("next_telemetry_seq", next + 1).apply()
        return next
    }

    /**
     * Ensures the next telemetry seq is past the server's latest so reports sent after a reset are
     * not rejected as duplicates (the server dedupes by seq). Returns true if the counter advanced.
     */
    /** Server config version last synced to this device (-1 until the first sync). */
    fun configVersion(): Int = prefs.getInt("config_version", -1)

    fun saveConfigVersion(version: Int) {
        prefs.edit().putInt("config_version", version).apply()
    }

    fun ensureSeqAtLeast(serverLatestSeq: Int): Boolean {
        if (serverLatestSeq <= 0) return false
        val current = prefs.getInt("next_telemetry_seq", 10500)
        if (serverLatestSeq + 1 > current) {
            prefs.edit().putInt("next_telemetry_seq", serverLatestSeq + 1).apply()
            return true
        }
        return false
    }

    /** (sentAtEpochMs, requestPayload, responseAtEpochMs, responseBody) of the last telemetry send. */
    fun lastTelemetryExchange(): List<String> = listOf(
        prefs.getLong("last_tx_at", 0L).toString(),
        prefs.getString("last_tx_payload", "").orEmpty(),
        prefs.getLong("last_tx_response_at", 0L).toString(),
        prefs.getString("last_tx_response", "").orEmpty()
    )


    fun saveServerResponse(text: String) {
        prefs.edit()
            .putString("last_server_response", text)
            .putString("last_tx_response", text)
            .putLong("last_tx_response_at", System.currentTimeMillis())
            .apply()
    }

    fun isConfigured(): Boolean = prefs.getString("config_json", null) != null && hasWifiSafeZone()

    fun addGyroSample(sample: GyroSample) {
        val samples = recentGyroSamples().toMutableList()
        samples += sample
        while (samples.size > SfdConfig.GYRO_REPORT_SAMPLE_COUNT) samples.removeAt(0)
        val array = JSONArray()
        samples.forEach {
            array.put(
                JSONObject()
                    .put("timestampMs", it.timestampMs)
                    .put("gyX", it.gyX)
                    .put("gyY", it.gyY)
                    .put("gyZ", it.gyZ)
            )
        }
        prefs.edit().putString("gyro_samples", array.toString()).apply()
    }

    fun recentGyroSamples(): List<GyroSample> {
        val raw = prefs.getString("gyro_samples", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                GyroSample(
                    timestampMs = item.optLong("timestampMs"),
                    gyX = item.optDouble("gyX"),
                    gyY = item.optDouble("gyY"),
                    gyZ = item.optDouble("gyZ")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun appendLog(text: String) {
        val logs = logs().toMutableList()
        logs.add(0, text)
        while (logs.size > 10) logs.removeAt(logs.lastIndex)
        prefs.edit().putString("logs", JSONArray(logs).toString()).apply()
    }

    fun logs(): List<String> = readStringArray("logs")

    fun appendMessage(kind: String, text: String) {
        val messages = messageHistory().toMutableList()
        messages.add(0, "[$kind]\n${text.take(3000)}")
        while (messages.size > 20) messages.removeAt(messages.lastIndex)
        prefs.edit().putString("message_history", JSONArray(messages).toString()).apply()
    }

    fun messageHistory(): List<String> = readStringArray("message_history")

    fun currentState(): SfdState = SfdState(
        isConfigured = isConfigured(),
        deviceId = prefs.getString("device_id", SfdConfig.DEFAULT_DEVICE_ID) ?: SfdConfig.DEFAULT_DEVICE_ID,
        configJson = prefs.getString("config_json", null),
        lastRegisterResult = prefs.getString("last_register_result", null),
        lastTelemetryResult = prefs.getString("last_telemetry_result", null),
        lastServerResponse = prefs.getString("last_server_response", null),
        logs = logs(),
        messageHistory = messageHistory(),
        gyroSamples = recentGyroSamples()
    )

    fun hasWifiSafeZone(): Boolean = wifiSafeZone() != null

    fun wifiSafeZones(): List<JSONObject> {
        val json = prefs.getString("config_json", null) ?: return emptyList()
        return runCatching {
            val zones = JSONObject(json).optJSONArray("safeZones") ?: return emptyList()
            val result = mutableListOf<JSONObject>()
            for (index in 0 until zones.length()) {
                val zone = zones.optJSONObject(index) ?: continue
                if (zone.optString("zoneType").equals("WIFI", ignoreCase = true) && zone.optBoolean("enabled", true)) {
                    result += zone
                }
            }
            result
        }.getOrDefault(emptyList())
    }

    fun wifiSafeZone(): JSONObject? {
        return wifiSafeZones().firstOrNull()
    }

    /** True when at least one WIFI safe zone already has a usable geofence center. */
    fun hasAnyWifiZoneCenter(): Boolean = wifiSafeZones().any {
        !it.isNull("centerLat") && !it.isNull("centerLng") &&
            !it.optDouble("centerLat", Double.NaN).isNaN() && !it.optDouble("centerLng", Double.NaN).isNaN()
    }

    /**
     * Self-learn the home geofence center from a real GPS fix while attached to a FIXED_AP home
     * zone that has none. The server strips centerLat/Lng from /config, which left the GPS geofence
     * with no reference and caused false "이탈" when Wi-Fi dropped. Only fills a missing center
     * (never overwrites). Matches by bssid, then normalized ssid/name. Returns true if written.
     */
    fun rememberZoneCenter(ssid: String, bssid: String, lat: Double, lng: Double): Boolean {
        if (lat.isNaN() || lng.isNaN()) return false
        val json = prefs.getString("config_json", null) ?: return false
        val config = runCatching { JSONObject(json) }.getOrNull() ?: return false
        val zones = config.optJSONArray("safeZones") ?: return false
        fun norm(v: String) = v.trim().trim('"')
            .removeSuffix("_5G").removeSuffix("_2G").removeSuffix("-5G").removeSuffix("-2G")
            .removeSuffix(" 5G").removeSuffix(" 2G")
        var changed = false
        for (index in 0 until zones.length()) {
            val zone = zones.optJSONObject(index) ?: continue
            if (!zone.optString("zoneType").equals("WIFI", ignoreCase = true)) continue
            val apType = zone.optString("apType")
            if (apType.isNotBlank() && !apType.equals("FIXED_AP", ignoreCase = true)) continue
            val zb = zone.optString("bssid")
            val matchBssid = bssid.isNotBlank() && zb.isNotBlank() && bssid.equals(zb, ignoreCase = true)
            val matchName = ssid.isNotBlank() && (
                norm(ssid).equals(norm(zone.optString("ssid")), ignoreCase = true) ||
                norm(ssid).equals(norm(zone.optString("name")), ignoreCase = true))
            if (!matchBssid && !matchName) continue
            val hasCenter = !zone.isNull("centerLat") && !zone.isNull("centerLng") &&
                !zone.optDouble("centerLat", Double.NaN).isNaN() && !zone.optDouble("centerLng", Double.NaN).isNaN()
            if (!hasCenter) {
                zone.put("centerLat", lat).put("centerLng", lng)
                changed = true
            }
        }
        if (changed) prefs.edit().putString("config_json", config.toString()).apply()
        return changed
    }

    fun firstWifiZoneName(): String {
        val zone = wifiSafeZone() ?: return "No Wi-Fi Config"
        return zone.optString("name", zone.optString("ssid", "No Wi-Fi Config"))
    }

    fun firstWifiSsid(): String {
        val zone = wifiSafeZone() ?: return ""
        return zone.optString("ssid", zone.optString("name", ""))
    }

    fun zoneNameForSsid(ssid: String): String {
        if (ssid.isBlank()) return ""
        val json = prefs.getString("config_json", null) ?: return ""
        val zones = runCatching { JSONObject(json).optJSONArray("safeZones") }.getOrNull() ?: return ""
        for (index in 0 until zones.length()) {
            val zone = zones.optJSONObject(index) ?: continue
            if (zone.optString("ssid") == ssid) return zone.optString("name", ssid)
        }
        return ""
    }

    private fun zonesOrNull(): JSONArray? {
        val json = prefs.getString("config_json", null) ?: return null
        return runCatching { JSONObject(json).optJSONArray("safeZones") }.getOrNull()
    }

    /** True when any stored safe zone is missing its server zoneId (so a sync is worthwhile). */
    fun hasMissingZoneIds(): Boolean {
        val zones = zonesOrNull() ?: return false
        for (index in 0 until zones.length()) {
            val zone = zones.optJSONObject(index) ?: continue
            if (zone.optString("zoneId").isBlank()) return true
        }
        return false
    }

    /**
     * Fills in missing zoneIds on the stored safe zones by matching the server's zones (by
     * bssid, then ssid, then name). Returns true if anything changed. Provisioning does not carry
     * zoneIds, so this back-fills them from the server for telemetry (safeZoneId) reporting.
     */
    fun ensureZoneIds(serverZones: JSONArray): Boolean {
        val json = prefs.getString("config_json", null) ?: return false
        val config = runCatching { JSONObject(json) }.getOrNull() ?: return false
        val zones = config.optJSONArray("safeZones") ?: return false
        var changed = false
        for (index in 0 until zones.length()) {
            val zone = zones.optJSONObject(index) ?: continue
            if (zone.optString("zoneId").isNotBlank()) continue
            val match = matchServerZone(zone, serverZones) ?: continue
            val id = match.optString("zoneId")
            if (id.isNotBlank()) {
                zone.put("zoneId", id)
                changed = true
            }
        }
        if (changed) prefs.edit().putString("config_json", config.toString()).apply()
        return changed
    }

    private fun matchServerZone(local: JSONObject, serverZones: JSONArray): JSONObject? {
        val ssid = local.optString("ssid")
        val bssid = local.optString("bssid")
        val name = local.optString("name")
        for (index in 0 until serverZones.length()) {
            val zone = serverZones.optJSONObject(index) ?: continue
            if (bssid.isNotBlank() && zone.optString("bssid").equals(bssid, ignoreCase = true)) return zone
            if (ssid.isNotBlank() && zone.optString("ssid").equals(ssid, ignoreCase = true)) return zone
            if (name.isNotBlank() && zone.optString("name").equals(name, ignoreCase = true)) return zone
        }
        return null
    }

    /** zoneId of the WIFI safe zone the device is currently attached to (matched by SSID/name). */
    fun safeZoneIdForSsid(ssid: String): String {
        val zones = zonesOrNull() ?: return ""
        if (ssid.isNotBlank()) {
            for (index in 0 until zones.length()) {
                val zone = zones.optJSONObject(index) ?: continue
                if (zone.optString("ssid").equals(ssid, ignoreCase = true) ||
                    zone.optString("name").equals(ssid, ignoreCase = true)
                ) {
                    val id = zone.optString("zoneId")
                    if (id.isNotBlank()) return id
                }
            }
        }
        // Fall back to the configured WIFI zone (usually the single home zone).
        return wifiSafeZone()?.optString("zoneId").orEmpty()
    }

    /** zoneId of the BLE / hotspot ("보호자 근접") safe zone, if one is configured. */
    fun bleSafeZoneId(): String {
        val zones = zonesOrNull() ?: return ""
        for (index in 0 until zones.length()) {
            val zone = zones.optJSONObject(index) ?: continue
            val type = zone.optString("zoneType")
            if (type.equals("BLE", ignoreCase = true) || type.equals("HOTSPOT", ignoreCase = true) ||
                zone.optString("ssid").equals(SfdConfig.HOTSPOT_SSID, ignoreCase = true)
            ) {
                val id = zone.optString("zoneId")
                if (id.isNotBlank()) return id
            }
        }
        return ""
    }

    fun saveLastZoneName(name: String) {
        prefs.edit().putString("last_zone_name", name).apply()
    }

    fun lastZoneName(): String = prefs.getString("last_zone_name", "") ?: ""

    fun firstWifiPassword(): String {
        val zone = wifiSafeZone() ?: return ""
        return zone.optString("password", "")
    }

    fun firstWifiBssid(): String {
        val zone = wifiSafeZone() ?: return ""
        return zone.optString("bssid", "")
    }

    private fun readStringArray(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.optString(it) }
        }.getOrDefault(emptyList())
    }

    private fun sameZone(left: JSONObject, right: JSONObject): Boolean {
        val leftBssid = left.optString("bssid")
        val rightBssid = right.optString("bssid")
        if (leftBssid.isNotBlank() && rightBssid.isNotBlank() && leftBssid.equals(rightBssid, ignoreCase = true)) return true
        val leftSsid = left.optString("ssid")
        val rightSsid = right.optString("ssid")
        return leftSsid.isNotBlank() && rightSsid.isNotBlank() && leftSsid == rightSsid
    }
}

