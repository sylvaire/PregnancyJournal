param(
    [ValidateSet('extreme', 'baseline')]
    [string]$Mode = 'extreme',
    [string]$ClassFilter = 'cn.yunji.journal.ExtremeLayoutMatrixPagesTest,cn.yunji.journal.UiStateMatrixTest'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$env:GRADLE_USER_HOME = Join-Path $root '.gradle-user'
$adb = 'D:\Android\Sdk\platform-tools\adb.exe'
$serial = 'emulator-5556'
$configScript = Join-Path $root 'docs\verification\2026-10-22-A02-ui-matrix\device-config.ps1'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $PSScriptRoot "device-pages-matrix-$stamp.log"

function Invoke-Step([string]$title, [scriptblock]$block) {
    Write-Host "==> $title"
    (& $block) 2>&1 | Tee-Object -FilePath $log -Append | Out-Host
}

Write-Host "mode=$Mode classFilter=$ClassFilter log=$log"
if ($Mode -ne 'baseline') {
    Invoke-Step 'apply extreme device configuration (A02 device-config.ps1)' { & powershell -NoProfile -ExecutionPolicy Bypass -File $configScript -Mode $Mode }
}
$gradleArgs = @(':app:connectedDebugAndroidTest', "-Pandroid.testInstrumentationRunnerArguments.class=$ClassFilter", '--console=plain')
$exit = 0
try {
    Invoke-Step 'run connectedDebugAndroidTest (page matrix)' {
        & (Join-Path $root 'gradlew.bat') @gradleArgs
        $script:exit = $LASTEXITCODE
    }
}
finally {
    Invoke-Step 'force-stop app' { & $adb -s $serial shell am force-stop cn.yunji.journal }
    $deviceDir = '/sdcard/Android/data/cn.yunji.journal/files/verification/pages-matrix'
    $localShots = Join-Path $PSScriptRoot 'screenshots'
    New-Item -ItemType Directory -Force $localShots | Out-Null
    Invoke-Step 'pull matrix screenshots' { & $adb -s $serial pull $deviceDir $localShots }
    $xmlDir = Join-Path $root 'app\build\outputs\androidTest-results\connected\debug'
    if (Test-Path $xmlDir) {
        Get-ChildItem $xmlDir -Filter '*.xml' | ForEach-Object {
            Copy-Item $_.FullName (Join-Path $PSScriptRoot ("junit-pages-$($_.Name)"))
        }
    }
    Invoke-Step 'restore baseline device configuration' { & powershell -NoProfile -ExecutionPolicy Bypass -File $configScript -Mode baseline }
    Invoke-Step 'readback device baseline' {
        & $adb -s $serial shell settings get system font_scale
        & $adb -s $serial shell cmd uimode night
        & $adb -s $serial shell settings get system accelerometer_rotation
        & $adb -s $serial shell settings get system user_rotation
    }
}
Write-Host "EXITCODE=$exit"
exit $exit
