# Job / status / heartbeat file schema

MongLightroomBridge (Windows app) and the MongLightroomBridge Lightroom Classic plugin talk to
each other only through files in one shared folder (the "jobs folder", default
`%LOCALAPPDATA%\MongLightroomBridge\jobs`, configurable on both sides). There is no network
call anywhere in this first version - see "Future: localhost bridge" at the end of this file.

All files are UTF-8 JSON, written key-sorted-arbitrarily (order is not meaningful), with
`camelCase` field names. Every write is done atomically (write to a `.tmp` file, then rename
over the final name), so a reader never observes a half-written file.

## `pending-{jobId}.json` (written by the Windows app, read by the plugin)

One job request. The plugin renames this to `processing-{jobId}.json` as soon as it claims the
job (so a restart mid-job never double-processes it), and leaves that renamed copy in place
afterwards as a lightweight audit trail.

| Field | Type | Notes |
|---|---|---|
| `jobId` | string | Unique per job. The Windows app uses `yyyyMMdd-HHmmssfff`. |
| `inputFolder` | string | Absolute Windows path. Read-only - never written to. |
| `outputFolder` | string | Absolute Windows path. Corrected photos are written here. |
| `profile` | string | One of `NaturalPortrait`, `CafeWarm`, `HighKeyClean`, `ProductNeutral`, `BlackAndWhiteSoft`. Informational only by the time the plugin sees it - `adjustments` below already has the profile's defaults merged with whatever the user changed in the Windows app. |
| `format` | string | `"jpg"` or `"tiff"` (lowercase). |
| `quality` | integer | 1-100. JPEG quality; ignored for TIFF. |
| `adjustments` | object | See below. |
| `preserveOriginals` | boolean | Always `true` in practice - see "Safety" below. |
| `dryRun` | boolean | If `true`, the Windows app processes the job itself locally (ImageSharp) and never writes a pending file the plugin would see - this field only matters for a job the Windows app decides *not* to send to Lightroom at all. |
| `createdAtUtc` | string | ISO-8601 UTC timestamp. |
| `excludedFileNames` | array of string, optional | **Windows-app-only extension, not in the original spec.** Plain file names (not full paths) inside `inputFolder` to skip even though they're otherwise-supported images - populated by the app's optional local quality pre-filter (blurry / eyes-uncertain, see "Quality pre-filter" below). Case-insensitive on the plugin side. Omitted/absent/empty means "process everything", exactly as before this field existed - both `DryRunProcessor` and the plugin's `TaskRunner.lua` treat a missing value identically to an empty list, so every job file from before this feature (including `samples/jobs/sample-job.json`) is unaffected. |

### `adjustments` object

| Field | Type | Range | Meaning |
|---|---|---|---|
| `exposure` | number | -5.0 .. 5.0 | Stops. Absolute - sets Lightroom's Exposure slider to exactly this value. |
| `contrast` | number | -100 .. 100 | Absolute. |
| `highlights` | number | -100 .. 100 | Absolute. |
| `shadows` | number | -100 .. 100 | Absolute. |
| `whites` | number | -100 .. 100 | Absolute. |
| `blacks` | number | -100 .. 100 | Absolute. |
| `temperature` | number | roughly -2000 .. 2000 | **Delta**, in Kelvin, added to the photo's *current* white balance temperature - not an absolute Kelvin value. See "Why Temperature/Tint are deltas" below. |
| `tint` | number | roughly -100 .. 100 | **Delta**, added to the photo's current Tint. |
| `vibrance` | number | -100 .. 100 | Absolute. |
| `saturation` | number | -100 .. 100 | Absolute. |
| `cropAspect` | string or omitted | e.g. `"3:2"`, `"4:5"`, `"1:1"`, `"16:9"` | When present, the plugin computes a centered crop to this aspect ratio from the photo's actual pixel dimensions. Omitted/empty = no crop. |
| `convertToGrayscale` | boolean, optional | | When `true`, the photo is converted to black & white. |

**Why Temperature/Tint are deltas, not absolutes:** Lightroom's `Temperature` Develop setting is
an absolute Kelvin value (e.g. 5500), but there is no such thing as "the neutral temperature"
independent of the shot - it depends entirely on the light the photo was taken under. A fixed
absolute value would fight the photographer's as-shot white balance on every single photo (a
sunset shot at 3200K and a cloudy-day shot at 7000K would both get forced to the same number).
Instead, the plugin reads the photo's current Temperature/Tint via `photo:getDevelopSettings()`
and adds the requested delta on top. This is implemented identically in two places that must be
kept in sync: `AdjustmentValues.cs` (Windows/C#) and `DevelopSettings.lua` (plugin/Lua).

## `status-{jobId}.json` (written by the plugin, polled by the Windows app)

| Field | Type | Notes |
|---|---|---|
| `jobId` | string | |
| `status` | string | `"pending"` \| `"running"` \| `"completed"` \| `"failed"` |
| `startedAtUtc` / `finishedAtUtc` | string or omitted | ISO-8601 UTC. |
| `totalFiles` | integer | Set once the input folder has been scanned. |
| `processedFiles` | integer | Incremented after each file (success or failure). |
| `results` | array | See below. **Always a JSON array, even when empty** (`[]`, never `{}` - the C# side deserializes this as `List<JobFileResult>`). |
| `errorMessage` | string, optional | Only present when `status == "failed"` at the job level (e.g. the input folder couldn't be created, or the job file itself couldn't be parsed). |

### `results[]` entry (`JobFileResult`)

| Field | Type | Notes |
|---|---|---|
| `fileName` | string | Just the filename, not a full path. |
| `success` | boolean | |
| `outputPath` | string, present only when `success == true` | Full path to the exported file. |
| `error` | string, present only when `success == false` | Human-readable error message - see "Error messages" below. |
| `skipped` | boolean, optional | **Windows-app-only extension.** `true` when this file was never handed to the export pipeline at all because it was listed in `excludedFileNames` (see above). Always paired with `success == false` and a explanatory `error`, so any older code that only checks `success`/`error` keeps working unchanged - `skipped` just lets the Windows app UI show a distinct "skipped by quality filter" icon instead of a red failure icon. Omitted (falsy) for every ordinary success/failure result. |

## Quality pre-filter (Windows-app-only, optional)

The Windows app can optionally run a local, offline pre-filter (`IPhotoQualityFilter` /
`OpenCvPhotoQualityFilter`, using OpenCvSharp) over `inputFolder` before building a job, when the
user checks either "초점이 안 맞는(흐린) 사진 제외" (exclude blurry) or "눈이 열린 것을 확인할 수 없는
사진 제외" (exclude eyes-uncertain) in the UI. Both are off by default, so a job's shape never
changes unless the user opts in.

- **Blur check**: variance of the Laplacian of a downscaled grayscale copy of the image - a
  standard focus-blur metric. Below the configurable threshold (default `100.0`) counts as
  blurry.
- **Eyes check**: best-effort only. Detects the largest face (Haar cascade
  `haarcascade_frontalface_default.xml`), then looks for an eye-like feature in its upper half
  (Haar cascade `haarcascade_eye_tree_eyeglasses.xml`, chosen for better tolerance of glasses).
  This can only ever *positively confirm* "found an eye-like feature" - it cannot reliably tell
  open eyes from closed eyes, and glasses glare/angle/occlusion routinely defeat the detector even
  when the eyes are wide open. So there is deliberately no "closed" verdict anywhere in this
  feature - a photo is excluded when the check is **uncertain** (no face or no eye-like feature
  found), never because eyes were positively detected as closed. `EyeOpennessResult` in the C#
  code has only `NotChecked` / `OpenDetected` / `Uncertain` - never `Closed` - specifically so this
  limitation can't quietly get lost in a future refactor.

Any file excluded this way is added to the job's `excludedFileNames` (see above) instead of being
left out of the job entirely, so it still shows up in the Windows app's results list - as a
skipped item with the reason, not silently dropped - and the user can always undo an exclusion by
unchecking the option and re-running.

## `log-{jobId}.log` (written by the plugin, polled by the Windows app)

Plain text, UTF-8, append-only. One line per event, each prefixed with `[<ISO-8601 UTC
timestamp>]`. Not JSON - the Windows app just displays whatever is currently in the file. This
is a plain append (not atomic-write-then-rename like the JSON files above), matching the
reader's tolerant "read whatever is there right now" behaviour.

## `heartbeat.json` (written by the plugin every poll tick, read by the Windows app)

| Field | Type | Notes |
|---|---|---|
| `lastSeenUtc` | string | ISO-8601 UTC, updated every tick (default every 2 seconds). |
| `lightroomVersion` | string | e.g. `"14"` - best-effort from `LrApplication.versionTable()`. |
| `pluginVersion` | string | The plugin's own version, e.g. `"0.1.0"`. |
| `queuedJobs` | integer | How many `pending-*.json` files were sitting in the folder at that tick. |

The Windows app treats the connection as **CONNECTED** as long as `heartbeat.json`'s
`lastSeenUtc` is less than ~15 seconds old, and **DISCONNECTED** otherwise (file missing, stale,
or unparseable). This is the *only* signal it uses - there is no other "is Lightroom running"
check, so it is only as reliable as the plugin's own tick loop.

## Safety: originals are never modified

`preserveOriginals` in the job JSON is honored as a fixed invariant on the plugin side, not a
real toggle: even a job that explicitly sets it to `false` is processed exactly as if it were
`true`, and the plugin logs a warning rather than silently ignoring what was asked. The plugin
never calls any Lightroom API that writes to a source file - `catalog:addPhoto()` only
registers the existing file with the catalog (Lightroom's Develop settings live in the catalog
database, not in the source file, until something explicitly does "Save Metadata to File",
which nothing here ever calls), and export always renders to `outputFolder`.

## Error messages

`log-{jobId}.log` lines and `results[].error` are meant to be read directly by a person, not
just logged for developers - e.g. `FAIL: IMG_0231.CR3 - LrExportSession: ...` rather than a bare
Lua stack trace. See the "Lightroom SDK limitations" section of the top-level README for what
kinds of failures are most likely and what they mean.

## Future: localhost HTTP bridge

This version is intentionally file-only. `IJobFileService` (C#) and `JobFile.lua` (Lua) are
both written as the seam where a future HTTP-based bridge could be swapped in without changing
any calling code - `SubmitJobAsync`/`TryReadStatusAsync`/etc. on the C# side already look like
what an HTTP client's methods would look like. Not implemented in this version, per the
original spec ("파일 기반으로 우선 구현하고, 향후 localhost HTTP 연동을 위한 인터페이스만 남겨둬라").
