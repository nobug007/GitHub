<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-04 | Updated: 2026-07-04 -->

# SFW

## Purpose
**Onboarding-only device app.** Not a lightweight version of `SFD` — it's a functionally distinct role: exposes the same custom BLE GATT protocol/UUIDs as `SFD` so `SFC` can provision it, then self-registers with the backend and stops. No WiFi/GPS/gyro sensing, no telemetry loop, no safe-zone or SOS logic at all.

## Key Files
| File | Description |
|------|-------------|
| `build.gradle.kts`, `app/build.gradle.kts`, `settings.gradle.kts` | Gradle build config — `namespace = "com.sf.sfw"`, **`minSdk = 30`** (higher than SFC/SFD/SFD_Test's `26`), `targetSdk`/`compileSdk = 35`, Kotlin/JVM target 17 |
| `gradle.properties`, `local.properties` | Gradle/AGP settings and machine-specific local SDK path |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `app/src/main/java/com/sf/sfw/` | Kotlin sources (see below) |

### Key Kotlin sources
| File | Role |
|------|------|
| `MainActivity.kt` | Minimal single-screen UI: a "Config" toggle button and a one-line status text. No dashboard, no CRUD, no sensing UI. |
| `BleConfigServer.kt` | BLE GATT server: advertises, exposes `DEVICE_INFO_UUID`/`CONFIG_WRITE_UUID`/`STATUS_UUID`, receives config via the same chunked-transfer idea as `SFD` (`BEGIN:<size>`/chunks/`END`) **but independently reimplemented** — includes an extra fallback that detects a raw JSON start (`{`) even without a `BEGIN` marker, which `SFD`'s implementation does not have. On complete config, saves it to `SharedPreferences` and calls `registerConfigWithServer()` — a direct `POST /devices/register`, nothing more. |
| `SfwConfig.kt` | BLE UUIDs (identical values to `SFC`/`SFD`), `SharedPreferences` keys. No timing/telemetry constants (SFD has many; SFW has none — confirms it doesn't do periodic reporting). |
| `SfwApiClient.kt` | Exactly one call: `registerDevice()`, builds a payload from `deviceId`/`elderName`/`guardian`/WIFI-type `safeZones` and POSTs it. No telemetry, no elder/guardian/safe-zone reads. |

## For AI Agents

### Working In This Directory
- **Do not assume SFW needs the same features as SFD.** It has no `Models.kt`, no telemetry service, no sensors, no SMS — this is intentional, not an oversight. If a task asks to "add SOS/telemetry to SFW," confirm with the user/`project-manager` whether that's actually meant to convert SFW into an SFD-like app, since that would be a significant scope change, not a small addition.
- `minSdk = 30` here is higher than its siblings (`26`) — don't backport SFW-only APIs to `SFC`/`SFD`/`SFD_Test` without checking minSdk compatibility first.
- The BLE chunk-receive logic in `BleConfigServer.kt` is **not** shared code with `SFD`'s `BlePeripheralManager.kt` — they were written separately and have subtly different edge-case handling (see the JSON-start fallback above). If the wire protocol needs to change, both files need matching updates, and `SFC`'s sending side (`BleProvisioningManager.kt`) needs to stay compatible with both.
- Registration here is direct and synchronous-feeling (one `Thread` + one POST) — there's no retry/backoff logic; a failed registration just sets `registrationState = "FAILED"` and stops, requiring the user to retry via BLE again.

### Testing Requirements
- Build/run via Gradle (`./gradlew assembleDebug`) or Android Studio, on a physical device for BLE advertising/GATT server testing (emulators generally can't advertise).
- No automated test suite. Manually verify: BLE config write completes → `registrationState` reads `REGISTERED` (or `ALREADY_REGISTERED`) via the `STATUS_UUID` characteristic.

### Common Patterns
- Same `*Config.kt`/`*ApiClient.kt` naming convention as the rest of the family, but with a much smaller surface area — don't over-engineer additions here to match SFD's complexity unless the task specifically calls for it.

## Dependencies

### Internal
- Onboarded by `SFC/` over BLE using the same UUID scheme as `SFD/`, but implements the chunk-transfer protocol independently.
- Registers itself directly with the backend — no dependency on SFC or SFD at runtime after provisioning.

### External
- Android BLE peripheral/GATT server APIs, Kotlin, Android Gradle Plugin
- Backend: `https://sf-api.ese-lab.com/api/v1/devices/register` (registration only)

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
