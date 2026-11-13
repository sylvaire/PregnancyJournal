# 2026-10-22 A03：锁定入口、进程恢复与完整状态矩阵（task-6）

## 背景与目标

A03 要验证「锁定入口覆盖 + 进程/后台恢复 + 完整状态矩阵」，同时承接 D02「保存中重建/物理旋转/实际进程终止」与 D05「真实进程终止后状态恢复」两个遗留项。本轮把可复现的部分做成：生产代码抽取的纯函数 JVM 测试 + 模拟器上的真实进程终止与 UI 入口矩阵。真断电、低内存真机、OEM 后台限制仍然无法在本环境验证，列在「未覆盖」一节。

## 环境

| 项 | 值 |
|---|---|
| 设备 | `emulator-5556`，`PJ_API36_16K(AVD) - 16` |
| API / 型号 / ABI | 36 / `sdk_gphone16k_x86_64` / `x86_64`，16 KiB 页（`dalvik.vm.page_size=16384`） |
| 显示 | 1080x2400、420 dpi、portrait、font_scale=1.0、night=no、`user_rotation=0`、`accelerometer_rotation=1` |
| JDK / 构建 | `$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'`，`.\gradlew.bat ... --console=plain` |
| APK | `app-debug` + `app-debug-androidTest`（debug signing，applicationId `cn.yunji.journal`） |

## 生产改动（3 改 1 增）

1. 新增 `app/src/main/java/cn/yunji/journal/core/security/AccessPolicy.kt`：把「打开会话 / 解锁 / 进入严格模式」的判定抽成纯 Kotlin 函数（输入 `lockEnabled`、`unlockedUntil`、`strictLockMode`、`authenticators`、`nowMillis`，输出 `ALLOW / UNLOCK / REQUIRE_AUTH`），不依赖 `Context`、`SharedPreferences` 或 Android API。
2. `app/src/main/java/cn/yunji/journal/core/security/AccessState.kt`：原判定逻辑改为委托 `AccessPolicy`，行为保持不变（`AccessState` 仍负责持久化 `unlockedUntil` 与 UI 通知）。
3. `app/src/main/java/cn/yunji/journal/core/security/DeviceKeyStore.kt`：为 `AccessPolicy` 提供 authenticator 类型信息（只读查询，无行为变化）。
4. `app/src/main/java/cn/yunji/journal/data/SecurityStore.kt`：暴露 `lockEnabled` / `strictLockMode` 读取入口，供 `AccessPolicy` 与 `AccessState` 共用。

## 命令与结果（原文）

### 1) androidTest 编译

```
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'
.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain
```

结果：`BUILD SUCCESSFUL in 2s`。仅有 `AccessibilityNodeInfo.recycle` 弃用警告（androidTest 内，lint 不计）。

### 2) JVM 全量（先删旧 XML，避免 stale 计数）

```
Remove-Item 'app/build/test-results/testDebugUnitTest' -Recurse -Force
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

结果：`BUILD SUCCESSFUL in 9s`；解析 `app/build/test-results/testDebugUnitTest/*.xml`：

- **52 类 / 317 项 / 0 失败 / 0 跳过 / 0 错误**
- 基线（task-5 后干净基线）：51 类 / 305 项 → 本任务增量 **+1 类 / +12 项**
- 新增 `AccessPolicyTest` 12/12 通过

### 3) lint

```
.\gradlew.bat :app:lintDebug --console=plain
```

结果：`BUILD SUCCESSFUL in 51s`；`app/build/reports/lint-results-debug.xml` 共 **35 warnings / 0 errors**，与基线（35 warnings）一致，无新增，且没有一条落在本任务新增文件上。

### 4) 安装（非测试）

```
.\gradlew.bat :app:installDebug :app:installDebugAndroidTest --console=plain
```

结果：`BUILD SUCCESSFUL in 16s`，两个 APK 均安装到 `emulator-5556`。

### 5) 真实进程重启矩阵（宿主脚本）

```
cd docs/verification/2026-10-22-A03-lock-process-matrix
SERIAL=emulator-5556 bash run-task6-lock-matrix.sh
```

结果：`RESULT: ALL PHASES PASS`（脚本退出码 0），6 个阶段全部 PASS：

| scenario | phase | result | detail |
|---|---|---|---|
| draft | phase1 | PASS | `OK (1 test)` |
| draft | phase2 | PASS | `OK (1 test)` |
| backup | phase1 | PASS | `OK (1 test)` |
| backup | phase2 | PASS | `OK (1 test)` |
| crash | kill | PASS | `pid=12006 method=am force-stop (kill -9 rejected by shell uid)` |
| crash | phase2 | PASS | `OK (1 test)` |

三组场景：

- **draft**：锁定状态下保存孕期 + 草稿，进程被 `force-stop` 结束；冷启动后从磁盘读回同一档案与草稿（`assertEquals` 比较原始 payload），且 `MainActivity` 冷启动显示「资料已锁定」锁屏、读取不到「今天/产检/记录/创建孕期档案」等日记文本。
- **backup**：写入一条「FAILED」待重传标记并结束进程；冷启动后标记逐字段保持不变（`CloudUploadStatusStore.load()` 全量 `assertEquals`），没有被启动流程清空或消费。
- **crash**：Vault 打开、Room/SQLite 处于活跃写入状态时，宿主 kill 掉进程（见下方 kill 语义）；冷启动后档案恰好 1 条、草稿内容一致，重复读不会产生重复行。

### 6) UI 入口矩阵 + 锁定时清理预览缓存

```
$arg = '-Pandroid.testInstrumentationRunnerArguments.class=cn.yunji.journal.LockEntryMatrixTest,cn.yunji.journal.SessionCacheCleanupTest'
.\gradlew.bat :app:connectedDebugAndroidTest $arg --console=plain
```

结果：`Starting 8 tests on PJ_API36_16K(AVD) - 16` → `Finished 8 tests`，`BUILD SUCCESSFUL in 54s`；JUnit XML：**8 项 / 0 失败 / 0 跳过 / 30.515s**。

| 测试 | 耗时 | 断言内容 |
|---|---|---|
| `t1ColdStartShowsLockScreenAndNoJournalContent` | 7.143s | 冷启动显示锁屏 + 解锁按钮，无任何日记文本 |
| `t2ActivityRecreationKeepsLockScreen` | 3.746s | `ActivityScenario.recreate()` 后仍显示锁屏，无内容 |
| `t3RotationKeepsLockScreen` | 3.203s | 请求横屏（触发配置变更/重建）后仍显示锁屏，无内容 |
| `t4BackPressCannotRevealJournalContent` | 3.247s | 系统 BACK 后不泄露日记内容 |
| `t5FailedUnlockAttemptKeepsLockScreen` | 6.236s | 点击「解锁资料」（无系统凭据环境）后仍停留在锁屏，无内容 |
| `t6LockingClearsPrivateDocumentPreviewCache` | 4.131s | 进入可查看会话后放一个私有预览探针，切后台锁定后探针被 `clearPreviews` 删除 |
| `clearingPreviewsDeletesPrivateDocumentPreviews` | 0.289s | 隔离 Vault 内 `clearPreviews()` 删除 `document-viewers/` 下探针 |
| `clearingPreviewsKeepsCommittedRecords` | 0.717s | 清理预览不删除已提交档案 |

> 首轮该命令为 7/8：`t5FailedUnlockAttemptKeepsLockScreen` 失败，原因是 `UiProbe.clickText` 只在文本节点上派发 `ACTION_CLICK`，Compose 把可点击动作放在祖先节点上。修复 `UiProbe`（向上找到 `isClickable` 的祖先再点击）后重跑为 8/8。AndroidTest 中没有 UiAutomator 依赖，按任务边界不改 gradle 配置，因此用 `androidx.test` 自带的平台 `UiAutomation` 读取活动窗口文本与派发 BACK/HOME。

## kill 语义（重要）

- 首选路径是 `adb shell kill -9 <pid>`，但 `adb shell` 以 `shell` uid 运行，无法给应用 uid 发送信号，实测被拒绝。
- 回退路径是 **`am force-stop cn.yunji.journal`**：ActivityManager 对目标进程调用 `Process.killProcess()`，即 **SIGKILL**，不执行 Activity/Service 生命周期回调、不等待落盘钩子，因此对「进程内状态是否已持久化」而言与应用内直接 `kill -9` 等价。
- 但它**不等价于断电/内核崩溃**：宿主内存与页缓存仍然保留，SQLite 的 WAL 不会被丢弃，内核也不会丢未 fsync 的写。本证据只证明「进程突然终止」语义，不证明「掉电」语义。真断电与低内存真机仍在未覆盖项中。

## 证据文件清单（本目录）

| 文件 | 说明 |
|---|---|
| `run-task6-lock-matrix.sh` | 宿主编排脚本（force-stop、marker 轮询、kill 尝试与回退、每阶段日志、设备回读） |
| `matrix-summary.txt` | 6 阶段结果表（脚本原始输出） |
| `raw/device-baseline.txt` | 运行前设备基线（API/页大小/字体/夜模/旋转/尺寸） |
| `raw/draft.phase1.stdout` / `.phase2.stdout`（+`.logcat`） | draft 场景两个阶段的 `am instrument` 原始输出与 logcat |
| `raw/backup.phase1.stdout` / `.phase2.stdout`（+`.logcat`） | backup 场景原始输出与 logcat |
| `raw/crash.phase1.stdout` / `.phase2.stdout`（+`.logcat`）、`raw/crash.marker.contents` | crash 场景原始输出、被杀进程 marker（含 pid）与 logcat |
| `raw/device-readback.txt` | 脚本结束时设备回读 |
| `device-readback-after.txt` | 手动恢复基线后的回读（font_scale=1.0、night no、rotation 0、1080x2400、无应用/测试进程） |
| `junit/device-lock-matrix-junit.xml` | `LockEntryMatrixTest` + `SessionCacheCleanupTest` 的 JUnit XML（8 项 0 失败，30.515s） |
| `jvm-full.log` | 全量 JVM 运行日志（52 类/317 项） |
| `lint-run.log` | lint 运行日志 |
| `device-ui-run.log` | 设备 UI 矩阵运行日志（`Starting 8 tests` / `Finished 8 tests`） |

## 覆盖与未覆盖

已覆盖（可重复，JVM + 本机模拟器）：

- 锁定入口：冷启动、Activity 重建、旋转、系统返回、失败解锁尝试，均不泄露日记内容；锁定（切后台）清理私有文档预览缓存。
- 进程恢复：draft 优雅重启、backup 待重传标记重启、Vault 打开时进程被杀后的数据一致性（不丢、不重复）。
- 状态矩阵：隔离 Vault（数据落 `filesDir/task6-recovery-*`、键别名与 SharedPreferences 均命名空间隔离）与真实应用数据互不影响；`emulator-5556` 上串行执行。

未覆盖（本环境无法验证，需真机/专项）：

- 真断电 / 内核崩溃（`kill -9` 与 force-stop 都保留页缓存与 WAL）。
- 真实低内存设备上的进程终止与恢复（LMK 路径）。
- OEM 后台限制/自启动策略导致的进程终止。
- 系统锁屏凭据/PIN/生物识别的「认证失败→锁定→冷却」完整往返：模拟器无已登记凭据，`t5` 实际进入的是「无可用认证器」分支；系统认证失败路径未走到。
- `kill -9` 直发（权限被拒），已用 ActivityManager SIGKILL 语义替代并如实记录。
- 真机安装、OEM 机型矩阵。

## 设备释放

运行结束后已恢复：`font_scale=1.0`、`cmd uimode night no`、`accelerometer_rotation=1`、`user_rotation=0`、`wm size=1080x2400`、`density=420`，执行 `am force-stop cn.yunji.journal` 并确认 `ps` 中无 `cn.yunji.*` / `androidx.test` 进程（见 `device-readback-after.txt`）。
