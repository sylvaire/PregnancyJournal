# 2026-11-12 剩余问题排查

本轮任务：排查项目剩余问题。范围包含代码基线复核、文档与代码一致性核对、遗留未实现项确认。

## 1. 基线复核（本轮实际执行）

设备：项目本地 AVD `PJ_API36_16K`（Android 16 / SDK 36 / `PAGE_SIZE=16384` / 1080x2400 / 420dpi / 字体 1.0 / 浅色 / 竖屏）。

| 检查 | 命令 | 结果 |
|---|---|---|
| JVM 单元测试（最终） | `./gradlew --gradle-user-home .gradle-user testDebugUnitTest` | **351 项 / 0 失败 / 0 错误**（57 个测试类；含新增的 schema 防漂移测试） |
| lint | `./gradlew --gradle-user-home .gradle-user lintDebug` | **0 错误 / 15 警告** |
| 仪器矩阵（修复前） | `am instrument -w -r` 全量 | **379 项 / 3 失败** |
| 仪器矩阵（断言修复后） | 同上 | **379 项 / 1 失败**（`medicationDraft…` 5 s 等待超时，单跑通过） |
| 仪器矩阵（等待加固后） | 同上 | **379 项 / 1 失败**（`cloudConnection…` 15 s 等待超时，单跑通过） |
| 仪器矩阵（最终，重新打包 app APK 后） | 同上 | **`OK (379 tests)` / 0 失败 / 0 跳过 / 850.734 s** |

> 过程更正：首次“最终”运行（`full-matrix-2026-11-12-final.log`，`OK (379 tests)` / 1056.864 s）安装的 `app-debug.apk` 时间戳为 2026-10-29，早于本轮 `LogicalSnapshot`/`SnapshotRelations` 修改（2026-11-12 20:49），即那次验证的是旧 app 包。已重新 `assembleDebug`（21:48:43）并复跑，得到上表最后一行结果；**以 `full-matrix-2026-11-12-verified.log` 为准**。两者均为 379/379，但只有后者覆盖 schema 版本修复。

最终矩阵日志：`.local/full-matrix-2026-11-12-verified.log`。

> 环境注意：本机全局 Gradle 初始化脚本 `D:\Devtools\Cache\gradle\init.d\china-mirror.gradle` 会以 `FAIL_ON_PROJECT_REPOS` 冲突使构建失败。项目约定使用 `--gradle-user-home .gradle-user` 绕过，`scripts/verify.ps1` 已内置该参数。

## 2. 已修复项

### 2.1 备份快照 schema 版本标记过期（生产代码）

- 位置：`core/backup/LogicalSnapshot.kt`、`core/backup/SnapshotRelations.kt`
- 问题：`LogicalSnapshot.schemaVersion` 固定为 **14**，`SnapshotRelations.validate` 只接受 `12..14`，而当前数据库已是 **schema 15**（N16 就诊字段迁移）。备份因此被标记为旧版本，且未来 schema 16 会直接让新备份的自检失败。
- 修复：抽取 `LogicalSnapshot.SCHEMA_VERSION = 15` 与 `OLDEST_READABLE_SCHEMA = 12`，校验范围改为 `OLDEST_READABLE_SCHEMA..SCHEMA_VERSION`。
- 回归测试：`LogicalSnapshotTest.snapshotSchemaVersionTracksCurrentDatabaseSchema` 以 `app/schemas/` 的最大编号作为独立来源对照 `SCHEMA_VERSION`，并断言超范围版本被拒绝（`LogicalSnapshotTest` 9/9 通过）。
- 影响面：不改备份字节格式与恢复行为；旧备份（schema 12–15）仍可读取。

### 2.2 三项仪器测试断言与改版 UI 不同步

| 测试 | 失败原因 | 处理 |
|---|---|---|
| `ImeInsetLayoutTest#documentListViewportKeepsUsableHeightWhenImeShown` | 断言“无键盘时 ≥600 dp”，实测 539.81 dp。`document-list` 未设 `fillMaxSize`，内容短于视口时按内容高度测量；N22 紧凑行后内容从 755 dp 降到约 540 dp | 绝对阈值改为可用性阈值（≥300 dp），键盘态改为等待 IME inset 状态而非固定压缩幅度 |
| `NavigationExperienceTest#timelineShowsVisitAndJournalRecordsWithThreeFilters` | N04 改版后症状/心情合并为一行摘要，`症状：…` 前缀只在展开「查看完整原始内容」后出现 | 断言改为合并行文案，并**追加**展开后原文可见断言（覆盖强于原断言） |
| `NavigationExperienceTest#reminderSettingsShowsPermissionAndTypeControls` | N06 改版后「通知权限」同时出现在首屏状态卡与下方详细控制区 | 唯一节点断言改为“至少存在一个可见节点” |

均为测试侧同步，未放宽产品行为断言。

### 2.3 测试同步加固（已消除本轮复现）

| 测试 | 现象 | 处理 |
|---|---|---|
| `NavigationExperienceTest#medicationDraftEditsAndHistoryRetainOldDose` | 全量负载下 5 s 等数据库计划数超时，单跑通过 | 两处等待放宽到 20 s（仍等产品真实结果） |
| `BackupFailureNotifierTest#foreignWriterBlockAlertsImmediatelyWithoutExposingDetails` 等 2 项 | `postIfRequired` 返回 true 但 `activeNotifications` 立即为空（系统通知列表异步可见） | 新增 `awaitPosted()` 最多等 5 s；属等待系统状态，不放宽“不泄露敏感信息”断言 |
| `NavigationExperienceTest#cloudConnectionValidatesBeforeNetworkAndClearsUnsavedPasswordOnReturn` | 全量/整类运行下 15 s 等控制器错误文案超时，单跑通过 | **本轮未改**；最终全量矩阵中通过，但仍属已知顺序敏感项 |

最终全量矩阵 `OK (379 tests)`，上述项全部通过。

## 3. 未修复的实质缺口（需决策或后续排期）

### 3.1 自制提醒未进入结构化导出（功能缺口）

- `core/backup/LogicalSnapshot` 与 `IsolatedRestore`、`SnapshotReadback`、`SnapshotRelations` 均包含 `customReminders`，**加密备份/恢复完整覆盖自定义提醒**。
- 但 `core/export/ExportScope.ALL_SECTIONS` 仍为 13 段（`journal_entries` 为止），`ExportFieldCatalog`、`ExportRowMapper`、`ExportService`、`ExportScreen.sectionLabel` 均无 `custom_reminders`。
- 结论：明文结构化导出与就诊摘要 PDF 不包含用户自建提醒。这不是测试遗漏，而是 schema 14 新增表之后未回填导出链路。
- 未直接修改原因：导出是用户可见的数据契约（字段说明、来源索引、UI 选项、验收报告口径），需先确认产品预期后再改，避免单方面改变导出格式。

### 3.2 文档记录陈旧（影响验收口径）

| 文件 | 现状 | 应为 |
|---|---|---|
| `docs/数据字典.md` | schema 13、19 张表、迁移 1→13 | schema 15；需补 `custom_reminders` 表与 `prenatal_visits.actualTime/actualHospital/actualResult` |
| `docs/已知限制.md` | 对应 `0.25.0-dev` / vc26 / schema13 | `0.26.0-dev` / vc27 / schema15 |
| `docs/测试验收报告.md` | 头部写 schema14；JVM 331 项、API36 292 项等旧数字 | 补 350 项 JVM / 379 项矩阵 |
| `docs/备份格式.md` | 对应 `0.22.0-dev` / schema13 | 更新版本行 |
| `docs/用户指南.md` | 对应 `0.22.0-dev` | 更新版本行 |
| `README.md` | “已进入M4开发，当前源码为 `0.22.0-dev`”、数据字典条目写 schema13 | `0.26.0-dev`，并修正链接说明 |

### 3.3 明确的未实现 UI 细节（来自现有记录，本轮未改动）

- N07 自定义提醒编辑：返回后不保留表单草稿（设计稿说明未实现）。
- N18 药品编辑：无独立药品备注字段（`DrugForm` 无对应模型）；包装照片/关联方案说明行无即时跳转。
- N22 单份资料卡仍高于示意稿；清理中状态、混合类型未验收。
- N20 设计稿 Ardot N20-final 的 2026 年 10 月日期列顺序有误（App 保留真实星期），需修设计稿。

## 4. 设备/矩阵未验收范围（沿用既有记录）

- 真机：小米 11 Ultra / 14 Pro 的逐屏视觉、锁屏通知视觉、应用锁完整 PIN 链路（需用户设置设备锁）、长时间待机与 OEM 强杀。
- 真实服务商：WebDAV 联测（配额/限流/DELETE/last-modified 行为）。
- 极端配置：N22/N21 横屏与大字深色、TalkBack 全页面读屏、真实低内存。
- 发布：**未生成签名包**（签名密钥需仓库外管理），版本号与备份格式版本未冻结为正式发布号。

## 5. 复现命令

```bash
# 单元测试
./gradlew --gradle-user-home .gradle-user testDebugUnitTest --offline

# lint
./gradlew --gradle-user-home .gradle-user lintDebug --offline

# 模拟器（项目本地 AVD）
ANDROID_AVD_HOME="$PWD/.local/avd" "$ANDROID_HOME/emulator/emulator" -avd PJ_API36_16K -no-snapshot-load -no-boot-anim -gpu swiftshader_indirect -no-audio

# 仪器矩阵
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner
```

日志：`.local/full-matrix-2026-11-12.log`（首轮 3 失败）、`.local/full-matrix-2026-11-12-r2.log`、`.local/full-matrix-2026-11-12-r3.log`、`.local/full-matrix-2026-11-12-final.log`（最终 `OK (379 tests)`）。

## 6. 本轮结论

- 代码基线健康：JVM 351/351、lint 0 错误、API36/16 KiB 全量 379/379（使用重新打包的 app APK）。
- 修掉 1 个生产缺陷（备份 schema 版本标记）与 5 项测试同步问题。
- 仍开放的实质问题：结构化导出缺 `custom_reminders`；N07/N18/N22/N20 的 UI 细节；签名包与版本号冻结；真机与服务商联测。
- 本轮仅模拟器 + JVM，**未安装真机、未做视觉验收**，不改变 M6 各画板的验收状态。
