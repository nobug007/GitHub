// Time (epoch/ISO8601 +09:00) and geo helpers shared across modules.
#pragma once
#include <Arduino.h>

void     sfaTimeBegin();                 // kick off NTP (call after Wi-Fi connects)
bool     sfaTimeValid();                 // true once wall-clock is set (year >= 2021)
uint64_t sfaNowMs();                     // epoch ms when time is valid, else millis() (reboot-safe durations)
String   sfaIso(uint64_t epochMs);       // "2026-07-31T15:23:01+09:00"
String   sfaIsoNow();
double   sfaDistanceMeters(double fromLat, double fromLng, double toLat, double toLng);
double   sfaRound2(double v);
