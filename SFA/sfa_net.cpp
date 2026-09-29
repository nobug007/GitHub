#include "sfa_net.h"
#include "sfa_config.h"
#include "sfa_sms.h"          // LTE HTTPS fallback (A7670E)
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>

// All HTTP goes over home Wi-Fi when connected (safe zone); otherwise it falls back to HTTPS over
// LTE via the A7670E (away). This mirrors the reference: Wi-Fi HTTPClient + TinyGSM https_*.
static ApiResult postJson(const char* url, const String& body) {
  ApiResult res;
  if (WiFi.status() == WL_CONNECTED) {
    WiFiClientSecure client; client.setInsecure();
    HTTPClient http;
    http.setConnectTimeout(15000);
    http.setTimeout(15000);
    if (!http.begin(client, url)) { res.body = "begin failed"; return res; }
    http.addHeader("Content-Type", "application/json; charset=utf-8");
    http.addHeader("Accept", "application/json");
    int code = http.POST((uint8_t*)body.c_str(), body.length());
    res.code = code;
    res.body = http.getString();
    res.ok = code >= 200 && code < 300;
    http.end();
    return res;
  }
  if (sfaModemDataReady()) {
    String resp;
    int code = sfaModemHttpsRequest("POST", String(url), body, resp);
    res.code = code; res.body = resp; res.ok = code >= 200 && code < 300;
    return res;
  }
  res.body = "no wifi/lte";
  return res;
}

static String getText(const String& url) {
  if (WiFi.status() == WL_CONNECTED) {
    WiFiClientSecure client; client.setInsecure();
    HTTPClient http;
    http.setConnectTimeout(15000);
    http.setTimeout(15000);
    if (!http.begin(client, url)) return "";
    http.addHeader("Accept", "application/json");
    int code = http.GET();
    String body = (code >= 200 && code < 300) ? http.getString() : "";
    http.end();
    return body;
  }
  if (sfaModemDataReady()) {
    String resp;
    int code = sfaModemHttpsRequest("GET", url, "", resp);
    return (code >= 200 && code < 300) ? resp : "";
  }
  return "";
}

static bool     gHavePhoneGps = false;
static double   gLastPhoneLat = 0, gLastPhoneLng = 0;
static float    gLastPhoneAcc = 0;
static uint32_t gLastPhoneAt  = 0;

// Why the last fetch ended the way it did. Every exit records one, because a silent false here is
// indistinguishable between "SFC is not running", "no location permission", "wrong network" and
// "the fix was too old" — and those need completely different fixes.
static String gPhoneGpsDetail = "not tried yet";
String sfaPhoneGpsLastDetail() { return gPhoneGpsDetail; }

bool sfaFetchPhoneGps(double& lat, double& lng, String& isoTime) {
  if (WiFi.status() != WL_CONNECTED) { gPhoneGpsDetail = "no Wi-Fi link"; return false; }
  IPAddress gw = WiFi.gatewayIP();
  if (gw == IPAddress((uint32_t)0)) { gPhoneGpsDetail = "no gateway IP"; return false; }
  String url = "http://" + gw.toString() + ":8765/gps";   // SFC's on-hotspot GPS server
  HTTPClient http;
  // Generous by local-network standards, because the phone may be waking its GPS to answer. A
  // timeout here reads as "no position" and costs a whole polling interval, which is a worse trade
  // than waiting a few extra seconds on a link that is one hop away.
  http.setConnectTimeout(5000);
  http.setTimeout(8000);
  if (!http.begin(url)) { gPhoneGpsDetail = "begin() failed for " + url; return false; }
  int code = http.GET();
  if (code != 200) {
    http.end();
    // A negative code is a transport error (-1 = could not connect): SFC's service is not listening
    // on the phone, or this gateway is a router rather than the guardian's phone.
    gPhoneGpsDetail = "GET " + url + " -> " + String(code) +
                      (code < 0 ? " (nothing listening — is SFC's monitor service running?)" : "");
    return false;
  }
  String body = http.getString();
  http.end();
  JsonDocument d;
  if (deserializeJson(d, body) != DeserializationError::Ok) {
    gPhoneGpsDetail = "bad JSON: " + body.substring(0, 80);
    return false;
  }
  if (d["lat"].isNull() || d["lng"].isNull()) {
    gPhoneGpsDetail = "SFC has no fix (location permission off, or the phone has no position yet)";
    return false;
  }
  lat = d["lat"].as<double>();
  lng = d["lng"].as<double>();
  isoTime = String((const char*)(d["time"] | ""));
  // SFC reports how old the phone's own fix is. A phone that has been indoors on a shelf can hand
  // back a last-known-location from hours ago; taking that as "where the device is now" would be
  // worse than reporting nothing. Reject it here rather than let it reach a guardian's map.
  uint32_t fixAgeMs = d["fixAgeMs"] | 0UL;
  if (fixAgeMs > SFA_PHONE_GPS_MAX_AGE_MS) {
    gPhoneGpsDetail = "SFC's own fix is " + String(fixAgeMs / 1000) + "s old — too stale to use";
    return false;
  }
  gLastPhoneLat = lat; gLastPhoneLng = lng;
  gLastPhoneAcc = d["accuracy"] | 0.0f;
  gLastPhoneAt  = millis();
  gHavePhoneGps = true;
  gPhoneGpsDetail = "ok";
  return true;
}

bool sfaRefreshPhoneGps(bool force) {
  static uint32_t lastTryAt = 0;
  if (!force && lastTryAt != 0 && millis() - lastTryAt < SFA_PHONE_GPS_POLL_MS) return gHavePhoneGps;
  lastTryAt = millis();
  double lat, lng; String iso;
  if (sfaFetchPhoneGps(lat, lng, iso)) {
    Serial.printf("[PHONEGPS] %.6f,%.6f acc=%.0fm from SFC\n", lat, lng, gLastPhoneAcc);
    return true;
  }
  Serial.println("[PHONEGPS] no position: " + gPhoneGpsDetail);
  return false;
}

bool sfaLastKnownPhoneGps(double& lat, double& lng, float* accuracyOut) {
  if (!gHavePhoneGps) return false;
  if (millis() - gLastPhoneAt > SFA_PHONE_GPS_MAX_AGE_MS) return false;
  lat = gLastPhoneLat; lng = gLastPhoneLng;
  if (accuracyOut) *accuracyOut = gLastPhoneAcc;
  return true;
}

ApiResult sfaRegisterDevice(const String& json) { return postJson(SFA_REGISTER_URL, json); }
ApiResult sfaSendTelemetry(const String& json)  { return postJson(SFA_TELEMETRY_URL, json); }

String sfaGetElderId(const String& deviceId) {
  String body = getText(String(SFA_API_BASE) + "/devices/" + deviceId + "/elder");
  if (body.isEmpty()) return "";
  JsonDocument doc;
  if (deserializeJson(doc, body) != DeserializationError::Ok) return "";
  JsonVariant d = doc.as<JsonVariant>();
  if (!doc["data"].isNull()) d = doc["data"].as<JsonVariant>();
  return String((const char*)(d["elderId"] | ""));
}

String sfaGetSafeZonesJson(const String& elderId) {
  String body = getText(String(SFA_API_BASE) + "/elders/" + elderId + "/safezones");
  if (body.isEmpty()) return "";
  JsonDocument doc;
  if (deserializeJson(doc, body) != DeserializationError::Ok) return "";
  JsonVariant arr = doc.as<JsonVariant>();
  if (!doc.is<JsonArray>() && !doc["data"].isNull()) arr = doc["data"].as<JsonVariant>();
  String out; serializeJson(arr, out); return out;
}

uint32_t sfaGetLatestSeq(const String& deviceId) {
  String body = getText(String(SFA_API_BASE) + "/devices/" + deviceId + "/logs?size=5");
  if (body.isEmpty()) return 0;
  JsonDocument doc;
  if (deserializeJson(doc, body) != DeserializationError::Ok) return 0;
  JsonArray logs = doc["data"]["logs"].as<JsonArray>();
  if (logs.isNull()) return 0;
  uint32_t maxSeq = 0;
  for (JsonObject l : logs) { uint32_t s = l["seq"] | 0; if (s > maxSeq) maxSeq = s; }
  return maxSeq;
}

String sfaGetConfigJson(const String& deviceId) {
  String body = getText(String(SFA_API_BASE) + "/devices/" + deviceId + "/config");
  if (body.isEmpty()) return "";
  JsonDocument doc;
  if (deserializeJson(doc, body) != DeserializationError::Ok) return "";
  JsonVariant d = doc.as<JsonVariant>();
  if (!doc["data"].isNull()) d = doc["data"].as<JsonVariant>();
  String out; serializeJson(d, out); return out;
}

String sfaGetGuardiansJson(const String& elderId) {
  String body = getText(String(SFA_API_BASE) + "/elders/" + elderId + "/guardians");
  if (body.isEmpty()) return "";
  JsonDocument doc;
  if (deserializeJson(doc, body) != DeserializationError::Ok) return "";
  JsonVariant arr = doc.as<JsonVariant>();
  if (!doc.is<JsonArray>() && !doc["data"].isNull()) arr = doc["data"].as<JsonVariant>();
  String out; serializeJson(arr, out); return out;
}

String sfaGetElderName(const String& deviceId) {
  String body = getText(String(SFA_API_BASE) + "/devices/" + deviceId + "/elder");
  if (body.isEmpty()) return "";
  JsonDocument doc;
  if (deserializeJson(doc, body) != DeserializationError::Ok) return "";
  JsonVariant d = doc.as<JsonVariant>();
  if (!doc["data"].isNull()) d = doc["data"].as<JsonVariant>();
  return String((const char*)(d["name"] | ""));
}
