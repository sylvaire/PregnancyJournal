# Figma 设计素材与 UI 对齐清单（2026-10-24）

- 用途：M6（小米 11 Ultra 全功能调试）中的 **UI 与 Figma 设计稿对齐**、以及 **Figma 未设计界面的风格一致性** 评审素材。
- 来源：[孕迹 · V1.0 Android UI](https://www.figma.com/design/qwUxmzTnSJtzppU5eZwHcy)（fileKey `qwUxmzTnSJtzppU5eZwHcy`；页面 `0:1`「01 · 孕迹 V1.0」）。
- 产物：`screens/`（设计屏导出 PNG）、`manifest.json`（清单/节点/失败记录）、`design-tokens.md`（设计令牌）、`implementation-board/`（同步板替代证据）、`_tools/`（像素取色与几何测量脚本+结果）。
- 权限：**只读**。本任务未修改 Figma 画布、未改 app 代码/测试/主文档。

## 结论摘要

1. **20 个设计屏在画布上全部存在**（`get_metadata` 逐节点核对），成功导出 **19/20**，导出尺寸 824×1830（412×915 dp 的 2x）。
2. **第 20 屏 `20 RecoveryKey`（`16:644`）导出失败**：Figma MCP 触发 Starter 计划调用上限（`You've reached the Figma MCP tool call limit on the Starter plan`）；节点本身存在，结构已由 `get_metadata` 取得。**未用记忆/旧图替代**。
3. **`06 · 当前实现（2026-10-22 同步）` 板（`50:581`）在当前画布中不存在**：`get_metadata(nodeId=50:581)` 返回 "node ID was not found in the file"，页面顶层节点清单也没有该板。`docs/design/figma-state.json` 与 2026-10-22 同步清单仍登记它 → **索引已过期或画布被删除/回退**。已复制当时的板级预览作历史参照（`implementation-board/board-06-preview-2026-10-22-historical.png`），并在 `manifest.json` 记录证据。
4. **设计令牌已取到主要集合**：7 个颜色变量 + 7 个间距 + 3 个圆角 + 5 个文字样式为 2026-10-24 实时返回；其余（apricot/amber/danger/dangerSoft、space/32、heading 样式）以创建记录 + 导出图像素取色交叉验证（详见 `design-tokens.md`）。
5. 20 屏设计覆盖的页面有限；**实现侧 29 个路由中有大量页面没有 Figma 设计**（见 §4、§5），这些页面的“风格是否统一”必须由走查人单独下结论，不能引用设计稿当依据。

---

## 1. 方法与限制

| 步骤 | 工具 | 结果 |
|---|---|---|
| 建会话 | `mcp__figma__create_session` | sessionId `250e26e9-e82e-431d-a65c-e4ddaca48efe` |
| 结构核对 | `mcp__figma__get_metadata`（page `0:1`、node `50:581`） | 20 屏 + 5 分组 + 组件/图标集全部确认；同步板不存在 |
| 设计屏导出 | `mcp__figma__download_assets`（png, scale=2） | 19/20 成功；第 20 屏限流失败 |
| 备选导出 | `mcp__figma__get_screenshot`（maxDimension=1600） | 只输出 1x（412×915，不放大），故改用 download_assets |
| 令牌采样 | `mcp__figma__get_variable_defs`（node `12:138`） | 1 个代表节点成功；后续调用限流 |
| 库 | `mcp__figma__get_libraries` | 8 个已订阅社区库（Material 3 Design Kit、Simple Design System、iOS/macOS/watchOS/visionOS 26/27）；无团队库 |
| 设计系统检索 | `mcp__figma__search_design_system` | **未执行成功**（限流） |

- 限流发生点：约第 30 次 MCP 调用后；曾间隔约 10 分钟重试 1 次仍失败，**未反复轮询**。
- 导出图为 `curl` 下载的 Figma MCP 临时 asset URL，URL 短时有效，已落盘为本地 PNG；清单只记录文件名与尺寸，不留 URL。
- 设计稿的示例日期（2026-10-17）、示例用药与“校验通过/备份完成”等均为**虚构演示文案**，不表示应用已实现该状态（`docs/design/README.md`）。

---

## 2. 20 个设计屏清单与导出情况

节点与名称来自 `docs/design/figma-state.json`，并在 2026-10-24 用 `get_metadata` 逐项复核存在。文件均在 `screens/`，尺寸均为 **824×1830 px（412×915 dp @2x）**。

| # | 设计屏名 | nodeId | 文件 | 导出 |
|---|---|---|---|---|
| 01 | 01 Welcome | `12:26` | `01-Welcome.png` | ✅ |
| 02 | 02 CreatePregnancy | `12:68` | `02-CreatePregnancy.png` | ✅ |
| 03 | 03 Today | `12:138` | `03-Today.png` | ✅ |
| 04 | 04 Visits | `12:217` | `04-Visits.png` | ✅ |
| 05 | 05 VisitDetail | `12:283` | `05-VisitDetail.png` | ✅ |
| 06 | 06 VisitEditor | `12:333` | `06-VisitEditor.png` | ✅ |
| 07 | 07 Documents | `12:409` | `07-Documents.png` | ✅ |
| 08 | 08 DocumentDetail | `12:497` | `08-DocumentDetail.png` | ✅ |
| 09 | 09 Medication | `14:255` | `09-Medication.png` | ✅ |
| 10 | 10 MedicationEditor | `14:329` | `10-MedicationEditor.png` | ✅ |
| 11 | 11 Health | `14:408` | `11-Health.png` | ✅ |
| 12 | 12 Timeline | `14:485` | `12-Timeline.png` | ✅ |
| 13 | 13 Settings | `14:564` | `13-Settings.png` | ✅ |
| 14 | 14 Backup | `14:635` | `14-Backup.png` | ✅ |
| 15 | 15 RestorePreview | `14:694` | `15-RestorePreview.png` | ✅ |
| 16 | 16 BackupError | `14:738` | `16-BackupError.png` | ✅ |
| 17 | 17 EmptyDocuments | `16:472` | `17-EmptyDocuments.png` | ✅ |
| 18 | 18 ImportProgress | `16:521` | `18-ImportProgress.png` | ✅ |
| 19 | 19 CloudConnect | `16:576` | `19-CloudConnect.png` | ✅ |
| 20 | 20 RecoveryKey | `16:644` | `20-RecoveryKey.png`（**缺失**） | ❌ MCP 限流 |

**缺失屏的替代证据**（第 20 屏）：`get_metadata` 已读到结构 —— 徽章「已找到加密备份」、标题「输入你的 恢复密钥」、说明卡「只有你能解开这份资料」、`Field · 恢复密钥`、`Button · 验证并检查备份`、`Button · 重新选择备份文件`、页脚「密钥仅用于本机解密，不会发送给云盘」。**没有 PNG**，比对时请勿把 15/16 屏当成它。

---

## 3. `06 · 当前实现（2026-10-22 同步）` 板状态

| 项 | 值 |
|---|---|
| 登记的板节点 | `50:581`（`docs/design/figma-state.json`、`docs/verification/2026-10-22-figma-sync/manifest.json`） |
| 登记的 9 帧 | A1 `50:585` 今天 / A2 `50:653` 产检 / A3 `50:712` 记录枢纽 / A4 `50:812` 我的 / A5 `51:751` 用药方案 / A6 `51:783` 服药记录 / A7 `51:822` 健康指标 / A8 `51:847` 产检资料 / A9 `51:893` 本地加密备份 |
| 2026-10-24 实况 | **不存在**（`get_metadata` 报 node not found；页面顶层无「06 · 当前实现」板） |
| 影响 | 无法导出 A1–A9 的当前 Figma 版本；不能把该板当作“已同步实现”的证据 |
| 替代证据 | `implementation-board/board-06-preview-2026-10-22-historical.png`（2026-10-22 板级预览，1300×1122，含 9 帧）；9 帧对应的实机截图仍在 `docs/verification/2026-10-22-figma-sync/screens/` |
| 后续 | 若评审需要该板，需在 Figma 中重建或从历史版本恢复；本次未改画布 |

---

## 4. 实现界面 ↔ 设计屏映射（按 App 路由）

实现侧路由与文件（`app/src/main/java/cn/yunji/journal/feature/JournalNavigation.kt`，L35–L63 定义 29 个 `NavKey`；`L173–L357` 为 `entry<>` 注册）。判定只依据**实际看过的 Figma 画布内容**（`get_metadata` 节点树 + 19 张导出图），不臆测。

| # | 路由 | 实现（Composable） | 对应设计屏 | 覆盖判定 | 依据 / 差异说明 |
|---|---|---|---|---|---|
| 1 | `TodayRoute` | `TodayScreen`（JournalApp.kt） | 03 Today `12:138`；空档案时 01 Welcome `12:26` | **有设计** | 设计含孕期摘要/用药待确认/下一次产检/快捷操作/最近资料；实现首页信息更多（如今日服药/备份状态），需逐屏核 |
| 2 | `VisitsRoute` | `VisitsScreen`（PrenatalScreens.kt:32） | 04 Visits `12:217` | **部分覆盖** | 列表/“待进行 2/历史记录”/新建入口/过期说明有设计；“日历视图”在设计里只是一个入口文字（`12:255`），**日历页面本身无设计** |
| 3 | `ProfilesRoute` | “我的” hub（JournalNavigation.kt:185–203） | 13 Settings `14:564` | **有设计（结构）** | 设计有 我的孕期卡 + 备份与恢复/应用锁与隐私/提醒设置/导出我的资料/回收站；实现另有 云盘连接/本地备份/导出资料/用药方案/服药记录/健康指标/安全设置 等行 → 入口集合不同 |
| 4 | `RecordsRoute` | `RecordsHub` | 07 Documents `12:409`（记录枢纽 tabs 资料/用药/健康/时间线） | **部分覆盖** | 设计的“我的记录”含同组 tab 与资料列表；实现 hub 的卡片/入口布局未按设计绘制；另有 2026-10-22 同步板 A3（已不存在） |
| 5 | `TimelineRoute`（默认） | `TimelineScreen`（TimelineScreens.kt:53） | 12 Timeline `14:485` | **部分覆盖** | 时间线（全部/产检/日常筛选、记录行、日常备注卡）有设计；同一实现还承载 `type=JOURNAL`（日常记录列表）与 `type=DOCUMENT`（全部资料列表），这两个列表态无独立设计 |
| 6 | `TimelineSourceRoute` | `TimelineSourceScreen`（TimelineScreens.kt:106，“原始记录”） | — | **无设计** | 20 屏无“原始记录/来源详情”页 |
| 7 | `JournalEntryEditorRoute` | `JournalEntryEditorScreen`（TimelineScreens.kt:87） | — | **无设计** | 12 屏只有「＋ 写一条日常备注」按钮，无编辑表单 |
| 8 | `PregnancyManagerRoute` | `ProfilesScreen`（管理孕期） | — | **无设计** | 02 是创建表单；切换/编辑/归档/删除孕期列表页无设计 |
| 9 | `TrashRoute` | `TrashScreen`（TrashScreen.kt:53） | — | **无设计** | 13 屏只有「回收站」入口行（`14:627`）；回收站页面、批量恢复、危险删除态均无设计 |
| 10 | `MedicationsRoute` | `MedicationListScreen`（MedicationScreens.kt:27） | 09 Medication `14:255` | **部分覆盖** | 09 是“用药记录”，把方案卡与服药确认放在一页（含 `当前方案/服药历史` 标签）；实现拆成“用药方案”与“服药记录”两页，09 只部分对应 |
| 11 | `DrugCatalogRoute` | `DrugCatalogScreen`（DrugScreens.kt:20） | — | **无设计** | 药品目录（新增药品/规格/剂型/包装照片/删除）无设计 |
| 12 | `DrugEditorRoute` | `DrugEditorScreen`（DrugScreens.kt:44） | 10 MedicationEditor `14:329`（仅风格参照） | **无设计** | 10 的字段是方案（药品名称/规格/剂量/频次/服药时间/起止日期）；药品基础信息表单（药品名称/规格/剂型）字段不对应，只能借用表单风格 |
| 13 | `DrugPhotosRoute` | `DocumentScreen(medication=true)`（DocumentScreen.kt:47） | 17 EmptyDocuments `16:472`（空态可参照） | **部分覆盖** | 空态/导入入口样式可参照；药品包装照片专页布局（分组、封面、删除）无设计 |
| 14 | `ReminderSettingsRoute` | `ReminderSettingsScreen`（ReminderSettingsScreen.kt:35） | — | **无设计** | 13 屏只有「提醒设置」入口行（`14:613`）；权限/精确提醒/未来 7 天重建/通知设置入口均无设计（同步板 notCaptured 也登记了提醒相关未覆盖） |
| 15 | `CloudConnectionRoute` | `CloudConnectionScreen`（CloudConnectionScreen.kt:26） | 19 CloudConnect `16:576` | **部分覆盖** | 19 覆盖 WebDAV 地址/账号/应用密码/备份目录/测试连接/暂不开启；实现页另有云端清理小节、连接状态/错误态，无设计（2026-10-22 同步清单亦标“云盘连接未同步”） |
| 16 | `RecoveryRoute` | `RecoveryScreen`（RecoveryScreen.kt） | 14 Backup `14:635`、15 RestorePreview `14:694`、16 BackupError `14:738`、20 RecoveryKey `16:644` | **部分覆盖** | 覆盖备份完成态、恢复预览（第 3/4 步）、备份失败、恢复密钥输入；实现的 恢复执行/完成、历史快照详情、安全副本（`SafetyCopiesPanel`）、回收旧代际 等状态无逐个设计 |
| 17 | `ExportRoute` | `ExportScreen`（ExportScreen.kt:15） | — | **无设计** | S01 阶段记录明确“导出向导”未覆盖；13 屏只有「导出我的资料」入口行（`14:620`） |
| 18 | `MedicationEditorRoute` | `MedicationEditorScreen`（MedicationScreens.kt:64） | 10 MedicationEditor `14:329` | **有设计** | 设计为“记录用药方案”，6 个 Field + 医嘱原文说明 + 保存按钮；实现字段需逐一核对（设计稿日期 2026-10-17，实现可能已扩展） |
| 19 | `MedicationHistoryRoute` | `MedicationHistoryScreen`（MedicationScreens.kt:118） | — | **无设计** | 用药历史版本页无设计 |
| 20 | `IntakesRoute` | `IntakeScreen`（IntakeScreens.kt:60） | 09 Medication `14:255` | **部分覆盖** | 09 含服药确认行与“记录已服用/稍后提醒”按钮；实现的 按日计划/全部历史、前一天/今天/后一天、补记/跳过/删除 布局无完整设计 |
| 21 | `MeasurementsRoute` | `MeasurementScreen`（MeasurementScreens.kt:37） | 11 Health `14:408` | **有设计（部分状态）** | 体重/血压/血糖分段、体重趋势与刻度、记录列表、＋记录按钮有设计；空态/加载/分组切换与实现可能不同（同步板 A7 记录实现含空态/分段） |
| 22 | `MeasurementEditorRoute` | `MeasurementEditorScreen`（MeasurementScreens.kt:154） | — | **无设计** | 11 屏只有「＋ 记录健康指标」按钮；录入/修改表单无设计 |
| 23 | `IntakeEditorRoute` | `IntakeEditorScreen`（IntakeScreens.kt:173） | — | **无设计** | 记录/补记/修改服药的表单无设计 |
| 24 | `PregnancyHistoryRoute` | `PregnancyHistoryScreen`（PrenatalScreens.kt:127） | — | **无设计** | 日期依据历史页无设计 |
| 25 | `PregnancyEditorRoute` | `PregnancyEditor`（创建/编辑） | 02 CreatePregnancy `12:68` | **有设计（创建态）** | 02 覆盖建档字段与两个按钮；编辑态（修改/归档、状态变更）复用同表单但无独立设计 |
| 26 | `VisitEditorRoute` | `VisitEditor`（新建/修改预约） | 06 VisitEditor `12:333` | **有设计（新建态）** | 06 覆盖预约日期/时间/医院/检查项目/准备事项/提醒/保存；修改预约复用同表单，无独立设计 |
| 27 | `VisitDetailRoute` | `VisitDetail` | 05 VisitDetail `12:283` | **有设计** | 概览/检查项目/准备事项/报告区/记录本次就诊/修改预约均有设计；实现扩展的提醒设置、取消/删除操作可能超出设计 |
| 28 | `VisitDocumentsRoute` | `DocumentScreen(visitId)`（DocumentScreen.kt:47） | 17 EmptyDocuments `16:472`（+ 07 Documents 列表） | **部分覆盖** | 检查资料空态有设计；产检资料页的封面、关联产检、搜索/筛选、分享原件等无完整设计（同步板 A8 曾复刻实现版，但 06 板已不存在） |
| 29 | `VisitRecordRoute` | `ActualVisitEditor`（实际就诊记录） | — | **无设计** | 05 屏只有「记录本次就诊」入口按钮；实际就诊记录表单无设计 |
| 30 | 安全设置（`SecuritySettingsScreen`，MainActivity.kt:105 打开） | SecurityScreen.kt:16 | — | **无设计** | 20 屏无安全设置；2026-10-22 同步清单明确列“安全设置/应用锁与锁定页”未覆盖 |
| 31 | 锁定页（`UnlockScreen`，SecurityScreen.kt:33） | — | — | **无设计** | 同上；A03 锁定入口只有设备侧实现与测试，无设计稿 |
| 32 | 自定义提醒编辑器（`CustomReminderEditor`，ReminderSettingsScreen.kt:173 弹窗） | — | — | **无设计** | C05 新增的一次/每天/每周提醒编辑器，设计稿完全没有 |
| 33 | （补充）`WelcomeScreen`（Today 无档案） | JournalApp.kt:35，由 JournalNavigation.kt:173 渲染 | 01 Welcome `12:26` | **有设计** | 欢迎页 hero/两条说明/两个按钮有设计 |
| 34 | （补充）资料原件预览 `DocumentPreviewDialog`（DocumentPreview.kt:114） | — | 08 DocumentDetail `12:497` | **部分覆盖** | 08 以“预览入口占位 + 查看原件/导出分享按钮”示意，未设计真实图片/PDF 阅读器 UI |

> 判定用词：**有设计** = 该页面主体结构与文案在 20 屏中有对应画板；**部分覆盖** = 只有页面的一部分（空态/入口/主结构）有设计，其余无；**无设计** = 20 屏中没有任何对应画板。
> 提醒：设计稿是 2026-10-17/18 的 V1.0 首轮稿，2026-10-22 后实现继续演进；即使标“有设计”的页面也存在实现已变化的可能，**对齐结论以实际截图比对为准**。

---

## 5. “Figma 未设计的实现界面”清单

下面每一项都**没有**完整设计稿，评审时只能判断“与整体风格是否统一”，不能对照设计稿逐像素比：

1. 时间线原始记录（`TimelineSourceRoute`）
2. 日常记录编辑（`JournalEntryEditorRoute`）
3. 管理孕期（`PregnancyManagerRoute`：切换/编辑/归档）
4. 全局回收站（`TrashRoute`，含批量恢复与危险操作）
5. 药品目录（`DrugCatalogRoute`）
6. 药品编辑（`DrugEditorRoute`）
7. 药品包装照片（`DrugPhotosRoute`；仅空态可参照 17）
8. 提醒设置（`ReminderSettingsRoute`）
9. 自定义提醒编辑器（一次/每天/每周，弹窗）
10. 导出资料/导出向导（`ExportRoute`）
11. 用药历史版本（`MedicationHistoryRoute`）
12. 健康指标录入/修改（`MeasurementEditorRoute`）
13. 服药记录详情/补记/修改（`IntakeEditorRoute`）
14. 日期依据历史（`PregnancyHistoryRoute`）
15. 实际就诊记录（`VisitRecordRoute`）
16. 孕期档案编辑/归档态（`PregnancyEditorRoute` 的编辑分支；02 只覆盖创建态）
17. 安全设置（应用锁、严格模式、立即锁定）
18. 锁定/解锁页（`UnlockScreen`）
19. 恢复流程其余状态：恢复执行中/完成、历史快照详情、安全副本（`SafetyCopiesPanel`）、回收旧代际确认
20. 云盘连接其余小节：云端清理、连接错误/重连态
21. 产检日历视图（04 仅有「日历视图」入口文字）
22. 日常记录列表 / 全部资料列表（`TimelineRoute` 的 JOURNAL / DOCUMENT 两种复用态）
23. 资料原件真实阅读器（08 只有入口占位）
24. 深色模式、平板、横屏、200% 字体等独立画板（S01/同步清单均登记未覆盖）

**部分覆盖（有设计但只覆盖主体/部分状态）**：今日首页、产检列表、记录枢纽、我的、用药方案、服药记录、健康指标、产检资料、备份与恢复、恢复预览/失败/密钥、云盘连接、建档、产检详情、新建预约、资料列表/空态。

---

## 6. 比对使用建议

1. **基准尺寸**：设计屏为 412×915 dp（导出 824×1830 px）。比对前把模拟器/真机截图按宽度归一（例如缩放到 824 宽），再按 dp 对齐；不要按物理像素直接叠图。
2. **真机截图限制**：M6 记录已指出 `MainActivity` 有 `FLAG_SECURE`，小米 11 Ultra 的 adb 截图对应用内容为空 → 视觉比对以模拟器 Compose 截图 + 本目录设计图为准（见 `docs/阶段记录/M6-小米11Ultra全功能调试.md`）。
3. **优先比对**：带“有设计”判定的 15 个页面（今日、产检列表、产检详情、新建预约、资料列表/详情、用药记录、用药方案、健康、时间线、我的、备份、恢复预览、失败、云盘连接、欢迎页、建档）。
4. **风格一致性**：对 §5 的 24 项，用 `design-tokens.md` 的硬指标核对（背景/卡片/主色/文字层级/圆角/边距/按钮 364×52/记录行 364×72/标签 48 高/字体 Noto Sans SC），不要找“设计稿原图”。
5. **判定差异时记录**：屏名 + Figma nodeId（见 §2）+ 设计文件 + 设备截图 + 差异描述（间距/字号/颜色/文案/缺失元素）。设计稿内的固定演示日期（2026-10-17）与示例数据不是差异。
6. **第 20 屏缺失、06 板缺失**：不要用其它截图冒充；如评审必须看，先恢复 MCP 额度或重建画板。

---

## 7. 证据与复现

| 文件 | 内容 |
|---|---|
| `manifest.json` | 20 屏 → nodeId → 文件 → 尺寸/导出方式；失败与限流记录；06 板缺失证据；令牌来源 |
| `design-tokens.md` | 颜色/文字/圆角/间距/组件规格 + 来源节点与证据等级 |
| `_tools/verify_colors.py` → `_tools/color-evidence.json` | 19 张导出图的精确色值统计（验证 `#F7F8F3/#FFFFFF/#223C32/#5F7166/#356B53/#E7F0E6/#DDE4DA/#FAEAD8/#80582C` 实际渲染） |
| `_tools/measure_geometry.py` → `_tools/geometry-evidence.json` | 按钮 52 高/胶囊圆角、标签 48 高/圆角 12、记录行圆角 20 的像素测量 |
| `implementation-board/board-06-preview-2026-10-22-historical.png` | 2026-10-22 同步板的历史预览（**非本次现导**） |

重新获取缺失素材的命令入口：等 MCP 额度恢复后，对 `16:644` 调 `mcp__figma__download_assets(defaultFormat=png, defaultScale=2)`，再 `curl -f -L -o screens/20-RecoveryKey.png <export.url>`；并对 `12:26`/`14:738` 补 `get_variable_defs` 以确认 `apricot/amber/danger` 与 `孕迹/button` 样式。

---

## 8. 遗留与需 Lead/用户确认

1. **`06 · 当前实现` 板是否被有意删除**：本任务只读，无法判断；若需要继续用“实现同步板”做比对，需重建/恢复。
2. **第 20 屏 PNG 缺失**：仅因 MCP Starter 计划限流，非节点缺失。
3. **`danger`/`dangerSoft` 未被 20 屏使用**：实现侧删除/错误态的实际配色无从设计稿核对（Figma 也没有回收站页）。
4. **`孕迹/button` 文字样式参数未取到**：导出图文字校准约 16sp，但需 MCP 额度恢复后核实。
5. **设计稿与实现的“部分覆盖”判定是结构级判断**：具体视觉差异需由真实截图比对后填写，本文不代替比对结论。
