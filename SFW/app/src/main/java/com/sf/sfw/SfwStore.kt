package com.sf.sfw

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Operational-state store for SFW. Reads the provisioning config that
 * BleConfigServer already persists under SfwConfig.PREFS_NAME/PREF_CONFIG_JSON,
 * and adds the runtime state the telemetry loop needs (gyro window, escalation
 * timers, logs). Modeled on SFD's SfdStore, minus the BLE safe-zone and
 * server-sync features SFW does not have.
 */
class SfwStore(context: Context) {
    private val prefs = context.getSharedPreferences(SfwConfig.PREFS_NAME, Context.MODE_PRIVATE)

    fun isConfigured(): Boolean = configJson() != null && hasWifiSafeZone()

    fun configJson(): String? =
        prefs.getString(SfwConfig.PREF_CONFIG_JSON, null)?.takeIf { it.isNotBlank() }

    /** Writes the full provisioning config (used by Device-ID re-activation from the server). */
    fun saveConfigJson(json: String) {
        prefs.edit()
            .putString(SfwConfig.PREF_CONFIG_JSON, json)
            .putString(SfwConfig.PREF_CONFIG_UPDATED_AT, java.time.Instant.now().toString())
            .apply()
    }

    /** Server config version last synced to this device (-1 until the first sync). */
    fun configVersion(): Int = prefs.getInt("config_version", -1)

    fun saveConfigVersion(version: Int) {
        prefs.edit().putInt("config_version", version).apply()
    }

    /**
     * Rebuild the local config (safe zones + guardians) from the authoritative server data, keeping
     * the same config_json shape the rest of SFW reads. Triggered by a telemetry ACK's configChanged.
     */
    fun saveServerSync(elder: JSONObject, guardians: JSONArray, safeZones: JSONArray) {
        val existing = runCatching { JSONObject(configJson() ?: "{}") }.getOrDefault(JSONObject())
        val deviceId = elder.optString("deviceId").ifBlank { existing.optString("deviceId", SfwConfig.DEFAULT_DEVICE_ID) }
        val normalizedZones = JSONArray()
        for (i in 0 until safeZones.length()) {
            val z = safeZones.optJSONObject(i) ?: continue
            val nz = JSONObject()
                .put("zoneId", z.optString("zoneId"))
                .put("zoneType", z.optString("zoneType"))
                .put("name", z.optString("name"))
                .put("bssid", z.optString("bssid"))
                .put("ssid", z.optString("ssid"))
                .put("enabled", z.optBoolean("enabled", true))
            if (!z.isNull("centerLat")) nz.put("centerLat", z.optDouble("centerLat"))
            if (!z.isNull("centerLng")) nz.put("centerLng", z.optDouble("centerLng"))
            if (!z.isNull("bleId") && z.optString("bleId").isNotBlank()) nz.put("bleId", z.optString("bleId"))
            if (z.has("apType")) nz.put("apType", z.optString("apType"))
            normalizedZones.put(nz)
        }
        val normalizedGuardians = JSONArray()
        for (i in 0 until guardians.length()) {
            val g = guardians.optJSONObject(i) ?: continue
            normalizedGuardians.put(
                JSONObject()
                    .put("id", g.optInt("id"))
                    .put("name", g.optString("name"))
                    .put("phone", g.optString("phone"))
                    .put("relation", g.optString("relation"))
            )
        }
        val primary = normalizedGuardians.optJSONObject(0) ?: JSONObject()
        val config = existing
            .put("deviceId", deviceId)
            .put("elderName", elder.optString("name"))
            .put("elderId", elder.optString("elderId"))
            .put("guardian", JSONObject()
                .put("name", primary.optString("name"))
                .put("phone", primary.optString("phone"))
                .put("relation", primary.optString("relation")))
            .put("guardians", normalizedGuardians)
            .put("safeZones", normalizedZones)
        saveConfigJson(config.toString())
    }

    /** Wipes device data (reset), preserving only the telemetry seq so re-registration is not
     * duplicate-rejected by the server (dedupes by seq). */
    fun clearAll() {
        val keepSeq = prefs.getInt("next_telemetry_seq", 10500)
        prefs.edit().clear().putInt("next_telemetry_seq", keepSeq).apply()
    }

    fun deviceId(): String {
        val json = configJson() ?: return SfwConfig.DEFAULT_DEVICE_ID
        return runCatching { JSONObject(json).optString("deviceId").ifBlank { SfwConfig.DEFAULT_DEVICE_ID } }
            .getOrDefault(SfwConfig.DEFAULT_DEVICE_ID)
    }

    fun elderName(): String {
        val json = configJson() ?: return ""
        return runCatching { JSONObject(json).optString("elderName") }.getOrDefault("")
    }

    /**
     * Guardian phone numbers deduplicated on digits only, so the same number
     * written as "010-1234-5678" in `guardian` and "01012345678" in `guardians`
     * gets exactly one SMS (SFD currently double-sends in that case).
     */
    fun guardianPhones(): List<String> {
        val json = configJson() ?: return emptyList()
        return runCatching {
            val config = JSONObject(json)
            val raw = mutableListOf<String>()
            config.optJSONObject("guardian")?.optString("phone").orEmpty()
                .takeIf { it.isNotBlank() }?.let { raw += it }
            val guardians = config.optJSONArray("guardians") ?: JSONArray()
            for (index in 0 until guardians.length()) {
                val phone = guardians.optJSONObject(index)?.optString("phone").orEmpty()
                if (phone.isNotBlank()) raw += phone
            }
            val seenDigits = mutableSetOf<String>()
            raw.filter { phone ->
                val digits = phone.filter { it.isDigit() }
                digits.isNotEmpty() && seenDigits.add(digits)
            }
        }.getOrDefault(emptyList())
    }

    fun hasWifiSafeZone(): Boolean = wifiSafeZones().isNotEmpty()

    fun wifiSafeZones(): List<JSONObject> {
        val json = configJson() ?: return emptyList()
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

    fun firstWifiZoneName(): String {
        val zone = wifiSafeZones().firstOrNull() ?: return "No Wi-Fi Config"
        return zone.optString("name", zone.optString("ssid", "No Wi-Fi Config"))
    }

    fun firstWifiSsid(): String {
        val zone = wifiSafeZones().firstOrNull() ?: return ""
        return zone.optString("ssid", zone.optString("name", ""))
    }

    fun firstWifiBssid(): String {
        val zone = wifiSafeZones().firstOrNull() ?: return ""
        return zone.optString("bssid", "")
    }

    /** Stored GPS center of the (FIXED_AP) home zone, used as coords while on a fixed AP. */
    fun firstWifiCenterLat(): Double? = wifiSafeZones().firstOrNull()
        ?.takeIf { !it.isNull("centerLat") }?.optDouble("centerLat")?.takeIf { !it.isNaN() }

    fun firstWifiCenterLng(): Double? = wifiSafeZones().firstOrNull()
        ?.takeIf { !it.isNull("centerLng") }?.optDouble("centerLng")?.takeIf { !it.isNaN() }

    /** True when at least one WIFI safe zone already has a usable geofence center. */
    fun hasAnyWifiZoneCenter(): Boolean = wifiSafeZones().any {
        !it.isNull("centerLat") && !it.isNull("centerLng") &&
            !it.optDouble("centerLat", Double.NaN).isNaN() && !it.optDouble("centerLng", Double.NaN).isNaN()
    }

    /**
     * Self-learn the home geofence center: while the watch is genuinely at home (attached to a
     * FIXED_AP home Wi-Fi zone) and has a real GPS fix, record that fix as the matching zone's
     * center IF it has none. The server strips centerLat/Lng from /config and provisioning may not
     * have carried one, which left the GPS geofence with no reference and caused constant false
     * "이탈" when Wi-Fi dropped. Only fills a missing center (never overwrites a real one). Matches
     * the zone by bssid, then normalized ssid/name. Returns true if a center was written.
     */
    fun rememberZoneCenter(ssid: String, bssid: String, lat: Double, lng: Double): Boolean {
        if (lat.isNaN() || lng.isNaN()) return false
        val json = configJson() ?: return false
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
            if (apType.isNotBlank() && !apType.equals("FIXED_AP", ignoreCase = true)) continue // fixed home only
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
        if (changed) prefs.edit().putString(SfwConfig.PREF_CONFIG_JSON, config.toString()).apply()
        return changed
    }

    fun zoneNameForSsid(ssid: String): String {
        if (ssid.isBlank()) return ""
        val json = configJson() ?: return ""
        val zones = runCatching { JSONObject(json).optJSONArray("safeZones") }.getOrNull() ?: return ""
        for (index in 0 until zones.length()) {
            val zone = zones.optJSONObject(index) ?: continue
            if (zone.optString("ssid") == ssid) return zone.optString("name", ssid)
        }
        return ""
    }

    /**
     * Applies an incoming `safeZoneUpdate` message (CREATE/UPDATE/DELETE) by MERGING the zone into
     * the existing config's safeZones — never replacing the whole config. Provisioning sends a full
     * config; later zone edits from SFC arrive as these update messages, and treating them as a full
     * config would wipe elderName/guardian/other zones and drop the device out of the operational
     * state. Mirrors SFD's SfdStore.applySafeZoneUpdate. Returns true if handled.
     */
    fun applySafeZoneUpdate(updateJson: String): Boolean {
        val update = runCatching { JSONObject(updateJson) }.getOrNull() ?: return false
        if (!update.optString("messageType").equals("safeZoneUpdate", ignoreCase = true)) return false
        val action = update.optString("action").uppercase()
        val incoming = update.optJSONObject("zone") ?: return false
        val zoneId = incoming.optString("zoneId")
        val currentConfig = runCatching { JSONObject(configJson() ?: "{}") }.getOrNull() ?: JSONObject()
        if (!currentConfig.has("deviceId")) {
            currentConfig.put("deviceId", update.optString("deviceId", SfwConfig.DEFAULT_DEVICE_ID))
        }
        val zones = currentConfig.optJSONArray("safeZones") ?: JSONArray().also { currentConfig.put("safeZones", it) }
        when (action) {
            "CREATE" -> {
                var replaced = false
                for (index in 0 until zones.length()) {
                    val zone = zones.optJSONObject(index) ?: continue
                    if (zone.optString("zoneId") == zoneId && zoneId.isNotBlank()) {
                        zones.put(index, incoming); replaced = true; break
                    }
                }
                if (!replaced) zones.put(incoming)
            }
            "PATCH", "UPDATE" -> {
                for (index in 0 until zones.length()) {
                    val zone = zones.optJSONObject(index) ?: continue
                    if (zone.optString("zoneId") == zoneId || sameZone(zone, incoming)) {
                        incoming.keys().forEach { key -> zone.put(key, incoming.opt(key)) }
                        zones.put(index, zone); break
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
        prefs.edit().putString(SfwConfig.PREF_CONFIG_JSON, currentConfig.toString()).apply()
        appendLog("SafeZone update applied: $action ${incoming.optString("name", zoneId)}")
        return true
    }

    private fun sameZone(a: JSONObject, b: JSONObject): Boolean {
        val aBssid = a.optString("bssid"); val bBssid = b.optString("bssid")
        if (aBssid.isNotBlank() && aBssid.equals(bBssid, ignoreCase = true)) return true
        val aSsid = a.optString("ssid"); val bSsid = b.optString("ssid")
        return aSsid.isNotBlank() && aSsid.equals(bSsid, ignoreCase = true)
    }

    private fun zonesOrNull(): JSONArray? {
        val json = configJson() ?: return null
        return runCatching { JSONObject(json).optJSONArray("safeZones") }.getOrNull()
    }

    /** zoneId of the WIFI safe zone the device is attached to (matched by SSID/name). */
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
        return wifiSafeZones().firstOrNull()?.optString("zoneId").orEmpty()
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
     * Fills in missing zoneIds on the stored safe zones by matching the server's zones (bssid, then
     * ssid, then name). Provisioning does not carry zoneIds, so this back-fills them for telemetry
     * (safeZoneId) reporting. Returns true if anything changed.
     */
    fun ensureZoneIds(serverZones: JSONArray): Boolean {
        val json = configJson() ?: return false
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
        if (changed) prefs.edit().putString(SfwConfig.PREF_CONFIG_JSON, config.toString()).apply()
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

    fun saveLastZoneName(name: String) {
        prefs.edit().putString("last_zone_name", name).apply()
    }

    fun lastZoneName(): String = prefs.getString("last_zone_name", "") ?: ""

    fun saveLastVerb(verb: String) {
        prefs.edit().putString("last_verb", verb).apply()
    }

    fun lastVerb(): String = prefs.getString("last_verb", "") ?: ""

    fun addGyroSample(sample: GyroSample) {
        val samples = recentGyroSamples().toMutableList()
        samples += sample
        while (samples.size > SfwConfig.GYRO_REPORT_SAMPLE_COUNT) samples.removeAt(0)
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

    fun nextTelemetrySeq(): Int {
        val next = prefs.getInt("next_telemetry_seq", 10500)
        prefs.edit().putInt("next_telemetry_seq", next + 1).apply()
        return next
    }

    /** Advances the next seq past the server's latest so post-reset reports are not duplicates. */
    fun ensureSeqAtLeast(serverLatestSeq: Int): Boolean {
        if (serverLatestSeq <= 0) return false
        val current = prefs.getInt("next_telemetry_seq", 10500)
        if (serverLatestSeq + 1 > current) {
            prefs.edit().putInt("next_telemetry_seq", serverLatestSeq + 1).apply()
            return true
        }
        return false
    }

    // Escalation state persisted across process restarts so escalation timers
    // resume instead of resetting (same keys/pattern as SfdStore).
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

    fun saveTelemetryResult(text: String) {
        prefs.edit().putString("last_telemetry_result", text).apply()
        appendLog(text)
    }

    fun lastTelemetryResult(): String = prefs.getString("last_telemetry_result", "") ?: ""

    fun appendLog(text: String) {
        val logs = logs().toMutableList()
        logs.add(0, text)
        while (logs.size > 10) logs.removeAt(logs.lastIndex)
        prefs.edit().putString("logs", JSONArray(logs).toString()).apply()
    }

    fun logs(): List<String> {
        val raw = prefs.getString("logs", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.optString(it) }
        }.getOrDefault(emptyList())
    }
}
