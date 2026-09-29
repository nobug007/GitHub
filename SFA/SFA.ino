

// SFA — Safe Finder Arduino (ESP32-C5). Headless C port of SFD's safety state machine.
// Same server protocol, BLE provisioning, WiFi/BLE/GPS safe-zone detection, false-exit
// suppression, and WARNING/EMERGENCY SOS escalation. SMS is delivered by an on-board GSM
// modem (replacing SFD's Android SmsManager); GPS by a UART NMEA module; movement by an
// optional IMU. Missing hardware degrades gracefully — the state machine still runs.
#include <Arduino.h>
#include <WiFi.h>
#include <vector>
#include "sfa_config.h"
#include "sfa_types.h"
#include "sfa_util.h"
#include "sfa_store.h"
#include "sfa_wifi.h"
#include "sfa_sensors.h"
#include "sfa_sms.h"
#include "sfa_payload.h"
#include "sfa_net.h"
#include "sfa_ble.h"

// ---------------- state (mirrors SfdTelemetryService fields) ----------------
static SfaStore store;
static std::vector<GyroSample> gSamples;
static GyroSample latestGyro;
static bool operational = false;
static uint32_t lastSampleTick = 0, lastConnTick = 0;
static uint64_t serviceStartedAt = 0;

static String  lastLocationType = "";     // "" == null
static uint64_t safeZoneStateStartedAt = 0, outsideStateStartedAt = 0, gpsModeStartedAt = 0;
static bool    warningSent = false, emergencySent = false;
static uint64_t lastSosTelemetryAt = 0, lastPeriodicReportAt = 0;
static String  pendingZoneVerb = "";
static uint64_t pendingZoneVerbStartedAt = 0;

static bool    hasConfirmedWifi = false;
static WifiStatus lastConfirmedWifi;
static uint64_t lastConfirmedWifiAt = 0, wifiHoldSince = 0;
static bool    movedSinceWifiLoss = false;

static bool    hasLastValidGps = false;
static GpsStatus lastValidGps;
static uint64_t lastValidGpsReadAt = 0;
static bool    hasLastGpsVerbLoc = false;
static LocationSnapshot lastGpsVerbLocation;
static bool    hasGpsStayed = false;
static uint64_t gpsStayedStartedAt = 0;

enum AwayPhase { AWAY_NONE, AWAY_ADVERTISING, AWAY_CONNECTING };
static AwayPhase awayPhase = AWAY_NONE;
static uint32_t awayPhaseStartedAt = 0;
static bool    hotspotSafeActive = false;

struct ZoneVerb { String verb; uint64_t durationMs; };

// ---------------- forward declarations ----------------
static void log(const String& m);
static void recordTxLog(const String& payload, const ApiResult& r);
static bool isInSafeZone(const WifiStatus& w);
static GpsStatus nonWifiGpsStatus();
static GpsStatus effectiveGpsStatus(GpsStatus cur);
static WifiStatus effectiveWifiStatus(WifiStatus current);
static LocationSnapshot locationSnapshot(const WifiStatus& wifi);
static bool updateLocationState(const WifiStatus& wifi, const LocationSnapshot& loc);
static void sendScheduledTelemetry(const WifiStatus& wifi, const LocationSnapshot& loc);
static ZoneVerb consumeZoneVerb(const WifiStatus& wifi);
static String verbForLocation(const String& verb, const LocationSnapshot& loc);
static String gpsVerb(const LocationSnapshot& loc, const String& fallback);
static String gyroMovementVerb();
static String deviceStatusFor(const LocationSnapshot& loc);
static void doSend(const LocationSnapshot& loc, bool inSafe, const String& eventType,
                   const String& verb, uint64_t durationMs, const String& deviceStatus);
static void sendZoneTransitionTelemetry(const String& verb, const WifiStatus& wifi);
static void sendRiskTelemetry(const String& eventType, const String& label, const WifiStatus& wifi);
static void sendStatusSms(const String& event, const LocationSnapshot& loc);
static void syncZoneIdsFromServer();
static void syncConfigFromServer(int serverVersion, bool configChanged);
static void connectivityTick();
static void sampleTick();
static void startOperational();

// ---------------- helpers ----------------
static void log(const String& m) {
  Serial.print("["); Serial.print(millis() / 1000); Serial.print("s] ");
  Serial.println(m);
}

// ---------------- sent-telemetry log (RAM, survives as long as power is held) ----------------
// Keeps the exact JSON we posted plus the server's answer, so a running device can be interrogated
// over the serial console without waiting for the next report. Deliberately RAM-only: it is a live
// debugging aid, and writing every payload to flash would wear NVS out.
struct TxLogEntry { uint32_t atMs; String payload; int code; String response; };
static std::vector<TxLogEntry> gTxLog;
static const size_t SFA_TX_LOG_MAX = 8;

static void recordTxLog(const String& payload, const ApiResult& r) {
  TxLogEntry e;
  e.atMs = millis();
  e.payload = payload;
  e.code = r.code;
  e.response = r.body.length() > 160 ? r.body.substring(0, 160) : r.body;
  gTxLog.push_back(e);
  while (gTxLog.size() > SFA_TX_LOG_MAX) gTxLog.erase(gTxLog.begin());
}

static void printTxLog() {
  Serial.println("========== SENT TELEMETRY LOG ==========");
  if (gTxLog.empty()) { Serial.println("(empty — nothing sent yet)"); }
  for (size_t i = 0; i < gTxLog.size(); i++) {
    const TxLogEntry& e = gTxLog[i];
    Serial.printf("--- [%u] t=%lus  HTTP %d\n", (unsigned)i, (unsigned long)(e.atMs / 1000), e.code);
    Serial.println(e.payload);
    if (e.response.length()) { Serial.print("  resp: "); Serial.println(e.response); }
  }
  Serial.printf("========== %u entries (max %u) ==========\n",
                (unsigned)gTxLog.size(), (unsigned)SFA_TX_LOG_MAX);
}

// One source for the command list, printed both at boot and on an unrecognised input. Keeping two
// copies meant the boot banner went on advertising an old set long after commands were added, so the
// banner stopped being usable as evidence of which build is actually running — which is exactly what
// it is there for.
#define SFA_CONSOLE_COMMANDS "log | gps | sat [초] | phone | cell | lbs | agps | sms | at <command>"

// Serial console. Read-only diagnostics — nothing here changes the state machine, so it is safe to
// poke at a running device. "at <cmd>" is the reason this exists: which optional AT commands a given
// A7670E firmware actually implements can only be settled by asking the modem, not by reading specs.
static void pollSerialConsole() {
  static String raw;
  while (Serial.available()) {
    char c = (char)Serial.read();
    // End the line on EITHER terminator. The serial monitor's "line ending" setting decides which
    // one it sends, and an earlier version only accepted '\n' — with the monitor set to "carriage
    // return" the command was silently never processed and pressing enter appeared to do nothing.
    if (c != '\n' && c != '\r') { if (raw.length() < 96) raw += c; continue; }
    raw.trim();
    if (raw.isEmpty()) { raw = ""; continue; }    // bare enter, or the second half of a CRLF pair
    // Echo the line, then say what is about to happen, THEN do it. Several of these commands block
    // on the modem for tens of seconds; without the running commentary a working device is
    // indistinguishable from a dead prompt. flush() matters here — the announcement has to leave the
    // USB buffer before the blocking call starts, not after it finishes.
    Serial.println("> " + raw);
    Serial.flush();
    String cmd = raw; cmd.toLowerCase();          // match case-insensitively...
    if (cmd == "log") {
      Serial.println("... dumping the sent-telemetry buffer");
      Serial.flush();
      printTxLog();
    } else if (cmd == "gps") {
      Serial.println("... reading the GNSS receiver");
      Serial.flush();
      GpsStatus g = nonWifiGpsStatus();
      Serial.printf("[GPS] gnssPowered=%s fix=%s lat=%.6f lng=%.6f acc=%.1f everSeen=%s\n",
                    sfaModemGnssIsOn() ? "Y" : "N",
                    g.hasFix ? "Y" : "N", g.latitude, g.longitude, g.accuracy,
                    sfaModemGnssSeen() ? "Y" : "N");
      Serial.println("[GPS] CGNSSINFO: " + sfaModemAtRaw("+CGNSSINFO", 3000));
    } else if (cmd == "sat" || cmd.startsWith("sat ")) {
      uint32_t secs = 20;
      if (cmd.length() > 4) { int v = cmd.substring(4).toInt(); if (v >= 5 && v <= 120) secs = v; }
      Serial.println(sfaModemGnssSatelliteScan(secs));
    } else if (cmd == "cell") {
      Serial.println("... asking the modem for its serving cell (AT+CPSI?)");
      Serial.flush();
      Serial.println("[CELL] " + sfaModemCellInfo());
    } else if (cmd == "lbs") {
      GpsStatus c2;
      // force = true: ignore the 2-minute poll interval. From the console the whole point is to
      // watch a real request go out, not to be handed the cached answer from a previous one.
      if (sfaModemLbsRead(c2, true))
        Serial.printf("[LBS] RESULT %.6f,%.6f acc=%.0fm\n", c2.latitude, c2.longitude, c2.accuracy);
      else
        Serial.printf("[LBS] RESULT none (command supported=%s)\n", sfaModemLbsSupported() ? "Y" : "N");
    } else if (cmd == "phone") {
      Serial.println("... fetching GPS from SFC at http://" + WiFi.gatewayIP().toString() + ":8765/gps");
      Serial.flush();
      sfaRefreshPhoneGps(true);
      double plat, plng; float pacc = 0;
      if (sfaLastKnownPhoneGps(plat, plng, &pacc))
        Serial.printf("[PHONEGPS] CACHED %.6f,%.6f acc=%.0fm\n", plat, plng, pacc);
      else
        Serial.println("[PHONEGPS] CACHED none — " + sfaPhoneGpsLastDetail());
    } else if (cmd == "sms") {
      // The modem announced "+SMS FULL" and that URC now turns up prepended to unrelated AT replies.
      // Read-only here: show how full each storage is and let the decision to clear it be a
      // deliberate one.
      Serial.println("... reading SMS storage usage (AT+CPMS?)");
      Serial.flush();
      Serial.println("[SMS] " + sfaModemAtRaw("+CPMS?", 5000));
    } else if (cmd == "agps") {
      sfaModemAgpsPrime();
    } else if (cmd.startsWith("at ") || cmd == "at") {
      String at = raw.substring(2);               // ...but send the command in its original case
      at.trim();
      if (at.isEmpty()) {
        Serial.println("usage: at +CGNSSMODE?");
      } else {
        Serial.println("... sending AT" + at + " (up to 10s)");
        Serial.flush();
        Serial.println("[AT] " + sfaModemAtRaw(at, 10000));
      }
    } else {
      Serial.println(String("commands: ") + SFA_CONSOLE_COMMANDS);
    }
    Serial.flush();
    raw = "";
  }
}

static bool isInSafeZone(const WifiStatus& w) { return w.isAttached || store.isBleSafeZoneActive(); }

static GpsStatus nonWifiGpsStatus() { return effectiveGpsStatus(sfaGpsRead()); }

static GpsStatus effectiveGpsStatus(GpsStatus cur) {
  uint64_t now = sfaNowMs();
  if (cur.hasFix) { lastValidGps = cur; hasLastValidGps = true; lastValidGpsReadAt = now; return cur; }
  if (hasLastValidGps && (now - lastValidGpsReadAt) <= SFA_GPS_LOCATION_CACHE_MS) return lastValidGps;
  return cur;
}

// Right after boot the radio may not have associated yet, but the device is normally started inside
// a safe zone — so we report the zone that is ACTUALLY ON THE AIR rather than letting a not-yet-
// associated radio look like a departure. It must be a zone a fresh scan can see: claiming the
// config's first entry regardless of the surroundings made a device sitting on the guardian hotspot
// report "nobug_home" with signal -127 (no link at all), observed 2026-08-15 11:13. If nothing
// registered is in range we claim nothing and let the normal away logic run.
static WifiStatus synthAttachedFromConfig(const WifiStatus& current) {
  String ssid, pass, name;
  int rssi = -1000;
  if (!sfaBestSafezoneInRange(store, ssid, pass, name, &rssi)) return current;
  // Visible is not the same as inside. A home AP heard faintly from a street away must not be
  // reported as "at home"; require the same proximity floor the nearby check uses.
  if (rssi < SFA_WIFI_NEARBY_MIN_RSSI) {
    log("Startup: '" + ssid + "' visible but too weak (" + String(rssi) + " dBm) — not claiming it");
    return current;
  }
  WifiStatus w = current;
  w.apName = ssid.length() ? ssid : name;
  w.bssid = "";
  JsonDocument d;
  if (store.loadConfig(d)) {
    JsonArray zs = d["safeZones"].as<JsonArray>();
    if (!zs.isNull()) for (JsonObject z : zs) {
      if (!sfaZoneIsWifiEnabled(z)) continue;
      String zSsid = String((const char*)(z["ssid"] | ""));
      String zName = String((const char*)(z["name"] | ""));
      if (sfaNamesMatch(w.apName, zSsid) || sfaNamesMatch(w.apName, zName)) {
        w.bssid = String((const char*)(z["bssid"] | ""));
        break;
      }
    }
  }
  w.isAttached = true;
  w.hasWifiConnection = false;   // reported from a scan sighting, not from an association
  w.signal = rssi;               // the measured scan RSSI, not the -127 of an unlinked radio
  log("Startup: not associated yet, but '" + w.apName + "' is in range (" + String(rssi) +
      " dBm); reporting that zone");
  return w;
}

static WifiStatus effectiveWifiStatus(WifiStatus current) {
  uint64_t now = sfaNowMs();
  if (current.isAttached) {
    lastConfirmedWifiAt = now; lastConfirmedWifi = current; hasConfirmedWifi = true;
    wifiHoldSince = 0; movedSinceWifiLoss = false;
    return current;
  }
  if (!hasConfirmedWifi) {
    // No association confirmed yet this session. synthAttachedFromConfig() reports a zone only when
    // a scan actually sees one, so it is safe to call unconditionally here — the old startup-grace
    // arm let it claim a zone with nothing on the air and that is what produced the phantom
    // "nobug_home" record.
    return synthAttachedFromConfig(current);
  }
  double movementNow = latestGyro.gyX + latestGyro.gyY + latestGyro.gyZ;
  if (movementNow >= SFA_GYRO_MOVEMENT_THRESHOLD) movedSinceWifiLoss = true;
  WifiStatus held = lastConfirmedWifi;
  if (current.signal > -127) held.signal = current.signal;

  if (!current.hasWifiConnection && (now - lastConfirmedWifiAt) <= SFA_WIFI_SAFEZONE_GRACE_MS) {
    log("Wi-Fi SafeZone reading missed once; keeping last AP " + held.apName);
    return held;
  }
  if (sfaZoneApNearby(store)) {
    lastConfirmedWifiAt = now;
    log("Wi-Fi not attached but zone AP still in scan range; holding safe zone");
    return held;
  }
  if (current.wifiEnabled && !current.hasWifiConnection && !movedSinceWifiLoss) {
    if (wifiHoldSince == 0) wifiHoldSince = now;
    if ((now - wifiHoldSince) <= SFA_WIFI_STATIONARY_HOLD_MAX_MS) {
      log("Wi-Fi lost but device stationary; holding safe zone");
      return held;
    }
  }
  // GPS-based departure decision. A missing fix must NOT mean "hold": modem GNSS needs minutes for
  // a cold fix and gets nothing indoors, so holding on "no fix" pinned isAttached=true forever — the
  // device then never went "away" and never looked for the guardian hotspot. We have already lost
  // the association AND the home AP is gone from scans, so trust Wi-Fi and treat that as departed.
  GpsStatus gps = nonWifiGpsStatus();
  if (!gps.hasFix) {
    log("Home AP gone from scans & no GPS fix; treating as departed (Wi-Fi is the evidence)");
    return current;
  }
  bool nearHome = false;
  JsonDocument d;
  if (store.loadConfig(d)) {
    JsonArray zs = d["safeZones"].as<JsonArray>();
    if (!zs.isNull()) for (JsonObject z : zs) {
      if (!z["centerLat"].is<double>() || !z["centerLng"].is<double>()) continue;
      double cl = z["centerLat"], cn = z["centerLng"];
      if (sfaDistanceMeters(gps.latitude, gps.longitude, cl, cn) <= SFA_GEOFENCE_RADIUS_M) { nearHome = true; break; }
    }
  }
  if (nearHome) { log("Wi-Fi off but GPS within home radius; holding safe zone"); return held; }
  return current;
}

// Current position, best source first. Used wherever the report must say where the device actually
// is. The order is by accuracy: our own GNSS fix (metres), then the last fix SFC handed us over the
// hotspot (metres, but possibly minutes old), then the modem's cell-based estimate (hundreds of
// metres to kilometres). The last one exists because indoors GNSS simply does not work — a coarse
// position that says which building is far more use to a searching guardian than no position at all,
// and the reported accuracy makes clear how much to trust it.
static bool attachCurrentPosition(LocationSnapshot& l) {
  GpsStatus g = nonWifiGpsStatus();
  if (g.hasFix) {
    l.hasCoords = true; l.latitude = g.latitude; l.longitude = g.longitude; l.accuracy = g.accuracy;
    return true;
  }
  double plat, plng; float pacc = 0;
  if (sfaLastKnownPhoneGps(plat, plng, &pacc)) {
    l.hasCoords = true; l.latitude = plat; l.longitude = plng; l.accuracy = pacc;
    return true;
  }
  GpsStatus c;
  if (sfaModemLbsRead(c)) {
    l.hasCoords = true; l.latitude = c.latitude; l.longitude = c.longitude; l.accuracy = c.accuracy;
    return true;
  }
  return false;
}

static LocationSnapshot locationSnapshot(const WifiStatus& wifi) {
  LocationSnapshot l;
  if (wifi.isAttached) {
    l.locationType = "WIFI"; l.apName = wifi.apName; l.bssid = wifi.bssid; l.signal = wifi.signal;
    // A FIXED_AP home zone is a known fixed place, so it needs NO coordinates at all — the AP name
    // already says where the device is. A mobile/hotspot zone moves with the guardian, so report the
    // live position from our own GNSS.
    String apType = sfaApTypeForConnected(store, wifi);
    bool mobile = apType.equalsIgnoreCase("SUSPECTED_HOTSPOT") || apType.equalsIgnoreCase("REGISTERED_HOTSPOT")
                  || sfaConnectedToSsid(wifi, SFA_HOTSPOT_SSID);
    if (mobile) attachCurrentPosition(l);
  } else if (store.isBleSafeZoneActive()) {
    // Guardian-proximity (hotspot) zone. locationType stays inside the spec set (WIFI | GPS): report
    // WIFI while that association exists, GPS otherwise. Position is live, same as the hotspot case.
    l.locationType = wifi.hasWifiConnection ? "WIFI" : "GPS";
    l.apName = wifi.apName; l.bssid = wifi.bssid;
    l.signal = wifi.hasWifiConnection ? wifi.signal : sfaModemSignalDbm();
    attachCurrentPosition(l);
  } else {
    // Away: the device's own position is the only location the guardian will get. Telemetry travels
    // over LTE here, so report the cellular RSSI rather than a stale Wi-Fi one.
    l.locationType = "GPS";
    l.signal = wifi.hasWifiConnection ? wifi.signal : sfaModemSignalDbm();
    attachCurrentPosition(l);
  }
  return l;
}

// A WARNING/SOS without a position is useless to the guardian, so these alerts always carry the best
// coordinate we can produce: a live modem GNSS fix, else the last position SFC gave us over the
// hotspot. (Normal in-zone reports deliberately stay coordinate-free for a fixed home AP.)
static void ensureRiskCoords(LocationSnapshot& l) {
  if (l.hasCoords) return;
  attachCurrentPosition(l);
}

static void persistEscalation() {
  EscalationState s;
  s.outsideStartedAtMs = outsideStateStartedAt; s.gpsModeStartedAtMs = gpsModeStartedAt;
  // The "SMS already sent" latches are per-outing runtime state and are deliberately NOT persisted:
  // a reboot used to restore them as true, which permanently suppressed the WARNING/SOS SMS.
  s.warningSent = false; s.emergencySent = false; s.lastLocationType = lastLocationType;
  store.saveEscalation(s);
}
static void restoreEscalation() {
  EscalationState s = store.loadEscalation();
  outsideStateStartedAt = s.outsideStartedAtMs; gpsModeStartedAt = s.gpsModeStartedAtMs;
  lastLocationType = s.lastLocationType;
  // warningSent/emergencySent are deliberately NOT restored. They are "SMS already sent" latches for
  // the CURRENT outing, and SFA reboots often: restoring them as true made the escalation SMS code
  // skip itself forever, so the server kept receiving SOS every minute while the guardian was never
  // texted (observed 2026-08-14 — SFD/SFW, which do not reboot, alerted normally). They are re-armed
  // on every fresh departure (see updateLocationState) and each alert then goes out exactly once.
  warningSent = false; emergencySent = false;
}

static String zoneDisplayName(const WifiStatus& wifi) {
  String n = store.zoneNameForSsid(wifi.apName);
  if (!n.isEmpty()) return n;
  return wifi.apName.isEmpty() ? store.firstWifiZoneName() : wifi.apName;
}

static bool updateLocationState(const WifiStatus& wifi, const LocationSnapshot& loc) {
  uint64_t now = sfaNowMs();
  String prev = lastLocationType;
  bool inSafe = isInSafeZone(wifi);
  bool wasInSafe = (prev == "WIFI" || prev == "BLE");

  if (prev == "") {
    lastLocationType = loc.locationType;
    if (inSafe) safeZoneStateStartedAt = now;
    else { outsideStateStartedAt = now; gpsModeStartedAt = now; }
    persistEscalation();
    return false;
  }
  if (!wasInSafe && inSafe) {
    lastLocationType = loc.locationType; safeZoneStateStartedAt = now; outsideStateStartedAt = 0; gpsModeStartedAt = 0;
    warningSent = false; emergencySent = false; lastSosTelemetryAt = 0; lastPeriodicReportAt = 0;
    store.clearEscalation();
    if (wifi.isAttached) store.saveLastZoneName(zoneDisplayName(wifi));
    log("Safe zone entered");
    sendStatusSms("entered", loc);
    sendZoneTransitionTelemetry("entered", wifi);
    return true;
  }
  if (wasInSafe && !inSafe) {
    // Boot hold: the restored state says we were in a safe zone, but the radio has only just started.
    // A scan that has not found the zone YET is not evidence the elder left, so leave the state alone
    // for the first seconds and re-evaluate on the next tick. Without this the departure SMS could
    // fire on every reboot that happens to beat the first scan.
    if (now - serviceStartedAt < SFA_STARTUP_EXIT_HOLD_MS) {
      log("Startup: no safe zone visible yet, holding departure");
      return false;
    }
    lastLocationType = loc.locationType; safeZoneStateStartedAt = 0; outsideStateStartedAt = now; gpsModeStartedAt = now;
    warningSent = false; emergencySent = false; lastSosTelemetryAt = 0; lastPeriodicReportAt = 0;
    persistEscalation();
    log("Safe zone exited");
    sendStatusSms("exited", loc);
    sendZoneTransitionTelemetry("exited", wifi);
    return true;
  }
  if (inSafe) {
    lastLocationType = loc.locationType; gpsModeStartedAt = 0;
    warningSent = false; emergencySent = false; lastSosTelemetryAt = 0;
    if (wifi.isAttached) store.saveLastZoneName(zoneDisplayName(wifi));
    persistEscalation();
    return false;
  }
  // outside
  lastLocationType = loc.locationType;
  if (gpsModeStartedAt == 0) gpsModeStartedAt = now;
  uint64_t elapsed = now - gpsModeStartedAt;
  if (!emergencySent && elapsed >= SFA_EMERGENCY_DELAY_MS) {
    warningSent = true; emergencySent = true; persistEscalation();
    LocationSnapshot rl = loc; ensureRiskCoords(rl);   // SOS always carries a position
    sendStatusSms("EMERGENCY", rl);
    sendRiskTelemetry("SOS", "EMERGENCY", wifi);
    lastSosTelemetryAt = now;
    return true;
  }
  if (!warningSent && elapsed >= SFA_WARNING_DELAY_MS) {
    warningSent = true; persistEscalation();
    LocationSnapshot rl = loc; ensureRiskCoords(rl);   // WARNING always carries a position
    sendStatusSms("WARNING", rl);
    sendRiskTelemetry("GEOFENCE_EXIT_HINT", "WARNING", wifi);
    return true;
  }
  persistEscalation();
  return false;
}

static ZoneVerb consumeZoneVerb(const WifiStatus& wifi) {
  uint64_t now = sfaNowMs();
  if (!pendingZoneVerb.isEmpty()) {
    ZoneVerb v{pendingZoneVerb, now - pendingZoneVerbStartedAt};
    pendingZoneVerb = "";
    return v;
  }
  if (isInSafeZone(wifi)) {
    if (!wifi.isAttached && store.isBleSafeZoneActive()) return ZoneVerb{"moved", 0};
    if (safeZoneStateStartedAt == 0) safeZoneStateStartedAt = now;
    return ZoneVerb{"stayed", now - safeZoneStateStartedAt};
  }
  if (outsideStateStartedAt == 0) outsideStateStartedAt = now;
  return ZoneVerb{"moved", now - outsideStateStartedAt};
}

static String gyroMovementVerb() {
  if (gSamples.empty()) return "stayed";
  double sum = 0;
  for (auto& s : gSamples) sum += sqrt(s.gyX * s.gyX + s.gyY * s.gyY + s.gyZ * s.gyZ);
  double avg = sum / gSamples.size();
  return avg >= SFA_GYRO_MOVEMENT_THRESHOLD ? "moved" : "stayed";
}

static String gpsVerb(const LocationSnapshot& loc, const String& fallback) {
  if (!loc.hasCoords) { hasGpsStayed = false; return fallback; }
  bool hadPrev = hasLastGpsVerbLoc && lastGpsVerbLocation.hasCoords;
  LocationSnapshot prev = lastGpsVerbLocation;
  lastGpsVerbLocation = loc; hasLastGpsVerbLoc = true;
  if (!hadPrev) { gpsStayedStartedAt = sfaNowMs(); hasGpsStayed = true; return fallback; }
  double dist = sfaDistanceMeters(prev.latitude, prev.longitude, loc.latitude, loc.longitude);
  if (dist <= SFA_GPS_STAY_DISTANCE_M) { if (!hasGpsStayed) { gpsStayedStartedAt = sfaNowMs(); hasGpsStayed = true; } return "stayed"; }
  hasGpsStayed = false; return "moved";
}

static String verbForLocation(const String& verb, const LocationSnapshot& loc) {
  if (loc.locationType == "GPS") return gpsVerb(loc, verb);
  if (loc.locationType == "BLE") return "moved";
  return verb;
}

static String eventTypeForVerb(const String& verb) {
  // A flat battery is reported as its own event so the server raises the operational alert even
  // while everything else looks routine.
  if (sfaBatteryLevel() <= SFA_LOW_BATTERY_PCT) return "LOW_BATTERY";
  return verb == "exited" ? "GEOFENCE_EXIT_HINT" : "PERIODIC";
}

static String deviceStatusFor(const LocationSnapshot& loc) {
  // Battery first: a nearly-flat device is the most actionable operational state, and LOW_BATTERY
  // is the spec enum for it.
  if (sfaBatteryLevel() <= SFA_LOW_BATTERY_PCT) return "LOW_BATTERY";
  if (loc.locationType == "GPS" && !loc.hasCoords) return "GPS_WEAK";
  if (loc.locationType == "GPS") {
    uint64_t elapsed = gpsModeStartedAt ? (sfaNowMs() - gpsModeStartedAt) : 0;
    if (elapsed >= SFA_EMERGENCY_DELAY_MS) return "EMERGENCY";
    if (elapsed >= SFA_WARNING_DELAY_MS) return "WARNING";
  }
  return "NORMAL";
}

static ApiResult sendTelemetryChecked(const String& payload) {
  ApiResult r = sfaSendTelemetry(payload);
  if (r.ok) {
    JsonDocument d;
    if (deserializeJson(d, r.body) == DeserializationError::Ok) {
      JsonObject data = d["data"].as<JsonObject>();
      if (!data.isNull()) {
        if ((int)(data["accepted"] | -1) == 0 && (int)(data["duplicated"] | 0) > 0) {
          uint32_t latest = sfaGetLatestSeq(store.deviceId());
          if (store.ensureSeqAtLeast(latest)) log("Seq re-synced after duplicate (server=" + String(latest) + ")");
        }
        // Server-driven config sync: the ACK carries configChanged + serverConfigVersion. Re-pull
        // /config (+ guardians) and update local safe zones / guardian info whenever the server
        // flags a change OR reports a version we don't hold, so the device stays in sync.
        int serverVersion = data["serverConfigVersion"] | -1;
        bool configChanged = data["configChanged"] | false;
        if (configChanged || (serverVersion >= 0 && serverVersion != store.configVersion())) {
          syncConfigFromServer(serverVersion, configChanged);
        }
      }
    }
  }
  return r;
}

// Pull authoritative safe zones + guardians from the server and persist them + the new version.
static void syncConfigFromServer(int serverVersion, bool configChanged) {
  String deviceId = store.deviceId();
  String configJson = sfaGetConfigJson(deviceId);
  if (configJson.isEmpty()) { log("Config sync: /config fetch failed"); return; }
  JsonDocument cdoc;
  if (deserializeJson(cdoc, configJson) != DeserializationError::Ok) { log("Config sync: parse failed"); return; }
  int version = cdoc["configVersion"] | serverVersion;
  String elderId = String((const char*)(cdoc["elderId"] | ""));
  String safeZonesJson; serializeJson(cdoc["safeZones"], safeZonesJson);
  String elderName = sfaGetElderName(deviceId);
  String guardiansJson = elderId.isEmpty() ? String("[]") : sfaGetGuardiansJson(elderId);
  store.saveServerSync(elderName, elderId, safeZonesJson, guardiansJson);
  store.saveConfigVersion(version);
  log("Config synced (v" + String(version) + ", changed=" + String(configChanged ? 1 : 0) + ")");
}

static void doSend(const LocationSnapshot& loc, bool inSafe, const String& eventType,
                   const String& verb, uint64_t durationMs, const String& deviceStatus) {
  if (gSamples.empty()) { log("Telemetry skipped: no samples"); return; }
  String payload = sfaBuildTelemetryPayload(store, gSamples, loc, inSafe, sfaBatteryLevel(),
                                            eventType, verb, durationMs, deviceStatus);
  // Diagnose coordinate-less reports at the source: track.html can only draw points where
  // hasLocation is true, so log which source came up empty instead of silently sending null.
  if (!loc.hasCoords) {
    double dlat, dlng;
    log("No coords in " + loc.locationType + " report — gnssFix=" + String(nonWifiGpsStatus().hasFix ? "Y" : "N") +
        " phoneGpsCached=" + String(sfaLastKnownPhoneGps(dlat, dlng) ? "Y" : "N") +
        " gnssSeen=" + String(sfaModemGnssSeen() ? "Y" : "N"));
  }
  ApiResult r = sendTelemetryChecked(payload);
  store.saveLastVerb(verb);
  recordTxLog(payload, r);
  log("Telemetry " + String(r.code) + ": " + (r.body.length() ? r.body.substring(0, 80) : String(r.ok ? "sent" : "err")));
}

static void sendTelemetryReport(const WifiStatus& wifi, const LocationSnapshot& loc) {
  bool inSafe = isInSafeZone(wifi);
  ZoneVerb zv = consumeZoneVerb(wifi);
  String nverb = verbForLocation(zv.verb, loc);
  uint64_t durationMs;
  if (loc.locationType == "GPS" && nverb == "stayed") {
    if (!hasGpsStayed) { gpsStayedStartedAt = sfaNowMs(); hasGpsStayed = true; }
    durationMs = sfaNowMs() - gpsStayedStartedAt;
  } else durationMs = zv.durationMs;
  doSend(loc, inSafe, eventTypeForVerb(nverb), nverb, durationMs, deviceStatusFor(loc));
}

static void sendZoneTransitionTelemetry(const String& verb, const WifiStatus& wifi) {
  LocationSnapshot loc = locationSnapshot(wifi);
  String nverb = verbForLocation(verb, loc);
  doSend(loc, isInSafeZone(wifi), eventTypeForVerb(nverb), nverb, 0, deviceStatusFor(loc));
}

static void sendRiskTelemetry(const String& eventType, const String& label, const WifiStatus& wifi) {
  LocationSnapshot loc = locationSnapshot(wifi);
  ensureRiskCoords(loc);   // WARNING/SOS telemetry must always carry coordinates
  ZoneVerb zv = consumeZoneVerb(wifi);
  String nverb;
  if (eventType == "SOS") nverb = gyroMovementVerb();
  else if (eventType == "GEOFENCE_EXIT_HINT") nverb = "approached-boundary";
  else nverb = verbForLocation(zv.verb, loc);
  String riskStatus = (eventType == "SOS") ? "EMERGENCY" : (eventType == "GEOFENCE_EXIT_HINT" ? "WARNING" : label);
  doSend(loc, isInSafeZone(wifi), eventType, nverb, zv.durationMs, riskStatus);
}

static void sendScheduledTelemetry(const WifiStatus& wifi, const LocationSnapshot& loc) {
  uint64_t now = sfaNowMs();
  if (loc.locationType == "GPS" && emergencySent && !isInSafeZone(wifi)) {
    if (lastSosTelemetryAt == 0 || now - lastSosTelemetryAt >= SFA_SOS_REPEAT_PERIOD_MS) {
      lastSosTelemetryAt = now;
      sendRiskTelemetry("SOS", "EMERGENCY", wifi);
    }
    return;
  }
  uint64_t interval = (loc.locationType == "GPS") ? SFA_GPS_TELEMETRY_REPORT_PERIOD_MS : SFA_TELEMETRY_REPORT_PERIOD_MS;
  if (lastPeriodicReportAt != 0 && now - lastPeriodicReportAt < interval) return;
  lastPeriodicReportAt = now;
  sendTelemetryReport(wifi, loc);
}

// ---------------- SMS (Korean templates identical to SFD) ----------------
static String elderLabel() {
  String name = store.elderName(); name.trim();
  if (name.isEmpty() || name.equalsIgnoreCase("elder")) return "어르신(" + store.deviceId() + ")";
  return name.endsWith("님") ? name : name + "님";
}
static String mapLink(const LocationSnapshot& loc) {
  if (!loc.hasCoords) return "";
  char b[64]; snprintf(b, sizeof(b), "https://maps.google.com/?q=%.5f,%.5f", loc.latitude, loc.longitude);
  return String(b);
}
static void sendStatusSms(const String& event, const LocationSnapshot& loc) {
  String zone = store.lastZoneName(); if (zone.isEmpty()) zone = "안전 구역";
  String link = mapLink(loc); String locLine = link.isEmpty() ? "" : ("\n현재 위치: " + link);
  String msg;
  if (event == "entered") msg = "[SafeFinder] " + elderLabel() + "이 안전 구역 " + zone + "에 들어가셨습니다.";
  else if (event == "exited") msg = "[SafeFinder] " + elderLabel() + "이 안전 구역 " + zone + "을 벗어났습니다." + locLine;
  else if (event == "WARNING") msg = "[SafeFinder] 주의: " + elderLabel() + "이 안전 구역을 벗어난 지 5분이 넘어 관심이 필요합니다." + locLine;
  else msg = "[SafeFinder] SOS: " + elderLabel() + "이 안전 구역을 벗어난 지 30분이 지났습니다. 지금 바로 위치를 확인해 주세요." + locLine;

  auto phones = store.guardianPhones();
  if (phones.empty()) { log("SMS skipped: no guardian phone"); return; }
  // Modem is OFF by default — power it on and wait for registration before sending, then off.
  if (!sfaModemBeginSms()) { log("SMS skipped: modem not ready (" + sfaSmsLastDetail() + ")"); return; }
  for (auto& p : phones) {
    bool ok = sfaSmsSend(p, msg);
    log("SMS " + String(ok ? "sent" : "failed") + " to " + p + " for " + event + " (" + sfaSmsLastDetail() + ")");
  }
  sfaModemEndSms();
}

static void syncZoneIdsFromServer() {
  if (!store.hasMissingZoneIds()) return;
  String elder = sfaGetElderId(store.deviceId());
  if (elder.isEmpty()) return;
  String zones = sfaGetSafeZonesJson(elder);
  if (zones.isEmpty()) return;
  if (store.ensureZoneIds(zones)) log("Safe zone IDs synced from server");
}

// ---------------- connectivity (away BLE loop) ----------------
static void clearHotspotSafeIfNeeded() {
  if (!hotspotSafeActive) return;
  hotspotSafeActive = false; store.saveBleSafeZone(false);
  log("Left hotspot; cleared hotspot safe-zone marker");
}
static void exitAwayState() { if (awayPhase != AWAY_NONE) { awayPhase = AWAY_NONE; log("Away: SafeZone connected; advertising loop stopped"); } }

static void attemptSafeZoneConnect() {
  String ssid, pass, name, outcome;
  // ALWAYS look at the current radio environment before choosing where to join. Without this the
  // 5-minute scan cache kept returning the AP we had just lost, so we retried a dead SSID forever.
  sfaWifiEnsureScan(SFA_AWAY_SCAN_FRESH_MS);
  // Prefer any in-range registered safe zone (home Wi-Fi or the "내 폰" hotspot) using its stored
  // password; only fall back to the bare hotspot join when nothing registered is visible.
  if (sfaBestSafezoneInRange(store, ssid, pass, name)) {
    log("Away: strongest in-range zone = '" + ssid + "'");
    // The zone matched by scan may lack a password (e.g. a WIFI "내 폰" synced from the server,
    // which omits passwords). Recover it from any stored zone, then the hotspot fallback.
    if (pass.isEmpty()) pass = sfaZonePasswordForSsid(store, ssid);
    if (pass.isEmpty() && sfaNamesMatch(ssid, SFA_HOTSPOT_SSID)) pass = SFA_HOTSPOT_PASSWORD;
    // Home (FIXED_AP) fallback: the server strips zone passwords, so a secured home AP would
    // otherwise be un-joinable and the device would stay dark whenever the hotspot is off.
    if (pass.isEmpty() && !sfaNamesMatch(ssid, SFA_HOTSPOT_SSID)) pass = SFA_HOME_PASSWORD;
    outcome = sfaConnectToZone(ssid, pass);
  } else if (sfaSsidNearby(SFA_HOTSPOT_SSID)) {
    outcome = sfaConnectToHotspot(store);
  } else {
    outcome = sfaConnectToHotspot(store);
  }
  log("Away: attempting SafeZone connect -> " + outcome);
}
static void runAwayStateMachine() {
  uint32_t now = millis();
  switch (awayPhase) {
    case AWAY_NONE: {
      // Drop the association we just lost so the ESP32 stops silently retrying a vanished SSID
      // (that background retry is why it looked "still connected" to a hotspot that was switched
      // off), then rescan so the decision below reflects what is actually on the air right now.
      WiFi.disconnect(false);
      sfaWifiEnsureScan(SFA_AWAY_SCAN_FRESH_MS);
      bool known = sfaSsidNearby(SFA_HOTSPOT_SSID);
      if (!known) { String a, b, c; known = sfaBestSafezoneInRange(store, a, b, c); }
      if (known) { awayPhase = AWAY_CONNECTING; awayPhaseStartedAt = now; sfaBleSetAdvertising(false); attemptSafeZoneConnect(); }
      else { awayPhase = AWAY_ADVERTISING; awayPhaseStartedAt = now; sfaBleSetAdvertising(true); log("Away: BLE advertising so SFC can detect SFA"); }
      break;
    }
    case AWAY_ADVERTISING:
      sfaBleSetAdvertising(true);
      if (now - awayPhaseStartedAt >= SFA_BLE_ADVERTISE_DURATION_MS) { awayPhase = AWAY_CONNECTING; awayPhaseStartedAt = now; sfaBleSetAdvertising(false); attemptSafeZoneConnect(); }
      break;
    case AWAY_CONNECTING:
      if (now - awayPhaseStartedAt >= SFA_CONNECT_GRACE_MS) { awayPhase = AWAY_ADVERTISING; awayPhaseStartedAt = now; sfaBleSetAdvertising(true); log("Away: connect grace elapsed; resuming BLE advertising"); }
      break;
  }
}

// Send an arbitrary alert SMS to all guardians, managing the modem power (OFF→ON→send→OFF).
static void sendCustomSms(const String& msg) {
  auto phones = store.guardianPhones();
  if (phones.empty()) return;
  if (!sfaModemBeginSms()) { log("SMS skipped: modem not ready (" + sfaSmsLastDetail() + ")"); return; }
  for (auto& p : phones) { bool ok = sfaSmsSend(p, msg); log("SMS " + String(ok ? "sent" : "failed") + " to " + p); }
  sfaModemEndSms();
}

// req 5: SMS the guardian whenever the connected Wi-Fi actually changes (2.4G/5G roaming of the
// same AP is ignored via name normalization, so home↔hotspot is a real change but a band switch is not).
static String lastWifiSsid = "";
static void checkWifiChangeSms(const WifiStatus& st) {
  String ssid = st.apName;
  if (ssid.isEmpty()) return;
  if (lastWifiSsid.isEmpty()) { lastWifiSsid = ssid; return; }     // first association: no SMS
  if (sfaNamesMatch(ssid, lastWifiSsid)) { lastWifiSsid = ssid; return; }
  String prev = lastWifiSsid; lastWifiSsid = ssid;
  log("WiFi changed: " + prev + " -> " + ssid);
  sendCustomSms("[SafeFinder] " + elderLabel() + " 연결 WiFi 변경: " + prev + " \xE2\x86\x92 " + ssid);
  // Whenever we alert the guardian by SMS we also push telemetry, so the server/map reflect the same
  // moment. This path had no telemetry at all: the guardian got "connected to Nobug" by SMS while
  // the server still showed the old AP until the next 10-minute periodic report.
  LocationSnapshot loc = locationSnapshot(st);
  sendTelemetryReport(st, loc);
  lastPeriodicReportAt = sfaNowMs();   // this report counts as the periodic one
}

static uint32_t lastWifiLinkAt = 0;

static void connectivityTick() {
  if (!operational) return;
  WifiStatus st = sfaWifiRead(store);
  bool toSafe = st.isAttached;
  bool toHot = sfaConnectedToSsid(st, SFA_HOTSPOT_SSID);

  // Watchdog for a wedged radio: any live association refreshes the timer. If we go this long with
  // no link at all, restart the Wi-Fi stack — the driver otherwise keeps chasing an SSID that no
  // longer exists (hotspot switched off) and never notices the home AP sitting in range.
  // The modem is brought up once at boot (power + registration + GNSS + PDP) and is never touched
  // again here — postJson() simply uses LTE whenever Wi-Fi is down. Toggling it at runtime is what
  // caused brownouts and disturbed GNSS.
  if (st.hasWifiConnection) {
    lastWifiLinkAt = millis();
  } else {
    if (lastWifiLinkAt == 0) lastWifiLinkAt = millis();
    uint32_t downFor = millis() - lastWifiLinkAt;
    if (downFor >= SFA_WIFI_RECOVERY_MS) {
      log("No Wi-Fi link for " + String((millis() - lastWifiLinkAt) / 1000) + "s; restarting Wi-Fi stack");
      WiFi.disconnect(true);
      WiFi.mode(WIFI_OFF);
      delay(200);
      sfaWifiBegin();
      sfaWifiScanNow();          // fresh picture of what is actually reachable
      lastWifiLinkAt = millis(); // give the fresh stack a full window before trying again
      awayPhase = AWAY_NONE;     // re-enter the away machine so it picks the strongest AP now
    }
  }

  if (toSafe || toHot) {
    // Came home: if we're on the guardian hotspot (toHot, or a SUSPECTED/REGISTERED_HOTSPOT zone)
    // and the fixed home AP is reachable (in scan) or GPS shows we're inside a FIXED_AP home
    // center, drop the hotspot and rejoin home Wi-Fi so the device reports the home zone, not "내폰".
    String apType = sfaApTypeForConnected(store, st);
    bool onHotspot = toHot || apType.equalsIgnoreCase("SUSPECTED_HOTSPOT") || apType.equalsIgnoreCase("REGISTERED_HOTSPOT");
    // Pull the guardian phone's fix while we are on its hotspot. This is the one place the device can
    // get a REAL GPS position indoors, where its own GNSS has no sky — SFC has been serving it on
    // :8765 all along and nothing was calling for it, which is why every report said
    // "phoneGpsCached=N". Only on the hotspot: on any other network the gateway is a router with
    // nothing on that port, and the request would just burn a connect timeout every tick.
    if (onHotspot) {
      sfaRefreshPhoneGps();
      String homeSsid, homePass; bool home = false; int homeRssi = -1000;
      if (sfaFixedApInScan(store, homeSsid, homePass, &homeRssi)) {
        // Only give up a strong hotspot for the home AP when home is MEANINGFULLY stronger. Simply
        // being visible is not enough: the elder standing next to the guardian had the hotspot at
        // -27 dBm while home showed -58, so "visible" kept yanking the device back home and it
        // flapped Nobug -> nobug_home -> Nobug every few minutes (seen 2026-08-13 12:34-12:51).
        home = (homeRssi > st.signal + SFA_ROAM_HYSTERESIS_DB);
        if (!home) {
          log("Home '" + homeSsid + "' visible (" + String(homeRssi) + "dBm) but hotspot is stronger (" +
              String(st.signal) + "dBm); staying on hotspot");
        }
      }
      else { GpsStatus g = nonWifiGpsStatus(); if (g.hasFix && sfaFixedApNearGps(store, g.latitude, g.longitude, homeSsid, homePass)) home = true; }
      if (home && !homeSsid.isEmpty() && !sfaNamesMatch(st.apName, homeSsid)) {
        log("Home reachable; leaving hotspot -> " + homeSsid);
        WiFi.disconnect(false);
        delay(100);
        sfaConnectToZone(homeSsid, homePass);
        clearHotspotSafeIfNeeded();
        return;   // next tick picks up the home association
      }
    }
    checkWifiChangeSms(st);   // req 5: notify on a real Wi-Fi change + push telemetry at the same time
    exitAwayState(); sfaBleSetAdvertising(false);
    if (toHot && !toSafe) {
      store.saveBleSafeZone(true, "", "SFC-Hotspot");
      if (!hotspotSafeActive) { hotspotSafeActive = true; log("Connected to hotspot; marked as safe zone"); }
    } else clearHotspotSafeIfNeeded();
    if (!sfaTimeValid()) sfaTimeBegin();
    return;
  }
  clearHotspotSafeIfNeeded();
  runAwayStateMachine();
}

// ---------------- sample tick (collectGyroSample) ----------------
static void sampleTick() {
  if (!store.isConfigured()) return;
  double mag = sfaImuMovementMagnitude();
  GyroSample s; s.timestampMs = millis(); s.gyX = sfaRound2(mag); s.gyY = 0; s.gyZ = 0;
  latestGyro = s;
  gSamples.push_back(s);
  while ((int)gSamples.size() > SFA_GYRO_REPORT_SAMPLE_COUNT) gSamples.erase(gSamples.begin());

  WifiStatus wifi = effectiveWifiStatus(sfaWifiRead(store));
  LocationSnapshot loc = locationSnapshot(wifi);
  if (store.hasMissingZoneIds()) syncZoneIdsFromServer();
  bool eventSent = updateLocationState(wifi, loc);
  if (!eventSent) sendScheduledTelemetry(wifi, loc);
  log(loc.locationType + ": " + (wifi.isAttached ? ("attached " + wifi.apName) : String("away")) +
      (loc.hasCoords ? (" @" + String(loc.latitude, 5) + "," + String(loc.longitude, 5)) : ""));
}

// ---------------- provisioning / operational transitions ----------------
static void handleIncomingConfig(const String& cfg) {
  JsonDocument d;
  if (deserializeJson(d, cfg) != DeserializationError::Ok) return;
  const char* mt = d["messageType"] | "";
  if (strcasecmp(mt, "safeZoneUpdate") == 0) {
    bool applied = store.applySafeZoneUpdate(cfg);
    sfaBleSetRegistrationState(applied ? "SAFEZONE_UPDATED" : "FAILED", -1, "");
    log(String("SafeZone update ") + (applied ? "applied" : "ignored"));
    if (!operational && store.isConfigured()) startOperational();
    return;
  }
  store.saveConfig(cfg);
  log("Config saved for " + store.deviceId() + "; registering...");
  String reg = sfaBuildRegisterPayload(cfg);
  ApiResult r = sfaRegisterDevice(reg);
  sfaBleSetRegistrationState(r.ok ? "SUCCESS" : "FAILED", r.code, r.body);
  log("Register " + String(r.code) + ": " + (r.body.length() ? r.body.substring(0, 80) : String(r.ok ? "ok" : "err")));
  if (store.isConfigured() && !operational) startOperational();
}

// Diagnostic: print the safe zones + guardians currently held in NVS so it is obvious from the
// serial log whether provisioning/config actually persisted them (req 1 concern).
static void logSafeZones() {
  JsonDocument doc;
  if (!store.loadConfig(doc)) { log("SafeZones: NONE in memory (config not saved!)"); return; }
  JsonArray zones = doc["safeZones"].as<JsonArray>();
  int n = zones.isNull() ? 0 : (int)zones.size();
  log("SafeZones in memory: " + String(n));
  if (!zones.isNull()) for (JsonObject z : zones) {
    bool hasPw = strlen(z["password"] | "") > 0;
    log("  - name=" + String((const char*)(z["name"] | "")) +
        " ssid=" + String((const char*)(z["ssid"] | "")) +
        " apType=" + String((const char*)(z["apType"] | "")) +
        " enabled=" + String((bool)(z["enabled"] | true)) +
        (hasPw ? " [pw]" : " [no-pw]"));
  }
  log("Guardians: " + String((int)store.guardianPhones().size()));
}

static void startOperational() {
  operational = true;
  store.saveBleSafeZone(false);
  hotspotSafeActive = false;
  sfaBleSetAdvertising(false);
  serviceStartedAt = sfaNowMs();
  restoreEscalation();
  logSafeZones();
  String msg = sfaRequestConnection(store);
  log("Operational mode: " + store.deviceId() + " | " + msg);
  sfaTimeBegin();
  syncZoneIdsFromServer();
  lastSampleTick = millis() - SFA_GYRO_SAMPLE_PERIOD_MS;   // fire soon
  lastConnTick = millis();                                  // let the first Wi-Fi join settle
}

// ---------------- boot self-test (GPS + SMS to 010-7260-8813) ----------------
#if SFA_SELFTEST_ON_BOOT
static void bootSelfTest() {
  Serial.println("========== SFA BOOT SELF-TEST ==========");
  Serial.printf("[SELFTEST] GNSS power=%s. Coordinates come from the modem's GNSS when it has a fix,\n",
                sfaModemGnssIsOn() ? "ON" : "OFF");
  Serial.println("[SELFTEST]   otherwise from SFC over the hotspot, otherwise from the safe zone centre.");
  String to = SFA_SELFTEST_SMS_TO;
  String msg = "[SafeFinder] SFA \xEB\xB6\x80\xED\x8C\x85 \xED\x85\x8C\xEC\x8A\xA4\xED\x8A\xB8 \xEB\xAC\xB8\xEC\x9E\x90\xEC\x9E\x85\xEB\x8B\x88\xEB\x8B\xA4.";
  Serial.println("[SELFTEST] SMS: powering modem (OFF by default) ...");
  if (sfaModemBeginSms()) {
    bool ok = sfaSmsSend(to, msg);
    Serial.printf("[SELFTEST] SMS RESULT: %s | %s\n", ok ? "OK" : "FAIL", sfaSmsLastDetail().c_str());
    sfaModemEndSms();
  } else {
    Serial.printf("[SELFTEST] SMS: modem not ready | %s\n", sfaSmsLastDetail().c_str());
  }
  Serial.println("========================================");
}
#endif

// ---------------- Arduino entry points ----------------
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println();
  Serial.println("SFA (Safe Finder Arduino / ESP32-S3 + A7670E) starting...");
  // Print the console's own help at boot. It doubles as proof that the image actually running on the
  // board is one that HAS the console — without it, a silent prompt is ambiguous between "wrong
  // firmware" and "wrong monitor setting".
  Serial.println(String("console: ") + SFA_CONSOLE_COMMANDS + "   (line ending: NL or CR)");
#if SFA_DEMO_MODE
  // Loud on purpose. Demo timings must never be mistaken for production behaviour in a log, and this
  // line is the reminder to set SFA_DEMO_MODE back to 0 after filming.
  Serial.println("*** DEMO MODE — WARNING 1min / SOS 2min, holds shortened. NOT production. ***");
  Serial.printf("*** revert: sfa_config.h  #define SFA_DEMO_MODE 0  (warn=%lus sos=%lus)\n",
                (unsigned long)(SFA_WARNING_DELAY_MS / 1000), (unsigned long)(SFA_EMERGENCY_DELAY_MS / 1000));
#endif
  if (SFA_STATUS_LED_PIN >= 0) pinMode(SFA_STATUS_LED_PIN, OUTPUT);

  store.begin();
  sfaWifiBegin();
  sfaGpsBegin();
  sfaBatteryBegin();
  sfaImuBegin();
  sfaSmsBegin();
  sfaBleBegin(&store);

#if SFA_SELFTEST_ON_BOOT
  bootSelfTest();
#endif

  if (store.isConfigured()) {
    startOperational();
  } else {
    Serial.println("Setup mode: advertising over BLE for SFC provisioning");
    sfaBleSetAdvertising(true);
  }
}

void loop() {
  sfaGpsPoll();
  pollSerialConsole();   // "log" dumps the sent JSON payloads, "gps" shows the GNSS state

  String cfg;
  if (sfaBleTakePendingConfig(cfg)) handleIncomingConfig(cfg);

  if (operational) {
    uint32_t now = millis();
    if (now - lastConnTick >= SFA_CONNECTIVITY_TICK_MS) { lastConnTick = now; connectivityTick(); }
    if (now - lastSampleTick >= SFA_GYRO_SAMPLE_PERIOD_MS) { lastSampleTick = now; sampleTick(); }
  }
  delay(20);
}
