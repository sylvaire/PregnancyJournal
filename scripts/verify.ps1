param([switch]$Device, [switch]$UseInstalledGradle, [string]$Serial, [switch]$Offline)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $projectRoot
try {
    $tasks = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug')
    if ($Device -and [string]::IsNullOrWhiteSpace($Serial)) { throw '设备测试请用 -Serial 指定专用测试设备，避免向其他设备安装。' }
    if ($Device) { $tasks += ':app:assembleDebugAndroidTest' }
    $gradleCommand = if ($UseInstalledGradle) { (Get-Command gradle -ErrorAction Stop).Source } else { Join-Path $projectRoot 'gradlew.bat' }
    $gradleOptions = @('--gradle-user-home', (Join-Path $projectRoot '.gradle-user'), '--console', 'plain')
    if ($Offline) { $gradleOptions += '--offline' }
    & $gradleCommand @gradleOptions @tasks
    if ($LASTEXITCODE -ne 0) { throw "验证未通过（退出码 $LASTEXITCODE），请检查构建日志" }
    if ($Device) {
        $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
        $adb = Join-Path $sdkRoot 'platform-tools/adb.exe'
        foreach ($apk in @('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
            & $adb -s $Serial install -r $apk
            if ($LASTEXITCODE -ne 0) { throw "安装测试包失败：$apk" }
        }
        $deviceResult = & $adb -s $Serial shell am instrument -w -e notAnnotation androidx.test.filters.LargeTest cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner
        $deviceResult | Write-Output
        if ($LASTEXITCODE -ne 0 -or ($deviceResult -join "`n") -notmatch 'OK \([1-9][0-9]* tests?\)') {
            throw '设备测试未通过或未实际执行测试，请检查上方输出。'
        }
    }
} finally { Pop-Location }
