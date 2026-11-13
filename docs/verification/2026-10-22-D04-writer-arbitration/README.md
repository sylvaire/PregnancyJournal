# D04 连续本机备份与外部写者仲裁修复验证（2026-10-22）

本轮修复 D04 在真实 TLS 路径上的缺陷：上传协调器此前只认最近一次快照，第三次本机备份会把第一份误判成外部 writer。
修复后逐一读取并认证云端 commit 及其 manifest，按 writerId 仲裁；确认主设备只把当时已观察到的外部提交记入基线，新提交仍阻断，恢复资料库后清空基线。

## 修复内容

- 缺陷：新增的 `DavBackupRemote.verifiedCommitWriterIds` 以 `BackupManifest.MAX_BYTES + 32` 请求快照清单，而 `WebDavClient.readBounded` 的传输层单次读取上限为 16 MiB（`require(limit in 1..16MiB)`），在真实 TLS 路径直接抛 `IllegalArgumentException: Failed requirement.`（`WebDavClient.kt:217` ← `DavBackupRemote.kt:52`），外部写者仲裁不可用。
- 修复：`core/network/DavBackupRemote.kt` 的清单读取改为 `BackupManifest.MAX_BYTES`（16 MiB 传输层上限），与既有 `CloudMaintenance.MAX_MANIFEST_READ`、`CloudSnapshotFetcher.MAX_MANIFEST_BYTES` 约定一致，并注明清单是加密元数据、远小于上限、不叠加 GCM 封装开销。未放宽任何校验。
- 协调层：`CloudBackupCoordinator` 上传前 `verifiedCommitWriterIds` → `CloudBackupPolicy.unconfirmedForeignCommits`，命中未确认外部提交时 `CloudSchedulerStore.blockForOtherWriter` 落持久状态并抛错；主设备确认时把观察到的提交 ID 并入 `acknowledgedForeignCommitIds`；`VaultRuntime` 恢复后 `afterRestore()` 清空。

## 验证命令与结果

均使用 `$env:GRADLE_USER_HOME = <仓库>\.gradle-user`，`.\gradlew.bat <task> --console=plain`。

| 验证 | 命令要点 | 结果 |
| --- | --- | --- |
| 定向 TLS 仲裁 | `:app:testDebugUnitTest --tests "cn.yunji.journal.CloudBackupUploadDavTest"` | BUILD SUCCESSFUL in 15s，7/7 通过（0.897s） |
| 策略单测 | `:app:testDebugUnitTest`（全量含该类） | `CloudBackupPolicyTest` 7/7 通过 |
| 全量 JVM | `:app:testDebugUnitTest` | BUILD SUCCESSFUL in 7s；43 类 / 237 项 / 0 失败 / 0 错误 / 0 跳过 |
| lint | `:app:lintDebug` | 0 错误 / 29 警告（基线 24，差异见下） |
| 定向设备 | `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.yunji.journal.CloudSchedulerStoreTest` | 6/6 通过（29.196s，PJ_API36_16K / API36 16 KiB 页） |
| 设备全量回归 | `:app:connectedDebugAndroidTest` | BUILD SUCCESSFUL in 10m45s；244 项完成、18 跳过、0 失败；权威 XML：226 项 / 0 失败 / 0 错误 / 18 跳过 |

证据文件：`targeted-test.log`、`jvm-full.log`、`jvm-summary.json`、`junit-CloudBackupPolicyTest.xml`、`junit-CloudBackupUploadDavTest.xml`、`lint-results.txt`、`lint-results.xml`、`lint-and-device.log`、`device-CloudSchedulerStoreTest.xml`、`device-full.log`。

## 覆盖内容

- 连续三次本机自动备份：3 个已发布 commit 全部认证为本机 writer，不被误判为外部写者；`unconfirmedForeignCommits` 为空、`foreignWriterDetected` 为假（真实 HTTPS/MockWebServer 上的 WebDAV 交互）。
- 同目录第二台设备提交有效 commit：检出外部提交并阻断自动备份；确认主设备后仅"当时已观察"的提交进入基线。
- 已确认基线上的更新提交：再次阻断，仅针对未确认提交。
- 持久状态：writer 阻断/确认基线跨实例持久；恢复资料库后清空基线并回落 SECONDOary、自动备份关闭、仅 WiFi；`reset` 不动上传状态。
- 原有覆盖不回归：外部身份/损坏对象绝不覆盖、不信任证书拒绝且无 trust-all 回退、缺失源文件不发远端请求、对象按哈希复用、原子提交（412）行为。

## 未覆盖

- 真实云盘服务商（Nextcloud/坚果云/群晖等）联测、多设备真实并发、OEM 后台限制、进程终止恢复、慢网/大文件/取消窗口：由 `docs/verification/2026-10-22-D03-slow-cancel/` 任务另行验证。
- 未提升版本号：本次是既有 0.25.0-dev/versionCode26 的缺陷修复增量。

## lint 差异（24 → 29）

- 新增 4 条 `GradleDependency`（`gradle/libs.versions.toml:5/8/8/10`）：同一依赖的新检查 ID，不是新增问题。
- 新增 2 条 `UseKtx`（`data/CloudSchedulerStore.kt:82/89`）：本轮新增的持久阻断/确认写入使用带返回值检查的同步 `commit()`，与既有 `SecurityStore.kt:14/17`、`ReminderStore.kt:50` 同一模式，属有意保留。
- `data/LocalRestoreAccess.kt` 只发生行号位移（37→41）。
- 未放宽任何 lint 基线。

## 涉及文件

- 修复：`app/src/main/java/cn/yunji/journal/core/network/DavBackupRemote.kt`
- 生产侧：`core/backup/CloudBackupPolicy.kt`、`core/backup/CloudBackupCoordinator.kt`、`data/CloudSchedulerStore.kt`、`data/CloudBackupRunner.kt`、`data/CloudBackupController.kt`、`data/VaultRuntime.kt`、`data/CloudUploadStatusStore.kt`
- 测试：`app/src/test/java/cn/yunji/journal/CloudBackupUploadDavTest.kt`（`ownBackupHistoryAndForeignWriterArbitrationAreResolvedOverTls`）、`app/src/test/java/cn/yunji/journal/CloudBackupPolicyTest.kt`（`repeatedOwnBackupsAndAcknowledgedForeignHistoryDoNotMaskNewCommits`）、`app/src/androidTest/java/cn/yunji/journal/CloudSchedulerStoreTest.kt`（`confirmationPersistsOnlyTheObservedForeignCommitsAndRestoreClearsIt`）
