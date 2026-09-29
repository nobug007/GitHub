# SFA — Safe Finder (LILYGO T-A7670E-S3)

Headless safeFinder tracked-device firmware for the **LILYGO T-A7670E-S3** (ESP32-S3 + on-board
**A7670E** LTE Cat-1 / GNSS). Same server protocol, BLE provisioning, safe-zone workflow and SOS
escalation as SFD/SFW, with a dual uplink:

- **At home (safe zone):** Wi-Fi (`WiFi.h` + `WiFiClientSecure` + `HTTPClient`).
- **Away:** **LTE via the A7670E** for both telemetry (HTTPS over LTE) and **SMS** to guardians.
- **Location:** A7670E **built-in GNSS** (`modem.getGPS(...)`).

The Wi-Fi/LTE/SMS/GNSS code follows the reference sketches in `C:\Arduino\Source\SFA`
(`HomeWifi`, `HttpsClient`, `lilogo_SMS`, `GPS_test`) using LILYGO's `utilities.h` pin map.

## ⚠️ Required library — LilyGO TinyGSM **fork**
The modem layer uses the **LilyGO TinyGSM fork** (adds `https_begin/https_set_url/https_get/
https_post/https_body/https_end`, `setNetworkActive`, `getGPS(...)`, `setGPSBaud`, `getNetworkModeString`).
This is the **same library your reference sketches compile against** — the mainline
`vshymanskyy/TinyGSM` does **not** have these methods. Install the LilyGO fork before building.

Because that fork is not installed in this workspace, the project was written but **not compiled
here** — build/flash it in your Arduino IDE (where the reference already compiles).

## Board / build settings (Arduino IDE)
- Board: **ESP32S3 Dev Module** (`esp32:esp32:esp32s3`)
- PSRAM: **OPI PSRAM**
- Partition Scheme: **Huge APP (3MB No OTA)**
- USB CDC On Boot: **Enabled** (for serial logs)
- Active board macro is already set in `utilities.h`: `#define LILYGO_A7670X_S3_STAN`

## Pin map (from utilities.h · LILYGO_A7670X_S3_STAN)
| Signal | GPIO |
|---|---|
| Modem TX / RX | 4 / 5 (`SerialAT` = Serial1) |
| Modem DTR | 7 |
| Modem PWRKEY | 46 |
| Battery ADC | 8 |
| I2C SDA / SCL | 3 / 2 |

## Config to review (`sfa_config.h`)
- `SFA_LTE_APN` — carrier APN (default `lte.ktfwing.com`, KT)
- `SFA_SELFTEST_SMS_TO` — boot self-test SMS target (`010-7260-8813`)
- Server URLs / timings / SOS thresholds — identical to SFD

## Files
| File | Role |
|---|---|
| `SFA.ino` | main state machine (safe/warning/SOS, telemetry, config-sync) |
| `sfa_config.h` | board + APN + server + timings (includes `utilities.h`) |
| `sfa_sms.*` | **A7670E modem (TinyGSM fork): LTE bring-up, SMS, GNSS, HTTPS-over-LTE** |
| `sfa_net.*` | HTTP client — Wi-Fi when home, LTE when away |
| `sfa_sensors.*` | GNSS (via modem), battery, optional IMU |
| `sfa_wifi.*` | Wi-Fi safe-zone detection |
| `sfa_store.*` | NVS config/seq/escalation + server config-sync |
| `sfa_payload.*` | register + telemetry JSON (SFD format) |
| `sfa_ble.*` | BLE GATT provisioning (SFC-compatible) |
| `sfa_util.*` | time (ISO8601 +09:00) + geo |
| `utilities.h` | LILYGO board pin map |

## Boot self-test
On power-up: powers the modem, registers on LTE, enables GNSS, waits up to 90 s for a fix, then
sends a Korean UCS2 test SMS to `SFA_SELFTEST_SMS_TO`. Watch the serial monitor (115200).

## Notes
- Guardian alert SMS uses **UCS2** so Korean text is delivered correctly.
- `sfaSmsBegin()` blocks during LTE registration at boot (per the reference). Fast with good signal.
- Server config-sync (telemetry ACK `configChanged`/`serverConfigVersion`) is included, same as SFD/SFW.
