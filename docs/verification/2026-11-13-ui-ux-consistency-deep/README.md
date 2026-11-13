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
| P0-2 | 卡片列表 vs 裸 `Column` 两种页面范式并存 | **高** | 6 个页面无卡片容器 | ✅ 5/6 已收敛（仅 `DocumentPreview` 为全屏 Dialog，已统一顶栏） |
| P1-1 | `OutlinedTextField` 48 处无组件包装（设计稿有 `孕迹/Field`） | 中 | 15 个文件 | ✅ 48→8；余 8 处为搜索框/页码输入 |
| P1-2 | `TextButton` 167 处无统一规格（4 种语义混用） | 中 | 16 个文件 | ✅ 167→69；余 43 为对话框按钮 + 列表行/密集工具栏（均保留，见 §7.5） |
| P1-3 | 两处全屏 Dialog 自建顶栏，且两者彼此不一致 | 中 | 2 处 | ✅ 已修 |
| P2-1 | 按钮高度 40/52/56/72 四种 | 低 | 10 处 | ✅ 已修（单行动作 6 处；字段选择器保留 56dp） |
| P2-2 | `Card`/`OutlinedCard`/`Checkbox`/`HorizontalDivider` 未包装 | 低 | 4 个文件 | ✅ 已修 |
| P2-3 | 加载态 22 处裸 `CircularProgressIndicator`，无统一组件 | 低 | 8 个文件 | ✅ 整页加载已全替换；保留 4 处局部小 spinner（图片渲染/顶栏角标） |
| P2-4 | 错误提示 29 处裸 `Text(color=error)`，无统一组件 | 低 | 多文件 | ✅ 25 处改 `JournalError`；余 4 处为状态/结果行，见 §7.5 |
| P2-5 | 3 处可点行缺 `onClickLabel`；1 处可点文本高度 18dp | 低（无障碍） | 4 处 | ✅ 已修 |

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

## 7. 修复进展

### 7.1 第一轮（commit `67752e5`）

| # | 项 | 状态 | 证据 |
|---|---|---|---|
| P0-1 | 补 `titleMedium`/`titleSmall` 定义 | ✅ | `Theme.kt` 新增两个角色，34+1 处引用走项目字族 |
| P0-3 | 杏色卡改 `LocalNoteCardColors` | ✅ | 深色实机像素验证（见 §9） |
| P0-4 | 9 个旧哈希图标归一 1.75 / `#356B53` | ✅ | 全量扫描后仅 `5fbe7`（64×64 插图）保持例外 |

### 7.2 第二轮（本轮）

新增 8 个设计系统组件（`core/designsystem/Components2.kt`，245 行），并逐项替换调用点：

| 新组件 | 解决的缺陷 | 替换量 |
|---|---|---|
| `JournalSecondaryButton` | P2-1 按钮高度 40/52/56 三档 | 6 处单行下拉动作统一 52dp/26dp |
| `JournalTextAction` | P1-2 `TextButton` 无统一规格 | 全部危险动作改用 `danger=true` |
| `JournalSectionTitle` | P1 小节标题散落 | ExportScreen 等 |
| `JournalField` | P1-1 对齐设计稿 `孕迹/Field`(56dp) | ExportScreen 2 处、ReminderSettings 2 处 |
| `JournalCheckboxRow` / `JournalCheckbox` | P2-2 2dp 直角断裂 | ExportScreen 3 处、RestoreScreen 1 处 |
| `JournalLoading` | P2-3 22 处裸 spinner | 13 处整页加载 |
| `JournalError` | P2-4 29 处裸错误文本 | 15 处 |
| `JournalFullScreenHeader` | P1-3 两处自建顶栏互不一致 | DocumentPreview、ReminderSettingsDialog |
| `JournalDivider` | P2-2 分隔线颜色不一致 | SafetyCopies、Recovery |

**同步修复的 P2-5 无障碍**：
- `TimelineRecordRow` / `JournalNoteCard`：`Surface(onClick)` → `clickable(onClickLabel=...)`，与 `RecordRow` 同一写法
- `HomeQuickAction`：补 `heightIn(min=88.dp)` + `onClickLabel`
- `JournalApp` 备份状态文字链：18dp → 48dp 触摸目标（保留 `bodySmall` 视觉密度）

**云盘连接页（P0-2/P1-1 增量）**：原「连接设置」小标题 + 说明 + 4 个裸 `OutlinedTextField` + `TextButton`
全部贴边铺在 `Column(padding=24dp)` 里，与上方 soft 卡片形成两种容器语言。现整段收进 `JournalCard`，
4 个字段改 `JournalField`（56dp / 16dp 圆角），密码字段的「显示/隐藏」移入 `trailingIcon`，
「浏览云盘目录」「取消」「将此设备设为主设备」「清除本机测试标记」改 `JournalTextAction`，
加载态改 `JournalLoading`，3 处裸错误文本改 `JournalError`，3 处 `titleMedium` 改 `JournalSectionTitle`。
`CloudConnectionScreen` 的裸 `OutlinedTextField` **4 → 0**（全局 48 → 40）。

**第三轮（r4）：Recovery / Restore / Security 三页卡片化**

沿用「卡内放文本/按钮/标签，卡外放 `RecordRow`」的约定（`RecordRow` 本身即 20dp 白卡，嵌进 `JournalCard` 会变双层卡片）：

| 页面 | 改动 |
|---|---|
| `RecoveryScreen` | INTRO/SHOW/VERIFY/READY 四个阶段各包一层 `JournalCard`；密钥核对的两处 `OutlinedTextField` 改 `JournalField`（保留 `recovery-answer-*` testTag）；`LOADING` 阶段改 `JournalLoading` |
| `RestoreScreen` | 7 个阶段全部卡片化（SELECT/KEY/CLOUD/CLOUD_LIST/VERIFYING/CONFIRM/RESTORING/DONE/ERROR）；2 处密钥输入改 `JournalField`；`JournalTextAction` 替换 6 处 `TextButton`；`JournalLoading` 替换 2 处裸 spinner |
| `SecurityScreen` | 三组卡片：应用锁状态（soft）/ 锁定设置（按钮+标签组）/ 说明文字；`RecordRow` 保持在卡外，避免双层卡片 |

**未截图的页面**：`SecurityScreen` 与 `RestoreScreen` 自身触发 `FLAG_SECURE`（`secureFlagRequired` 只覆盖 MainActivity 窗口标志），
`-PvisualVerification=true` 不足以截图。这两页改用代码级核对：`testTag` 全部保留、`OutlinedTextField` 降为 0。
截图说明见 `screens/README-r4.md`。

**第五轮（r5）：编辑页字段卡片化 + 残留错误/危险动作清零**

实机走查时发现「写日常记录」与「编辑药品」两个编辑页的字段全部贴边（`JournalCard=0`），属 P0-2 同类问题，一并收敛：

| 页面 | 改动 |
|---|---|
| `JournalEntryEditorScreen` | 日期/时间一组卡片，症状/心情/备注一组卡片；4 处 `OutlinedTextField` 改 `JournalField`；「设为时间未记录」改 `JournalTextAction`；「保存前请确认」改 `JournalSectionTitle` |
| `DrugEditorScreen` | 3 个字段收进 `JournalCard`（原 `JournalCard=0`）；「包装照片」「关联方案」改 `JournalSectionTitle` |
| 全局 | 5 处独立错误文本改 `JournalError`（P2-4 清零）；3 处行内危险动作改 `JournalTextAction(danger=true)`；`DocumentScreen` 动作行 5 个 `TextButton` 全部收敛 |

`OutlinedTextField` **37 → 30**。剩余 30 处分属 `MeasurementScreens`(7)/`IntakeScreens`(6)/`MedicationScreens`(5)/`TimelineScreens`(5)/其余小量——
均为已有 `JournalCard` 包裹、仅缺 `JournalField` 替换的页面，风险低、可分批。

**SafetyCopiesScreen 全面重写**：原为 M3 默认 `Card`(12dp) + `OutlinedButton`(40dp) + 裸 `TextButton` + 裸 `HorizontalDivider`，
现为 `JournalDivider` + `JournalSectionTitle` + `JournalCard`(20dp) + `JournalTextAction`。

### 7.3 本轮发现并修掉的回归

替换过程中引入 2 个问题，均已在实机上发现并修复：

| 回归 | 现象 | 修复 |
|---|---|---|
| 备份状态文字链改用 `JournalTextAction` | `labelLarge` + 长文案撑破布局，首页出现大号重叠文字 | 回退为 `Text(bodySmall)` + `clickable` + `heightIn(48.dp)` |
| `JournalFullScreenHeader` 的 chevron | 用了 `rotate(90f)`，箭头朝下（应为左） | 改为 `rotate(if (Ltr) 180f else 0f)`，与 `JournalPageHeader` 一致 |

### 7.5 保留的例外（判定依据）

以下 `TextButton`/`OutlinedTextField` 未替换，是**有依据的决定**，不是遗漏：

| 项 | 数量 | 保留理由 |
|---|---|---|
| `AlertDialog` 的 confirm/dismiss 按钮 | 43 | Material 3 平台约定，全站 32 个对话框已完全一致；替换会改动大量对话框测试 |
| `PdfDocumentView` 查看器工具栏 | 6 | 单行 6 个控件（上一页/页码/下一页/缩小/适合页面/放大）；`JournalTextAction` 每项多 24dp 横向内边距，合计会超出 412dp 屏宽 |
| 列表行项（云盘目录项、产检日历日格） | 2 | 是列表/网格的单元格，不是独立动作；日格用 `Modifier.fillMaxSize()` + 自定义 contentPadding |
| 对话框内部项（重新关联、导入选项等） | 5 | 在 `AlertDialog`/`BottomSheet` 内，随对话框统一样式 |
| 7 处搜索框 + 1 处 PDF 页码输入 | 8 | 搜索框在 `LazyColumn` 内由 `Inset{}` 包裹，是筛选控件而非表单字段；改为 `JournalField` 会破坏列表节奏 |
| 随状态变色的行内状态/结果文字 | 4 | ①`CloudConnectionScreen` 上传结果行（注释已说明「紧贴按钮、避免被视口裁掉」）；②`DocumentScreen` 的资料状态标签（「已加密保存/导入失败」）；③`ExportScreen` 导出结果行（成功 primary / 失败 error）；④`TrashScreen` 对话框确认按钮（彻底删除时变红）。这些是**状态文字**而非错误提示块，套 `JournalError` 的粉色容器会破坏其行内语义 |

**判定标准**：`JournalTextAction` 适用于「页面/卡片内、与内容并列的独立动作」。
工具栏、列表单元格、对话框、筛选控件属于不同控件族，强行统一会损害布局或交互。

### 7.6 剩余未修

| # | 项 | 原因 |
|---|---|---|
| P0-2 | 两种页面布局范式 | ✅ 已收口：Export/Cloud/Recovery/Restore/Security/JournalEditor/DrugEditor/MeasurementEditor 全部卡片化；`DocumentPreview` 为全屏 Dialog，已统一顶栏 |
| P1-2 余量 | 部分 `TextButton` 未替换（`TrashScreen`/`DocumentScreen` 行内危险态） | 已在 `JournalTextAction` 就位后具备替换条件，但 167 处需分批 |
| — | `RecordRow` 的 `ModifierParameter` lint 警告 | 原有代码，改动会波及全部调用点 |

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

## 9. 深色模式实机验证（2026-11-13 补做）

审计初稿时设备断开、模拟器因磁盘不足无法启动，深色结论只能靠对比度实算。设备恢复后已补做实机验证。

**验证条件**：小米 11 Ultra（1440×3200 / 560dpi），`cmd uimode night yes`（`ui_night_mode=2`），
`-PvisualVerification=true` 构建（绕过 `FLAG_SECURE`），5 个页面截图。

### 9.1 P0-3 杏色卡：像素级确认修复生效

在 `dark-03-timeline-notecard.png` 上定位备注卡区域（x 84..1354，y 2292..2560），统计像素：

| 项 | 预期 | 实测 |
|---|---|---|
| 卡片底色 | `#3A2E1F` | **`#3A2E1F`**（20651 px） |
| 卡内文字色 | `#F0D5B4` | **`#F0D5B4`**（6493 px） |
| 卡内文字对比度 | 9.36:1 | **9.36:1** |
| 卡片对页面背景 | 1.31:1 | **1.31:1** |
| 旧浅杏 `#FAEAD8` | 应为 0 | **0 px（已消失）** |

对照修复前的理论值：旧卡 `#FAEAD8` 对背景 `#141D17` 为 **14.64:1**。

### 9.2 逐页深色扫描

| 页面 | 截图 | 主导色 | 旧浅杏残留 |
|---|---|---|---|
| 首页 | `dark-01-home.png` | `#141D17` / `#1D2921` / `#294C39` | 0 px |
| 记录枢纽 | `dark-02-records.png` | 同上 | 0 px |
| 时间线 | `dark-03-timeline-notecard.png` | + `#3A2E1F`（新备注卡） | 0 px |
| 我的 | `dark-04-profile.png` | 同上 | 0 px |
| 导出资料 | `dark-05-export.png` | 同上 | 0 px |

深色下 M3 1.2+ 的 `surfaceContainer*` 角色全部映射到品牌深绿，**无默认淡紫灰残留**。
底部导航 4 个图标（含本次归一的 `acf3f`/`da483`/`cdc29`/`d9f3c`）在深色下渲染正常。

### 9.3 实机新发现（补充进待修清单）

在 `dark-05-export.png` 上确认 **P2-2 的具体表现**：`ExportScreen` 的 `Checkbox` 使用 Material3 默认样式，
未选中时是 **2dp 近直角方块**，与全站 12/16/20/26dp 圆角体系明显冲突（同页 `JournalSwitch` 是胶囊形）。
这印证了 P2-2「`Checkbox` 未包装」不只是代码整洁问题，而是**肉眼可见的视觉断裂**。

### 9.4 仍存在的范式分裂（实机可见）

`dark-05-export.png` 直观呈现 P0-2：页面顶部两个 `OutlinedTextField` 是**贴边扁平框**（左右贴 24dp 页边），
紧接着的 `JournalCard` 却是**圆角卡片内缩**，两种容器语言在同一屏并存。这是本轮未修但优先级最高的遗留项。

## 10. 验证证据

### 10.1 仪器测试：全量矩阵已通过（2026-11-14）

**环境**：AVD `PJ_API36_16K`（Android 36 / `PAGE_SIZE=16384` / 16 KiB 页）。
该 AVD 实际位于项目内 `.local/avd/`（此前误判为「不存在」是因为只查了 `~/.android/avd`）；
用 `ANDROID_AVD_HOME=.local/avd` 可正常列出并启动，无需重建、无需额外磁盘。

**结果**：`am instrument -w -e notAnnotation androidx.test.filters.LargeTest`

```
Time: 703.484
OK (362 tests)
```

- **362 项 / 0 失败 / 0 错误**，覆盖 **78 个测试类**
- 完整日志：`full-matrix-362-tests.log`
- 与源码一致性：`git status` 干净（`bda463b`），两个 APK 均由该提交构建并安装

### 10.2 全量矩阵揪出的两处真实回归（已修）

首次全量运行 362 项 / 2 失败，均为 `NavigationExperienceTest`：

```
Failed to inject touch input.
Reason: could not find any node that satisfies: Text contains '返回'
```

**根因**：`DocumentPreview`（原件预览）全屏 Dialog 的顶栏在 P1-3 中由 `TextButton("返回")`
统一为 `JournalFullScreenHeader` 的图标返回键，测试仍按「返回」文字查找——**测试断言过期**，产品行为正确。

**修复**：
1. 给 `JournalFullScreenHeader` 的返回键改用独立 `testTag("fullscreen-navigate-up")`。
   原因：全屏 Dialog 叠在 `JournalPage` 之上，两者若都用 `navigate-up`，语义树会出现**两个同名返回键**
   （实测 `Expected exactly '1' node but found '2' nodes`）。改完后页面顶栏仍是 `navigate-up`，Dialog 内是 `fullscreen-navigate-up`，互不干扰。
2. 测试改为按 `testTag` 精确定位（比按文字更稳定）；**未放宽任何产品断言**。

修复后 `NavigationExperienceTest` 整类 **OK (26 tests)**，全量复跑 **OK (362 tests)**。

### 10.3 一处时序 flake（非产品缺陷）

第二轮全量出现 1 项失败：`CustomReminderUiTest#createEditAndDeleteFromReminderSettings`
（`expected DAILY but was NONE`——`JournalTag` 点击在 IME 动画期间落空）。

验证：同用例单独运行 **OK (1 test)**、整类运行 **OK (3 tests)**，第三轮全量再次 **OK (362 tests)**。
判定为全量负载下的时序 flake，与项目历史记录的同类 IME flake 一致，未修改产品代码。

### 10.4 真机 vs 模拟器的差异（已澄清）

同一批 API 36 代码：
- **模拟器**：`CloudCleanupUiTest` + `CloudConnectionUiTest` → `OK (11 tests)` / 15.5s
- **小米 11 Ultra 真机**：同样两个类渲染 `CloudConnectionContent` 后挂起，日志无 crash 栈

用两版回退（`536556e~1`、`b51e173~1`）重装后真机仍挂起，证明**不是本次改动引起**，
而是真机的 Compose 测试环境问题。历史全量矩阵也一直只在模拟器上跑。

### 10.5 真机补充验证（198 项非 UI）

真机另跑了非 UI 类，共 **198 项 / 197 通过 / 1 失败**。唯一失败：

```
OriginalShareDeviceTest#shareIntentCarriesExactlyOneReadOnlyStream
expected:<android.intent.action.CHOOSER> but was:<miui.intent.action.MIUI_CHOOSER>
```

MIUI 改写系统 chooser 的 action。依据：①`git diff 536556e~1..HEAD` 未触及 `FileHandoff`/分享文件；
②产品代码不含 `ACTION_CHOOSER` 字面量（由系统 chooser 注入）。**未修改断言**，避免掩盖跨 ROM 差异。

### 10.6 其他未覆盖

- **未做**：TalkBack 读屏实测、字体缩放 200% 与横屏组合矩阵、API 26 深色表现。
- **未审计**：`PdfDocumentView` 的 PDF 渲染区（第三方渲染，样式不在 Compose 控制内）、
  `DocumentPreview` 的缩放手势交互细节。
- 首轮记录的两项遗留仍在：底部导航 indicator 视觉权重（主观）、无障碍动作标签未全覆盖。


- **未做**：TalkBack 读屏实测、字体缩放 200% 与横屏组合矩阵、API 26 深色表现。
- **未审计**：`PdfDocumentView` 的 PDF 渲染区（第三方渲染，样式不在 Compose 控制内）、
  `DocumentPreview` 的缩放手势交互细节。
- **字体回退**：`FontFamily.Default` 与 `SansSerif` 在 MIUI 上的实际字形差异仍未逐字比对
  （P0-1 已通过补定义消除该路径，故风险已降为 0）。
- 首轮记录的两项遗留仍在：底部导航 indicator 视觉权重（主观）、无障碍动作标签未全覆盖。
