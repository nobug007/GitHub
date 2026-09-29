# MongLightroomBridge

A Windows desktop app + an Adobe Lightroom Classic plugin that work together to batch-apply an
auto-correction profile to a folder of photos and export the results, without ever touching the
original files.

- **`MongLightroomBridge.App`** (WPF, .NET 8) - pick a profile, tweak sliders, click 자동 보정
  실행 ("Run Auto Correction"), watch progress.
- **`LightroomPlugin/MongLightroomBridge.lrdevplugin`** - a Lightroom Classic plugin that polls a
  shared folder for jobs, applies the requested Develop settings to each photo via the catalog,
  and exports the corrected copies.
- The two only ever talk through JSON/text files in one shared folder. There is no network call,
  no shared process, and no direct linking of Lightroom's editing engine into the Windows app.

## Table of contents

1. [Architecture](#architecture)
2. [Repository layout](#repository-layout)
3. [Installing the Lightroom Classic SDK / plugin](#installing-the-lightroom-classic-sdk--plugin)
4. [Building and running the Windows app](#building-and-running-the-windows-app)
5. [Testing](#testing)
6. [Lightroom SDK limitations and confidence notes](#lightroom-sdk-limitations-and-confidence-notes)
7. [Safety: originals are never modified](#safety-originals-are-never-modified)

## Architecture

```
 ┌─────────────────────────┐        pending-{jobId}.json        ┌───────────────────────────┐
 │  MongLightroomBridge.App │ ───────────────────────────────▶  │  MongLightroomBridge       │
 │  (WPF, .NET 8)            │                                │  .lrdevplugin (Lua)         │
 │                           │  ◀──────────────────────────────  │                             │
 │  - profile + sliders UI   │   status-{jobId}.json, log,       │  - polls jobs folder        │
 │  - writes job JSON        │   heartbeat.json                  │  - catalog:addPhoto         │
 │  - polls status/log       │                                   │  - applyDevelopSettings     │
 │  - dry-run (no Lightroom) │                                   │  - LrExportSession export   │
 └─────────────────────────┘                                   └───────────────────────────┘
              shared folder:  %LOCALAPPDATA%\MongLightroomBridge\jobs\  (configurable both sides)
```

Both sides read/write only to that shared "jobs folder". The Windows app never imports Lightroom
SDK code, and the Lua plugin never talks to the Windows app except through those files. This
keeps the two fully decoupled - Lightroom Classic does not even need to be running for the
Windows app's **dry-run mode** to work (it corrects a copy of the image itself, locally, with
ImageSharp, purely so you can preview slider effects before Lightroom is involved).

`IJobFileService` (C#, `MongLightroomBridge.Infrastructure`) and `JobFile.lua` (Lua) are both
written as a seam for a possible future localhost HTTP bridge (see the spec note in
`docs/job-schema.md`'s last section) - not implemented in this version, per the original
instruction to implement file-based IPC first and only leave the interface for later.

## Repository layout

```
MongLightroomBridge.sln
src/
  MongLightroomBridge.Core/            job & status models, profile definitions, adjustment math
  MongLightroomBridge.Infrastructure/  file-based job IPC, dry-run local processor
  MongLightroomBridge.App/             WPF UI (MVVM, CommunityToolkit.Mvvm)
  MongLightroomBridge.Tests/           unit tests (xunit)
LightroomPlugin/
  MongLightroomBridge.lrdevplugin/     the Lightroom Classic plugin - see file list below
docs/
  job-schema.md                        full job/status/log/heartbeat JSON schema
samples/
  jobs/sample-job.json                 example pending-job file (CafeWarm profile)
  output/                              dry-run / Lightroom export output lands here
build.bat                              pins the SDK-bearing dotnet.exe and builds the solution
```

### Lightroom plugin files (`LightroomPlugin/MongLightroomBridge.lrdevplugin/`)

| File | Purpose |
|---|---|
| `Info.lua` | Plugin manifest (`LrSdkVersion`, `LrToolkitIdentifier`, menu items, `LrInitPlugin`). |
| `PluginInit.lua` | `LrInitPlugin` hook - starts the background polling task when Lightroom loads the plugin. |
| `PluginManager.lua` | `LrPluginInfoProvider` - the settings section shown in Plug-in Manager (jobs folder path, poll interval, status). |
| `TaskRunner.lua` | Core orchestrator: polls for `pending-*.json`, imports photos, applies settings, exports, writes status/log/heartbeat. |
| `DevelopSettings.lua` | Converts a job's `adjustments` object into a Lightroom Develop settings table (2012-process keys), including crop and the Temperature/Tint delta logic. |
| `ExportServiceProvider.lua` | `LrExportSession`-based export helper. **Naming note:** despite the name, this is not a real interactive `LrExportServiceProvider` (the SDK's plugin-export-format hook) - see the SDK limitations section below for why, and what it actually is. |
| `JobFile.lua` | Reads/writes the shared job/status/log/heartbeat files; atomic write (temp file + rename). |
| `Json.lua` | Hand-written pure-Lua JSON encode/decode (no third-party dependency). |
| `Log.lua` | Thin wrapper around `LrLogger`. |
| `RunNow.lua` | Menu command to trigger an immediate poll (`LrLibraryMenuItems`), useful for manual testing. |
| `OpenLogsFolder.lua` | Menu command that reveals the jobs folder in Explorer (`LrShell.revealInShell`). |

## Installing the Lightroom Classic SDK / plugin

You do **not** need to download or install the Lightroom SDK separately to *run* the plugin - the
SDK is only a set of Lua API declarations and documentation that Adobe publishes for plugin
developers; Lightroom Classic itself already contains the runtime that executes plugin Lua code.
You only need the SDK docs if you intend to modify the plugin's Lua source.

**To install the plugin into your copy of Lightroom Classic:**

1. Make sure Adobe **Lightroom Classic** (not the cloud-only "Lightroom") is installed.
2. Open Lightroom Classic → **File → Plug-in Manager…**
3. Click **Add** (bottom-left of the Plug-in Manager dialog).
4. Browse to and select the `MongLightroomBridge.lrdevplugin` folder itself (the folder, not a
   file inside it) - by default `C:\GitHub\LT\LightroomPlugin\MongLightroomBridge.lrdevplugin`,
   or wherever you copied it.
5. Confirm the plugin shows **Status: Installed and running** in the list on the left, with a
   green status indicator. If it shows a warning/error, expand it - the message will name the
   missing file or Lua error.
6. The plugin's own settings panel (jobs folder path, poll interval) appears on the right side of
   Plug-in Manager when the plugin is selected - the jobs folder path here must match the one
   configured in the Windows app's settings screen (both default to
   `%LOCALAPPDATA%\MongLightroomBridge\jobs`).
7. Close Plug-in Manager. The plugin starts polling automatically (via `LrInitPlugin` in
   `PluginInit.lua`); no further action is needed in Lightroom itself. You can also trigger an
   immediate one-off poll from **Library → Plug-in Extras → MongLightroomBridge → Run now**, and
   open the jobs folder from the same menu (**Open logs/jobs folder**).

If you want to read Adobe's official SDK documentation (API reference, sample plugins) while
modifying the Lua source, it is published on Adobe's developer site under "Lightroom Classic SDK"
- search "Lightroom Classic SDK download" from Adobe's developer portal, since the exact URL and
the current SDK version number change over releases (see the SDK-version note below).

## Building and running the Windows app

Requires the **.NET 8 SDK** (not just the runtime - see the two-`dotnet.exe` note below if
`dotnet build` reports "no compatible SDK" despite .NET being installed).

```bat
cd C:\GitHub\LT
build.bat
```

`build.bat` builds `MongLightroomBridge.sln` in Debug/x64 and writes full output to
`build_output.txt` (useful because Computer Use / remote sessions can't always see a console
window scroll by). To run the app after a successful build:

```bat
"C:\GitHub\LT\src\MongLightroomBridge.App\bin\x64\Debug\net8.0-windows\MongLightroomBridge.App.exe"
```

or open `MongLightroomBridge.sln` in Visual Studio and press F5.

**On first run**, use the settings screen (gear icon) to confirm/change:
- the jobs folder path (must match the path configured in the Lightroom plugin's Plug-in Manager
  panel),
- the input photo folder and output export folder.

Then: pick a profile, adjust any of the 10 sliders (Exposure/Contrast/Highlights/Shadows/Whites/
Blacks/Temperature/Tint/Vibrance/Saturation) plus crop aspect if desired, and click **자동 보정
실행**. The connection indicator turns green once the plugin's `heartbeat.json` is being written
(i.e. Lightroom Classic is open with the plugin installed and running); progress and per-file
results stream into the log panel and processed-image list as the plugin updates
`status-{jobId}.json`.

**Two-dotnet.exe note:** on a machine with both a 32-bit "runtime only" install and a 64-bit SDK
install of .NET, `where dotnet` / plain PATH resolution can pick the wrong one first, producing
"Could not execute because the application was not found or a compatible .NET SDK is not
installed" even though the SDK genuinely is installed. `build.bat` avoids this by calling the
64-bit SDK's `dotnet.exe` by full path (`C:\Program Files\dotnet\dotnet.exe`) rather than relying
on PATH. If you build manually and hit this error, run `dotnet --info` and check which
`dotnet.exe` resolves first in your `PATH`.

## Testing

Three layers of testing were run for this project, appropriate to what each side can actually be
tested with:

**1. C# unit tests (`MongLightroomBridge.Tests`)** - run with:

```bat
"C:\Program Files\dotnet\dotnet.exe" test MongLightroomBridge.sln -c Debug
```

Covers profile default merging, adjustment clamping/range validation, and job/status JSON
round-tripping.

**2. Full solution build** - `build.bat` (see above). Last verified result: **0 errors, 4
warnings** (all 4 warnings are `SixLabors.ImageSharp` known-vulnerability advisories, NU1902/
NU1903 - informational, not build failures; see "Known issue" below if you want to address them).

**3. Lua plugin verification** - there is no bundled Lua interpreter to "run" a Lightroom plugin
outside Lightroom itself (Lightroom's Lua runtime is private to the application), so the plugin
code was verified two ways during development:
   - **Syntax verification** of all 11 `.lua` files (parsed, not executed, against a real Lua 5.4
     runtime).
   - **Behavioral tests** of the two modules that contain the actual "logic" (as opposed to SDK
     calls that need Lightroom running) - `Json.lua` and `DevelopSettings.lua` - executed against
     a real Lua interpreter with 25 assertions covering: JSON encode/decode round-trip using this
     project's own sample job payload; correct integer-not-float encoding of the `quality` field;
     empty-vs-non-empty JSON array encoding (`[]` vs `{}`); graceful handling of malformed JSON;
     UTF-8 (Korean text) round-trip; centered-crop math for both wide and tall source photos;
     unparseable `cropAspect` strings being ignored rather than crashing; the Temperature/Tint
     delta math (both the "add delta to current WB" case and passing through when no current
     value is available); and extreme-value clamping. All 25 assertions passed.
   - **What this does *not* verify**: any code path that calls into the real Lightroom SDK
     objects (`LrApplication`, `LrExportSession`, `catalog:addPhoto`, etc.), since those only
     exist inside a running Lightroom Classic process. That requires a manual end-to-end test:

**Manual end-to-end test (needs a real Lightroom Classic installation):**

1. Install the plugin (see above) and confirm Plug-in Manager shows it running.
2. Point the Windows app's input folder at `C:\GitHub\Photo` (21 real sample JPGs are already
   there) and its output folder at `C:\GitHub\LT\samples\output`.
3. Either click **자동 보정 실행** in the Windows app, or copy `samples/jobs/sample-job.json`
   directly into the jobs folder and use **Library → Plug-in Extras → MongLightroomBridge → Run
   now** for a faster manual trigger that doesn't require the Windows app at all.
4. Watch `status-{jobId}.json` and `log-{jobId}.log` appear/update in the jobs folder (or watch
   the Windows app's own log panel), and confirm corrected JPGs appear in
   `C:\GitHub\LT\samples\output` while the originals in `C:\GitHub\Photo` are untouched (check
   file modified timestamps).

**Dry-run mode** (works with zero Lightroom Classic install, useful for UI/demo purposes): toggle
"dry run" in the Windows app before clicking 자동 보정 실행. The Windows app applies a local,
approximate version of the same adjustments directly to a copy of each image using ImageSharp,
skips writing anything to the jobs folder, and writes results straight to the output folder. This
is **not** a substitute for the real Lightroom-driven correction (different rendering engine,
approximate slider math) - it exists purely so the app is demoable/testable without Lightroom.

### Optional: photo quality pre-filter (exclude blurry / eyes-uncertain photos)

Two checkboxes in the Windows app - "초점이 안 맞는(흐린) 사진 제외" (exclude blurry photos) and
"눈이 열린 것을 확인할 수 없는 사진 제외" (exclude photos where an open eye can't be confirmed) -
are both off by default. When either is checked, the app runs a local, offline pass over the
input folder (OpenCvSharp: Laplacian-variance sharpness scoring, plus Haar-cascade face/eye
detection) *before* building the job, and any excluded photo is skipped in both dry-run and
real Lightroom jobs - it still shows up in the results list with an amber "skipped" indicator and
a reason, it just isn't sent through Develop/export. This is entirely a Windows-app-side
convenience layer (`IPhotoQualityFilter` / `OpenCvPhotoQualityFilter`) on top of one small,
backward-compatible, optional job field (`excludedFileNames` - see `docs/job-schema.md`); it adds
no new Lightroom SDK surface and older job files are completely unaffected.

Important limitation, by design: the eye check can only ever confirm "found an eye-like feature" -
it cannot reliably tell open eyes from closed eyes, since glasses glare, angle, or partial
occlusion routinely defeats the detector even with eyes wide open. So a photo is excluded when the
check is **uncertain**, never because eyes were positively detected as *closed* - there is no
"closed" state anywhere in the code (`EyeOpennessResult` only has `NotChecked` / `OpenDetected` /
`Uncertain`). Always spot-check what got excluded before assuming every excluded photo actually
has a problem.

## Lightroom SDK limitations and confidence notes

Everything below reflects what was verified during development (via Adobe's own SDK
documentation and real published community plugins) versus what remains a reasonable-but-
unverified assumption. Nothing here is a fabricated API name; anything not confirmed is flagged
explicitly rather than presented as certain.

- **`LrPhoto:applyDevelopSettings(settingsTable, actionName, relativeTo2012Defaults)`** - this is
  the core API this plugin relies on to actually push slider values onto a photo. It is
  confirmed real (present in Adobe's own SDK reference and used by multiple published
  third-party plugins) and, importantly, works on *any* `LrPhoto` obtained via
  `catalog:addPhoto()`/search - it does **not** require that photo to be the active/selected
  photo in Develop module, unlike `LrDevelopController` (see next point). This is why the plugin
  never has to bring Lightroom's UI into Develop module or select photos - it can process a whole
  folder of photos as pure catalog operations.
- **`LrDevelopController`** exists in the SDK but was deliberately **not used** - its API only
  operates on whatever photo is currently active in the Develop module UI, which makes it
  unsuitable for unattended batch processing of many files. This plugin avoids it entirely.
- **2012-process Develop setting keys** (`Exposure2012`, `Contrast2012`, `Highlights2012`,
  `Shadows2012`, `Whites2012`, `Blacks2012`, plus `Vibrance`, `Saturation`, `Temperature`, `Tint`,
  `ConvertToGrayscale`, `HasCrop`/`CropTop`/`CropLeft`/`CropRight`/`CropBottom`/`CropAngle`) are
  the real key names used by `applyDevelopSettings`/`getDevelopSettings` for photos using
  Lightroom's modern ("2012"/current) process version. If a specific photo in your catalog is
  still on an older process version (very old catalogs, pre-2012 processed photos), these keys
  may behave differently or not apply as expected - the plugin does not currently detect or
  migrate process version.
- **`LrSdkVersion = 9.0`, `LrSdkMinimumVersion = 6.0`** in `Info.lua` were chosen as
  conservative, individually-confirmed-real values (matched against a working published example
  plugin) rather than the newest possible SDK version number. Current Lightroom Classic releases
  support a materially newer SDK version, but the exact current number was not independently
  confirmed to high confidence during development - if Plug-in Manager reports an SDK-version
  compatibility warning, raising `LrSdkVersion` in `Info.lua` to match your installed Lightroom
  Classic's supported SDK version should resolve it; nothing else in the plugin depends on this
  number.
- **`ExportServiceProvider.lua` naming deviation** - the file is named to match the task's
  requested file list, but it deliberately does **not** implement the real
  `LrExportServiceProvider` interface (the SDK hook that adds a new *interactive* export format
  users pick from Lightroom's own Export dialog). That interface is the wrong tool here: this
  plugin needs to trigger an export **programmatically**, from the background task, not offer a
  new UI option in Lightroom's Export dialog. Instead the file is a plain helper module that
  builds an `LrExportSession` and calls `:doExportOnCurrentTask()` directly - which is the
  documented, correct way to export photos from plugin code without user interaction. This is
  noted again in the file's own header comment.
- **Temperature/Tint as deltas, not absolutes** - see `docs/job-schema.md` for the full
  reasoning; summarized, Lightroom's `Temperature` key is an absolute Kelvin value with no
  meaningful "zero point" independent of the shot, so the plugin reads each photo's *current*
  Temperature/Tint via `photo:getDevelopSettings()` first and adds the job's delta on top, rather
  than overwriting with an absolute number.
- **Long-running work and `LrTasks`** - all plugin work that touches the catalog or filesystem
  runs inside `LrTasks.startAsyncTask`, using `LrTasks.pcall`/`LrTasks.sleep`/`LrFunctionContext`
  patterns per Adobe's guidance for long-running background plugin code (never blocking
  Lightroom's UI thread), and catalog writes go through `catalog:withWriteAccessDo()` as
  required by the SDK.
- **Known issue (non-blocking):** the Windows app's dry-run image processor depends on
  `SixLabors.ImageSharp`, which currently reports two known-vulnerability NuGet advisories
  (NU1902/NU1903) for the pinned version. These do not block the build or affect the
  Lightroom-driven path at all (dry-run is a local preview convenience only) but should be
  revisited by bumping the package version if this project moves toward production use.

## Safety: originals are never modified

This is treated as a hard invariant, not a configurable behavior, in both halves of the app:

- The Windows app's job JSON always sets `"preserveOriginals": true`.
- The Lightroom plugin honors this as fixed regardless of what a job file says - even a
  (hypothetical, never-produced-by-the-Windows-app) job with `"preserveOriginals": false` is
  still processed as if it were `true`, with a warning logged rather than silently ignored.
- The plugin only ever calls `catalog:addPhoto()` (registers an existing file with the catalog;
  does not touch the file on disk) and export APIs targeting `outputFolder` (a separate,
  user-chosen folder). Nothing in this plugin calls Lightroom's "Save Metadata to File" or any
  other API that writes into the original source file.
- Dry-run mode (Windows app / ImageSharp) reads each source image and writes its corrected
  version only into the configured output folder - it never overwrites the source file it reads
  from.
