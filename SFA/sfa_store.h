// NVS-backed persistent store — analogue of SfdStore.kt (SharedPreferences).
#pragma once
#include <Arduino.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include <vector>

struct EscalationState {
  uint64_t outsideStartedAtMs = 0;
  uint64_t gpsModeStartedAtMs = 0;
  bool warningSent = false;
  bool emergencySent = false;
  String lastLocationType;
};

// True for an enabled WIFI safe zone entry.
static inline bool sfaZoneIsWifiEnabled(JsonObjectConst z) {
  const char* t = z["zoneType"] | "";
  return (strcasecmp(t, "WIFI") == 0) && (z["enabled"] | true);
}

class SfaStore {
 public:
  void begin();

  bool   isConfigured();
  String deviceId();
  String configJson();

  void   saveConfig(const String& json);
  bool   applySafeZoneUpdate(const String& json);

  String elderName();
  String elderId();
  std::vector<String> guardianPhones();

  bool   safeZoneCenter(double& lat, double& lng);   // fixed-AP home GPS center
  String deviceBleId();

  // Telemetry seq (self-heal against server dedup).
  uint32_t nextTelemetrySeq();
  bool     ensureSeqAtLeast(uint32_t serverLatest);

  // Escalation state (survives reboot via epoch timestamps).
  void            saveEscalation(const EscalationState& s);
  EscalationState loadEscalation();
  void            clearEscalation();

  // BLE / hotspot ("보호자 근접") safe zone.
  void   saveBleSafeZone(bool connected, const String& address = "", const String& name = "");
  void   resetBleSafeZone();
  bool   isBleSafeZoneActive();
  String bleSafeZoneName();
  String bleSafeZoneAddress();
  String bleSafeZoneId();

  void   saveLastZoneName(const String& name);
  String lastZoneName();
  void   saveLastVerb(const String& verb);
  String lastVerb();

  // Zone lookups used by telemetry.
  String zoneNameForSsid(const String& ssid);
  String safeZoneIdForSsid(const String& ssid);
  bool   hasMissingZoneIds();
  bool   ensureZoneIds(const String& serverZonesJson);   // back-fill zoneIds from server

  // Server-driven config sync (telemetry ACK configChanged/serverConfigVersion).
  int    configVersion();                                 // -1 until first sync
  void   saveConfigVersion(int version);
  void   saveServerSync(const String& elderName, const String& elderId,
                        const String& safeZonesJson, const String& guardiansJson);

  bool   hasWifiSafeZone();
  String firstWifiSsid();
  String firstWifiBssid();
  String firstWifiZoneName();

  // Deserialize the stored config into a caller-owned document. False if none/invalid.
  bool   loadConfig(JsonDocument& doc);

 private:
  Preferences p_;
  String getStr(const char* key, const char* def = "");
  void   putStr(const char* key, const String& v);
};
