# P0-1 标签 / P0-3 按钮体系 / P0-6 页边距修复 · 构建与截图量化验证（2026-10-24）

- 对应共享任务：`task-7`（负责人 `ui-consistency`）。
- 修复范围（依据 `../2026-10-24-figma-ui-alignment/comparison/README.md` §8 P0-1/3/6 与 §3 G2/G3/G6）：
  1. **P0-1** 新增 `JournalTag`，替换全部 `FilterChip`（48 dp 高 / min 64 dp 宽 / 圆角 12 / 选中 `#356B53` 底 + 白字 / 未选中白底 + `#DDE4DA` 1 dp 描边 + `#5F7166` 字，保留 `assertIsSelected` 语义与既有 testTag）。
  2. **P0-3** Recovery / Restore / Export 三个页面的原生 `Button`/`OutlinedButton` 改为既有 `JournalButton`（52 dp 胶囊；主操作 primary、次操作 `secondary=true`），保留 testTag。
  3. **P0-6** 页面边距统一 24 dp（新页面 20 dp → 24 dp；提醒设置 4+24 叠层 → 24 dp）。
- 不改动：`Theme.kt`（P0-2 终版）、P0-4 二级页头部、P0-5 Timeline 布局、测试代码、主文档/阶段记录/Figma。
- 设备：`emulator-5556`（PJ_API36_16K：Android 16 / API 36 / x86_64 / **16 KiB page** / 1080×2400 / 420 dpi = 411.43×914.29 dp）；
  基线 `font_scale=1.0`、浅色（Night mode: no）、竖屏。**未操作小米真机**（11 Ultra / 14 Pro 当前不在线）。
- 边界：本目录只写本目录；Gradle 本会话独占。

---

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| 构建 | ✅ `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest` **BUILD SUCCESSFUL in 28s**（EXIT=0；18 executed / 68 up-to-date） |
| JVM 单测 | ✅ **342/342** 通过（0 失败 0 错误 0 跳过；56 个测试类 XML） |
| 截图回归 `UiSnapshotCoverageTest` | ✅ **OK (17 tests)**，Time **48.372s**；产出 **22 张** 1080×2400 PNG |
| 补充截图 `UiSnapshotTest` | ✅ **OK (9 tests)**，Time **36.943s**；产出 **9 张** 1080×2400 PNG |
| 导航/交互 `NavigationExperienceTest`（整类） | ✅ **OK (23 tests)**，Time **193.038s**，**0 失败**；`assertIsSelected` 三处依赖（`:156`/`:226`/`:817`）全部通过 |
| 矩阵回归 `UiStateMatrixTest`（补充） | ✅ **OK (7 tests)**，Time **32.66s**。说明：仓库**没有**名为 `PageMatrixTest` 的类（`PageMatrixBaseTest` 是基类），以 `UiStateMatrixTest` 作为矩阵回归代表 |
| P0-1 标签高度 | ✅ **32.0 dp → 48.4 dp**（layout 取整 127 px；设计 48 dp）；旧 32 dp `FilterChip` 在新截图 **0 个**（代码中 `FilterChip` 0 处） |
| P0-1 选中态 | ✅ 选中填充 **`#E7F0E6` 底 + `#356B53` 字 → `#356B53` 底 + `#FFFFFF` 字**；文字字形像素数 **902 / 2886 / 1056 前后完全相同**（仅颜色互换，未改字号/文案） |
| P0-1 未选中态 | ✅ 白底（bbox 内白色占比 0.78–0.83）+ `#DDE4DA` 1 dp 描边 + `#5F7166` 字；圆角代理（首行内缩）**20 px → 31 px**（≈8 dp → ≈11.8 dp，设计 12 dp） |
| P0-1 最小宽度 | ✅ 两字标签实测 **w = 64.0 dp**（`全部`/`列表`/`日历`），符合 min 64 dp |
| P0-3 按钮高度 | ✅ 三页原生按钮 **40.0 dp → 52.19 dp**（137 px，layout 取整；设计 52 dp） |
| P0-3 次按钮样式 | ✅ 40 dp **描边 OutlinedButton** → 52.19 dp **`#E7F0E6` 填充 FilledTonal（secondary）**（local-backup / restore-wizard） |
| P0-3 按钮 x0 | ✅ **20.19 dp → 24.0 dp**（local-backup / restore-wizard / restore-wizard-key / export-screen-disabled 全宽按钮） |
| P0-6 页边距 | ✅ 9 个文件 10 处 20 dp → 24 dp；提醒设置 4+24 叠层 **28.19 dp → 24.0 dp**；目标页卡片/按钮 x0 全部 **24.0 dp** |
| 无副作用 | ✅ 未受影响的 4 张页面截图（drug-catalog / pregnancy-editor / visit-detail / visit-editor）与 before **sha256 逐字节一致** |
| 观察项 | ⚠️ ① 3.2 中 `02-visits` 仍有 1 个 32 dp 高的 `#E7F0E6` 区块，经裁剪确认是**底部导航选中指示胶囊（56×32 dp）**，不是残留 FilterChip；② 深色/大字体未截图验证（见 §7） |

---

## 1. 改动清单（产品文件，共 15 个）

| # | 文件 | 改动 | 数量 |
|---|---|---|---|
| 1 | `core/designsystem/Components.kt` | 新增 `JournalTag`（48 dp、min 64、圆角 12、`Modifier.selectable(role=Role.Tab)`、`Surface`+`ProvideTextStyle(labelLarge)`、色板全部取 `MaterialTheme.colorScheme` 角色 → 支持深色） | +1 组件（+24 行） |
| 2 | `feature/TimelineScreens.kt` | FilterChip→JournalTag；JournalEntry 编辑页/原始记录页边距 20→24 | 2 处标签；2 处边距 |
| 3 | `feature/MeasurementScreens.kt` | FilterChip→JournalTag；记录测量页边距 20→24 | 7 处标签；1 处边距 |
| 4 | `feature/IntakeScreens.kt` | FilterChip→JournalTag | 5 处标签 |
| 5 | `feature/PrenatalScreens.kt` | FilterChip→JournalTag | 3 处标签 |
| 6 | `feature/TrashScreen.kt` | FilterChip→JournalTag | 2 处标签 |
| 7 | `feature/DocumentScreen.kt` | FilterChip→JournalTag | 2 处标签 |
| 8 | `feature/MedicationScreens.kt` | FilterChip→JournalTag | 1 处标签 |
| 9 | `feature/SecurityScreen.kt` | FilterChip→JournalTag；页边距 20→24 | 1 处标签；1 处边距 |
| 10 | `feature/RecoveryScreen.kt` | 原生 Button×5（生成/已保存核对/确认保存/导出/重试）+ OutlinedButton×2（从备份恢复资料、回收上一代资料）→ JournalButton；页边距 20→24 | 7 处按钮 |
| 11 | `feature/RestoreScreen.kt` | 原生 Button×7（选择备份/校验/查找云端/下载并校验/确认恢复/打开资料/重新选择备份）+ OutlinedButton×2（从云端备份恢复、云端快照选择项）→ JournalButton；页边距 20→24 | 9 处按钮 |
| 12 | `feature/ExportScreen.kt` | 原生 Button×1（导出结构化数据包）+ OutlinedButton×2（取消导出、生成就诊摘要 PDF）→ JournalButton；页边距 20→24 | 3 处按钮 |
| 13 | `feature/CloudConnectionScreen.kt` | 页边距 20→24 | 1 处边距 |
| 14 | `feature/ReminderSettingsScreen.kt` | 移除外层 `padding(horizontal = 4.dp)`（子项本身由 `Inset` 提供 24 dp） | 1 处边距 |
| 15 | `feature/DrugScreens.kt` | 新增药品页边距 20→24 | 1 处边距 |

**标签替换计数**：全仓 `grep -c FilterChip` 命中 **22 行** = **23 个实例**（`MeasurementScreens.kt:64` 一行有 2 个 chip）；替换后递归扫描 `app/src/main`：`FilterChip` **0 处**、`JournalTag(` **23 个调用点**（+1 个定义）。
**按钮替换计数**：Recovery **7**、Restore **9**、Export **3**，共 **19** 个 `JournalButton`；三文件内原生 `Button(`/`OutlinedButton(` 递归扫描 **0 处**。
**说明（JournalNavigation.kt）**：任务书提到的“JournalNavigation 编辑页 20 dp”在 `JournalNavigation.kt` 本体中**不存在**（该文件无页面 padding）；实际 20 dp 位于由它路由的编辑页实现：`DrugScreens.kt:47`（新增药品）、`MeasurementScreens.kt:159`（记录测量）、`TimelineScreens.kt:89/114`（写日常记录/原始记录），已全部改为 24 dp，**`JournalNavigation.kt` 无需改动**。

---

## 2. 构建与安装

```powershell
.\gradlew.bat --gradle-user-home .gradle-user --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
```

- 结果：`BUILD SUCCESSFUL in 28s`，`86 actionable tasks: 18 executed, 68 up-to-date`，EXIT=0（`_tools/build.log`）。
- `app-debug.apk`：29,874,168 B，2026-10-24 05:20:59，sha256 `71D33B655B83CC683101FB4C7B74BA426AB312B9A336CF4BE5198660344E65D9`。
- `app-debug-androidTest.apk`：2,932,482 B，sha256 `673ECA88164BA4D77DECFB06C20C4A59511265739A8CE65BD7E5579B5FA929D8`（测试代码本轮未改，APK UP-TO-DATE；测试 APK 不打包 app 类，修复在 app APK 内）。
- 安装（`emulator-5556`）：`adb install -r -g` 两个 APK 均 `Success`。
- JVM：**342/342** 通过（`_tools/unit-test-summary.txt`，`app/build/test-results/testDebugUnitTest/TEST-*.xml` 56 类）。

---

## 3. 截图证据（本轮新生成，非旧图）

1. **运行前先删除设备旧截图目录**（`figma-ui-alignment/`、`figma-sync/` 及 `verification/` 根下旧 `c0*/b0*/d0*` 图）；
   删除前设备清单保存在 `_tools/device-verification-before.txt`（含 2026-10-23 的旧 `c02-home.png` 等），证明本轮 after 全部为新渲染。
2. 安装终版 APK 后按类运行（`-e class ...`），日志见 `_tools/instrument-*.log`：

| 测试类 | 结果 | Time |
|---|---|---|
| `cn.yunji.journal.UiSnapshotCoverageTest` | **OK (17 tests)** | 48.372 s |
| `cn.yunji.journal.UiSnapshotTest` | **OK (9 tests)** | 36.943 s |
| `cn.yunji.journal.NavigationExperienceTest` | **OK (23 tests)** | 193.038 s |
| `cn.yunji.journal.UiStateMatrixTest`（补充） | **OK (7 tests)** | 32.66 s |

   另有 2 次定点冒烟（早于整类运行，验证 `JournalTag` 语义与渲染）：`UiSnapshotCoverageTest#snap06TrashEmpty` OK、`NavigationExperienceTest#rootSwitchRetainsVisitFilterAndDoesNotCreateNestedProfileRoot`（含 `:817 assertIsSelected`）OK（`_tools/smoke-*.log`）。
3. 拉取：`after/` **22 张**（Coverage）、`after-supplementary/` **9 张**（UiSnapshot）、`navigation/` **20 张**（NavigationExperience 的历史 `captureToImage` 产物，本轮顺带保留）。
4. 尺寸校验：Coverage+Supplementary **31/31 张 1080×2400**；`navigation/` 中 4 张为**节点裁剪图**（非整屏：`b05-retained-relink 840×698`、`b05-zoom 1080×1957`、`b06-delete-scope`/`c01left-delete-scope 840×817`），只作行为证据、不参与几何测量（`_tools/screenshot-dimensions.txt`）。

`after/`（22，`UiSnapshotCoverageTest`）：
`cloud-connection.png`、`cloud-connection-backup.png`、`cloud-connection-cleanup.png`、`reminder-settings.png`、
`reminder-settings-custom.png`、`custom-reminder-editor.png`、`security-settings.png`、`local-backup.png`、
`restore-wizard.png`、`restore-wizard-key.png`、`export-screen.png`、`export-screen-disabled.png`、`trash-empty.png`、
`drug-catalog.png`、`drug-editor.png`、`measurement-editor.png`、`journal-entry-editor.png`、`pregnancy-editor.png`、
`visit-detail.png`、`visit-editor.png`、`timeline.png`、`timeline-source-detail.png`。

`after-supplementary/`（9，`UiSnapshotTest`，主页面 + 带标签的列表页）：`01-today.png`、`02-visits.png`、`03-records-hub.png`、
`04-profiles.png`、`05-medications.png`、`06-intakes.png`、`07-measurements.png`、`08-visit-documents.png`、`09-recovery.png`。

> before 基准：`../2026-10-24-ui-theme-fix/after/`（**同一测试类/同一组 17 用例/同一设备基线**、P0-2 主题修复后构建，本轮直接继承其 22 张作为 P0-1/3/6 的 before）；
> 更早的 `../2026-10-24-figma-ui-alignment/app-screens/`（P0-2 修复前）作为第二组 before，两组的标签/按钮/边距数值一致（主题修复未改几何），交叉核对见 `measurements-summary.txt`。

---

## 4. 测量方法（可复现）

- 工具：bundled Python 3 + Pillow 12.3 / numpy 2.3（`measure_tags.py`、`summarize_measurements.py`、`make_diff_stats.py`）。
- 换算：1080 px / 411.4286 dp ⇒ **1 dp = 2.625 px**；屏 1080×2400 = 2,592,000 px。
- 指标：
  1. **精确色计数**（tol=0；Compose 平涂色 PNG 可精确命中）与 bbox；
  2. **连通域**（4 邻域、行 run + 并查集）：用于标签（`#356B53` 选中底 / `#DDE4DA` 未选中描边）、白色卡片（`#FFFFFF`，w≥850 px）；
  3. **圆角代理**：`top_row_inset`（组件 bbox 首行首个纯色像素相对 bbox 左缘的内缩 px）与 `corner_void`（bbox 左上 32×32 内非填充像素数）——两者随半径单调变化，用于 before/after 对比；
  4. **全宽元素行主色检测**（`wide_blocks`）：逐行取非背景主色（≥400 px）并跨行分组，得到按钮/卡片 bbox —— 对胶囊（两端圆角）与含居中文字的按钮稳健；`outline_rings` 用 `#DDE4DA` 连通域识别 1 dp 描边环（原生 OutlinedButton / OutlinedTextField）；
  5. **页面边距**：逐行最左非背景像素（排除 AppBar 与底部导航区、排除 x=0 全出血行）取众数；无卡片/描边元素的页面该指标受**字形左边距**影响（见 §5.3 备注），此时以按钮/卡片 bbox 的 x0 为准；
  6. **sha256** 判断 before/after 是否逐字节一致；**像素差 bbox + 主色对**用于变化归因。
- 命令：

```powershell
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-consistency-fix\measure_tags.py
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-consistency-fix\summarize_measurements.py
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-consistency-fix\make_diff_stats.py
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-consistency-fix\make_diff_crops.py
```

- 输出：`measurements.json`（逐图全量结构化）、`measurements.txt`（控制台摘要全文）、`diffs/diff-stats.txt`（逐图差异像素/bbox/主色对）、`diffs/*-before-after.png`（5 张 2 联对照裁剪图）。

> `custom-reminder-editor.png` 是 UiAutomation 整屏截图（含系统状态栏与 dialog dim 遮罩），页面边距指标不适用；其变化主要是状态栏时钟（运行时刻不同）与对话框文字，未纳入几何统计。

---

## 5. 关键数值 before → after

### 5.1 P0-1 标签（`JournalTag` 替换 `FilterChip`）

before = `2026-10-24-ui-theme-fix/after`（与 `figma-alignment/app-screens` 数值一致）。

| 页面（截图） | before：M3 FilterChip | after：JournalTag |
|---|---|---|
| `timeline.png`（全部选中 + 4 个可见未选） | 选中：h **32.0 dp**、w 64.0 dp、填充 **`#E7F0E6`**、字 **`#356B53`**（字形 902 px）；未选：h 32 dp、浅描边；圆角代理 `top_row_inset` **20 px**、`corner_void` 112 | 选中：h **48.4 dp**、w 64.0 dp、填充 **`#356B53`**、字 **`#FFFFFF`**（字形 902 px）；未选 h **48.4 dp**、w 64/64/96/43.4 dp（最后一个为横向滚动**屏幕右缘裁切**）、白底占比 0.816/0.803/0.800/0.788、`#DDE4DA` 描边、`#5F7166` 字 731/838/1848/874 px；`top_row_inset` **31 px**、`corner_void` **242** |
| `trash-empty.png`（删除的项目选中 + 保留的资料未选） | 选中：h **32.0 dp**、w 153.1 dp、`#E7F0E6` 底、`#356B53` 字 2886 px | 选中：h **48.4 dp**、w 153.1 dp、`#356B53` 底、白字 2886 px；未选：h 48.4 dp、w 153.1 dp、x0 185.1 dp、白底占比 0.823、`#5F7166` 字 2760 px |
| `security-settings.png`（立即选中 + 1/5 分钟未选） | 选中：h **32.0 dp**、w 73.1 dp、`#E7F0E6` 底、`#356B53` 字 1056 px、x0 20.2 dp | 选中：h **48.4 dp**、w 73.1 dp、`#356B53` 底、白字 1056 px、x0 24.0 dp；未选 2 个 h 48.4 dp、w 64.0/73.1 dp、白底占比 0.81/0.82 |
| `02-visits.png`（列表/日历 + 4 状态） | —（supplementary 无同基线 before，不做逐像素 before/after） | 选中 2 个（列表、全部）：h **48.4 dp**、w **64.0 dp**、x0 24.0 dp；未选 4 个（待进行/已完成/已取消/…）：h 48.4 dp、w 64/80/80/80 dp |
| `06-intakes.png`（按日计划选中 / 全部历史未选） | — | 选中/未选均 h **48.4 dp**、w 96.0 dp；未选白底占比 0.806、`#5F7166` 字 1809 px |
| `07-measurements.png`（原始记录选中 / 分组趋势未选） | — | 选中/未选均 h **48.4 dp**、w 96.0 dp；未选白底占比 0.800 |
| `08-visit-documents.png`（本次资料选中 / 回收站未选） | — | 选中/未选均 h **48.4 dp**、w 96.0/121.1 dp；未选白底占比 0.832 |
| `navigation/c04-timeline.png`（测试内时间线筛选） | — | 选中 h **48.4 dp**、w 64.0 dp；4 个未选 h 48.4 dp（横向滚动视图，x0 随滚动偏移） |

**结论（P0-1）**：设计规格 4 项全部达成 —— 高 **48 dp**（实测 48.4 dp，layout 取整 127 px）、min 宽 **64 dp**（两字标签实测 64.0 dp）、圆角 **12 dp**（首行内缩 20→31 px，corner_void 112→242）、选中 **`#356B53` 底 + 白字**、未选 **白底 + `#DDE4DA` 1 dp 描边 + `#5F7166` 字**。三组页面（timeline/trash/security）的 **选中文字字形像素数与 before 完全相等**（902/2886/1056），证明只换颜色与尺寸、未改字号或文案。旧 32 dp soft chip 在 7 张新截图中为 **0**（`02-visits` 的 1 个 32 dp `#E7F0E6` 区块经裁剪确认为**底部导航选中指示胶囊 56×32 dp @x0=125.3 dp, y0=819.4 dp**，非 FilterChip）。

### 5.2 P0-3 按钮（原生 M3 → `JournalButton` 52 dp 胶囊）

| 页面 / 按钮 | before（原生） | after（JournalButton） |
|---|---|---|
| `local-backup.png` 主“导出加密备份” | **h 40.0 dp**、w 371.05 dp、x0 **20.19 dp**、`#356B53` 填充 | **h 52.19 dp**、w 363.43 dp、x0 **24.0 dp**、`#356B53` 填充 |
| `local-backup.png` 次“从备份恢复资料” | **h 40.0 dp**、w 371.05 dp、x0 20.19 dp、**描边环**（无填充） | **h 52.19 dp**、w 363.43 dp、x0 24.0 dp、**`#E7F0E6` 填充**（secondary） |
| `restore-wizard.png` 主“选择加密备份” | **h 40.0 dp**、w 371.05 dp、x0 20.19 dp | **h 52.19 dp**、w 363.43 dp、x0 24.0 dp |
| `restore-wizard.png` 次“从云端备份恢复” | **h 40.0 dp**、w 371.05 dp、**描边环** | **h 52.19 dp**、w 363.43 dp、**`#E7F0E6` 填充** |
| `restore-wizard-key.png` 主“校验备份”（禁用） | h 40.0 dp、w 371.05 dp、x0 20.19 dp、禁用色 `#E1E5DF` | h 52.19 dp、w 363.43 dp、x0 24.0 dp、`#E1E5DF` |
| `export-screen-disabled.png` 导出按钮（禁用，滚动至底） | h 40.0 dp、x0 **20.19 dp**、`#E1E5DF` | h **52.19 dp**、x0 **24.0 dp**、`#E1E5DF` |

- `export-screen.png`（页面顶部）按钮在首屏之外，故按钮几何以 `export-screen-disabled.png`（滚动至底后可见的禁用导出按钮）为证；`restore-wizard-key` 中 h=56 dp 的 `outline_rings` 是 `OutlinedTextField` 边框（非按钮），before/after 一致。
- 次按钮由“无填充的 40 dp 描边”变为“52 dp `#E7F0E6` 填充”：`diff-stats` 主色对 `#F7F8F3→#E7F0E6`（local-backup 22,640 px、restore-wizard 28,245 px）直接对应新增的浅绿胶囊面。
- 禁用按钮变高同理：`#F7F8F3→#E1E5DF`（restore-wizard-key 28,875 px、export-screen-disabled 14,501 px）。
- 全宽按钮宽度 before 371.05 dp = 411.43−2×20.19，after 363.43 dp = 411.43−2×24.0，与页边距改动自洽。

### 5.3 P0-6 页面边距（x0，单位 dp）

| 页面 | 测量依据 | before | after |
|---|---|---|---|
| `cloud-connection.png` | 文本/输入框左缘众数 | **20.19** | **24.0** |
| `security-settings.png` | 文本/按钮左缘众数 | **20.19** | **24.0** |
| `export-screen.png` | 文本/输入框左缘众数 | **20.19** | **24.0** |
| `drug-editor.png`（新增药品） | 文本/输入框左缘众数 | **20.19** | **24.0** |
| `journal-entry-editor.png`（写日常记录） | 文本左缘众数 | **20.19** | **24.0** |
| `measurement-editor.png`（记录测量） | 文本/输入框左缘众数 | **20.19** | **24.0** |
| `restore-wizard-key.png` | 文本左缘众数 | **20.19** | **24.0** |
| `local-backup.png` | **全宽按钮填充 x0** | **20.19** | **24.0** |
| `restore-wizard.png` | **全宽按钮填充 x0** | **20.19** | **24.0** |
| `export-screen-disabled.png` | **全宽禁用按钮 x0** | **20.19** | **24.0** |
| `reminder-settings.png` / `-custom.png` | **白卡 x0（连通域）** | **28.19**（4+24 叠层） | **24.0** |
| `timeline-source-detail.png` | 文本左缘众数 | 21.33 | 25.14（≈+3.81） |
| `timeline.png`、`trash-empty.png`、`visit-detail.png`、`visit-editor.png`、`pregnancy-editor.png`、`drug-catalog.png` | 白卡 / 按钮 x0 | 24.0 | 24.0（未受影响） |

- 所有 after 页面中，可识别的**白色 `JournalCard` x0 均为 24.0 dp**（timeline/trash/visit/reminder/drug-catalog/记录枢纽/服药/健康/资料等 17 个页面/截图），全宽按钮 x0 也均为 24.0 dp。
- 估算器限制（如实记录）：`local-backup` / `restore-wizard` / `restore-wizard-key` / `timeline-source-detail` 这批页面没有卡片/描边元素覆盖大量行，逐行“最左非背景像素”会落在文字字形上（受汉字左边距影响，众数 22.86→26.67、21.33→25.14），因此这三页的边距结论以**按钮填充块 x0=24.0** 为准（before 同为 20.19）；这不是未达标，而是测量偏差，改动量恒为 +3.81~+4.0 dp ≈ 20→24 dp。
- `reminder-settings`：移除外层 `padding(horizontal = 4.dp)` 后，`Inset` 的 24 dp 成为唯一页边距，白卡 x0 由 **28.19 → 24.0 dp**，与设计一致；改动前后卡片宽度 363.4→363.4 dp（411.43−2×24.0），高度逐项不变（156.95/204.57/288.76 dp）。
- `custom-reminder-editor.png` 为整屏 dialog 截图（含 dim 遮罩），不参与边距测量。

### 5.4 无副作用与变化归因

- **4 张未受影响页面 sha256 与 before 逐字节一致**：`drug-catalog.png`、`pregnancy-editor.png`、`visit-detail.png`、`visit-editor.png`。
- 其余 18 张变化均落在目标区域或运行间动态内容（`_tools`/`diffs/diff-stats.txt`）：
  - **P0-1 标签**：`trash-empty.png` 变化 bbox 仅 **355.4×133.0 dp @y=112.8 dp**（即标签行；主色对 `#E7F0E6→#356B53` 28,093 px = 选中底色互换、`#F7F8F3→#FFFFFF` 39,486 px = 未选标签白底变大）、`timeline.png`（含标签行与动态时间）。
  - **P0-3 按钮**：`local-backup.png`（`#F7F8F3→#E7F0E6` 22,640 px 等）、`restore-wizard.png`、`restore-wizard-key.png`、`export-screen-disabled.png`。
  - **P0-6 边距**：`cloud-connection*.png`、`security-settings.png`、`export-screen.png`、`drug-editor.png`、`journal-entry-editor.png`、`measurement-editor.png`、`timeline-source-detail.png`、`reminder-settings*.png` —— 差异呈整体左右位移（`#F7F8F3↔#223C32` 成对出现），与 20→24 dp 平移一致。
  - **运行间动态内容**（非本次修复）：`measurement-editor.png` 的“当前时间”`LocalTime.now()`、`timeline-source-detail.png`/`timeline.png` 的时间文案、`custom-reminder-editor.png` 的系统状态栏时钟/dim 遮罩。

---

## 6. 语义与测试兼容性

- `JournalTag` 用 `Modifier.selectable(selected=…, enabled=…, role=Role.Tab, onClick=…)`：clickable/selectable 的**语义合并**保证 `onNodeWithText("已取消").assertIsSelected()` 仍命中标签节点；`testTag` 仍挂在同一节点上（`timeline-filter-*`、`restore-*`、`export-*` 等全部保留）。
- `NavigationExperienceTest` 整类 **23/23 通过**，其中三处 `assertIsSelected`（`:156 timeline-filter-JOURNAL`、`:226 timeline-filter-DOCUMENT`、`:817 "已取消"`）全部通过；另做定点冒烟单独复跑 `:817` 用例通过。
- `UiSnapshotCoverageTest` 17/17、`UiSnapshotTest` 9/9、`UiStateMatrixTest` 7/7 通过；未删除/未修改任何测试断言。
- 代码级核对：`app/src/main` 递归扫描 `FilterChip` = **0**；三页面原生 `Button`/`OutlinedButton` = **0**；`JournalTag` 调用点 = **23**（`_tools/code-replacements.txt`）。

---

## 7. 限制（结论必须在此边界内读）

| 限制 | 影响 |
|---|---|
| **模拟器渲染 ≠ 真机视觉** | 全部像素证据来自 API 36 16 KiB 模拟器 Compose/UiAutomation 截图；真机（小米 11 Ultra）`MainActivity` 设 `FLAG_SECURE` 无法截取应用内容，字体栅格/厂商字体/系统栏处理可能不同。真机结论只能到结构/行为级。 |
| **仅单一配置** | 只验证浅色、竖屏、`font_scale=1.0`、412×915 dp；**深色**仅代码级使用 `MaterialTheme.colorScheme` 角色（primary/onPrimary/surface/outline/onSurfaceVariant），未截图验证；大字体（200%）下 `JournalTag` 高度随 `labelLarge` 行高增长（保留 min 48 dp），未截图。 |
| **标签禁用态未截图** | `security-settings` 夹具 `strict=false`、其余标签页均启用；禁用视觉（38% 内容色、选中底 12% 主色）仅代码实现，无像素证据。 |
| **横向滚动裁切** | 时间线/产险状态标签行可横向滚动，屏幕右缘标签被裁切（如 timeline 第 5 个 w=43.4 dp、c04-timeline 左侧裁切），measured width < 64 dp 属裁切而非最小宽度不达标。 |
| **页面边距估算器** | 对无卡片/描边元素的页面（local-backup/restore-wizard/timeline-source）逐行左缘受字形左边距影响，已在 §5.3 用“按钮填充 x0”作为主证据并列出两法数值差；不宣称这些页面逐像素 24.0，但按钮/卡片 x0 均精确 24.0。 |
| **截图覆盖** | 22+9 张截图中带标签的页面 7 个（timeline/trash/security/visits/intakes/measurements/visit-documents）+ 导航测试截图 2 个；其余含标签页面（服药编辑、测量编辑、用药方案、药品目录等）**代码已替换但无像素证据**。 |
| **布局结构调整不在本任务** | 只替换组件与边距，未改页面内部布局：如 `security-settings` 的三个超时标签仍为**纵向三行**（原代码即 `Column` 直排，未改为 Row）；`export-screen` 内容仍为分割线分区而非卡片（P0 未要求）。这些属设计/后续任务决策，本轮未动。 |
| **对话框/系统栏** | `custom-reminder-editor.png` 为整屏 + dim + 系统状态栏（时钟随时间变化），其变化不参与几何结论。 |
| **未做** | P0-4 二级页头部、P0-5 Timeline 布局未改（不在本任务范围）；未跑全量仪器矩阵（仅 4 个类）；未操作小米真机（设备不在线）。 |

---

## 8. 产物清单

```
docs/verification/2026-10-24-ui-consistency-fix/
├─ README.md                          本文件
├─ after/                             22 张 after 截图（UiSnapshotCoverageTest，1080×2400）
├─ after-supplementary/               9 张补充截图（UiSnapshotTest，1080×2400）
├─ navigation/                        20 张 NavigationExperienceTest 截图（4 张为节点裁剪图）
├─ diffs/
│  ├─ diff-stats.txt                  18 张变化图的差异像素/bbox/主色对；4 张 IDENTICAL
│  ├─ tags-trash-empty-before-after.png
│  ├─ tags-timeline-before-after.png
│  ├─ tags-security-settings-before-after.png
│  ├─ buttons-local-backup-before-after.png
│  └─ buttons-export-disabled-before-after.png
├─ measurements.json                  逐图全量结构化测量（标签/按钮/白卡/边距/色板计数/sha256）
├─ measurements.txt                   控制台摘要全文（tags/buttons/margins/sha256 对比）
├─ measure_tags.py                    主测量脚本（精确色/连通域/圆角代理/全宽块/白卡/边距）
├─ summarize_measurements.py          摘要打印脚本
├─ make_diff_stats.py                 差异像素/bbox/主色对统计
├─ make_diff_crops.py                 5 张 before|after 对照裁剪图
└─ _tools/
   ├─ build.log                        Gradle 构建日志（BUILD SUCCESSFUL in 28s）
   ├─ apk-info.txt                    APK 大小/时间/sha256
   ├─ unit-test-summary.txt           JVM 342/342
   ├─ instrument-coverage.log         OK (17 tests) Time 48.372
   ├─ instrument-supplementary.log    OK (9 tests) Time 36.943
   ├─ instrument-navigation.log       OK (23 tests) Time 193.038
   ├─ instrument-uistatematrix.log    OK (7 tests) Time 32.66
   ├─ smoke-trash.log / smoke-visits-chip.log   定点冒烟（JournalTag 语义）
   ├─ code-replacements.txt           替换计数与零残留核对（recursive）
   ├─ screenshot-dimensions.txt       51 张截图尺寸清单
   ├─ device-verification-before.txt  运行前设备旧截图清单（证明已删除重拍）
   ├─ device-verification-after.txt   运行后设备新截图清单
   ├─ emulator-baseline-restored.txt  跑后模拟器基线恢复记录
   └─ run_instrument.ps1 / apply_p0_button_fallback.ps1   运行/替换辅助脚本（复现用）
```

---

## 9. 复现命令

```powershell
# 1) 构建 + JVM 单测（--offline）
.\gradlew.bat --gradle-user-home .gradle-user --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest

# 2) 安装（仅专用模拟器）
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 install -r -g -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk

# 3) 清旧图 → 按类跑 → 拉取（脚本已封装上述步骤，可整段复跑）
pwsh -NoProfile -ExecutionPolicy Bypass -File docs\verification\2026-10-24-ui-consistency-fix\_tools\run_instrument.ps1

# 4) 测量与差异证据
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-consistency-fix\measure_tags.py
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-consistency-fix\summarize_measurements.py
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-consistency-fix\make_diff_stats.py
```

## 10. 模拟器基线恢复（跑后）

在 `emulator-5556` 执行并核对（`_tools/emulator-baseline-restored.txt`）：`font_scale=1.0`、`cmd uimode night no`（浅色）、
`stay_on_while_plugged_in=0`、`accelerometer_rotation=1 / user_rotation=0`（竖屏）、`wm size=1080x2400`、`wm density=420`；
`am force-stop cn.yunji.journal` 与 `cn.yunji.journal.test` 后 `ps -A | grep yunji` 为空（无残留进程）。
未操作小米 11 Ultra / 14 Pro；未改动 `Theme.kt`/测试代码/主文档/阶段记录/Figma。

---

## Lead 独立复核（2026-10-24 06:14）

- Lead 在 Gradle 释放后独立重跑 `NavigationExperienceTest`（emulator-5556）：**OK (23 tests)，EXIT=0**（原始输出 `_tools/lead-rerun-navigation-experience.log`），确认 `assertIsSelected`（:156/:226/:817）在最终代码上通过。
- Lead 复核范围：`FilterChip` 全仓残留 0、`JournalTag` 23 处调用（1 定义 + 22 芯片实例）、`Theme.kt`/`JournalNavigation.kt` 未被本轮修改、测试代码仅上一轮 `CloudUploadDeviceTest` 变更；改动 15 个产品文件均在任务范围内。
