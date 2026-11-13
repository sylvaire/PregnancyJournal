param(
    [string]$Adb = 'D:/Android/Sdk/platform-tools/adb.exe',
    [string]$Serial = 'emulator-5556'
)
$ErrorActionPreference = 'Stop'
$app = 'cn.yunji.journal'
$runner = 'cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner'
$testClass = 'cn.yunji.journal.D02ProcessMatrixTest'
$folder = Split-Path -Parent $MyInvocation.MyCommand.Path
$phase1Log = Join-Path $folder 'orphan-phase1.log'
$phase1Error = Join-Path $folder 'orphan-phase1-stderr.log'
$phase2Log = Join-Path $folder 'orphan-phase2.log'
$marker = 'files/task8-markers/orphan.marker'

$model = (& $Adb -s $Serial shell getprop ro.product.model).Trim()
if ($LASTEXITCODE -ne 0 -or $model -ne 'sdk_gphone16k_x86_64') {
    throw "仅允许专用 API36/16KiB 模拟器；实际型号：$model"
}
& $Adb -s $Serial shell am force-stop $app | Out-Null
& $Adb -s $Serial shell run-as $app rm -f $marker | Out-Null
$phase1 = Start-Process -FilePath $Adb -ArgumentList @(
    '-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r',
    '-e', 'class', "$testClass#phase1OrphanArtifactsCreated",
    '-e', 'd02Phase', 'orphan', $runner
) -WindowStyle Hidden -RedirectStandardOutput $phase1Log -RedirectStandardError $phase1Error -PassThru
try {
    $seen = $false
    for ($attempt = 0; $attempt -lt 180; $attempt++) {
        $contents = (& $Adb -s $Serial shell run-as $app cat $marker 2>$null) -join "`n"
        if ($contents -match 'phase=orphan') { $seen = $true; break }
        if ($phase1.HasExited) { break }
        Start-Sleep -Seconds 1
    }
    if (-not $seen) { throw '阶段 1 未创建孤儿标记' }
    & $Adb -s $Serial shell am force-stop $app | Out-Null
    $when = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    & $Adb -s $Serial shell "run-as $app sh -c 'echo forceStoppedAt=$when >> $marker'" | Out-Null
    $contents = (& $Adb -s $Serial shell run-as $app cat $marker) -join "`n"
    if ($contents -notmatch 'forceStoppedAt=') { throw 'force-stop 后标记未写入' }
} finally {
    if (-not $phase1.HasExited) { Stop-Process -Id $phase1.Id -Force }
}

$result = & $Adb -s $Serial shell am instrument -w -r -e class "$testClass#phase2ColdStartCleansPrivatePlaintextWorkspaces" -e d02Phase orphan $runner
$result | Set-Content -LiteralPath $phase2Log -Encoding utf8
$result | Select-Object -Last 8
$report = $result -join "`n"
if ($report -notmatch 'OK \(1 test\)' -or $report -notmatch 'INSTRUMENTATION_STATUS_CODE: 0' -or
    $report -match 'INSTRUMENTATION_STATUS_CODE: -4') {
    throw '新进程孤儿目录清理验证未通过或被前置条件跳过'
}
