# P0-2 M3 容器色主题修复 · 构建与视觉验证（2026-10-24）

- 对应任务：共享任务 `task-5`（验证工程师 `theme-verify`）。
- 验证对象：Lead 的 P0-2 UI 修复，**仅改** `app/src/main/java/cn/yunji/journal/core/designsystem/Theme.kt`：
  为 Material3 1.2+ 新增的 `surfaceContainerLowest/Low/Container/High/Highest`、`surfaceTint`、`surfaceBright/Dim`、
  `inverseSurface/OnSurface/Primary`、`errorContainer` 等角色补齐暖白绿色板，替换 M3 默认淡紫灰；
  并新增 `Apricot/Amber/Danger/DangerSoft` 常量。
- 终版映射（`Theme.kt` light scheme，sha256 `C5D494446CA07E6605B2AD5A3A863D63FC59361457CF8157A33D02E614D75C7F`）：
  `surfaceTint=Primary(#356B53)`、`surfaceBright=White`、`surfaceDim=Outline(#DDE4DA)`、
  `surfaceContainerLowest/Low/Container/High=White(#FFFFFF)`、`surfaceContainerHighest=Outline(#DDE4DA)`、
  `inverseSurface=Ink(#223C32)`、`inverseOnSurface=Background(#F7F8F3)`、`inversePrimary=Soft(#E7F0E6)`、
  `errorContainer=DangerSoft(#FCECE7)`、`onErrorContainer=Danger(#A44238)`。
- 迭代说明：中间版本曾把 `surfaceContainerLow/Container/High` 设为 `Background/Background/Soft`（对话框实测 `#E7F0E6`）；
  Lead 随后微调为 **White**（避免大面积对话框染浅绿），本文件所有 after 数值均为**终版**重跑结果。
- 设备：`emulator-5556`（PJ_API36_16K：Android 16 / API 36 / x86_64 / **16 KiB page** / 1080×2400 / 420 dpi = 411.4×914.3 dp）；
  基线 `font_scale=1.0`、浅色（Night mode: no）、竖屏。**未操作小米真机**。
- 边界：只写本目录；未改 app 代码/测试/主文档/Figma；Gradle 本会话独占。

---

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| 构建 | ✅ `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest` **BUILD SUCCESSFUL in 21s**（EXIT=0，墙钟 21.9s） |
| JVM 单测 | ✅ **342/342** 通过（0 失败 0 错误 0 跳过；56 个测试类 XML） |
| 截图回归 | ✅ `UiSnapshotCoverageTest` **OK (17 tests)**，Time **53.161s**（墙钟 55.1s）；产出 **22 张** 1080×2400 PNG |
| 补充截图 | ✅ `UiSnapshotTest` **OK (9 tests)**，Time **36.411s**（墙钟 37.7s）；产出 **9 张** 主页面 PNG（补充覆盖，不作 before→after 数差） |
| P0-2 对话框面 | ✅ `custom-reminder-editor.png` 对话框面 **`#ECE6F0` → `#FFFFFF`**；同一几何 `320.0×501.3 dp`（px [120,574]–[959,1889]），面内该色 **1,055,245 → 1,055,274 px（40.71%）** |
| P0-2 Switch 未选中轨道 | ✅ `export-screen.png` **`#E6E0E9`（6,352 px / 0.245%，轨道 47.6×27.4 dp）→ 0**；终版为 **`#DDE4DA`**（Outline）：未选中 Switch 的轨道+滑块+描边合并为 1 个连通域 `52.2×32.0 dp`（9,896 px） |
| 淡紫灰残留 | ✅ 22/22 after 截图中 `#ECE6F0/#E6E0E9/#F3EDF7/#F7F2FA/#E8DEF8/#EADDFF/#FFD8E4/#49454F/#938F99/#CAC4D0/#6750A4/#322F35/#D0BCFF`（tol≤2）**均为 0 像素**；补充 9 张亦为 0 |
| 卡片底色回归 | ✅ `reminder-settings.png` 白卡 `#FFFFFF` 覆盖率 60.72%→60.85%（差异来自通知权限状态文案，非主题；行扫描/定点采样几何与白面完全一致） |
| 其余页面 | ✅ 22 张中 **16 张与 before 字节级一致**（sha256 相同），无副作用 |
| lint（可选） | ✅ `:app:lintDebug` **BUILD SUCCESSFUL in 51s**，**0 errors / 27 warnings**（与 2026-10-23 记录一致，无新增） |
| 观察项 | ⚠️ ① 未选中 Switch 轨道与滑块同为 `#DDE4DA`，滑块位置仅靠形状区分（对比度 Outline/Background=1.22:1）；② `DropdownMenu/Snackbar/BottomSheet/日期选择器` 等不在现有截图测试状态内，未直接截图（角色映射正确，但无像素证据）；详见 §6 |

---

## 1. 构建与安装

```powershell
.\gradlew.bat --gradle-user-home .gradle-user --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
```

- 结果：`BUILD SUCCESSFUL in 21s`，`86 actionable tasks: 9 executed, 77 up-to-date`，`GRADLE_EXIT=0`，墙钟 21.9s（终版）。
- `app-debug.apk`：29,877,582 B，2026-10-24 04:34:19，sha256 `1B8D7FA8C3C32FCA2B8104F295C34F7B4022597EE9F4699D8E31365DE94100E3`。
- `app-debug-androidTest.apk`：2,082,107 B，sha256 `1783B068E7983B5F1E37F215B467B9C4A08D9D053F6B5E1E61B75CEB9420BBD2`
  （测试代码本轮未改而 UP-TO-DATE；测试 APK 不打包 app 类，主题修复在 app APK 内）。
- 安装（`emulator-5556`）：`adb -s emulator-5556 install -r -g app-debug.apk`、`install -r -g -t app-debug-androidTest.apk`，均 `Success`。
- JVM：`342/342` 通过，`app/build/test-results/testDebugUnitTest/TEST-*.xml` 56 个类。
- 证据：`build.log`、`apk-info.txt`、`unit-test-summary.txt`。

## 2. 截图证据（本轮新生成，非旧图）

1. 运行前**先删除设备上的旧截图目录**（`.../files/verification/figma-ui-alignment`、`figma-sync`），删除前的旧文件清单（2026-10-23 18:34 UTC 的 before 期文件）保存在 `device-verification-dir-before.txt`；
2. 安装终版 APK 后运行：

```powershell
adb -s emulator-5556 shell am instrument -w -r -e class cn.yunji.journal.UiSnapshotCoverageTest cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell am instrument -w -r -e class cn.yunji.journal.UiSnapshotTest cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner
```

3. 结果（`instrument.log` / `instrument-supplementary.log`）：`OK (17 tests)` Time **53.161**；`OK (9 tests)` Time **36.411**。
4. 从设备 `getExternalFilesDir(null)/verification/figma-ui-alignment/` 拉取 **22 张**到 `after/`（`adb pull`，设备端文件 mtime 2026-10-23 20:35 UTC = 宿主 2026-10-24 04:35）；
   从 `verification/figma-sync/` 拉取 **9 张**到 `after-supplementary/`。
5. 校验：**31/31 张均为 1080×2400**（`screenshot-dimensions.txt`）；`after/` 22 张大小 92,818–264,571 B（最小 90.6 KB，均 >10 KB）；设备端清单见 `device-verification-dir-after.txt`。
6. 设备 `verification/` 根下另有 2 张 **2026-10-23 的旧图**（`c02-home.png`、`c02-history.png`，非本测试类产物），**未拉入 after/**，避免旧图混入。

`after/`（22，`UiSnapshotCoverageTest`）：
`cloud-connection.png`、`cloud-connection-backup.png`、`cloud-connection-cleanup.png`、`reminder-settings.png`、
`reminder-settings-custom.png`、`custom-reminder-editor.png`、`security-settings.png`、`local-backup.png`、
`restore-wizard.png`、`restore-wizard-key.png`、`export-screen.png`、`export-screen-disabled.png`、`trash-empty.png`、
`drug-catalog.png`、`drug-editor.png`、`measurement-editor.png`、`journal-entry-editor.png`、`pregnancy-editor.png`、
`visit-detail.png`、`visit-editor.png`、`timeline.png`、`timeline-source-detail.png`。

`after-supplementary/`（9，`UiSnapshotTest`）：`01-today.png`、`02-visits.png`、`03-records-hub.png`、`04-profiles.png`、
`05-medications.png`、`06-intakes.png`、`07-measurements.png`、`08-visit-documents.png`、`09-recovery.png`。

> 说明：before 基准来自 `../2026-10-24-figma-ui-alignment/app-screens/`（2026-10-24 02:35、**同一测试类/同一组 17 用例/同一设备基线**的修复前构建，22 张 1080×2400，见该目录 `manifest.md`、`instrument-run.log`）。
> 本轮 after 为同法重跑，故 before→after 可直接逐像素对照。补充 9 张（`UiSnapshotTest`）无同基线 before，只用于“无淡紫灰残留”的绝对色板扫描，不参与数差。

## 3. 测量方法（可复现）

- 工具：bundled Python 3 + Pillow 12.3 / numpy 2.3（`measure_colors.py`）。
- 换算：1080 px / 411.4286 dp ⇒ **1 dp = 2.625 px**；屏 1080×2400 = 2,592,000 px。
- 指标：① 精确颜色计数（tol=0；PNG 为 Compose 实填色）与**面积占比**；② 颜色 **bbox**（px/dp）；
  ③ Switch 等小面积色用 **BFS 4 邻域连通域**（面积≥2000 px）定位组件；④ `sha256` 判断 before/after 是否逐字节一致；
  ⑤ 变化区域颜色对 `before→after` top pairs + 差异 bbox，用于区分“主题变化 / 动态内容差异”。
- 命令：

```powershell
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-theme-fix\measure_colors.py
```

输出：`measure-output.txt`（控制台全文）、`measure-output.json`（结构化）。辅助脚本：`make_diff_crops.py`（差异裁剪图到 `diffs/`）、
`probe_card_geometry.py`（卡片行扫描/定点采样）、`scan_supplementary.py`（补充 9 张 + 2026-10-22 历史图色板扫描）、
`check_nearwhite.py`（近白 #FFFBFF 抗锯齿判别 + 对比度）、`check_dimensions.py`（尺寸校验）。

> 对话框是独立窗口，测试用 `UiAutomation.takeScreenshot()` 整屏截图（含系统状态栏与 dim 遮罩），因此对话框面 bbox 限定在
> 对话框区域（x 100–980 px，y 540–1910 px）内测量，排除状态栏白图标；系统状态栏时钟随运行时刻变化属正常。

## 4. 关键色 before → after（终版）

### 4.1 对话框面：`custom-reminder-editor.png`

| 项 | before（修复前构建，02:35） | after（终版，04:35） |
|---|---|---|
| 对话框面主色 | **`#ECE6F0`**（M3 基线 surfaceContainerHigh，淡紫灰） | **`#FFFFFF`**（White，设计白卡面） |
| 面内主色像素/占比 | 1,055,245 px / 40.71% | 1,055,274 px / 40.71% |
| 面 bbox（px） | [120,574]–[959,1889] | [120,574]–[959,1889]（**几何完全一致**） |
| 面 bbox（dp） | **320.0 × 501.3 dp** | **320.0 × 501.3 dp** |
| 残留 `#ECE6F0` / `#E6E0E9` | 1,055,245 px | **0 px** |

- 变化像素 1,070,358 px（41.295%），其中主色对 `#ECE6F0 → #FFFFFF` 占 1,054,952 px；其余为同一面换色后文字/描边的抗锯齿边缘色变化（`#EAE5EE→#FDFDFD` 等），无布局位移（`diffs/custom-reminder-editor-before-after.png`）。
- 面内文字对比度：`Ink #223C32` on `White #FFFFFF` = **11.93:1**（WCAG AA 通过）。

### 4.2 M3 未选中 Switch 轨道：`export-screen.png`

| 项 | before | after（终版） |
|---|---|---|
| 未选中轨道颜色 | **`#E6E0E9`**（M3 基线 surfaceContainerHighest） | **`#DDE4DA`**（Outline） |
| `#E6E0E9` 像素/连通域 | 6,352 px（0.245%），1 个组件 47.6×27.4 dp @px(896,1161) | **0 px / 0 组件** |
| 终版未选中 Switch | — | `#DDE4DA` 组件 **52.2×32.0 dp（9,896 px）@px(890,1155)**：轨道+滑块+1 dp 描边同色合并 |
| 同图其它 `#DDE4DA` | 2 个 179.4×56.0 dp（3,580 px，按钮描边，修复前已是令牌色） | 同左（未变） |

- 变化像素 6,768 px（0.261%），实际差异 bbox **52.2×32.0 dp**（裁剪图含 30 px 留边共 75.0×54.9 dp，`diffs/export-screen-before-after.png`），即该 Switch 及其紧邻区域；页面其余像素不变。
- 说明：M3 `Switch` 的未选中滑块/描边默认取 `colorScheme.outline`，与 `surfaceContainerHighest` 的新映射同色，故终版未选中 Switch 呈单一浅色胶囊（见 §6 观察 1）。

### 4.3 提醒设置卡片底色：`reminder-settings.png`

| 项 | before | after |
|---|---|---|
| `#FFFFFF` 白卡覆盖率 | 60.72%（1,573,761 px） | 60.85%（1,577,109 px） |
| `#F7F8F3` 背景覆盖率 | 33.75%（874,856 px） | 33.75%（874,856 px，**完全相同**） |
| 行扫描白卡区间（dp，y=800/1400/2000 px） | x0/宽 29.7/352.0；44.2/323.0；28.2/355.0 | 同上，**逐项一致** |
| 卡片内定点采样（4 点） | `#FFFFFF`×4 | `#FFFFFF`×4 |

- 该图 14,545 px 差异（0.561%，bbox 230.1×87.2 dp @y≈222 dp）经裁剪对比确认为**通知权限状态文案变化**：
  before “未允许；只能使用应用内待办 / 开启通知权限” → after “已允许显示通知 / 发送测试通知”（运行间权限状态不同）。
  **卡片底色/几何未变**，属非主题差异（`diffs/reminder-settings-before-after.png`）。

### 4.4 离板色（M3 基线淡紫灰）全量扫描

- 扫描 13 个 M3 基线角色色（tol≤2）：`#ECE6F0 #E6E0E9 #F3EDF7 #F7F2FA #E8DEF8 #EADDFF #FFD8E4 #49454F #938F99 #CAC4D0 #6750A4 #322F35 #D0BCFF`
  ⇒ **after/ 22 张：0 文件命中（0/22）**；`after-supplementary/` 9 张：**0/9**；2026-10-22 历史 9 张主页面：0/9（旧问题集中在对话框/开关）。
- 近白 `#FFFBFF`（M3 基线 surfaceBright）单独判别：**精确匹配 before/after 均为 0 px**；tol≤2 的近白像素为文字/边缘抗锯齿，
  before/after 计数基本一致（如 386/386、88/88、762/762、920/918），仅对话框由 27→266（换纯白面后白底 AA 像素略增）——**不是离板填充**（`nearwhite-check.txt`）。

### 4.5 其余差异归因（22 张中 16 张字节一致）

changed 6 张，其中 **2 张为主题目标变化、4 张为运行间动态内容**：

| 截图 | 差异像素 | 差异 bbox(dp) | 归因（证据） |
|---|---|---|---|
| `custom-reminder-editor.png` | 1,070,358（41.295%） | 32.0,19.0,334.9,701.0 | **主题**：对话框面 `#ECE6F0→#FFFFFF` |
| `export-screen.png` | 6,768（0.261%） | 339.0,440.0,52.2,32.0 | **主题**：Switch 未选中轨道 `#E6E0E9→#DDE4DA` |
| `measurement-editor.png` | 1,391（0.054%） | 36.6,378.7,61.7,12.2 | 动态“当前时间”文本 `18:34:24→20:19:15`（测试夹具 `LocalTime.now()`） |
| `reminder-settings.png` | 14,545（0.561%） | 48.8,222.1,207.2,64.4 | 通知权限状态文案（未允许→已允许 + 按钮文案），运行间环境状态 |
| `timeline-source-detail.png` | 1,912（0.074%） | 123.8,159.2,62.5,218.7 | 动态时间戳 `02:34→04:19` 及其换行 |
| `timeline.png` | 269（0.01%） | 50.7,808.8,6.9,9.1 | 动态时间 `02:34→04:19` |

**16 张 sha256 与 before 完全相同**（无任何副作用）：
`cloud-connection.png`、`cloud-connection-backup.png`、`cloud-connection-cleanup.png`、`drug-catalog.png`、`drug-editor.png`、
`export-screen-disabled.png`、`journal-entry-editor.png`、`local-backup.png`、`pregnancy-editor.png`、`reminder-settings-custom.png`、
`restore-wizard.png`、`restore-wizard-key.png`、`security-settings.png`、`trash-empty.png`、`visit-detail.png`、`visit-editor.png`。

### 4.6 令牌色覆盖（22 张 after 合计）

| 令牌 | 值 | 像素 |
|---|---|---|
| Background | `#F7F8F3` | 43,458,289 |
| Surface(White) | `#FFFFFF` | 6,302,004 |
| Soft | `#E7F0E6` | 1,280,655 |
| Outline | `#DDE4DA` | 257,751 |
| Primary | `#356B53` | 2,216,887 |
| Ink | `#223C32` | 605,098 |
| Muted | `#5F7166` | 132,636 |
| Danger | `#A44238` | 8,010 |
| Apricot / Amber | `#FAEAD8` / `#80582C` | 0 / 0（P0-5 时间线未重做，属已知未使用，不影响 P0-2） |

## 5. 结论

1. **P0-2 修复验证通过（终版）**：M3 默认淡紫灰容器色已从全部 31 张 after 截图中消失；
   对话框面 `#ECE6F0 → #FFFFFF`、未选中 Switch 轨道 `#E6E0E9 → #DDE4DA`，几何/布局不变，其余 16 张页面逐字节不变。
2. **行为面**：JVM 342/342、设备截图用例 17/17 + 补充 9/9 全通过，lint 0 错误（27 警告，与修复前同）。
3. **产物**：22 张 after + 9 张补充截图、测量脚本/JSON、差异裁剪图、全部日志均在本目录（见 §8）。
4. 对话框换白面后与 app 既有白卡语言一致（`JournalCard` 白卡、圆角 20/28），`Ink/White` 对比度 11.93:1，可读性达标。

## 6. 观察项与建议（非阻断）

1. **未选中 Switch 的滑块与轨道同色**：M3 未选中态由 `outline`（滑块/描边）+ `surfaceContainerHighest`（轨道）构成；两者终版都映射到
   `#DDE4DA`，因此未选中 Switch 为单一浅色胶囊，滑块不可靠颜色区分（对比度 Outline/Background = 1.22:1）。
   P0-2 目标（消除淡紫灰）已达成；若希望滑块更可辨识，可在后续把 `surfaceContainerHighest` 调为中间色，或对 `SwitchDefaults.colors(uncheckedThumbColor=…)` 显式指定。
2. **未直接截图的 M3 容器组件**：`DropdownMenu`、`Snackbar`、`BottomSheet`、`DatePicker` 等在当前 22+9 个截图状态中均未打开，
   只能依据 `Theme.kt` 角色映射判断（`surfaceContainer*=White`、`surfaceContainerHighest=Outline`、`inverseSurface=Ink`），
   **无像素级证据**。建议后续补一个“打开下拉菜单/Snackbar”的截图用例，或在 JVM 单测断言 `lightColorScheme` 的角色值。
3. **深色主题**：`darkColorScheme` 的 `surfaceContainer*` 也一并补齐，但本轮基线仅浅色，深色未截图验证。
4. **对话框面“白 vs 浅绿”的用户偏好**：终版为白面（Lead 决策），与设计“白卡”范式一致；设计稿无弹窗画板，属实现选择。

## 7. 限制（结论必须在此边界内读）

| 限制 | 影响 |
|---|---|
| 模拟器渲染 ≠ 真机视觉 | 全部像素证据来自 API 36 16 KiB 模拟器 Compose/UiAutomation 截图；真机（小米 11 Ultra）`MainActivity` 设 `FLAG_SECURE` 无法截取应用内容，字体栅格/厂商字体/系统栏处理可能不同。真机结论只能到结构/行为级。 |
| 仅单一配置 | 只验证浅色、竖屏、`font_scale=1.0`、412×915 dp；深色/平板/横屏/200% 字体未评估。 |
| 截图范围 | `UiSnapshotCoverageTest` 截 Compose 内容区（不含系统状态栏/手势条）；`custom-reminder-editor` 为 UiAutomation 整屏（含状态栏与 dim 遮罩），状态栏时钟随运行时刻变化。 |
| 测试夹具/环境 | 截图为合成测试数据；运行间“当前时间”“通知权限状态”差异已在上表归因，非主题差异；未使用真实用户数据。 |
| 范围 | 仅验证 P0-2 容器色主题修复；P0-1 筛选标签、P0-3 按钮体系等未在本轮。 |
| 非主观审美 | 结论以量化测量 + 抽查裁剪图为主，不含动效、交互手感与人工审美评审。 |

## 8. 产物清单

```
docs/verification/2026-10-24-ui-theme-fix/
├─ README.md                       本文件
├─ after/                          22 张 after 截图（UiSnapshotCoverageTest，1080×2400）
├─ after-supplementary/            9 张补充截图（UiSnapshotTest，1080×2400）
├─ diffs/                          6 张 before|after 差异裁剪图（2x 放大）
├─ measure_colors.py               主测量脚本（颜色/bbox/连通域/哈希/离板色扫描/近白判别/令牌覆盖）
├─ measure-output.txt / .json      测量原始输出
├─ make_diff_crops.py              差异裁剪 + before→after top color pairs
├─ diff-analysis.txt               差异裁剪脚本输出（颜色对证据）
├─ probe_card_geometry.py          卡片行扫描/定点采样脚本
├─ card-geometry-probe.txt         卡片几何探针输出
├─ scan_supplementary.py           补充 9 张 + 2026-10-22 历史图色板扫描
├─ supplementary-scan.txt          补充扫描输出
├─ check_nearwhite.py / nearwhite-check.txt   近白 #FFFBFF 抗锯齿判别 + 对比度
├─ check_dimensions.py / screenshot-dimensions.txt   31 张尺寸校验
├─ build.log                       Gradle 构建日志（终版）
├─ apk-info.txt                    APK 大小/时间/sha256（终版）
├─ unit-test-summary.txt           JVM 342/342（终版）
├─ instrument.log                  OK (17 tests) Time 53.161
├─ instrument-supplementary.log    OK (9 tests) Time 36.411
├─ lint.log / lint-summary.txt     0 errors / 27 warnings
├─ device-verification-dir-before.txt  运行前设备旧截图清单（2026-10-23 18:34 UTC，证明已删除重拍）
├─ device-verification-dir-after.txt   运行后设备新截图清单（2026-10-23 20:35 UTC）
├─ emulator-baseline-restored.txt  跑后模拟器基线恢复记录
└─ emulator-info-after-run.txt     跑后设备状态（基线）
```

## 9. 复现命令

```powershell
# 1) 构建 + JVM
.\gradlew.bat --gradle-user-home .gradle-user --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest

# 2) 安装（仅专用模拟器）
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 install -r -g -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk

# 3) 清旧图 → 跑截图用例 → 拉取
adb -s emulator-5556 shell "rm -rf /sdcard/Android/data/cn.yunji.journal/files/verification/figma-ui-alignment"
adb -s emulator-5556 shell am instrument -w -r -e class cn.yunji.journal.UiSnapshotCoverageTest cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 pull /sdcard/Android/data/cn.yunji.journal/files/verification/figma-ui-alignment/. docs/verification/2026-10-24-ui-theme-fix/after

# 4) 测量
C:\Users\sun\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe docs\verification\2026-10-24-ui-theme-fix\measure_colors.py
```

## 10. 模拟器基线恢复（跑后）

在 `emulator-5556` 执行并核对（`emulator-baseline-restored.txt`）：`font_scale=1.0`、`cmd uimode night no`（浅色）、
`stay_on_while_plugged_in=0`、`accelerometer_rotation=1 / user_rotation=0`（竖屏，与本轮开始前一致）、
`wm size=1080x2400`，`mCurrentOrientation=0`；`am force-stop cn.yunji.journal` 与 `cn.yunji.journal.test` 后确认无 `yunji` 进程残留。
未操作小米 11 Ultra / 14 Pro；未改动 app 代码/测试/主文档/Figma。
