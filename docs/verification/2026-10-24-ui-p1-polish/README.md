# P1 UI 收敛：JournalSwitch 对比度 / 空态统一 / 深色表面证据（2026-10-24）

- 对应共享任务：`task-10`（负责人 `ui-header`）。
- 背景：P0 修复（P0-1/2/3/4/6）与证据缺口（task-9）已完成；本任务收敛两项 P1 一致性项并补深色表面证据：
  1. **P1 Switch 对比度**：主题补齐后，M3 默认 Switch 的未选中滑块与轨道都映射到 `outline #DDE4DA`（滑块不可辨，对比度 1.00:1）；新增 `JournalSwitch` 显式两态配色。
  2. **P1-9 空态统一**：新增 `JournalEmptyState`（soft 卡 + 图标 + 20 sp 标题 + 可选副标题/动作），替换 6 个文件的纯文字空态，文案与 testTag 保持不变。
  3. **深色表面证据**（补 task-9 遗留）：`cmd uimode night yes` 下运行 `UiSurfaceSnapshotTest`（4 表面），量化深色容器色并确认无大面积浅色残留。
- 设备：`emulator-5556`（API36 / 1080×2400 / 420dpi = 411.43×914.29 dp，1 dp = 2.625 px）；基线浅色、竖屏、`font_scale=1.0`。
- 改动范围：`Components.kt` + 9 个 feature 文件（3 个 Switch 文件 + 6 个空态文件）；未改 `Theme.kt`/测试代码/主文档/Figma；未操作小米真机（11 Ultra 离线）。

---

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| 构建 | ✅ `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest` **BUILD SUCCESSFUL**（EXIT=0） |
| JVM 单测 | ✅ **342/342**（56 类，0 失败） |
| `JournalSwitch` 替换 | ✅ **6 处 / 3 文件**（CloudConnection ×2、Export ×2、ReminderSettings ×2），testTag/Modifier/回调语义全部保留；全仓已无裸 `Switch(` 调用（`Components.kt` 内 1 处委托） |
| Switch 未选中对比度 | ✅ 滑块 `#5F7166` / 轨道 `#E7F0E6` = **4.46:1 ≥ 3:1**（修复前 M3 默认滑块=轨道=`#DDE4DA`，**1.00:1**，滑块不可辨）；滑块/描边 `#DDE4DA` = 4.01:1 |
| Switch 选中对比度 | ✅ 滑块白 / 轨道 `#356B53` = **6.21:1**（与修复前一致，显式固定） |
| `JournalEmptyState` 替换 | ✅ **8 处 / 6 文件**（Trash ×2、Prenatal ×1、Document ×2、Intake ×1、Drug ×1、SafetyCopies ×1），原文案与 testTag 不变；保留 3 处既有 soft 卡空态（用药/测量/时间线）并说明 |
| 空态前后对比 | ✅ `trash-empty`：纯文字 → **soft 卡 364.19×96.00 dp**（含图标，占屏 8.7%）；`local-backup`（安全副本空态）：纯文字 → **soft 卡 364.19×97.52 dp**（8.9%）；before 两张均无 soft 卡 |
| 深色表面 | ✅ `cmd uimode night yes` → `UiSurfaceSnapshotTest` **OK (4 tests)**；容器色：AlertDialog **`#213228`**（320.00×236.19 dp）、DatePicker **`#213228`**（359.62×448.00 dp）、DropdownMenu **`#1B2A20`**（112.76×400.76 dp，深色 surfaceContainer）、Snackbar inverseSurface **`#E3EADF`**（362.67×47.24 dp） |
| 深色浅色残留 | ✅ 4 张中 `#F7F8F3` 精确命中 **0 px**；对话框/菜单/DatePicker 无 >5,000 px 的浅色连通域；仅 DropdownMenu 图中 149,690 px `#FFFFFF` 来自**用户资料缩略图**（测试夹具 PNG），非 UI 表面 |
| 回归测试 | ✅ Coverage **OK(17)** 52.498s、Supplementary **OK(9)** 39.572s、Surface(light) **OK(4)** 23.992s、NavigationExperienceTest **OK(23)** 193.550s、Surface(dark) **OK(4)** 24.891s —— 全部 0 失败，未改动任何既有断言 |
| 观察项 | ⚠️ `JournalEmptyState` 标题沿用 `JournalCard(soft)` 的 `onPrimaryContainer`（primary `#356B53`）；与设计基准的空态标题 ink `#223C32` 略有差异（见 §6 遗留，1 行可改） |

---

## 1. 改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `core/designsystem/Components.kt` | 新增 `JournalSwitch(checked, onCheckedChange, modifier, enabled)`：显式 `SwitchDefaults.colors` —— 选中轨道 `primary`/滑块 `onPrimary`；未选中轨道 `primaryContainer`(soft)/滑块 `onSurfaceVariant`(muted)/描边 `outline`；禁用态用 `onSurface` alpha 语义。<br>新增 `JournalEmptyState(title, subtitle=null, icon="86969.svg", action=null)`：`JournalCard(soft=true)` + `DesignIcon` + `titleLarge` 标题 + `bodySmall`/`onSurfaceVariant` 副标题 + 可选 `action`。 |
| 2 | `feature/CloudConnectionScreen.kt` | `Switch` → `JournalSwitch` ×2（`cloud-auto`、`cloud-wifi`，testTag/enabled/回调不变）+ import。 |
| 3 | `feature/ExportScreen.kt` | `Switch` → `JournalSwitch` ×2（`export-originals`、`export-trash`，testTag/enabled 不变）+ import。 |
| 4 | `feature/ReminderSettingsScreen.kt` | `Switch` → `JournalSwitch` ×2（`reminder-visits`、`reminder-medications`，testTag 不变）。 |
| 5 | `feature/TrashScreen.kt` | 空态 ×2：「全局回收站为空」「暂无保留的资料」→ `JournalEmptyState`（文案不变）。 |
| 6 | `feature/PrenatalScreens.kt` | 空态 ×1：「还没有产检记录 / 当前筛选下没有记录」+ 原副标题 → `JournalEmptyState`（文案不变）。 |
| 7 | `feature/DocumentScreen.kt` | 空态 ×2：`activeJobs.isEmpty()` 的「没有匹配的…/还没有产检资料/还没有包装照片」+ 原副标题 + 「清除搜索与筛选」动作 → `JournalEmptyState`；「回收站为空」→ `JournalEmptyState`。 |
| 8 | `feature/IntakeScreens.kt` | 空态 ×1：「还没有服药记录」→ `JournalEmptyState`。 |
| 9 | `feature/DrugScreens.kt` | 空态 ×1：「还没有药品，可以先录入基本信息，再创建用药方案。」→ `JournalEmptyState`。 |
| 10 | `feature/SafetyCopiesScreen.kt` | 空态 ×1：「还没有安全副本」→ `JournalEmptyState` + import。 |

> 代码核对（`_tools/code-replacements.txt`）：`JournalSwitch` 1 定义 + 6 调用；`JournalEmptyState` 1 定义 + 8 调用；全仓无裸 `Switch(`（只剩 `JournalSwitch` 内部 1 处委托）。

**保留未替换的 3 处空态（任务允许）**：`MedicationScreens.kt:37`（还没有用药方案）、`MeasurementScreens.kt:71`（还没有测量记录）、`TimelineScreens.kt:71`（还没有这类记录）—— 它们本就是 `JournalCard(soft=true)` + 标题 + 说明范式，已符合统一空态形态，仅缺图标；为避免本任务外扩散与截图回归风险，本轮保留原样。

---

## 2. Switch 两态配色与对比度（`measure_switch.py`）

### 2.1 设计令牌与对比度

| 状态/配对 | before（M3 默认经本主题映射） | after（JournalSwitch 显式） | WCAG |
|---|---|---|---|
| 未选中 滑块 / 轨道 | `#DDE4DA` / `#DDE4DA` = **1.00:1**（滑块不可辨） | `#5F7166` / `#E7F0E6` = **4.46:1** ✅ | ≥3:1 |
| 未选中 滑块 / 描边 | `#DDE4DA` / `#DDE4DA` | `#5F7166` / `#DDE4DA` = **4.01:1** | ≥3:1 |
| 选中 滑块 / 轨道 | 白 / `#356B53` = 6.21:1 | 白 / `#356B53` = **6.21:1** | AAA |
| 未选中 轨道 / 页面底色 | `#DDE4DA` / `#F7F8F3` = 1.22:1 | `#E7F0E6` / `#F7F8F3` = 1.09:1 | 装饰性（靠描边+滑块区分） |
| 选中 轨道 / 页面底色 | `#356B53` / `#F7F8F3` = 5.82:1 | 同左 | — |
| 描边 / 页面底色 | `#DDE4DA` / `#F7F8F3` = 1.22:1 | 同左（1 dp 描边，非文字） | — |

### 2.2 截图像素实测（浅色）

| 截图 | before | after |
|---|---|---|
| `export-screen.png` 选中开关 | 轨道 `#356B53` 51.43×32.00 dp，内部白色滑块 2,981 px | 轨道 `#356B53` 52.19×32.00 dp，白色滑块 2,981 px（不变） |
| `export-screen.png` 未选中开关 | 轨道 `#DDE4DA` 52.19×32.00 dp，内部**全部为 `#DDE4DA`**（滑块与轨道同色，9,896 px 不可辨） | 轨道 `#E7F0E6`（内部 6,335 px）+ 滑块 `#5F7166` **1,300 px** + 描边 `#DDE4DA` **644 px**（滑块+描边可辨） |
| `cloud-connection-backup.png` | 1 个可见开关（选中 `#356B53`） | 同位置选中 `#356B53`，白色滑块 2,982 px（不变） |
| `reminder-settings-custom.png` | 2 个选中开关（`#356B53`，白滑块 4,226/4,223 px） | 2 个选中开关（`#356B53`，白滑块 4,280/4,277 px） |

> 选中态 before/after 一致（显式固定为 `primary`+`onPrimary`）；未选中态由“滑块与轨道同为 outline、不可辨”变为“soft 轨道 + muted 滑块 + outline 描边”，视觉与设计标签体系（`JournalTag` 未选：白底 + outline 描边 + muted 字）同源。

---

## 3. 空态统一前后对比（`measure_empty.py`）

| 截图（页面） | before | after |
|---|---|---|
| `trash-empty.png`（全局回收站空态） | 纯文字「全局回收站为空」（无卡片/图标） | **soft 卡 364.19 × 96.00 dp @ (24.38, 243.81)，占屏 8.7%**；卡内 primary 图标像素 5,525（24 dp 图标 + 标题）；卡片 x0=24.38 dp 与页面 24 dp 边距一致 |
| `local-backup.png`（恢复前安全副本空态） | 纯文字「还没有安全副本」 | **soft 卡 364.19 × 97.52 dp @ (24.38, 600.38)，占屏 8.9%**；primary 图标像素 5,647；同页 52 dp 次要按钮 `JournalButton`（soft，364.19×53.33 dp）被正确区分 |
| before 两张 | soft 卡连通域 **0 个** | — |

- 空态卡结构：`JournalCard(soft=true)`（`#E7F0E6`、圆角 20）+ `DesignIcon`（24 dp，primary）+ `titleLarge`（20/30）+ `bodySmall/onSurfaceVariant` + 可选 action；与设计“浅绿卡 + 图标 + 标题 + 说明”的空态范式一致（设计 17 EmptyDocuments）。
- 文案字符串与 testTag 未改：`trash-empty`/`local-backup` 既有断言（`onAllNodesWithText("全局回收站为空")`、`"还没有安全副本"`）全部通过。

---

## 4. 深色表面证据（`dark-surfaces/` + `measure_dark_surfaces.py`）

方法：`cmd uimode night yes`（`am force-stop` 后新进程以深色配置启动）→ 运行 `UiSurfaceSnapshotTest`（4 用例，OK）→ pull 到 `dark-surfaces/` → `cmd uimode night no` 恢复。

| 表面 | 深色容器实测 | 尺寸 / 位置（dp） | 与主题令牌一致 |
|---|---|---|---|
| AlertDialog（删除确认） | **`#213228`** | 320.00×236.19 @ (45.71, 352.00) | ✅ `surfaceContainerHigh`（dark） |
| DatePickerDialog | **`#213228`** | 359.62×448.00 @ (25.90, 306.29)（另有 1 px 分割线分成两块） | ✅ `surfaceContainerHigh` |
| DropdownMenu | **`#1B2A20`**（Popup 卡片）；同图页面白卡被映射为 `#1D2921` | 112.76×400.76 @ (24.38, 158.48) | ✅ `surfaceContainer`（dark） |
| Snackbar（撤销） | **`#E3EADF`**（inverseSurface 亮条，深色文字） | 362.67×47.24 @ (24.38, 739.05) | ✅ `inverseSurface`（dark） |

**浅色残留检查**（4 张深色表面截图）：
- `#F7F8F3` 精确命中 **0 px**（4/4）；
- 无 >5,000 px 的浅色连通域（AlertDialog / DatePicker / Snackbar 均 0 个）；
- 例外（已归因，非 UI 残留）：DropdownMenu 图有 149,690 px `#FFFFFF`，其中 149,328 px 是一个 120.38×181.33 dp 的连通块 = **用户资料缩略图**（测试夹具的白色 PNG 报告图）在深色下的原始内容；AlertDialog/DatePicker 图各有 1,784 px `#FFFFFF` = 系统状态栏图标（UiAutomation 整屏截图附带），均无大面积 UI 面板。
- 深色背景（被 dialog scrim 压暗后的页面）主色为 `#080C09`/`#0C100D`，属深色 `#141D17` 叠加 32% scrim 的结果，非浅色。

---

## 5. 测试结果（emulator-5556，全部 0 失败）

| 测试类 | 结果 | Time |
|---|---|---|
| `ImeInsetLayoutTest`（历史回归，本轮顺带复核，可选） | 未在本轮重复运行 | — |
| `UiSnapshotCoverageTest`（浅色） | **OK (17 tests)** | 52.498 s |
| `UiSnapshotTest`（浅色） | **OK (9 tests)** | 39.572 s |
| `UiSurfaceSnapshotTest`（浅色） | **OK (4 tests)** | 23.992 s |
| `NavigationExperienceTest`（浅色） | **OK (23 tests)** | 193.550 s |
| `UiSurfaceSnapshotTest`（深色） | **OK (4 tests)** | 24.891 s |
| JVM `testDebugUnitTest` | **342/342**（56 类） | — |

- 构建与安装：`BUILD SUCCESSFUL`；`app-debug.apk` sha256 `18ABC03A…C4FE`、androidTest APK 未变（测试代码本轮未改）。
- 未修改/未放宽任何既有测试断言；`UiSnapshotCoverageTest`/`UiSnapshotTest` 在空态与 Switch 变更后均保持通过（`其断言基于文本/testTag`）。

---

## 6. 限制与遗留

| 限制 | 说明 |
|---|---|
| 模拟器渲染 ≠ 真机视觉 | 全部像素证据来自 API36 模拟器；真机（小米 11 Ultra，`FLAG_SECURE`）无法截图，结论限模拟器。 |
| 深色仅表面组件 | 深色只验证了 4 张表面截图（AlertDialog/DatePicker/Snackbar/DropdownMenu）与容器色；深色页面快照已在 task-9 覆盖（31 张）。 |
| 空态保留项 | `MedicationScreens`/`MeasurementScreens`/`TimelineScreens` 3 处既有 soft 卡空态按任务允许保留（无图标），未纳入本轮替换计数。 |
| 空态标题色 | `JournalEmptyState` 标题在 `JournalCard(soft)` 内继承 `onPrimaryContainer`（primary `#356B53`），与设计基准 ink `#223C32` 略异；对 `trash-empty`/`local-backup` 的 primary 图标像素统计已包含标题字形。可 1 行改为 `color = onSurface`，但会引起 `trash-empty`/`local-backup` 截图重跑，本轮为控制范围未改（列遗留）。 |
| Switch 禁用态 | 仅代码级显式定义（onSurface alpha 语义），未截禁用态像素证据；本轮浅色快照中无禁用 Switch 场景。 |
| 未跑全量矩阵/lint | 只跑任务要求的 4 类仪器测试 + 深色表面一次；未跑 API36 全量矩阵与 lint。 |

---

## 7. 产物清单

```
docs/verification/2026-10-24-ui-p1-polish/
├─ README.md                         本文件
├─ light/coverage/                   22 张浅色快照（含 export-screen 的 Switch、trash-empty/local-backup 的空态）
├─ light/supplementary/              9 张浅色补充快照
├─ light/surfaces/                   4 张浅色表面截图
├─ dark-surfaces/                    4 张深色表面截图（#213228 对话框/#1B2A20 菜单/#E3EADF Snackbar）
├─ measure_switch.py                 Switch 轨道/滑块/描边连通域与对比度测量
├─ measure_empty.py                  空态 soft 卡/图标/文案像素测量（before vs after）
├─ measure_dark_surfaces.py          深色表面容器色与浅色残留测量
├─ measurements-switch.json / _tools/measurements-switch.txt
├─ measurements-empty.json  / _tools/measurements-empty.txt
├─ measurements-dark-surfaces.json / _tools/measurements-dark-surfaces.txt
└─ _tools/
   ├─ build.log                      BUILD SUCCESSFUL
   ├─ unit-test-summary.txt          JVM 342/342
   ├─ apk-info.txt                   两个 APK 的 sha256
   ├─ install.log                    安装结果
   ├─ instrument-coverage-light.log  OK (17) 52.498s
   ├─ instrument-supplementary-light.log  OK (9) 39.572s
   ├─ instrument-surfaces-light.log  OK (4) 23.992s
   ├─ instrument-navigation-light.log OK (23) 193.550s
   ├─ instrument-surfaces-dark.log   OK (4) 24.891s
   ├─ code-replacements.txt          JournalSwitch/JournalEmptyState 调用点、无裸 Switch 残留、改动文件
   ├─ device-files-before/after.txt  设备截图清单
   ├─ device-baseline-before/after.txt  跑前/跑后设备状态
   ├─ emulator-baseline-restored.txt 基线恢复记录
   └─ run_p1.ps1                     浅色回归 + 深色表面两阶段采集脚本（可整段复跑）
```

---

## 8. 复现命令与基线恢复

```powershell
# 1) 构建 + JVM 单测
.\gradlew.bat --gradle-user-home .gradle-user --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest

# 2) 安装 + 浅色回归 + 深色表面采集（脚本内含 night 切换与恢复）
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 install -r -g -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
pwsh -NoProfile -ExecutionPolicy Bypass -File docs\verification\2026-10-24-ui-p1-polish\_tools\run_p1.ps1

# 3) 测量
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-p1-polish\measure_switch.py
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-p1-polish\measure_empty.py
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-p1-polish\measure_dark_surfaces.py
```

跑后基线恢复（`_tools/emulator-baseline-restored.txt`，2026-10-24T09:12:57+08:00）：`font_scale=1.0`、`cmd uimode night no`、`stay_on_while_plugged_in=0`、竖屏、`wm size=1080x2400`、`wm density=420`；`am force-stop cn.yunji.journal` 与 `cn.yunji.journal.test` 后无残留进程。未操作小米真机。

---

## 8. 补充修复：空态标题色（2026-10-24 追加）

- 问题：`JournalEmptyState` 标题在 soft 卡内继承 `onPrimaryContainer`（primary `#356B53`），与设计基准 ink `#223C32` 略异（上一轮遗留项 1）。
- 修复：`Components.kt` 的 `JournalEmptyState` 标题显式指定 `color = MaterialTheme.colorScheme.onBackground`（ink），其余样式/文案/testTag 不变。
- 验证：构建 + JVM 342/342；重跑 `UiSnapshotCoverageTest` **OK(17)**、`UiSnapshotTest` **OK(9)**；拉取刷新后的截图到 `after-color-fix/`、`after-color-fix-supplementary/`；对两张含空态的截图做 before↔after 像素 diff：
  - `trash-empty.png`：diff 8,216 px，bbox `[117,789]–[476,839]`（标题文字带），变更像素 before 主色 `#356B53` 4,903px → after 主色 `#223C32` 4,854px；
  - `local-backup.png`（安全副本空态）：diff 8,331 px，bbox `[117,1727]–[478,1777]`，before `#356B53` 5,028px → after `#223C32` 5,012px。
  - 结论：仅标题文字颜色变化，图标/卡片/布局未动（diff 仅出现在标题字形带）。
- 原始测量：`empty-title-color-fix.json`；截图：`after-color-fix/`、`after-color-fix-supplementary/`。
- 说明：本轮为纯颜色改动，未再跑全量矩阵（上一轮 P1 后全量矩阵 367/367 已通过；本改动后 JVM + 两套快照回归通过）。
