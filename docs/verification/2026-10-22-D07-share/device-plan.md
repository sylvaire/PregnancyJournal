# 设备端定向执行计划（task-4 D07 单份原件分享）

复核时间：2026-10-22 14:16（+08:00）。状态：**待设备空闲**（emulator-5556 正被 ui-matrix 的 A02 极端配置矩阵 run3 占用，见 `docs/verification/2026-10-22-A02-ui-matrix/device-matrix-run3.log`，最近一条为 `ExtremeLayoutMatrixTest` 4/8、1 failed）。

## 前置检查（跑前逐条确认）

1. `adb devices` 只有 `emulator-5556 device`，且 `adb -s emulator-5556 shell ps | Select-String yunji` 无残留测试进程。
2. 无并行 Gradle：`Get-Process java | Where-Object { $_.StartTime -gt (Get-Date).AddMinutes(-10) }` 为空，或 Lead 明确说 A02 已全部结束。
3. 串行执行：本命令与 task-3 的设备命令**不能同时跑**。

## 设备事实

- 模拟器：`PJ_API36_16K(AVD)`，Android SDK 36，x86_64，页大小 16384（16 KiB）。
- 包名：`cn.yunji.journal`；provider 在清单里的 authority 为 `${applicationId}.share` → 实际 `cn.yunji.journal.share`。

## 命令（原文）

```powershell
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=cn.yunji.journal.OriginalShareDeviceTest" --console=plain
```

- 用例数：**7 项**（`providerIsRegisteredNonExportedWithGrantOnly`、`onlyTheShareDirectoryIsReachableThroughTheProvider`、`shareIntentCarriesExactlyOneReadOnlyStream`、`launchPassesExactlyThePublishedInvocation`、`returningRevokesThenDeletesTheTemporaryCopy`、`residualPlaintextIsClearedWhenTheScreenStarts`、`shareNamesNeverEscapeTheShareDirectory`）。
- 通过线：7 项全过、0 失败、0 跳过。
- 首次运行若报 `ClassNotFoundException` / 测试 APK 与主 APK 不同步，先 `adb uninstall cn.yunji.journal` 再重跑，**不要改测试断言**。

## 证据采集清单

1. 命令原文 + 开始/结束时间 + 退出码 → `device-targeted.log`（`Tee-Object`）。
2. 设备信息（SDK/型号/页大小）→ `device-info.txt`。
3. JUnit XML：`app/build/outputs/androidTest-results/connected/debug/**/TEST-*.xml` 中 `OriginalShareDeviceTest` → 本目录 `junit-device/`，并核对 `tests=/failures=/skipped=`。
4. UTP 日志：`utp.0.log`、`testlog/test-results.log` → `device-utp.log`。
5. 失败时：`adb -s emulator-5556 logcat -d -s TestRunner:* AndroidJUnitRunner:* AndroidRuntime:*` + 截图 → `device-failure-*`。

## 通过后仍不覆盖（如实保留）

- 真机 OEM / API 26 / 数百 MiB 原件与低内存；系统分享面板（chooser）真实交互（用例注入 launcher 断言 intent 与 flags，不拉起真实面板）；跨应用授权撤销的真实可见性（同 UID 限制）；分享进行中进程被杀的残留清理（仅靠下次进入页面 `clearResidual` 兜底）。

## 可测性风险（已识别）

1. 测试进程与应用同 UID，无法观察**另一个应用**在 revoke 后的读取失败；当前用「发布返回值 + 文件删除 + URI 不可读」三条断言近似，真实跨应用撤销需人工/双应用验证。
2. `shareIntentCarriesExactlyOneReadOnlyStream` 依赖 `FLAG_GRANT_READ_URI_PERMISSION` 且**无** `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`；若系统版本行为变化需先看日志再判断。
3. 分享目录清理断言依赖 `filesDir/share` 路径；若产品后续改目录，测试 `ShareTemporaryFiles` 常量会同步，不需要宽松化断言。
