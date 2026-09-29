// A7670E modem (LILYGO T-A7670E-S3) via the LilyGO TinyGSM fork.
// Provides: LTE bring-up (power-on/SIM/APN/registration/PDP), SMS (incl. Korean UCS2),
// A7670E built-in GNSS, and HTTPS over LTE (used when Wi-Fi is unavailable / away).
#pragma once
#include <Arduino.h>
#include "sfa_types.h"

// ---- modem lifecycle (OFF by default; powered ON only to send SMS to save battery) ----
void   sfaSmsBegin();        // init pins + power the modem ON for the session (call once in setup)
bool   sfaModemBeginSms();   // ensure powered on + registered; true when ready to send (no-op if up)
void   sfaModemEndSms();     // no-op: the modem is deliberately left powered on (see sfa_sms.cpp)
bool   sfaSmsAvailable();    // modem currently powered + registered
// LTE data (PDP). Off by default; enabled only once Wi-Fi has been unusable for a while, so the
// device can still report telemetry (WARNING/SOS) from outside any known Wi-Fi.
bool   sfaModemDataEnable();
void   sfaModemDataDisable();
bool   sfaModemDataReady();  // true when the modem is registered AND the PDP context is active
int    sfaModemSignalDbm();  // cellular RSSI (dBm) for telemetry while on LTE; 0 when unknown
String sfaSmsLastDetail();

// ---- SMS (call sfaModemBeginSms() first, sfaModemEndSms() when the batch is done) ----
bool   sfaSmsSend(const String& number, const String& utf8Text);

// ---- GNSS (A7670E built-in receiver) ----
void   sfaModemGnssPowerOn();
bool   sfaModemGnssRead(GpsStatus& out);
bool   sfaModemGnssSeen();
bool   sfaModemGnssIsOn();   // GNSS receiver currently powered

// ---- Network-assisted positioning (no API key, no cost) ----
// Download satellite ephemeris over LTE so a cold start takes seconds instead of minutes. Harmless
// if the firmware does not implement it; the reply is logged either way.
void   sfaModemAgpsPrime();
// Coarse position from the modem's own cell-based location service (AT+CLBS). Accuracy is hundreds
// of metres to kilometres — a last resort for when GNSS has no sky. Self-disables permanently the
// first time the modem shows the command is unsupported, so it costs nothing when unavailable.
// `force` skips the poll interval and asks the network right now — for the serial console, where the
// point is to watch a real attempt happen rather than be handed a cached answer.
bool   sfaModemLbsRead(GpsStatus& out, bool force = false);
bool   sfaModemLbsSupported();   // false once the modem has rejected AT+CLBS

// ---- Diagnostics (serial console) ----
// Serving-cell info (AT+CPSI?) as a single line: operator, band, cell id, RSRP...
String sfaModemCellInfo();
// Turn on raw NMEA output for `seconds`, count the satellites the receiver actually hears, then turn
// it off again. This is the ONLY way to tell "antenna is dead" from "needs a view of the sky":
// +CGNSSINFO stays empty until there is a fix, so it can never answer that question.
String sfaModemGnssSatelliteScan(uint32_t seconds);
// Send an arbitrary AT command and return the modem's raw reply on one line. Read-only debugging
// aid: it is only ever reached from the serial console, never from the state machine.
String sfaModemAtRaw(const String& cmd, uint32_t timeoutMs = 5000);

int    sfaModemHttpsRequest(const String& method, const String& url,
                            const String& body, String& respOut);
