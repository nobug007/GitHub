<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-04 | Updated: 2026-07-04 -->

# SFC

## Purpose
**Guardian/caregiver companion app** for the SafeFinder elder-safety system. Despite the name suggesting "Configurator," BLE provisioning is only the onboarding entry point — the bulk of the app is a caregiver-facing UI: live elder status dashboard, guardian management, and safe-zone (WiFi/BLE/GPS) management, all backed by `https://sf-api.ese-lab.com/api/v1/*`.

## Key Files
| File | Description |
|------|-------------|
| `build.gradle.kts`, `app/build.gradle.kts`, `settings.gradle.kts` | Gradle build config — `namespace = "com.sf.sfc"`, `minSdk = 26`, `targetSdk`/`compileSdk = 35`, Kotlin/JVM target 17 |
| `gradle.properties`, `local.properties` | Gradle/AGP settings and machine-specific local SDK path |
| `gradlew`, `gradlew.bat` | Gradle wrapper scripts |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `app/src/main/java/com/sf/sfc/` | Kotlin sources (see below) |

### Key Kotlin sources
| File | Role |
|------|------|
| `MainActivity.kt` (~1870 lines) | Entire UI: pairing/BLE-scan screen, provisioning form, home dashboard (polls `GET /devices/{id}/logs`), guardian CRUD screens, safe-zone CRUD screens. Largest and most important file in this project. |
| `BleProvisioningManager.kt` | BLE **central** role: scans, connects, writes config to `SFD`/`SFW` in chunks (`BEGIN:<size>` → 20-byte chunks → `END`), then polls the device's `STATUS_UUID` characteristic for ACK/registration confirmation. |
| `SfcBleMonitorService.kt` | Background service — rescans for the last-known device every 60s (12s scan window) to keep a BLE connection available for live safe-zone updates. |
| `SfcApiClient.kt` | All backend HTTP calls: `provisionDevice`, `getElder`, safe-zone CRUD, guardian CRUD, device log queries (count/calendar/by-date). |
| `SfcConfig.kt` | BLE UUIDs (`SERVICE_UUID` etc. — identical to SFD/SFW) and `DEFAULT_DEVICE_ID`. |
| `ProvisioningPayload.kt` | `ProvisioningForm` — builds the JSON sent to the device: `deviceId`, `elderName`, `guardian{name,phone}`, `safeZones[WIFI, BLE]`. |
| `PhoneDefaultsReader.kt` | Reads phone-side defaults (WiFi SSID/BSSID, Bluetooth name) to prefill the provisioning form. |
| `Models.kt` | Data models for backend responses: `ElderInfo`, `SafeZoneInfo`/`SafeZoneDraft`, `GuardianInfo`/`GuardianDraft`, `DeviceLogEntry`/`DeviceLogPage`/`DeviceLogCalendar`, etc. |

## For AI Agents

### Working In This Directory
- **This is not just a provisioning tool.** Most of the code is caregiver-facing CRUD and monitoring UI (`MainActivity.kt`). When asked to "fix the SFC app," clarify whether the request is about the BLE onboarding flow or the guardian/safe-zone management UI — they are different areas of the same (very large) file.
- **BLE provisioning is one-directional at first contact, but safe-zone edits are pushed live**: `sendSafeZoneUpdateToSfd()` re-sends a `safeZoneUpdate` message over the same BLE channel whenever a safe zone is created/updated/deleted, using the last-known device BLE address (kept alive by `SfcBleMonitorService`).
- When changing the provisioning/config JSON schema or BLE UUIDs, this must stay compatible with **both** `SFD`'s `BlePeripheralManager.kt`/`SfdConfig.kt` and `SFW`'s `BleConfigServer.kt`/`SfwConfig.kt` — coordinate with `project-manager` (or the relevant owner) before changing.
- Resolved 2026-07: the dead `TARGET_DEVICE_NAME` constant was removed (the monitor scan now filters on `SERVICE_UUID`), and the garbled provisioning status string in `MainActivity.kt` was fixed to "기기 등록 요청 시작".
- `local.properties` is machine-specific; never assume its SDK path is portable across environments.

### Testing Requirements
- Build/run via Gradle: `./gradlew assembleDebug` or open in Android Studio and run on a device/emulator with BLE support (emulator BLE support is limited — prefer a physical device for BLE and safe-zone testing).
- No automated test suite in this project — verify manually against the live backend or a mock.

### Common Patterns
- One manager/service class per concern (`BleProvisioningManager`, `SfcBleMonitorService`, `SfcApiClient`) with all screen logic centralized in `MainActivity.kt` rather than split into separate Activities/Fragments.

## Dependencies

### Internal
- Provisions `SFD/` and (potentially) `SFW/` devices over BLE — payload format must stay compatible with both.
- Pushes live safe-zone updates to whichever device is connected via `SfcBleMonitorService`.

### External
- Android BLE APIs (`BluetoothGatt`/`BluetoothLeScanner` etc.)
- Backend: `https://sf-api.ese-lab.com/api/v1/*` (devices, elders, guardians, safezones, logs)
- Kotlin, Android Gradle Plugin

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
