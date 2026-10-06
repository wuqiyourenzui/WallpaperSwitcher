#!/usr/bin/env pwsh
<#
.SYNOPSIS
  WallpaperSwitcher 杞婚噺鎬ц兘鍩虹嚎蹇収锛堝惎鍔ㄨ€楁椂 + 甯х粺璁?+ 鍐呭瓨鍗犵敤锛夈€?
.DESCRIPTION
  涓嶆敼浠ｇ爜銆佷笉鍔犱緷璧栵紝鍏ㄩ儴鐢?adb 閲囬泦锛屽師濮嬭緭鍑轰笌瑙ｆ瀽缁撴灉涓€璧峰啓杩?  docs/performance/<鏃堕棿鎴?-<git鐭搱甯?.txt锛屼究浜庢敼鍔ㄥ墠鍚庡姣斻€?
  涓夌被鎸囨爣锛屽墠缃潯浠朵笉鍚岋細

  | 鎸囨爣            | 鍓嶇疆鏉′欢                          | 璇存槑 |
  |-----------------|-----------------------------------|------|
  | 鍚姩鑰楁椂        | 灞忓箷浜?+ 宸茶В閿?                  | `am start -W` 鐨?TotalTime锛屽彇澶氭涓綅鏁?|
  | 甯х粺璁?鍗￠】姣斾緥 | 灞忓箷浜?+ 宸茶В閿?+ 鑳芥帴鍙楁粦鍔ㄨ緭鍏? | `dumpsys gfxinfo`锛涚唲灞忔椂鏄?0 甯э紝浼氳鏄惧紡璺宠繃 |
  | 鍐呭瓨 (Java/PSS) | 杩涚▼瀛樻椿鍗冲彲                      | `dumpsys meminfo`锛涚唲灞忎篃鏈夋晥 |

  鐔勫睆/閿佸睆鏃?*涓嶄細**杈撳嚭鍋囩殑 0% 鍗￠】鐜囷細鑴氭湰浼氬啓鏄庤烦杩囦簡鍝簺鎸囨爣浠ュ強鍘熷洜銆?
.PARAMETER Serial
  adb 璁惧搴忓垪鍙枫€傚彧鏈変竴鍙拌澶囨椂鍙渷鐣ャ€?
.PARAMETER MaxJankyPercent
  鏈夊抚鏁版嵁鏃讹紝janky 甯ф瘮渚嬩笂闄愶紱瓒呰繃浠ラ€€鍑虹爜 2 缁撴潫锛堝彲鐢ㄤ簬鍥炲綊鎷︽埅锛夈€?
.PARAMETER RequireAwake
  灞忓箷鏈寒鏃剁洿鎺ュけ璐ワ紙榛樿鍙鍛婂苟璺宠繃渚濊禆灞忓箷鐨勬寚鏍囷級銆?
.PARAMETER SkipDrive
  鍙噰闆嗐€佷笉椹卞姩搴旂敤銆?
.EXAMPLE
  PowerShell -File tools/perf-snapshot.ps1 -Serial 0A0AA84189A00540
#>
[CmdletBinding()]
param(
    [string]$Serial = "",
    [double]$MaxJankyPercent = 100,
    [switch]$RequireAwake,
    [switch]$SkipDrive
)

# adb writes progress text to stderr; on PowerShell 5.1 `2>&1` folds it into
# captured stdout, which silently corrupts a parsed report. stderr is dropped
# instead (see Invoke-Adb).
$ErrorActionPreference = "Continue"
$pkg = "com.wallpaperswitcher"
$activity = "$pkg/.ui.MainActivity"
$repoRoot = Split-Path -Parent $PSScriptRoot

function Resolve-Adb {
    $candidates = @(
        "D:\Android Studio\SDK\platform-tools\adb.exe",
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $found = Get-ChildItem "D:\Android Studio\SDK" -Recurse -Filter adb.exe -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($found) { return $found }
    throw "adb not found - add its path to Resolve-Adb."
}

$adb = Resolve-Adb
$adbArgs = @()
if ($Serial) { $adbArgs = @("-s", $Serial) }

# Tool arguments are passed as ONE array so that flags belonging to the called
# tool are never seen by PowerShell's parameter binder. `Invoke-Adb shell am
# start -W -n <activity>` used to die on "-W is ambiguous (-WarningAction /
# -WarningVariable)" and silently skipped the launch-time metric.
function Invoke-AdbCmd { param([string[]]$CmdArgs) & $adb @($adbArgs + $CmdArgs) 2>$null }

$devices = (Invoke-AdbCmd @("devices")) -split "`n" | Where-Object { $_ -match "\sdevice$" }
if (-not $devices) { throw "no adb device in 'device' state (adb devices)" }
if (-not $Serial -and $devices.Count -gt 1) {
    throw "several devices attached; pass -Serial.`n$($devices -join "`n")"
}

function Get-Awake {
    $p = (Invoke-AdbCmd @("shell", "dumpsys", "power")) -join "`n"
    if ($p -match "mWakefulness=(\w+)") { return $Matches[1] -eq "Awake" }
    return $false
}
function Get-ScreenOn {
    $p = (Invoke-AdbCmd @("shell", "dumpsys", "power")) -join "`n"
    if ($p -match "mScreenOn=(\w+)") { return $Matches[1] -eq "true" }
    return (Get-Awake)
}
function Get-LaunchMs {
    param([int]$Runs = 3)
    $times = @()
    foreach ($i in 1..$Runs) {
        Invoke-AdbCmd @("shell", "am", "force-stop", $pkg) | Out-Null
        Start-Sleep -Milliseconds 800
        $out = (Invoke-AdbCmd @("shell", "am", "start", "-W", "-n", $activity)) -join "`n"
        if ($out -match "TotalTime:\s*(\d+)") {
            $t = [int]$Matches[1]
            # TotalTime 0 means the system did not really start it (screen off /
            # already resumed): counting it would flatter the baseline.
            if ($t -gt 0) { $times += $t }
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $times) { return $null }
    return ($times | Sort-Object)[[int][math]::Floor($times.Count / 2)]
}

$stamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$git = try { (git -C $repoRoot rev-parse --short HEAD).Trim() } catch { "nogit" }
$awake = Get-Awake
$screenOn = Get-ScreenOn

$outDir = Join-Path $repoRoot "docs/performance"
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$outFile = Join-Path $outDir "$stamp-$git.txt"

$apk = Join-Path $repoRoot "app/build/outputs/apk/debug/app-debug.apk"
$apkInfo = if (Test-Path $apk) {
    $fi = Get-Item $apk
    "$($fi.Name) $([math]::Round($fi.Length / 1MB, 1))MB $($fi.LastWriteTime)"
} else { "(no debug apk)" }

$log = New-Object System.Collections.Generic.List[string]
$log.Add("# WallpaperSwitcher 杞婚噺鎬ц兘鍩虹嚎")
$log.Add("")
$log.Add("captured  : $stamp")
$log.Add("git       : $git")
$log.Add("apk       : $apkInfo")
$log.Add("device    : $(((Invoke-AdbCmd @("shell", "getprop", "ro.product.model")) -join '').Trim()) / Android $(((Invoke-AdbCmd @("shell", "getprop", "ro.build.version.release")) -join '').Trim()) (API $(((Invoke-AdbCmd @("shell", "getprop", "ro.build.version.sdk")) -join '').Trim()))")
$log.Add("awake     : $awake (screenOn=$screenOn)")
$log.Add("protocol  : cold start x3 (median TotalTime) -> idle 4s -> 3 slow swipes -> gfxinfo/meminfo")
$log.Add("")

if ($RequireAwake -and -not $awake) {
    Write-Host "FAIL: device is not awake (mWakefulness != Awake); wake and unlock it first."
    exit 3
}
if (-not $awake) {
    Write-Host "WARN: device is asleep - skipping launch-time and frame metrics (they would be 0/meaningless)."
}

$launchMs = $null
if ($awake -and -not $SkipDrive) {
    Write-Host "measuring launch time (3 cold starts) ..."
    $launchMs = Get-LaunchMs
    Write-Host "launch TotalTime median: $launchMs ms"
}

if (-not $SkipDrive -and $awake) {
    Write-Host "driving the app (scroll) ..."
    Invoke-AdbCmd @("shell", "am", "start", "-n", $activity) | Out-Null
    Start-Sleep -Seconds 8
    Invoke-AdbCmd @("shell", "dumpsys", "gfxinfo", $pkg, "reset") | Out-Null
    Start-Sleep -Seconds 4
    foreach ($i in 1..3) {
        Invoke-AdbCmd @("shell", "input", "swipe", "1068", "2000", "1068", "1200", "800") | Out-Null
        Start-Sleep -Milliseconds 400
    }
    Start-Sleep -Seconds 2
}

$gfx = (Invoke-AdbCmd @("shell", "dumpsys", "gfxinfo", $pkg)) -join "`n"
$mem = (Invoke-AdbCmd @("shell", "dumpsys", "meminfo", $pkg)) -join "`n"
$log.Add("## gfxinfo (raw)")
$log.Add($gfx)
$log.Add("")
$log.Add("## meminfo (raw)")
$log.Add($mem)

function Parse-Gfx {
    param([string]$Text)
    $total = 0; $janky = 0
    $pct = @{}
    foreach ($line in $Text -split "`n") {
        if ($line -match "Total frames rendered:\s*(\d+)") { $total += [int]$Matches[1] }
        if ($line -match "Janky frames:\s*(\d+)") { $janky += [int]$Matches[1] }
        if ($line -match "(\d+)th percentile:\s*(\d+)ms") { $pct[[int]$Matches[1]] = [int]$Matches[2] }
    }
    # Android reports the last histogram bucket (4950ms) when nothing was
    # rendered. Report nothing rather than a fake p50.
    $hasFrames = $total -gt 0
    [pscustomobject]@{
        Frames = $total; Janky = $janky; HasFrames = $hasFrames
        JankyPercent = if ($hasFrames) { [math]::Round(100.0 * $janky / $total, 2) } else { $null }
        P50 = if ($hasFrames) { $pct[50] } else { $null }
        P90 = if ($hasFrames) { $pct[90] } else { $null }
        P95 = if ($hasFrames) { $pct[95] } else { $null }
        P99 = if ($hasFrames) { $pct[99] } else { $null }
    }
}
function Parse-Mem {
    param([string]$Text)
    $javaHeap = $null; $totalPss = $null
    foreach ($line in $Text -split "`n") {
        if ($line -match "^\s*Java Heap:\s*(\d+)") { $javaHeap = [int]$Matches[1] }
        if ($line -match "TOTAL PSS:\s*(\d+)") { $totalPss = [int]$Matches[1] }
        elseif (-not $totalPss -and $line -match "^\s*TOTAL\s+(\d+)") { $totalPss = [int]$Matches[1] }
    }
    [pscustomobject]@{ JavaHeapKB = $javaHeap; TotalPssKB = $totalPss }
}

$g = Parse-Gfx $gfx
$m = Parse-Mem $mem

$summary = New-Object System.Collections.Generic.List[string]
$summary.Add("")
$summary.Add("## parsed")
$summary.Add("awake                 : $awake (screenOn=$screenOn)")
if ($null -ne $launchMs) {
    $summary.Add("cold start TotalTime  : $launchMs ms (3 runs, median)")
} else {
    $summary.Add("cold start TotalTime  : SKIPPED (device asleep or TotalTime=0)")
}
if ($g.HasFrames) {
    $summary.Add("frames                : $($g.Frames)")
    $summary.Add("janky frames          : $($g.Janky) ($($g.JankyPercent)%)")
    $summary.Add("frame time p50/p90/p95/p99 : $($g.P50) / $($g.P90) / $($g.P95) / $($g.P99) ms")
} else {
    $summary.Add("frames                : UNAVAILABLE (gfxinfo reports `"Total frames rendered: 0`")")
    $summary.Add("                        This device/OS does not feed the HWUI FrameMetrics counters even")
    $summary.Add("                        with the window focused and swiped (verified with `"framestats`" too),")
    $summary.Add("                        so janky% CANNOT be measured this way. Use a Perfetto trace or a")
    $summary.Add("                        Macrobenchmark module when frame-level numbers are needed; do NOT")
    $summary.Add("                        read this 0 as `"0% janky`".")
}
$summary.Add("java heap             : $($m.JavaHeapKB) KB")
$summary.Add("total PSS             : $($m.TotalPssKB) KB")
$summary.Add("")
$summary.Add("raw report            : $outFile")

$log.AddRange($summary)
Set-Content -Path $outFile -Value $log -Encoding utf8
$summary | ForEach-Object { Write-Host $_ }

if ($g.HasFrames -and $g.JankyPercent -gt $MaxJankyPercent) {
    Write-Host "FAIL: janky $($g.JankyPercent)% > allowed $MaxJankyPercent%"
    exit 2
}
Write-Host "OK"
exit 0


