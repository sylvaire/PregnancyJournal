# Runs the instrumented screenshot/regression suites on emulator-5556 for task-8 (P0-4 header fix)
# and pulls the evidence into docs/verification/2026-10-24-ui-header-fix/. Run from repo root.
$ErrorActionPreference = 'Continue'
$d = 'emulator-5556'
$root = 'D:\Devtools\Code\CodexWorkspace\PregnancyJournal'
$out = Join-Path $root 'docs\verification\2026-10-24-ui-header-fix'
$dev = '/sdcard/Android/data/cn.yunji.journal/files/verification'
Set-Location $root

function Run([string]$name, [string]$class) {
    Write-Host "=== RUN $name ($class) ==="
    $start = Get-Date
    adb -s $d shell "am instrument -w -r -e class $class cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 |
        Tee-Object -FilePath (Join-Path $out "_tools\$name.log") | Select-Object -Last 8
    Write-Host "=== DONE $name in $([int]((Get-Date) - $start).TotalSeconds)s ==="
}

Write-Host '--- install APKs ---'
adb -s $d install -r -g 'app\build\outputs\apk\debug\app-debug.apk' 2>&1 | Tee-Object -FilePath (Join-Path $out '_tools\install.log')
adb -s $d install -r -g -t 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk' 2>&1 |
    Tee-Object -FilePath (Join-Path $out '_tools\install.log') -Append

Run 'instrument-coverage' 'cn.yunji.journal.UiSnapshotCoverageTest'
New-Item -ItemType Directory -Force -Path (Join-Path $out 'after') | Out-Null
adb -s $d pull "$dev/figma-ui-alignment/." (Join-Path $out 'after')

Run 'instrument-supplementary' 'cn.yunji.journal.UiSnapshotTest'
New-Item -ItemType Directory -Force -Path (Join-Path $out 'after-supplementary') | Out-Null
adb -s $d pull "$dev/figma-sync/." (Join-Path $out 'after-supplementary')

Run 'instrument-navigation' 'cn.yunji.journal.NavigationExperienceTest'

Write-Host '--- pull navigation screenshots (verification root) ---'
New-Item -ItemType Directory -Force -Path (Join-Path $out 'navigation') | Out-Null
adb -s $d shell "ls $dev/*.png" | ForEach-Object {
    $p = $_.Trim()
    if ($p) { adb -s $d pull $p (Join-Path $out 'navigation') | Out-Null }
}

Write-Host '--- device verification dir after ---'
adb -s $d shell "find $dev -type f" 2>&1 | Out-File -Encoding utf8 (Join-Path $out '_tools\device-verification-after.txt')
Write-Host 'ALL INSTRUMENT RUNS FINISHED'
