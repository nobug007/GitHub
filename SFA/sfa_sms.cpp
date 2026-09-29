// A7670E modem driver for the LILYGO T-A7670E-S3, built on the LilyGO TinyGSM fork.
// Mirrors the reference sketches in C:\Arduino\Source\SFA (lilogo_SMS / HttpsClient / GPS_test):
// PWRKEY power-on, SIM check, network-mode AUTO, KT APN, registration wait, PDP activation,
// modem.sendSMS / modem.getGPS, and the fork's https_* helpers for HTTPS over LTE.
#include "sfa_config.h"          // includes utilities.h (board pins + TINY_GSM_MODEM_A7670)

#define TINY_GSM_RX_BUFFER 1024
#include <TinyGsmClient.h>
#include "sfa_sms.h"

#ifndef MODEM_POWERON_PULSE_WIDTH_MS
#define MODEM_POWERON_PULSE_WIDTH_MS SFA_MODEM_POWERON_PULSE_MS
#endif

static TinyGsm  modem(SerialAT);
static bool     gModemUp    = false;      // powered on + registered right now
static String   gLastDetail = "modem idle (off)";

static void modemPowerOn() {
  pinMode(MODEM_DTR_PIN, OUTPUT);
  digitalWrite(MODEM_DTR_PIN, LOW);                 // keep modem awake
  pinMode(BOARD_PWRKEY_PIN, OUTPUT);
  digitalWrite(BOARD_PWRKEY_PIN, LOW);  delay(100);
  digitalWrite(BOARD_PWRKEY_PIN, HIGH); delay(MODEM_POWERON_PULSE_WIDTH_MS);
  digitalWrite(BOARD_PWRKEY_PIN, LOW);
  SerialAT.begin(MODEM_BAUDRATE, SERIAL_8N1, MODEM_RX_PIN, MODEM_TX_PIN);
}

void sfaSmsBegin() {
  // LILYGO T-A7670E-S3: the board/modem power rail is gated by BOARD_POWERON_PIN. It MUST be driven
  // HIGH (and kept high) or the A7670E runs under-powered and browns out the ESP32 the moment it
  // draws current to register/transmit — which is exactly the "device dies right after the boot
  // SMS" symptom. Assert it once here and leave it on (the modem is still logically powered off via
  // PWRKEY until we actually need SMS).
#ifdef BOARD_POWERON_PIN
  pinMode(BOARD_POWERON_PIN, OUTPUT);
  digitalWrite(BOARD_POWERON_PIN, HIGH);
#endif
#ifdef MODEM_RESET_PIN
  // Release the modem from hardware reset (inactive = opposite of the board's reset-active level).
  pinMode(MODEM_RESET_PIN, OUTPUT);
  digitalWrite(MODEM_RESET_PIN, !MODEM_RESET_LEVEL);
#endif
  delay(100);   // let the power rail settle before touching PWRKEY
  pinMode(BOARD_PWRKEY_PIN, OUTPUT); digitalWrite(BOARD_PWRKEY_PIN, LOW);
  pinMode(MODEM_DTR_PIN, OUTPUT);   digitalWrite(MODEM_DTR_PIN, LOW);
  // Bring the modem fully up ONCE here — power, network registration, GNSS and the LTE data context
  // — and never tear any of it down again. Every previous scheme that toggled some part of the modem
  // at runtime (power-cycling for each SMS, releasing the PDP context when Wi-Fi returned) caused
  // trouble: inrush at power-on browned out the ESP32, and NETCLOSE/NETOPEN churn disturbed a modem
  // that was also serving GNSS. Doing it all at boot, while idle and on mains power, keeps the
  // current spike away from the moment it matters and leaves a stable modem for the whole session.
  Serial.println("[MODEM] boot bring-up (stays on for the whole session)...");
  if (sfaModemBeginSms()) {
    sfaModemGnssPowerOn();   // GNSS on once
    sfaModemDataEnable();    // LTE data up once; postJson() can fall back to it at any time
    sfaModemAgpsPrime();     // needs the PDP context, so it goes after data is up
  }
}

bool sfaModemBeginSms() {
  if (gModemUp) return true;
  Serial.println("[MODEM] powering on for SMS...");
  modemPowerOn();
  delay(SFA_MODEM_BOOT_WAIT_MS);
  int retry = 0;
  while (!modem.testAT(1000)) { if (retry++ > 30) { gLastDetail = "no modem (AT timeout)"; return false; } }
  modem.getSimStatus();
  modem.setNetworkMode(MODEM_NETWORK_AUTO);
  modem.setNetworkAPN(SFA_LTE_APN);
  // Wait until the modem is fully registered before sending (req 5: "충분히 활성화된 다음에").
  uint32_t t0 = millis();
  RegStatus st = REG_NO_RESULT;
  while (millis() - t0 < SFA_MODEM_REG_TIMEOUT_MS) {
    st = modem.getRegistrationStatus();
    if (st == REG_OK_HOME || st == REG_OK_ROAMING) break;
    if (st == REG_DENIED) break;
    delay(1000);
  }
  gModemUp = (st == REG_OK_HOME || st == REG_OK_ROAMING);
  gLastDetail = gModemUp ? "modem up (registered)" : "modem on but NOT registered";
  Serial.print("[MODEM] "); Serial.println(gLastDetail);
  return gModemUp;
}

void sfaModemEndSms() {
  // Stability first (per request): do NOT power the modem down between SMS. Repeatedly power-cycling
  // the A7670E is what browns out / hangs the board. Once the modem is up we leave it registered so
  // the next SMS reuses it (sfaModemBeginSms returns immediately). Revisit power-saving later.
  // Previously: modem.poweroff(); SerialAT.end(); gModemUp = false;
}

bool sfaSmsAvailable()   { return gModemUp; }
String sfaSmsLastDetail(){ return gLastDetail; }

// ---------------- LTE data (PDP) — the away transport when Wi-Fi is unreachable ----------------
// Brought up once at boot and left up. postJson() only uses it when Wi-Fi is down, so an idle PDP
// context costs nothing, while tearing it down and back up churned the modem for no benefit.
static bool gDataReady = false;

bool sfaModemDataEnable() {
  if (!gModemUp) return false;
  if (gDataReady) return true;
  // setNetworkActive() sets the APN, brings up IPv4 and issues +NETOPEN. If it reports failure the
  // context may still be open from a previous attempt, so confirm with +NETOPEN? before giving up.
  if (!modem.setNetworkActive(SFA_LTE_APN) && !modem.getNetworkActive()) {
    gLastDetail = "LTE data activation failed";
    Serial.println("[MODEM] LTE data activation failed");
    return false;
  }
  gDataReady = modem.getNetworkActive();
  gLastDetail = gDataReady ? "LTE data active" : "LTE data NOT active";
  Serial.print("[MODEM] "); Serial.println(gLastDetail);
  return gDataReady;
}

void sfaModemDataDisable() {
  // Intentionally does nothing. The modem — power, registration, GNSS and the PDP context — is set
  // up once at boot and never torn down; releasing the context when Wi-Fi returned only churned a
  // modem that is also serving GNSS. Kept as a no-op so callers need no #ifdef.
}

bool sfaModemDataReady() { return gModemUp && gDataReady; }

// Cellular RSSI in dBm, or 0 when unknown. Telemetry reports the signal of the link it is actually
// using: Wi-Fi RSSI while on Wi-Fi, this value once the transport has fallen back to LTE.
int sfaModemSignalDbm() {
  if (!gModemUp) return 0;
  int16_t csq = modem.getSignalQuality();     // 0..31, 99 = unknown
  if (csq <= 0 || csq >= 99) return 0;
  return -113 + (2 * csq);                    // 3GPP TS 27.007 CSQ → dBm
}

// ---------------- SMS (ASCII via TinyGSM; Korean via UCS2 AT) ----------------
static bool isAscii(const String& s) { for (size_t i = 0; i < s.length(); i++) if ((uint8_t)s[i] > 0x7F) return false; return true; }
static String digitsPlus(const String& s) {
  String o; for (size_t i = 0; i < s.length(); i++) { char c = s[i]; if (isdigit((int)c)) o += c; else if (c == '+' && o.isEmpty()) o += c; }
  return o;
}
static String toUcs2Hex(const String& utf8) {
  String out; const uint8_t* b = (const uint8_t*)utf8.c_str(); size_t n = utf8.length(); char h[5];
  for (size_t i = 0; i < n;) {
    uint32_t cp; uint8_t c = b[i];
    if (c < 0x80) { cp = c; i += 1; }
    else if ((c & 0xE0) == 0xC0 && i + 1 < n) { cp = ((c & 0x1F) << 6) | (b[i+1] & 0x3F); i += 2; }
    else if ((c & 0xF0) == 0xE0 && i + 2 < n) { cp = ((c & 0x0F) << 12) | ((b[i+1] & 0x3F) << 6) | (b[i+2] & 0x3F); i += 3; }
    else { cp = 0xFFFD; i += 1; }
    if (cp > 0xFFFF) cp = 0xFFFD;
    snprintf(h, sizeof(h), "%04X", (unsigned)cp); out += h;
  }
  return out;
}

bool sfaSmsSend(const String& number, const String& utf8Text) {
  if (!gModemUp) { gLastDetail = "modem not up (call sfaModemBeginSms first)"; return false; }
  String num = digitsPlus(number);
  if (isAscii(utf8Text)) {
    // Plain GSM-7 SMS through the fork.
    modem.sendAT("+CMGF=1"); modem.waitResponse();
    modem.sendAT("+CSCS=\"GSM\""); modem.waitResponse();
    bool ok = modem.sendSMS(num, utf8Text);
    gLastDetail = ok ? ("sent to " + num) : "send failed";
    return ok;
  }
  // Korean → UCS2 via raw AT on the modem's UART.
  modem.sendAT("+CMGF=1"); modem.waitResponse();
  modem.sendAT("+CSCS=\"UCS2\""); modem.waitResponse();
  modem.sendAT("+CSMP=17,167,0,8"); modem.waitResponse();
  SerialAT.print("AT+CMGS=\""); SerialAT.print(toUcs2Hex(num)); SerialAT.print("\"\r");
  if (modem.waitResponse(5000, ">") != 1) { gLastDetail = "no '>' prompt"; return false; }
  SerialAT.print(toUcs2Hex(utf8Text));
  SerialAT.write(0x1A);                       // Ctrl-Z
  bool ok = (modem.waitResponse(60000, "+CMGS") == 1);
  modem.waitResponse(3000);                   // trailing OK
  gLastDetail = ok ? ("sent to " + num) : "send failed (ucs2)";
  return ok;
}

// ---------------- GNSS (A7670E built-in, as in the working 2026-08-08 build) ----------------
// These were stubbed out to no-ops while GPS was being sourced from SFC. With SFA_GPS_FROM_MODEM=1
// that made sfaGpsRead() always return hasFix=false, which in turn made effectiveWifiStatus() hold
// the safe zone forever — the device never went "away" and so never looked for the hotspot.
static bool     gGnssOn   = false;
static bool     gGnssSeen = false;
static uint32_t gGnssOnAt = 0;   // when the receiver was confirmed powered — a cold fix takes minutes

static uint32_t gGnssLastTryAt = 0;

// Keep the GNSS receiver powered for the whole session. It is a separate subsystem inside the
// A7670E — the LTE side can be registered and sending SMS while GNSS is still off, which is exactly
// what was observed (SMS fine, zero coordinates). So: retry until it comes up, never switch it off,
// and when a retry fails print what the modem actually replied instead of a bare "failed".
// An active (amplified) GNSS antenna draws its power from the module's AUX rail. If that rail stays
// off, the receiver is powered but deaf — exactly the "visible=0 used=0" we are seeing. Turning it on
// is harmless with a passive antenna, so it is unconditional. Not all firmware builds implement
// CVAUXS, hence the reply is only logged.
// The active GPS antenna on this board is fed from a MODEM GPIO, not an ESP32 pin — utilities.h
// publishes it as MODEM_GPS_ENABLE_GPIO for LILYGO_A7670X_S3_STAN. The fork's enableGPS(pin, level)
// is what drives it: AT+CGDRT=<pin>,1 makes the modem GPIO an output, AT+CGSETV=<pin>,<level> turns
// the antenna on. Both are skipped when the pin argument is -1.
//
// That was the bug. The old code called enableGPS() with no arguments, so the receiver was powered
// while its antenna never was: the modem cheerfully answered "+CGNSSPWR: 1,0,1" and +CGNSSINFO
// stayed empty for 15 minutes straight, with zero satellites ever heard. AT+CVAUXS=1, which the old
// code sent instead, answers OK on this firmware but is not the antenna control for this board.
static void gnssAntennaPowerOn() {
  Serial.printf("[GNSS] antenna power via modem GPIO %d -> level %d\n",
                (int)MODEM_GPS_ENABLE_GPIO, (int)MODEM_GPS_ENABLE_LEVEL);
  Serial.println("[GNSS]   CGDRT: " + sfaModemAtRaw(String("+CGDRT=") + MODEM_GPS_ENABLE_GPIO + ",1", 3000));
  Serial.println("[GNSS]   CGSETV: " + sfaModemAtRaw(
      String("+CGSETV=") + MODEM_GPS_ENABLE_GPIO + "," + MODEM_GPS_ENABLE_LEVEL, 3000));
}

// Not every A7670 carries a GNSS receiver. LilyGO's own reference sketch refuses to continue on the
// -LNXY/-LASE/-LNMV variants for exactly that reason, so log the model: if this board is one of
// those, no amount of firmware work will ever produce a fix and we should know that immediately.
static void gnssReportModemModel() {
  String name = modem.getModemName();
  Serial.println("[GNSS] modem model: " + name);
  if (name.indexOf("LNXY") >= 0 || name.indexOf("LNMV") >= 0 || name.indexOf("LASE") >= 0 ||
      name.indexOf("LASC") >= 0 || name.indexOf("LLSE") >= 0 || name.indexOf("LABE") >= 0) {
    Serial.println("[GNSS] *** this variant has NO built-in GNSS — a fix is impossible on this hardware ***");
  }
}

// The modem's own view of its GNSS power: 1 = on, 0 = off, -1 = could not tell.
// The reply looks like "+CGNSSPWR: 1,0,1"; only the FIRST field is the power state and only that
// field is interpreted here — the rest is logged raw rather than guessed at.
static int gnssPowerState(String* rawOut) {
  String reply;
  modem.sendAT(GF("+CGNSSPWR?"));
  int r = modem.waitResponse(3000UL, reply);
  reply.trim(); reply.replace("\r\n", " | ");
  if (rawOut) *rawOut = reply;
  if (r != 1) return -1;
  int at = reply.indexOf("+CGNSSPWR:");
  if (at < 0) return -1;
  int i = at + 10;
  while (i < (int)reply.length() && reply[i] == ' ') i++;
  if (i >= (int)reply.length()) return -1;
  if (reply[i] == '1') return 1;
  if (reply[i] == '0') return 0;
  return -1;
}

// Common tail for every path that establishes GNSS power: start the acquisition clock, bring up the
// antenna rail, and dump the receiver's constellation config once. Which constellations are enabled
// decides how many satellites can ever be tracked, so it is worth seeing rather than assuming.
static void gnssMarkOn(const String& how) {
  gGnssOn = true;
  gGnssOnAt = millis();
  Serial.println(String("[GNSS] ON (") + how + ")");
  // LilyGO's working reference sets the GNSS port baud right after enabling. Keep the same order.
  modem.setGPSBaud(115200);
  String raw;
  modem.sendAT(GF("+CGNSSMODE?"));
  modem.waitResponse(3000UL, raw);
  raw.trim(); raw.replace("\r\n", " | ");
  Serial.print("[GNSS] constellations: "); Serial.println(raw);
}

void sfaModemGnssPowerOn() {
  if (gGnssOn || !gModemUp) return;
  if (gGnssLastTryAt != 0 && millis() - gGnssLastTryAt < SFA_GNSS_RETRY_MS) return;  // pace retries
  gGnssLastTryAt = millis();

  String raw;
  gnssReportModemModel();

  // The antenna rail goes on FIRST and unconditionally. It is a separate thing from the receiver's
  // power state, so the "already powered" shortcut below must never be allowed to skip it — that is
  // precisely how the board ended up with a running receiver and a dead antenna.
  gnssAntennaPowerOn();

  // 1) The fork's helper, given this board's antenna pin so it drives CGDRT/CGSETV itself as well.
  if (modem.enableGPS(MODEM_GPS_ENABLE_GPIO, MODEM_GPS_ENABLE_LEVEL)) {
    gnssMarkOn("enableGPS with antenna pin");
    return;
  }
  // 2) Only the ESP32 reboots — the modem keeps running across a reset, so the receiver is often
  // ALREADY powered. AT+CGNSSPWR=1 then just answers OK: the "+CGNSSPWR: READY!" URC fires on an
  // off->on transition and never comes again, so its absence is not a failure (seen 2026-08-15,
  // "+CGNSSPWR: 1,0,1" for a whole session).
  if (gnssPowerState(&raw) == 1) { gnssMarkOn("already powered — " + raw); return; }
  // 2) Straight AT, in case the helper's pin handling or its wait is what fails on this board.
  modem.sendAT(GF("+CGNSSPWR=1"));
  if (modem.waitResponse(20000UL, GF("+CGNSSPWR: READY!")) == 1) {
    modem.waitResponse(2000);            // swallow the trailing OK
    gnssMarkOn("AT+CGNSSPWR=1");
    return;
  }
  // 3) The URC did not arrive — but it may simply have been missed. Re-read the state and believe
  // the modem over the URC.
  if (gnssPowerState(&raw) == 1) { gnssMarkOn("no READY! URC, but state reads on — " + raw); return; }
  Serial.print("[GNSS] still OFF — modem says: "); Serial.println(raw);
}

bool sfaModemGnssIsOn() { return gGnssOn; }

static uint32_t  gGnssLastPollAt = 0;
static bool      gGnssLastOk = false;
static GpsStatus gGnssLastResult;

bool sfaModemGnssRead(GpsStatus& out) {
  if (!gModemUp) return false;
  if (!gGnssOn) sfaModemGnssPowerOn();
  if (!gGnssOn) return false;

  // Rate-limit the receiver. Every getGPS() is a blocking AT round-trip on the modem UART, and this
  // function is reached several times per tick (zone decision, snapshot, risk coords, diagnostics).
  // Polling it on every call stalled the main loop — the gyro sample spacing stretched from 60 s to
  // 16 min. A cold GNSS fix takes minutes anyway, so asking once every SFA_GNSS_POLL_MS is plenty;
  // in between we hand back the cached answer.
  if (gGnssLastPollAt != 0 && millis() - gGnssLastPollAt < SFA_GNSS_POLL_MS) {
    if (gGnssLastOk) { out = gGnssLastResult; return true; }
    return false;
  }
  gGnssLastPollAt = millis();

  float lat = 0, lng = 0, speed = 0, alt = 0, acc = 0;
  uint8_t status = 0;
  int vsat = 0, usat = 0;
  int year = 0, month = 0, day = 0, hour = 0, minute = 0, second = 0;
  bool got = modem.getGPS(&status, &lat, &lng, &speed, &alt, &vsat, &usat, &acc,
                          &year, &month, &day, &hour, &minute, &second);
  // Report acquisition progress once a minute while there is still no fix.
  //
  // Do NOT read anything into vsat/usat being 0 here: getGPS() parses +CGNSSINFO, and on the A7670E
  // that command returns an ALL-EMPTY line until it has a fix, so the satellite counts are simply
  // unavailable before then — they are not a measurement of what the antenna hears. (An earlier
  // version of this log declared "0 satellites visible -> antenna problem" from exactly that, which
  // was wrong.) What does distinguish the cases is the raw reply: a bare "+CGNSSINFO: ,,,,,,,,,"
  // means searching, while a reply carrying a mode and satellite counts but no position means the
  // receiver is tracking and just needs more time. So print the raw line and let it speak.
  if (!got || (lat == 0 && lng == 0)) {
    static uint32_t lastNoFixLog = 0;
    if (millis() - lastNoFixLog >= 60000) {
      lastNoFixLog = millis();
      Serial.printf("[GNSS] no fix yet, %lus since power-on (status=%u)\n",
                    (unsigned long)((millis() - gGnssOnAt) / 1000), status);
      String raw;
      modem.sendAT(GF("+CGNSSINFO"));
      modem.waitResponse(3000UL, raw);
      raw.trim(); raw.replace("\r\n", " | ");
      Serial.print("[GNSS]   "); Serial.println(raw);

      // Ephemeris goes stale after a few hours, and a device that has been indoors all day starts
      // cold again the moment it steps outside. Re-prime AGPS hourly while there is still no fix so
      // that moment produces a position in seconds. Gated to once an hour because AT+CAGPS can hold
      // the modem UART for a while, and we only pay that cost when we have no position anyway.
      static uint32_t lastAgpsAt = 0;
      if (millis() - lastAgpsAt >= 3600000UL) { lastAgpsAt = millis(); sfaModemAgpsPrime(); }
    }
    return false;
  }
  gGnssSeen = true;
  out.hasFix = true;
  out.latitude = lat;
  out.longitude = lng;
  out.accuracy = acc;
  out.timestampMs = millis();
  gGnssLastOk = true;
  gGnssLastResult = out;
  return true;
}

bool sfaModemGnssSeen() { return gGnssSeen; }

// ---------------- Raw AT helper (shared by the diagnostics below) ----------------
String sfaModemAtRaw(const String& cmd, uint32_t timeoutMs) {
  if (!gModemUp) return "(modem down)";
  String reply;
  modem.sendAT(cmd.c_str());
  int r = modem.waitResponse(timeoutMs, reply);
  reply.trim(); reply.replace("\r\n", " | ");
  if (r != 1 && reply.isEmpty()) return "(no reply)";
  return reply;
}

String sfaModemCellInfo() { return sfaModemAtRaw("+CPSI?", 5000); }

// Raw NMEA satellite survey. +CGNSSINFO reports nothing until the receiver has a fix, so it can
// never distinguish "the antenna hears nothing" from "the antenna hears plenty, it just needs more
// time / more sky". GSV sentences carry satellites-in-view and their SNR whether or not there is a
// fix, which answers that question directly. NMEA is switched on only for the duration of the scan
// because it floods the same UART the rest of the firmware uses for AT commands.
String sfaModemGnssSatelliteScan(uint32_t seconds) {
  if (!gModemUp) return "(modem down)";
  Serial.printf("[SAT] NMEA on, listening %lus...\n", (unsigned long)seconds);
  Serial.flush();
  modem.sendAT(GF("+CGNSSTST=1"));
  if (modem.waitResponse(5000) != 1) {
    modem.sendAT(GF("+CGNSSTST=0")); modem.waitResponse(3000);
    return "AT+CGNSSTST=1 rejected — this firmware cannot stream NMEA";
  }

  int    inView = 0, withSnr = 0, bestSnr = 0;
  int    gsvSeen = 0, lines = 0;
  String line, sample;
  uint32_t start = millis();
  while (millis() - start < seconds * 1000UL) {
    while (SerialAT.available()) {
      char c = (char)SerialAT.read();
      if (c != '\n') { if (line.length() < 200) line += c; continue; }
      line.trim();
      lines++;
      if (line.indexOf("GSV") > 0) {
        gsvSeen++;
        if (sample.isEmpty()) sample = line;
        // $xxGSV,<msgs>,<msg#>,<inView>,<sv>,<elev>,<azim>,<snr>, ...
        int f = 0, from = 0;
        while (from <= (int)line.length()) {
          int comma = line.indexOf(',', from);
          String v = (comma < 0) ? line.substring(from) : line.substring(from, comma);
          if (f == 3) { int n = v.toInt(); if (n > inView) inView = n; }
          if (f >= 7 && (f - 7) % 4 == 0 && v.length()) {       // SNR fields
            int snr = v.toInt();
            if (snr > 0) { withSnr++; if (snr > bestSnr) bestSnr = snr; }
          }
          if (comma < 0) break;
          from = comma + 1; f++;
        }
      }
      line = "";
    }
    delay(10);
  }
  modem.sendAT(GF("+CGNSSTST=0"));
  modem.waitResponse(3000);

  String out = "NMEA lines=" + String(lines) + " GSV=" + String(gsvSeen) +
               " | satellites in view=" + String(inView) +
               ", with signal=" + String(withSnr) + ", best SNR=" + String(bestSnr);
  if (lines == 0)        out += "\n[SAT] verdict: receiver produced NO NMEA at all — GNSS not streaming.";
  else if (inView == 0)  out += "\n[SAT] verdict: 0 satellites heard -> antenna is not working or is on "
                                "the LTE port. More time will NOT help.";
  else if (bestSnr < 20) out += "\n[SAT] verdict: satellites seen but too weak (SNR<20) -> indoors or "
                                "poor antenna placement. Try outdoors.";
  else                   out += "\n[SAT] verdict: good signal present -> a fix should follow; give it time.";
  if (sample.length()) out += "\n[SAT] sample: " + sample;
  return out;
}

// ---------------- AGPS ----------------
// A cold GNSS start has to read the satellites' orbital data off the satellites themselves, which is
// what makes a first fix take minutes. AT+CAGPS fetches that data over LTE instead, so the receiver
// only has to find the satellites, not learn about them. It does NOT help where there is no sky —
// nothing does — but it turns a several-minute wait near a window into a few seconds. Free, and the
// PDP context it needs is already up from boot. Not every firmware build implements the command, so
// the reply is logged rather than acted on.
void sfaModemAgpsPrime() {
  if (!gModemUp) return;
  Serial.println("[GNSS] AT+CAGPS — downloading ephemeris over LTE, up to 30s...");
  Serial.flush();
  String reply = sfaModemAtRaw("+CAGPS", 30000);
  Serial.print("[GNSS] AGPS prime (AT+CAGPS): "); Serial.println(reply);
}

// ---------------- Cell-based location (AT+CLBS) ----------------
// Read raw modem output until a line starting with `until` arrives, or the timeout expires. Needed
// for results the modem delivers as an unsolicited line AFTER its OK — waitResponse() has already
// returned by then, so the line has to be picked up off the port directly.
static String modemCollectLine(const char* until, uint32_t timeoutMs) {
  String buf;
  uint32_t start = millis();
  while (millis() - start < timeoutMs) {
    while (SerialAT.available()) {
      buf += (char)SerialAT.read();
      if (buf.length() > 768) buf.remove(0, 384);   // bound it; only the tail can still match
      int at = buf.indexOf(until);
      if (at >= 0) {
        int eol = buf.indexOf('\n', at);
        if (eol > at) { String line = buf.substring(at, eol); line.trim(); return line; }
      }
    }
    delay(20);
  }
  return "";
}

static bool gLbsSupported = true;   // until the modem answers ERROR, or keeps refusing
static int  gLbsFailures  = 0;
bool sfaModemLbsSupported() { return gLbsSupported; }

static uint32_t  gLbsLastPollAt = 0;
static bool      gLbsLastOk = false;
static GpsStatus gLbsLastResult;

bool sfaModemLbsRead(GpsStatus& out, bool force) {
  if (!gModemUp) return false;
  if (force) { gLbsSupported = true; gLbsFailures = 0; }   // an explicit console request always tries
  if (!gLbsSupported) return false;
  // Rate-limit exactly as the GNSS read is: attachCurrentPosition() is reached several times per
  // tick, and an unguarded 30 s AT round-trip on each of them would stall the main loop.
  if (!force && gLbsLastPollAt != 0 && millis() - gLbsLastPollAt < SFA_LBS_POLL_MS) {
    if (gLbsLastOk) { out = gLbsLastResult; return true; }
    return false;
  }
  gLbsLastPollAt = millis();
  gLbsLastOk = false;

  // AT+CLBS answers in two parts: an immediate OK meaning "request accepted", and then the actual
  // "+CLBS: ..." line once the network has replied — which can be many seconds later. An earlier
  // version treated that bare OK as proof the command did not exist and switched the whole feature
  // off; the modem had in fact accepted it. So: only ERROR means unsupported, and the position line
  // is collected afterwards as the unsolicited result it is.
  Serial.println("[LBS] AT+CLBS=1,1 — asking the network, this can take up to 30s...");
  Serial.flush();
  modem.sendAT(GF("+CLBS=1,1"));
  if (modem.waitResponse(10000UL) != 1) {
    gLbsSupported = false;
    Serial.println("[LBS] AT+CLBS rejected by the modem — disabled for this session");
    return false;
  }
  String reply = modemCollectLine("+CLBS:", 30000);
  int at = reply.indexOf("+CLBS:");
  if (at < 0) {
    // Accepted but nothing came back in time. This is a network/service outcome, not a missing
    // command, so the feature stays enabled and the next call tries again.
    Serial.println("[LBS] no reply from the location service within 30s");
    return false;
  }
  // Format is "+CLBS: <code>,<a>,<b>,<accuracy>" and vendors disagree on whether <a> is latitude or
  // longitude. Rather than guess, sort them by range: a latitude cannot exceed 90, and in Korea the
  // longitude (124-132) always does. Anything that fails both tests is rejected.
  double v[4] = {0, 0, 0, 0};
  int n = 0, i = at + 6;
  while (n < 4 && i < (int)reply.length()) {
    while (i < (int)reply.length() && (reply[i] == ' ' || reply[i] == ',')) i++;
    int s = i;
    while (i < (int)reply.length() && reply[i] != ',' && reply[i] != ' ' && reply[i] != '|') i++;
    if (i > s) v[n++] = reply.substring(s, i).toDouble();
    if (i < (int)reply.length() && reply[i] == '|') break;
  }
  if (n < 3 || v[0] != 0) {          // <code> 0 means success; anything else is an error code
    // The command exists and the network answers, it just will not give us a position (observed:
    // "+CLBS: 10", repeatedly). That is a service-side outcome and retrying every two minutes buys
    // nothing but a stalled modem, so give up after a few tries. A console "lbs" still forces a
    // fresh attempt, which is how it gets re-enabled if the situation changes.
    Serial.print("[LBS] no position: "); Serial.println(reply);
    if (++gLbsFailures >= 3) {
      gLbsSupported = false;
      Serial.println("[LBS] service keeps refusing — disabled for this session (console 'lbs' retries)");
    }
    return false;
  }
  gLbsFailures = 0;
  double a = v[1], b = v[2], acc = (n >= 4 ? v[3] : 0);
  double lat, lng;
  if (fabs(a) <= 90.0 && fabs(b) <= 180.0)      { lat = a; lng = b; }
  else if (fabs(b) <= 90.0 && fabs(a) <= 180.0) { lat = b; lng = a; }
  else { Serial.print("[LBS] implausible coords: "); Serial.println(reply); return false; }
  if (lat == 0 && lng == 0) return false;

  out.hasFix = true;
  out.latitude = lat;
  out.longitude = lng;
  out.accuracy = (acc > 0 ? (float)acc : 2000.0f);   // coarse by nature; say so rather than claim 0
  out.timestampMs = millis();
  gLbsLastOk = true;
  gLbsLastResult = out;
  Serial.printf("[LBS] cell position %.6f,%.6f acc=%.0fm\n", lat, lng, out.accuracy);
  return true;
}

// ---------------- HTTPS over LTE (fork https_* API) ----------------
int sfaModemHttpsRequest(const String& method, const String& url, const String& body, String& respOut) {
  respOut = "";
  if (!gModemUp || !sfaModemDataReady()) return -1;   // LTE HTTPS unused: telemetry is Wi-Fi only
  modem.https_begin();
  if (!modem.https_set_url(url, TINYGSM_SSL_AUTO)) { modem.https_end(); return -2; }
  modem.https_add_header("Content-Type", "application/json");
  modem.https_add_header("Accept", "application/json");
  int code;
  if (method == "POST") code = modem.https_post(body);
  else                  code = modem.https_get();
  respOut = modem.https_body();
  modem.https_end();
  return code;
}
