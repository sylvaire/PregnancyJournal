# UI/UX 统一性深度审计（2026-11-13 · 第二轮）

> 本轮目标：**穷尽式**排查哪些界面与组件仍未按统一风格设计，避免像首轮那样只抽查部分页面。
> 首轮（[2026-11-13-ui-ux-audit](../2026-11-13-ui-ux-audit/README.md)）只核对了 `Components.kt`、`Theme.kt`、`JournalNavigation.kt`
> 与手工抽样页面；本轮改为**先机器扫描全部 28 个 UI 文件，再逐一核实**。

## 1. 方法

| 步骤 | 做法 |
|---|---|
| 1 | 枚举**全项目**含 `@Composable` 的文件（共 22 个），剔除纯逻辑文件后得 20 个 UI 文件 |
| 2 | 正则扫描 9 类「绕过设计系统」的原始组件调用 |
| 3 | 扫描硬编码颜色、字号、圆角、间距、字重 |
| 4 | 建立「页面 × 组件」矩阵，逐格确认 |
| 5 | 与 Ardot 设计稿 `7:2461` 已定义组件比对 |
| 6 | 对比度实算（深色模式） |

**审计范围（20 个文件）**：

`feature/` 19 个 —— `CloudConnectionScreen` `DocumentPreview` `DocumentScreen` `DrugScreens`
`ExportScreen` `IntakeScreens` `JournalApp` `JournalNavigation` `MeasurementScreens`
`MedicationScreens` `PdfDocumentView` `PrenatalScreens` `RecoveryScreen`
`ReminderSettingsScreen` `RestoreScreen` `SafetyCopiesScreen` `SecurityScreen`
`TimelineScreens` `TrashScreen`

`core/` 1 个 —— `share/OriginalShareDialog.kt`
（首轮审计遗漏此文件；本轮核实结论：标准 `AlertDialog` + `TextButton`，与全站对话框一致，**不构成问题**）

排除的非 UI 文件（9 个）：各类 `*Controller`、`JournalViewModel`、`DisplayFormat`、`NavigationTapGate`。
`MainActivity.kt` 虽无 `@Composable`，但持有 `setContent { PregnancyTheme { ... } }` 入口，已核对其主题注入正确。

## 2. 结论摘要

首轮结论「色板、字体、主按钮全站统一」**只对了一半**。真正统一的是**颜色**与**主按钮**；
**字号体系、表单、卡片范式、页面结构**存在 6 类系统性不一致，其中 3 类会直接被用户看到。

| # | 问题 | 严重度 | 影响面 | 状态 |
|---|---|---|---|---|
| P0-1 | `titleMedium`/`titleSmall` 未在 Theme 中定义，落到 M3 默认 | **高** | 34 + 1 处，14 个文件 | ✅ 已修 |
| P0-3 | 暖色卡片 `Apricot`/`Amber` 硬编码，深色模式刺眼 | **高** | 1 处（4 行代码） | ✅ 已修 |
| P0-4 | 底部导航等旧图标描边 1.7、颜色非 `#356B53` | **高（每次可见）** | 10 + 4 个文件 | ✅ 已修 |
| P0-2 | 卡片列表 vs 裸 `Column` 两种页面范式并存 | **高** | 6 个页面无卡片容器 | ⬜ 待排期 |
| P1-1 | `OutlinedTextField` 48 处无组件包装（设计稿有 `孕迹/Field`） | 中 | 15 个文件 | ⬜ 待排期 |
| P1-2 | `TextButton` 167 处无统一规格（4 种语义混用） | 中 | 16 个文件 | ⬜ 待排期 |
| P1-3 | 两处全屏 Dialog 自建顶栏，且两者彼此不一致 | 中 | 2 处 | ⬜ 待排期 |
| P2-1 | 按钮高度 40/52/56/72 四种 | 低 | 10 处 | ⬜ |
| P2-2 | `Card`/`OutlinedCard`/`Checkbox`/`HorizontalDivider` 未包装 | 低 | 4 个文件 | ⬜ |
| P2-3 | 加载态 22 处裸 `CircularProgressIndicator`，无统一组件 | 低 | 8 个文件 | ⬜ |
| P2-4 | 错误提示 29 处裸 `Text(color=error)`，无统一组件 | 低 | 多文件 | ⬜ |
| P2-5 | 3 处可点行缺 `onClickLabel`；1 处可点文本高度 18dp | 低（无障碍） | 4 处 | ⬜ |

## 3. P0 级问题

### 3.1 `titleMedium` / `titleSmall` 未定义（最严重）—— ✅ 已修

`Theme.kt` 原只显式定义了 9 个角色（缺 `displayLarge/displayMedium/headlineLarge/headlineSmall/titleMedium/titleSmall`）：

```
displaySmall(40/60/Bold) headlineMedium(28/42/Bold) titleLarge(20/30/Medium)
bodyLarge(16/24) bodyMedium(14/21) labelLarge(16/24/Medium)
labelMedium(14/21/Medium) bodySmall(12/18) labelSmall(12/18)
```

**`titleMedium` 与 `titleSmall` 不在其中**，因此回落到 Material3 默认值：

| 角色 | 实际值 | 项目同族角色 |
|---|---|---|
| `titleMedium`（34 处） | 16sp / 24 / Medium / **FontFamily.Default（Roboto）** | `titleLarge` = 20sp / 30 |
| `titleSmall`（1 处） | 14sp / 20 / Medium / **FontFamily.Default** | `labelMedium` = 14sp / 21 |

两重不一致：

1. **字号跳变**：同属「小节标题」语义，同一页面内既有 `titleLarge` 20sp 又有 `titleMedium` 16sp。
   例：`CloudConnectionScreen` 用 `titleMedium` 做「备份状态」「连接设置」「自动备份」「云端清理」4 个小节标题，
   而 `JournalPageHeader` 用 `headlineMedium` 28sp，中间缺一档，层级显得扁平。
2. **字族分裂**：项目自定义 `type()` 一律 `FontFamily.SansSerif`，而 M3 默认是 `FontFamily.Default`。
   两条路径在中文字体回退链上可能不同，导致字形/字重渲染不一致。

受影响文件（14 个）：
`DocumentScreen` `PrenatalScreens` `DrugScreens` `ExportScreen` `RecoveryScreen`
`ReminderSettingsScreen` `JournalApp` `MeasurementScreens` `MedicationScreens`
`CloudConnectionScreen` `SafetyCopiesScreen` `TimelineScreens` `SecurityScreen` `TrashScreen`

**修复方向**：在 `Theme.kt` 补 `titleMedium = type(16, 24, Medium)`、`titleSmall = type(14, 21, Medium)`，
或把所有 `titleMedium` 改为 `titleLarge`（需评估布局高度变化）。**前者风险更低**。

### 3.2 两种页面布局范式并存

| 范式 | 特征 | 页面 |
|---|---|---|
| **A. 卡片列表** | `Inset{}` + `LazyColumn` + `JournalCard`/`RecordRow`，内容装在 20dp 圆角白卡里 | `JournalApp` `TimelineScreens` `DocumentScreen` `DrugScreens` `TrashScreen` `PrenatalScreens` `IntakeScreens` `MedicationScreens` `MeasurementScreens` `ReminderSettingsScreen` `JournalNavigation` |
| **B. 裸 Column** | `Column(Modifier.padding(24.dp))` 直接铺 `Text`/`OutlinedTextField`/`JournalButton`，**无卡片容器** | `ExportScreen` `RecoveryScreen` `RestoreScreen` `SecurityScreen` `CloudConnectionScreen` `DocumentPreview` `PdfDocumentView` |

同样进入「我的 → 某功能」的两级页面，`DrugScreens` 是白卡片列表，`ExportScreen` 是贴边平铺文本。
用户会感到「换了一个 App」。

其中 `CloudConnectionScreen` 更特殊：用了 `LazyColumn` 但 **`Inset=0`**，即列表项自己管 padding，与同范式其他页面不同。

**修复（已完成）**：在 `Theme.kt` 补

```kotlin
titleMedium = type(16, 24, FontWeight.Medium)
titleSmall  = type(14, 21, FontWeight.Medium)
```

两处都走项目自己的 `type()`，因此 `fontFamily = FontFamily.SansSerif`、行高与既有字阶一致。
选择「补定义」而非「把 34 处改成 titleLarge」，是因为后者会把所有小节标题放大到 20sp，
在 `CloudConnectionScreen` 这类小卡片密集的页面会增加高度、可能引起截断。

### 3.3 暖色卡片深色模式失配 —— ✅ 已修

`TimelineScreens.kt:178-193`：

```kotlin
Surface(..., color = Apricot) {          // Apricot = #FAEAD8（固定浅色）
    Text(title, color = Amber, ...)      // Amber = #80582C（固定深棕）
    ...
}
```

`Apricot`/`Amber` 是 `Theme.kt` 里的**顶层常量**，不随 `isSystemInDarkTheme()` 变化。

实算对比度：

| 场景 | 对比度 | 判断 |
|---|---|---|
| `Amber` on `Apricot`（浅色） | 5.32:1 | 合格（AA） |
| `Apricot` 卡片 on 深色背景 `#141D17` | **14.64:1** | **过亮**，深色模式出现刺眼亮斑 |

深色模式下这张卡片是一块近白色（`#FAEAD8`）的矩形，与 `#141D17` 背景形成极强反差，
且是全站唯一此类问题（其余颜色全部走 `MaterialTheme.colorScheme`）。

**修复（已完成）**：新增 `NoteCardColors(container, content)` 与 `LocalNoteCardColors`，
由 `PregnancyTheme` 里的 `CompositionLocalProvider` 按 `isSystemInDarkTheme()` 注入：

| 模式 | container | content |
|---|---|---|
| 浅色 | `#FAEAD8`（Apricot） | `#80582C`（Amber） |
| 深色 | `#3A2E1F` | `#F0D5B4` |

`TimelineScreens.JournalNoteCard` 改用 `LocalNoteCardColors.current`，不再直接引用常量。
深色对换算对比度：`#F0D5B4` on `#3A2E1F` = **9.36:1**（远超 AA），
卡片对深色背景 `#141D17` 仅 **1.31:1**（原为 14.64:1），不再出现亮斑。

`Apricot`/`Amber` 常量保留（浅色基准 + 供设计对照），但注释已标明「不要在 Composable 里直接引用」。

## 3.4 P0-4：上一轮图标归一化漏掉了旧哈希文件（本轮新发现）

上一轮（提交 `7b61069`）只把 **`ic-*.svg` 这 20 个**文件的描边统一到 1.75，
**漏掉了 `assets/design/` 下 14 个旧哈希名文件**。全量扫描结果：

| 文件 | 描边 | 颜色 | 引用数 | 说明 |
|---|---|---|---|---|
| `acf3f.svg` | **1.7** | `#356B53` | 1 | **底部导航「今天」** |
| `da483.svg` | **1.7** | **`#5F7166`** | 1 | **底部导航「产检」** |
| `cdc29.svg` | **1.7** | `#356B53` | **12** | 底部导航「记录」+ 文档类共 12 处 |
| `d9f3c.svg` | **1.7** | **`#5F7166`** | 2 | 底部导航「我的」+「管理孕期」 |
| `5fbe7.svg` | **1.7** | `#356B53` | 2 | 首页欢迎插图（64dp） |
| `42641.svg` | **1.7** | `#356B53` | 2 | 孕期档案编辑 |
| `86969.svg` | **1.7** | `#356B53` | 1 | `JournalEmptyState` 默认图标 |
| `dcd03.svg` | **1.7** | `#356B53` | 1 | 首页「健康指标」快捷入口 |
| `62b4c.svg` | **1.5 / 2** | **`#223C32`** | 0 | 已无引用（`viewBox` 还是 48×16） |
| `d34da.svg` | **1.7** | **`#5F7166`** | 0 | 已无引用 |
| `122c6.svg` | 1.75 | `#356B53` | 1 | 合规 |
| `73f08.svg` | 1.75 | `#356B53` | 0 | 合规但未引用 |
| `7b4fe.svg` | 1.75 | `#356B53` | 0 | 合规但未引用 |
| `cfd63.svg` | 1.75 | `#356B53` | 3 | 合规（chevron） |

**为什么严重**：底部导航 4 个图标中有 3 个是 1.7 描边（其余全站 1.75），
且 `da483`/`d9f3c` 的 SVG 内嵌了 `#5F7166` 而非规范的 `#356B53`。
虽然 `DesignIcon` 用 `ColorFilter.tint` 覆盖了实际颜色（视觉上看不出色差），
但描边 1.7 vs 1.75 是**实际可见的线宽差异**，而且底部导航是**每次打开 App 都看到**的位置。

**修复**：把这 10 个旧文件的 `stroke-width` 统一为 `1.75`、`stroke` 统一为 `#356B53`
（`5fbe7` 是 64×64 插图，建议单独保留原样或单独归一，需判断）。

## 4. P1 级问题

### 4.1 设计稿有 `孕迹/Field`，代码无对应组件

Ardot `7:2461`「孕迹 · 基础组件」定义 3 个组件：

| Ardot 组件 | 规格 | 代码对应 |
|---|---|---|
| `7:2484 孕迹/Button` | 364×52，圆角 26 | ✅ `JournalButton` 完全一致 |
| `7:2485 孕迹/Field` | 高 56 | ❌ **无组件**，48 处裸 `OutlinedTextField` |
| `7:2496 孕迹/RecordRow` | 364×72，44dp 图标 | ✅ `RecordRow` 完全一致 |

48 处 `OutlinedTextField` 分布在 15 个文件，各自手写 `isError`/`enabled`/`singleLine`/`label`，
没有统一的高度、圆角、错误态样式。设计稿已定 56dp 高，但代码未强制。

### 4.2 `TextButton` 语义混用

167 处 `TextButton` 承担了 4 种完全不同的语义，却没有区分规格：

| 语义 | 示例 | 期望 |
|---|---|---|
| 顶栏动作 | `DocumentPreview` 的「导出」「替换」「删除」 | 应走图标按钮或统一 TextAction |
| 行内次要操作 | `TrashScreen` 的「删除资料」「彻底删除」 | 危险色 + 最小触摸高度 |
| 对话框按钮 | 32 处 `AlertDialog` 的 confirm/dismiss | 应与 `JournalButton` 同圆角/字重 |
| 全文链接 | `JournalApp:120` 的「查看备份状态」 | 应可点区域 ≥48dp（现 18dp） |

### 4.3 两处全屏 Dialog 自建顶栏，互不一致

两处都设 `usePlatformDefaultWidth = false`（全屏），都绕过了 `JournalPage`/`JournalPageHeader`：

| 位置 | 标题样式 | 返回方式 |
|---|---|---|
| `DocumentPreview.kt:136-142` | `titleLarge` 20sp | 左侧 `TextButton("返回")` |
| `ReminderSettingsScreen.kt:250-253` | `headlineMedium` 28sp + `bodySmall` 副标题 | 无显式返回（靠系统手势） |

而全站其余页面统一为 `JournalPageHeader`：`headlineMedium` 28sp + `bodySmall` 副标题 + 左上角旋转 chevron 图标。
`ReminderSettingsScreen` 手写复刻了一部分（结构对但缺返回键），`DocumentPreview` 完全另起一套。
**同一份设计语言出现三种顶栏。**

## 5. P2 级问题

### 5.1 按钮高度四种

| 来源 | 高度 | 数量 |
|---|---|---|
| `JournalButton`（设计系统） | 52dp | 全站主按钮 |
| `OutlinedButton` 显式 | 56dp | 4 处（`JournalApp:208` `PrenatalScreens:115` `MeasurementScreens:162` `ReminderSettingsScreen`×2） |
| `OutlinedButton` 默认 | 40dp | 6 处（`MedicationScreens`×2 `DocumentScreen` `IntakeScreens` `SafetyCopiesScreen`） |
| `RecordRow` | 72dp | 行高（合理，非按钮） |

同一页面 `MeasurementScreens` 里，主按钮 52dp、次按钮 56dp，次按钮反而更高。

### 5.2 未包装的基础组件

| 组件 | 处数 | 位置 |
|---|---|---|
| `Card` | 1 | `SafetyCopiesScreen:86` |
| `OutlinedCard` | 1 | `RestoreScreen:82` |
| `Checkbox` | 3 | `ExportScreen:53,71` `RestoreScreen:92` |
| `HorizontalDivider` | 2 | `SafetyCopiesScreen:80` `RecoveryScreen:118` |

`Card` 默认圆角 12dp，而 `JournalCard` 是 20dp —— 同一 App 内两种卡片圆角。

### 5.3 加载态与错误态无统一组件

- `CircularProgressIndicator` **22 处 / 8 个文件**，无统一 `JournalLoading`。
- 错误提示 **29 处**裸 `Text(..., color = MaterialTheme.colorScheme.error)`，
  有的在 `Inset{}` 里、有的直接铺在 `Column`、有的带重试按钮、有的不带。
  `ExportScreen:76` 更是用 `if (lastResult != null) primary else error` 动态切色，与其他页面写法不同。

### 5.4 无障碍缺口

| 位置 | 问题 |
|---|---|
| `JournalApp.kt:120` | 可点文本「查看备份状态」高 18dp（`bodySmall`），**远低于 48dp** |
| `JournalApp.kt:129` `HomeQuickAction` | `Surface(onClick)` 无 `onClickLabel`；`padding(12.dp)` 无最小高度 |
| `TimelineScreens.kt:163` `TimelineRecordRow` | `Surface(onClick)` 无 `onClickLabel`（`RecordRow` 已修，这个副本未修） |
| `TimelineScreens.kt:189` 杏色卡 | `Surface(onClick)` 无 `onClickLabel` |

`TimelineRecordRow` 是 `RecordRow` 的手写复制品（同样 20dp 圆角/44dp 图标/16sp 标题），
但**没有跟进 `RecordRow` 的无障碍修复** —— 这是典型的「重复实现导致修复不同步」。

## 6. 已确认合规（本轮复验）

| 检查 | 结果 |
|---|---|
| 硬编码颜色 | **0 处**（全部颜色集中在 `Theme.kt`，业务代码 0 处 `Color(0x...)`） |
| 硬编码字号 | **0 处**（除 `Theme.kt` 定义） |
| 内联 `fontWeight`/`letterSpacing` | **0 处**（除 `Theme.kt`） |
| 字族覆盖 | 仅 2 处 `FontFamily.Monospace`（恢复密钥抄录，**合理例外**） |
| 裸 `Button`/`Switch`/`FilterChip`/`TopAppBar`/`FAB` | **0 处** |
| 页面标题 | 31 处 `JournalPage` 全部经 `JournalPageHeader` 统一渲染 |
| Snackbar | 集中在 `JournalNavigation` 1 处，已统一 16dp 圆角 |
| `JournalEmptyState` | 11 处覆盖主要空态 |
| `containerColor` 非主题来源 | **0 处** |
| 超出 4pt 网格的间距 | 仅 4 处（`3.dp`×2 进度条圆角、`6.dp`、`10.dp`、`14.dp`） |

## 7. 修复进展（本轮已落地）

| # | 项 | 状态 | 证据 |
|---|---|---|---|
| P0-1 | 补 `titleMedium = type(16,24,Medium)`、`titleSmall = type(14,21,Medium)` | ✅ **已修** | `Theme.kt:62-74`，34+1 处引用现在走项目字族与行高 |
| P0-3 | 杏色卡改走 `LocalNoteCardColors`，深色用 `#3A2E1F`/`#F0D5B4` | ✅ **已修** | `Theme.kt` 新增 `NoteCardColors` + `CompositionLocalProvider`；`TimelineScreens.kt:178-198` |
| P0-4 | 9 个旧哈希图标描边归一 1.75、颜色归一 `#356B53` | ✅ **已修** | `acf3f da483 cdc29 d9f3c 42641 86969 dcd03 d34da 62b4c` |
| — | 回归 | ✅ | 编译通过（0 新增警告）、JVM **365 项 / 0 失败 / 0 错误 / 0 跳过** |

**P0-4 的例外说明**：`5fbe7.svg` 是 64×64 欢迎页插图（非 24dp 语义图标），
1.7 描边在 64dp 下视觉更细，保持原样；全量复核后仅此一个文件非 1.75。

### 未修复（需后续排期）

| # | 项 | 原因 |
|---|---|---|
| P0-2 | 两种页面布局范式（卡片列表 vs 裸 Column） | 涉及 6 个页面的结构重写，改动面大，需单独排期 |
| P1-1 | `JournalField` 包装 48 处 `OutlinedTextField` | 需对齐设计稿 `孕迹/Field`（56dp 高），逐页替换 |
| P1-2 | 167 处 `TextButton` 语义收敛 | 需先定义 4 种语义规格，分批替换 |
| P1-3 | 两处全屏 Dialog 顶栏统一 | 需先抽出 `JournalFullScreenHeader` 组件 |
| P2-1~5 | 按钮高度、基础组件包装、Loading/Error 组件、无障碍标签 | 低优先级，建议随 P1 一起做 |

## 8. 建议修复顺序

| 顺序 | 项 | 理由 | 风险 |
|---|---|---|---|
| 1 | 补 `titleMedium`/`titleSmall` 定义 | 影响 14 个文件的小节标题，改 Theme 一处即可 | 低（仅字号 16→16 不变，但补齐字族/行高） |
| 2 | 修 `Apricot`/`Amber` 深色适配 | 深色模式可见缺陷，4 行代码 | 低 |
| 3 | 新建 `JournalField` 包装 `OutlinedTextField` | 对齐设计稿 `孕迹/Field`，48 处收敛 | 中（需逐页替换） |
| 4 | 两处全屏 Dialog 改用统一顶栏组件 | 消除三种顶栏并存 | 中 |
| 5 | 新建 `JournalTextAction`/`JournalLoading`/`JournalError` | 收敛 167 处 TextButton、22 处 Loading、29 处 Error | 中（改动面大，建议分批） |
| 6 | 统一 `OutlinedButton` 高度 52dp | 消除 40/52/56 三档 | 低 |
| 7 | `TimelineRecordRow` 合并到 `RecordRow` | 消除重复实现与无障碍不同步 | 低 |
| 8 | 修 `JournalApp:120` 触摸目标 | 18dp → 48dp | 低 |

## 9. 未覆盖范围（如实记录）

- **未做真机/模拟器视觉复核**：本轮为静态代码审计 + 对比度实算，未截新图。
  - 尝试设备：小米 11 Ultra 无线调试在审计开始时已断开（`device not found`）。
  - 尝试模拟器：新建 AVD `PJ_DARK_CHECK` 后启动失败——
    `FATAL | Not enough space to create userdata partition. Available: 9198.50 MB, need 12288.00 MB`。
    AVD 已删除，未留残留。
  - 因此 **P0-3（深色模式刺眼）是计算结论，非截图验证**。
    计算依据：`Apricot=#FAEAD8` 对深色背景 `#141D17` 对比度 **14.64:1**；
    且代码层面已确认这两个常量**未出现在任何 `lightColorScheme`/`darkColorScheme` 参数中**，
    也无 `isSystemInDarkTheme()` 分支，故深色下必然保持浅色值。
- **P0-1 已代码级确认**：`Typography()` 显式定义 9 个角色，
  `displayLarge/displayMedium/headlineLarge/headlineSmall/titleMedium/titleSmall` 6 个未定义并回落 M3 默认；
  其中 `titleMedium`/`titleSmall` 确有业务引用（34 / 1 处）。
- **未审计**：`PdfDocumentView` 的 PDF 渲染区（第三方渲染，样式不在 Compose 控制内）、
  `DocumentPreview` 的缩放手势交互细节。
- **Unicode/字体回退**：`FontFamily.Default` 与 `SansSerif` 在 MIUI 上的实际字形差异未实测。
- 首轮记录的两项遗留仍在：底部导航 indicator 视觉权重（主观）、无障碍动作标签未全覆盖。
