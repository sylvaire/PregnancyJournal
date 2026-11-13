# 2026-10-22 D06 云端清理界面状态与手动入口（task-5）验收证据

## 环境与基线

- 工程：`D:\Devtools\Code\CodexWorkspace\PregnancyJournal`（Android Kotlin/Compose，无 git 仓库）。
- 构建：`$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'`，`.\gradlew.bat <task> --console=plain`（守护进程热，未用 `--no-daemon`）。
- 设备：`emulator-5556`，AVD `PJ_API36_16K`（Android 16 / API 36，`ro.build.fingerprint=google/sdk_gphone16k_x86_64/emu64xa16k:16/BE2A.250530.026.F3/13894323:user/dev-keys`，PAGE_SIZE=16384，1080x2400 @420dpi）。
- 运行前设备基线（已 readback）：`font_scale=1.0`、`Night mode: no`、`accelerometer_rotation=1`、`user_rotation=0`、`mCurrentOrientation=0`、`ROTATION_0`、`wm size=1080x2400`；应用已 `am force-stop cn.yunji.journal`（跑后再次确认无进程）。
- 改动前基线：JVM 49 类 / 292 项 0 失败；lint 0 错误 / 35 警告。

## 命令与结果（代码 + JVM + lint）

| # | 命令 | 结果 | 退出码 | 日志 |
|---|---|---|---|---|
| 1 | `:app:compileDebugKotlin` | BUILD SUCCESSFUL in 19s | 0 | 终端输出（首次编译确认） |
| 2 | `:app:testDebugUnitTest` | BUILD SUCCESSFUL in 15s；**51 类 / 305 项 / 0 失败 / 0 错误 / 0 跳过**（基线 49/292，新增 2 类 13 项） | 0 | `jvm-full.log` |
| 3 | `:app:lintDebug` | BUILD SUCCESSFUL in 1m；**0 错误 / 35 警告**（与基线一致，未新增；新文件 0 issue） | 0 | `lint-debug.log`、`lint-results-debug.txt`、`lint-results-debug.xml` |
| 4 | `:app:compileDebugAndroidTestKotlin` | BUILD SUCCESSFUL in 5s | 0 | `androidtest-compile.log` |
| 5 | `.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=cn.yunji.journal.CloudCleanupUiTest" --console=plain` | 首跑 7 项 6 过 1 失败（测试断言歧义，非产品缺陷，见下）；修正后重跑 **BUILD SUCCESSFUL in 37s；7 项 0 失败 0 错误 0 跳过**（套件耗时 19.57s） | 0 | `device-targeted.log`、`junit-device-targeted.xml` |

JVM 结果取自 `app/build/test-results/testDebugUnitTest/*.xml` 聚合：classes=51、tests=305、failures=0、errors=0、skipped=0；本次新增两份 XML 已拷贝：
`TEST-cn.yunji.journal.CloudCleanupControllerTest.xml`（8 项）、`TEST-cn.yunji.journal.CloudCleanupStatusStoreTest.xml`（5 项）。

## 设备定向结果（7/7）

| 用例 | 断言要点 | 耗时(s) | 结果 |
|---|---|---|---|
| neverCleanedShowsHintAndManualEntryIsAvailable | 「尚未清理，备份成功后每天最多自动清理一次」+手动入口可用 | 6.019 | 通过 |
| manualCleanupRequiresConfirmationAndRunsOnce | 先出现确认框、确认前回调 0 次、文案完整、确认后恰 1 次、对话框消失 | 2.713 | 通过（修正后） |
| succeededAttemptShowsTimeCountsAndResultMessage | 「上次尝试：… · 清理完成」+计数行+结果消息 | 1.735 | 通过 |
| skippedAttemptExplainsWhyNothingWasDeleted | 「未清理原因：尚未保存恢复密钥，不能清理云端备份」 | 2.038 | 通过 |
| unverifiedConnectionDisablesManualEntryAndShowsReason | 连接未校验→按钮禁用+原因文案 | 1.709 | 通过 |
| otherDeviceBackupDisablesManualEntryAndShowsReason | 他设备备份→按钮禁用+原因文案 | 2.31 | 通过 |
| runningCleanupShowsProgressAndDisablesButton | 执行中→进度文案+按钮禁用 | 2.075 | 通过 |

设备跑完恢复：`device-config.ps1 -Mode baseline` 后 readback 为 `font_scale=1.0`、`Night mode: no`、`accelerometer_rotation=1`、`user_rotation=0`、`mCurrentOrientation=0`、`ROTATION_0`、`wm size=1080x2400`；见 `device-info.txt`。设备已归还（force-stop 后无应用进程）。

## 覆盖范围

### JVM（13 项新增）

- `CloudCleanupControllerTest`（8 项）：成功运行写入状态（计数/时间/消息/`running=false`/store 记录一致）；skipped 只记录原因且计数全 0；异常失败记录 `failed` 消息；重复点击只触发一次 `runOnce`；备份进行中不调用清理并给出「正在备份，完成后再清理」；离开页面取消在途任务且不写失败记录；`refresh` 重载持久记录；时间格式固定（`formatCleanupTime(1_700_000_000_000, Asia/Shanghai) == "2023-11-15 06:13"`）。
- `CloudCleanupStatusStoreTest`（5 项）：记录编解码往返；空存储回默认值且 `hasRecord=false`；坏数据回默认；失败/跳过记录保留原因且 `ran=false`；`CleanupOutcome → CleanupRecord` 映射含 4 个计数与消息截断（≤200 字符）。

### 设备定向（7 项）

见上表：状态展示（无记录/成功/跳过）、二次确认与只调用一次、执行中进度与防重复、两种禁用原因（连接未校验、他设备备份）。合成宿主直接渲染无状态 `CloudConnectionContent`，不访问网络。

## 未覆盖与已知限制

- 真实云盘服务商的 DELETE/last-modified 行为、真机断电/进程终止、多设备真实并发互斥、清理在途取消窗口仍未验证（属 D06 既有遗留，本次未改 `core/backup/**` 保留策略语义）。
- 互斥为“简单互斥”：`CloudCleanupRunner` 进程内 `AtomicBoolean` 防并发清理 + UI 在备份进行中/他设备备份时禁用 + controller 运行前再查 `CloudBackupAccess.isRunning()`；不构成跨进程硬锁。
- 无恢复密钥/无本机备份身份两种情况下按钮仍可点，点击后由 `runOnce` 返回原因并展示（与需求“禁用或点击后给出原因”的后者一致）。
- 自动清理的 `lastCleanupAt` 日节流语义未改动：自动路径仍是“上传成功且满 24 小时”，本次新增状态展示与手动入口不改变其门控。
- 清理小节的极端配置（字体 200%/深色/横屏）未做矩阵验证（属后续 task-7 范围）；设备用例均为常规基线配置。
- 界面时间格式为本机时区 `yyyy-MM-dd HH:mm`。
- 任务描述中的阶段记录路径为 `docs/阶段记录/M4-D06云端保留与清理.md`，工作区实际文件为 `docs/阶段记录/M4-D06远端保留与清理.md`，增量已追加到实际文件。

## 失败与修复记录

1. 首次 `:app:testDebugUnitTest` 在 `:app:compileDebugUnitTestKotlin` 失败：`e: … CloudCleanupControllerTest.kt:14:8 Unresolved reference 'ExperimentalCoroutinesApi'`、`Annotation argument must be a compile-time constant`。修复：`@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)` 全限定引用；重跑通过（51/305/0）。
2. 首次 `:app:compileDebugKotlin` 提示 `Redundant annotation target 'param'`（`CloudCleanupStatusStore.kt:43`）。修复：构造参数注解改为 `@ApplicationContext context: Context`（不带 `@param:` 目标）；lint 与后续编译无新增警告。
3. 设备首跑 `manualCleanupRequiresConfirmationAndRunsOnce` 失败：`Failed: assertExists. Expected exactly '1' node but found '2' nodes that satisfy: … contains '不删除本机资料' (substring)`——备份区块说明文字也包含该短语，属测试断言范围不清的测试缺陷，不是产品缺陷。修复：改为整段确认框文案精确匹配（不删除本机资料/历史资料库/其他设备目录/清理失败不影响已有备份），未放宽任何产品断言；重跑 7/7 通过。
4. 设备首跑前应用为基线状态、无残留进程；重跑前后均 readback 设备配置，未改动设备全局设置。

## 产物文件清单（本目录）

- `jvm-full.log`、`lint-debug.log`、`lint-results-debug.txt`、`lint-results-debug.xml`、`androidtest-compile.log`
- `TEST-cn.yunji.journal.CloudCleanupControllerTest.xml`、`TEST-cn.yunji.journal.CloudCleanupStatusStoreTest.xml`（JVM 新增 13 项）
- `device-targeted.log`（设备重跑，7/7 通过）、`junit-device-targeted.xml`（tests=7/failures=0/errors=0/skipped=0）、`device-info.txt`（设备/构建指纹/页大小/基线 readback）
