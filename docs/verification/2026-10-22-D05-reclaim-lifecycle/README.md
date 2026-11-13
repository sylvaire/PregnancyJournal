# 2026-10-22 D05 确认回收旧代际与再次恢复生命周期

- 任务：D05 遗留「确认回收与再次恢复生命周期」（`docs/剩余任务验收清单.md:33`；`docs/已知限制.md:12` 记录回收入口缺失）
- 分支基线：0.25.0-dev / versionCode 26（task-1/task-2 之后，未发新版本）
- 结论：**JVM 侧生产接线与语义验证完成；设备端定向 10 项 0 失败（含生产面板回收全链路与回收后再次恢复）**。仍不宣称 D05 整体验收（真机、物理断电、真实云盘服务商未覆盖）。

## 背景与目标

`RestoreWizard.reclaimPrevious()` 之前只在 `RestoreWizardTest` 中被调用，生产代码没有任何调用点；恢复后旧代际永远保留，界面也没有回收入口。本次目标：

1. 把「确认恢复完成后回收旧代际」接进生产调用链（核心 + 恢复页 UI），而不是只保留测试可达的方法；
2. 保证回收的持久化/中断安全：只删 `previousId` 指向的代际、绝不碰 active；重复回收幂等；崩溃/中途失败不留半删除或破坏指针；
3. 验证回收后仍能再次恢复（新恢复保留自己的回退代际），覆盖 `PREPARED → SWITCHED → VERIFIED → 清日志 → 回收 → 再次恢复` 生命周期；
4. 全部结论有可重跑命令与 JUnit XML 证据。

## 生产改动（3 处修改 + 1 个新文件）

### 1. `app/src/main/java/cn/yunji/journal/core/backup/VaultLayout.kt`

新增 `@Synchronized fun reclaimPreviousGeneration(): String?`：读取指针 → `previous != active` 校验 → 校验 active 的 `journal.db` 仍是文件 → 把旧代际目录重命名为 `gen/<id>.reclaim`（确定性 trash）→ 目录 fsync → 原子写入 `active-generation.json`（`previousId = null`）→ 删除 trash → 清理孤儿 trash → 再次 fsync；返回被回收 id，无 `previousId`（旧布局/已回收）时返回 null。

中断语义（见方法 KDoc）：**先 rename、再写指针、最后删 trash**。
- 崩溃在指针切换前：`previousId` 指向不再存在的目录，下一次调用仍能（幂等地）完成；
- 崩溃在指针切换后：只剩 `.reclaim` 孤儿目录，下一次调用清理；
- active 代际从不被 rename/delete。

辅助：`reclaimTrash()`、`cleanOrphanReclaimDirectories(keep)`、`isCanonicalUuid()`、`RECLAIM_SUFFIX = ".reclaim"`。未新增第三方依赖，继续复用既有 `syncDirectoryDurably`（Android `Os.fsync`，目录重新打开后 fsync）。

### 2. `app/src/main/java/cn/yunji/journal/core/backup/RestoreReclaim.kt`（新增，100 行）

- `enum ReclaimState { NONE, AVAILABLE, PENDING_RESTORE, UNREADABLE }`：`NONE`=旧布局或已回收；`AVAILABLE`=上次恢复已验证完成，`previousId` 可回收；`PENDING_RESTORE`=journal 仍为 `PREPARED/SWITCHED`，旧代际可能还要用于回退；`UNREADABLE`=指针/journal/active 不可信，**任何删除都不做**。
- `data class ReclaimInfo(state, previousId, message)`：给 UI 的只读快照。
- `class RestoreReclaim(vaultDir, syncDirectory = ::syncDirectoryDurably)`：
  - `inspect()`：纯只读；journal 为 PREPARED/SWITCHED → PENDING_RESTORE；指针/journal 读失败 → UNREADABLE；缺 active/previous → NONE；`previous == active` → UNREADABLE；active 库缺失 → UNREADABLE；journal 为 VERIFIED 但 target ≠ active → UNREADABLE。
  - `reclaim()`：journal 必须为 null 或（VERIFIED 且 target==active），active 库必须存在，随后委托 `layout.reclaimPreviousGeneration()`。失败时抛异常且文件/指针保持原状。

### 3. `app/src/main/java/cn/yunji/journal/core/backup/RestoreWizard.kt:150`

`reclaimPrevious()` 由原来「按 journal.targetId 匹配后自行删除」改为委托 `VaultLayout.reclaimPreviousGeneration()`；前置条件为 `journal == VERIFIED` 且 active target 相同，保证向导路径与 UI 路径共用同一套持久化顺序。

### 4. `app/src/main/java/cn/yunji/journal/feature/RecoveryScreen.kt`

- `RecoveryContent(..., extra = {})` 的 extra 槽（与 `SafetyCopiesPanel` 同属性，仅 `INTRO/READY` 阶段渲染）追加 `ReclaimPreviousPanel()`，不改变 `JournalNavigation.kt` 的 `RecoveryScreen(model.recovery)` 调用签名；
- `ReclaimPanelState` + `ReclaimPreviousContent`（可纯状态 UI 测试）：无 `previous` 时整块不渲染；需要显式二次确认（`SecureFlagPolicy.SecureOn` 独立窗口，点外部不可关闭），文案明确「删除恢复前的旧资料代际、无法撤销、当前资料与安全副本/导出备份不受影响」；
- `ReclaimPreviousPanel` 生产实现：`ON_RESUME` inspect、`ON_PAUSE` 关闭确认；`LocalSafetyActionsAllowed`（Main 的 covered 状态）为 false 时禁用按钮并丢弃确认；回收中 busy 禁用；成功/失败后重新 `inspect()` 并以真实状态展示，不做乐观更新；IO 调度，取消异常透传。

**未改**：`JournalApp.kt`、`JournalNavigation.kt`、`MedicationScreens.kt`、`TimelineScreens.kt`、`PrenatalScreens.kt`（ui-matrix 占用）、`MainActivity.kt`、数据库/schema/依赖。

## 验证命令与结果（全部本机真实执行，exit code 0）

Gradle 环境：`$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'`，守护进程热。

| # | 命令（原文） | 结果 |
|---|---|---|
| 1 | `.\gradlew.bat :app:testDebugUnitTest --tests "cn.yunji.journal.RestoreReclaimTest" --tests "cn.yunji.journal.RestoreWizardTest" --tests "cn.yunji.journal.VaultLayoutTest" --console=plain` | BUILD SUCCESSFUL，3.8s。XML：`RestoreReclaimTest` 16 项、`RestoreWizardTest` 12 项、`VaultLayoutTest` 11 项 = **39 项 / 0 失败 / 0 跳过**（0.80s 测试耗时） |
| 2 | `.\gradlew.bat :app:testDebugUnitTest --console=plain` | BUILD SUCCESSFUL，8.5s。**45 个测试类 / 266 项 / 0 失败 / 0 错误 / 0 跳过**（基线 task-2 后 44 类/250 项 → +1 类 +16 项，无既有用例减少） |
| 3 | `.\gradlew.bat :app:lintDebug --console=plain` | BUILD SUCCESSFUL。**0 errors / 35 warnings**，与基线一致、无新增（NewerVersionAvailable=13、GradleDependency=9、UseKtx=9、AndroidGradlePluginVersion=2、ExifInterface=2）。首轮报告被 Gradle 判为 UP-TO-DATE（时间戳陈旧），已删除旧报告重跑 13.5s 重新生成后再计数，见 `lint-summary.txt` |
| 4 | `.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain` | BUILD SUCCESSFUL，1.7s；新增设备测试与 UI 测试已编译（本机未连设备，未运行） |

### 设备执行（2026-10-22 15:03–15:04，emulator-5556 / API36 / 16KiB；Lead 释放设备后串行执行）

```
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.yunji.journal.RecoveryUiTest,cn.yunji.journal.LocalRestoreCloudTest --console=plain
```

结果：BUILD SUCCESSFUL，59.3s；**10 项 / 0 失败 / 0 错误 / 0 跳过**（XML 测试耗时 45.963s：`RecoveryUiTest` 6 + `LocalRestoreCloudTest` 4）。关键用例：

- `RecoveryUiTest.productionReclaimPanelReclaimsPreviousGenerationThroughRealVault`（1.9s）：真实 `VaultLayout`（默认 `syncDirectoryDurably`）+ `RestoreReclaim` + `RecoveryScreen` 的 `ReclaimPreviousPanel` 全链路——旧代际存在→入口可见→二次确认→指针 `previousId` 清空、旧代际目录消失、active 库/objects 保留、入口消失；
- `LocalRestoreCloudTest.reclaimsTheConfirmedGenerationThenRestoresAgain`（18.025s）：真实 TLS 假 DAV + 真实 SQLCipher 代际，连续两次云端恢复→断言 `previousId`→回收→旧代际目录消失、active 保留、journal 清空→第三次恢复保留第二份回退代际；
- 其余：`reclaimPanelIsHiddenWhenNoPreviousGenerationExists` 4.763s、`reclaimNeedsExplicitConfirmationAndCanBeCancelled` 1.939s、`reclaimActionIsDisabledWhileCoveredOrBusy` 1.274s、`fullKeyIsHiddenBeforeVerificationAndPageDisposalClearsIt` 4.739s、`configuredPageLaunchesBackupForTheCurrentVaultWithoutShowingRecoveryKey` 1.57s；`LocalRestoreCloudTest` 其他 3 项 0.578s / 6.987s / 2.985s。

设备信息：SDK 36、model `sdk_gphone16k_x86_64`、PAGE_SIZE 16384、font_scale 1.0、浅色、竖屏 1080x2400；跑前跑后配置一致（`device-info.txt` / `device-info-post.txt`）。原始日志 `device-targeted.log`，JUnit XML `junit/device-RecoveryUiTest-LocalRestoreCloudTest.xml`，HTML 报告快照 `junit/device-report-index.html`。

> 2026-10-22 14:20 更新（Lead 要求补生产接线集成用例）：`RecoveryUiTest` 增加 1 项 `productionReclaimPanelReclaimsPreviousGenerationThroughRealVault`（真实 `VaultLayout`/`RestoreReclaim`/默认 fsync + `RecoveryScreen` 的 `ReclaimPreviousPanel` 完整链路）。因此命令 2 调整为 **10 项**（`RecoveryUiTest` 6 + `LocalRestoreCloudTest` 4）；执行前需先真实跑一次 `compileDebugAndroidTestKotlin`（非 UP-TO-DATE）。

## 新增测试清单

`app/src/test/java/cn/yunji/journal/RestoreReclaimTest.kt`（16 项，全部通过；覆盖生产 `VaultLayout.reclaimPreviousGeneration` + `RestoreReclaim` + `RestoreStartup` 组合）：

| 类别 | 用例 |
|---|---|
| 状态语义 | `legacyVaultWithoutAPointerHasNothingToReclaim`、`activeGenerationWithoutPreviousHasNothingToReclaim`、`preparedRestoreBlocksReclaimAndKeepsEverything`、`switchedRestoreBlocksReclaimUntilReopenCompletes`、`clearedJournalAfterReopenStillAllowsReclaimAndStartupIsNormal` |
| 保护不变量 | `reclaimIsIdempotentAndNeverTouchesTheActiveGeneration`、`reclaimKeepsSafetyCopiesExportsAndForeignFiles`、`unusableActiveGenerationBlocksReclaim` |
| 损坏/异常 | `corruptedPointerIsReportedInsteadOfDeleting`、`inconsistentPointerIsReportedAndNeverDeletesAnything` |
| 中断恢复 | `interruptedRenameIsFinishedByRetry`、`orphanTrashFromAnEarlierInterruptedReclaimIsCleanedByTheNextReclaim`、`startupLeavesJournalWhenRollbackCannotProveAnyUsableGeneration` |
| 生命周期 | `verifiedCompletedRestoreExposesAndThenReclaimsOnlyThePreviousGeneration`、`secondRestoreAfterReclaimCanReclaimItsOwnPreviousAndReopen`、`interruptedSecondRestoreAfterReclaimRollsBackToTheActiveGeneration` |

说明：JVM 用例在临时目录用 `syncDirectory = {}` 注入（JVM 无 `android.system.Os`，沿用仓库既有约定），文件写入/rename/fsync 语义由 `VaultLayout` 的 Android 生产路径覆盖；设备用例使用默认 `syncDirectoryDurably`。

`app/src/androidTest/java/cn/yunji/journal/RecoveryUiTest.kt`（+4，2026-10-22 设备端已执行通过）：`reclaimPanelIsHiddenWhenNoPreviousGenerationExists`、`reclaimNeedsExplicitConfirmationAndCanBeCancelled`、`reclaimActionIsDisabledWhileCoveredOrBusy`；以及生产接线集成 `productionReclaimPanelReclaimsPreviousGenerationThroughRealVault` —— 真实 `VaultLayout`（默认 `syncDirectoryDurably`）+ 真实 `RestoreReclaim` + 无 pending journal，经 `RecoveryScreen` 的 `ReclaimPreviousPanel` 走完整 UI：旧代际存在 → 入口可见 → 二次确认 → 指针 `previousId` 清空、旧代际目录（含 `.reclaim` 残留）消失、当前代际 `journal.db`/`objects` 保留 → 不可回收态入口消失。用例对默认 vault 目录先备份后恢复，避免污染真实资料。

`app/src/androidTest/java/cn/yunji/journal/LocalRestoreCloudTest.kt`（+1，2026-10-22 设备端已执行通过）：`reclaimsTheConfirmedGenerationThenRestoresAgain` —— 真实生产适配器 `LocalRestoreAccess` 连续两次云端恢复（第二次保留第一次的代际）→ 断言 `previousId` → `RestoreReclaim` 回收 → 断言 `previousId` 清空、旧代际目录消失、active 库保留、journal 清空 → 再次恢复第三份并断言保留第二份回退代际。

## 覆盖与未覆盖

已覆盖（JVM 39 项 + 设备端 10 项）：
- 生产入口链：`RecoveryScreen → ReclaimPreviousPanel → RestoreReclaim → VaultLayout.reclaimPreviousGeneration`（含 `RestoreWizard.reclaimPrevious` 委托）；
- journal 状态机边界：无 journal / PREPARED / SWITCHED / VERIFIED(target 匹配/不匹配) / 回收后清空；
- 不变量：idempotent、active 永不删除/重命名、安全副本与导出文件不受影响、指针损坏或 previous==active 时零删除；
- 中断：rename 后崩溃由重试完成、孤儿 `.reclaim` 由下一次回收清理、再次恢复后回收属于自己的 previous、回收后第二次中断恢复可回滚到 active；
- 设备端：真实 SQLCipher 代际目录（`journal.db`/`objects`）+ 默认 `syncDirectoryDurably` 的 rename/fsync/delete 顺序、生产 `ReclaimPreviousPanel` 完整链路、真实 TLS 假 DAV 下连续两次恢复→回收→第三次恢复（10 项，0 失败，见上表）。

未覆盖 / 不能由本环境证明：
- **物理断电/真机终止**：JVM 与模拟器只能证明「文件系统顺序 + fsync 调用」与异常注入，不代表磁盘真正落盘顺序；
- 严格模式系统认证完整往返下的回收入口（需真机 + 认证设置）；
- 真实云盘服务商与真机安装、OEM 后台限制；
- 大代际（数百 MiB 原件）回收的耗时/ANR 风险：当前实现同步 `deleteRecursively`，UI 仅在 IO 调度器上 busy，无进度；建议后续按需迁移到 WorkManager 或带进度；
- 多进程并发（本应用单进程，未设计跨进程锁）。

## 风险与备注

- `docs/已知限制.md:12` 仍写着「恢复向导的确认回收入口缺失」，现已过时但不在本任务写范围，建议 Lead 回填（同 `docs/剩余任务验收清单.md:33` 的 D05 行）。
- `VaultLayout.kt` 保留了一个既有的未使用私有函数 `systemSyncDirectory`（非本次引入，未清理以免扩大 diff）。
- 回收只处理 `gen/<uuid>.reclaim` 且有 canonical UUID 的条目；不触碰 `restore-safety`、导出文件、`objects` 等其它目录（用例 `reclaimKeepsSafetyCopiesExportsAndForeignFiles` 证明）。

## 原始证据文件

- `jvm-targeted.log`、`jvm-full.log`、`jvm-summary.txt`（命令与计数）
- `junit/TEST-cn.yunji.journal.RestoreReclaimTest.xml`、`...RestoreWizardTest.xml`、`...VaultLayoutTest.xml`
- `lint-results-debug.txt`、`lint-summary.txt`
- `androidtest-compile.log`
- `device-targeted.log`、`junit/device-RecoveryUiTest-LocalRestoreCloudTest.xml`、`junit/device-report-index.html`
- `device-info.txt` / `device-info-post.txt`（设备跑前/跑后配置：SDK36/16KiB/font_scale 1.0/竖屏 1080x2400）
