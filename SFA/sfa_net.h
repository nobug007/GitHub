// HTTPS API client — analogue of SfdApiClient.kt.
#pragma once
#include <Arduino.h>

struct ApiResult { int code = 0; String body; bool ok = false; };

ApiResult sfaRegisterDevice(const String& json);
ApiResult sfaSendTelemetry(const String& json);

String    sfaGetElderId(const String& deviceId);         // "" on failure
String    sfaGetSafeZonesJson(const String& elderId);    // JSON array text, "" on failure
uint32_t  sfaGetLatestSeq(const String& deviceId);       // 0 if none/unknown
String    sfaGetConfigJson(const String& deviceId);      // /config data object (safeZones + configVersion)
String    sfaGetGuardiansJson(const String& elderId);    // /guardians array, "" on failure
String    sfaGetElderName(const String& deviceId);       // elder display name, "" on failure
// Fetch the guardian phone's GPS + time from SFC's on-hotspot HTTP server (GET gateway:8765/gps).
// True when a fix was returned; used instead of the modem GNSS when on the hotspot.
bool      sfaFetchPhoneGps(double& lat, double& lng, String& isoTime);
// Poll-limited wrapper for the connectivity tick: refreshes the cache at most every
// SFA_PHONE_GPS_POLL_MS. Call it only while actually on the guardian's hotspot — on any other
// network the gateway is a router with nothing on port 8765 and the request just burns a timeout.
bool      sfaRefreshPhoneGps(bool force = false);
// Why the last fetch succeeded or failed, in plain words — for the serial console.
String    sfaPhoneGpsLastDetail();
// Last GPS fetched from SFC, provided it is newer than SFA_PHONE_GPS_MAX_AGE_MS. Older than that it
// is the guardian's position rather than the device's, so it is reported as "no position".
bool      sfaLastKnownPhoneGps(double& lat, double& lng, float* accuracyOut = nullptr);
