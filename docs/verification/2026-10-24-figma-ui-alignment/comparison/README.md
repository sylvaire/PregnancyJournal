# 孕迹 UI 视觉对齐评审（Figma 设计 ↔ 实现截图，2026-10-24）

- 对应任务：M6「UI 与 Figma 对齐 / Figma 未设计界面风格一致性」（共享任务 `task-4`，负责人 `ui-compare`）。
- 评审对象（均为只读素材）：
  - 设计侧：`../figma/screens/`（19 张，824×1830 px = 412×915 dp @2x）、`../figma/design-tokens.md`、`../figma/README.md`。
  - 实现侧：`../app-screens/`（**实际 22 张** PNG，1080×2400 px，API 36 模拟器 `sdk_gphone16k_x86_64`、420 dpi = 2.625x ⇒ 411.4×914.3 dp）。
  - 交叉核对：`docs/verification/2026-10-22-figma-sync/screens/`（9 张 **2026-10-22 历史实现截图**，非本轮，仅作参考判定）；`app/src/main/java/cn/yunji/journal/**` 与 `core/designsystem/**`（只读代码，仅用于解释实现，不作为视觉判定依据）。
- 产物：本文件、`findings.csv`（供 Lead 回填 Bug 清单）、`measurements.md`（像素/几何测量复现）。
- 边界：本评审**不操作设备/模拟器、不构建、不改 app 代码或主文档、不动 `figma/` 与 `app-screens/` 原文件**。

---

## 0. 结论摘要

| 维度 | 结论 |
|---|---|
| 设计屏可判定性 | 19 张设计屏中**仅 5 张**有本轮实现截图可做视觉比对（05/06/12/19 + 02 编辑态部分对照），第 20 屏（RecoveryKey）**无 PNG**，只能做结构级对照（1 项）；**其余 14 张设计屏（01/03/04/07/08/09/10/11/13/14/15/16/17/18）本轮没有对应实现截图 → 无法判定**（见 §4.1 覆盖缺口）。 |
| 逐屏对齐（可比 6 项） | **一致 0 / 部分一致 5 / 不一致 1**（12 Timeline）。 |
| 最严重问题 | ① 列表筛选标签体系与设计整体不符（设计 48 dp、选中主色填充；实现 M3 FilterChip 32 dp、选中浅绿底）——影响产检/时间线/回收站/服药/健康/资料等全部列表页；② M3 默认容器色未纳入主题（对话框底色 `#ECE6F0`、Switch 未选中轨道 `#E6E0E9` 等淡紫灰）脱离暖白绿色板，全 app 20+ 对话框通用；③ 12 Timeline 整体布局偏离设计（大卡片信息流 vs 72 dp 记录行 + 杏色备注卡，CTA/筛选/孕周信息全不同）。 |
| 未设计界面（16 项） | 10 项有截图可评：**0 项完全统一，10 项有偏差**（明显 3：回收站空态、导出资料、自定义提醒弹窗；轻微 7），6 项**无法评估**（缺截图）。 |
| 部分覆盖（10 项） | 3 项可评（Timeline 不一致；CloudConnection、Recovery 部分一致/有偏差），5 项仅有历史参考，2 项无法评估。 |
| 一句话风格结论 | **色板、字号层级、圆角、卡片、JournalButton 主/次按钮与设计令牌高度一致；偏差集中在①页面头部层级 ②筛选标签 ③页边距三套 ④M3 默认容器色 ⑤新页面改用原生 M3 控件与 40 dp 按钮并丢弃卡片/记录行范式 → 「风格基本统一，组件与间距不统一」。** |

> 术语：**有图对比 / 结构级对照 / 仅设计 / 仅实现 / 无法评估 / 参考判定（历史截图）**。

---

## 1. 方法与限制

### 1.1 方法

1. **逐张看图**：用 `read_image` 逐张查看 19 张设计屏 + 22 张实现截图（不臆测；看不清处标注“无法判定”）。
2. **像素级校验**：用 Pillow 对实现截图做色值统计与连通域/行扫描测量（背景/卡片/主色/浅绿/线色/危险色、组件高度、页边距、弹窗底色），与 `design-tokens.md`、`figma/_tools/color-evidence.json`、`figma/_tools/geometry-evidence.json` 对照（数据见 `measurements.md`）。
3. **代码只作解释**：阅读 `core/designsystem/Theme.kt`、`Components.kt`、`JournalNavigation.kt` 及各 feature 页面，说明“差异从哪来”（组件选型/主题色板/页边距），**不以代码推断代替截图判定**；代码线索单独标注。
4. **历史截图交叉核对**：`docs/verification/2026-10-22-figma-sync/screens/` 的 9 张 2026-10-22 实现截图用于补足本轮缺口的“参考判定”，与 2026-10-24 当前代码/截图交叉验证（多处 UI 代码未变，可作参考，**不计入本轮判定**）。

### 1.2 限制（结论必须在此边界内读）

| 限制 | 影响 |
|---|---|
| 真机 `MainActivity` 设 `FLAG_SECURE`（MainActivity.kt:85），小米 11 Ultra 无法截取应用内容 | 视觉比对只能用 API 36 **模拟器 Compose `captureToImage()`**；模拟器渲染 ≠ 真机渲染（字体栅格、厂商字体、布局小数像素、系统栏/手势条处理可能不同）。真机结论只能到“结构/文案/行为”级，不能宣称真机像素一致。 |
| 实现截图由测试宿主渲染：`ui.setContent { PregnancyTheme { JournalPage(...) { ... } } }` | ① 截图是 **Compose 内容区**（不含系统状态栏、手势条；对话框截图例外，是整屏 UiAutomation 抓取并带 dim 遮罩）；设计屏含 34 dp 状态栏 + 20 dp 手势区 + 94 dp 底部导航 ⇒ **不能按绝对 y 坐标对比**，只能比对相对布局/间距/组件；② 截图里的“页面骨架”（`JournalPage`：M3 TopAppBar + 返回箭头）与本轮**全部为二级子页**一致，但**首页/一级页（今天/产检/记录/我的）没有本轮截图**，无法核对 PageHeader + 底部导航；③ 测试数据为夹具（日期 2026-10-23/11-02、`Asia/Shanghai`/`GMT` 等），**设计稿示例数据不算差异**。 |
| 基线单一 | 仅 `font_scale=1.0`、浅色、竖屏、412×915 dp；设计稿也无深色/平板/横屏/200% 字体画板 ⇒ 这些维度**本轮一律无法评估**。 |
| 无动效 | 不含转场/滚动/按压态/交互手感；也不含“点击后状态”。 |
| 素材账实差异 | 任务书写“23 张实现截图”，实际目录 **22 张 PNG**；`app-screens/manifest.md` **已于 02:40 由实现侧生成**（22 屏清单；本评审初稿基于文件名+代码推断，最终以清单为准，结论不受影响）。第 20 张设计屏 PNG 因 Figma MCP 限流缺失（结构已知）。 |

---

## 2. 输入素材清单与账实差异

| 类别 | 期望 | 实际 | 说明 |
|---|---|---|---|
| 设计屏 PNG | 20 | **19** | `20-RecoveryKey.png` 缺失（Figma MCP Starter 限流；节点结构见 `../figma/README.md` §2） |
| 实现截图 PNG | 23（任务书） | **22** | 17 个 `UiSnapshotCoverageTest` 用例产出 22 张（部分用例多帧：export×2、restore×2、cloud×3、reminder×2） |
| 截图状态清单 | `app-screens/manifest.md` | **已存在**（22 屏，02:40 生成） | 初稿评审时尚未落盘；最终以清单为准 |
| 测量基线 | `device-info-baseline.txt` | ✅ | emulator-5556 / SDK 36 / 1080×2400 / 420 dpi / font_scale 1.0 / night no |
| 设计令牌 | `design-tokens.md` | ✅ | 颜色 11、文字 7（button 待核）、圆角 3、间距 7、组件 5 类 |

---

## 3. 全局（跨屏）差异

> 「全局」= 与具体某屏无关、在多屏重复出现的偏差；逐屏结论引用编号。

| ID | 差异 | 设计基准 | 实现事实（截图/测量） | 严重度 | 证据 |
|---|---|---|---|---|---|
| G1 | **页面头部层级**：子页（详情/表单/设置类）标题字号与信息结构不同 | 每屏统一 `PageHeader`：标题 `孕迹/title` 28/42 Bold + 副标题 `caption` 12/18（副标题兼“返回 xxx”面包屑），无返回箭头（如 05/06/10/15/19/20） | 二级页统一 `TopAppBar`：返回箭头 + 标题 **20/30 Medium**、无副标题；一级页才用 `PageHeader` 28 | 明显 | 字形实测：设计页标题 26.5 dp（≈28 sp），实现 AppBar 标题 18.3 dp（≈20 sp）；实现 hero 标题 26.7 dp（28 sp ✓）→ 仅“页面标题”这一级降级。代码：`JournalNavigation.kt:367-375`、`Components.kt:60-64` |
| G2 | **筛选/标签组件** | 高 **48**、最小宽 64、圆角 **12**；选中 = `#356B53` 底 + 白字；未选 = 白底 + `#DDE4DA` 1px 描边 + `#5F7166` 字 | M3 `FilterChip`：高 **31.6 dp**、圆角 8；选中 = **`#E7F0E6` 浅绿底 + `#356B53` 字**；未选 = 白底浅描边 | 明显 | 像素测量：设计 `11-Health` 选中标签 47.5 dp 高、`#356B53`；实现 `timeline.png`/`trash-empty.png` 选中块 31.6 dp 高、`#E7F0E6`。代码：`FilterChip` 共 20+ 处（Timeline/Trash/Visits/Intake/Measurement/Security/Document…） |
| G3 | **页边距三套并存** | 统一 24 dp | ① `Inset` 系 24.0 dp（visit-detail/timeline/drug-catalog；`JournalApp.kt:54`）；② 新页面 `padding(20.dp)` → **20.2 dp**（CloudConnection/Export/Recovery/Restore/Security/JournalEntry/TimelineSource/MeasurementEditor）；③ 提醒设置 `4 dp + Inset 24` → **28.2 dp** | 轻微 | 实测：卡片/按钮 x0 = 24.0 / 20.2 / 28.2 dp |
| G4 | **字体族** | Noto Sans SC（Regular/Medium/Bold） | `FontFamily.SansSerif`（系统 Roboto + 中文 Noto CJK fallback）；字号/行高/字重与令牌**完全一致**（40/60、28/42、20/30、16/24、14/21、12/18） | 观察 | 代码 `Theme.kt:37-42`；截图中中文观感接近、拉丁字符（数字/`WebDAV`/`kg`）字形略窄 |
| G5 | **M3 默认容器色未纳入主题**（对话框/开关/菜单等） | 色板仅 9 个暖白绿令牌；设计无弹窗画板 | `AlertDialog` 默认容器 = M3 基线 `surfaceContainerHigh` = **`#ECE6F0`（淡紫灰）**；`Switch` 未选中轨道 = 基线 `surfaceContainerHighest` = **`#E6E0E9`**；`DropdownMenu` 同类。全 app 20+ 对话框、导出页开关等可见 | 明显 | 像素：`custom-reminder-editor.png` 对话框主体 `#ECE6F0` 占 40.7%（320×501 dp 区域）；`export-screen.png` 未选中 Switch 轨道 `#E6E0E9` 47.6×27.4 dp。根因：`Theme.kt:22-27` 只覆盖部分 `lightColorScheme` 角色，未覆盖 `surfaceContainer*/surfaceTint` 等 |
| G6 | **两套按钮体系并存** | 仅一套 `孕迹/Button`：364×52、圆角 26（渲染胶囊）、Primary=主色/Secondary=浅绿/Disabled=线色；无“描边按钮”变体 | `JournalButton` 52 dp 胶囊（主/次/禁用 ✓ 与设计一致）；但 Recovery/Restore/Export 页改用**原生 M3 `Button`/`OutlinedButton`**：高 **40.0 dp**、描边次级样式（设计系统无此变体） | 明显 | 实测：`security-settings`/`cloud-connection` 主按钮 52.2 dp；`local-backup`/`restore-wizard` 原按钮 40.0 dp。代码 `RecoveryScreen.kt:52/64/71/76/80`、`ExportScreen.kt:88-89`、`RestoreScreen.kt`、`RecoveryScreen.kt:80 OutlinedButton` |
| G7 | **标题重复** | 设计每屏只有一个 H1（AppBar 标题即页面标题） | AppBar 标题与内容首个 `headlineSmall` 重复（如导出资料页：“导出资料”出现两次） | 轻微 | `export-screen.png`；`ExportScreen.kt:29`；`RecoveryScreen.kt:46`、`CloudConnectionScreen.kt:70` 同模式（未同屏重复，但标题层级叠加） |
| G8 | **日期/时间文案格式** | 中文格式：`10月17日 星期六`、`10月21日 09:00`、`昨天 21:10` | ISO 格式：`2026-11-02`、`2026-10-23`、`2026-10-24 · 02:17`；设计示例日期本身不算差异，但**格式体系不同**属实现选择 | 轻微 | `visit-detail.png` 头部；`timeline.png` 日期分组；`visit-editor.png` 日期字段；设计 03/04/05/12 |
| G9 | **中英数混排空格/标点** | 统一加空格：`1 个孕期 · 28 条记录 · 12 个附件`、`时间待定 · 市妇幼保健院` | 多处无空格：`已有2项内容`、`其他HTTPS WebDAV服务`、`孕24周`；面板分隔符混用 | 观察 | `cloud-connection*.png`（截图可见）；设计 14/15 |
| G10 | **图标语义/复用** | 10 个语义图标（check/plus/file/clock/calendar/shield/pill/scale/restore/chevron），记录行按语义选用 | “记录”与“我的”枢纽多行复用同一叶子图标（6 行同图标）；底部导航“记录”tab 也用叶子而非设计文档图标 | 轻微 | 历史实现截图 `03-records-hub.png`/`04-profiles.png`（2026-10-22）与当前代码一致：`JournalNavigation.kt:189-199` 全部 `86969.svg`，`TimelineScreens.kt:43-48` 全部 `86969.svg`，底部导航 `JournalNavigation.kt:377` 记录 = `86969.svg`；设计 13 各行图标不同、底部导航“记录”为文档图标 |

> 说明：状态栏（34 dp）/手势条（20 dp）/底部导航（94 dp）在实现截图中缺席（测试宿主 Compose 内容区），属**方法限制**不是 UI 缺陷；底部导航因此无法与本轮截图逐一比对（仅历史截图可参考）。

---

## 4. 映射表

### 4.1 设计屏（19 张）↔ 实现截图（22 张）映射

状态：**有图对比** / **整数级对照** / **部分对照** / **仅设计（无实现截图）**。

| # | 设计屏（nodeId） | 文件 | 实现截图 | 状态 | 说明 |
|---|---|---|---|---|---|
| 01 | Welcome `12:26` | `01-Welcome.png` | — | **仅设计** | 本轮无欢迎页截图；代码 `WelcomeScreen`（JournalApp.kt:35-52）结构相近但**无“恢复已有资料”次按钮**（设计有）、footer 文案不同 → 代码线索，不计视觉判定 |
| 02 | CreatePregnancy `12:68` | `02-CreatePregnancy.png` | `pregnancy-editor.png` | **部分对照** | 实现截图是**编辑态**（设计只有创建态）：无“保存并进入首页/日期暂不确定”两按钮、多“备注/清除日期”，见 §5.2 |
| 03 | Today `12:138` | `03-Today.png` | —（历史 `01-today.png`） | **仅设计** | 本轮无截图；历史截图见 §7 |
| 04 | Visits `12:217` | `04-Visits.png` | —（历史 `02-visits.png`） | **仅设计** | 同上 |
| 05 | VisitDetail `12:283` | `05-VisitDetail.png` | `visit-detail.png` | **有图对比** | 结论 部分一致（明显），见 §5.1 |
| 06 | VisitEditor `12:333` | `06-VisitEditor.png` | `visit-editor.png` | **有图对比** | 部分一致（明显），见 §5.2 |
| 07 | Documents `12:409` | `07-Documents.png` | —（历史 `03-records-hub.png`） | **仅设计** | 同上 |
| 08 | DocumentDetail `12:497` | `08-DocumentDetail.png` | — | **仅设计** | 无实现截图；历史 `08-visit-documents.png` 是产检资料列表非详情页 |
| 09 | Medication `14:255` | `09-Medication.png` | —（历史 `05-medications.png`/`06-intakes.png`） | **仅设计** | 同上 |
| 10 | MedicationEditor `14:329` | `10-MedicationEditor.png` | — | **仅设计** | 无本表单截图（`drug-editor.png` 是药品基础信息，字段不对应） |
| 11 | Health `14:408` | `11-Health.png` | —（历史 `07-measurements.png`） | **仅设计** | 本轮无截图 |
| 12 | Timeline `14:485` | `12-Timeline.png` | `timeline.png` | **有图对比** | **不一致（明显）**，见 §5.3 |
| 13 | Settings `14:564` | `13-Settings.png` | —（历史 `04-profiles.png`） | **仅设计** | 同上 |
| 14 | Backup `14:635` | `14-Backup.png` | `local-backup.png`（弱对照） | **仅设计（仅实现弱参照）** | 设计 14 是“备份与恢复”总览（hero 状态卡 + 4 行入口 + 立即备份/从备份恢复）；实现已拆成“云盘连接/本地加密备份/导出资料”三页，`local-backup` 只是本地备份子页，不是同屏 → 不作视觉比对 |
| 15 | RestorePreview `14:694` | `15-RestorePreview.png` | — | **仅设计** | `restore-wizard.png` 是“选择加密备份”前置步（第 1 步），不是设计 15 的“第 3 步预览”，不作同屏判定 |
| 16 | BackupError `14:738` | `16-BackupError.png` | — | **仅设计** | 无实现截图 |
| 17 | EmptyDocuments `16:472` | `17-EmptyDocuments.png` | —（历史 `08-visit-documents.png` 非空态） | **仅设计** | 无空态截图；回收站空态见 `trash-empty.png`（不同页） |
| 18 | ImportProgress `16:521` | `18-ImportProgress.png` | — | **仅设计** | 无实现截图 |
| 19 | CloudConnect `16:576` | `19-CloudConnect.png` | `cloud-connection.png` (+backup/cleanup 小节) | **有图对比** | 部分一致（明显），见 §5.4 |
| 20 | RecoveryKey `16:644`（无 PNG） | — | `restore-wizard-key.png` | **结构级对照** | 设计 PNG 因限流缺失，仅按 README 记录的结构比对 → 部分一致（明显），见 §5.5 |

### 4.2 实现截图（22 张）去向（覆盖两侧全部文件）

| 实现截图 | 对应用途 | 设计对照 | 状态 |
|---|---|---|---|
| `visit-detail.png` | 产检详情 | 05 | 有图对比 |
| `visit-editor.png` | 新建产检预约 | 06 | 有图对比 |
| `pregnancy-editor.png` | 编辑孕期档案 | 02（创建态） | 部分对照 |
| `timeline.png` | 孕期时间线（全部） | 12 | 有图对比 |
| `cloud-connection.png` | 云盘连接（连接检查前） | 19 | 有图对比 |
| `cloud-connection-backup.png` | 云盘连接·云端备份小节 | 19 部分覆盖延伸 | 仅实现（无逐屏设计） |
| `cloud-connection-cleanup.png` | 云盘连接·云端清理小节 | 19 部分覆盖延伸 | 仅实现 |
| `restore-wizard-key.png` | 恢复资料·输入恢复密钥 | 20（结构） | 结构级对照 |
| `restore-wizard.png` | 恢复资料·选择加密备份 | — | 仅实现 |
| `local-backup.png` | 本地加密备份（READY 态） | —（14 已拆分） | 仅实现（弱参照 14） |
| `security-settings.png` | 安全设置 | — | 仅实现 |
| `reminder-settings.png` | 提醒设置（上屏） | — | 仅实现 |
| `reminder-settings-custom.png` | 提醒设置（滚动到自定义提醒） | — | 仅实现 |
| `custom-reminder-editor.png` | 新建提醒弹窗（整屏含系统栏/dim） | — | 仅实现 |
| `export-screen.png` | 导出资料（上屏） | — | 仅实现 |
| `export-screen-disabled.png` | 导出资料（滚动+按钮禁用态） | — | 仅实现 |
| `trash-empty.png` | 全局回收站（空态） | — | 仅实现 |
| `drug-catalog.png` | 管理药品 | — | 仅实现 |
| `drug-editor.png` | 新增药品 | —（10 仅风格参照） | 仅实现 |
| `measurement-editor.png` | 记录测量 | — | 仅实现 |
| `journal-entry-editor.png` | 写日常记录 | — | 仅实现 |
| `timeline-source-detail.png` | 原始记录 | — | 仅实现 |

**覆盖缺口（影响最大）**：本轮 22 张实现截图里 **16 张是“未设计界面”**，设计侧 **14 张屏完全没有实现截图**（01/03/04/07/08/09/10/11/13/14/15/16/17/18）—— 因此“设计屏对齐”的总体结论只能覆盖 19 张设计屏中的 5 张 + 1 个结构级；这是本轮证据集的结构性缺口，不是实现缺陷，但会限制结论强度（见 §8）。

---

## 5. 逐屏对齐结论（有实现截图可判定者）

### 5.1 05 VisitDetail ↔ `visit-detail.png` — 部分一致（明显）

| 维度 | 设计（05） | 实现 | 差异级别 |
|---|---|---|---|
| 标题 | 28 Bold `产检详情` + 副标题 `返回产检` | TopAppBar 20 Medium + 返回箭头，无副标题 | 明显（G1） |
| Hero 卡 | 浅绿卡：状态 + `常规产检` 28 + `10月21日 09:00 · 孕19周` + 医院·科室 | 浅绿卡 ✓ 同规格；状态/标题/时间/医院 ✓；**缺“孕周”信息**；日期为 ISO `2026-11-02` | 轻微（信息缺失+格式 G8） |
| 检查项目 | 有 `检查项目` 分区 + 记录行（勾选图标+“常规检查、超声检查”+说明+箭头） | **整块缺失** | 明显 |
| 准备事项 | 分区 + 正文 | 分区 + 正文 ✓ | 一致 |
| 关联资料 | 有分区 + “添加”链接 + 记录行（+图标/“暂未添加本次报告”/说明/箭头） | **整块缺失**，改为次要按钮“产检资料” | 明显 |
| 提醒 | 无（设计放在 06 编辑器） | 新增白卡“提醒/未设置/设置提醒” | 观察（设计未覆盖；属新增能力） |
| 按钮 | 主`记录本次就诊` → 次`修改预约`（全宽 52 dp） | 次`产检资料` → 次`修改预约` → 主`记录实际就诊`（52 dp ✓），另有危险文本按钮“取消这次预约”“删除产检记录” | 明显（顺序/层级不同；危险项设计未覆盖） |
| 页边距/卡片 | 24 dp / 白卡圆角 20 / 按钮 364×52 | 24.0 dp ✓ / 圆角 20 ✓ / 363×52 ✓ | 一致 |

**判定**：部分一致（明显）。核心信息在，但设计中的两块“检查项目/关联资料”功能区缺失、按钮层级与顺序改变、页面标题降级。

### 5.2 06 VisitEditor ↔ `visit-editor.png`（附 02 ↔ `pregnancy-editor.png`）— 部分一致（明显）

| 维度 | 设计 06 | 实现 | 差异级别 |
|---|---|---|---|
| 标题/副标题 | 28 `新建产检预约` + `返回 · 未完成内容会保留草稿` | TopAppBar 20 + 说明文字移到内容首行 | 明显（G1） |
| 字段 | 5 个 `孕迹/Field` 56 dp：预约日期* / 预约时间（选填）/ 医院 / 检查项目 / 准备事项；标签常显（notch） | 5 个字段 ✓ 高度≈56 dp；但“预约日期”用 `DateField`（OutlinedButton 伪装：小标签+值+日历图标+圆角 4）与 `OutlinedTextField` 混排；空值字段标签内联居中；**必填 `*` 缺失** | 轻微 |
| 提醒设置 | **有分区**：记录行「前一天 18:00 / 通知中默认隐藏医院和项目」 | **整块缺失**（实现把提醒放到 05 详情页卡片） | 明显 |
| 按钮/页脚 | 主`保存预约`（底部）+ 页脚“时间不确定时可留空，提醒时刻单独设置。” | 主`保存预约` ✓；提示语挪到标题下方且文案改为“时间不确定可留空，返回会保留草稿。” | 轻微 |
| 页边距 | 24 dp | 24.0 dp ✓（`Inset`） | 一致 |

02 ↔ `pregnancy-editor.png`（设计仅创建态，实现为编辑态，部分对照）：

| 维度 | 设计 02 | 实现（编辑态） | 差异级别 |
|---|---|---|---|
| 标题/副标题 | 28 `创建孕期档案` + “先从一个名字开始，日期也可以稍后补充” | 20 `编辑孕期档案` + 说明行 | 明显（G1） |
| 字段 | 档案名称 / 医生确认的预产期 / 末次月经日期（选填）/ 建档医院（选填） | 同 4 字段 ✓ + **新增“备注”**；日期字段为 `DateField` 伪字段 + “清除日期”文本按钮 | 轻微（编辑态合理增项；样式混排同 06） |
| 提示 | “选择日期依据”右侧 `可稍后填写` | **无右侧提示**；说明行移到顶部 | 轻微 |
| 说明卡 | 浅绿卡：图标 + “已填写医生确认的预产期” + 正文 | 浅绿卡 ✓ 同结构；文案“均以确认的预产期优先计算孕周”微差 | 一致/观察 |
| 按钮 | 主`保存并进入首页` + 次`日期暂不确定，先建档`（创建态专属） | 仅主`保存档案`（编辑态合理） | —（分支差异，不计不一致） |

### 5.3 12 Timeline ↔ `timeline.png` — **不一致（明显）**

| 维度 | 设计（12） | 实现 | 差异级别 |
|---|---|---|---|
| 页面骨架 | PageHeader 28 + 副标题“把一次次记录，连成自己的故事” | TopAppBar 20 + 无副标题 | 明显（G1） |
| 主 CTA | **底部** 主按钮“写一条日常备注” | **顶部** 主按钮“写日常记录”（文案也不同） | 明显 |
| 筛选 | 3 个标签：全部/产检/日常（48 dp，选中主色填充） | 6 个 `FilterChip` 可横滚：全部/产检/资料/方案变更/服药…（32 dp，选中浅绿底） | 明显（G2 + 信息架构扩张） |
| 搜索 | 无 | 新增“搜索标题与摘要”输入框 | 观察（新增） |
| 列表单元 | **72 dp 记录行**：44 dp 浅绿图标容器 + 标题/副标题 + 箭头（白卡圆角 20） | **大卡片**：类型 eyebrow（主色 16）+ 标题 20 + 时间/时区 + 依据 + 摘要 + “查看原始记录”文本按钮（行高约 150–190 dp，无图标容器、无箭头） | 不一致 |
| 日期分组 | 左侧 `10月17日`，右侧 `孕18周＋3天` | 左侧 ISO `2026-11-02`，**右侧无孕周** | 明显 |
| 日常备注卡 | **杏色卡** `#FAEAD8` 底 + `#80582C` 文字（唯一暖色强调） | 无杏色卡；日常记录也渲染为白卡 | 明显（色板中 apricot/amber 未使用） |

**判定**：不一致（明显）。“时间线”被实现成“记录信息流”，行组件、筛选、CTA 位置、暖色备注卡全部不同；这不仅是视觉差异，也影响设计稿想表达的“日期分组 + 快速浏览”节奏。

### 5.4 19 CloudConnect ↔ `cloud-connection.png` — 部分一致（明显）

| 维度 | 设计（19） | 实现 | 差异级别 |
|---|---|---|---|
| 标题 | 28 `连接坚果云` + 副标题“返回备份 · 可选设置” | TopAppBar 20 `云盘连接` | 明显（G1 + 文案） |
| 首屏说明 | 浅绿说明卡：盾牌图标 + 20 sp“加密后，再交给云盘”+ 正文 | **无说明卡**；改为 24 sp（`headlineSmall`，**不在令牌字号体系**）标题 + 普通段落 | 明显 |
| 字段 | 4 个 notch 字段（WebDAV 服务地址/账号/应用密码/备份目录），API 密码为纯点 | 4 个 `OutlinedTextField` ✓ 但“应用密码”为内联标签 + 行内“显示” `TextButton`；空值标签不浮起 | 轻微 |
| 主/次按钮 | 两个**全宽纵向**按钮：主`测试连接`、次`暂不开启备份` | **并排两个半宽按钮**：主`测试连接`(179 dp) + 次`保存连接`(179 dp，禁用态)；文案不同 | 明显 |
| 页脚 | “凭据只保存在本机，不写入报告或资料备份。” | 首屏段落“支持坚果云及其他HTTPS WebDAV服务。连接设置只保存在本机…”（文案重组；缺空格 G9） | 轻微 |
| 页边距 | 24 dp | 20.2 dp（G3） | 轻微 |
| 新增内容 | — | 连接检查（纯文本行，无卡片/分割线体系）、云端备份、自动备份（Switch 关闭态轨道 `#E6E0E9` 离板 G5）、云端清理 | 明显（未设计内容，见 §6.2 第 15 项） |

### 5.5 20 RecoveryKey（设计无 PNG）↔ `restore-wizard-key.png` — 部分一致（明显，结构级）

设计结构（`../figma/README.md` §2 记录）：徽章「已找到加密备份」/ 标题「输入你的恢复密钥」/ 说明卡「只有你能解开这份资料」/ `Field·恢复密钥` / `Button·验证并检查备份` / `Button·重新选择备份文件` / 页脚「密钥仅用于本机解密，不会发送给云盘」。

| 维度 | 设计（结构证据） | 实现 | 差异级别 |
|---|---|---|---|
| 徽章 | 有 | **无** | 明显 |
| 标题 | “输入你的恢复密钥”（28 级） | “输入恢复密钥”（TopAppBar 20） | 明显（G1） |
| 说明卡 | 独立说明卡（“只有你能解开这份资料”） | 普通段落（“请输入完整的 YJ1: 恢复密钥…”） | 明显 |
| 字段 | Field·恢复密钥 | 1 个 `OutlinedTextField`（标签内联，非浮起） | 轻微 |
| 主按钮 | “验证并检查备份” | “校验备份”（禁用态，52 dp 胶囊 ✓） | 轻微（文案） |
| 次按钮 | **全宽次按钮**“重新选择备份文件” | **绿色文本按钮**“重新选择文件” | 明显 |
| 页脚 | 有安全说明 | **无** | 轻微 |
| 页边距 | 24 | 20.2 dp | 轻微 |

> 该屏设计 PNG 缺失，以上为**结构级**判定（不计像素），请勿当成图像对比结论。

---

## 6. Figma 未设计界面的风格一致性

### 6.0 判定口径

- 先核对“设计令牌硬指标”：色板（`#F7F8F3/#FFFFFF/#223C32/#5F7166/#356B53/#E7F0E6/#DDE4DA`，危险 `#A44238`）、字号体系（40/28/20/16/14/12）、圆角（12/20/28→按钮 26）、间距、按钮/卡片/记录行/标签规格。
- 再对照设计稿的“范式”：PageHeader + 卡片 + 记录行 + 空态卡 + 主/次按钮。
- 结论三档：**风格统一** / **有偏差（列点）** / **无法评估（缺截图）**。（本轮实际：色板与字号层面普遍统一，偏差集中在组件选型/容器结构/间距。）

### 6.1 “Figma 无设计”16 项（`figma/README.md` §4 的 16 条路由）

| # | 未设计界面 | 截图 | 结论 | 偏差点（列点） |
|---|---|---|---|---|
| 1 | 时间线原始记录（TimelineSource） | `timeline-source-detail.png` | **有偏差（轻微）** | ① 纯文本堆叠，无卡片/分区/键值行（设计范式为卡片+记录行）；② 标题与正文重复显示同一句内容（信息冗余）；③ 页边距 20 dp（G3）；色板/字号/主按钮（52 dp）✓ |
| 2 | 日常记录编辑（JournalEntryEditor） | `journal-entry-editor.png` | **有偏差（轻微）** | ① 20 dp 页边距；② 日期为伪字段（小标签+值+图标），与 `OutlinedTextField` 混排、标签浮起状态不一；③ 无分区/卡片；色板/字号/主按钮 ✓ |
| 3 | 管理孕期（切换/编辑/归档） | 无 | **无法评估** | 无截图；代码为 `JournalCard` + `TextButton` 行（`JournalApp.kt:82-100`），仅代码线索，不作判定 |
| 4 | 全局回收站（含批量恢复/危险删除） | `trash-empty.png`（空态） | **有偏差（明显）** | ① 空态仅一行粗体“全局回收站为空”，无图标/说明/CTA，与设计 17 空态范式（浅绿卡+图标+28 标题+两按钮）差距大，页面 97% 留白；② 选中标签浅绿底 32 dp（G2）；列表态/危险确认态未截图 |
| 5 | 药品目录（DrugCatalog） | `drug-catalog.png` | **有偏差（轻微）** | ① 主 CTA 在页首（设计范式为主 CTA 在底部）；② 卡片内操作为“文本按钮行”（编辑信息/包装照片/删除），而设计组件集用记录行箭头或次按钮；卡片/色板/字号 ✓ |
| 6 | 药品编辑（DrugEditor） | `drug-editor.png` | **有偏差（轻微）** | ① 无分区标题，字段全为内联占位标签（空值不浮起），与设计 `Field` 常显标签不一致；② 20 dp 页边距；按钮 52 dp ✓ |
| 7 | 提醒设置（ReminderSettings） | `reminder-settings.png`、`reminder-settings-custom.png` | **有偏差（轻微）** | ① 卡片内是“标题+正文+文本按钮”，未使用设计的记录行/图标容器范式；② 页边距 28.2 dp（4+24 叠加，G3）；③ 卡片/圆角/字号/主次按钮 ✓；开关为 M3 默认（开启=主色 ✓，关闭轨道离板 G5 未在此图出现） |
| 8 | 导出资料/导出向导（Export） | `export-screen.png`、`export-screen-disabled.png` | **有偏差（明显）** | ① AppBar 标题与页内 H1 重复（G7）；② 标题用 24 sp（非令牌层级）；③ 大量原生 M3 控件（Checkbox/Switch/40 dp Button/OutlinedButton）与 52 dp 胶囊按钮体系混用（G6）；④ 未选中 Switch 轨道 `#E6E0E9` 离板；⑤ 全页无卡片，仅分割线分区，与设计卡片语言不一致 |
| 9 | 用药历史版本（MedicationHistory） | 无 | **无法评估** | 无截图 |
| 10 | 健康指标录入/修改（MeasurementEditor） | `measurement-editor.png` | **有偏差（轻微）** | ① 20 dp 页边距；② 字段标签浮起状态不统一（测量值/备注内联，日期/时间/来源浮起）；③ 顶部“体重·kg”胶囊样式与设计标签规格不同；色板/字号/主按钮 ✓ |
| 11 | 服药记录详情/补记/修改（IntakeEditor） | 无 | **无法评估** | 本轮无截图；历史 `06-intakes.png` 为上一版服药记录页（结构类似但版本不同），不作判定 |
| 12 | 日期依据历史（PregnancyHistory） | 无 | **无法评估** | 无截图 |
| 13 | 实际就诊记录（VisitRecord） | 无 | **无法评估** | 无截图 |
| 14 | 安全设置（应用锁/严格模式/立即锁定） | `security-settings.png` | **有偏差（轻微）** | ① 20 dp 页边距；② 退出锁定选项为 32 dp 浅绿选中标签（G2）；③ 标题层级混用 `titleLarge`(20) 与 `titleMedium`(16)；④ 无卡片，纯纵向段落；按钮 52 dp ✓ |
| 15 | 锁定/解锁页（UnlockScreen） | 无 | **无法评估** | 无截图；代码为 display 40 标题 + 居中按钮（`SecurityScreen.kt:33-41`），仅代码线索 |
| 16 | 自定义提醒编辑器（弹窗） | `custom-reminder-editor.png` | **有偏差（明显）** | ① 弹窗底色 `#ECE6F0`（M3 基线淡紫灰，离板，G5）；② 日期/时间用绿色文本按钮而非字段；③ 单选圈为 M3 默认样式（主色 ✓）；④ 无说明/辅助文字层级；与 app 表单语言不一致 |

> 口径：上表严格对应 `figma/README.md` §4 判为“无设计”的 16 条路由。**有截图 10 项（1/2/4/5/6/7/8/10/14/16）：0 项风格统一、10 项有偏差（明显 3：回收站空态、导出资料、自定义提醒弹窗；轻微 7）；无截图 6 项（3/9/11/12/13/15）无法评估。**
> 另：02 建档页的“编辑/归档分支”（`pregnancy-editor.png`）属“设计仅覆盖创建态”的部分对照，见 §5.2，不计入本表。

### 6.2 “部分覆盖”10 项（设计只覆盖主体/部分状态）

| # | 路由 | 截图 | 结论 | 偏差点 |
|---|---|---|---|---|
| 2 | 产检列表（Visits） | 无（历史 `02-visits.png`） | **无法评估（本轮缺截图）** | 历史参考：PageHeader ✓/记录行 ✓/底部导航 ✓，但主 CTA 在页首（设计在底部）、筛选改 列表/日历 + 4 状态 chips、缺月份标题与“日历视图”链接、设计大卡改为记录行 |
| 4 | 记录枢纽（RecordsHub） | 无（历史 `03-records-hub.png`） | **无法评估（本轮缺截图）** | 历史参考：6 行同图标（G10）、无设计中的 tabs/搜索/资料列表/主 CTA → 结构级不一致 |
| 5 | 时间线（Timeline） | `timeline.png` | **不一致（明显）** | 见 §5.3 |
| 10 | 用药方案（Medications） | 无（历史 `05-medications.png`） | **无法评估（本轮缺截图）** | 历史参考：设计 09 单页“当前方案+今天的记录”，实现拆为“用药方案/服药记录”两页；方案卡为纯文本+文本按钮行，无设计中的记录行/按钮层级；**历史截图可见 Bug #1 过期文案** |
| 13 | 药品包装照片（DrugPhotos） | 无 | **无法评估** | 无截图 |
| 15 | 云盘连接（CloudConnection） | `cloud-connection*.png` | **有偏差（明显）** | 见 §5.4 + §6.2 末条（云端备份/清理小节） |
| 16 | 备份与恢复（Recovery） | `local-backup.png`、`restore-wizard*.png` | **有偏差（明显）** | ① 拆页后无设计 14 的 hero 状态卡与 4 行入口；② RecoveryContent 用 24 sp 标题 + 原生 40 dp `Button` + `OutlinedButton`（G5/G6）；③ 20 dp 页边距；④ 安全副本/回收旧代际为纯文本区，无卡片；恢复执行/完成/历史快照详情无截图 |
| 20 | 服药记录（Intakes） | 无（历史 `06-intakes.png`） | **无法评估（本轮缺截图）** | 历史参考：`FilterChip` 选中浅绿（G2）、日期伪字段、计划卡内文本按钮行动作、**Bug #1 过期文案可见** |
| 28 | 产检资料（VisitDocuments） | 无（历史 `08-visit-documents.png`） | **无法评估（本轮缺截图）** | 历史参考：三枚“选图片/选PDF/拍照”胶囊按钮与设计 17 的“主按钮+次按钮”不同；搜索/分类 chips 设计未覆盖；资料卡封面区大片空白（可能为测试缩略图未加载，需真机复核，不作 bug） |
| 34 | 资料原件阅读器（DocumentPreview） | 无 | **无法评估** | 无截图；设计 08 仅有预览入口占位 |

**云盘连接其余小节（19 的部分覆盖延伸，`cloud-connection-backup.png` / `cloud-connection-cleanup.png`）**：连接检查为“纯文本行 + 右侧绿色可用”（无卡片，无记录行），自动备份 `Switch` 关闭轨道 `#E6E0E9` 离板（G5），两列半宽按钮（G6 的变体）→ **有偏差（明显）**。

---

## 7. 历史实现截图（2026-10-22）参考核对

> 来源 `docs/verification/2026-10-22-figma-sync/screens/`（9 张，1080×2400，来路见该目录 `manifest.json`）。**非本轮证据**，用于判断“较早版本是否更接近设计”。当前代码多处未变（已逐条核对），故可作趋势参考，但不计入逐屏判定。

| 历史截图 | 对应设计屏 | 参考结论 | 要点 |
|---|---|---|---|
| `01-today.png` | 03 Today | 部分一致（明显） | PageHeader 28 + 副标题 ✓、浅绿 hero 卡 ✓；但缺“今天的安排（用药待确认+查看全部）”“随手记一笔”三卡、“最近资料”“最近完整备份”footer；多出主按钮、管理孕期行、今日服药卡 → 信息架构不同 |
| `02-visits.png` | 04 Visits | 部分一致（明显） | 记录行 ✓、底部导航 ✓；主 CTA 在顶部（设计在底部）、筛选/月份标题/日历链接不同、设计大卡（4天后/日期/时间/医院/准备/提醒）未复刻 |
| `03-records-hub.png` | 07 Documents | 不一致（明显） | 设计为 tabs+搜索+全部资料列表+主 CTA；实现为 6 行菜单，且 6 行同一图标（G10） |
| `04-profiles.png` | 13 Settings | 部分一致（明显） | 缺“我的孕期”hero 卡与品牌 footer；入口集合不同（云盘/本地备份/导出/用药/服药/健康各自成行）；全部同图标 |
| `05-medications.png` | 09 Medication | 部分一致（明显） | 拆分方案页/服药页；方案卡纯文本+文本按钮行；**“通知提醒仍待后续提供”过期文案（Bug #1）** |
| `06-intakes.png` | 09/20 部分 | 部分一致（明显） | chips 浅绿选中、日期伪字段、计划卡文本按钮；**“通知提醒尚未启用”过期文案（Bug #1）** |
| `07-measurements.png` | 11 Health | 部分一致（明显） | 缺最新值大卡+趋势图+历史记录；空态浅绿卡 ✓；chips 浅绿选中（G2）；主 CTA 在顶部 |
| `08-visit-documents.png` | 17/28 | 部分一致（明显） | 空态范式未复刻；三枚胶囊按钮；资料卡封面区空白（待真机复核） |
| `09-recovery.png` | 14 Backup | 有偏差（明显） | 24 sp 标题 + 原生 40 dp 按钮 + 纯文本安全副本区；无设计 hero 卡/入口行 |

底线：**2026-10-22 → 2026-10-24 期间，设计系统组件（卡片/记录行/按钮）在新页面上被逐步替换为原生 M3 控件**，是“风格统一性”下降的主要来源（G5/G6）。

---

## 8. 美学与一致性改进建议（按优先级）

**P0（明显，建议本轮修复）**

1. **统一筛选/标签组件**：新建 `JournalTag`（48 dp 高、radius 12、选中主色填充+白字、未选白底+`#DDE4DA` 描边+`#5F7166`），替换全部 20+ 处 `FilterChip`（产检/时间线/回收站/服药/健康/资料/安全设置）；若保留 M3 Chip，至少通过 `FilterChipDefaults.filterChipColors(...)` + `Modifier.height(48.dp)` 对齐令牌。
2. **补齐 M3 容器色板**：在 `Theme.kt` 覆盖 `surfaceContainerLowest/Low/…/Highest`、`surfaceVariant`、`surfaceTint`，使 `AlertDialog`（→`surface`/白）、`DropdownMenu`、`Switch` 未选中轨道（→`outline`/`soft`）、`Snackbar` 回到暖白绿色板；一次性消除 `#ECE6F0`/`#E6E0E9` 紫灰。
3. **收敛按钮体系**：Recovery/Restore/Export 页面改用 `JournalButton` / `JournalButton(secondary=true)`（52 dp 胶囊）；如确需第三级“描边”样式，先在 `design-tokens.md` 定义（尺寸/圆角/颜色/禁用态）再实现，避免 40 dp 与 52 dp 混排。
4. **统一子页头部**：二级页改用设计 `PageHeader`（28/42 Bold + 12/18 副标题“返回 xxx”），或在 `TopAppBar` 中使用 `headlineMedium` + 副标题行；避免“AppBar 20 sp 标题 + 无副标题”（G1）。
5. **12 Timeline 按设计重做**：72 dp RecordRow + 44 dp 图标容器 + 日期分组（右侧孕周）+ 杏色备注卡（`#FAEAD8`/`#80582C`）+ 底部主 CTA；筛选回到 全部/产检/日常；若产品确需“资料/方案变更/服药”筛选，属于设计稿需补充的画板，应先补设计再实现。
6. **统一页边距 24 dp**（G3）：移除新页面的 `padding(20.dp)`，提醒设置去掉 `4 dp` 叠层。

**P1（轻微，随版优化）**

7. **统一日期/时间文案格式**（G8）：列表/详情用 `10月17日`、`10月21日 09:00`、`昨天 21:10`；表单值可用 ISO，但展示层统一中文格式；数字与中文之间补空格（G9）。
8. **图标语义化**（G10）：记录枢纽/我的按设计语义选图标；底部导航“记录”用文档图标；避免同一叶子图标复用 6 行。
9. **空态统一**：以设计 17 为范式（浅绿卡 + 图标 + 28 sp 标题 + 说明 + 主/次按钮），先落地到回收站空态（当前最弱）。
10. **消除标题重复**（G7）：页面 AppBar 标题与内容 H1 二选一，内容区改用 20 sp 分区标题。
11. **危险操作规范**：危险文本按钮已用 `#A44238` ✓；建议补 `dangerSoft #FCECE7` 底的确认卡/横幅（设计中已定义未使用），统一回收站/删除/取消场景。

**P2（观察 / 需设计补齐）**

12. **字体族**（G4）：如需与 Figma 完全一致，应内置 Noto Sans SC（或确认 MIUI/模拟器 fallback 一致）；否则在交付说明中标注“系统字体”。
13. **深色模式/平板/横屏/200% 字体**：设计无画板、实现无截图矩阵 → 建议 S01 后续补画板 + 截图基线，否则该维度无法验收。
14. **记录行/卡片的高阶用法**：设计 14/15/16 的 hero 卡 + 状态徽章是当前实现最缺的“状态总览”语言，建议在备份/恢复相关页复用（可一并解决 §6.2 第 16 项）。

---

## 9. 结论边界（务必随结论一起引用）

1. **模拟器渲染 ≠ 真机视觉**：本结论全部基于 API 36 模拟器 Compose 截图（1080×2400/420 dpi）与 Figma 2x 导出图；真机（小米 11 Ultra，Android 13/MIUI 14，`FLAG_SECURE`）**没有应用内容截图**，其字体栅格、系统栏/手势条、厂商字体与显示缩放可能使观感与截图不同。真机结论只到“结构/文案/行为”级。
2. **不含动效与交互手感**：转场、滚动、按压反馈、手势、动画节奏均未评审。
3. **不含深色/平板/横屏/200% 字体**：基线只有浅色、竖屏、font_scale=1.0。
4. **设计稿示例数据不是差异**（日期 2026-10-17/18、示例药品/计数等）；同理实现截图的测试夹具数据（2026-10-23/11-02、GMT 时区、合成档案）也不构成缺陷。
5. **数据/功能差异**（如 09 用药记录被拆成两页、14 备份被拆成三页、提醒从编辑器移到详情页）属产品/信息架构演进，本评审只记录“视觉与设计稿不对应”，**不判定功能对错**；是否回改由用户/Lead 决定。
6. **单屏样本量小**：19 张设计屏只覆盖有限状态（无错误态/加载态/空态之外的多数状态），实现截图也多为正常态；“部分一致”不等于“实现质量差”，仅表示与设计稿存在可见偏差。
7. 本评审**未修改** `figma/`、`app-screens/`、app 代码/测试/主文档；`comparison/` 之外无写入。

---

## 10. 遗留与需 Lead / 用户确认

1. **补齐实现截图（最高优先）**：本轮缺 14 张设计屏的实现截图（01 Welcome、03 Today、04 Visits、07 Documents、08 DocumentDetail、09 Medication、10 MedicationEditor、11 Health、13 Settings、14 Backup、15 RestorePreview、16 BackupError、17 EmptyDocuments、18 ImportProgress）。建议实现侧再跑一次 `UiSnapshotCoverageTest` 同类用例（`app-screens/manifest.md` 已存在，可直接对照）；补齐后本评审可把“无法判定”收敛为逐屏结论。
2. **第 20 屏 PNG 缺失**：待 Figma MCP 额度恢复后补导出，可把 §5.5 从结构级升级为图像级。
3. **颜色/组件修复责任**：§8 P0-1/2/3 属 UI 代码改动（`Theme.kt`/`Components.kt`/页面替换），本评审不改代码，需 Lead 指派实现任务并回归。
4. **设计稿与实现的 IA 差异**（时间线信息流、备份拆页、提醒位置）需用户确认是否“设计稿回改”还是“实现回改”。
5. **Bug #1 截图证据**：历史 `05-medications.png`/`06-intakes.png` 可见“通知提醒仍未提供/尚未启用”，与 `docs/verification/2026-10-24-Xiaomi11Ultra-full-functional/bugs.md` #1 一致；修复后建议重截这两页纳入证据。
6. **`FLAG_SECURE` 与截图**：本限制不削弱安全机制（记录为机制而非 bug）；若后续需要真机视觉证据，只能走“屏幕镜像/相机拍摄”等外部路径，需用户决定是否接受。

---

## 11. 证据与复现

- 逐张查看：`read_image` 覆盖 `figma/screens/` 19 张 + `app-screens/` 22 张 + 历史 `2026-10-22-figma-sync/screens/` 9 张。
- 像素/几何测量脚本与结果：见 `measurements.md`（含色值统计、组件高度、页边距、字形高度、弹窗底色；可用同一脚本复现）。
- 设计基准：`../figma/design-tokens.md`、`../figma/_tools/color-evidence.json`、`../figma/_tools/geometry-evidence.json`。
- 实现解释（只读代码）：`app/src/main/java/cn/yunji/journal/core/designsystem/{Theme,Components}.kt`、`feature/JournalNavigation.kt`、`feature/JournalApp.kt`、`feature/TimelineScreens.kt`、`feature/CloudConnectionScreen.kt`、`feature/RecoveryScreen.kt`、`feature/ExportScreen.kt`、`feature/RestoreScreen.kt`、`feature/MeasurementScreens.kt`、`feature/SecurityScreen.kt`、`feature/ReminderSettingsScreen.kt`、`feature/DrugScreens.kt`、`feature/TrashScreen.kt`。
- 问题清单（供 Lead 回填 Bug）：`findings.csv`。


---

## 12. 修复记录（2026-10-24 晚）

- **P0-2（M3 默认容器色）已修复并验证**：`app/src/main/java/cn/yunji/journal/core/designsystem/Theme.kt` 补齐 `surfaceContainerLowest/Low/Container/High/Highest`、`surfaceTint`、`surfaceBright/Dim`、`inverseSurface/OnSurface/Primary`、`errorContainer/onErrorContainer`，并把对话框/菜单容器映射为 White、Switch 未选中轨道映射为 Outline `#DDE4DA`；同时新增 `Apricot/Amber/Danger/DangerSoft` 常量。
  - 验证（API36 模拟器，同测试类/同基线）：对话框面 `#ECE6F0`→**`#FFFFFF`**（几何不变 320.0×501.3dp）、Switch 轨道 `#E6E0E9`→**`#DDE4DA`**、22 张截图中 13 个 M3 淡紫灰基线色 **0 命中**；构建/JVM 342/342/快照 17/17 通过。证据：[2026-10-24-ui-theme-fix](../2026-10-24-ui-theme-fix/README.md)。
  - 遗留（非阻断）：Switch 未选中轨道/滑块/描边同为 `#DDE4DA`，对比度偏低（1.22:1），建议后续显式指定 SwitchDefaults 或调 `surfaceContainerHighest`；DropdownMenu/Snackbar/BottomSheet/DatePicker 尚缺像素级截图证据；深色主题未截图验证。
- 其余 P0（筛选标签体系、按钮体系、二级页头部、Timeline、页边距）待后续修复。

- **P0-1（筛选/标签体系）、P0-3（按钮体系）、P0-6（页边距）已修复并验证（2026-10-24 晚）**：新增 `JournalTag`（48dp 高/min 64 宽/圆角 12/选中 #356B53 底+白字/未选中白底+#DDE4DA 描边+#5F7166 字，保留 `assertIsSelected` 语义与 testTag）替换全部 23 个 `FilterChip`；Recovery/Restore/Export 的原生 40dp 按钮改为 52dp `JournalButton`（19 个，保留 testTag）；页面边距统一 24dp（9 个文件 10 处 20→24，提醒设置 28.19→24dp）。
  - 实测（API36 模拟器，before=2026-10-24-ui-theme-fix/after）：标签高 32.0→**48.4dp**、选中填充 `#E7F0E6`→**`#356B53`**+白字、未选白底+描边、min 宽 64dp；按钮 40.0→**52.19dp**、x0 20.19→**24.0dp**；旧 FilterChip 残留 0；4 张未受影响页 sha256 逐字节一致。
  - 回归：构建成功、JVM 342/342、UiSnapshotCoverageTest 17/17、UiSnapshotTest 9/9、NavigationExperienceTest 23/23（三处 `assertIsSelected` 通过）、UiStateMatrixTest 7/7。证据：[2026-10-24-ui-consistency-fix](../2026-10-24-ui-consistency-fix/README.md)。
- 剩余 P0：**P0-4 二级页头部**（PageHeader 28/42+副标题）、**P0-5 Timeline 布局**（信息架构差异，需用户确认“设计稿回改还是实现回改”）。

- **P0-4（二级页头部层级）与 G7（标题重复）已修复并验证（2026-10-24 深夜）**：`JournalPage` 的 M3 `TopAppBar` 改为 `JournalPageHeader`（28/42 Bold 标题 + 12/18 副标题「返回上一级」、24dp 页边距；保留 `navigate-up` testTag/180° 图标/返回语义）；`ExportScreen` 删除重复「导出资料」H1，Cloud/Recovery/Restore 的 9 处 24sp `headlineSmall` 降为 20sp `titleLarge`。
  - 实测（API36 模拟器）：标题字形高 19.05→**26.67~27.43dp**（≈28sp，设计 26.5dp）、副标题 11.43dp（设计 11.0dp）、头部左缘 4.02→**23.83≈24dp**、Export「导出资料」标题级文本 2→1、内容标题 24→20sp 比例正确；root 四页 sha256 与 before 逐字节一致。
  - 回归：构建成功、JVM 342/342、UiSnapshotCoverageTest 17/17、UiSnapshotTest 9/9、NavigationExperienceTest 23/23（首次 1/23 失败系更高 header 使 PDF 卡片落在 LazyColumn 组合窗口外，经 Lead 授权做最小测试侧滚动同步适配，未放宽断言）。证据：[2026-10-24-ui-header-fix](../2026-10-24-ui-header-fix/README.md)。
  - 观察：设计稿头部无返回箭头；实现保留返回键后标题列 x0≈76.6dp（设计 24.5dp），属可访问性取舍，已在证据中记录。
- 剩余 P0：**P0-5 Timeline 布局**（信息架构差异，需用户确认「设计稿回改还是实现回改」）。

---

## 13. P0/P1 修复状态总表（2026-10-24 深夜更新）

| 项 | 内容 | 状态 | 证据 |
|---|---|---|---|
| P0-2 | M3 默认容器色（对话框 #ECE6F0 / Switch 轨道 #E6E0E9 → White / #DDE4DA） | ✅ 已修复+验证 | [theme-fix](../../2026-10-24-ui-theme-fix/README.md) |
| P0-1 | 筛选/标签体系（`JournalTag` 替换 23 个 FilterChip；32→48.4dp、主色选中、白底描边） | ✅ 已修复+验证 | [ui-consistency-fix](../../2026-10-24-ui-consistency-fix/README.md) |
| P0-3 | 按钮体系（Recovery/Restore/Export 19 个 40dp → 52dp JournalButton） | ✅ 已修复+验证 | 同上 |
| P0-6 | 页边距统一 24dp（9 文件 10 处） | ✅ 已修复+验证 | 同上 |
| P0-4 | 二级页头部（28/42 标题 + 12/18 副标题；Export 重复 H1 去除；内容标题 24→20sp） | ✅ 已修复+验证（返回键保留，标题列 x0≈76.6dp 为可访问性取舍） | [ui-header-fix](../../2026-10-24-ui-header-fix/README.md) |
| P0-5 | Timeline 布局（72dp 记录行/杏色备注卡/日期分组/筛选 IA） | ⏸ 待用户确认 IA 方向（设计稿回改 vs 实现回改） | §8 P0-5 |
| P1-7 | 日期/时间展示格式统一 | ⏳ 待办 | §8 P1-7 |
| P1-8 | 图标语义化 | ⏳ 待办 | §8 P1-8 |
| P1-9 | 空态统一（回收站空态最弱） | ⏳ 待办 | §8 P1-9 |
| P1-10 | 标题重复（已解决 Export；其余页面 AppBar 标题 vs 内容 H1 已通过 24→20sp 层级处理） | ✅ 基本解决 | ui-header-fix |
| P1-11 | 危险操作底色 `dangerSoft #FCECE7` 未使用 | ⏳ 待办 | §8 P1-11 |
| 观察 | Switch 未选中轨道/滑块/描边同为 #DDE4DA（对比度 1.22:1） | ⏳ 待办（显式 SwitchDefaults） | theme-fix §0 |
| Bug #3 | 键盘弹出时双重 IME 内边距压缩（视口 106.7dp） | ✅ 已修复；专项视口断言补充中 | [ime-inset-fix](../../2026-10-24-ime-inset-fix/README.md) |

**补充验证（2026-10-24 深夜，见 [ui-evidence-gaps](../../2026-10-24-ui-evidence-gaps/README.md)）**
- 表面组件像素证据已补：AlertDialog `#FFFFFF`、DatePicker `#FFFFFF`、Snackbar `#223C32`（inverseSurface）、DropdownMenu `#FFFFFF`，M3 淡紫灰精确命中 0；App 未使用 BottomSheet（代码 grep 0）。
- 深色主题已验证：light/dark 两套快照 （17+9 各两套；深色 31 张），31/31 主色为深色令牌（`#141D17`/`#1D2921`/`#213228`）、浅色背景 `#F7F8F3` 在深色截图 0 命中；对比度 5.98–14.04，文字级均达 WCAG AA/AAA。
- Bug #3 回归断言已补：`ImeInsetLayoutTest` 键盘弹出 `document-list` 443.05dp ≥300dp（修复前 106.7dp），收起恢复 755.43dp。
- 仍在待办：Switch 未选中轨道/滑块同色低对比（P1 观察）；P0-5 Timeline 待用户确认 IA。

**全量回归（2026-10-24）**：emulator-5556 全量仪器矩阵 **OK (367 tests) / 0 failed / EXIT=0**（772.3s），覆盖 UI 修复后的导航/表单/备份恢复/云盘/快照等；证据 `../../2026-10-24-ui-evidence-gaps/full-matrix-lead-367-tests.log`。

**P1 收敛（2026-10-24 继续）**：Switch 对比度修复（未选中滑块/轨道 1.00:1→4.46:1）、8 处纯文字空态统一为 soft 卡+图标（文案不变）、深色表面 4 张验证（容器 #213228/#1B2A20、Snackbar #E3EADF，无浅色残留）。证据：`../../2026-10-24-ui-p1-polish/README.md`；遗留空态标题色取 onPrimaryContainer 而非 ink、Switch 禁用态无像素证据。

**P1 后全量回归**：emulator-5556 全量仪器矩阵 **OK (367 tests) / 0 failed / EXIT=0**（含开关/空态与 Bug #3 视口断言），证据 `../../2026-10-24-ui-p1-polish/full-matrix-after-p1-367-tests.log`。

**P1 遗留收敛（2026-10-24）**：空态标题色已由 primary 改为 ink #223C32（像素 diff 验证）；Switch 禁用态仍缺像素证据。**P0-5 Timeline IA** 决策备忘见 `../../design/决策备忘-Timeline信息架构.md`（A 实现回改 / B 设计稿回改 / C 折中推荐）。

**P0-5 Timeline 已实现（方案 A，2026-10-24）**：3 筛选/72dp 记录行+44dp 图标容器/日期分组+孕周/杏色备注卡/底部 CTA；量化行高 72.38dp、图标 44.19dp、杏色卡 363.43×108.95dp、CTA 363.43×52.57dp；证据 `../../2026-10-24-timeline-ia-a/README.md`。P0 全部收敛（P0-1/2/3/4/5/6）。

**P1-7 已完成（2026-10-24）**：展示层日期/时间统一为中文格式（同年 M月d日、跨年 yyyy年M月d日、带时间 HH:mm；表单/导出/备份仍 ISO），41 处调用；证据 `../../2026-10-24-remaining-debug/README.md`。**Switch 禁用态像素证据已补**（四态截图）。P1-8 图标语义化仍待 Figma 图标命名映射；安全设置待真机走查。

**真机像素级复核已完成（2026-10-24 round 16）**：通过 -PvisualVerification=true 验证构建在 11 Ultra 截取 17 张真机截图（含深色），与 Figma 10 屏并排对比；实测浅色色板 #F7F8F3/#E7F0E6/#FFFFFF/#356B53/#223C32/#DDE4DA、时间线杏色卡 #FAEAD8 与设计令牌一致；Timeline 方案 A 真机视觉高度一致；未设计页面风格统一；安全设置页因设计内 SecureFlagPolicy.SecureOn 无法截图（语义树证据保留）。证据：../../2026-10-24-device-visual-round16/README.md。
