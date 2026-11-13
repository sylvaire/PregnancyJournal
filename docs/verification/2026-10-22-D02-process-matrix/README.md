# 2026-10-22 D02 本地加密备份：真实进程终止、取消窗口与孤儿扫描验证矩阵

- 任务：共享任务 **task-8**（对应 `docs/剩余任务验收清单.md:30` D02 遗留项、`docs/阶段记录/M4-D02完整本地加密备份.md` 的「下一步」）
- 执行者：net-slow-io · 工作区：`D:\Devtools\Code\CodexWorkspace\PregnancyJournal`
- 状态：**完成** —— JVM 7 项全绿、全量 JVM 54 类/331 项/0 失败；API26 emulator-5554 两阶段矩阵 `TOTAL: pass=5 fail=0`（§5）；lint 0 错误/34 警告；阶段记录已追加。
- 杀进程语义：`am force-stop` = ActivityManager SIGKILL（无生命周期回调），**不等于断电**；本目录不声称验证掉电一致性。

## 1. 背景与目标

D02 本地备份此前只有 JVM 全量绿 + 既有 `BackupProcessBoundaryTest`（覆盖「登记了备份开始标记但尚未进入导出」这一边界），不覆盖：

1. 导出/自检过程中真实进程终止（工作目录、租约、目标文件、进度状态）；
2. 恢复（读回/校验）过程中真实进程终止（不误报成功、不完全清空、可安全重试）；
3. 清理/彻底删除（PURGING）过程中终止（不得把 PURGING 误当完整数据）；
4. 进程遗留随机孤儿对象在冷启动时是否被扫描清理（若生产无扫描，只记录缺口）；
5. 导出/自检/恢复的取消窗口（刚开始/流式进行中/提交前/提交后）。

## 2. 产物清单

| 文件 | 说明 |
|---|---|
| `run-task8-d02-matrix.sh` | 两阶段宿主脚本：phase1 阻塞并写 marker → 宿主 `am force-stop` 并在 marker 追加 `forceStoppedAt=` → phase2 新进程断言；输出 PASS/FAIL 表与设备配置前后回读 |
| `app/src/androidTest/java/cn/yunji/journal/D02ProcessMatrixTest.kt` | 10 个 @Test = 5 场景 × (phase1, phase2)。**只在 `-e d02Phase <scenario>` 时执行**，普通全量跑全部 assumeTrue 跳过；phase2 额外要求 marker 含宿主追加的 `forceStoppedAt=`，否则跳过（防全量跑误报）；并断言 phase2 pid ≠ phase1 pid |
| `app/src/test/java/cn/yunji/journal/BackupProcessMatrixTest.kt` | 7 个纯 JVM 取消/截断/重试用例（LocalBackupArchive 层） |
| `compile-androidtest.log` | `:app:compileDebugAndroidTestKotlin` 原始日志 |
| `jvm-targeted.log` / `junit/TEST-cn.yunji.journal.BackupProcessMatrixTest.xml` | 新增 7 项 JVM 用例原始日志与 JUnit XML |
| `jvm-full.log` / `jvm-summary.txt` | 全量 JVM 原始日志与解析摘要 |
| `device-info.txt`（待采） | 设备前/后配置回读（API/model/page size/font_scale/night/rotation） |
| `<scenario>-phase1.log` / `<scenario>-phase2.log` / `junit/<scenario>-phase2.xml`（待采） | 5 组场景设备证据（见 §5 映射） |

## 3. 命令原文与已得数字

环境：`$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'`；无设备部分在工作区完成；设备矩阵在 Lead 授权的 **API26 emulator-5554**（SDK 26）独占窗口执行（见 §5），`emulator-5556`（API36/16KiB）未被占用；串行执行，同一时间只跑一条 gradle/设备命令。

```powershell
# 1) androidTest 编译（无设备）
.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain
# => BUILD SUCCESSFUL in 2s (35 actionable tasks: 2 executed, 33 up-to-date)

# 2) 新增 JVM 定向
.\gradlew.bat :app:testDebugUnitTest --tests "cn.yunji.journal.BackupProcessMatrixTest" --console=plain
# => BUILD SUCCESSFUL; 7 tests / 0 failures / 0 skipped / 0.57s

# 3) 全量 JVM（任务开始时基线 52 类/317 项；本任务新增 1 类 7 项）
.\gradlew.bat :app:testDebugUnitTest --console=plain
# => BUILD SUCCESSFUL in 2s (33 actionable tasks: 33 up-to-date); classes=54 tests=331 failures=0 skipped=0
#    说明：本轮计时 53 类/324 项；其后 Lead 的 API26 兼容改动（DocumentPreview→androidx.exifinterface、
#    data/PdfReadability.kt 等）并入工作区，最终全量为 54 类/331 项，其中本任务贡献 +1 类/+7 项。
```

| 项 | 结果 | 证据 |
|---|---|---|
| 全量 JVM | **54 类 / 331 项 / 0 失败 / 0 跳过**（任务开始时基线 52 类/317 项；本任务 +1 类 +7 项，其余为 Lead 的 API26 兼容改动） | `jvm-full.log`、`jvm-summary.txt` |
| 新增 JVM 定向 | **7 项 / 0 失败 / 0 跳过 / 0.57s** | `jvm-targeted.log`、`junit/…xml` |
| androidTest 编译 | **BUILD SUCCESSFUL**（修复前 `compile-androidtest.log`，运行时缺陷修复后 `compile-androidtest-runtimefix.log`） | 同左 |
| lintDebug | **0 错误 / 34 警告**（`lint.log`、`lint-results-debug.xml`；本任务只新增 test/androidTest 与 docs/script，未改 main，无新增告警） | `lint.log`、`lint-results-debug.xml` |
| 设备矩阵 | **5 场景 / 5 项 phase2 全 PASS / 0 失败**（API26 emulator-5554，`TOTAL: pass=5 fail=0`，见 §5） | `matrix-run.log`、`*-phase1/2.log`、`device-markers.txt` |

## 4. 用例矩阵

### 4.1 JVM（`BackupProcessMatrixTest`，7 项，全绿）

| 用例 | 覆盖点 |
|---|---|
| `cancelledPublicationAtEveryCheckpointNeverPublishesOrLeavesPartial` | 归档写入每个 checkpoint 注入取消：不发布目标、不残留 `*.backup-partial` |
| `cancelledVerificationAtEveryCheckpointLeavesNoStagingDirectory` | 校验每个 checkpoint 取消：不残留 `backup-verify-*` staging |
| `cancelledVerificationLeavesTheSourceObjectsIntactAndReusable` | 取消校验不篡改源密文对象，之后可再次校验/再导出 |
| `everyTruncationOfThePublishedArchiveIsRejected` | 目标文件被截断到任意长度（含 0/1/半长/末字节）一律校验失败（对应"kill 在目标拷贝中途"的产物） |
| `preExistingDestinationContentIsNeverOverwritten` | 目标已有内容时拒绝写入，失败不破坏已存在文件 |
| `retryAfterCancellationPublishesTheSameSnapshotExactlyOnce` | 取消后重试：发布同一 snapshot 一次，不重复、无残留 |
| `staleStagingDirectoryFromAPreviousProcessIsNeitherReusedNorTrusted` | 上一进程遗留 staging 目录不被复用/信任 |

### 4.2 设备两阶段（`D02ProcessMatrixTest`，5 场景 × 2 阶段）

| 场景（`-e d02Phase`） | phase1（被杀点） | phase2（冷启动断言） |
|---|---|---|
| `export-selfcheck` | 自检阶段（归档写入后、目标打开前）阻塞；kill 后遗留 `vault/backup-work/job-*` | 生产 `ImportWorker.recover→DocumentImporter.recover→BackupExporter.recover` 清掉 job-*；无关目录保留；无目标半成品；`FileHandoff` 不提供"继续" |
| `export-write` | 目标 OutputStream 只落 1KiB 即阻塞；kill 后目标为截断文件 | 截断文件被 `LocalBackupArchive.verify` 拒绝；`FileHandoff` 不可续；新目标重试成功且可校验 |
| `verify` | `LocalBackupArchive.verify` 第 4 个 checkpoint 阻塞；kill | 归档 digest 未变、无"校验成功"标记；重试校验成功（附 staging 残留检测记录） |
| `purge` | `importer.purge(id, schedule=false)` 提交 PURGING 后立即阻塞；kill | 冷启动 PURGING 仍在、元数据/密文对象未被提前删；备份被"资料正在清理"拒绝（不得当完整数据）；`process(id)` 完成后 PURGED、对象删除、备份恢复可用 |
| `orphan` | 在真实 `vault/backup-work/job-*`、`vault/restore-input/job-*`、`cacheDir/export-work/job-*` 放置合成残留后阻塞；kill | 冷启动清理 backup-work 与 restore-input 孤儿、保留无关目录；**`export-work` 孤儿仍存在**（缺口断言，见 §6-③-1） |

## 5. 设备执行结果（API26 emulator-5554，2026-10-22 17:38–17:41，Lead 授权独占窗口）

```bash
# 0) 用当前工作区重新构建（含 Lead 当日 DocumentPreview/PdfReadability 改动）并安装
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain   # BUILD SUCCESSFUL in 2s（78 tasks up-to-date）
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk          # Success
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk  # Success

# 1) 两阶段矩阵（Git Bash 5.2 / MSYS，PATH 注入 platform-tools）
ADB=/d/Android/Sdk/platform-tools/adb.exe SERIAL=emulator-5554 \
  ./docs/verification/2026-10-22-D02-process-matrix/run-task8-d02-matrix.sh
# => TOTAL: pass=5 fail=0（原文 matrix-run.log）
```

| 场景 | phase1 marker 证据（阻塞后写盘，随后被 SIGKILL） | phase2（新 PID）结果 | 阶段日志 |
|---|---|---|---|
| `export-selfcheck` | `phase=export-selfcheck pid=16224 blockedAt=正在校验原件与恢复数据` + `forceStoppedAt=1792662148`（pid 16224 被杀） | **PASS** `OK (1 test)` / code -1：backup-work `job-*` 由生产恢复清理、无目标半成品、`FileHandoff` 不提供“继续” | `export-selfcheck-phase1.log` / `-phase2.log` |
| `export-write` | `pid=16332 destination=…/files/task6-recovery-d02write/destination-prefix.yunji` + `forceStoppedAt=1792662150` | **PASS**：目标为 1KiB 截断文件 → `LocalBackupArchive.verify` 拒绝、不可续；新目标重试成功且可校验 | `export-write-phase1.log` / `-phase2.log` |
| `verify` | `pid=16438 archiveDigest=a66b9c4799592585ffe59b8193ba8f412d348592764e3dde76a775c12925aae3 blockedDuringVerify=true` + `forceStoppedAt=1792662154` | **PASS**：归档字节未变、无“校验成功”标记；重试校验通过（staging 残留检测记录见 §6-③-2） | `verify-phase1.log` / `-phase2.log` |
| `purge` | `pid=16548 attachmentId=d370c6b2-c983-4e4e-9274-bbca632d1bda objectFile=…/objects/d370c6b2-….bin` + `forceStoppedAt=1792662156` | **PASS**：冷启动 PURGING 仍在 → 备份被“资料正在清理”拒绝（不得当完整数据）；`process(id)` 后 PURGED、密文对象删除、备份恢复可用 | `purge-phase1.log` / `-phase2.log` |
| `orphan` | `pid=16652` + 三类合成孤儿路径（backup-work/restore-input/export-work） + `forceStoppedAt=1792662158` | **PASS**：backup-work 与 restore-input 孤儿被清理、无关目录保留；**export-work 孤儿仍存在**（缺口真值断言，见 §6-③-1） | `orphan-phase1.log` / `-phase2.log` |

- 每阶段原始输出为 `am instrument -w -r` 全文：`<scenario>-phase1.log` / `<scenario>-phase2.log`；5 个 phase2 均含 `OK (1 test)` 与 `INSTRUMENTATION_CODE: -1`。
- phase1 marker 原文汇总：`device-markers.txt`（pid、phase、关键路径、`forceStoppedAt`）；`matrix-run.log` 首尾 `device:` 行即前后设备读回。
- 跑前设备：SDK 26；跑后恢复 `font_scale=1.0 / ui_night_mode=1（浅色）/ user_rotation=0 / accelerometer_rotation=0`，`wm size=1080x1920`、`density=420`（该 API26 AVD 物理分辨率即 1080x1920，非 1080x2400）；`ps -A | grep yunji` 为空并已 `am force-stop cn.yunji.journal`（`device-after-baseline.txt`）。
- 执行环境注意（已修脚本）：WSL 不继承 Windows 的 `ADB`/`SERIAL` 环境变量；且 `set -u` 下 `local a="$1" log="$OUT/$a…"` 因 bash 先展开后赋值会报 unbound variable——脚本已拆成两条 `local`。首次 WSL 尝试在启动测试前就失败（无脏状态），改用 Git Bash 后 5/5 通过。
- 无 AGP JUnit XML：本矩阵直接 `adb shell am instrument` 单方法执行，不经过 `connectedDebugAndroidTest`；设备测试项 = 5 场景 phase2（5 项）全部 `OK`，phase1 是被 SIGKILL 的阻塞进程，不计为测试通过。

## 6. 覆盖 / 未覆盖与缺陷记录

**覆盖**：见 §4（JVM 7 项）与 §5（设备 5 场景 × phase1 被杀 + phase2 新进程断言，5/5 PASS）。取消窗口在 JVM 层覆盖「每个 checkpoint」；设备层覆盖 kill 在自检后、目标写入中、校验中、PURGING 提交后、孤儿冷启动（API26 emulator-5554）。

**未覆盖（如实记录）**：
1. 真实断电/文件系统崩溃一致性（force-stop ≠ 断电，`fd.sync()` 只覆盖正常路径）；
2. WorkManager/OEM Doze、后台限制、自启动管理；
3. 真实存储空间不足/SAF provider 异常（只读、流满、USB/云盘拔出、配额）无法确定性注入，本轮不覆盖；
4. API36（emulator-5556）未跑本矩阵——本轮设备窗口为 API26 emulator-5554；API26 覆盖已由 §5 提供，API36 行为差异（16KiB 页、DocumentsUI）仍待 Lead 安排；
5. purge 的「文件删除→状态更新」毫秒级竞态无法确定性命中，改用 PURGING 已提交/cleanup 未完成的 crash-artifact 状态验证，非真实删除中途 kill；
6. 真机/厂商私有目录语义。

**测试暴露但未修复（记录，未改生产代码——task-8 明令孤儿扫描只记录缺口）**：
- **③-1 `cacheDir/export-work/job-*` 无跨进程恢复扫描**：`ExportService` 只在同进程 finally 清理；`DocumentImporter.recover()` 不扫描 export-work → 进程被杀可残留解密原件/结构化 ZIP 明文（应用私有）。建议另开任务加冷启动清理。
- **③-2 `vault/restore-validation/backup-verify-*` 无进程终止清理**：`LocalBackupArchive.verifyCore` 的 catch/close 清理在 SIGKILL 下不执行；staging 目录（含可能的 `authenticated-original` 中间明文）会残留，无启动扫描。建议 RestoreWizard 初始化/冷启动清理。
- **③-3 外部 SAF 目标文件半成品无法清理**：kill 后截断文件留在用户选择位置；verify 拒绝且不可续传，但应用无权限删除外部文档 → 需文案说明。
- **③-4 `*.backup-partial` 仅靠 finally 清理**：生产 destination 在 `backup-work/job-*` 内，由恢复路径兜底（不构成缺口）；仅当调用方把 destination 指到其它目录才有残留。
- ③-5 测试自身缺陷（已修）：`retryAfterCancellationPublishesTheSameSnapshotExactlyOnce` 初版未用 `runCatching` 包裹预期的取消异常，导致用例失败；生产 `LocalBackupArchive` 取消路径未发现缺陷。

**全量跑不误失败的门控**：`D02ProcessMatrixTest` 只在 `-e d02Phase <scenario>` 参数下执行；phase2 必须看到宿主脚本在 `am force-stop` 后追加的 `forceStoppedAt=`（否则 assumeTrue 跳过）；并断言 phase2 pid ≠ phase1 pid。A03 的 3 个旧 phase2 用例在普通全量跑的误失败问题由 Lead 在 API26 报告记为「脚本专用」，不在本任务范围。

## 7. 风险与下一步

- 风险：设备 kill 时序依赖 marker 轮询（1s 粒度）与 phase1 120s 看门狗；若 adb 慢需调 `POLL_TIMEOUT`。WorkManager 冷启动恢复延迟（最多 30s 轮询）在慢模拟器上可能边界。
- 下一步（截至 2026-10-22 17:43）：①设备矩阵已在 API26 emulator-5554 完成 5/5 PASS（§5）并归还设备；②`lintDebug` 已回填 0 错误/34 警告；③`docs/阶段记录/M4-D02完整本地加密备份.md` 已追加本轮章节；④待 Lead 回填 `docs/剩余任务验收清单.md` D02 行与 `docs/项目进度.md`；⑤若需 API36 证据，另约 5556 窗口跑同一脚本（脚本 `SERIAL` 可切换）。

## 8. 2026-10-22 17:30 增补：JUnit 运行时类初始化缺陷修复（API26 回归发现）

- **发现来源**：Lead 在 API26 全量回归（美术库 283 项）中报告：`D02ProcessMatrixTest` 触发 JUnit4 `InvalidTestClassError`：「Method phase2ExportSelfCheckRecovered() should be void」「Method phase2VerifyKilledProcessKeepsArchiveAndRetryVerifies() should be void」等 —— 类直接初始化失败，0 个方法执行。
- **根因**：`phase2*` 三个方法原为 Kotlin 表达式体 `fun phase2Xxx() = runBlocking { ... }`，`runBlocking` 的泛型 `T` 由 lambda 最后表达式推断为非 `Unit`（本文件里是 `assert*` 之外的 `if/assume` 混合分支），字节码返回类型为 `java.lang.Object`（而非 `void`）；JUnit4 在 `BlockJUnit4ClassRunner.validateTestMethods` 中以 `method.getReturnType()` 是否为 `void` 判定测试方法，条件不满足即抛 `InvalidTestClassError`。
- **修复**（最小）：三处显式标注 `runBlocking<Unit>`：
  - `app/src/androidTest/java/cn/yunji/journal/D02ProcessMatrixTest.kt:203` `phase2ExportSelfCheckRecovered`
  - 同文件 `:271` `phase2VerifyKilledProcessKeepsArchiveAndRetryVerifies`
  - 同文件 `:335` `phase2PurgingIsNotCompleteAndResumeFinishesIt`
  其余 7 个方法保持原样（已经是 `void` 字节码）。
- **验证证据**：
  - `compile-androidtest-runtimefix.log`：`:app:compileDebugAndroidTestKotlin` → `BUILD SUCCESSFUL in 4s`（二次编译本轮 `BUILD SUCCESSFUL in 6s`，含 assembleDebug/assembleDebugAndroidTest）。
  - `junit-void-signatures.txt`：`javap -p` 反编译结果，10 个 phase1/phase2 方法全部为 `public final void`。
  - `device-classinit-check.txt`：API26 emulator-5554（SDK 26、boot_completed=1）安装最新 debug+androidTest APK 后执行：`am instrument -w -r -e class cn.yunji.journal.D02ProcessMatrixTest -e d02Phase none cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner` → 输出 `OK (10 tests)`、`INSTRUMENTATION_CODE: -1`，无 `initializationError` / `InvalidTestClassError`；10 个方法均以 `INSTRUMENTATION_STATUS_CODE: -4`（ASSUMPTION_FAILURE，因 `d02Phase=none` 被门控跳过，符合设计）。
- **状态**：类初始化已修复并完成设备级校验；真实两阶段矩阵已随后在 API26 emulator-5554 跑完 5/5 PASS（见 §5），脚本与证据不变。
