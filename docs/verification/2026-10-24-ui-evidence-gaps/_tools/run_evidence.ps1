# task-9 evidence runs: IME regression + surface screenshots (light) and dark-theme snapshot phase.
# Runs on emulator-5556 (API36 / 1080x2400 / 420dpi). Run from the repository root.
$ErrorActionPreference = 'Continue'
$d = 'emulator-5556'
$root = 'D:\Devtools\Code\CodexWorkspace\PregnancyJournal'
$out = Join-Path $root 'docs\verification\2026-10-24-ui-evidence-gaps'
$tools = Join-Path $out '_tools'
$dev = '/sdcard/Android/data/cn.yunji.journal/files/verification'
Set-Location $root

function Run([string]$name, [string]$class) {
    Write-Host "=== RUN $name ($class) ==="
    $start = Get-Date
    adb -s $d shell "am instrument -w -r -e class $class cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 |
        Tee-Object -FilePath (Join-Path $tools "$name.log") | Select-Object -Last 6
    Write-Host "=== DONE $name in $([int]((Get-Date) - $start).TotalSeconds)s ==="
}

function Pull([string]$deviceSub, [string]$dest) {
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    adb -s $d pull "$dev/$deviceSub/." $dest 2>&1 | Select-Object -Last 1
}

Write-Host '--- device baseline before ---'
adb -s $d shell "settings get system font_scale; cmd uimode night; settings get global stay_on_while_plugged_in" |
    Out-File -Encoding utf8 (Join-Path $tools 'device-baseline-before.txt')

# ---------- light phase ----------
Write-Host '--- clear device screenshot dirs (before light phase) ---'
adb -s $d shell "find $dev -type f" 2>&1 | Out-File -Encoding utf8 (Join-Path $tools 'device-files-before-light.txt')
adb -s $d shell "rm -rf $dev/figma-ui-alignment $dev/figma-sync $dev/evidence-gaps"
adb -s $d shell "cmd uimode night no"
Start-Sleep -Seconds 2
adb -s $d shell "am force-stop cn.yunji.journal"

Run 'instrument-ime-inset' 'cn.yunji.journal.ImeInsetLayoutTest'
Pull 'evidence-gaps' (Join-Path $out 'ime')

Run 'instrument-coverage-light' 'cn.yunji.journal.UiSnapshotCoverageTest'
Pull 'figma-ui-alignment' (Join-Path $out 'light\coverage')

Run 'instrument-supplementary-light' 'cn.yunji.journal.UiSnapshotTest'
Pull 'figma-sync' (Join-Path $out 'light\supplementary')

# ---------- dark phase ----------
Write-Host '--- switch to dark theme ---'
adb -s $d shell "cmd uimode night yes"
Start-Sleep -Seconds 3
adb -s $d shell "am force-stop cn.yunji.journal"
adb -s $d shell "rm -rf $dev/figma-ui-alignment $dev/figma-sync"
Write-Host '--- device night mode now:'
adb -s $d shell "cmd uimode night"
adb -s $d shell "dumpsys activity top | grep -m1 -i 'mCurrentConfig'" 2>&1 | Out-Null

Run 'instrument-coverage-dark' 'cn.yunji.journal.UiSnapshotCoverageTest'
Pull 'figma-ui-alignment' (Join-Path $out 'dark\coverage')

Run 'instrument-supplementary-dark' 'cn.yunji.journal.UiSnapshotTest'
Pull 'figma-sync' (Join-Path $out 'dark\supplementary')

# ---------- restore light ----------
Write-Host '--- restore light theme ---'
adb -s $d shell "cmd uimode night no"
Start-Sleep -Seconds 2
adb -s $d shell "am force-stop cn.yunji.journal"
adb -s $d shell "am force-stop cn.yunji.journal.test"
adb -s $d shell "find $dev -type f" 2>&1 | Out-File -Encoding utf8 (Join-Path $tools 'device-files-after-dark.txt')
Write-Host '--- device state after ---'
adb -s $d shell "settings get system font_scale; cmd uimode night; settings get global stay_on_while_plugged_in" |
    Out-File -Encoding utf8 (Join-Path $tools 'device-baseline-after.txt')
Write-Host 'ALL EVIDENCE RUNS FINISHED'
