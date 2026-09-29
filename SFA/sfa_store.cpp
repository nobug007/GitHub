#include "sfa_store.h"
#include "sfa_config.h"
#include "sfa_util.h"

static const char* NS = "sfa";

void SfaStore::begin() { /* Preferences opened per-op to keep NVS handles short-lived */ }

String SfaStore::getStr(const char* key, const char* def) {
  p_.begin(NS, true);
  String v = p_.getString(key, def);
  p_.end();
  return v;
}
void SfaStore::putStr(const char* key, const String& v) {
  p_.begin(NS, false);
  p_.putString(key, v);
  p_.end();
}

bool SfaStore::loadConfig(JsonDocument& doc) {
  String json = configJson();
  if (json.isEmpty()) return false;
  return deserializeJson(doc, json) == DeserializationError::Ok;
}

String SfaStore::configJson() { return getStr("config_json", ""); }

bool SfaStore::hasWifiSafeZone() {
  JsonDocument doc;
  if (!loadConfig(doc)) return false;
  JsonArray zones = doc["safeZones"].as<JsonArray>();
  if (zones.isNull()) return false;
  for (JsonObject z : zones) if (sfaZoneIsWifiEnabled(z)) return true;
  return false;
}

bool SfaStore::isConfigured() { return !configJson().isEmpty() && hasWifiSafeZone(); }

String SfaStore::deviceId() { return getStr("device_id", SFA_DEFAULT_DEVICE_ID); }

void SfaStore::saveConfig(const String& json) {
  JsonDocument doc;
  String deviceId = SFA_DEFAULT_DEVICE_ID;
  double centerLat = NAN, centerLng = NAN;
  String bleId = "";
  if (deserializeJson(doc, json) == DeserializationError::Ok) {
    deviceId = String((const char*)(doc["deviceId"] | SFA_DEFAULT_DEVICE_ID));
    JsonArray zones = doc["safeZones"].as<JsonArray>();
    if (!zones.isNull()) {
      for (JsonObject z : zones) {
        if (z["centerLat"].is<double>() && z["centerLng"].is<double>()) {
          centerLat = z["centerLat"].as<double>();
          centerLng = z["centerLng"].as<double>();
        }
        const char* zb = z["bleId"] | "";
        if (strlen(zb) > 0) bleId = zb;
      }
    }
    if (bleId.isEmpty()) bleId = String((const char*)(doc["bleId"] | ""));
  }
  p_.begin(NS, false);
  p_.putString("config_json", json);
  p_.putString("device_id", deviceId);
  if (!isnan(centerLat) && !isnan(centerLng)) {
    p_.putDouble("center_lat", centerLat);
    p_.putDouble("center_lng", centerLng);
  }
  if (!bleId.isEmpty()) p_.putString("device_ble_id", bleId);
  p_.end();
}

bool SfaStore::safeZoneCenter(double& lat, double& lng) {
  p_.begin(NS, true);
  bool has = p_.isKey("center_lat") && p_.isKey("center_lng");
  if (has) { lat = p_.getDouble("center_lat"); lng = p_.getDouble("center_lng"); }
  p_.end();
  return has;
}

String SfaStore::deviceBleId() { return getStr("device_ble_id", ""); }

String SfaStore::elderName() {
  JsonDocument doc; if (!loadConfig(doc)) return "";
  return String((const char*)(doc["elderName"] | ""));
}
String SfaStore::elderId() {
  JsonDocument doc; if (!loadConfig(doc)) return "";
  return String((const char*)(doc["elderId"] | ""));
}

static String digitsOnly(const String& s) {
  String d; for (size_t i = 0; i < s.length(); i++) if (isdigit((int)s[i])) d += s[i];
  return d;
}

std::vector<String> SfaStore::guardianPhones() {
  std::vector<String> phones;
  JsonDocument doc; if (!loadConfig(doc)) return phones;
  std::vector<String> seen;
  auto add = [&](const char* raw) {
    if (!raw || !*raw) return;
    String d = digitsOnly(String(raw));
    if (d.isEmpty()) return;
    for (auto& s : seen) if (s == d) return;
    seen.push_back(d);
    phones.push_back(String(raw));
  };
  JsonObject g = doc["guardian"].as<JsonObject>();
  if (!g.isNull()) add(g["phone"] | "");
  JsonArray gs = doc["guardians"].as<JsonArray>();
  if (!gs.isNull()) for (JsonObject gg : gs) add(gg["phone"] | "");
  return phones;
}

uint32_t SfaStore::nextTelemetrySeq() {
  p_.begin(NS, false);
  uint32_t next = p_.getUInt("seq", SFA_SEQ_BASE);
  p_.putUInt("seq", next + 1);
  p_.end();
  return next;
}
bool SfaStore::ensureSeqAtLeast(uint32_t serverLatest) {
  if (serverLatest == 0) return false;
  p_.begin(NS, false);
  uint32_t cur = p_.getUInt("seq", SFA_SEQ_BASE);
  bool changed = false;
  if (serverLatest + 1 > cur) { p_.putUInt("seq", serverLatest + 1); changed = true; }
  p_.end();
  return changed;
}

void SfaStore::saveEscalation(const EscalationState& s) {
  p_.begin(NS, false);
  p_.putULong64("esc_out", s.outsideStartedAtMs);
  p_.putULong64("esc_gps", s.gpsModeStartedAtMs);
  p_.putBool("esc_warn", s.warningSent);
  p_.putBool("esc_emg", s.emergencySent);
  p_.putString("esc_loc", s.lastLocationType);
  p_.end();
}
EscalationState SfaStore::loadEscalation() {
  EscalationState s;
  p_.begin(NS, true);
  s.outsideStartedAtMs = p_.getULong64("esc_out", 0);
  s.gpsModeStartedAtMs = p_.getULong64("esc_gps", 0);
  s.warningSent = p_.getBool("esc_warn", false);
  s.emergencySent = p_.getBool("esc_emg", false);
  s.lastLocationType = p_.getString("esc_loc", "");
  p_.end();
  return s;
}
void SfaStore::clearEscalation() {
  p_.begin(NS, false);
  p_.remove("esc_out"); p_.remove("esc_gps"); p_.remove("esc_warn");
  p_.remove("esc_emg"); p_.remove("esc_loc");
  p_.end();
}

void SfaStore::saveBleSafeZone(bool connected, const String& address, const String& name) {
  p_.begin(NS, false);
  p_.putBool("ble_conn", connected);
  p_.putString("ble_addr", address);
  p_.putString("ble_name", name);
  p_.putULong64("ble_at", sfaNowMs());
  if (connected) p_.putULong64("ble_ok_at", sfaNowMs());
  p_.end();
}
void SfaStore::resetBleSafeZone() {
  p_.begin(NS, false);
  p_.putBool("ble_conn", false);
  p_.putULong64("ble_ok_at", 0);
  p_.end();
}
bool SfaStore::isBleSafeZoneActive() {
  p_.begin(NS, true);
  bool conn = p_.getBool("ble_conn", false);
  uint64_t okAt = p_.getULong64("ble_ok_at", 0);
  p_.end();
  if (conn) return true;
  if (okAt == 0) return false;
  return (sfaNowMs() - okAt) <= SFA_BLE_SAFEZONE_GRACE_MS;
}
String SfaStore::bleSafeZoneName() { return getStr("ble_name", ""); }
String SfaStore::bleSafeZoneAddress() { return getStr("ble_addr", ""); }

void SfaStore::saveLastZoneName(const String& name) { putStr("last_zone", name); }
String SfaStore::lastZoneName() { return getStr("last_zone", ""); }
void SfaStore::saveLastVerb(const String& verb) { putStr("last_verb", verb); }
String SfaStore::lastVerb() { return getStr("last_verb", ""); }

String SfaStore::firstWifiSsid() {
  JsonDocument doc; if (!loadConfig(doc)) return "";
  JsonArray zones = doc["safeZones"].as<JsonArray>(); if (zones.isNull()) return "";
  for (JsonObject z : zones) if (sfaZoneIsWifiEnabled(z)) {
    const char* s = z["ssid"] | ""; if (*s) return String(s);
    return String((const char*)(z["name"] | ""));
  }
  return "";
}
String SfaStore::firstWifiBssid() {
  JsonDocument doc; if (!loadConfig(doc)) return "";
  JsonArray zones = doc["safeZones"].as<JsonArray>(); if (zones.isNull()) return "";
  for (JsonObject z : zones) if (sfaZoneIsWifiEnabled(z)) return String((const char*)(z["bssid"] | ""));
  return "";
}
String SfaStore::firstWifiZoneName() {
  JsonDocument doc; if (!loadConfig(doc)) return "No Wi-Fi Config";
  JsonArray zones = doc["safeZones"].as<JsonArray>(); if (zones.isNull()) return "No Wi-Fi Config";
  for (JsonObject z : zones) if (sfaZoneIsWifiEnabled(z)) {
    const char* n = z["name"] | ""; if (*n) return String(n);
    return String((const char*)(z["ssid"] | "No Wi-Fi Config"));
  }
  return "No Wi-Fi Config";
}

String SfaStore::zoneNameForSsid(const String& ssid) {
  if (ssid.isEmpty()) return "";
  JsonDocument doc; if (!loadConfig(doc)) return "";
  JsonArray zones = doc["safeZones"].as<JsonArray>(); if (zones.isNull()) return "";
  for (JsonObject z : zones) {
    if (ssid.equals((const char*)(z["ssid"] | ""))) return String((const char*)(z["name"] | ssid.c_str()));
  }
  return "";
}

String SfaStore::safeZoneIdForSsid(const String& ssid) {
  JsonDocument doc; if (!loadConfig(doc)) return "";
  JsonArray zones = doc["safeZones"].as<JsonArray>(); if (zones.isNull()) return "";
  if (!ssid.isEmpty()) {
    for (JsonObject z : zones) {
      if (ssid.equalsIgnoreCase((const char*)(z["ssid"] | "")) ||
          ssid.equalsIgnoreCase((const char*)(z["name"] | ""))) {
        const char* id = z["zoneId"] | ""; if (*id) return String(id);
      }
    }
  }
  for (JsonObject z : zones) if (sfaZoneIsWifiEnabled(z)) return String((const char*)(z["zoneId"] | ""));
  return "";
}

String SfaStore::bleSafeZoneId() {
  JsonDocument doc; if (!loadConfig(doc)) return "";
  JsonArray zones = doc["safeZones"].as<JsonArray>(); if (zones.isNull()) return "";
  for (JsonObject z : zones) {
    const char* t = z["zoneType"] | "";
    if (strcasecmp(t, "BLE") == 0 || strcasecmp(t, "HOTSPOT") == 0 ||
        strcasecmp((const char*)(z["ssid"] | ""), SFA_HOTSPOT_SSID) == 0) {
      const char* id = z["zoneId"] | ""; if (*id) return String(id);
    }
  }
  return "";
}

bool SfaStore::hasMissingZoneIds() {
  JsonDocument doc; if (!loadConfig(doc)) return false;
  JsonArray zones = doc["safeZones"].as<JsonArray>(); if (zones.isNull()) return false;
  for (JsonObject z : zones) { const char* id = z["zoneId"] | ""; if (!*id) return true; }
  return false;
}

static JsonObjectConst matchServerZone(JsonObjectConst local, JsonArrayConst serverZones) {
  String bssid = local["bssid"] | "";
  String ssid  = local["ssid"] | "";
  String name  = local["name"] | "";
  for (JsonObjectConst z : serverZones) {
    if (!bssid.isEmpty() && bssid.equalsIgnoreCase((const char*)(z["bssid"] | ""))) return z;
    if (!ssid.isEmpty()  && ssid.equalsIgnoreCase((const char*)(z["ssid"] | ""))) return z;
    if (!name.isEmpty()  && name.equalsIgnoreCase((const char*)(z["name"] | ""))) return z;
  }
  return JsonObjectConst();
}

bool SfaStore::ensureZoneIds(const String& serverZonesJson) {
  JsonDocument cfg; if (!loadConfig(cfg)) return false;
  JsonArray zones = cfg["safeZones"].as<JsonArray>(); if (zones.isNull()) return false;
  JsonDocument srv;
  if (deserializeJson(srv, serverZonesJson) != DeserializationError::Ok) return false;
  JsonArrayConst serverZones = srv.as<JsonArrayConst>();
  if (serverZones.isNull()) return false;
  bool changed = false;
  for (JsonObject z : zones) {
    const char* id = z["zoneId"] | ""; if (*id) continue;
    JsonObjectConst m = matchServerZone(z, serverZones);
    if (m.isNull()) continue;
    const char* mid = m["zoneId"] | "";
    if (*mid) { z["zoneId"] = mid; changed = true; }
  }
  if (changed) {
    String out; serializeJson(cfg, out);
    putStr("config_json", out);
  }
  return changed;
}

static bool sameZone(JsonObjectConst a, JsonObjectConst b) {
  String ab = a["bssid"] | "", bb = b["bssid"] | "";
  if (!ab.isEmpty() && !bb.isEmpty() && ab.equalsIgnoreCase(bb)) return true;
  String as = a["ssid"] | "", bs = b["ssid"] | "";
  return !as.isEmpty() && !bs.isEmpty() && as == bs;
}

bool SfaStore::applySafeZoneUpdate(const String& json) {
  JsonDocument upd;
  if (deserializeJson(upd, json) != DeserializationError::Ok) return false;
  String action = String((const char*)(upd["action"] | "")); action.toUpperCase();
  JsonObject incoming = upd["zone"].as<JsonObject>();
  if (incoming.isNull()) return false;
  String zoneId = incoming["zoneId"] | "";

  JsonDocument cfg;
  if (!loadConfig(cfg)) { cfg.to<JsonObject>(); }
  if (!cfg["deviceId"].is<const char*>())
    cfg["deviceId"] = (const char*)(upd["deviceId"] | SFA_DEFAULT_DEVICE_ID);
  JsonArray zones = cfg["safeZones"].as<JsonArray>();
  if (zones.isNull()) zones = cfg["safeZones"].to<JsonArray>();

  if (action == "CREATE") {
    bool replaced = false;
    for (JsonObject z : zones) {
      if (!zoneId.isEmpty() && zoneId == (const char*)(z["zoneId"] | "")) { z.set(incoming); replaced = true; break; }
    }
    if (!replaced) zones.add(incoming);
  } else if (action == "PATCH" || action == "UPDATE") {
    for (JsonObject z : zones) {
      if (zoneId == (const char*)(z["zoneId"] | "") || sameZone(z, incoming)) {
        for (JsonPair kv : incoming) z[kv.key()] = kv.value();
        break;
      }
    }
  } else if (action == "DELETE") {
    JsonDocument next; JsonArray na = next.to<JsonArray>();
    for (JsonObject z : zones) {
      if (zoneId == (const char*)(z["zoneId"] | "") || sameZone(z, incoming)) continue;
      na.add(z);
    }
    cfg["safeZones"] = na;
  } else {
    return false;
  }

  String out; serializeJson(cfg, out);
  p_.begin(NS, false);
  p_.putString("config_json", out);
  p_.putString("device_id", (const char*)(cfg["deviceId"] | SFA_DEFAULT_DEVICE_ID));
  p_.end();
  return true;
}

// ---------------- server-driven config sync ----------------
int SfaStore::configVersion() {
  p_.begin(NS, true);
  int v = p_.getInt("config_version", -1);
  p_.end();
  return v;
}

void SfaStore::saveConfigVersion(int version) {
  p_.begin(NS, false);
  p_.putInt("config_version", version);
  p_.end();
}

void SfaStore::saveServerSync(const String& elderName, const String& elderId,
                              const String& safeZonesJson, const String& guardiansJson) {
  JsonDocument cfg;
  loadConfig(cfg);                         // keep existing (deviceId etc.); empty doc if none
  cfg["elderName"] = elderName;            // Arduino String -> deep-copied into cfg
  cfg["elderId"] = elderId;

  // The server /config does NOT return zone passwords or GPS centers. Capture what we already
  // hold so a sync never wipes the Wi-Fi/hotspot passwords or the home geofence center.
  struct Keep { String ssid, bssid, password; bool hasLat = false; double lat = 0, lng = 0; };
  std::vector<Keep> kept;
  {
    JsonArray old = cfg["safeZones"].as<JsonArray>();
    if (!old.isNull()) for (JsonObject z : old) {
      Keep k;
      k.ssid = String((const char*)(z["ssid"] | ""));
      k.bssid = String((const char*)(z["bssid"] | ""));
      k.password = String((const char*)(z["password"] | ""));
      if (z["centerLat"].is<double>() && z["centerLng"].is<double>()) { k.hasLat = true; k.lat = z["centerLat"].as<double>(); k.lng = z["centerLng"].as<double>(); }
      if (!k.password.isEmpty() || k.hasLat) kept.push_back(k);
    }
  }

  JsonDocument zdoc;
  if (deserializeJson(zdoc, safeZonesJson) == DeserializationError::Ok && zdoc.is<JsonArray>())
    cfg["safeZones"] = zdoc.as<JsonArray>();   // deep copy of the server's safe zones

  // Re-inject the preserved password / GPS center into each freshly-synced zone (match by ssid,
  // then bssid) when the server did not provide them.
  {
    JsonArray zs = cfg["safeZones"].as<JsonArray>();
    if (!zs.isNull()) for (JsonObject z : zs) {
      String ssid  = String((const char*)(z["ssid"] | ""));
      String bssid = String((const char*)(z["bssid"] | ""));
      for (auto& k : kept) {
        bool match = (!ssid.isEmpty()  && ssid.equalsIgnoreCase(k.ssid)) ||
                     (!bssid.isEmpty() && bssid.equalsIgnoreCase(k.bssid));
        if (!match) continue;
        const char* p = z["password"] | "";
        if (!*p && !k.password.isEmpty()) z["password"] = k.password;
        if (!z["centerLat"].is<double>() && k.hasLat) { z["centerLat"] = k.lat; z["centerLng"] = k.lng; }
        break;
      }
    }
  }

  JsonDocument gdoc;
  if (deserializeJson(gdoc, guardiansJson) == DeserializationError::Ok && gdoc.is<JsonArray>()) {
    cfg["guardians"] = gdoc.as<JsonArray>();
    JsonObject g0 = gdoc[0].as<JsonObject>();
    JsonObject guardian = cfg["guardian"].to<JsonObject>();
    if (!g0.isNull()) {
      guardian["name"] = g0["name"].as<String>();
      guardian["phone"] = g0["phone"].as<String>();
      guardian["relation"] = g0["relation"].as<String>();
    }
  }

  String out;
  serializeJson(cfg, out);
  saveConfig(out);   // reuses saveConfig (also refreshes center_lat/center_lng/device_ble_id)
}
