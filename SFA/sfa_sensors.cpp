#include "sfa_sensors.h"
#include "sfa_config.h"
#include "sfa_util.h"
#if SFA_GPS_FROM_MODEM
#include "sfa_sms.h"
#endif

// ---------------- GPS (UART NMEA, self-contained parser: RMC + GGA) ----------------
#if SFA_HAS_GPS
static HardwareSerial GpsSerial(SFA_GPS_UART);
#endif
static bool     gGpsBytesSeen = false;
static bool     gHasFix = false;
static double   gLat = 0, gLng = 0;
static float    gAcc = 0;
static uint32_t gFixAtMs = 0;
static char     gLine[100];
static uint8_t  gLen = 0;

static double nmeaToDegrees(const String& v, const String& hemi) {
  if (v.length() < 3) return NAN;
  int dot = v.indexOf('.');
  int degLen = (dot >= 4) ? (dot - 2) : 2;      // lat=dd, lng=ddd
  double deg = v.substring(0, degLen).toDouble();
  double min = v.substring(degLen).toDouble();
  double d = deg + min / 60.0;
  if (hemi == "S" || hemi == "W") d = -d;
  return d;
}

static String field(const String& s, int idx) {
  int start = 0, count = 0;
  while (count < idx) { start = s.indexOf(',', start); if (start < 0) return ""; start++; count++; }
  int end = s.indexOf(',', start);
  return (end < 0) ? s.substring(start) : s.substring(start, end);
}

static void parseSentence(const String& s) {
  if (s.length() < 6) return;
  String type = s.substring(3, 6);   // skip "$GP"/"$GN"
  if (type == "RMC") {
    String status = field(s, 2);
    String lat = field(s, 3), ns = field(s, 4), lng = field(s, 5), ew = field(s, 6);
    if (status == "A" && lat.length() && lng.length()) {
      double la = nmeaToDegrees(lat, ns), lo = nmeaToDegrees(lng, ew);
      if (!isnan(la) && !isnan(lo)) { gLat = la; gLng = lo; gHasFix = true; gFixAtMs = millis(); }
    }
  } else if (type == "GGA") {
    String hdop = field(s, 8);
    if (hdop.length()) gAcc = hdop.toFloat() * 5.0f;   // rough horizontal accuracy in metres
  }
}

void sfaGpsBegin() {
#if SFA_HAS_GPS
  GpsSerial.begin(SFA_GPS_BAUD, SERIAL_8N1, SFA_GPS_RX_PIN, SFA_GPS_TX_PIN);
#endif
}

void sfaGpsPoll() {
#if SFA_HAS_GPS
  while (GpsSerial.available()) {
    char c = (char)GpsSerial.read();
    gGpsBytesSeen = true;
    if (c == '\n' || c == '\r') {
      if (gLen > 0) { gLine[gLen] = 0; if (gLine[0] == '$') parseSentence(String(gLine)); gLen = 0; }
    } else if (gLen < sizeof(gLine) - 1) {
      gLine[gLen++] = c;
    } else {
      gLen = 0;  // overflow, resync
    }
  }
#endif
}

GpsStatus sfaGpsRead() {
  GpsStatus st;
#if SFA_GPS_FROM_MODEM
  sfaModemGnssRead(st);          // A7670E built-in GNSS over the modem UART
  return st;
#else
  if (gHasFix && (millis() - gFixAtMs) <= SFA_GPS_MAX_FIX_AGE_MS) {
    st.hasFix = true; st.latitude = gLat; st.longitude = gLng;
    st.accuracy = gAcc; st.timestampMs = gFixAtMs;
  }
  return st;
#endif
}

bool sfaGpsHardwarePresent() {
#if SFA_GPS_FROM_MODEM
  return sfaModemGnssSeen();
#else
  return gGpsBytesSeen;
#endif
}

// ---------------- IMU (optional MPU6050) ----------------
#if SFA_HAS_IMU
#include <Wire.h>
static bool gImuOk = false;
static const uint8_t MPU_ADDR = 0x68;
#endif

void sfaImuBegin() {
#if SFA_HAS_IMU
  Wire.begin(SFA_IMU_SDA_PIN, SFA_IMU_SCL_PIN);
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x6B); Wire.write(0x00);            // wake up
  gImuOk = (Wire.endTransmission() == 0);
#endif
}

double sfaImuMovementMagnitude() {
#if SFA_HAS_IMU
  if (!gImuOk) return 0.0;
  Wire.beginTransmission(MPU_ADDR); Wire.write(0x43);           // GYRO_XOUT_H
  if (Wire.endTransmission(false) != 0) return 0.0;
  if (Wire.requestFrom((int)MPU_ADDR, 6) != 6) return 0.0;
  int16_t gx = (Wire.read() << 8) | Wire.read();
  int16_t gy = (Wire.read() << 8) | Wire.read();
  int16_t gz = (Wire.read() << 8) | Wire.read();
  double s = 131.0;   // LSB/(deg/s) at +/-250 dps
  return (fabs(gx) + fabs(gy) + fabs(gz)) / s;
#else
  return 0.0;   // no IMU => movement 0 => "stayed" (conservative, matches SFD when sensor absent)
#endif
}

// ---------------- Battery ----------------
void sfaBatteryBegin() {}

int sfaBatteryLevel() {
#ifdef SFA_BATTERY_ADC_PIN
  int mv = analogReadMilliVolts(SFA_BATTERY_ADC_PIN) * 2;   // assume 1:1 divider
  int pct = (int)(((double)mv - 3300.0) / (4200.0 - 3300.0) * 100.0);
  if (pct < 0) pct = 0; if (pct > 100) pct = 100;
  return pct;
#else
  return SFA_BATTERY_FIXED_LEVEL;
#endif
}
