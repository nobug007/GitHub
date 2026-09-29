<!-- Generated: 2026-07-04 | Updated: 2026-07-04 -->

# github

## Purpose
Personal monorepo-style collection of independent projects, mostly centered on a "SafeFinder" (SF) elder-safety tracking ecosystem — a guardian/caregiver Android app, tracked-device Android apps, and a shared backend at `sf-api.ese-lab.com` — plus an unrelated AI-assisted MVP builder and a separate Vercel-based reference/test system.

## Key Files
| File | Description |
|------|-------------|
| `package.json` | Minimal root manifest (`github-pages`, no real scripts) — not a real build unit, just a placeholder/name for the repo |
| `index.html` | Standalone static landing page at repo root (unrelated to any subproject's own `index.html`) |
| `vercel.json` | Root-level Vercel config, likely legacy/unused now that per-project Vercel configs exist |
| `.gitattributes` | Git line-ending/attribute rules |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `SFC/` | **Guardian/caregiver companion app** — elder/guardian/safe-zone management UI, live status dashboard, and the BLE onboarding flow that provisions `SFD`/`SFW` devices (see `SFC/AGENTS.md`) |
| `SFD/` | **Primary tracked-device app** — a full safety state machine: BLE peripheral for onboarding, then WiFi/BLE/GPS safe-zone detection, gyro-based movement sensing, and WARNING/EMERGENCY(SOS) escalation with direct SMS alerts to guardians (see `SFD/AGENTS.md`) |
| `SFD_Test/` | Lightweight test/reference variant of SFD without BLE peripheral or WiFi connector (see `SFD_Test/AGENTS.md`) |
| `SFW/` | **Onboarding-only device app** — same BLE GATT protocol/UUIDs as SFD, but only receives config and self-registers with the backend; no WiFi/GPS/gyro/telemetry loop (see `SFW/AGENTS.md`) |
| `safeFinder/` | Separate reference/test deployment (different backend domain, Vercel + Node) — not integrated with the SFC/SFD/SFW ecosystem (see `safeFinder/AGENTS.md`) |
| `MAG/` | Unrelated project: "MAG AI MVP Builder", an external-package-free harness for AI-assisted idea-to-MVP workflows (see `MAG/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- Each top-level directory is an independent, separately-versioned project — do not assume shared dependencies or build tooling across them.
- `SFC`, `SFD`, `SFD_Test`, and `SFW` share the `com.sf.*` package namespace, the **same custom BLE GATT protocol** (identical `SERVICE_UUID`/`DEVICE_INFO_UUID`/`CONFIG_WRITE_UUID`/`STATUS_UUID` across SFC/SFD/SFW), and the same backend (`https://sf-api.ese-lab.com/api/v1/*`). Check sibling projects before duplicating logic.
- **The BLE chunked-transfer protocol (`BEGIN:<size>` / chunks / `END`) is independently reimplemented in `SFD` and `SFW`, not shared.** They are not byte-for-byte identical (e.g. SFW has a JSON-start-detection fallback that SFD lacks). Changing this protocol requires updating both, plus `SFC`'s sending side.
- **`SFC/SfcConfig.kt`'s default `TARGET_DEVICE_NAME` is `"SFD_Test"`, not `"SFD"`** — verify this is intentional before assuming SFC targets the production `SFD` app by default.
- There is a known mojibake/encoding bug in `SFC/app/src/main/java/com/sf/sfc/MainActivity.kt` (garbled Korean status string around the provisioning request) — worth fixing if touching that area.
- `git status`/`git diff` on this repo can be very slow because it lives on an NTFS mount accessed through WSL (`/mnt/c/...`); prefer `find`/`ls` for directory exploration and scope git commands narrowly, with a timeout, when possible.

### Testing Requirements
- Android projects (`SFC`, `SFD`, `SFD_Test`, `SFW`): build/test via Gradle (`./gradlew test`, `./gradlew assembleDebug`) from within each project's own root.
- `MAG`: `npm run verify` (runs syntax checks + `node --test` suite in `test/`).
- `safeFinder`: no automated test suite; verify manually via the Vercel monitor page or local Node server.

### Common Patterns
- SafeFinder family apps follow a similar structure: `MainActivity.kt`, a `*Config.kt` (with the shared BLE UUIDs), a `*ApiClient.kt`, and device/telemetry readers — but the actual amount of logic behind that structure varies enormously (SFC and SFD are substantial; SFW is minimal).

## Dependencies

### External
- Kotlin/Android Gradle Plugin (SFC, SFD, SFD_Test, SFW)
- Node.js + Vercel serverless functions (safeFinder, MAG)
- Shared backend: `https://sf-api.ese-lab.com/api/v1/*` (SFC, SFD, SFW, SFD_Test — separate from `safeFinder`'s own test backend)

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
