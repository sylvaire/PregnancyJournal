param([string]$Name)
$dev = "<设备>:<端口>   # 运行前填入"
$dir = "docs\verification\2026-10-24-device-visual-round16\shots"
New-Item -ItemType Directory -Force $dir | Out-Null
adb -s $dev shell screencap -p /sdcard/vshot.png 2>&1 | Out-Null
adb -s $dev pull /sdcard/vshot.png "$dir\$Name.png" 2>&1 | Out-Null
$size = (Get-Item "$dir\$Name.png").Length
"saved $Name.png ($size bytes)"
