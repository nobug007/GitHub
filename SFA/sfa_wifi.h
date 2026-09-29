// Wi-Fi safe-zone detection + connection management.
// Analogue of WifiStatusReader.kt + WifiConnector.kt.
#pragma once
#include <Arduino.h>
#include "sfa_types.h"
#include "sfa_store.h"

void       sfaWifiBegin();
WifiStatus sfaWifiRead(SfaStore& store);

int        sfaWifiScanNow();                       // synchronous scan into the cache
void       sfaWifiEnsureScan(uint32_t maxAgeMs);   // rescan only if the cache is stale

bool       sfaZoneApNearby(SfaStore& store);       // a registered safe-zone AP is in a fresh scan
bool       sfaSsidNearby(const String& target);    // the given SSID (e.g. hotspot) is in a fresh scan
// Strongest safe zone currently visible; returns false if none. rssiOut (optional) receives that
// zone's scan RSSI so a caller can require real proximity, not mere visibility.
bool       sfaBestSafezoneInRange(SfaStore& store, String& ssid, String& pass, String& name,
                                  int* rssiOut = nullptr);
bool       sfaConnectedToSsid(const WifiStatus& st, const String& target);

String     sfaConnectToZone(const String& ssid, const String& pass);
String     sfaConnectToHotspot(SfaStore& store);
// Password of a registered zone whose ssid/name matches `ssid` (any zone type), "" if none.
String     sfaZonePasswordForSsid(SfaStore& store, const String& ssid);
// apType ("FIXED_AP" / "SUSPECTED_HOTSPOT" / "REGISTERED_HOTSPOT" / "") of the zone we are on.
String     sfaApTypeForConnected(SfaStore& store, const WifiStatus& st);
// Registered geofence center of the connected zone; true if it has one (report Home once on FIXED_AP entry).
bool       sfaZoneCenterForConnected(SfaStore& store, const WifiStatus& st, double& lat, double& lng);
// True if a FIXED_AP zone's stored GPS center is within the geofence radius of (lat,lng);
// returns that zone's ssid/password so the caller can switch from the hotspot back to home Wi-Fi.
bool       sfaFixedApNearGps(SfaStore& store, double lat, double lng, String& ssidOut, String& passOut);
// True if a FIXED_AP (home) zone is visible in a fresh scan; returns its ssid/password.
// Strongest visible FIXED_AP (home) zone; rssiOut (optional) receives its scan RSSI so the caller
// can compare it against the current link instead of switching on mere visibility.
bool       sfaFixedApInScan(SfaStore& store, String& ssidOut, String& passOut, int* rssiOut = nullptr);
// Connect to the first in-range home zone, else the hotspot. Returns a status line.
String     sfaRequestConnection(SfaStore& store);

// Name matching shared with telemetry (strips _5G/_2G suffixes).
bool       sfaNamesMatch(const String& a, const String& b);
String     sfaCleanSsid(const String& v);
String     sfaCleanBssid(const String& v);
