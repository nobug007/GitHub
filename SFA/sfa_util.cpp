#include "sfa_util.h"
#include "sfa_config.h"
#include <time.h>
#include <math.h>

void sfaTimeBegin() {
  configTime(SFA_TZ_OFFSET_SEC, 0, SFA_NTP_SERVER1, SFA_NTP_SERVER2);
}

bool sfaTimeValid() {
  time_t now = time(nullptr);
  return now > 1609459200; // 2021-01-01
}

uint64_t sfaNowMs() {
  if (sfaTimeValid()) {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return (uint64_t)tv.tv_sec * 1000ULL + (tv.tv_usec / 1000ULL);
  }
  return (uint64_t)millis();
}

String sfaIso(uint64_t epochMs) {
  time_t secs = (time_t)(epochMs / 1000ULL);
  struct tm tmv;
  localtime_r(&secs, &tmv);           // configTime applied the KST offset already
  char buf[40];
  strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%S+09:00", &tmv);
  return String(buf);
}

String sfaIsoNow() { return sfaIso(sfaNowMs()); }

double sfaDistanceMeters(double fromLat, double fromLng, double toLat, double toLng) {
  const double R = 6371000.0;
  double dLat = (toLat - fromLat) * M_PI / 180.0;
  double dLng = (toLng - fromLng) * M_PI / 180.0;
  double a = sin(dLat / 2) * sin(dLat / 2) +
             cos(fromLat * M_PI / 180.0) * cos(toLat * M_PI / 180.0) *
             sin(dLng / 2) * sin(dLng / 2);
  return R * 2 * atan2(sqrt(a), sqrt(1 - a));
}

double sfaRound2(double v) { return round(v * 100.0) / 100.0; }
