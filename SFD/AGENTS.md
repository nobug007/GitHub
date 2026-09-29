<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-04 | Updated: 2026-07-04 -->

# SFD

## Purpose
**Primary tracked-device app** for the SafeFinder elder-safety system — not just a telemetry sender, but a full safety state machine: BLE onboarding (peripheral role), then continuous WiFi/BLE/GPS safe-zone detection, gyro-based movement sensing, and a WARNING → EMERGENCY(SOS) escalation path that sends SMS directly to guardians in addition to backend telemetry.

## Key Files
| File | Description |
|------|-------------|
| `build.gradle.kts`, `app/build.gradle.kts`, `settings.gradle.kts` | Gradle build config — `namespace = "com.sf.sfd"`, `minSdk = 26`, `targetSdk`/`compileSdk = 35`, Kotlin/JVM target 17 |
| `gradle.properties`, `local.properties` | Gradle/AGP settings and machine-specific local SDK path |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `app/src/main/java/com/sf/sfd/` | Kotlin sources (see below) |

### Key Kotlin sources
| File | Role |
|------|------|
| `MainActivity.kt` | Two screens only: **setup mode** (shows device name, waits for BLE provisioning, `Device 초기화` reset button) and **operation mode** (shows BLE/GPS status chips, connected phone, WiFi AP info, "Config Sync" button that re-pulls elder/guardian/safe-zone data from the backend). |
| `BlePeripheralManager.kt` | BLE **peripheral/GATT server** role: advertises, exposes `DEVICE_INFO_UUID`/`CONFIG_WRITE_UUID`/`STATUS_UUID` characteristics, receives config in chunks from SFC, then **self-registers** with the backend (`POST /devices/register`) — SFC does not register on SFD's behalf. |
| `SfdTelemetryService.kt` (~650 lines) | **The core safety engine.** Foreground service that: samples gyro/accelerometer every 60s; determines location type (WiFi safe zone with grace period → BLE safe zone via SFC connection → GPS fallback with distance-based stay/moved detection); on safe-zone exit, starts a timer — 5 min unconfirmed → **WARNING** SMS + telemetry, 30 min → **EMERGENCY/SOS** SMS + telemetry (repeating every 60s while in SOS state); reschedules itself via `AlarmManager` on service death/task-removal. |
| `SfdConfig.kt` | BLE UUIDs (identical to SFC/SFW), backend URLs (`REGISTER_URL`, `TELEMETRY_URL`), and all timing constants (warning/emergency delays, GPS cache windows, gyro thresholds). |
| `BlePeripheralManager.kt`, `WifiConnector.kt`, `WifiStatusReader.kt`, `GpsStatusReader.kt` | Sensing/connectivity layer feeding the telemetry service. |
| `TelemetryPayloadFactory.kt` | Builds the telemetry JSON payload (gyro samples, location, safe-zone flag, battery, event type, verb, duration, device status). |
| `SfdApiClient.kt` | `registerDevice`, `sendTelemetry`, plus read-only `getElder`/`getGuardians`/`getSafeZones` (used by "Config Sync" in `MainActivity`). |
| `SfdStore.kt` | Local persistence: device config, gyro sample buffer, BLE safe-zone state, guardian phone numbers (for SMS), log lines. |
| `SfdRestartReceiver.kt` | `BroadcastReceiver` triggered by a scheduled `AlarmManager` alarm to restart `SfdTelemetryService` if it's killed — works together with `scheduleRestart()` in the service itself. |
| `Models.kt` | Shared data classes (gyro samples, location snapshots, WiFi/GPS status, etc.) |

## For AI Agents

### Working In This Directory
- **This is a stateful safety engine, not a simple sender.** Before changing anything in `SfdTelemetryService.kt`, understand the full state machine: safe-zone entered/exited transitions, the warning/emergency timers, and how `consumeZoneVerb`/`verbForLocation` derive the "stayed"/"moved" verb sent to the backend. A partial change can silently break SOS escalation.
- **SMS is sent directly from the device**, not proxied through the backend (`SmsManager.sendTextMessage` in `sendSms()`), to every phone number in `store.guardianPhones()`. This requires the `SEND_SMS` permission — be deliberate about changing when/how often SMS fires (there's already a `SOS_REPEAT_PERIOD_MS` guard against spamming).
- Config arrives via BLE from `SFC` (`BlePeripheralManager.handleConfigWrite`) using the same chunked-transfer protocol (`BEGIN:<size>`/chunks/`END`) that `SFW` reimplements separately — if you change this protocol, `SFC`'s sender and `SFW`'s receiver both need matching updates. Coordinate via `project-manager`.
- `SfdConfig.kt`'s BLE UUIDs and backend URLs are shared constants across the family — do not change them here without checking `SFC` and `SFW`.
- `SfdRestartReceiver.kt` + `AlarmManager` scheduling exists specifically so telemetry survives service death/reboot — be careful not to break auto-restart when touching service lifecycle (`onDestroy`, `onTaskRemoved`).
- `SFD_Test/` is a stripped-down sibling (no BLE peripheral, no WiFi connector, no gyro/SOS logic) — useful as a quicker reference when debugging just the telemetry/API path in isolation, but it does **not** mirror SFD's safety state machine.

### Testing Requirements
- Build/run via Gradle (`./gradlew assembleDebug`) or Android Studio, on a physical device — BLE peripheral/advertising, gyro sensors, and GPS all behave unreliably or not at all on emulators.
- No automated test suite. Manually verify safe-zone transitions and the warning/emergency timers are the highest-value things to check after any change in this area (consider temporarily shortening `WARNING_DELAY_MS`/`EMERGENCY_DELAY_MS` in a debug build to test faster).

### Common Patterns
- Reader classes (`WifiStatusReader`, `GpsStatusReader`) are separated from action classes (`WifiConnector`) — follow this split when adding new sensors/status sources.
- "Effective" status wrappers (`effectiveWifiStatus`, `effectiveGpsStatus`) apply grace-period/caching logic on top of raw readers — don't bypass these when consuming WiFi/GPS status elsewhere in the service.

## Dependencies

### Internal
- Onboarded by `SFC/` over BLE, but registers itself with the backend directly (not proxied by SFC).
- Shares BLE UUIDs and general config/API-client shape with `SFD_Test/` and `SFW/`, but only `SFD` has the full safety state machine.

### External
- Android BLE peripheral APIs, WiFi APIs, location services, `SensorManager` (gyro/accelerometer), `SmsManager`
- Backend: `https://sf-api.ese-lab.com/api/v1/*` (register, telemetry, elder/guardian/safezone reads for Config Sync)
- Kotlin, Android Gradle Plugin

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
