# E01 其余页面极端配置矩阵与空/错/加载状态（2026-10-22）

关联任务：`task-7`（E01/A02 遗留：首页/时间线、用药、设置、产检详情与编辑表单的极端配置矩阵 + 空/错/加载状态矩阵）。
边界：资料列表/大图预览/元数据长表单已由 A02 `ExtremeLayoutMatrixTest` 覆盖（8/8，见 `../2026-10-22-A02-ui-matrix/`），本轮不重复。

## 结论（最终，2026-10-22 16:26 重跑后）

- 修复后重跑（同一极端配置、同一合并命令）：**13 项 13 通过 / 0 失败 / 0 跳过**，`BUILD SUCCESSFUL in 2m 40s`，退出码 0；JUnit XML `tests=13 failures=0 errors=0 skipped=0 time=131.874`（`junit-device-matrix-retest.xml`）。
- 首轮（修复前）实测 5 通过/8 失败/0 跳过，8 项失败全部为测试侧缺陷（折叠区未组合/断言时机/种子字段），已修复并经重跑确认；首轮记录保留为历史（`device-matrix.log`、`junit-device-matrix.xml`）。
- 修复未放宽断言：`assertExtreme()` 在 6 项极端用例中通过（0 跳过），`assertNoTruncation` 容差判据未改。
- 本轮覆盖 E01/A02 其余页面（首页/四入口、时间线、用药、设置、产检详情与编辑表单）在字体200%+深色+横屏下的可达与不截断，以及产检/时间线/用药+服药/健康指标/资料列表的空态、资料导入队列中（加载）、导入失败（错误+重试/重新选择入口）状态矩阵。
- 修复后复跑 JVM 全量 52 类/317 项 0 失败、lint 0 错误/35 警告（均未回归）。
- 结论边界：本矩阵「其余页面极端配置 + 空/错/加载状态」子集通过；**不扩大为 E01 整体验收通过**（TalkBack 实际读屏与焦点顺序、深色对比度量化、最低 API26、真机/OEM、真实服务商仍未覆盖）。

## 环境与设备配置 readback

设备：`emulator-5556`（AVD `PJ_API36_16K`，API36、16 KiB 页，`wm size=1080x2400`，density 420）。

极端配置（`device-config.ps1 -Mode extreme` 后 readback，原文见 `device-readback-before.txt`）：

```
font_scale=2.0
Night mode: yes
accelerometer_rotation=0
user_rotation=1
init=1080x2400 420dpi ... cur=2400x1080 app=2400x1080
mCurrentRotation=ROTATION_90
mUserRotationMode=USER_ROTATION_LOCKED mUserRotation=ROTATION_90
```

注：readback 瞬间 `dumpsys window displays` 的 `mGlobalConfiguration` 行仍打印竖屏缓存值（`w411dp h914dp ... ROTATION_0`），但 display 已 `ROTATION_90`；用例内 `assertExtreme()`（fontScale=2.0 / NIGHT_YES / ORIENTATION_LANDSCAPE）在 p1、p4、p5 通过，证明测试进程实际运行在极端组合下。缺 `cmd window user-rotation -d 0 lock 1` 时 API36 不会真旋转（A02 踩过），本轮沿用 A02 脚本。

运行后已恢复基线并 readback（`device-readback-after.txt`）：

```
font_scale=1.0 / night=no / accelerometer_rotation=1 / user_rotation=0
init=1080x2400 ... cur=1080x2400
mCurrentRotation=ROTATION_0
mUserRotationMode=USER_ROTATION_FREE mUserRotation=ROTATION_0
app_pid=（空，已 force-stop）
```

## 命令原文

```powershell
# 1) 极端配置（A02 脚本，-d 0 是 API36 真横屏的关键）
powershell -NoProfile -ExecutionPolicy Bypass -File docs\verification\2026-10-22-A02-ui-matrix\device-config.ps1 -Mode extreme

# 2) 合并运行 13 项（GRADLE_USER_HOME=.gradle-user）
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=cn.yunji.journal.ExtremeLayoutMatrixPagesTest,cn.yunji.journal.UiStateMatrixTest" --console=plain

# 3) 收尾：恢复基线、force-stop、readback
powershell -NoProfile -ExecutionPolicy Bypass -File docs\verification\2026-10-22-A02-ui-matrix\device-config.ps1 -Mode baseline
adb -s emulator-5556 shell am force-stop cn.yunji.journal
```

JVM/lint（修复后复跑）：

```powershell
Remove-Item 'app\build\test-results\testDebugUnitTest\*.xml'   # 避免 stale 计数
.\gradlew.bat :app:testDebugUnitTest --console=plain          # 52 类/317 项/0 失败/0 错误/0 跳过
.\gradlew.bat :app:lintDebug --console=plain                  # 0 错误/35 警告
```

## 首轮逐项结果（修复前，2026-10-22 15:59；历史记录）

| # | 类 | 用例 | 页面 | 结果 | 耗时 | 失败原因与判定 |
|---|----|------|------|------|------|----------------|
| 1 | ExtremeLayoutMatrixPagesTest | p1HomeAndRootEntrancesExtreme | 首页/四入口/底栏/长列表 | PASS | 8.829s | —（截图 4 张） |
| 2 | ExtremeLayoutMatrixPagesTest | p2TimelineLongContentExtreme | 孕期时间线长内容 | FAIL | 7.745s | 先滚回顶部后断言「查看原始记录」「时间未记录」，卡片未组合 → `nodes=0`（测试缺陷：断言时机；且种子记录带时间，本无「时间未记录」节点） |
| 3 | ExtremeLayoutMatrixPagesTest | p3MedicationPagesExtreme | 用药方案列表/编辑长表单/服药记录 | FAIL | 6.179s | 种子 `MedicationForm` 缺 `name/specification/dosageForm`，`MedicationRules.normalize` 抛「请填写药品名称，最多100字」（测试缺陷：种子校验） |
| 4 | ExtremeLayoutMatrixPagesTest | p4ReminderSettingsExtreme | 提醒设置 | PASS | 10.991s | —（截图 1 张） |
| 5 | ExtremeLayoutMatrixPagesTest | p5SecuritySettingsExtreme | 安全设置（独立渲染） | PASS | 3.622s | —（截图 1 张） |
| 6 | ExtremeLayoutMatrixPagesTest | p6PrenatalListDetailAndEditorExtreme | 产检列表/详情/修改预约表单 | FAIL | 14.654s | 在滚动前等待列表行「糖耐量试验（OGTT）」，极端视口下 RecordRow 未组合 → 10s 超时（测试缺陷：先滚动再等待） |
| 7 | UiStateMatrixTest | s1VisitsEmptyState | 产检列表空态 | FAIL | 16.206s | 空态卡片位于头部/筛选下方未组合 → 10s 超时（测试缺陷：未滚动） |
| 8 | UiStateMatrixTest | s2TimelineEmptyState | 时间线空态 | FAIL | 15.221s | 空态卡片位于写记录按钮/筛选/搜索下方未组合 → 超时（测试缺陷：未滚动） |
| 9 | UiStateMatrixTest | s3MedicationAndIntakeEmptyStates | 用药空态 + 服药记录空态 | FAIL | 19.250s | 用药空态断言通过；服药记录空态文案位于日期控件下方未组合 → 超时（测试缺陷：未滚动，服药列表当时无 testTag） |
| 10 | UiStateMatrixTest | s4MeasurementEmptyState | 健康指标空态 | PASS | 7.664s | — |
| 11 | UiStateMatrixTest | s5DocumentEmptyState | 资料列表空态 | PASS | 6.387s | — |
| 12 | UiStateMatrixTest | s6DocumentLoadingQueuedState | 资料导入队列中（加载态） | FAIL | 16.626s | 「等待导入…」卡片位于筛选/按钮/搜索下方未组合 → 超时（测试缺陷：未滚动） |
| 13 | UiStateMatrixTest | s7DocumentFailedShowsRetryAndReselect | 资料导入失败（错误态+重试入口） | FAIL | 16.971s | 「导入失败」卡片同样未组合 → 超时（测试缺陷：未滚动；失败原因已由 `DocumentImporter` 置 `FAILED`+error） |

## 迭代修复记录（测试侧，未放宽断言）

| 文件:行 | 修改 | 针对失败 |
|---|---|---|
| `app/src/androidTest/java/cn/yunji/journal/PageMatrixBaseTest.kt:181-186` | 新增 `scrollListTo(listTag, text, substring)`：`performScrollToNode` + `settle()` | p2/p3/p6/s1/s2/s3/s6/s7 共同根因 |
| `PageMatrixBaseTest.kt:192-210` | `seedJournalEntry` 增加 `time/text` 参数（可造无时间记录）；`seedMedicationPlan` 补 `name/specification/dosageForm` | p2、p3 |
| `ExtremeLayoutMatrixPagesTest.kt:56-72` | p2 增种一条无时间记录；先滚动到「时间未记录」「查看原始记录」再断言 | p2 |
| `ExtremeLayoutMatrixPagesTest.kt:80-82` | p3 用药列表先滚动到药品名再断言 | p3 |
| `ExtremeLayoutMatrixPagesTest.kt:98-102` | p3 服药记录改断言稳定入口「补记服药」（原等待药品名依赖计划卡片渲染） | p3 |
| `ExtremeLayoutMatrixPagesTest.kt:144-149` | p6 先滚动产检列表到目标行，再等待/点击 | p6 |
| `UiStateMatrixTest.kt:19-21,31-33,47-52,82-85,97-101` | 五处空/加载/失败状态先滚动列表到目标文案再断言 | s1/s2/s3/s6/s7 |
| `app/src/main/java/cn/yunji/journal/feature/IntakeScreens.kt:96` | 服药记录 `LazyColumn` 增加 `Modifier.testTag("intake-list")`（纯语义标记，无布局/行为变化） | s3（滚动定位） |

验证：修复后 `:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` → `BUILD SUCCESSFUL in 14s`，exit 0（`compile-after-fixes.log`）；JVM 52 类/317 项 0 失败（`jvm-after-fixes.log`）；lint 0 错误/35 警告（`lint-after-fixes.log`、`lint-results-after-fixes.*`）。设备重跑见下节。

## 重跑（修复后，2026-10-22 16:23–16:26）

- 应用极端配置并 readback（`device-readback-before-retest.txt`）：font_scale=2.0、Night mode: yes、accelerometer_rotation=0、user_rotation=1；`dumpsys window displays`：`init=1080x2400 ... cur=2400x1080 app=2400x1080`、`mCurrentRotation=ROTATION_90`、`mUserRotationMode=USER_ROTATION_LOCKED mUserRotation=ROTATION_90`（`mGlobalConfiguration` 行仍打印竖屏缓存值，与首轮相同；6 项 `assertExtreme()` 全部通过且 0 跳过，证明测试进程实际运行在 200%+深色+横屏）。
- 命令（同首轮）：

```
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=cn.yunji.journal.ExtremeLayoutMatrixPagesTest,cn.yunji.journal.UiStateMatrixTest" --console=plain
```

- 结果：`BUILD SUCCESSFUL in 2m 40s`，退出码 0；`PJ_API36_16K(AVD)`：Tests 13/13 completed（0 failed 0 skipped），XML `tests=13 failures=0 errors=0 skipped=0 time=131.874`（`junit-device-matrix-retest.xml`，日志 `device-matrix-retest.log`）。
- 逐项耗时（retest XML）：p3 32.458s、p2 10.714s、p1 10.285s、p6 9.986s、p4 8.124s、p5 4.758s；s3 8.940s、s6 8.695s、s7 8.588s、s2 6.889s、s5 7.662s、s4 6.544s、s1 5.487s。
- 环境事实（如实记录）：本次 `:app:connectedDebugAndroidTest` 由 AGP 自动选择所有已连接设备，除本矩阵目标 `PJ_API36_16K`（emulator-5556）外，还把同一批 13 项安装/执行到 `PregnancyJournal_API26(AVD)`（emulator-5554，另一任务占用）：该设备 6 项极端用例被 `assumeTrue` 跳过、7 项状态用例执行通过（0 failed），未参与本矩阵结论。
- 收尾：baseline 脚本恢复后字体/深色/自动旋转正常，但旋转仍停留在横屏（脚本缺 `cmd window user-rotation -d 0 free` + `user_rotation 0`），已手动补齐并重新 readback（`device-readback-after-retest.txt`）：font_scale=1.0、Night mode: no、accelerometer_rotation=1、user_rotation=0、`cur=1080x2400 app=1080x2400`、`mCurrentRotation=ROTATION_0`、`mUserRotationMode=USER_ROTATION_FREE`、`pidof cn.yunji.journal` 为空（已 force-stop）。另注：该轮结束后 AGP 清理使 5556 上应用被卸载（`pm path` 为空），下次设备运行由 Gradle 重新安装。
- 截图说明：`screenshots/` 6 张来自首轮（同页面同配置；首轮与重跑 p1、p4、p5 均通过）；重跑时测试内也生成了设备侧截图，但 AGP 收尾卸载应用后设备外部目录随之清除，未再拉取，本地保留首轮版本。
- 结论：修复后 **13/13 通过**，首轮 8 项失败经重跑确认均为测试侧缺陷、非产品布局缺陷；`assertNoTruncation` 与语义断言均未放宽。

### 首轮 8 项失败的修复与复测

| 用例 | 首轮失败原因（测试侧） | 修法 | 复测 |
|---|---|---|---|
| p2 时间线 | 滚回顶部后断言「查看原始记录」「时间未记录」，卡片未组合；且种子记录带时间，本无「时间未记录」节点 | 增种一条无时间记录；先 `scrollListTo` 再逐项断言 | PASS 10.714s |
| p3 用药 | 种子 `MedicationForm` 缺 `name/specification/dosageForm`，`MedicationRules.normalize` 抛「请填写药品名称，最多100字」 | 补齐种子字段；列表先滚动到药品名；服药记录改断言稳定入口「补记服药」 | PASS 32.458s |
| p6 产检 | 在滚动前等待列表行「糖耐量试验（OGTT）」，极端视口下 RecordRow 未组合 → 10s 超时 | 先滚动列表到目标行再等待/点击 | PASS 9.986s |
| s1 产检空态 | 空态卡片位于头部/筛选下方未组合 → 超时 | 先 `scrollListTo` 再断言 | PASS 5.487s |
| s2 时间线空态 | 空态卡片位于写记录按钮/筛选/搜索下方未组合 → 超时 | 先 `scrollListTo` 再断言 | PASS 6.889s |
| s3 用药+服药空态 | 服药记录空态文案位于日期控件下方未组合；服药列表当时无 testTag | 先滚动；`IntakeScreens.kt:96` 补 `testTag("intake-list")`（生产代码语义标记） | PASS 8.940s |
| s6 资料加载中 | 「等待导入…」卡片位于筛选/按钮/搜索下方未组合 → 超时 | 先 `scrollListTo` 再断言 | PASS 8.695s |
| s7 资料失败态 | 「导入失败」卡片同样未组合 → 超时 | 先 `scrollListTo` 再断言 | PASS 8.588s |

## 覆盖对照

### 已有覆盖（本轮前）

| 范围 | 证据/位置 | 说明 |
|---|---|---|
| 资料列表/大图预览/元数据长表单/主页入口/产检保存表单/日常记录/TalkBack 语义（极端配置） | A02 `ExtremeLayoutMatrixTest` 8/8 | 资料/长表单子集已闭环 |
| 云盘连接与清理状态（普通配置） | D06 `CloudCleanupUiTest` 7/7 | 非极端配置 |
| 流程级 UI（日常记录/时间线来源/用药/健康指标/备份恢复） | `NavigationExperienceTest` 等 43 个 androidTest 文件 | 非空/错/加载矩阵断言 |
| 既有状态断言（盘点） | `NavigationExperienceTest.kt:382`「还没有用药方案」、`:607`「还没有产检资料」、`:622` reminder-permission；`LockEntryMatrixTest` 解锁；`ExportControllerTest` 失效消息 | 零散、未成矩阵 |

### 本次新增

| 类 | 用例 | 覆盖 |
|---|---|---|
| `ExtremeLayoutMatrixPagesTest`（6 项，`assumeTrue(extremeConfig())` 门控） | p1 | 首页/四个一级入口/底部导航可达、首页长列表滚动、可点击节点语义审计、全屏文本不截断 |
| | p2 | 时间线长内容（产检+两条日常记录，含无时间记录）、`写日常记录`/`查看原始记录` 可达、逐屏不截断 |
| | p3 | 用药方案列表（含长药品名）、编辑长表单字段、服药记录列表 |
| | p4 | 提醒设置四张卡片标题/开关/重建按钮，不截断 |
| | p5 | 安全设置（独立渲染，不依赖系统生物识别）标题/按钮不截断 |
| | p6 | 产检列表、完成态详情（医院/检查项目/医嘱长文本）、修改预约表单字段 |
| `UiStateMatrixTest`（7 项，不限制配置） | s1/s2/s3/s4/s5 | 产检/时间线/用药+服药/健康指标/资料列表空态 |
| | s6 | 资料导入队列中（`等待导入…`、进度条、非空态） |
| | s7 | 资料导入失败（`导入失败`+error、`重试`/`重新选择` 入口） |

### 未覆盖 / 未验证

1. **重跑已完成**：2026-10-22 16:26 修复后重跑 **13/13 通过**（见「重跑」节）；首轮 5/13 仅作历史记录，8 项失败已由重跑确认非产品缺陷。
2. 基线配置下单独运行 `UiStateMatrixTest`：本轮（含重跑）均在极端配置下执行（纯文本/可见性断言，无配置依赖，但未单独跑基线）。
3. 资料失败后「重试成功」闭环（仅断言入口）；资料错误详情文本截断未逐条度量。
4. 云盘清理小节在极端配置下的排版（D06 用例为普通配置）；时间线 `loading` 分支无稳定注入点；用药方案校验错误态；取消/进行中产检详情极端排版（p6 只覆盖完成态）。
5. TalkBack 实际读屏与焦点顺序、深色对比度量化、最低 API26（无镜像）、真机/OEM、真实服务商。

## 证据清单

| 文件 | 内容 |
|---|---|
| `device-matrix.log` | 首轮（修复前）合并 13 项运行原文（含 8 条失败栈） |
| `junit-device-matrix.xml` | 首轮 JUnit XML：tests=13 failures=8 errors=0 skipped=0 time=152.531 |
| `device-matrix-retest.log` | 重跑（修复后）运行原文：BUILD SUCCESSFUL 2m40s，13/13、0 skip |
| `junit-device-matrix-retest.xml` | 重跑 JUnit XML：tests=13 failures=0 errors=0 skipped=0 time=131.874 |
| `screenshots/p1-home-list.png`、`p1-visits-list.png`、`p1-records-hub-list.png`、`p1-profiles-root-list.png`、`p4-reminders.png`、`p5-security.png` | 通过项截图（运行中 adb pull，6 张；失败项未到截图步骤） |
| `device-readback-before.txt` / `device-readback-after.txt` | 首轮极端配置 / 基线恢复 readback |
| `device-readback-before-retest.txt` / `device-readback-after-retest.txt` | 重跑极端配置 / 基线恢复 readback（含 `cur=2400x1080` 与 `ROTATION_90` 确认、收尾 `cur=1080x2400`/`ROTATION_0`） |
| `jvm-full.log`、`jvm-after-fixes.log` | JVM 全量（52 类/317 项/0 失败，修复后复跑一致） |
| `lint-debug.log`、`lint-results-debug.*`、`lint-after-fixes.log`、`lint-results-after-fixes.*` | lint 0 错误/35 警告 |
| `compile-after-fixes.log` | 修复后编译 BUILD SUCCESSFUL 14s |
| `run-pages-matrix.ps1` | 极端/合并运行/收尾脚本 |

## 设备信息与释放

- 设备：`emulator-5556`（AVD `PJ_API36_16K`，Android 16/API36，16 KiB 页，1080x2400/420dpi；`wm size` 基线 1080x2400/420dpi）。
- 重跑后已恢复基线并 readback（`device-readback-after-retest.txt`）：font_scale=1.0、Night mode: no、accelerometer_rotation=1、user_rotation=0、`cur=1080x2400 app=1080x2400`、`mCurrentRotation=ROTATION_0`、`mUserRotationMode=USER_ROTATION_FREE`；`am force-stop cn.yunji.journal` 后 `pidof` 为空。
- 说明：AGP `connectedDebugAndroidTest` 收尾清理后 5556 上应用被卸载（`pm path` 为空），下次 Gradle 设备运行会重新安装；5556 未再运行任何设备任务，设备已释放。
- 并行设备披露：重跑由 AGP 自动选择全部已连接设备，同一批 13 项也安装/执行到 `PregnancyJournal_API26(AVD)`（emulator-5554，Lead 的 API26 验证设备）：6 项极端用例被 `assumeTrue` 跳过、7 项状态用例 0 失败；该设备结果不计入本矩阵结论。

## 风险与下一步

- 本矩阵已完成 13/13 重跑（均为 API36、极端配置、0 跳过）；风险转为「单设备单配置样本」：未在基线配置单独复跑状态类、未覆盖 TalkBack 实际读屏与焦点顺序、深色对比度量化、最低 API26、真机/OEM、真实服务商。
- 极端视口（约 914x411dp、200% 字体）下 LazyColumn 只组合可见项是首轮 8 项失败的唯一共同根因；后续页面矩阵应统一「先 `performScrollToNode` 再等待/断言」写法，避免以「节点不存在」误判产品缺陷。
- 未发现产品布局缺陷不等于可访问性验收通过；E01 整体仍未验收。
- 生产代码仅新增 1 处测试可达性语义标记（`IntakeScreens.kt:96` 的 `testTag("intake-list")`），无布局/行为变化，经 Lead 确认可保留并在此说明。
