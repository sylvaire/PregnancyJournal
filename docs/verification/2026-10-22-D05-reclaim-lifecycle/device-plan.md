# 设备端定向执行计划（task-3 D05 确认回收与再次恢复）

复核时间：2026-10-22 14:16（+08:00）。状态：**待设备空闲**（emulator-5556 正被 ui-matrix 的 A02 极端配置矩阵 run3 占用，见 `docs/verification/2026-10-22-A02-ui-matrix/device-matrix-run3.log`，最近一条为 `ExtremeLayoutMatrixTest` 4/8、1 failed）。

## 前置检查（跑前逐条确认）

1. `adb devices` 只有 `emulator-5556 device`，且 `adb -s emulator-5556 shell ps | Select-String yunji` 无残留测试进程。
2. 无并行 Gradle：`Get-Process java | Where-Object { $_.StartTime -gt (Get-Date).AddMinutes(-10) }` 为空，或 Lead 明确说 A02 已全部结束。
3. `.\gradlew.bat --status` 里没有活跃的 connected 构建。
4. 串行执行：本命令与 task-4 的设备命令**不能同时跑**。

## 设备事实

- 模拟器：`PJ_API36_16K(AVD)`，Android SDK 36，x86_64，页大小 16384（16 KiB）。
- 包名：`cn.yunji.journal`（debug 使用 debug 签名，无 applicationIdSuffix）。

## 命令（原文）

```powershell
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=cn.yunji.journal.RecoveryUiTest,cn.yunji.journal.LocalRestoreCloudTest" --console=plain
```

- 两个类的用例数：`RecoveryUiTest` 6 项（2 旧 + 3 UI 新：`reclaimPanelIsHiddenWhenNoPreviousGenerationExists`、`reclaimNeedsExplicitConfirmationAndCanBeCancelled`、`reclaimActionIsDisabledWhileCoveredOrBusy` + 1 生产接线集成 `productionReclaimPanelReclaimsPreviousGenerationThroughRealVault`）；`LocalRestoreCloudTest` 4 项（3 旧 + 1 新：`reclaimsTheConfirmedGenerationThenRestoresAgain`）。
- 合计 **10 项**（其中新增 5 项）；**0 失败**为通过线。若有 skip 必须逐个查明原因并记录，不得当作通过。
- 跑设备前必须先执行一次真实（非 UP-TO-DATE）`:app:compileDebugAndroidTestKotlin`，确认新增集成用例编译通过。
- 若只想跑新增用例，可用逗号+井号形式（备用，不推荐），例如：`-Pandroid.testInstrumentationRunnerArguments.class=cn.yunji.journal.RecoveryUiTest#reclaimPanelIsHiddenWhenNoPreviousGenerationExists,...`。

## 证据采集清单

跑完后立即（同一条 pwsh 命令里）采集：

1. 命令原文 + 开始/结束时间 + 退出码，写入 `device-targeted.log`（`Tee-Object`）。
2. 设备信息：`adb -s emulator-5556 shell "getprop ro.build.version.sdk; getprop ro.product.model; getconf PAGE_SIZE"` → `device-info.txt`。
3. JUnit XML：`app/build/outputs/androidTest-results/connected/debug/**/TEST-*.xml` → 本目录 `junit-device/`（只保留本命令两个类的 XML；其余类若被重跑生成需注明不属于本证据）。
4. UTP 日志：`app/build/outputs/androidTest-results/connected/debug/**/utp.0.log`、`testlog/test-results.log` → `device-utp.log`。
5. 失败时：过滤 logcat（`adb -s emulator-5556 logcat -d -s TestRunner:* AndroidJUnitRunner:* AndroidRuntime:*`）+ 截图到 `device-failure-*`。
6. 结果数字回填 README「待执行（设备端）」段、`jvm-summary.txt` 同目录的 `device-summary.txt`，并追加 `docs/阶段记录/M4-D05恢复向导与切换.md`。

## 通过后仍不覆盖（如实保留）

- 真实进程终止/断电（本用例用 `Runtime` 关闭与重开数据库模拟生命周期，不是真实 kill）；严格模式系统认证完整往返；真机 OEM/A02 矩阵；真实云盘服务商与真机取消 UI；数百 MiB 低内存；API 26。

## 可测性风险（已识别）

1. 设备 UI 用例只直接组合 `ReclaimPreviousContent`，未覆盖生产 `ReclaimPreviousPanel()`（真实 `RestoreReclaim` + `ON_RESUME` + `context.noBackupFilesDir` 接线）。若需闭环，可另加一个仪器集成测试（需新增代码并重新编译 androidTest），当前不在本次范围内，等待 Lead 决定。
2. `LocalRestoreCloudTest` 的 TLS 假 DAV 依赖 loopback + `HTTP_1_1`，设备端网络/代理异常会影响结果；失败时先区分环境问题与产品缺陷。
3. 上次设备运行出现过测试 APK 与主 APK 版本不同步导致的 `ClassNotFoundException`；若复现，先 `adb uninstall cn.yunji.journal` 再重跑，不要改测试断言。
