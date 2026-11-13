# UI 证据缺口补齐：Bug #3 视口回归断言 / 表面组件截图 / 深色主题验证（2026-10-24）

- 对应共享任务：`task-9`（负责人 `ui-header`，本任务替代新建 teammate）。
- 背景：UI P0-1/2/3/4/6 已完成并验证（`../2026-10-24-ui-consistency-fix/`、`../2026-10-24-ui-header-fix/`）；本任务补齐三处证据缺口：
  1. **Bug #3（键盘 IME 双内边距）回归断言**：修复后键盘弹出时内容视口不应再被压扁；
  2. **对话框/日期选择/Snackbar 等表面组件无像素证据**：补真实 App 状态截图 + 容器色测量；
  3. **深色主题未截图验证**：切 dark 跑现有快照测试并量化对比度。
- 设备：`emulator-5556`（PJ_API36_16K：Android 16 / API 36 / x86_64 / 16 KiB page / 1080×2400 / 420 dpi = 411.43×914.29 dp，密度 2.625）；基线 `font_scale=1.0`、浅色、竖屏。
- 本次改动范围：**只新增 2 个 androidTest 文件 + 本证据目录**；未改 `app/src/main`、未改既有测试断言、未改主文档/阶段记录/Figma；未操作小米真机（11 Ultra 当前离线）。
- 说明：Bug #3 的产品侧修复（`JournalPage` 内容区去掉重复的 `imePadding()`、`RestoreScreen` 同口径收敛）发生于本任务开始前（`app/src/main` 文件 mtime 07:18–07:19，非本任务改动）；本任务只为其补回归断言与证据。

---

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| 构建 | ✅ `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest` **BUILD SUCCESSFUL**（EXIT=0） |
| JVM 单测 | ✅ **342/342**（56 类，0 失败） |
| 新增 `ImeInsetLayoutTest` | ✅ **OK (1 test)**，9.101 s。键盘弹出后 `document-list` 高度 **443.05 dp**（断言阈值 300 dp；Bug #3 修复前基线 **106.7 dp**），无键盘 **755.43 dp**，键盘收起后恢复 **755.43 dp** |
| 新增 `UiSurfaceSnapshotTest` | ✅ **OK (4 tests)**，23.936 s；产出 4 张真实表面截图（AlertDialog / DatePicker / Snackbar / DropdownMenu） |
| 表面容器色 | ✅ AlertDialog **`#FFFFFF`**（320.00×236.19 dp）、DatePicker **`#FFFFFF`**（359.62×568.4 dp，1 px 分割线分成两块白色连通域）、Snackbar **`#223C32`**（362.67×47.24 dp，inverseSurface=设计 `color/ink`）、DropdownMenu **`#FFFFFF`**（112.76×400.76 dp）。4 张中 **M3 基线淡紫灰精确命中 = 0** |
| 深色主题 | ✅ `cmd uimode night yes` 后快照两套 **OK 17/17 + 9/9**；产出 **31 张**深色截图（22 coverage + 9 supplementary）；**31/31 主色为深色主题令牌**（`#141D17`/`#1D2921`/`#213228`）；31 张中 `#F7F8F3`（浅色背景）精确命中 **0 px** |
| 深色对比度 | ✅ onSurface/background **14.04**、onSurface/surface **12.29**、muted/surface **8.65**、primary/surface **8.82**、onPrimaryContainer/primaryContainer **7.64**、outline/background **5.98**、onSurface/primaryContainer **7.82** —— 文字级全部 ≥ 4.5:1（WCAG AA），均达 AAA（≥7）除 outline（非文字描边，≥3 即可） |
| 回归 | ✅ 浅色下同一批快照也重跑：Coverage **17/17**、UiSnapshotTest **9/9**；未修改任何既有断言 |
| 观察项 | ⚠️ ① 少量「light residue」为**描边/抗锯齿 coincidence**（见 §5.4）而非大面积浅色残留；② App 未使用 BottomSheet（代码 `grep` 0 处），该表面无法/无需截图；③ DatePicker 的 M3 默认文案随系统 locale（模拟器 en-US），属组件默认值、非主题缺陷 |

---

## 1. 新增测试

### 1.1 `app/src/androidTest/java/cn/yunji/journal/ImeInsetLayoutTest.kt`（Bug #3 回归）

- 复用 `PageMatrixBaseTest` 夹具（独立加密数据库 + 真实 `JournalApp` Compose 树）。
- 路径：启动 App → 产检 tab → 产检详情 → 产检资料（`DocumentScreen`，带 1 份 READY 资料使搜索框出现）→ 聚焦搜索框 → 键盘弹出。
- 断言（按 dp，`px / displayMetrics.density`，与分辨率无关）：
  1. 无键盘时 `document-list` 高度 ≥ 600 dp（实测 755.43 dp）；
  2. **键盘弹出后 `document-list` 高度 ≥ 300 dp**（实测 **443.05 dp**；Bug #3 双内边距复现时应约 106~110 dp，见 `../2026-10-24-ui-header-fix/README.md` §5）；
  3. 键盘收起后恢复 ≥ 无键盘值 − 5 dp（实测 755.43 dp）。
- 证据：`ime/ime-viewport-heights.txt`（测试内写出的实测值）、`ime/ime-open-document-list.png`、`ime/ime-hidden-document-list.png`、`_tools/instrument-ime-inset.log`、`_tools/logcat-ime-inset.txt`。

### 1.2 `app/src/androidTest/java/cn/yunji/journal/UiSurfaceSnapshotTest.kt`（表面组件截图，4 个用例）

| 用例 | 真实 App 触发路径 | 截图 |
|---|---|---|
| `surface01DeleteConfirmAlertDialog` | 产检资料 → 删除 → 「将资料移入回收站？」AlertDialog | `surfaces/surface-alert-dialog.png`（UiAutomation 整屏，含系统状态栏与 scrim） |
| `surface02DatePickerDialog` | 首页 → 新建产检预约 → 预约日期 → M3 `DatePickerDialog` | `surfaces/surface-date-picker.png`（UiAutomation 整屏） |
| `surface03UndoSnackbar` | 产检详情 → 删除产检记录 → 确认 → Snackbar「已移入全局回收站 · 撤销」 | `surfaces/surface-snackbar.png`（Compose root 截图） |
| `surface04DocumentCategoryDropdownMenu`（附加） | 产检资料 → 「全部分类」→ `DropdownMenu` | `surfaces/surface-dropdown-menu.png`（UiAutomation 整屏） |

> Dialog / Popup 是独立窗口，Compose `captureToImage()` 取不到其像素，因此统一用 `Instrumentation.uiAutomation.takeScreenshot()` 整屏截图（测试宿主 Activity 未设 `FLAG_SECURE`）。

---

## 2. 构建与测试结果

```powershell
.\gradlew.bat --gradle-user-home .gradle-user --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
```

- `BUILD SUCCESSFUL`，EXIT=0（`_tools/build.log`；测试文件二次改动后 `_tools/build-test-apk.log`）。
- `app-debug.apk`：sha256 `2BDD11C4…5DF613`（mtime 07:20:08，含 Bug #3 产品侧修复）；`androidTest.apk`：sha256 `95A78D14…BF733C`（含本任务两个新测试；`_tools/apk-info.txt`）。
- JVM：**342/342**（56 类，0 失败 0 错误 0 跳过）。
- 定向 instrument（`emulator-5556`，日志均在 `_tools/`）：

| 轮次 | 类 | 结果 | Time |
|---|---|---|---|
| 新增 | `ImeInsetLayoutTest` | **OK (1 test)** | 9.101 s |
| 新增 | `UiSurfaceSnapshotTest` | **OK (4 tests)** | 23.936 s |
| 浅色 | `UiSnapshotCoverageTest` | **OK (17 tests)** | 49.749 s |
| 浅色 | `UiSnapshotTest` | **OK (9 tests)** | 36.645 s |
| 深色 | `UiSnapshotCoverageTest` | **OK (17 tests)** | 50.823 s |
| 深色 | `UiSnapshotTest` | **OK (9 tests)** | 36.131 s |

全部 0 失败；未修改任何既有测试断言（新增文件之外仅 `ImeInsetLayoutTest`/`UiSurfaceSnapshotTest` 两个新类）。

---

## 3. Bug #3（IME 双内边距）回归数值

| 指标（`document-list` LazyColumn 高度） | 值 |
|---|---|
| 无键盘 | **755.43 dp** |
| **键盘弹出（断言目标）** | **443.05 dp** ≥ 300 dp ✅ |
| 键盘收起后恢复 | **755.43 dp** ✅ |
| Bug #3 修复前（双内边距基线，见 §5 of `2026-10-24-ui-header-fix`） | **~106.7 dp** ❌ |
| 断言阈值 | 300 dp（介于 106.7 与 443.05 之间，可有效区分修复前后） |

- 差值的合理性：屏幕 914.29 dp − header 136.76 dp − IME ~334.5 dp ≈ 443 dp（单内边距）；若双内边距则再减一次 IME ≈ 107 dp。
- 该断言在键盘弹出时读取真实语义节点 `boundsInRoot.height`，不是像素测量，因此不受渲染差异影响。

---

## 4. 表面组件截图与容器色（Pillow 精确色 + 连通域）

方法：`measure_surfaces.py`（仓库根执行）——对 4 张截图做 0 容差精确色计数，并对容器色做 1/4 降采样连通域取 bbox；同时统计 M3 1.x 基线淡紫灰色（`#ECE6F0/#E6E0E9/#F3EDF7/#49454F/#6750A4` 等 18 个）的精确命中。

| 表面 | 截图容器实测 | 尺寸（dp） | 位置（dp） | 设计色板 | M3 淡紫灰 |
|---|---|---|---|---|---|
| AlertDialog（删除确认） | **`#FFFFFF`** | 320.00 × 236.19 | x0=45.71, y0=352.00 | `color/surface` ✅ | **0 px** |
| DatePickerDialog | **`#FFFFFF`** | 359.62 × 568.40（1 px `#DDE4DA` 分割线切成两块白色连通域） | x0=25.90, y0=185.90 | `color/surface` ✅ | **0 px** |
| Snackbar（撤销） | **`#223C32`** | 362.67 × 47.24 | x0=24.38, y0=739.05 | `color/ink`（M3 inverseSurface 映射）✅ | **0 px** |
| DropdownMenu（分类） | **`#FFFFFF`** | 112.76 × 400.76（Popup；同图另有页面白卡 364.19×472.38 dp，已按连通域区分） | x0=24.38, y0=158.48 | `color/surface` ✅ | **0 px** |

其它设计色命中（精确色像素数）：
- AlertDialog：`#FFFFFF` 475,140；`#223C32`（标题/正文）6,870；`#356B53`（确认/取消按钮）3,156；`#5F7166` 9,778。
- DatePicker：`#FFFFFF` 1,357,284；`#356B53`（选中日圆形）9,972；`#5F7166`（表头/星期）11,819；`#223C32`（日期数字）8,480；`#DDE4DA`（分割线）1,890。
- Snackbar：`#223C32` 122,107（条 + 页面文字）；`#F7F8F3`（条内文字/inverseOnSurface）与页面背景同色；`#E7F0E6`（撤销动作/inversePrimary）10,637。
- DropdownMenu：`#FFFFFF` 1,343,264（含页面白卡）；`#223C32` 23,987；`#356B53` 22,128；`#5F7166` 11,561。

**before → after（容器色，历史对照）**：P0-2 修复前 AlertDialog 容器为 M3 基线 `#ECE6F0`、Switch 轨道 `#E6E0E9`（见 `../2026-10-24-figma-ui-alignment/comparison/README.md` §8 P0-2 / G5）；本轮实测表面对应容器已全部回落到设计色板（弹窗/菜单 `#FFFFFF`、Snackbar `#223C32`），**未复现任何淡紫灰**。

---

## 5. 深色主题验证

### 5.1 方法与产物

1. `adb shell cmd uimode night yes` 后 `am force-stop` 应用，重新以 instrument 启动（进程重建 → `isSystemInDarkTheme()` 生效）；
2. 跑 `UiSnapshotCoverageTest`（17 tests）与 `UiSnapshotTest`（9 tests），日志 `_tools/instrument-coverage-dark.log`、`_tools/instrument-supplementary-dark.log`（均 OK，0 失败）；
3. 拉取：`dark/coverage/` **22 张**、`dark/supplementary/` **9 张**（共 **31 张深色截图**，1080×2400）；同批次浅色对照在 `light/coverage/`、`light/supplementary/`；
4. 跑后 `cmd uimode night no` 恢复。

### 5.2 深色结论（`measure_dark.py`）

- **主题确实切换**：31 张深色截图主色全部为深色主题令牌（`#141D17` 背景 / `#1D2921` 卡片 / `#213228` 对话框 surfaceContainerHigh），31/31；同批浅色截图主色仅 `#F7F8F3`/`#FFFFFF`。
- **无浅色大面积残留**：31 张深色截图中浅色背景 `#F7F8F3` 精确命中 **0 px**；深色令牌像素合计：背景 57,123,257、surface 11,160,968、onSurface 849,037、onSurfaceVariant 201,245、primary 3,202,811、primaryContainer 2,740,470、outline 180,708。
- 指定 6 屏（light → dark，主色）：`timeline`、`security-settings`、`local-backup`、`01-today`、`02-visits` 为 `#F7F8F3 → #141D17`；`reminder-settings` 为 `#FFFFFF → #1D2921`（白卡主导）。全部符合预期。

### 5.3 深色对比度（WCAG，主题令牌）

| 前景/背景 | 色值 | 对比度 | 判定 |
|---|---|---|---|
| onSurface / background | `#E3EADF` / `#141D17` | **14.04:1** | AAA |
| onSurface / surface | `#E3EADF` / `#1D2921` | **12.29:1** | AAA |
| onSurfaceVariant / surface | `#B9C8BB` / `#1D2921` | **8.65:1** | AAA |
| primary / surface | `#A5D0B3` / `#1D2921` | **8.82:1** | AAA |
| onPrimaryContainer / primaryContainer | `#D5EBD8` / `#294C39` | **7.64:1** | AAA |
| onSurface / primaryContainer | `#E3EADF` / `#294C39` | **7.82:1** | AAA |
| outline / background（非文字描边） | `#8A9D8D` / `#141D17` | **5.98:1** | ≥3:1 ✅ |

### 5.4 「light residue」逐项说明（非大面积浅色残留）

`measure_dark.py` 同时统计了浅色令牌精确命中；少量像素经逐项归因，**均非浅色主题残留**：

1. 大多数截图有 **6–297 px** 的 `outline #DDE4DA` 命中：这是浅色文字 `#E3EADF` 在深色背景 `#141D17` 上抗锯齿到约 97% 不透明度时的混色（`0.971×#E3EADF + 0.029×#141D17 ≈ #DDE4DA`），属**字形边缘 1–2 px 抗锯齿 coincidence**，不是描边色残留。
2. `coverage/custom-reminder-editor.png` 有 **1,863 px `#FFFFFF`**：该图是 UiAutomation **整屏**截图（含系统状态栏/手势条，与 App 表面无关），白色来自系统 UI 图标；其主色 `#213228` 40.7% 即深色对话框容器。
3. `supplementary/08-visit-documents.png` 有 **147,906 px `#FFFFFF`**：来自资料卡中的**用户图片缩略图**（测试夹具 600×900 白色 PNG 报告图），属内容而非 UI 表面。
4. 除上述三项外，31 张深色截图的浅色残留均为 **0 px**。

---

## 6. 限制（结论必须在此边界内读）

| 限制 | 影响 |
|---|---|
| **模拟器渲染 ≠ 真机视觉** | 全部截图/断言来自 API 36 16 KiB 模拟器；真机（小米 11 Ultra）`MainActivity` 设 `FLAG_SECURE` 无法截取应用内容。IME 断言为语义布局断言（与渲染无关），但视觉结论仍限于模拟器。 |
| **深色仅主状态** | 深色只覆盖两套快照测试的 31 个主状态（浅色/深色各 1 组），未覆盖对话框/日期选择等表面在深色下的样式，也未覆盖深色 + 大字体/横屏组合。 |
| **BottomSheet 未覆盖** | 全仓 `app/src/main` 检索无 `BottomSheet`/`ModalBottomSheet` 使用（0 处），该表面不存在于当前实现，无法也无需截图；如后续新增需补证据。 |
| **表面截图均为浅色** | AlertDialog/DatePicker/Snackbar/DropdownMenu 只在浅色下截图；Dialog 截图含系统状态栏与 scrim 遮罩（UiAutomation 整屏），面积统计已按容器色连通域处理。 |
| **DatePicker 文案 locale** | 模拟器系统 locale 为 en-US，M3 `DatePicker` 的默认文案（"Select date"/"October 2026"/"S M T W T F S"）随系统 locale 显示；App 自有文案均为中文。属组件默认值/长期 i18n 项，非本次 UI 缺陷。 |
| **未跑全量仪器矩阵/lint** | 本轮只跑任务要求的测试类 + 两套快照（浅/深各一轮）；未跑 API36 全量矩阵与 lint。 |
| **产品侧修复不在本任务** | Bug #3 的 `app/src/main` 修复（去掉内容区重复 `imePadding()`）由 Lead 在本任务开始前完成（07:18–07:19），本任务只新增回归断言与证据，未改主代码。 |

---

## 7. 产物清单

```
docs/verification/2026-10-24-ui-evidence-gaps/
├─ README.md                              本文件
├─ surfaces/                              4 张表面截图（AlertDialog/DatePicker/Snackbar/DropdownMenu，1080×2400）
├─ ime/                                   IME 回归证据：2 张截图 + ime-viewport-heights.txt（实测 dp 值）
├─ light/coverage/                        22 张浅色快照（UiSnapshotCoverageTest）
├─ light/supplementary/                   9 张浅色补充快照（UiSnapshotTest）
├─ dark/coverage/                         22 张深色快照
├─ dark/supplementary/                    9 张深色补充快照
├─ measure_surfaces.py                    表面容器色测量脚本（精确色 + 连通域）
├─ measure_dark.py                        深色配色/对比度/残留测量脚本
├─ measurements-surfaces.json             4 张表面的逐图测量明细
├─ measurements-dark.json                 31 张深色 + 31 张浅色的逐图测量明细与对比度
└─ _tools/
   ├─ build.log / build-test-apk.log      Gradle 构建日志（BUILD SUCCESSFUL）
   ├─ apk-info.txt                        两个 APK 的 sha256
   ├─ install.log                         安装结果
   ├─ instrument-ime-inset.log            OK (1 test) 9.101 s
   ├─ instrument-ui-surfaces.log          OK (4 tests) 23.936 s
   ├─ instrument-coverage-light.log       OK (17 tests) 49.749 s
   ├─ instrument-supplementary-light.log  OK (9 tests) 36.645 s
   ├─ instrument-coverage-dark.log        OK (17 tests) 50.823 s
   ├─ instrument-supplementary-dark.log   OK (9 tests) 36.131 s
   ├─ logcat-ime-inset.txt                IME 实测 dp 值（logcat 副本）
   ├─ measurements-surfaces.txt           表面测量控制台摘要
   ├─ measurements-dark.txt               深色测量控制台摘要
   ├─ device-baseline-before/after.txt     跑前/跑后设备状态
   ├─ device-files-before-light/after-dark.txt  设备截图清单（证明本轮新生成）
   ├─ emulator-baseline-restored.txt      基线恢复记录（font_scale/night/stay_on/旋转/分辨率/无残留进程）
   └─ run_evidence.ps1                    浅色 + 深色两阶段证据采集脚本（可整段复跑）
```

---

## 8. 复现命令与模拟器基线恢复

```powershell
# 1) 构建 + JVM 单测（--offline）
.\gradlew.bat --gradle-user-home .gradle-user --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest

# 2) 安装（仅专用模拟器）
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 install -r -g -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk

# 3) 新测试（浅色）
adb -s emulator-5556 shell "am instrument -w -r -e class cn.yunji.journal.ImeInsetLayoutTest cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner"
adb -s emulator-5556 shell "am instrument -w -r -e class cn.yunji.journal.UiSurfaceSnapshotTest cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner"

# 4) 浅色 + 深色快照两阶段证据（自动切换 night 并恢复）
pwsh -NoProfile -ExecutionPolicy Bypass -File docs\verification\2026-10-24-ui-evidence-gaps\_tools\run_evidence.ps1

# 5) 测量
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-evidence-gaps\measure_surfaces.py
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-evidence-gaps\measure_dark.py
```

跑后基线恢复（`_tools/emulator-baseline-restored.txt`，2026-10-24T08:15:16+08:00）：
`font_scale=1.0`、`cmd uimode night no`（浅色）、`stay_on_while_plugged_in=0`、`accelerometer_rotation=1 / user_rotation=0`（竖屏）、`wm size=1080x2400`、`wm density=420`；
`am force-stop cn.yunji.journal` 与 `cn.yunji.journal.test` 后 `ps -A | grep yunji` 为空。未操作小米 11 Ultra / 14 Pro（均不在线）。

---

## 9. 遗留

1. 深色下对话框/日期选择/Snackbar 表面未截图（本轮深色只跑页面快照；如需可复用 `UiSurfaceSnapshotTest` 在深色下再跑一轮）。
2. `DatePicker` 默认文案随系统 locale（en-US）——属 i18n 长期项，建议后续统一通过 `DatePicker` 的 title/headline 本地化或锁定 App 语言。
3. App 无 BottomSheet，无需补证据；如后续引入需按本目录方法补截图与容器色测量。
