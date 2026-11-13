# 0.1.0-dev 验证证据

日期：2026-10-18，上海时区。仅为V1.0开发过程中的首个可运行增量。

本目录归档0.1.0-dev的结果。当前构建目录已被0.2.0-dev替换，最新安装包与验收见[M2证据](../2026-10-18-M2/README.md)；本目录中的指纹属于当时的0.1版，不与当前APK混用。

## 可复现检查

在项目根目录、专用模拟器已启动且依赖缓存齐备时运行：

```powershell
.\scripts\verify.ps1 -UseInstalledGradle -Offline -Device -Serial emulator-5580
```

首次联网构建去掉 `-Offline`；普通环境用项目Wrapper时去掉 `-UseInstalledGradle`。设备参数必须明确指定，避免安装至其他设备。脚本编译应用/测试包，运行单元测试及lint，然后安装到指定设备，通过AndroidJUnitRunner执行设备测试；不依赖Gradle额外UTP工具下载。

## 结果

| 检查 | 实际结果 | 证据 |
|---|---|---|
| debug / 测试APK构建 | 成功 | [构建与设备测试](构建与设备测试.txt) |
| JVM单元测试 | 12项通过，0失败 | 同目录4份 `TEST-*.xml` |
| API34加密库设备测试 | 2项通过 | 构建与设备测试末尾 `OK (2 tests)` |
| lintDebug | 0错误，14警告 | [静态检查](静态检查.txt)，均为依赖升级提示 |
| 建档、首页、预约持久化 | 实际操作通过 | [首页](首页-开发版.png)、[产检](产检-开发版.png) |
| 预约草稿恢复 | 输入虚构项目后返回、强制停止、重启后恢复成功 | [恢复后的界面树](预约草稿恢复.xml)，测试字段为SmokeVisit |
| 完成产检 | 点击确认今日已就诊后列表显示已完成 | 产检截图 |
| 小米14 Pro / Android16 | 无线安装、启动、2项加密库测试通过；修复并更新日期边框颜色，升级后草稿恢复通过 | [真机阶段记录](../../阶段记录/M1-小米真机调试.md)、[真机数据库测试](小米真机数据库测试.txt)、[修复构建](小米修复构建.txt) |

设备：项目专用API34 x86_64模拟器，端口5580。截图为浅色、1080×1920；先前也检查过较长屏幕及320×640小屏滚动。未完成所有屏幕、深色和字体放大状态的验收。设备时区为UTC，截图日期为10月17日，应用按设备本地时间显示。

## 安装包与限制

路径：`app/build/outputs/apk/debug/app-debug.apk`，版本 `0.1.0-dev`，debug签名。最终文件指纹见[安装包信息](安装包信息.txt)。APK在构建目录，下次构建可能替换。

当前APK为小米真机检查后日期边框颜色修复版本；原模拟器截图和首轮构建证据仍保留，新增修复构建/真机测试单独记录。真机界面涉及用户输入，未归档其截图或字段值。

覆盖档案、首页、基础预约、加密草稿。完整资料导入、用药、健康指标、提醒、应用锁、备份与恢复尚未交付；P0/M1均未全部退出。API26、16KB页、真实坚果云、新设备恢复、性能统计与正式签名发布尚未验收。

## 继续测试

本轮结束已关闭专用模拟器及Gradle后台进程。项目本地AVD仍保留在 `.local/avd`，虚构测试记录可继续使用。已安装SDK和镜像的当前机器可在项目根目录重新启动：

```powershell
$env:ANDROID_AVD_HOME = Join-Path $PWD '.local/avd'
$env:ANDROID_USER_HOME = Join-Path $PWD '.local/android'
Start-Process -FilePath "$env:ANDROID_HOME/emulator/emulator.exe" -ArgumentList '-avd PregnancyJournal_API34 -port 5580 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect' -WindowStyle Hidden
```

启动完成后运行上面的指定设备验证命令。`.local`及缓存不属于可移植工程文件；新机器应创建自己的专用测试AVD。当前目录没有Git仓库，本轮文件已落盘但没有Git提交。
