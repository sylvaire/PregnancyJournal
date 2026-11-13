# E02 服药保存用例顺序敏感 flake 修复（API36 / 16KiB 页）

- 日期：2026-10-23（Asia/Shanghai；设备时区 GMT）。设备：专用 `emulator-5556`，AVD `PJ_API36_16K`（Android 16 / SDK 36 / `PAGE_SIZE=16384` / 1080x2400 420dpi，`model=sdk_gphone16k_x86_64`）；基线 `font_scale=1.0`、浅色、竖屏，跑前读回见 [`device-info.txt`](device-info.txt)。**未在个人手机运行任何清库型测试。**
- 构建：`0.26.0-dev` / `versionCode 27` / schema14，`.\gradlew.bat --gradle-user-home .gradle-user --offline :app:assembleDebug :app:assembleDebugAndroidTest`；`pm clear cn.yunji.journal` 后安装应用与测试 APK。
- 任务：E02 发布阻断项 `NavigationExperienceTest#scheduledIntakeEditDeleteUndoAndManualHistoryKeepOriginalPlan` 偶发 `ComposeTimeoutException`（保存服药后等待 10s 的 `all_intakes=1` 超时）。不得放宽产品断言、不得改生产代码。

## 基线复现（原始代码、断言未改）

串行执行：`am instrument -w -r -e class 'cn.yunji.journal.LockEntryMatrixTest,cn.yunji.journal.NavigationExperienceTest#scheduledIntakeEditDeleteUndoAndManualHistoryKeepOriginalPlan'`。

| 轮次 | 前置条件 | 结果 | 失败形态 |
| --- | --- | --- | --- |
| [baseline-attempt1](baseline-attempt1.log) | `pm clear` + 全新安装后的第一轮（输入法进程冷启动） | **失败** | `保存服药记录 is not displayed`（点击前 `assertIsDisplayed` 失败） |
| [baseline-attempt2](baseline-attempt2.log) | 同上，输入法已预热 | 通过 | `OK (7 tests)` |
| [baseline-attempt3](baseline-attempt3.log) | 同上 | 通过 | `OK (7 tests)` |
| [baseline-attempt4](baseline-attempt4.log) | 同上 | 通过 | `OK (7 tests)` |
| [baseline-cold-ime-1](baseline-cold-ime-1.log) | 每轮前 `am force-stop com.google.android.inputmethod.latin` 制造冷输入法 | 通过 | `OK (7 tests)` |
| [baseline-cold-ime-2](baseline-cold-ime-2.log) | 同上 | **失败** | `保存服药记录 is not displayed` |
| [baseline-cold-ime-3](baseline-cold-ime-3.log) | 同上 | **失败（与报告一致）** | `ComposeTimeoutException`，诊断 `记录数=0；ready=true；busy=false；计划状态=ACTIVE`；[XML](baseline-cold-ime-3.xml) `tests=7 failures=1` |

- 结论：基线复现成立，且可稳定制造——**冷输入法进程下 3 轮有 2 轮失败**（1 次 `assertIsDisplayed`、1 次报告中的 10s 保存超时）；输入法预热后连续 4 轮通过，解释了“整类/单独跑通过、与其它类串行时更易复现”的历史现象。
- 失败现场（[baseline-cold-ime-3-logcat](baseline-cold-ime-3-logcat.txt)）：
  - 语义树显示编辑页滚动容器被压缩到 `y=296..634`（`VerticalScrollAxisRange value=809, maxValue=1406`），保存按钮未裁剪坐标 `y=946..1083` 已落在键盘窗口覆盖区 → 不可见/点击落点在应用窗口之外。
  - IME 时间线：应用 `onRequestShow 15:21:31.363` → 输入法 `onShown 15:21:32.724`（**1.36s 延迟**）；点击发生在键盘 inset 动画期间，保存回调未执行，随后 `all_intakes` 等待 10s 超时。
  - 通过轮次的对照：IME 显示请求在测试点击完成前被取消（从未 `onShown`），点击时表单仍为全高视口。参见各轮 `*-logcat.txt`。

## 根因分析

- **不是产品写入缺陷**：失败时 `formReady=true`、`busy=false`、计划项 `ACTIVE`、数据库无半条记录，同一 `IntakeForm` 直接调用 `IntakeRepository.save` 可成功（2026-10-22 E01 已记录并复核）。本轮未改任何 `app/src/main` 代码。
- **是测试输入与软键盘的同步缺陷**：Compose 通过 insets API 异步请求软键盘；输入法进程冷启动/系统负载时，从请求到 `onShown` 可超过 1 秒。测试在 `performTextReplacement` 后只等待 `waitForIdle()+350ms`（`settle()`）就滚动并点击保存按钮，键盘窗口恰好在这期间出现：应用窗口被 `imePadding()` 压缩，按钮滑到键盘窗口下方，真实触摸事件被键盘窗口接收（或落点已失效），保存回调不执行，最终 10s 等待超时或 `assertIsDisplayed` 失败。
- **顺序敏感性来源**：只有本用例是“进程内首次触发键盘（或输入法服务被回收）”时才有 >1s 的显示延迟；整类运行/输入法预热后延迟很短，因此整类与干净单用例通过。此前“点击后静置 400ms”“改为等待产品‘已保存到本机’反馈”只是错开时间或不检查点击是否生效，故只能降低复现率。

## 改动文件与理由（仅测试代码）

`app/src/androidTest/java/cn/yunji/journal/NavigationExperienceTest.kt`：

- 新增私有辅助 `clickSaveAfterKeyboardSettles(label)`：
  1. 通过 `WindowInsetsControllerCompat.hide(Type.ime())` 主动收起软键盘（用户在表单里点击保存前的正常动作，不涉及产品代码）；
  2. 循环 `waitForIdle()` 直到 **IME 可见性与保存按钮 `boundsInRoot` 连续 700ms 不再变化**（上限 15s，超时抛出带 ime/bounds 的明确断言错误）；
  3. 再 `performScrollTo()` + `assertIsDisplayed()` + 真实 `performClick()`（保持真实触摸点击），随后 `settle()`。
  - 键盘收起失败（例如硬件键盘模式、系统未响应）时也会等到键盘完全展开且布局稳定后再滚动点击：此时视口变小，但 `performScrollTo()` 仍能把按钮滚入可视区（该滚动容器 `maxValue=1406` 足够），两条路径都确定。
- 使用位置共 5 处（均为“先输入文本、再点击表单保存”的同类路径）：
  - `scheduledIntakeEditDeleteUndoAndManualHistoryKeepOriginalPlan` 三处保存：首次保存、修改记录保存、补记保存；
  - `journalDraftSourceEditDeleteUndoAndTimelineFilterSurviveBack` 两处保存：该用例在 2026-10-22 E01 增量的全量中已出现过同签名超时（`full-one-click-settled.log`），本轮一并接入同一辅助，避免同类顺序敏感问题复发。
- **未放宽任何产品断言、未改生产代码、未改测试断言语义**：仍校验数据库记录数/剂量/计划版本/回收站/首页统计与界面文案；只把真实点击的时机与键盘 inset 对齐。
- 辅助代码对 API 级别安全：`WindowInsetsControllerCompat`/`ViewCompat` 在 API<30 走兼容实现（该类也在 API26 矩阵中运行）。

## 验证结果

1. **串行前置（冷输入法，最不利条件）+ 目标用例连续 3 次**：每轮前 `am force-stop com.google.android.inputmethod.latin`，先 `LockEntryMatrixTest` 再目标用例，3 轮均 `OK (7 tests)`：[fixed-cold-ime-1](fixed-cold-ime-1.log) / [2](fixed-cold-ime-2.log) / [3](fixed-cold-ime-3.log)（XML：[1](fixed-cold-ime-1.xml) / [2](fixed-cold-ime-2.xml) / [3](fixed-cold-ime-3.xml)，各 `tests=7 failures=0`）。
2. **`NavigationExperienceTest` 整类**：`OK (23 tests)`，214.6s（[fixed-navigation-class-rerun.log](fixed-navigation-class-rerun.log) / [XML](fixed-navigation-class.xml) `tests=23 failures=0 errors=0`）。
   - 首次整类尝试（[fixed-navigation-class.log](fixed-navigation-class.log)）在第 3 个用例处因 Compose 测试框架销毁 Activity 崩溃（`Must run runDetachLifecycle() ... Process crashed`，未执行到本改动的用例；属既有环境/框架故障），重跑通过；如实保留原始日志，不把崩溃计为通过。
3. **API36/16KiB 全量 `am instrument`**：
   - 第 1 轮：[full-api36-round1.log](full-api36-round1.log) → **`OK (338 tests)`，764.338s，0 失败 0 错误、61 项前置条件跳过**；[XML](full-api36-round1.xml) `tests=338 failures=0 errors=0 skipped=61`。
   - 第 2 轮首次尝试（[full-api36-round2.log](full-api36-round2.log)）在 `current=232/338` 处因**模拟器进程退出**中断（无任何测试失败状态；`adb devices` 中设备消失），按环境中断如实记录，**不计入通过或失败**。
   - 重启 AVD（`-no-snapshot-load` 冷启动，[emulator-restart-stdout.log](emulator-restart-stdout.log)/[stderr](emulator-restart-stderr.log)）后重跑第 2 轮：[full-api36-round2-retry.log](full-api36-round2-retry.log) → **`OK (338 tests)`，690.716s，0 失败 0 错误、61 项前置条件跳过**；[XML](full-api36-round2-retry.xml) `tests=338 failures=0 errors=0 skipped=61`。
   - 两轮全量合计：**676 项次执行、0 失败、0 错误**；跳过项按各类原有前置条件解释（与 2026-10-22 全量一致的 42 项及后续新增门控），不能折算为通过。
4. **JVM 单测与 lint**：`:app:testDebugUnitTest` → **56 个测试类 / 342 项 / 0 失败 / 0 错误 / 0 跳过**（[jvm-and-lint.log](jvm-and-lint.log)，JUnit XML 汇总自 `app/build/test-results/testDebugUnitTest/TEST-*.xml`）；`:app:lintDebug` → **0 错误 / 27 警告**（[lint-results-debug.txt](lint-results-debug.txt)，警告均为既有类别：13 `NewerVersionAvailable`、11 `UseKtx`、1 `AndroidGradlePluginVersion`、1 `BatteryLife`、1 `GradleDependency`，无一条指向本次改动文件）。改动前（19:58 生成的 C05 快照报告）同为 27 警告、同一组 ID，本轮无新增告警。JVM 测试任务因主代码未变化曾为 UP-TO-DATE，本次已删除结果目录强制重跑确认。

## 未覆盖边界与遗留风险

- 修复针对“真实触摸在软键盘出现的窗口期内丢失”的测试同步问题；产品在真实用户场景下的行为未改变（用户看到键盘遮挡时会滚动或再次点击）。未新增/放宽产品断言，未改生产代码。
- 本轮只接入有失败证据的两个用例（3+2 处保存点击）；其余“先输入文本、再点击保存”的用例路径（如测量保存、用药方案保存、资料保存等）仍使用原有 `settle()` 同步，冷输入法+重负载下理论上有同类风险，未逐一改动（避免扩大改动面）。若后续全量在冷输入法下再现同类失败，可按同一辅助扩展。
- 15s 稳定窗口上限：若系统极端卡顿导致键盘 inset 超过 15s 才稳定，测试会以明确的自描述断言失败（而不是静默超时），仍需按环境问题排查。
- 模拟器环境本身的稳定性未增强：16KiB/2.5GiB 内存下长跑仍可能遇到 `system_server` 重启、模拟器进程退出等中断（本轮第 2 轮首跑即中断）；中断不折算为通过。
- 真机/OEM、真实用户输入法（第三方键盘收起行为差异）、低内存与长时运行仍未覆盖；本证据只证明 API36/16KiB 模拟器自动化矩阵闭环。

## 产物

- 基线：`baseline-attempt1..4.log`、`baseline-cold-ime-1..3.log`、对应 `*-logcat.txt`、`baseline-cold-ime-3.xml`。
- 修复后：`fixed-cold-ime-1..3.log/.xml`、`fixed-navigation-class.log`、`fixed-navigation-class-rerun.log/.xml`、对应 `*-logcat.txt`。
- 全量：`full-api36-round1.log/.xml`、`full-api36-round2.log`（中断）、`full-api36-round2-retry.log/.xml`、对应 `*-logcat.txt`；执行命令均为 `adb -s emulator-5556 shell am instrument -w -r cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner`（每轮前 `pm clear cn.yunji.journal` 并覆盖安装应用/测试 APK）。
- 设备与工具：`device-info.txt`（跑前/跑后读回，基线已恢复：`font_scale=1.0`、浅色、竖屏、GMT）、`emulator-restart-*.log`、`make-junit-xml.ps1`（把 `am instrument -w -r` 原始输出按 status code 映射为 JUnit XML 的脚本；`am instrument` 本身不产出 XML，原始日志仍为第一证据）。


## Lead 独立复核（2026-10-24 00:40 Asia/Shanghai）

- 重新安装当前 `app-debug.apk`（0.26.0-dev / vc27 / schema14）与 `app-debug-androidTest.apk`（构建于测试源码修改之后），`pm clear cn.yunji.journal`，并 `am force-stop com.google.android.inputmethod.latin` 制造冷输入法。
- 串行执行 `LockEntryMatrixTest` + 目标用例：**`OK (7 tests)`，33.013 s**（[lead-verification.log](lead-verification.log)）。
- 独立核对：JVM 测试结果目录 **56 类 / 342 项 / 0 失败 0 错误 0 跳过**；lint 结果 **0 错误 / 27 警告**；`full-api36-round1.xml` 与 `full-api36-round2-retry.xml` 均为 **tests=338 / failures=0 / errors=0 / skipped=61**。
- 复核结论：根因分析与修复方向与证据一致；改动仅测试代码，产品断言未放宽；冷输入法最不利条件不再复现保存丢失。

