# API36 / 16KiB 页全量回归证据（2026-10-22）

关联任务：E01 自动化矩阵与目标 API36、E02 发布前回归；覆盖当日新增的 A02/A03/D02/D05/D06/D07 设备用例与 API26 兼容性修复后的生产门禁。

## 环境与配置（readback 原文见 `device-info-api36.txt`）

- 设备：`emulator-5556`，AVD `PJ_API36_16K`，Android 16（SDK 36），`model=sdk_gphone16k_x86_64`，`abi=x86_64`，`PAGE_SIZE=16384`（16 KiB 页），`1080x2400 @ 420dpi`。
- 构建：`0.25.0-dev` / `versionCode 26` / `schema 13`（`app-debug.apk`、`app-debug-androidTest.apk`，来源 `:app:assembleDebug :app:assembleDebugAndroidTest`）。
- 配置基线（运行前 readback）：`font_scale=1.0`、`Night mode: no`、竖屏 `cur=1080x2400`；运行后再次 readback 一致。
- 前置：`adb shell pm clear cn.yunji.journal` 后 `install -r -t -g` 安装应用与测试 APK，避免历史数据影响。

## 命令

```
adb -s emulator-5556 shell am instrument -w -r cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner
```

## 结果

- **292 项执行 / 291 通过 / 1 失败 / 0 错误**，耗时 554.7s（`device-full-api36.log`）。
- 唯一失败：`NavigationExperienceTest#scheduledIntakeEditDeleteUndoAndManualHistoryKeepOriginalPlan`
  `androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 10000 ms`
  （`NavigationExperienceTest.kt:276`，等待「保存服药记录」后 `journalDao().allIntakes().size == 1`）。
- 同一构建的对照运行：
  - 整类运行 `-e class cn.yunji.journal.NavigationExperienceTest` → **`OK (23 tests)`**（`class-navigation-experience.log`）。
  - 单用例运行（干净应用数据）→ **`OK (1 test)`**（`isolated-flaky-test.log`）。
  - 该用例在完整套件中连续 3 次全量运行均失败（另一次 19:09 全量同为 292/1），本目录只保留最后一次全量日志。

## 失败项定位与边界（如实记录）

- **是测试侧不稳定性，不是数据损坏**：失败时编辑页仍在前台、已输入剂量仍在、数据库 `all_intakes` 为 0（无半条记录），不存在写入一半或覆盖既有记录的情况；编辑页保留使人工可重试。
- **顺序/状态敏感**：把 `LockEntryMatrixTest` 或 `MainRestorePickerTest` 与目标用例串行运行可复现（各 1 次失败），而 `MeasurementRepositoryTest` / `MedicationRepositoryTest` / `LocalRestoreCloudTest` / `MainRestoreCommitTest` 与目标用例串行均通过；整类运行稳定通过。完整套件中先运行的真实 `MainActivity` 用例（锁定入口、恢复向导）与合成 Compose 场景共享进程级状态，是可复现的路径。
- 尝试过两种测试侧同步改进（点击后 400ms 静置；改为等待产品反馈文案「已保存到本机」再断言数据库），复现率由约 1/2 降到约 1/6，但**均未达到确定性**，因此未保留改动，测试文件保持团队交付时的原状。
- 该失败在本轮改动之前已存在：`docs/测试验收报告.md` 4.2 记录的上一轮全量（293 项 1 失败）即同一用例同一异常。本轮不新增产品缺陷结论。
- 结论：**API36 全量回归 291/292 通过、0 错误；1 项为既有测试同步缺陷（order-sensitive flake），列为 E02 发布前必须修复/隔离的遗留项，不据此宣称全绿**。

## 覆盖与未覆盖

- 覆盖：JVM 后整套设备仪器用例（含 A02 极端矩阵、A03 锁定/进程矩阵、D02 进程终止矩阵、D05 回收、D06 清理 UI、D07 分享、E01 页面矩阵、API26 兼容相关用例）在 API36 / 16 KiB 页上的完整执行。
- 未覆盖（环境或用户条件阻塞，见 `docs/已知限制.md`）：真机与 OEM 后台限制、真实 WebDAV 服务商联测、系统签名发布包（密钥未授权）、TalkBack 实际读屏与对比度量化、真实断电。

## 产物

- `device-full-api36.log`：完整 `am instrument` 输出（含 292 项状态与失败栈）。
- `device-info-api36.txt`：版本、设备、页大小、显示、时区与配置 readback。
- `class-navigation-experience.log`：整类对照运行。
- `isolated-flaky-test.log`：单用例干净状态运行。
