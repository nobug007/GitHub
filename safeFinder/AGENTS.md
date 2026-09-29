<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-04 | Updated: 2026-07-04 -->

# safeFinder

## Purpose
Reference/test deployment of the SafeFinder telemetry system: a Kotlin Android app that reports WiFi/location readings, a Vercel serverless API that receives and displays them, and a local Node.js server as an alternative receiver for development.

## Key Files
| File | Description |
|------|-------------|
| `README.md` | Full usage guide: running the Android app, Vercel endpoints, local server, and emulator/physical-device URL differences |
| `package.json` | Root manifest — `dev` runs `vercel dev`, `start` runs `server/index.js` |
| `index.html` | Vercel-hosted monitor page; polls every 2s and shows the latest received telemetry |
| `vercel.json` | Vercel deployment config |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `android-app/` | Kotlin native Android test app — shows connected WiFi AP name, sends readings on demand or via a 10-minute foreground-service loop |
| `api/` | Vercel serverless functions: `health.js` (health check), `readings.js` (legacy receiver), `v1/` (versioned telemetry endpoint) |
| `server/` | Standalone Node.js HTTP receiver (`index.js`) for local development, mirroring the Vercel API |

## For AI Agents

### Working In This Directory
- This is a **test/reference** system, not the primary tracked device — see `SFD/` for the main SafeFinder device app and `SFC/`/`SFW/` for its companions. Don't assume shared code between this Android app and those; it predates the `com.sf.*` family and has its own structure.
- Duplicate/dedup logic for telemetry lives in warm serverless memory only (`api/v1/`) — it resets on cold start. Don't rely on it for durable history; the README calls out Vercel KV/Redis/a database as the production-grade option.
- Default server URLs differ by target: emulator uses `10.0.2.2:8080`, physical device uses the PC's LAN IP, deployed app uses the Vercel domain — check which one applies before debugging "it's not sending."

### Testing Requirements
- No automated test suite. Verify manually: run `npm start` (or `vercel dev`) and confirm `GET /api/health` responds, then send a reading from the Android app or via `POST /readings` / `POST /api/v1/telemetry`.
- Android app: open in Android Studio, sync Gradle, run on device/emulator.

### Common Patterns
- Health-check endpoint pattern (`/api/health`, `/health`) mirrored between the Vercel API and the local Node server.

## Dependencies

### Internal
- None — self-contained relative to the rest of the repo.

### External
- Vercel Serverless Functions
- Node.js HTTP server (no framework)
- Android: WiFi/location APIs for reading connected AP name (requires location permission, and on some Android versions, Location services enabled)

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
