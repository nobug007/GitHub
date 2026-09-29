<#
    Run-SFSetup.ps1
    SafeFinder onboarding orchestration / test harness (PC-driven via ADB).

    Drives the 4-step flow end to end:
      1. Install + launch SFD on the tablet   -> SFD starts BLE advertising
      2. Confirm SFD BLE advertising is live
      3. Launch SFC on the phone, run BLE scan -> when the phone "finds" SFD,
         enable the phone Mobile Hotspot (must go through the Settings UI:
         Android blocks 3rd-party apps from toggling internet-sharing hotspot)
      4. Connect the tablet to the phone hotspot SSID (already saved on tablet)

    This harness exists BECAUSE step 3 cannot be done from inside the phone app
    or from pure device settings. See README.md for the full rationale.

    Usage:
      pwsh ./Run-SFSetup.ps1                    # auto-detect phone + tablet
      pwsh ./Run-SFSetup.ps1 -Reset             # turn hotspot off / disconnect first
      pwsh ./Run-SFSetup.ps1 -SkipInstall       # don't reinstall SFD
#>

[CmdletBinding()]
param(
    [string]$PhoneSerial,
    [string]$TabletSerial,
    [string]$SfdApk = "C:\GitHub\SFD\app\build\outputs\apk\debug\app-debug.apk",
    [string]$SfdPkg = "com.sf.sfd",
    [string]$SfcPkg = "com.sf.sfc",
    [string]$SfdAdvertiseName = "Safe Finder",   # substring shown in SFC scan list
    [switch]$SkipInstall,
    [switch]$Reset
)

# Native tools (adb) write harmless noise to stderr (e.g. dumpsys "Broken pipe"
# when grep -m1 closes the pipe early). Under Stop that would abort the script,
# so we use Continue and check every result explicitly via Die/Warn.
$ErrorActionPreference = "Continue"
$script:StepNo = 0

function Say([string]$msg, [string]$color = "Gray") { Write-Host $msg -ForegroundColor $color }
function Step([string]$title) {
    $script:StepNo++
    Write-Host ""
    Write-Host ("=== STEP {0}: {1} ===" -f $script:StepNo, $title) -ForegroundColor Cyan
}
function Ok([string]$m)   { Write-Host ("  [OK]   {0}" -f $m) -ForegroundColor Green }
function Info([string]$m) { Write-Host ("  [..]   {0}" -f $m) -ForegroundColor Gray }
function Warn([string]$m) { Write-Host ("  [WARN] {0}" -f $m) -ForegroundColor Yellow }
function Die([string]$m)  { Write-Host ("  [FAIL] {0}" -f $m) -ForegroundColor Red; exit 1 }

# ---- ADB helpers -----------------------------------------------------------
# NB: function must NOT be named 'Adb' -- PowerShell is case-insensitive and it
# would shadow the adb.exe executable, causing infinite recursion.
function Ash([string]$serial, [string]$cmd) {
    $out = & adb.exe -s $serial shell $cmd
    return ($out | Out-String)
}

function Get-Devices {
    $lines = & adb.exe devices -l
    $devs = @()
    foreach ($l in $lines) {
        if ($l -match '^(\S+)\s+device\s') {
            $serial = $Matches[1]
            $model  = if ($l -match 'model:(\S+)') { $Matches[1] } else { "unknown" }
            $devs += [pscustomobject]@{ Serial = $serial; Model = $model }
        }
    }
    return $devs
}

# Parse a uiautomator XML dump into node objects (text, checked, cx, cy).
function Get-UiNodes([string]$serial) {
    Ash $serial "uiautomator dump /sdcard/sf_ui.xml" | Out-Null
    $xml = Ash $serial "cat /sdcard/sf_ui.xml"
    $nodes = @()
    foreach ($m in [regex]::Matches($xml, '<node\b[^>]*?/?>')) {
        $n = $m.Value
        $text    = if ($n -match 'text="([^"]*)"')      { $Matches[1] } else { "" }
        $desc    = if ($n -match 'content-desc="([^"]*)"') { $Matches[1] } else { "" }
        $checked = if ($n -match 'checked="(true|false)"') { $Matches[1] -eq 'true' } else { $null }
        $cls     = if ($n -match 'class="([^"]*)"')      { $Matches[1] } else { "" }
        $bounds  = if ($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
                       [pscustomobject]@{ x1=[int]$Matches[1]; y1=[int]$Matches[2]; x2=[int]$Matches[3]; y2=[int]$Matches[4] }
                   } else { $null }
        if ($bounds) {
            $nodes += [pscustomobject]@{
                Text = $text; Desc = $desc; Class = $cls; Checked = $checked
                CX = [int](($bounds.x1 + $bounds.x2) / 2)
                CY = [int](($bounds.y1 + $bounds.y2) / 2)
                Bounds = $bounds
            }
        }
    }
    return $nodes
}

function Tap([string]$serial, [int]$x, [int]$y) { Ash $serial "input tap $x $y" | Out-Null }

# Find the Switch control that belongs to a labelled row: pick the Switch whose
# vertical centre is closest to the label TextView. More robust than "first Switch".
function Find-SwitchByLabel($nodes, [string]$labelRegex) {
    $label = $nodes | Where-Object { $_.Class -match 'TextView' -and $_.Text -match $labelRegex } | Select-Object -First 1
    $switches = $nodes | Where-Object { $_.Class -match 'Switch' }
    if (-not $switches) { return $null }
    if (-not $label) { return ($switches | Select-Object -First 1) }
    return ($switches | Sort-Object { [math]::Abs($_.CY - $label.CY) } | Select-Object -First 1)
}

function Softap-Up([string]$serial) {
    # Live signal only: mCurrentSoftApInfoMap holds a SoftApInfo entry (e.g.
    # swlan0=SoftApInfo{...}) while the hotspot is up, and is "{}" when off.
    # NB: do NOT grep "num SoftApManagers" -- that string also appears in the
    # historical state-machine records (rec[N]) and would give stale results.
    $s = Ash $serial "dumpsys wifi 2>/dev/null | grep -m1 mCurrentSoftApInfoMap"
    return ($s -match 'SoftApInfo\{')
}

function Wake([string]$serial) {
    Ash $serial "input keyevent KEYCODE_WAKEUP" | Out-Null
    Ash $serial "wm dismiss-keyguard" | Out-Null
    Start-Sleep -Milliseconds 500
}

# ---- Device resolution -----------------------------------------------------
Step "Resolve connected devices"
$devs = Get-Devices
foreach ($d in $devs) { Info ("device {0}  model={1}" -f $d.Serial, $d.Model) }
if ($devs.Count -lt 2 -and (-not $PhoneSerial -or -not $TabletSerial)) {
    Die "Need both phone and tablet authorized in 'adb devices'. Found $($devs.Count)."
}
# Heuristics: tablet models tend to contain X9/Tab/gts; phone contains S9/SM-S.
if (-not $TabletSerial) {
    $t = $devs | Where-Object { $_.Model -match 'X9|Tab|gts|SM_X' } | Select-Object -First 1
    if ($t) { $TabletSerial = $t.Serial }
}
if (-not $PhoneSerial) {
    $p = $devs | Where-Object { $_.Serial -ne $TabletSerial } | Select-Object -First 1
    if ($p) { $PhoneSerial = $p.Serial }
}
if (-not $PhoneSerial -or -not $TabletSerial) { Die "Could not auto-assign phone/tablet. Pass -PhoneSerial / -TabletSerial." }
Ok ("Phone  = {0}" -f $PhoneSerial)
Ok ("Tablet = {0}" -f $TabletSerial)

Wake $PhoneSerial
Wake $TabletSerial

# ---- Optional reset --------------------------------------------------------
if ($Reset) {
    Step "Reset (turn hotspot off, drop tablet wifi)"
    if (Softap-Up $PhoneSerial) {
        Info "Hotspot is ON -> opening tether settings to turn it off"
        Ash $PhoneSerial "am start -a android.settings.TETHER_SETTINGS" | Out-Null
        Start-Sleep 3
        $sw = (Get-UiNodes $PhoneSerial | Where-Object { $_.Class -match 'Switch' } | Sort-Object CY | Select-Object -First 1)
        if ($sw) { Tap $PhoneSerial $sw.CX $sw.CY; Start-Sleep 5 }
        if (Softap-Up $PhoneSerial) { Warn "Hotspot still ON after toggle" } else { Ok "Hotspot toggled off" }
    } else { Info "Hotspot already OFF" }
    Ash $TabletSerial "svc wifi disable" | Out-Null; Start-Sleep 2
    Ash $TabletSerial "svc wifi enable"  | Out-Null; Start-Sleep 3
    Ok "Tablet wifi cycled"
}

# ---- STEP 1: install + launch SFD -----------------------------------------
Step "Install + launch SFD on tablet (BLE peripheral)"
if (-not $SkipInstall) {
    if (-not (Test-Path $SfdApk)) { Die "SFD APK not found: $SfdApk" }
    Info ("installing {0}" -f (Split-Path $SfdApk -Leaf))
    $r = & adb.exe -s $TabletSerial install -r -g $SfdApk 2>&1 | Out-String
    if ($r -match 'Success') { Ok "SFD installed" } else { Die "SFD install failed: $r" }
} else { Info "SkipInstall set" }
Ash $TabletSerial "am start -n $SfdPkg/.MainActivity" | Out-Null
Start-Sleep 4
Ok "SFD launched"

# ---- STEP 2: confirm BLE advertising --------------------------------------
Step "Confirm SFD BLE advertising is active"
$advOk = $false
for ($i = 0; $i -lt 5; $i++) {
    $adv = Ash $TabletSerial "dumpsys bluetooth_manager 2>/dev/null | grep -ic advertis"
    if (($adv -as [int]) -gt 0) { $advOk = $true; break }
    Start-Sleep 2
}
if ($advOk) { Ok "SFD is advertising (GATT peripheral up)" }
else { Warn "Could not confirm advertising via dumpsys; continuing (SFD setup screen should have it on)" }

# ---- STEP 3: phone scans, finds SFD, then enable hotspot ------------------
Step "Phone SFC scan -> detect SFD"
Ash $PhoneSerial "am force-stop $SfcPkg" | Out-Null
Start-Sleep 1
Ash $PhoneSerial "am start -n $SfcPkg/.MainActivity" | Out-Null
Start-Sleep 4
# Press the "BLE scan" button by locating it in the UI dump (label starts with "BLE").
$scanBtn = Get-UiNodes $PhoneSerial | Where-Object { $_.Text -match 'BLE' } | Select-Object -First 1
if ($scanBtn) { Tap $PhoneSerial $scanBtn.CX $scanBtn.CY; Info "tapped BLE scan" }
else { Warn "scan button not found in UI dump; check SFC screen state" }

$found = $false
for ($i = 0; $i -lt 8; $i++) {
    Start-Sleep 2
    $nodes = Get-UiNodes $PhoneSerial
    $hit = $nodes | Where-Object { $_.Text -match [regex]::Escape($SfdAdvertiseName) } | Select-Object -First 1
    if ($hit) { $found = $true; Ok ("SFD found in scan list: '{0}'" -f $hit.Text.Split("`n")[0]); break }
    Info ("waiting for SFD in scan list... ({0}/8)" -f ($i+1))
}
if (-not $found) {
    Warn "SFD not confirmed in SFC scan dialog (uiautomator can miss AlertDialog rows)."
    Warn "Advertising was confirmed in step 2, so proceeding to hotspot enable."
}

Step "Enable phone Mobile Hotspot (Settings UI toggle)"
if (Softap-Up $PhoneSerial) {
    Ok "Hotspot already UP (per dumpsys)"
} else {
    Ash $PhoneSerial "am start -a android.settings.TETHER_SETTINGS" | Out-Null
    Start-Sleep 4
    $nodes = Get-UiNodes $PhoneSerial
    # On Samsung tether settings the Mobile Hotspot switch is the topmost Switch
    # (smallest Y). Bluetooth/USB/Ethernet tethering switches sit below it.
    $hsSwitch = $nodes | Where-Object { $_.Class -match 'Switch' } | Sort-Object CY | Select-Object -First 1
    if (-not $hsSwitch) { Die "Hotspot switch not found in tether settings." }
    # Tap, wait for SoftAp; retry the tap once if the first press didn't take.
    for ($try = 1; $try -le 2 -and -not (Softap-Up $PhoneSerial); $try++) {
        Info ("tapping hotspot toggle (attempt {0})" -f $try)
        Tap $PhoneSerial $hsSwitch.CX $hsSwitch.CY
        for ($i = 0; $i -lt 6; $i++) { Start-Sleep 2; if (Softap-Up $PhoneSerial) { break } }
    }
}
# Read the SSID the tablet must join.
$ssid = ""
$cfg  = Ash $PhoneSerial "dumpsys wifi 2>/dev/null | grep -m1 mCurrentSoftApConfiguration"
if ($cfg -match 'ssid = "([^"]+)"') { $ssid = $Matches[1] }
if (Softap-Up $PhoneSerial) { Ok ("Hotspot is UP. SSID = '{0}'" -f $ssid) }
else { Die "Hotspot did not start (num SoftApManagers != 1)." }

# ---- STEP 4: tablet joins the phone hotspot -------------------------------
Step "Connect tablet to phone hotspot"
if (-not $ssid) { $ssid = "Nobug"; Warn "SSID unread; assuming '$ssid'" }
$already = Ash $TabletSerial "cmd wifi status"
if ($already -match [regex]::Escape("connected to `"$ssid`"")) {
    Ok "Tablet already connected to '$ssid'"
} else {
    # Open a FRESH wifi list (HOME first, otherwise a stale detail sub-page can be
    # "brought to front" instead of the network list).
    Ash $TabletSerial "input keyevent KEYCODE_HOME" | Out-Null
    Start-Sleep 1
    Ash $TabletSerial "am start -a android.settings.WIFI_SETTINGS" | Out-Null
    Start-Sleep 5
    $joined = $false
    for ($i = 0; $i -lt 8; $i++) {
        $nodes = Get-UiNodes $TabletSerial
        $row = $nodes | Where-Object { $_.Text -eq $ssid } | Select-Object -First 1
        if ($row) {
            Tap $TabletSerial $row.CX $row.CY
            Info ("tapped saved network '{0}'" -f $ssid)
            for ($j = 0; $j -lt 6; $j++) {
                Start-Sleep 2
                $st = Ash $TabletSerial "cmd wifi status"
                if ($st -match [regex]::Escape("connected to `"$ssid`"")) { $joined = $true; break }
            }
            if ($joined) { break }
        } else {
            Info ("SSID '{0}' not visible yet; scanning/scrolling ({1}/8)" -f $ssid, ($i+1))
            Ash $TabletSerial "input swipe 700 1400 700 700" | Out-Null  # scroll down to reveal SSID
        }
        Start-Sleep 2
    }
    if ($joined) { Ok "Tablet connected to '$ssid'" }
    else { Die "Tablet failed to connect to '$ssid'. Confirm it is a saved network." }
}

# ---- Final report ----------------------------------------------------------
Step "Result"
$tabWifi = (Ash $TabletSerial "cmd wifi status" | Select-String 'connected to').ToString().Trim()
Ok  "SFD installed + advertising on tablet"
Ok  "Phone hotspot enabled (SSID '$ssid')"
Ok  "Tablet: $tabWifi"
Write-Host ""
Say "END-TO-END FLOW COMPLETE." "Green"
