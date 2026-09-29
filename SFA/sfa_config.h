// SFA — Safe Finder Arduino, LILYGO T-A7670E-S3 (ESP32-S3 + on-board A7670E LTE/GNSS).
// Same server protocol / BLE provisioning / safe-zone workflow / SOS escalation as SFD, adapted
// for the LILYGO board: Wi-Fi at home (safe zone), LTE (TinyGSM) when away for telemetry + SMS,
// and the A7670E built-in GNSS for location. Board pins come from LILYGO's utilities.h.
//
// LIBRARY NOTE: the modem uses the *LilyGO TinyGSM fork* (adds https_begin/https_get/https_body,
// setNetworkActive, getGPS(...), setGPSBaud, etc.) — the same one the reference sketches in
// C:\Arduino\Source\SFA use. Install that fork (not the mainline vshymanskyy/TinyGSM).
#pragma once
#include <Arduino.h>
#include "utilities.h"      // LILYGO_A7670X_S3_STAN board pins + TINY_GSM_MODEM_A7670

// ===== Server (identical to SFD) =====
#define SFA_API_BASE       "https://sf-api.ese-lab.com/api/v1"
#define SFA_REGISTER_URL   SFA_API_BASE "/devices/register"
#define SFA_TELEMETRY_URL  SFA_API_BASE "/telemetry"
// Firmware/app version reported in every telemetry + register payload. Starts at 0.9.0 and is
// bumped on each significant update (SFC/SFD/SFW/SFA share the same versioning line).
#define SFA_FW_VERSION     "0.9.0"
#define SFA_DEFAULT_DEVICE_ID "SF-000011"
#define SFA_BLE_DEVICE_NAME   "Safe Finder 0.1"

// ===== BLE GATT UUIDs — identical to SFD/SFW so SFC provisions SFA unchanged =====
#define SFA_SERVICE_UUID       "7d9f0001-4f5d-4a6e-8d6a-534644544553"
#define SFA_DEVICE_INFO_UUID   "7d9f0002-4f5d-4a6e-8d6a-534644544553"
#define SFA_CONFIG_WRITE_UUID  "7d9f0003-4f5d-4a6e-8d6a-534644544553"
#define SFA_STATUS_UUID        "7d9f0004-4f5d-4a6e-8d6a-534644544553"

// ============================================================================
// 데모(촬영) 모드 — 임시. 촬영이 끝나면 0 으로 되돌릴 것.
//
// 1 이면 시연 영상용으로 타이밍이 압축된다: 이탈 확정 약 10~20초, WARNING 1분, SOS 2분.
// 운영값으로는 촬영이 불가능하다. 이유는 경보 단계(5분/30분)만이 아니라 이탈 판정 자체가 늦기
// 때문이다 — 이 보드에는 IMU가 없어 movedSinceWifiLoss 가 항상 false 이고, 그래서 "정지 상태
// 홀드"(SFA_WIFI_STATIONARY_HOLD_MAX_MS, 5분)가 매번 끝까지 걸린다. 아래 네 가지를 함께 줄여야
// 실제로 1분/2분에 화면이 바뀐다:
//   · 연결 끊김 유예(SAFEZONE_GRACE)      — 끊긴 직후의 유예
//   · 정지 상태 홀드(STATIONARY_HOLD)     — IMU 없는 이 보드에서 항상 적용되는 5분
//   · 스캔 캐시(WIFI_SCAN_FRESH)          — 꺼진 핫스팟이 캐시에 남아 "아직 근처"로 판정되는 것
//   · 경보 단계(WARNING / EMERGENCY)      — 5분 / 30분
//
// 원복: SFA_DEMO_MODE 를 0 으로 바꾸고 다시 컴파일·플래시하면 전부 운영값으로 돌아온다.
// 다른 곳은 손대지 않았으므로 이 한 줄이 유일한 되돌림 지점이다.
// ============================================================================
#define SFA_DEMO_MODE 0

// ===== Timings (ms) — mirror SfdConfig =====
static const uint32_t SFA_GYRO_SAMPLE_PERIOD_MS         = 60000UL;
static const uint32_t SFA_TELEMETRY_REPORT_PERIOD_MS    = 600000UL;   // 10 min inside a safe zone
static const uint32_t SFA_GPS_TELEMETRY_REPORT_PERIOD_MS= SFA_DEMO_MODE ? 20000UL : 60000UL;
static const uint32_t SFA_CONNECTIVITY_TICK_MS          = 15000UL;
static const uint32_t SFA_WIFI_SAFEZONE_GRACE_MS        = SFA_DEMO_MODE ? 10000UL : 120000UL;
static const uint32_t SFA_STARTUP_SAFE_GRACE_MS         = 90000UL;
// After a reboot the stored state may say "in a safe zone" while the Wi-Fi stack has only just come
// up and has not completed a scan yet. Hold off on declaring a departure (and its SMS) for this long
// so a cold radio is never mistaken for the elder walking out. Short on purpose: a real departure
// still reaches the guardian within seconds.
static const uint32_t SFA_STARTUP_EXIT_HOLD_MS          = SFA_DEMO_MODE ? 5000UL : 20000UL;
static const uint32_t SFA_WIFI_STATIONARY_HOLD_MAX_MS   = SFA_DEMO_MODE ? 10000UL : 300000UL;
static const uint32_t SFA_WIFI_SCAN_FRESH_MS            = SFA_DEMO_MODE ? 15000UL : 300000UL;
static const int      SFA_WIFI_NEARBY_MIN_RSSI          = -85;
static const uint32_t SFA_BLE_SAFEZONE_GRACE_MS         = SFA_DEMO_MODE ? 10000UL : 120000UL;
static const uint32_t SFA_GPS_LOCATION_CACHE_MS         = 300000UL;
static const uint32_t SFA_GPS_MAX_FIX_AGE_MS            = 120000UL;
static const double   SFA_GPS_STAY_DISTANCE_M           = 10.0;
static const double   SFA_GEOFENCE_RADIUS_M             = 150.0;
static const uint32_t SFA_WARNING_DELAY_MS   = SFA_DEMO_MODE ? 60000UL  : 300000UL;   // 5 min -> WARNING
static const uint32_t SFA_EMERGENCY_DELAY_MS = SFA_DEMO_MODE ? 120000UL : 1800000UL;  // 30 min -> SOS
static const uint32_t SFA_SOS_REPEAT_PERIOD_MS          = 60000UL;
static const double   SFA_GYRO_MOVEMENT_THRESHOLD       = 1.0;
static const int      SFA_GYRO_REPORT_SAMPLE_COUNT      = 10;
static const uint32_t SFA_BLE_ADVERTISE_DURATION_MS     = 60000UL;    // 1 min advertise, then retry Wi-Fi
static const uint32_t SFA_CONNECT_GRACE_MS              = 45000UL;
// While disconnected we must SEE the current radio environment, not the one from before the link
// dropped. The normal 5-minute scan cache kept handing back the AP we just lost (home after walking
// out, or the hotspot after it was switched off), so the device retried a dead SSID forever and
// never discovered the one that was actually reachable. Rescan this often while away.
static const uint32_t SFA_AWAY_SCAN_FRESH_MS            = 20000UL;
// Last-resort recovery: if nothing has been joined for this long, tear the Wi-Fi stack down and
// bring it back up. Observed 2026-08-13: after the guardian hotspot was switched off the device
// went silent for 12 h even though home Wi-Fi was in range — the driver kept chasing the SSID that
// had vanished. A full radio restart clears that state so the scan/join cycle can start clean.
static const uint32_t SFA_WIFI_RECOVERY_MS              = 300000UL;
// How much stronger the home AP must be before we abandon a working hotspot link for it. Without
// this margin the two APs traded the device back and forth every few minutes.
static const int      SFA_ROAM_HYSTERESIS_DB            = 8;
// No Wi-Fi at all for this long → bring up LTE data so telemetry (WARNING, then SOS) still reaches
// the server from outside every known network. Matches the WARNING delay: the elder has been off
// all known Wi-Fi for 5 minutes, which is exactly when the guardian needs to hear about it.
static const uint32_t SFA_WIFI_LTE_FALLBACK_MS          = 300000UL;

// Telemetry seq base (preserved across resets so the server never rejects as duplicate).
static const uint32_t SFA_SEQ_BASE = 10500;

// ===== Guardian hotspot (away Wi-Fi fallback; LTE is the primary away link) =====
#define SFA_HOTSPOT_SSID     "Nobug"
// Fallback password used only if the provisioning config carried none for the hotspot zone.
// The password saved with the "내 폰" safe zone takes priority (see sfaConnectToHotspot).
#define SFA_HOTSPOT_PASSWORD "bang8813"

// Home router (FIXED_AP) fallback password. The server /config strips zone passwords, so a home
// zone synced from the server has none, and a secured home AP (WPA2) then cannot be joined — the
// device is stuck offline whenever the hotspot is off. Set this to the home Wi-Fi password so the
// device can rejoin home when the guardian hotspot turns off. Priority: zone's own stored password
// (from provisioning) > this fallback. Leave "" only if the home AP is open.
#define SFA_HOME_PASSWORD    "bang8813"   // TEMP: nobug_home fallback (replace with real home pw)

// Universal Wi-Fi password fallback. The server strips zone passwords and we cannot write the ESP32
// NVS directly, so ANY zone (home or hotspot) that ends up with no password uses this to join.
// nobug_home and Nobug currently share this password.
#define SFA_DEFAULT_WIFI_PASSWORD "bang8813"

// ============================================================================
// LILYGO T-A7670E-S3 hardware. Pins are supplied by utilities.h (active board:
// LILYGO_A7670X_S3_STAN): MODEM_TX_PIN=4, MODEM_RX_PIN=5, MODEM_DTR_PIN=7,
// BOARD_PWRKEY_PIN=46, BOARD_BAT_ADC_PIN=8, SerialAT=Serial1, GNSS on the modem.
// ============================================================================

// ---- A7670E modem (TinyGSM fork) ----
#define SFA_HAS_GSM        1
#define SFA_GPS_FROM_MODEM 1                    // GNSS comes from the A7670E via modem.getGPS()
#define SFA_HAS_GPS        0                    // no separate NMEA UART GPS
static const uint32_t SFA_MODEM_POWERON_PULSE_MS = 500;   // fallback if utilities.h lacks it
static const uint32_t SFA_MODEM_BOOT_WAIT_MS     = 3000;
static const uint32_t SFA_MODEM_REG_TIMEOUT_MS   = 90000;
// GNSS is a separate subsystem inside the A7670E and can refuse to power up even while the LTE side
// is registered. Keep retrying at this interval until it comes up; once on it is never switched off.
static const uint32_t SFA_GNSS_RETRY_MS          = 30000;
// How often the receiver is actually asked for a position. getGPS() blocks on an AT round-trip and
// is reached several times per tick, so it is polled on this interval and cached in between; a cold
// fix takes minutes regardless, and hammering it was stalling the main loop.
static const uint32_t SFA_GNSS_POLL_MS           = 10000;
// Same treatment for the cell-based fallback, but slower: AT+CLBS is a network round-trip that can
// hold the modem UART for seconds, and a position accurate to hundreds of metres does not change
// meaningfully minute to minute. Asked at most this often; the answer is cached in between.
static const uint32_t SFA_LBS_POLL_MS            = 120000;
// SFC's on-hotspot GPS endpoint (http://<gateway>:8765/gps). While the device is on the guardian's
// hotspot it is physically beside the phone, so the phone's fix IS the device's position — and it is
// a real GPS fix, which is exactly what the A7670E cannot get indoors. Polled this often.
static const uint32_t SFA_PHONE_GPS_POLL_MS      = 60000;
// ...and discarded once this old. The moment the elder walks away from the hotspot, the cached
// position stops being theirs and becomes the guardian's. Reporting that as the elder's location
// would send a searcher to the wrong place, so a stale cache is treated as no position at all.
static const uint32_t SFA_PHONE_GPS_MAX_AGE_MS   = 300000;
// KT LTE APN (from the reference). Change for your carrier.
#define SFA_LTE_APN        "lte.ktfwing.com"

// ---- IMU (optional MPU6050 over I2C; utilities.h I2C = SDA 3 / SCL 2) ----
// #define SFA_HAS_IMU 1
static const int SFA_IMU_SDA_PIN = BOARD_SDA_PIN;   // 3
static const int SFA_IMU_SCL_PIN = BOARD_SCL_PIN;   // 2

// ---- Battery: on-board divider on BOARD_BAT_ADC_PIN (GPIO8) ----
#define SFA_BATTERY_ADC_PIN  BOARD_BAT_ADC_PIN
static const int SFA_BATTERY_FIXED_LEVEL = 100;
// At or below this level telemetry reports deviceStatus/eventType = LOW_BATTERY (spec enum).
static const int SFA_LOW_BATTERY_PCT = 10;

// ---- Status LED (optional): -1 disables ----
static const int SFA_STATUS_LED_PIN = -1;

// ---- Boot self-test: acquire a GNSS fix, then send a test SMS ----
#define SFA_SELFTEST_ON_BOOT 1
#define SFA_SELFTEST_SMS_TO  "010-7260-8813"
static const uint32_t SFA_SELFTEST_GPS_TIMEOUT_MS = 90000UL;

// ===== NTP (ISO8601 +09:00 sentAt/timestamp) =====
#define SFA_NTP_SERVER1 "pool.ntp.org"
#define SFA_NTP_SERVER2 "time.google.com"
static const long SFA_TZ_OFFSET_SEC = 9 * 3600;   // KST
