# P0-4 二级页头部层级修复 · 构建与截图量化验证（2026-10-24）

- 对应共享任务：`task-8`（负责人 `ui-header`）。
- 修复范围（依据 `../2026-10-24-figma-ui-alignment/comparison/README.md` §8 P0-4 与 §3 G1/G7）：
  1. **P0-4 二级页头部**：`JournalPage` 的 M3 `TopAppBar`（返回箭头 + 20/30 标题、无副标题）改为 `JournalPageHeader`（28/42 Bold 标题 + 12/18 `onSurfaceVariant` 副标题，24 dp 页边距），返回键保留 `navigate-up` testTag、180° 镜像图标与 `"返回上一级"` contentDescription。
  2. **G7 标题重复**：`ExportScreen` 删除内容首行重复的 `Text("导出资料", headlineSmall)`；`CloudConnection`/`Recovery`/`Restore` 的 9 处描述性 24 sp `headlineSmall` 降为 20 sp `titleLarge`，形成「1 个 28 sp 页面标题 + 20 sp 分区/阶段标题」层级。
- 不改动：`Theme.kt`、其他 feature 页面、主文档/阶段记录/Figma；未操作小米真机（11 Ultra 当前离线）。
- 设备：`emulator-5556`（PJ_API36_16K：Android 16 / API 36 / x86_64 / 16 KiB page / 1080×2400 / 420 dpi = 411.43×914.29 dp）；基线 `font_scale=1.0`、浅色、竖屏。
- 边界：本目录只写本目录；Gradle 本会话独占；模拟器渲染 ≠ 真机视觉（见 §7）。

---

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| 构建 | ✅ `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest` **BUILD SUCCESSFUL**（EXIT=0；`_tools/build.log`） |
| JVM 单测 | ✅ **342/342** 通过（0 失败 0 错误 0 跳过；56 个测试类 XML，`_tools/unit-test-summary.txt`） |
| 截图回归 `UiSnapshotCoverageTest` | ✅ **OK (17 tests)**，Time **50.212 s**；产出 22 张 1080×2400 PNG（`after/`） |
| 补充截图 `UiSnapshotTest` | ✅ **OK (9 tests)**，Time **36.979 s**；产出 9 张（`after-supplementary/`） |
| 导航/交互 `NavigationExperienceTest`（整类） | ✅ **OK (23 tests)**，Time **192.721 s**，0 失败（`_tools/instrument-navigation.log`）。**首次运行 1/23 失败**（PDF 资料用例，测试同步假设被更高的 header 打破），经 Lead 授权做最小测试侧适配后整类通过，见 §5 |
| 28 sp 页面标题 | ✅ 标题字形高 **19.05 dp → 26.67~27.43 dp**（×1.40 = 28/20）；Figma 设计实测 **26.5 dp**；标题颜色 `#223C32` |
| 12 sp 副标题 | ✅ 22 张覆盖截图中的 21 张页面截图（`custom-reminder-editor` 是全屏对话框截图，不适用）出现副标题：字形高 **11.43 dp**（设计 11.0 dp）、颜色 `#5F7166`、y0=108.95 dp；`back != null` 时文案固定「返回上一级」 |
| 头部左缘 | ✅ 返回键 48 dp 触控槽 x0 = **4.02 dp（旧 TopAppBar）→ 23.83 dp ≈ 24 dp**（像素反推，方法见 §4.2）；头部 Row 水平 padding = 24 dp |
| Export 标题重复（G7） | ✅ 顶部 250 dp 内“标题级”文本行 **2 → 1**：旧 = 20 sp AppBar(19.05 dp@y72) + 24 sp 内容 H1(22.48 dp@y144.4)，新 = 28 sp header(26.67 dp@y69.3)；`ExportScreen.kt` 中「导出资料」**0 处**，`headlineSmall` 全仓 **0 处** |
| 内容标题 24 → 20 sp | ✅ Cloud `22.86→19.05 dp`、Recovery `22.48→19.05 dp`、Restore `22.86→19.05 dp`（×0.83 = 20/24），宽度同比例收缩（如 Restore「输入恢复密钥」140.95→116.57 dp） |
| root 页面无副作用 | ✅ `01-today/02-visits/03-records-hub/04-profiles` 四张补充截图 **sha256 与 before 逐字节一致**（root 页面不进入 title 分支） |
| 观察项 | ⚠️ ① 设计稿头部**无返回箭头**、标题/副标题 x0≈24.5–25 dp；实现保留返回键后文本列 x0=76.6~77.7 dp（字号/层级已对齐，横向位置偏离设计 52 dp，见 §7、§9）；② 深色/大字体未截图验证 |

---

## 1. 改动清单（产品文件 6 个 + 测试适配 1 个）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `core/designsystem/Components.kt` | 新增 `JournalPageHeader(title, subtitle: String? = null, onBack: (() -> Unit)? = null)`：`Row`（`windowInsetsPadding(TopAppBarDefaults.windowInsets)` + 水平 24 dp/垂直 12 dp、垂直居中、icon 与文本列间距 4 dp），可选 `IconButton`（`Modifier.testTag("navigate-up")` + `rotate` 180° 镜像图标 + contentDescription「返回上一级」），`Column`：标题 `headlineMedium`（28/42 Bold、单行省略）+ 可选副标题 `bodySmall`（12/18、`onSurfaceVariant`、单行省略），间距 4 dp。另补充 `rotate`/`LocalLayoutDirection`/`LayoutDirection`/`testTag` import。 |
| 2 | `feature/JournalNavigation.kt` | `JournalPage` 的 `topBar` 槽：`TopAppBar(...)` → `JournalPageHeader(title = title, subtitle = if (back != null) "返回上一级" else null, onBack = back)`；`Scaffold(modifier = Modifier.testTag(title ?: root.toString()))`、`contentWindowInsets = WindowInsets.safeDrawing`、`bottomBar`、内容 `padding(padding).imePadding()` 全部保持不变；删除不再使用的 `rotate` import。 |
| 3 | `feature/ExportScreen.kt` | 删除内容首行 `Text("导出资料", headlineSmall)`（header 已承载页面标题）。 |
| 4 | `feature/CloudConnectionScreen.kt` | 内容首行「已有加密保存的连接/连接你的云盘」`headlineSmall` → `titleLarge`（1 处）。 |
| 5 | `feature/RecoveryScreen.kt` | 「保留找回资料的钥匙」`headlineSmall` → `titleLarge`（1 处）。 |
| 6 | `feature/RestoreScreen.kt` | 7 个阶段描述标题（从加密备份找回资料/输入恢复密钥/从云端备份恢复/选择要恢复的云端备份/核对要恢复的资料/资料恢复完成/恢复需要重新核对）`headlineSmall` → `titleLarge`（7 处）。 |
| 7 | `androidTest/.../NavigationExperienceTest.kt` | **Lead 授权的最小测试侧适配**（见 §5）：PDF 资料用例在「清除搜索与筛选」点击前先 `performScrollTo()`、在等待「查看PDF」前先 `performScrollToNode(hasText("查看PDF"))`；未放宽任何产品断言、未改其他用例。 |

> 全仓核对（`_tools/code-replacements.txt`）：`app/src/main` 中 `headlineSmall` = **0**、`JournalPageHeader` = 2（1 定义 + 1 调用）、`navigate-up` = 1（header 内）、`TopAppBar` 仅剩 KDoc 引用与 `TopAppBarDefaults.windowInsets`、`ExportScreen.kt` 的「导出资料」= **0**。本任务只修改上述 7 个文件（按文件 mtime 核对）。

---

## 2. 构建与安装

```powershell
.\gradlew.bat --gradle-user-home .gradle-user --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
```

- 结果：`BUILD SUCCESSFUL`，EXIT=0（`_tools/build.log`；最终一轮 86 up-to-date）。
- `app-debug.apk`：29,871,944 B，sha256 `DB15BFD25270C1C5BA74D50731858DA340049BEE658C9074EEF5FEBB7D45C56C`。
- `app-debug-androidTest.apk`：2,932,551 B，sha256 `E4302DA724A19A542428E844E6691E6F99E0C16622FA96D45EC1E1F34F6AD633`（含 §5 的测试适配；`_tools/apk-info.txt`）。
- 安装（`emulator-5556`）：`adb install -r -g` / `-r -g -t` 两个 APK 均 `Success`（`_tools/install.log`）。
- JVM：**342/342**（56 类，`_tools/unit-test-summary.txt`）。

---

## 3. 截图证据（本轮新生成，非旧图）

1. **运行前先删除设备旧截图**：`verification/figma-ui-alignment/`、`verification/figma-sync/` 与 `verification/` 根下旧 `c0*/b0*/d0*` 图；删除前设备清单（51 个旧文件）保存在 `_tools/device-verification-before.txt`，删除后为空，证明 after 全部为新渲染。
2. 安装终版 APK 后按类运行（日志见 `_tools/instrument-*.log`）：

| 测试类 | 结果 | Time |
|---|---|---|
| `cn.yunji.journal.UiSnapshotCoverageTest` | **OK (17 tests)** | 50.212 s |
| `cn.yunji.journal.UiSnapshotTest` | **OK (9 tests)** | 36.979 s |
| `cn.yunji.journal.NavigationExperienceTest` | **OK (23 tests)** | 192.721 s |

> 说明：Coverage/Supplementary 两轮在 §5 的**测试侧适配之前**运行；该适配只改 `NavigationExperienceTest.kt`，产品 APK 在首轮构建后未再改动（sha256 见 §2），因此其截图与结论对最终代码有效。NavigationExperienceTest 的通过结果是**适配后**整类重跑的终态。

3. 拉取：`after/` **22 张**（Coverage，1080×2400）、`after-supplementary/` **9 张**（UiSnapshot）、`navigation/` **19 张**（NavigationExperience 的历史 `captureToImage` 产物，含 4 张节点裁剪图，只作行为证据、不参与几何测量）。尺寸清单见 `_tools/screenshot-dimensions.txt`。
4. **before 基准**：`../2026-10-24-ui-consistency-fix/after/`（22 张）与 `../2026-10-24-ui-consistency-fix/after-supplementary/`（9 张）——同一测试类、同一组用例、同一模拟器基线，P0-1/3/6 修复后的上一轮终态。
5. 对照裁剪图（`diffs/`）：`header-export-screen-before-after.png`、`header-local-backup-before-after.png`、`header-cloud-connection-before-after.png`、`header-restore-wizard-before-after.png`（左侧 before / 右侧 after，各取顶部 900 px）。

---

## 4. 测量方法（可复现）

### 4.1 工具与换算

- bundled Python 3 + Pillow 12.3 / numpy 2.3；脚本 `measure_header.py`（逐图测量）、`_tools/summarize_header.py`（汇总表）、`_tools/make_diff_crops.py`（before|after 裁剪）。
- 换算：1080 px / 411.4286 dp ⇒ **1 dp = 2.625 px**；设计稿 824×1830 = 2x ⇒ **1 dp = 2 px**。
- 指标：
  1. **页眉标题**：先对墨色掩膜（`sum(RGB) < 450`）逐行取 run，取最上方、高度 ≥15 dp 的 run；再屏蔽返回箭头列（x<140 px）后重算标题 ink box（高/宽/x0/y0）。20 sp 与 28 sp 的 CJK 字形墨高分别约 19.05 dp 与 26.5–27.4 dp，与设计实测 26.5 dp 一致。
  2. **副标题**：标题下方 45 px 内、墨高 8–14 dp 的 run。
  3. **返回键触控槽 x0 反推**：旧 TopAppBar 的 48 dp `navigationIcon` 槽从 4 dp 起，chevron 字形墨迹从 23.62 dp 起 ⇒ 字形左 bearing = 23.62 − 4 − (48−24)/2 = **7.6 dp**；对任意截图 `slot_x0 = glyph_x0 − 7.6 − 12`。用旧截图校验得 4.02 dp ≈ 4 dp（与 TopAppBar 内部 padding 一致），再由新截图反推 23.83 dp ≈ 24 dp。
  4. **导出页标题重复**：统计顶部 250 dp 内“标题级”文本行（墨高 17–31 dp）；旧图 2 行、新图 1 行。另以代码核对 `ExportScreen.kt` 中「导出资料」= 0。
  5. **内容标题降级**：header 下方第一个墨高 15–24 dp、宽 ≥100 dp 的文本行（24 sp→20 sp）。
  6. **颜色**：在标题/副标题 bbox 内数精确色像素（`#223C32` / `#5F7166`，tol=0，Compose 平涂 PNG 可精确命中）。
  7. **无副作用**：before/after sha256 对比。

### 4.2 命令

```powershell
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-header-fix\measure_header.py
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-header-fix\_tools\summarize_header.py
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-header-fix\_tools\make_diff_crops.py
```

- 输出：`measurements.json`（逐图全量结构化：title/subtitle/arrow/content_heading/top-250dp 标题行/精确色计数/sha256）、`_tools/measurements.txt`（控制台全文）、`_tools/summary.txt`（README 用汇总表）。

---

## 5. 测试兼容性：首次运行 1/23 失败的根因与最小测试侧适配（Lead 授权）

### 5.1 失败现象

首轮 `NavigationExperienceTest`：**Tests run: 23, Failures: 1**，失败用例
`pdfNavigationMetadataSearchAndBackgroundCleanup`，`NavigationExperienceTest.kt:538`
`ui.waitUntil(10_000) { onAllNodesWithText("查看PDF") ... }` 超时（ComposeTimeoutException）。
原始日志：`_tools/instrument-navigation-before-test-fix.log`；单跑复现：`_tools/smoke-pdf-single.log`（20.426 s，同样在 :538 失败，说明是确定性而非偶发）。

### 5.2 根因（本任务实测，不是推测）

1. 该用例在 `document-search` 输入「不存在」后**软键盘保持打开**；`JournalPage` 内容区同时受 `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)` 的 bottom inset（`safeDrawing` 含 IME）与内容 `Box` 显式 `.imePadding()` 影响（这是既有实现，非本次改动），键盘打开时内容视口被压缩得比屏幕小很多。
2. 通过失败瞬间的 `uiautomator` 配对截图/层级（保留为 `_tools/debug-failing-state-viewport.png`、`_tools/debug-failing-state-ui.xml`）实测：`document-list`（LazyColumn）bounds = **[0,354][1080,634]**，视口仅约 **106.7 dp** 高；空态卡只露出标题，「清除搜索与筛选」按钮落在列表视口之下。
3. 新 header 比旧 TopAppBar 高约 **24 dp**（旧 112.76 dp → 新 136.76 dp = 48.76 状态栏/挖孔 inset + 12+42+4+18+12），把这处“本来刚好够用”的测试假设压过阈值：**点击注入落在列表视口之外/无命中目标**，`query` 仍为「不存在」→ 文档卡不出现 → 「查看PDF」永不组合 → :538 超时。失败截图层级中搜索框仍显示「不存在」、空态卡仍在，可直接佐证。
4. 判定：这是**测试同步对布局高度的脆弱假设**，不是断言语义/产品语义问题（`navigate-up`、页面 testTag、`assertIsSelected` 等断言均未变化）。

### 5.3 最小适配（仅此一处用例、2 个行为点）

`app/src/androidTest/java/cn/yunji/journal/NavigationExperienceTest.kt`（原 537–539 行）：

```kotlin
ui.onNodeWithText("清除搜索与筛选").performScrollTo().performClick(); settle()
ui.onNodeWithTag("document-list").performScrollToNode(hasText("查看PDF"))
ui.waitUntil(10_000) { ui.onAllNodesWithText("查看PDF").fetchSemanticsNodes().isNotEmpty() }
ui.onNodeWithText("查看PDF").performScrollTo()
```

- ① 点击「清除搜索与筛选」前先 `performScrollTo()`，把按钮滚入（被 IME 压缩的）列表视口，保证点击命中；
- ② 等待「查看PDF」前先 `performScrollToNode(hasText("查看PDF"))`，让 LazyColumn 把文档卡滚入组合窗口，不再假设它已在视口内；
- 后续 `performScrollTo()` 保留，其余断言/逻辑零改动；未放宽任何产品断言。
- 其它用例本轮无同类“视口外未组合”失败，无需同类修复。

### 5.4 适配后结果

- 单跑：**OK (1 test)**，17.878 s（`_tools/smoke-pdf-single-after-fix.log`）。
- 整类：**OK (23 tests)**，192.721 s（`_tools/instrument-navigation.log`）。
- 适配后重新构建的 `app-debug-androidTest.apk` sha256 `E4302DA7…6AD633`；`app-debug.apk` 未变（产品代码自首轮构建后未再改动）。

---

## 6. 关键数值 before → after

### 6.1 页眉标题（20 sp TopAppBar → 28 sp JournalPageHeader）

| 页面（截图） | before 标题墨高 | after 标题墨高 | 比例 | before 标题 x0 | after 标题 x0 | after 副标题 |
|---|---|---|---|---|---|---|
| `export-screen.png` | 19.05 dp | **26.67 dp** | 1.400 | 57.52 dp | 77.71 dp | h 11.43 / x0 76.57 dp |
| `cloud-connection.png` | 19.05 dp | **27.05 dp** | 1.420 | 57.52 dp | 77.71 dp | h 11.43 / x0 76.57 dp |
| `local-backup.png` | 19.05 dp | **27.05 dp** | 1.420 | 57.14 dp | 76.95 dp | h 11.43 / x0 76.57 dp |
| `restore-wizard.png` | 19.05 dp | **26.67 dp** | 1.400 | 57.14 dp | 76.95 dp | h 11.43 / x0 76.57 dp |
| `visit-detail.png` | 19.05 dp | **27.05 dp** | 1.420 | 57.14 dp | 76.95 dp | h 11.43 / x0 76.57 dp |
| 21 张页面截图（全部） | 18.29–19.05 dp | **26.29–27.43 dp** | 1.400–1.458 | 57.1–57.9 dp | 76.9–78.1 dp | 均存在（h 11.43） |
| **Figma 设计基准**（05/06/19 屏，2x→dp） | — | **26.5 dp** | — | 24.5–25.0 dp | — | h 11.0 / x0 24.5 dp |

- 标题宽度同比放大（×1.40 ≈ 28/20），如 `export` 77.71→109.33 dp、`local-backup` 117.71→165.71 dp；即“是换字号而不是换文案/字重”。
- 标题颜色：`#223C32` 精确色像素 7557–13887 px（after，页面标题 21 张）。
- 副标题：before **无**（21 张页面截图均无 ≤14 dp 的标题下 run），after **21/21 存在**、字形高 11.43 dp（设计 11.0 dp）、`#5F7166` 精确色像素 579 px、x0=76.57 dp。
- 说明：`custom-reminder-editor.png` 是 UiAutomation 全屏对话框截图（含系统状态栏与 dim 遮罩），不参与页眉几何统计。

### 6.2 头部左缘（24 dp 页边距）与返回键

| 指标 | before（TopAppBar） | after（JournalPageHeader） |
|---|---|---|
| 返回箭头字形墨迹 x0 | 23.62 dp | 43.43 dp |
| 返回箭头字形墨迹 y0 | 73.14 dp | 84.19 dp |
| **反推 48 dp 触控槽 x0** | **4.02 dp**（TopAppBar navIcon 内部从 4 dp 起，校验 bearing 模型） | **23.83 dp ≈ 24 dp** ✅ |
| 标题/副标题文本列 x0 | 57.1–57.9 dp（标题） | 76.9–78.1 dp（标题）/ 76.57 dp（副标题） |

- 结论：header 的 `Row` 水平 padding = 24 dp，返回键 48 dp 触控槽从 **x=24 dp** 开始（与设计“页面左右边距 24”一致）；文本列因返回键占位再右移 48+4 dp。设计稿无返回箭头，故设计标题 x0≈24.5 dp 与实现文本列 x0≈77 dp 相差约 52 dp（见 §7）。
- header 高度：旧 112.76 dp（48.76 状态栏/挖孔 + 64 dp TopAppBar）→ 新 **136.76 dp**（48.76 + 12 + 42 + 4 + 18 + 12），内容整体下移 24 dp；实测副标题墨迹 y1=120.38 dp + 行盒/下内边距 ≈ 136 dp 与计算一致（`export` 内容首行墨迹 y0 由 190.10 dp 提前/后移关系见 §6.3，因重复标题同时被删除）。

### 6.3 Export 页标题重复（G7）

| 指标 | before | after |
|---|---|---|
| 顶部 250 dp 内“标题级”行数（墨高 17–31 dp） | **2** | **1** |
| 行明细 | 20 sp AppBar：19.05 dp @ y=72.0；24 sp 内容 H1：22.48 dp @ y=144.38 | 28 sp header：26.67 dp @ y=69.33 |
| `ExportScreen.kt` 中「导出资料」 | 1 | **0** |
| 页面首个内容块 | 重复 H1「导出资料」 | 说明段落（14 sp，墨高 12.95 dp @ y=163.05） |

> “只出现一次”的像素证据：新截图顶部 250 dp 内仅剩一行标题级文本（26.67 dp），其下第一行文字是 14 sp 说明段；代码侧 `ExportScreen.kt` 不再包含该字符串（引用只剩 `JournalNavigation.kt` 的路由标题与「我的」页入口文案，不会同时出现在导出页）。对照图见 `diffs/header-export-screen-before-after.png`。

### 6.4 内容分区/阶段标题 24 sp → 20 sp

| 页面（截图） | before 墨高 / 宽 | after 墨高 / 宽 | 比例 |
|---|---|---|---|
| `cloud-connection.png`「已有加密保存的连接」 | 22.86 dp / 212.95 dp | **19.05 dp / 176.00 dp** | 0.83 |
| `local-backup.png`「保留找回资料的钥匙」 | 22.48 dp / 214.10 dp | **19.05 dp / 177.14 dp** | 0.85 |
| `restore-wizard.png`「从加密备份找回资料」 | 22.86 dp / 214.10 dp | **19.05 dp / 177.14 dp** | 0.83 |
| `restore-wizard-key.png`「输入恢复密钥」 | 22.86 dp / 140.95 dp | **19.05 dp / 116.57 dp** | 0.83 |
| `09-recovery.png`（补充截图，本地加密备份） | 22.48 dp / 214.10 dp | **19.05 dp / 177.14 dp** | 0.85 |
| 设计基准 | 24 sp（headlineSmall，非令牌层级） | 20 sp = `孕迹/heading`（令牌 `titleLarge` 20/30 Medium） | — |

- 这些描述性标题**保留**（未删除），仅降级为 20 sp，与 28 sp 页面标题形成二级层级；其 x0 after = 24.76–25.90 dp（页面 24 dp 边距 + 汉字 bearing）。

### 6.5 无副作用

- root 页面（无 title 分支）：`01-today`、`02-visits`、`03-records-hub`、`04-profiles` 四张补充截图的 **sha256 与 before 完全一致**（`measurements.json` 的 `supplementary` 节点），证明 `JournalPageHeader` 未影响一级页 `PageHeader`/底部导航。
- 其余 25 张截图变化均集中在页眉区域（标题字号/副标题/内容起点下移 24 dp）与目标内容标题，未发现目标区域外的布局位移。

---

## 7. 限制（结论必须在此边界内读）

| 限制 | 影响 |
|---|---|
| **模拟器渲染 ≠ 真机视觉** | 全部像素证据来自 API 36 16 KiB 模拟器 Compose/UiAutomation 截图；真机（小米 11 Ultra）`MainActivity` 设 `FLAG_SECURE` 无法截取应用内容，字体栅格/厂商字体/系统栏处理可能不同。真机结论只能到结构/行为级。 |
| **仅单一配置** | 只验证浅色、竖屏、`font_scale=1.0`、412×915 dp；深色仅代码级使用 `MaterialTheme.colorScheme` 角色（`headlineMedium`/`onSurfaceVariant`/`background`），未截图；大字体（200%）未验证。 |
| **设计稿头部无返回箭头** | 设计 05/06/10/15/19/20 的 PageHeader 无返回箭头，标题/副标题 x0≈24.5–25 dp；实现按任务书保留返回 `IconButton`（测试依赖 `onNodeWithContentDescription("返回上一级")`、回归需要返回入口），文本列 x0=76.6~77.7 dp。**字号/行高/层级/颜色/上边距已对齐设计，横向位置因返回键偏离约 52 dp**；若需文本列严格 x0=24 dp，需把返回键改为“标题上方一行”的堆叠式头部（属设计取舍，见 §9 待确认）。 |
| **`custom-reminder-editor.png` 不参与页眉几何** | 该图是 UiAutomation 整屏对话框截图（含系统状态栏时钟与 dim 遮罩），无法做 header ink 测量；其页面本身在对话框后方，未单独截图。 |
| **截图覆盖** | 22+9 张截图全部覆盖二级页页眉（除上述对话框），但页眉的**长标题省略号**、横屏/分屏、弹窗状态下的页眉未覆盖。 |
| **未跑全量仪器矩阵** | 本轮按任务只跑 3 个类（另加 1 次单用例冒烟与 2 次整类），未跑 API36 全量仪器矩阵；`NavigationExperienceTest` 首轮失败→适配后通过的过程见 §5（如实记录，未把失败轮次当作通过）。 |
| **既有的 IME 双内边距** | §5.2 观察到 `Scaffold(contentWindowInsets = safeDrawing)`（含 IME）与内容 `Box.imePadding()` 同时生效，键盘打开时内容视口被压缩得明显小于可用高度。这是**既有实现**、不在本任务范围（未改动），仅记录为测试失败触因与后续产品侧可优化项；本轮用测试侧滚动适配规避，未削弱任何断言。 |

---

## 8. 产物清单

```
docs/verification/2026-10-24-ui-header-fix/
├─ README.md                              本文件
├─ after/                                 22 张 after 截图（UiSnapshotCoverageTest，1080×2400）
├─ after-supplementary/                   9 张补充截图（UiSnapshotTest，1080×2400）
├─ navigation/                            19 张 NavigationExperienceTest 截图（4 张为节点裁剪图）
├─ diffs/
│  ├─ header-export-screen-before-after.png
│  ├─ header-local-backup-before-after.png
│  ├─ header-cloud-connection-before-after.png
│  └─ header-restore-wizard-before-after.png
├─ measurements.json                      逐图全量结构化测量（标题/副标题/箭头/内容标题/精确色/sha256）
├─ measure_header.py                      主测量脚本（可复现 §4 全部数值）
└─ _tools/
   ├─ build.log                           Gradle 构建（BUILD SUCCESSFUL，EXIT=0）
   ├─ build-test-apk.log                  测试适配后 :app:assembleDebugAndroidTest
   ├─ apk-info.txt                        两个 APK 的大小/时间/sha256
   ├─ install.log                         安装结果
   ├─ unit-test-summary.txt               JVM 342/342
   ├─ instrument-coverage.log             OK (17 tests) Time 50.212
   ├─ instrument-supplementary.log        OK (9 tests) Time 36.979
   ├─ instrument-navigation-before-test-fix.log   首次失败：Tests run 23, Failures 1（:538 超时）
   ├─ smoke-pdf-single.log                失败用例单跑复现（20.426 s，:538）
   ├─ smoke-pdf-single-after-fix.log      适配后单跑 OK (1 test) 17.878 s
   ├─ instrument-navigation.log           适配后整类 OK (23 tests) Time 192.721
   ├─ code-replacements.txt               代码级核对（headlineSmall=0、JournalPageHeader=2、navigate-up=1 等）
   ├─ measurements.txt                    测量控制台全文
   ├─ summary.txt                         README 用汇总表（逐页/设计基准）
   ├─ screenshot-dimensions.txt           50 张截图尺寸清单
   ├─ device-verification-before.txt      运行前设备旧截图清单（51 个，随后删除）
   ├─ device-verification-after.txt       运行后设备新截图清单
   ├─ debug-failing-state-viewport.png    失败瞬间配对截图（LazyColumn 视口被 IME 压缩的证据）
   ├─ debug-failing-state-ui.xml          失败瞬间 uiautomator 层级（document-list bounds [0,354][1080,634]）
   ├─ emulator-baseline-restored.txt      跑后模拟器基线恢复记录
   ├─ run_instrument.ps1                  清旧图 → 按类跑 → 拉取（可整段复跑）
   ├─ summarize_header.py                 汇总表脚本
   └─ make_diff_crops.py                  before|after 裁剪图脚本
```

---

## 9. 复现命令与模拟器基线恢复

```powershell
# 1) 构建 + JVM 单测（--offline）
.\gradlew.bat --gradle-user-home .gradle-user --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest

# 2) 安装（仅专用模拟器）
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 install -r -g -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk

# 3) 清旧图 → 按类跑 → 拉取
pwsh -NoProfile -ExecutionPolicy Bypass -File docs\verification\2026-10-24-ui-header-fix\_tools\run_instrument.ps1

# 4) 测量与对照图
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-header-fix\measure_header.py
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-header-fix\_tools\summarize_header.py
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-header-fix\_tools\make_diff_crops.py
```

跑后基线恢复（`_tools/emulator-baseline-restored.txt`，2026-10-24T07:10:33+08:00）：
`font_scale=1.0`、`cmd uimode night no`（浅色）、`stay_on_while_plugged_in=0`、`accelerometer_rotation=1 / user_rotation=0`（竖屏）、`wm size=1080x2400`、`wm density=420`；
`am force-stop cn.yunji.journal` 与 `cn.yunji.journal.test` 后 `ps -A | grep yunji` 为空。未操作小米 11 Ultra / 14 Pro（均不在线）。

---

## 10. 遗留与待确认

1. **设计稿头部无返回箭头 vs 实现保留返回键**（本轮最重要差异）：字号（28/42 + 12/18）、颜色、24 dp 上/左边距均已对齐设计；但返回键占位使标题/副标题文本列 x0≈76.6~77.7 dp（设计 24.5 dp）。若要求严格对齐设计 x0，可把返回键独立成标题上方一行（M3 LargeTopAppBar 式）——这会改变任务书约定的 `Row` 结构，**需 Lead/用户确认后再改**；本轮未擅自变更。
2. **IME 双内边距（既有实现，未修）**：`Scaffold(contentWindowInsets = safeDrawing)` 已含 IME，内容 `Box` 又 `.imePadding()`，键盘打开时视口被双重压缩（失败瞬间实测 `document-list` 仅约 106.7 dp 高）。建议后续把 `contentWindowInsets` 限定为 `safeDrawing.only(Horizontal + Top)`（bottom 交给 `imePadding()`/`navigationBarsPadding()` 单一口径），可同时降低其它“键盘 + 列表滚动”测试的脆弱性；因不在 P0-4 范围且涉及全局 inset 行为，本轮只记录、不改动。
3. **深色/大字体/横屏未验证**（同 §7）；页眉在 200% 字号下高度会随 headlineMedium/bodySmall 行高增长（`Text` 单行省略），未截图。
4. 未跑 API36 全量仪器矩阵（本轮只跑任务要求的 3 个类 + 单用例冒烟），其余页面/矩阵回归可沿用现有证据或后续统一执行。
