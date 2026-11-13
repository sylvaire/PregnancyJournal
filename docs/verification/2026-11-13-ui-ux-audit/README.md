# UI/UX 一致性审计（2026-11-13）

审计范围：整体视觉风格统一性（按钮、开关等组件）、布局合理性、人机交互合理性、过渡动画自然度。

方法：读取设计系统与主题定义 → 统计业务代码绕过组件的情况 → 模拟器实景截图核对 → 修复高优先级项 → 全量回归。

环境：AVD `PJ_API36_16K`（Android 16 / 16 KiB 页 / 1080×2400 / 420dpi / 字体 1.0 / 浅色），
用 `-PvisualVerification=true` 构建以绕过 `FLAG_SECURE` 截图。

## 1. 结论摘要

**基础质量良好**：色板、字号、圆角、间距高度统一，无硬编码颜色，主按钮全部走设计系统，
触摸目标全部达标。发现的问题是**局部不一致**与**动效缺失**，已修复 3 项、记录 3 项待决策。

| 维度 | 评价 | 说明 |
|---|---|---|
| 色板 | 优秀 | 14 个语义色集中定义，业务代码 **0 处硬编码颜色** |
| 字体 | 优秀 | 8 级字阶集中定义（displaySmall→labelSmall） |
| 主按钮 | 优秀 | `JournalButton`/`JournalDangerButton` 全站唯一，**0 处裸 `Button`** |
| 开关 | 优秀 | `JournalSwitch` 6 处替换，显式定义四态配色（含禁用态） |
| 标签 | 优秀 | `JournalTag` 替换全部 `FilterChip`，48dp 高、selectable 语义 |
| 卡片 | 良好 | `JournalCard` 统一 20dp 圆角 + 20/16dp 内边距 |
| 圆角 | 修复后统一 | 原有 3 处 4dp 尖角，已统一为 16dp |
| Snackbar | 修复后统一 | 原为直角矩形，已改 16dp 圆角 |
| 动效 | 修复后改善 | 原有仅页面级转场；已为卡片加 `animateContentSize` |
| 无障碍 | 部分修复 | `RecordRow` 补 `onClickLabel`；装饰图标不重复播报 |
| 图标语义 | 已修复 | 新增 20 个语义化图标，27 处引用替换，无缺失引用 |
| 图标风格 | 已统一 | Ardot 26 个组件与本地 20 个 SVG 描边统一 1.75，像素级核验通过 |

## 2. 已修复项

### 2.1 Snackbar 直角与全站圆角冲突

- 位置：`feature/JournalNavigation.kt`
- 现象：首页保存后提示「已保存到本机」为**直角矩形**，与全站 20dp 圆角卡片明显冲突（见 `screens/03-home.png` 底部）。
- 修复：`SnackbarHost` 自定义 `Snackbar(shape = RoundedCornerShape(16.dp))`，显式使用 `inverseSurface`/`inverseOnSurface` 保证深色模式跟随主题。

### 2.2 表单类按钮圆角不统一（4dp vs 全站 20/26dp）

- 位置：`JournalApp.kt:208`（建档日期）、`PrenatalScreens.kt:115`（预约日期）、`MeasurementScreens.kt:162`（指标选择）
- 现象：三处 `OutlinedButton` 使用 `RoundedCornerShape(4.dp)`，是**孤立的尖角样式**，在其余全部 12/16/20/26dp 圆角中显得突兀。
- 修复：统一为 `RoundedCornerShape(16.dp)`（与 Snackbar 一致，属"输入类"控件）。
- 证据：`screens/02-create-form-before.png`（尖角）vs `screens/02-create-form-after.png`（圆角）。

### 2.3 缺少组件级过渡动画

- 位置：`core/designsystem/Components.kt` 的 `JournalCard`
- 现象：全仓动画**仅有页面级转场**（`tween(320)` 滑入 + 根页 `fadeIn(160)`/`fadeOut(120)`），
  **零组件级动画**。卡片内容变化（展开"操作"、显示导入进度、切换状态）时高度**瞬间跳变**。
- 修复：`JournalCard` 的 `Column` 加 `animateContentSize(tween(180))`。
  选 180ms 是因为短于此在展开长内容时仍显跳，长于此会与 320ms 页面转场叠加产生拖沓感。

## 3. 图标语义修复（本轮完成）

### 3.1 问题与根因

**根因**：`assets/design/` 下只有 **14 个 SVG**，而 `RecordRow` 被用于几十个不同功能，图标被迫复用。

实测错配示例（`screens/04-profile.png`、`screens/05-reminder-settings.png`）：

| 功能 | 实际图标 | 应为 |
|---|---|---|
| 全局回收站 | 禁止符号 ⊘ | 垃圾桶 |
| 提醒设置 | 叶片 | 铃铛 |
| 安全设置 | 叶片 | 盾牌/锁 |
| 云盘连接 | 图表+WiFi | 云 |
| 通知权限 | 叶片 | 铃铛 |
| 产检提醒 | 人像 | 医院/日历 |
| 用药提醒 | 日历 | 药丸 |
| 自定义提醒 | 对勾 | 铃铛+加号 |
| 调度检查 | 禁止符号 ⊘ | 时钟 |

用户看到「回收站」配禁止符号、「安全设置」配叶片，需要读文字才能分辨——这是**可识别的可用性缺陷**。

**已修复**：新增 20 个语义化图标（`app/src/main/assets/design/ic-*.svg`），
沿用现有图标的完全一致规格（24×24、stroke 1.7–1.75、round cap/join、`#356B53`），
并把全站 **27 处**图标引用替换为语义正确的版本。

| 新增图标 | 语义 |
|---|---|
| `ic-trash` / `ic-bell` / `ic-bell-plus` | 回收站 / 提醒 / 新建提醒 |
| `ic-shield` / `ic-lock` / `ic-refresh` | 安全 / 锁定 / 重试 |
| `ic-cloud` / `ic-backup` / `ic-clock` | 云盘 / 备份 / 调度 |
| `ic-pill` / `ic-calendar-check` | 药品 / 产检 |
| `ic-file-text` / `ic-image` / `ic-notebook` | 文档 / 图片 / 日常记录 |
| `ic-activity` / `ic-history` / `ic-edit` | 健康指标 / 历史 / 编辑 |
| `ic-check` / `ic-info` / `ic-user` | 确认 / 提示 / 用户 |

图标规范与替换清单见 [语义图标规范](../../design/语义图标规范.md)。
图标总览：`screens/08-icon-set.png`；三处形状微调：`screens/09-icon-fixes.png`。

**Ardot 状态**：配置已写入 `~/.zcode/cli/config.json`（`ardot-remote`），
OAuth 元数据可读、动态注册成功，但授权页返回 `400 加载授权信息失败`——需要重启 ZCode
在「设置 → MCP」内完成客户端侧授权。届时可用设计稿同名覆盖 SVG，代码无需改动。

### 3.3 Ardot 设计稿同步（第二轮完成）

Ardot MCP 恢复可用后，已在设计稿内补齐缺失图标并完成风格核验。

- 图标帧 `22:575`「孕迹 · 精修图标」由 **10 个** 扩到 **26 个** COMPONENT
- 新增 16 个：trash / bell / bell-plus / lock / cloud / calendar-check / activity /
  notebook / backup / history / refresh / edit / info / user / pill / image
- 结构统一为 `COMPONENT(24×24) → FRAME("Frame") → VECTOR`，
  VECTOR 为 `strokeWeight=1.75`、`ROUND/ROUND`、`CENTER`，描边绑定变量 `$7:7`（`primitive/primary` = `#356B53`）
- 修正两处历史命名错误：`pill`（实为禁止符）→ `prohibit`，`scale`（实为图片）→ `image-legacy`
- **修正本地侧不一致**：首轮 `ic-*.svg` 混用 1.5/1.6/1.7/1.75，已全部归一到设计稿的 **1.75**

**风格一致性核验**（27 个图标导出 10× PNG 做像素测量）：

| 指标 | 原有 10 个 | 新增 17 个 | 结论 |
|---|---|---|---|
| 墨迹占比 | 6.5–27.0%（均值 17.2） | 11.6–28.3%（均值 19.1） | 同一量级 |
| x / y 包围盒 | 2.1–21.9 | 2.1–21.9 | 一致 |
| 主色 | `#356B53` | `#356B53` | 一致 |
| 描边 | 1.75 ROUND/ROUND | 1.75 ROUND/ROUND | 一致 |

真机复核（小米 11 Ultra，1440×3200 / 560dpi）：
`screens/10-profile-icons-ardot-synced.png`、`11-profile-icons-scrolled.png`、
`12-reminder-icons-ardot-synced.png` 确认 9 个「我的」行图标与 5 个「提醒设置」行图标语义正确、线宽统一。

详见 [语义图标规范](../../design/语义图标规范.md) 与 [ardot-state.json](../../design/ardot-state.json)。

### 3.2 无障碍语义（已部分修复）

- `contentDescription` 仅 5 处、`onClickLabel` 仅 1 处，而交互点（`RecordRow`、`JournalTag`、列表行）数量远多于此。
- `RecordRow` 的可点击 `Surface` 不声明动作语义，TalkBack 会读作「按钮」但不说明会打开什么。
**已修复 `RecordRow`**：改用 `Modifier.clickable(onClickLabel = title, role = Role.Button)`，
读屏现在会说出「<标题>，按钮」，说明点击后会进入该功能。
早先尝试的 `semantics { onClick(label = title, action = null) }` 会**覆盖原点击行为**，已在编译前回退。

`JournalTag` 已通过 `selectable(role = Role.Tab)` 提供选中状态，未额外加动作标签（避免重复播报）。
`DesignIcon` 作为 `RecordRow` 内的装饰图标保持无 `contentDescription`，
因为标题文字已表达语义，重复描述会让 TalkBack 念两遍。

### 3.3 底部导航选中态视觉权重

- 选中项有 `primaryContainer` 圆角背景块，视觉上是全站最重的强调块之一。
- 与内容区大量「软绿卡片」叠加时，层级略显抢眼。
- 属于**主观审美**，未改动。若希望更克制，可把 indicator 改为更浅的色阶或仅保留图标着色。

## 4. 已确认合规的项

| 检查 | 结果 |
|---|---|
| 硬编码颜色 | **0 处**（业务代码全部使用 `MaterialTheme.colorScheme`） |
| 裸 `Button(` | **0 处**（全部 `JournalButton`/`JournalDangerButton`） |
| 裸 `Switch(` / `FilterChip(` | **0 处**（全部 `JournalSwitch`/`JournalTag`） |
| `TopAppBar(` / `FloatingActionButton(` | **0 处**（统一用 `JournalPageHeader`） |
| 触摸目标 | `JournalTag` 48dp 高/64dp 宽、`RecordRow` 72dp 行高、底栏 M3 标准 80dp，**无 <48dp 可点元素** |
| 深色模式 | 完整 `darkColorScheme`，含 M3 1.2+ 全部 `surfaceContainer*` 角色，无默认淡紫灰残留 |
| 字体缩放 | 全部使用 `MaterialTheme.typography`，无硬编码 `fontSize`（除 Theme 定义） |
| 页面转场 | 根页 `fadeIn(160)`/`fadeOut(120)`，下级页 `slideIn(320, FastOutSlowInEasing)`，back 与 predictive-back 方向相反，时长统一 |

## 5. 回归验证

| 检查 | 结果 |
|---|---|
| JVM 单元测试 | **365 项 / 0 失败** |
| 仪器全量矩阵（修复后） | **`OK (382 tests)` / 0 失败 / 0 跳过** |
| 视觉复核 | `screens/02-create-form-before.png` vs `after.png` 确认圆角生效 |

`animateContentSize` 未破坏任何既有断言（矩阵全绿），说明 180ms 的过渡在测试的 `settle()` 窗口内可完成。

## 6. 建议后续动作

1. **补齐语义化图标**（优先，影响可用性）：建立语义图标映射，从设计侧补 10–15 个 SVG。
2. 统一无障碍动作标签：`RecordRow`/`JournalTag`/列表行补 `onClickLabel`。
3. 为空态、加载态加淡入过渡（`AnimatedVisibility`），目前是瞬间出现。
4. 评估底部导航 indicator 的视觉权重。
