#include "sfa_payload.h"
#include "sfa_config.h"
#include "sfa_util.h"

String sfaBuildRegisterPayload(const String& configJson) {
  JsonDocument cfg;
  deserializeJson(cfg, configJson);
  JsonDocument out;
  out["deviceId"] = (const char*)(cfg["deviceId"] | SFA_DEFAULT_DEVICE_ID);
  // Spec B-1 requires elderId + fwVersion on registration (elderName/guardian/safeZones stay as the
  // extra provisioning payload this server already consumes).
  out["elderId"] = (const char*)(cfg["elderId"] | "");
  out["fwVersion"] = SFA_FW_VERSION;
  out["elderName"] = (const char*)(cfg["elderName"] | "elder");
  if (!cfg["guardian"].isNull()) out["guardian"] = cfg["guardian"];
  else out["guardian"].to<JsonObject>();
  JsonArray rz = out["safeZones"].to<JsonArray>();
  JsonArray zones = cfg["safeZones"].as<JsonArray>();
  if (!zones.isNull()) {
    for (JsonObject z : zones) {
      const char* t = z["zoneType"] | "";
      if (strcasecmp(t, "WIFI") != 0) continue;
      JsonObject o = rz.add<JsonObject>();
      o["zoneType"] = "WIFI";
      const char* name = z["name"] | "";
      if (!*name) name = z["ssid"] | "HomeWiFi";
      o["name"] = name;
      o["bssid"] = (const char*)(z["bssid"] | "");
      const char* ssid = z["ssid"] | "";
      if (!*ssid) ssid = z["apName"] | "";
      o["ssid"] = ssid;
    }
  }
  String s; serializeJson(out, s); return s;
}

String sfaBuildTelemetryPayload(SfaStore& store,
                                const std::vector<GyroSample>& samples,
                                const LocationSnapshot& loc,
                                bool inSafeZone,
                                int battery,
                                const String& eventType,
                                const String& verb,
                                uint64_t durationMs,
                                const String& deviceStatus) {
  JsonDocument doc;
  doc["deviceId"] = store.deviceId();
  doc["fwVersion"] = SFA_FW_VERSION;
  doc["sentAt"] = sfaIsoNow();
  JsonArray readings = doc["readings"].to<JsonArray>();
  JsonObject r = readings.add<JsonObject>();

  // last N samples
  int total = samples.size();
  int startIdx = total > SFA_GYRO_REPORT_SAMPLE_COUNT ? total - SFA_GYRO_REPORT_SAMPLE_COUNT : 0;
  uint32_t newest = total ? samples[total - 1].timestampMs : millis();

  r["seq"] = store.nextTelemetrySeq();
  r["timestamp"] = sfaIsoNow();
  r["locationType"] = loc.locationType;
  r["inSafeZone"] = inSafeZone;
  r["battery"] = constrain(battery, 0, 100);
  r["signal"] = loc.signal;
  r["deviceStatus"] = deviceStatus;
  r["eventType"] = eventType;
  r["verb"] = verb;

  JsonObject gyro = r["gyro"].to<JsonObject>();
  gyro["intervalMs"] = SFA_GYRO_SAMPLE_PERIOD_MS;
  JsonArray gdata = gyro["data"].to<JsonArray>();
  for (int i = startIdx; i < total; i++) {
    JsonObject g = gdata.add<JsonObject>();
    g["offsetMs"] = (long)(samples[i].timestampMs - newest);
    g["gyX"] = samples[i].gyX;
    g["gyY"] = samples[i].gyY;
    g["gyZ"] = samples[i].gyZ;
  }

  // locationType is limited to the spec set (WIFI | GPS); the hotspot/BLE safe zone reports as WIFI.
  if (loc.locationType == "WIFI") {
    String apName = loc.apName;
    if (apName.isEmpty()) apName = store.firstWifiSsid();
    if (apName.isEmpty()) apName = loc.bssid;
    if (apName.isEmpty()) apName = store.firstWifiBssid();
    r["apName"] = apName;
    String bssid = loc.bssid; if (bssid.isEmpty()) bssid = store.firstWifiBssid();
    if (!bssid.isEmpty()) r["bssid"] = bssid;
  }

  if (inSafeZone) {
    String zid;
    if (loc.locationType == "WIFI") {
      String s = loc.apName; if (s.isEmpty()) s = store.firstWifiSsid();
      zid = store.safeZoneIdForSsid(s);
    }
    // Hotspot proximity zone: it has no registered Wi-Fi zone id, so fall back to the BLE zone id.
    if (zid.isEmpty()) zid = store.bleSafeZoneId();
    if (!zid.isEmpty()) r["safeZoneId"] = zid;
  }

  if (loc.hasCoords) {
    r["lat"] = loc.latitude;
    r["lng"] = loc.longitude;
    if (loc.accuracy > 0) r["accuracy"] = (int)loc.accuracy;
  }

  if (verb == "stayed") {
    JsonObject res = r["result"].to<JsonObject>();
    res["duration"] = (long)(durationMs / 1000ULL);
  } else if (verb == "moved") {
    JsonObject res = r["result"].to<JsonObject>();
    res["distance-m"] = 0;
    res["speed-kmh"] = 0;
  } else if (verb == "entered" || verb == "exited") {
    r["object"] = "zone";
  }

  String s; serializeJson(doc, s); return s;
}
