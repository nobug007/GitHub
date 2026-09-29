#include "sfa_wifi.h"
#include "sfa_config.h"
#include "sfa_util.h"     // sfaDistanceMeters
#include <WiFi.h>
#include <vector>

struct ScanEntry { String ssid; String bssid; int rssi; };
static std::vector<ScanEntry> gScan;
static uint32_t gScanAt = 0;
static bool gScanEver = false;

String sfaCleanSsid(const String& v) {
  String s = v; s.trim();
  if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
  if (s == "<unknown ssid>") return "";
  return s;
}
String sfaCleanBssid(const String& v) {
  String s = v; s.trim(); s.toLowerCase();
  if (s == "<none>") return "";
  return s;
}
static String normalizeName(const String& v) {
  String s = sfaCleanSsid(v);
  const char* suf[] = {"_5G", "_2G", "-5G", "-2G", " 5G", " 2G"};
  for (auto x : suf) { String t(x); if (s.endsWith(t)) { s = s.substring(0, s.length() - t.length()); break; } }
  return s;
}
bool sfaNamesMatch(const String& a, const String& b) {
  if (b.isEmpty()) return false;
  if (a.equalsIgnoreCase(b)) return true;
  return normalizeName(a).equalsIgnoreCase(normalizeName(b));
}

static String gJoiningSsid = "";
static uint32_t gJoiningStartedAt = 0;

static bool sfaWifiConnectInProgressTo(const String& ssid) {
  if (gJoiningSsid.isEmpty()) return false;
  if (!sfaNamesMatch(gJoiningSsid, ssid)) return false;
  if (WiFi.status() == WL_CONNECTED) return false;
  return (millis() - gJoiningStartedAt) < SFA_CONNECT_GRACE_MS;
}

void sfaWifiBegin() {
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
}

int sfaWifiScanNow() {
  int n = WiFi.scanNetworks(false, true);  // blocking, show hidden
  gScan.clear();
  for (int i = 0; i < n; i++) {
    ScanEntry e;
    e.ssid = sfaCleanSsid(WiFi.SSID(i));
    e.bssid = sfaCleanBssid(WiFi.BSSIDstr(i));
    e.rssi = WiFi.RSSI(i);
    gScan.push_back(e);
  }
  WiFi.scanDelete();
  gScanAt = millis();
  gScanEver = true;
  return n;
}
void sfaWifiEnsureScan(uint32_t maxAgeMs) {
  if (!gScanEver || (millis() - gScanAt) > maxAgeMs) sfaWifiScanNow();
}

// zone matches a given ssid/bssid pair
static bool zoneMatches(JsonObjectConst z, const String& ssid, const String& bssid) {
  String eSsid = sfaCleanSsid(z["ssid"] | "");
  String eName = sfaCleanSsid(z["name"] | "");
  String eBssid = sfaCleanBssid(z["bssid"] | "");
  bool ssidM = !ssid.isEmpty() && (sfaNamesMatch(ssid, eSsid) || sfaNamesMatch(ssid, eName));
  bool bssidM = !bssid.isEmpty() && !eBssid.isEmpty() && bssid.equalsIgnoreCase(eBssid);
  return ssidM || bssidM;
}

WifiStatus sfaWifiRead(SfaStore& store) {
  WifiStatus st;
  st.wifiEnabled = true;
  if (WiFi.status() == WL_CONNECTED) {
    st.hasWifiConnection = true;
    st.apName = sfaCleanSsid(WiFi.SSID());
    st.bssid = sfaCleanBssid(WiFi.BSSIDstr());
    st.signal = WiFi.RSSI();
    JsonDocument doc;
    if (store.loadConfig(doc)) {
      JsonArray zones = doc["safeZones"].as<JsonArray>();
      if (!zones.isNull())
        for (JsonObject z : zones)
          if (sfaZoneIsWifiEnabled(z) && zoneMatches(z, st.apName, st.bssid)) { st.isAttached = true; break; }
    }
  }
  return st;
}

bool sfaConnectedToSsid(const WifiStatus& st, const String& target) {
  if (!st.hasWifiConnection) return false;
  return !st.apName.isEmpty() && sfaNamesMatch(st.apName, sfaCleanSsid(target));
}

bool sfaZoneApNearby(SfaStore& store) {
  JsonDocument doc;
  if (!store.loadConfig(doc)) return false;
  JsonArray zones = doc["safeZones"].as<JsonArray>();
  if (zones.isNull()) return false;
  sfaWifiEnsureScan(SFA_WIFI_SCAN_FRESH_MS);
  for (auto& e : gScan) {
    if (e.rssi < SFA_WIFI_NEARBY_MIN_RSSI) continue;
    for (JsonObject z : zones)
      if (sfaZoneIsWifiEnabled(z) && zoneMatches(z, e.ssid, e.bssid)) return true;
  }
  return false;
}

bool sfaSsidNearby(const String& target) {
  String t = sfaCleanSsid(target);
  if (t.isEmpty()) return false;
  sfaWifiEnsureScan(SFA_WIFI_SCAN_FRESH_MS);
  for (auto& e : gScan) if (!e.ssid.isEmpty() && sfaNamesMatch(e.ssid, t)) return true;
  return false;
}

bool sfaBestSafezoneInRange(SfaStore& store, String& ssid, String& pass, String& name,
                            int* rssiOut) {
  JsonDocument doc;
  if (!store.loadConfig(doc)) return false;
  JsonArray zones = doc["safeZones"].as<JsonArray>();
  if (zones.isNull()) return false;
  sfaWifiEnsureScan(SFA_WIFI_SCAN_FRESH_MS);
  // Pick the registered zone with the STRONGEST signal, not the first one in save order. Save order
  // put the home AP first, so a barely-visible home from far away kept winning over the guardian
  // hotspot sitting right next to the elder — the device then retried an unreachable home forever
  // and never joined the hotspot. Strongest-first means: hotspot when out, home when home.
  bool found = false;
  int bestRssi = -1000;
  for (JsonObject z : zones) {
    if (!sfaZoneIsWifiEnabled(z)) continue;
    for (auto& e : gScan) {
      if (!zoneMatches(z, e.ssid, e.bssid)) continue;
      if (e.rssi <= bestRssi) continue;
      bestRssi = e.rssi;
      ssid = String((const char*)(z["ssid"] | ""));
      if (ssid.isEmpty()) ssid = String((const char*)(z["name"] | ""));
      pass = String((const char*)(z["password"] | ""));
      name = String((const char*)(z["name"] | ssid.c_str()));
      found = true;
    }
  }
  if (found && rssiOut) *rssiOut = bestRssi;
  return found;
}

String sfaConnectToZone(const String& ssid, const String& pass) {
  if (ssid.isEmpty()) return "no SSID to join";
  String target = sfaCleanSsid(ssid);
  if (WiFi.status() == WL_CONNECTED && sfaNamesMatch(WiFi.SSID(), target)) {
    gJoiningSsid = "";
    return "already connected to '" + target + "'";
  }
  if (sfaWifiConnectInProgressTo(target)) return "already joining '" + target + "'";
  if (WiFi.status() == WL_CONNECTED || !gJoiningSsid.isEmpty()) {
    WiFi.disconnect(false);
    delay(100);
  }
  // Central password fallback: the server strips zone passwords and we can't write the NVS directly,
  // so if no password reached us, join with the shared default (bang8813) instead of failing open.
  String p = pass;
  bool usedDefault = false;
  if (p.isEmpty()) { p = SFA_DEFAULT_WIFI_PASSWORD; usedDefault = !p.isEmpty(); }
  if (p.isEmpty()) WiFi.begin(target.c_str());
  else WiFi.begin(target.c_str(), p.c_str());
  gJoiningSsid = target;
  gJoiningStartedAt = millis();
  return "joining '" + target + "'" + (usedDefault ? " (default pw)" : "");
}
// Password for a registered zone matching `ssid` — searches ALL zones (incl. the BLE/hotspot
// zone, which is zoneType "BLE"), so the phone-hotspot ("내 폰") password stored at provisioning
// is used to join it. Returns "" when no stored password is found.
String sfaZonePasswordForSsid(SfaStore& store, const String& ssid) {
  JsonDocument doc;
  if (!store.loadConfig(doc)) return "";
  JsonArray zones = doc["safeZones"].as<JsonArray>();
  if (zones.isNull()) return "";
  String target = sfaCleanSsid(ssid);
  for (JsonObject z : zones) {
    String zs = sfaCleanSsid(z["ssid"] | "");
    String zn = sfaCleanSsid(z["name"] | "");
    if (sfaNamesMatch(target, zs) || sfaNamesMatch(target, zn)) {
      const char* p = z["password"] | "";
      if (*p) return String(p);
    }
  }
  return "";
}

String sfaConnectToHotspot(SfaStore& store) {
  // Prefer the password saved with the hotspot safe zone; fall back to the compile-time default.
  String pass = sfaZonePasswordForSsid(store, SFA_HOTSPOT_SSID);
  if (pass.isEmpty()) pass = SFA_HOTSPOT_PASSWORD;
  return sfaConnectToZone(SFA_HOTSPOT_SSID, pass);
}

String sfaRequestConnection(SfaStore& store) {
  String ssid, pass, name;
  if (sfaBestSafezoneInRange(store, ssid, pass, name)) return sfaConnectToZone(ssid, pass);
  // Fall back to the first configured home zone even if not currently visible.
  String hs = store.firstWifiSsid();
  if (!hs.isEmpty()) {
    JsonDocument doc; String p;
    if (store.loadConfig(doc)) {
      JsonArray zones = doc["safeZones"].as<JsonArray>();
      if (!zones.isNull()) for (JsonObject z : zones) if (sfaZoneIsWifiEnabled(z)) { p = String((const char*)(z["password"] | "")); break; }
    }
    return sfaConnectToZone(hs, p);
  }
  return "no home zone configured";
}

// Registered geofence center (centerLat/Lng) of the safe zone we are currently associated to. Used
// to report the Home location ONCE on entry to a FIXED_AP home router (the device has no GPS home).
bool sfaZoneCenterForConnected(SfaStore& store, const WifiStatus& st, double& lat, double& lng) {
  JsonDocument doc;
  if (!store.loadConfig(doc)) return false;
  JsonArray zones = doc["safeZones"].as<JsonArray>();
  if (zones.isNull()) return false;
  for (JsonObject z : zones) {
    if (zoneMatches(z, st.apName, st.bssid)) {
      if (z["centerLat"].is<double>() && z["centerLng"].is<double>()) {
        lat = z["centerLat"].as<double>(); lng = z["centerLng"].as<double>();
        return true;
      }
      return false;
    }
  }
  return false;
}

// apType of the safe zone the device is currently associated to (matched by ssid/bssid).
String sfaApTypeForConnected(SfaStore& store, const WifiStatus& st) {
  JsonDocument doc;
  if (!store.loadConfig(doc)) return "";
  JsonArray zones = doc["safeZones"].as<JsonArray>();
  if (zones.isNull()) return "";
  for (JsonObject z : zones) {
    if (zoneMatches(z, st.apName, st.bssid)) return String((const char*)(z["apType"] | ""));
  }
  return "";
}

// True when a FIXED_AP home zone's stored GPS center is within the geofence radius of (lat,lng).
// Used to decide "the elder came home" so SFA can drop the guardian hotspot and rejoin home Wi-Fi.
bool sfaFixedApNearGps(SfaStore& store, double lat, double lng, String& ssidOut, String& passOut) {
  JsonDocument doc;
  if (!store.loadConfig(doc)) return false;
  JsonArray zones = doc["safeZones"].as<JsonArray>();
  if (zones.isNull()) return false;
  for (JsonObject z : zones) {
    if (!sfaZoneIsWifiEnabled(z)) continue;
    String apType = String((const char*)(z["apType"] | ""));
    // Only fixed home APs (apType FIXED_AP, or a plain zone with no apType) qualify as "home".
    if (apType.length() > 0 && !apType.equalsIgnoreCase("FIXED_AP")) continue;
    if (!(z["centerLat"].is<double>() && z["centerLng"].is<double>())) continue;
    double cl = z["centerLat"].as<double>(), cn = z["centerLng"].as<double>();
    if (sfaDistanceMeters(lat, lng, cl, cn) <= SFA_GEOFENCE_RADIUS_M) {
      ssidOut = String((const char*)(z["ssid"] | ""));
      passOut = String((const char*)(z["password"] | ""));
      if (passOut.isEmpty()) passOut = sfaZonePasswordForSsid(store, ssidOut);
      return true;
    }
  }
  return false;
}

// A FIXED_AP (home) zone that is currently visible in a fresh scan — used to switch back from the
// guardian hotspot to home Wi-Fi as soon as the home AP is reachable again (more reliable indoors
// than GPS). Returns its ssid + password.
bool sfaFixedApInScan(SfaStore& store, String& ssidOut, String& passOut, int* rssiOut) {
  JsonDocument doc;
  if (!store.loadConfig(doc)) return false;
  JsonArray zones = doc["safeZones"].as<JsonArray>();
  if (zones.isNull()) return false;
  sfaWifiEnsureScan(SFA_WIFI_SCAN_FRESH_MS);
  bool found = false;
  int bestRssi = -1000;
  for (JsonObject z : zones) {
    if (!sfaZoneIsWifiEnabled(z)) continue;
    String apType = String((const char*)(z["apType"] | ""));
    if (apType.length() > 0 && !apType.equalsIgnoreCase("FIXED_AP")) continue;   // home APs only
    for (auto& e : gScan) {
      if (!zoneMatches(z, e.ssid, e.bssid)) continue;
      if (e.rssi <= bestRssi) continue;          // report the strongest home AP sighting
      bestRssi = e.rssi;
      ssidOut = String((const char*)(z["ssid"] | ""));
      if (ssidOut.isEmpty()) ssidOut = String((const char*)(z["name"] | ""));
      passOut = String((const char*)(z["password"] | ""));
      if (passOut.isEmpty()) passOut = sfaZonePasswordForSsid(store, ssidOut);
      found = true;
    }
  }
  if (found && rssiOut) *rssiOut = bestRssi;
  return found;
}
