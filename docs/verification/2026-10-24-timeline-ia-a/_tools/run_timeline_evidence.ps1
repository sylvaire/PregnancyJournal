# task-12 evidence runs: timeline IA verification on emulator-5556 (light) + extreme-config pages matrix.
$ErrorActionPreference = 'Continue'
$d = 'emulator-5556'
$root = 'D:\Devtools\Code\CodexWorkspace\PregnancyJournal'
$out = Join-Path $root 'docs\verification\2026-10-24-timeline-ia-a'
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

Write-Host '--- device state before ---'
adb -s $d shell "settings get system font_scale; cmd uimode night; settings get system accelerometer_rotation; settings get system user_rotation; settings get global stay_on_while_plugged_in" |
    Out-File -Encoding utf8 (Join-Path $tools 'device-baseline-before.txt')

Write-Host '--- install APKs ---'
adb -s $d install -r -g 'app\build\outputs\apk\debug\app-debug.apk' 2>&1 | Tee-Object -FilePath (Join-Path $tools 'install.log')
adb -s $d install -r -g -t 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk' 2>&1 |
    Tee-Object -FilePath (Join-Path $tools 'install.log') -Append

Write-Host '--- force light/normal, clear old screenshots ---'
adb -s $d shell "cmd uimode night no"
adb -s $d shell "settings put system font_scale 1.0"
adb -s $d shell "settings put global stay_on_while_plugged_in 0"
adb -s $d shell "settings put system accelerometer_rotation 1"
adb -s $d shell "settings put system user_rotation 0"
Start-Sleep -Seconds 2
adb -s $d shell "am force-stop cn.yunji.journal"
adb -s $d shell "find $dev -type f" 2>&1 | Out-File -Encoding utf8 (Join-Path $tools 'device-files-before.txt')
adb -s $d shell "rm -rf $dev/figma-ui-alignment $dev/figma-sync $dev/pages-matrix"
adb -s $d shell "rm -f $dev/c04-timeline.png $dev/c04-journal.png"

Run 'instrument-navigation-light' 'cn.yunji.journal.NavigationExperienceTest'
New-Item -ItemType Directory -Force -Path (Join-Path $out 'after\navigation') | Out-Null
adb -s $d pull "$dev/c04-timeline.png" (Join-Path $out 'after\navigation') 2>&1 | Select-Object -Last 1
adb -s $d pull "$dev/c04-journal.png" (Join-Path $out 'after\navigation') 2>&1 | Select-Object -Last 1

Run 'instrument-uistatematrix-light' 'cn.yunji.journal.UiStateMatrixTest'

Run 'instrument-coverage-light' 'cn.yunji.journal.UiSnapshotCoverageTest'
New-Item -ItemType Directory -Force -Path (Join-Path $out 'after\coverage') | Out-Null
adb -s $d pull "$dev/figma-ui-alignment/." (Join-Path $out 'after\coverage')

Run 'instrument-supplementary-light' 'cn.yunji.journal.UiSnapshotTest'
New-Item -ItemType Directory -Force -Path (Join-Path $out 'after\supplementary') | Out-Null
adb -s $d pull "$dev/figma-sync/." (Join-Path $out 'after\supplementary')

Write-Host '--- extreme config (font 200% + dark + landscape) for ExtremeLayoutMatrixPagesTest ---'
adb -s $d shell "settings put system font_scale 2.0"
adb -s $d shell "cmd uimode night yes"
adb -s $d shell "settings put system accelerometer_rotation 0"
adb -s $d shell "settings put system user_rotation 1"
Start-Sleep -Seconds 4
adb -s $d shell "am force-stop cn.yunji.journal"
Run 'instrument-extreme-pages' 'cn.yunji.journal.ExtremeLayoutMatrixPagesTest'
New-Item -ItemType Directory -Force -Path (Join-Path $out 'after\extreme-pages') | Out-Null
adb -s $d pull "$dev/pages-matrix/." (Join-Path $out 'after\extreme-pages')

Write-Host '--- restore baseline ---'
adb -s $d shell "settings put system font_scale 1.0"
adb -s $d shell "cmd uimode night no"
adb -s $d shell "settings put system accelerometer_rotation 1"
adb -s $d shell "settings put system user_rotation 0"
adb -s $d shell "settings put global stay_on_while_plugged_in 0"
Start-Sleep -Seconds 2
adb -s $d shell "am force-stop cn.yunji.journal"
adb -s $d shell "am force-stop cn.yunji.journal.test"
adb -s $d shell "find $dev -type f" 2>&1 | Out-File -Encoding utf8 (Join-Path $tools 'device-files-after.txt')
Write-Host '--- device state after ---'
adb -s $d shell "settings get system font_scale; cmd uimode night; settings get system accelerometer_rotation; settings get system user_rotation; settings get global stay_on_while_plugged_in" |
    Out-File -Encoding utf8 (Join-Path $tools 'device-baseline-after.txt')
Write-Host 'ALL TIMELINE EVIDENCE RUNS FINISHED'
