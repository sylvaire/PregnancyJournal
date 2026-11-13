# 孕迹 Figma 设计令牌（2026-10-24 提取）

- 来源文件：[孕迹 · V1.0 Android UI](https://www.figma.com/design/qwUxmzTnSJtzppU5eZwHcy)（fileKey `qwUxmzTnSJtzppU5eZwHcy`，页面 `0:1` / 「01 · 孕迹 V1.0」）。
- 提取方式：`mcp__figma__get_metadata`（结构）、`mcp__figma__get_variable_defs`（实时变量/样式，采样节点 `12:138` Today）、`mcp__figma__get_libraries`（库）、`mcp__figma__download_assets`（导出图）。
- 交叉证据：`docs/design/figma-foundations.js` / `figma-foundations-result.json`（2026-10-17 创建记录）、`docs/design/figma-detail-polish.js`（2026-10-18 精修记录）、`docs/design/UI细节检查-2026-10-17.md`、本目录导出图像的**像素取色**与**几何测量**（`figma/_tools/color-evidence.json`、`figma/_tools/geometry-evidence.json`）。
- 只读：本任务未修改 Figma 画布。
- 取数限制（如实记录）：
  - `mcp__figma__get_variable_defs` **只成功采样 1 个代表节点**（`12:138` Today）；其余节点在采样过程中触发 Figma MCP Starter 计划调用上限（见 `manifest.json` 的 `exportSummary.rateLimit`），**未能逐个屏幕复核**。
  - `mcp__figma__search_design_system` **未执行成功**（同因限流），本地/团队库组件与变量未通过该接口核对。
  - 因此：下表中**「实时」列**表示「2026-10-24 由 MCP 实际返回」；**「记录」列**表示「来自仓库内 Figma 构建记录 + 本次导出图像素/几何测量交叉验证」。两者冲突时以实时值为准，并在备注中标出。

---

## 1. 颜色变量（颜色集合：`孕迹 · Light`，单一模式 `Value`）

实时采样节点：`12:138`（03 Today）。`get_variable_defs` 实际返回 7 个颜色变量 + 7 个间距 + 3 个圆角 + 5 个文字样式；其余变量来自创建记录与像素取色。

| 变量名 | 值 | 用途（按命名/画面实际使用） | 实时（2026-10-24） | 像素取色（19 张导出图中出现该精确色值的屏数） |
|---|---|---|---|---|
| `color/bg` | `#F7F8F3` | 应用暖白背景 | ✅ 返回 | 19/19 |
| `color/surface` | `#FFFFFF` | 卡片/记录行表面 | ✅ 返回 | 18/19（06 VisitEditor 仅 91823 px 边缘） |
| `color/ink` | `#223C32` | 正文/标题深墨绿 | ✅ 返回 | 19/19 |
| `color/muted` | `#5F7166` | 次级文字（2026-10-18 由 `#65766D` 调整，对比度 4.51→4.87） | ✅ 返回 | 19/19 |
| `color/primary` | `#356B53` | 主色/主按钮/选中态 | ✅ 返回 | 19/19 |
| `color/soft` | `#E7F0E6` | 浅绿容器（图标底、摘要卡） | ✅ 返回 | 19/19 |
| `color/line` | `#DDE4DA` | 分割线/描边 | ✅ 返回 | 8/19 |
| `color/apricot` | `#FAEAD8` | 暖杏色强调（时间线备注卡等） | ⛔ 未返回（未在 12:138 使用） | 3/19（12 Timeline / 15 / 16） |
| `color/amber` | `#80582C` | 暖杏色的深色文字 | ⛔ 未返回 | 3/19（12 / 15 / 16） |
| `color/danger` | `#A44238` | 危险/删除 | ⛔ 未返回 | **0/19**（仅出现在「设计基础」色板 7:55，20 屏未使用） |
| `color/dangerSoft` | `#FCECE7` | 危险浅底 | ⛔ 未返回 | **0/19**（仅色板定义） |

- 结构：存在 `primitive/{bg,surface,ink,muted,primary,soft,line,apricot,amber,danger,dangerSoft}` 原始色，`color/*` 为指向 primitive 的别名（见 `docs/design/figma-foundations.js` L149–L153）。
- **判定**：`danger` / `dangerSoft` 是本设计文件的**已定义但未被 20 屏使用**的令牌；实现侧的删除/错误态（如回收站、校验错误）不要直接假设设计稿给过样式——设计稿未覆盖。
- 说明：`color/muted` 实时值为 `#5F7166`，与创建记录里的 `#65766D` 不同，属 2026-10-18 精修（见 `docs/design/UI细节检查-2026-10-17.md` L35），**以 `#5F7166` 为准**。

---

## 2. 文字样式（字体：Noto Sans SC）

字体族在创建时加载了 `Regular / Medium / Bold` 三个字重（`figma-foundations.js` L147）。

| 样式名 | 字号 | 行高 | 字重 | 来源与核对 |
|---|---|---|---|---|
| `孕迹/display` | 40 | 60 | Bold (700) | 实时返回 ✅；导出图 01/03 字形高 38dp ≈ 0.95×字号 |
| `孕迹/title` | 28 | 42 | Bold (700) | 实时返回 ✅；App bar 标题字形高 26dp ≈ 0.93×字号 |
| `孕迹/heading` | 20 | 30 | Medium (500) | 创建记录（`figma-foundations.js` L157：lineHeight=round(size×1.5)=30）；**实时未返回**（采样屏未用该样式）。画面证据：各屏 `Section title` 节点高 30（`get_metadata`） |
| `孕迹/body` | 16 | 24 | Regular (400) | 实时返回 ✅ |
| `孕迹/label` | 14 | 21 | Medium (500) | 实时返回 ✅（标签/筛选/按钮短文案常用） |
| `孕迹/caption` | 12 | 18 | Regular (400) | 实时返回 ✅ |
| `孕迹/button` | 待核 | 待核 | Medium | 阶段记录（S01 2026-10-18）称「新增 button 文字样式，当前共 7 个共享样式」；但本仓库 Figma 构建记录/审计快照只列出 6 个 `孕迹/*` 样式，且本次实时样式清单未取到 → **参数未取到**。替代证据：导出图按钮文字字形高 14.5dp、按 title/display 校准比例 ≈0.93 → **字号约 16sp**（估算，非 Figma 实时值） |

行高规则：除 heading 外均为 `round(字号×1.5)` 像素行高；heading 同样是 20×1.5=30。

---

## 3. 圆角

| 令牌 | 值 | 用途 | 核对 |
|---|---|---|---|
| `radius/12` | 12 | 标签/筛选胶囊、图标容器 | 实时 ✅；11 Health 选中标签像素测量：48 高、圆角 ≈11–12 |
| `radius/20` | 20 | 记录行/卡片 | 实时 ✅；记录行 72 高，x=30dp 处白底竖向可见高度 60dp，与 r=20 圆角几何吻合 |
| `radius/28` | 28 | 大卡片/按钮 | 实时 ✅ |
| 按钮渲染圆角 | **26**（视觉胶囊） | 364×52 按钮 | 按钮母版应用 `radius/28`（`figma-detail-polish.js` L55），但 52 高时 Figma 将圆角夹取到 26，因此**渲染为胶囊**；导出图像素测量顶部内缩≈25–26dp、整体 52 高 ✅ |

---

## 4. 间距

| 令牌 | 值 | 典型用途 |
|---|---|---|
| `space/4` | 4 | 文本内小间隙 |
| `space/8` | 8 | 卡内元素、标签组 gap |
| `space/12` | 12 | 记录行内 gap、列表项间距 |
| `space/16` | 16 | 卡片内边距、记录行上下内边距（12）、左右 16 |
| `space/20` | 20 | 卡片内边距（摘要卡/说明卡 20） |
| `space/24` | 24 | 页面左右边距（各屏 Content 内元素 x=24）、分组间距 |
| `space/32` | 32 | 大分组间距（创建记录有定义；本次实时未返回该令牌） |

画面级实测（`get_metadata`）：手机画板 412×915；内容左右边距 24；卡片内边距 20；记录行 364×72、图标容器 44×44、正文区约 244、箭头 20。

---

## 5. 组件

组件集合节点 `7:2461`「孕迹 · 基础组件」下的本地母版（实例遍布 20 屏）。

### 5.1 按钮 `孕迹/Button`（组件集 `7:2484`）
- 变体：`Style=Primary`（`7:2462`）、`Style=Secondary`（`7:2470`）、`Style=Disabled`（`7:2477`）。
- 尺寸：**364×52**（全部 29 个按钮实例统一；`UI细节检查-2026-10-17.md` L28 + `figma-detail-polish.js` L54–L57）。
- 圆角：令牌 `radius/28`，52 高夹取为 **26（胶囊）**。
- 配色：Primary = `color/primary` 底 + `color/surface` 文字；Secondary = `color/soft` 底 + `color/primary` 文字；Disabled = `color/line` 底 + `color/muted` 文字。
- 文字：Noto Sans SC Medium（详见 §2 的 `孕迹/button` 待核项）；有文本属性 `Label#23:0`（`figma-state.json`）。
- 来源：Material 3 按钮封装后改为本地母版以修复内层收缩问题（S01 / `figma-foundations.js` L176–L180、`figma-detail-buttons.js`）。

### 5.2 输入框 `孕迹/Field`（`7:2485`）
- 规格：母版 348×56（页面实例统一 364×56）；Material 3 outlined 文本域封装。
- 内容：中文浮动标签、输入值、可选辅助文字；文案示例见 02/06/10/19 屏（如「档案名称」「预约日期 *」「WebDAV 服务地址」）。
- 说明：保留 Material 3 封装，未改内部结构（`docs/design/README.md` L12）。

### 5.3 记录行 `孕迹/RecordRow`（`7:2496`）
- 尺寸：**364×72**（2026-10-18 由 84 改为 72）；圆角 20；底色 `color/surface`。
- 内部：前导图标容器 **44×44**（圆角 12、底色 `color/soft`、内边距 10、图标 24×24）；正文区约 244；尾部箭头 20。
- 排布：左右内边距 16、上下 12、内部 gap 12、垂直居中（`figma-detail-polish.js` L35、L40–L52）。
- 可替换属性：`Leading icon#22:0`、`Show chevron#22:1`（`figma-state.json`）。
- 35 条记录行实例统一规格（`UI细节检查-2026-10-17.md` L30）。

### 5.4 图标
- 10 个 24×24 矢量图标母组件（`22:576`–`22:603`）：check / plus / file / clock / calendar / shield / pill / scale / restore / chevron；1.75 描边。
- 记录行图标容器 44×44、圆角 12。

### 5.5 页面骨架（20 屏统一）
| 区域 | 尺寸 | 说明 |
|---|---|---|
| 状态栏 `Android status bar` | 412×34 | 统一矢量信号/Wi-Fi/电量，时间「9:41」 |
| App bar | 412×96 | 标题用 `孕迹/title`（28/42）；副标题 `caption` 12/18；返回提示写在副标题（如「返回备份 · 第 3 步，共 4 步」） |
| Content | 412×671（有底部导航）或 412×765（无底部导航） | 左右边距 24 |
| Bottom navigation | 412×94 | 4 个一级入口：今天/产检/记录/我的；选中项 40×40 指示器 + primary 色 |
| Gesture area | 412×20 | 底部手势条 108×4 |
| 手机画板 | **412×915** | 全部 20 屏统一；导出 2x = 824×1830 |

### 5.6 交互标签/筛选（tabs、filters、record sections）
- 高 48、最小宽 64、圆角 12、组内 gap 8；
- 选中：`color/primary` 底 + `color/surface` 文字；未选：`color/surface` 底 + `color/line` 1px 描边 + `color/muted` 文字（`figma-detail-polish.js` L61、`UI细节检查-2026-10-17.md` L29）。

### 5.7 状态徽章（Badge）
- 例：`孕中期`、`待进行`、`4 天后`；`color/soft` 底 + 高 34；与可交互标签形状区分（`UI细节检查-2026-10-17.md` L29）。
- 资料详情键值行：标签列 96 + 间隔 16 + 值列 212（`UI细节检查-2026-10-17.md` L31）。

---

## 6. 与实现对照时可用的硬指标

1. 画板基准 **412×915 dp**；设计稿为 Android 手机竖屏，**无深色模式、无平板/横屏画板**（S01 未覆盖项）。
2. 页面左右边距 **24**；按钮 **364×52** 胶囊；记录行 **364×72**；标签 **48 高**。
3. 主色 `#356B53`、背景 `#F7F8F3`、卡片 `#FFFFFF`、正文 `#223C32`、次级 `#5F7166`、浅绿 `#E7F0E6`、分割线 `#DDE4DA`。
4. 字体 Noto Sans SC；正文 16/24、标签 14/21、说明 12/18、标题 28/42、大标题 40/60。
5. 设计稿中的**示例日期固定为 2026-10-17**、示例数据为虚构演示，不能当作真实数据语义（`UI细节检查-2026-10-17.md` L62；S01）。

---

## 7. 未取到 / 后续补齐

| 项 | 状态 | 补齐方式 |
|---|---|---|
| 全部变量逐条实时值（尤其 `apricot`/`amber`/`danger`/`dangerSoft`、`space/32`） | 仅 1 节点实时返回；其余为创建记录+像素证据 | 额度恢复后对 12:26、14:738、16:472 等节点再跑 `get_variable_defs` |
| `孕迹/button` 文字样式参数 | 未取到（见 §2） | `get_variable_defs` 采样含按钮的节点（如 `12:26` 的 `12:55` Button 实例） |
| 本地共享样式/库资产清单 | `search_design_system` 未执行成功；`get_libraries` 已取到 8 个已订阅社区库（Material 3 Design Kit 等，**无团队库**） | 额度恢复后补 `search_design_system` |
| 组件内部 fills/字体细节 | `get_metadata` 只返回结构（无 fills） | 对 `7:2462/7:2485/7:2496` 跑 `get_design_context`（仅当需要生成代码时） |
