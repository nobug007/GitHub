<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-04 | Updated: 2026-07-04 -->

# SFD_Test

## Purpose
Lightweight test/reference variant of `SFD` (SafeFinder Device). Keeps the device-reading + telemetry-reporting path but drops the BLE peripheral and WiFi-connector pieces, making it a simpler app for testing the telemetry/API flow in isolation.

## Key Files
| File | Description |
|------|-------------|
| `build.gradle.kts` | Root Gradle build script |
| `settings.gradle.kts` | Declares `rootProject.name` for this test app, includes `:app` |
| `gradle.properties` | Gradle/AGP settings |
| `local.properties` | Local SDK path (machine-specific) |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `app/` | Android application module, namespace `com.sf.sfdtest` |
| `app/src/main/java/com/sf/sfdtest/` | Kotlin sources: `MainActivity.kt` (UI entry), `DeviceReaders.kt` (consolidated device/sensor status reading — likely merges what `SFD` splits into `WifiStatusReader`/`GpsStatusReader`), `TelemetryService.kt` + `TelemetryStore.kt` (telemetry loop + local persistence), `PayloadFactory.kt` (builds telemetry payloads), `ApiClient.kt` (backend API calls), `AppConfig.kt` (app config), `Models.kt` (data models) |

## For AI Agents

### Working In This Directory
- This is a **test/reference** app, not the production device — see `SFD/` for the full app with BLE peripheral + WiFi connector support. Prefer testing telemetry/API changes here first since the surface area is smaller, then port to `SFD` if it also needs the BLE/WiFi pieces.
- Naming here is simpler than `SFD` (`ApiClient` vs `SfdApiClient`, `DeviceReaders` vs separate `WifiStatusReader`/`GpsStatusReader`) — don't assume 1:1 file correspondence between the two projects.
- `minSdk = 26`, `targetSdk`/`compileSdk = 35`, Kotlin/JVM target 17.

### Testing Requirements
- Build/run via Gradle (`./gradlew assembleDebug`) or Android Studio.

### Common Patterns
- Same overall architecture as `SFD`/`SFW`: config + API client + models + a telemetry/service loop.

## Dependencies

### Internal
- Simplified sibling of `SFD/` — no BLE peripheral or WiFi connector dependency.

### External
- Android device/location APIs, Kotlin, Android Gradle Plugin

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
