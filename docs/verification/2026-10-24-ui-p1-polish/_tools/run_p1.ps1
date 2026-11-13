# task-10 evidence runs: light regression snapshots (Switch + empty states) and dark surface snapshots.
# Runs on emulator-5556 (API36 / 1080x2400 / 420dpi). Run from the repository root.
$ErrorActionPreference = 'Continue'
$d = 'emulator-5556'
$root = 'D:\Devtools\Code\CodexWorkspace\PregnancyJournal'
$out = Join-Path $root 'docs\verification\2026-10-24-ui-p1-polish'
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

Write-Host '--- device state before ---'
adb -s $d shell "settings get system font_scale; cmd uimode night; settings get global stay_on_while_plugged_in" |
    Out-File -Encoding utf8 (Join-Path $tools 'device-baseline-before.txt')

Write-Host '--- install APKs ---'
adb -s $d install -r -g 'app\build\outputs\apk\debug\app-debug.apk' 2>&1 | Tee-Object -FilePath (Join-Path $tools 'install.log')
adb -s $d install -r -g -t 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk' 2>&1 |
    Tee-Object -FilePath (Join-Path $tools 'install.log') -Append

Write-Host '--- force light, clear old screenshots ---'
adb -s $d shell "cmd uimode night no"
adb -s $d shell "settings put system font_scale 1.0"
adb -s $d shell "settings put global stay_on_while_plugged_in 0"
Start-Sleep -Seconds 2
adb -s $d shell "am force-stop cn.yunji.journal"
adb -s $d shell "find $dev -type f" 2>&1 | Out-File -Encoding utf8 (Join-Path $tools 'device-files-before.txt')
adb -s $d shell "rm -rf $dev/figma-ui-alignment $dev/figma-sync $dev/evidence-gaps"

Run 'instrument-coverage-light' 'cn.yunji.journal.UiSnapshotCoverageTest'
Pull 'figma-ui-alignment' (Join-Path $out 'light\coverage')

Run 'instrument-supplementary-light' 'cn.yunji.journal.UiSnapshotTest'
Pull 'figma-sync' (Join-Path $out 'light\supplementary')

Run 'instrument-surfaces-light' 'cn.yunji.journal.UiSurfaceSnapshotTest'
Pull 'evidence-gaps' (Join-Path $out 'light\surfaces')

Run 'instrument-navigation-light' 'cn.yunji.journal.NavigationExperienceTest'

Write-Host '--- switch to dark theme for surface evidence ---'
adb -s $d shell "cmd uimode night yes"
Start-Sleep -Seconds 3
adb -s $d shell "am force-stop cn.yunji.journal"
adb -s $d shell "rm -rf $dev/evidence-gaps"

Run 'instrument-surfaces-dark' 'cn.yunji.journal.UiSurfaceSnapshotTest'
Pull 'evidence-gaps' (Join-Path $out 'dark-surfaces')

Write-Host '--- restore light baseline ---'
adb -s $d shell "cmd uimode night no"
Start-Sleep -Seconds 2
adb -s $d shell "settings put system font_scale 1.0"
adb -s $d shell "settings put global stay_on_while_plugged_in 0"
adb -s $d shell "settings put system accelerometer_rotation 1"
adb -s $d shell "settings put system user_rotation 0"
adb -s $d shell "am force-stop cn.yunji.journal"
adb -s $d shell "am force-stop cn.yunji.journal.test"
adb -s $d shell "find $dev -type f" 2>&1 | Out-File -Encoding utf8 (Join-Path $tools 'device-files-after.txt')
Write-Host '--- device state after ---'
adb -s $d shell "settings get system font_scale; cmd uimode night; settings get global stay_on_while_plugged_in" |
    Out-File -Encoding utf8 (Join-Path $tools 'device-baseline-after.txt')
Write-Host 'ALL P1 EVIDENCE RUNS FINISHED'
