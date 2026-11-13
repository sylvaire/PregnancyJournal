# 设备极端配置设置/恢复脚本（A02/E01 遗留验收）
# 用法：
#   .\device-config.ps1 -Mode extreme   # 系统字体200% + 深色 + 横屏
#   .\device-config.ps1 -Mode baseline  # 恢复基线：字体100% + 浅色 + 竖屏 + 自动旋转
# 目标设备为 16KiB 页的 API36 模拟器 emulator-5556。
# 注意：API36 上仅写 settings 不会真正旋转；必须 cmd window user-rotation -d 0 lock 1
#（不带 -d 0 时该命令无效，界面仍是竖屏，assumeTrue 会导致测试跳过）。
param([Parameter(Mandatory = $true)][ValidateSet('extreme', 'baseline')][string]$Mode)

Write-Output "MODE=$Mode"

$adb = 'D:\Android\Sdk\platform-tools\adb.exe'
$serial = 'emulator-5556'
function A([string[]]$args_) { & $adb -s $serial @args_ }

if ($Mode -eq 'extreme') {
    A @('shell', 'settings', 'put', 'system', 'font_scale', '2.0')
    A @('shell', 'cmd', 'uimode', 'night', 'yes')
    A @('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
    A @('shell', 'settings', 'put', 'system', 'user_rotation', '1')
    A @('shell', 'cmd', 'window', 'user-rotation', '-d', '0', 'lock', '1')
} else {
    A @('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    A @('shell', 'cmd', 'uimode', 'night', 'no')
    A @('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '1')
    A @('shell', 'settings', 'put', 'system', 'user_rotation', '0')
    A @('shell', 'cmd', 'window', 'user-rotation', '-d', '0', 'free')
}

# 读回确认
"font_scale=$(& $adb -s $serial shell settings get system font_scale)".Trim()
"$(& $adb -s $serial shell cmd uimode night)".Trim()
"accelerometer_rotation=$(& $adb -s $serial shell settings get system accelerometer_rotation)".Trim()
"user_rotation=$(& $adb -s $serial shell settings get system user_rotation)".Trim()
"$(& $adb -s $serial shell dumpsys display | grep -m1 mCurrentOrientation)".Trim()
"$(& $adb -s $serial shell dumpsys window | grep -m1 mGlobalConfiguration)".Trim()