# Timeline 方案 A 重做：72dp 记录行 / 日期孕周 / 杏色备注卡 / 3 筛选 / 底部 CTA（2026-10-24）

- 对应共享任务：`task-12`（负责人 `ui-header`）；产品决策：`docs/design/决策备忘-Timeline信息架构.md` 方案 A（用户已确认），并按 Lead 2026-10-24 的逐像素补充口径执行（保留所有事件类型作为记录行，仅收敛筛选）。
- 设计基准：`docs/verification/2026-10-24-figma-ui-alignment/figma/screens/12-Timeline.png`（824×1830 = 2x）、`figma/design-tokens.md`（apricot `#FAEAD8` / amber `#80582C`，primary `#356B53`、muted `#5F7166`）。
- 设备：`emulator-5556`（API36 / 1080×2400 / 420dpi = 411.43×914.29 dp）；普通基线：浅色/竖屏/font_scale 1.0；极端配置用例按类要求切换（font 200% + 深色 + 横屏）。
- 边界：仅改 Timeline 相关产品代码 + 受影响的 androidTest（清单见 §5，含 Lead 单独授权的 `ExtremeLayoutMatrixTest` 两处 CTA 文案）；未改 `Theme.kt`/其他页面/主文档/阶段记录/Figma；未操作小米真机（11 Ultra 离线）。

---

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| 构建 | ✅ `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest` **BUILD SUCCESSFUL** |
| JVM 单测 | ✅ **342/342**（56 类，0 失败） |
| `NavigationExperienceTest` | ✅ **OK (24 tests)**（原 23 + 1 个迁移后的资料/原件用例；时间线深链断言按新 IA 重写，未放宽） |
| `UiSnapshotCoverageTest` | ✅ **OK (17 tests)**；时间线截图 `timeline.png` 已更新为新 IA |
| `UiSnapshotTest` | ✅ **OK (9 tests)** |
| `UiStateMatrixTest` | ✅ **OK (7 tests)**（时间线空态/CTA/筛选断言按新 IA 更新） |
| `ExtremeLayoutMatrixPagesTest` | ✅ **OK (6 tests)**（200% 字体 + 深色 + 横屏；p2 时间线长内容不截断审计通过） |
| 记录行 / 图标容器 | ✅ 截图实测 **3 行**记录行：72.38 / 71.62 / 72.38 dp × 363.43 dp；图标容器 **3×44.19 dp**（设计 3 行 72 dp、图标 44 dp） |
| 3 个筛选 | ✅ 实测 **1 选中（64.0×48.0 dp，primary 填充）+ 2 未选**，共 3 个（设计 3 个；旧实现 7 类） |
| 日期分组 + 孕周 | ✅ 左侧「10月24日」ink x 25.9–94.48 dp；右侧「孕22周 + 6天」为 primary `#356B53`（该区域 primary 像素 1,117，muted 0），x 309.33–387.05 dp（右缘=24 dp 页边距）；设计右侧 307.5–386.5 dp 同为 `#356B53` |
| 杏色备注卡 | ✅ `#FAEAD8` 卡片 363.43×108.95 dp @ (24.38, 469.33)（宽/边距同设计 364/24），`#80582C` 文本像素 9,453；设计 364×152（高度随正文行数变化） |
| 底部 CTA | ✅ 「写一条日常备注」363.43×52.57 dp @ y=825.9–878.48 dp（内容底 padding 12 dp）；无顶部 CTA（旧实现顶部 363.43×52.57 @ y=134.86） |
| 其他事件类型 | ✅ 资料/方案变更/服药/健康事件**不再有专属筛选**，但仍在「全部」中以记录行降级展示；「全部资料」改为独立列表页（DocumentScreen 语义等价路径） |

---

## 1. 产品改动（3 个文件）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `feature/TimelineScreens.kt`（主） | **TimelineScreen 重写**（方案 A）：<br>• 筛选收敛为 `TIMELINE_FILTERS = {"" 全部, "VISIT" 产检, "JOURNAL" 日常}`；<br>• 按事件日期分组：组头左侧 `M月d日`（中文月日），右侧孕周（按 EDD 优先、否则 LMP+280 推算，格式 `孕N周` / `孕N周 + N天`，颜色 primary——设计像素实测）；<br>• 记录行 `TimelineRecordRow`：白卡圆角 20、min 72 dp、44 dp soft 图标容器 + 16 sp Medium 标题 + 12 sp muted 副标题 + chevron，整行可点（`source-<key>`）；外层保留 `timeline-<key>`；<br>• JOURNAL 事件用**杏色备注卡** `JournalNoteCard`（`Apricot #FAEAD8` 底、`Amber #80582C` 标题/正文/说明，底部说明 `日常备注 · HH:mm` 或 `日常备注 · 只有日期，没有具体时间`，正文最多 3 行）；<br>• **底部固定主 CTA**「写一条日常备注」（`timeline-add` testTag，LazyColumn weight(1f) + 底部按钮）；<br>• 移除时间线内搜索框 `timeline-query`、说明段与旧顶部 CTA；空态保留旧文案（`还没有这类记录` + 说明）。<br>**新增 `AllDocumentsScreen`**：「全部资料」独立资料列表页（复用 TimelineSnapshot 的 DOCUMENT 事件，跨产检/药品照片，点击进入原始记录页查看/编辑元数据与原件）。<br>**新增 private helpers**：`DateGroupHeader`、`gestationalLabel`、`timelineTitle/timelineSubtitle`、`TIMELINE_TYPE_LABELS`、`TIMELINE_ROW_ICONS`、`TIMELINE_TAGLINE`。 |
| 2 | `feature/JournalNavigation.kt` | `JournalPage` 增加 `subtitle: String? = null` 参数（默认仍为「返回上一级」；时间线用设计 tagline 覆盖，避免与返回提示重复）；`TimelineRoute` 映射调整：`type=""` → 孕期时间线（subtitle=tagline）、`type="JOURNAL"` → 日常记录（默认副标题）、`type="DOCUMENT"` → 新「全部资料」页（AllDocumentsScreen，不再进 TimelineScreen）；`TimelineScreen` 改为接收当前 `PregnancyEntity?`（用于孕周计算），事件点击路由保持不变（VISIT→产检详情；PLAN→用药历史；其余→原始记录页）。 |
| 3 | `core/designsystem/Components.kt` | **未改**。Timeline 专用行/卡仅在本页面使用，按要求放在 `TimelineScreens.kt` 内部（`TimelineRecordRow` 为 internal 便于后续复用），未触碰既有组件语义。 |

> 设计取舍说明（按 task-12 spec 与 Lead 补充）：① 事件类型**全部保留**在「全部」，只是去掉专属筛选（设计「全部」中确实有测量/资料行）；② CTA 文案按设计用「写一条日常备注」，编辑器页标题/保存按钮仍是「写日常记录/保存日常记录」；③ 顶部返回键保留（P0-4），副标题改为设计 tagline。

---

## 2. 设计 → 实现对照（Figma 12-Timeline）

| 设计元素 | 设计（像素实测，2x→dp） | 实现（Pillow 实测） | 结论 |
|---|---|---|---|
| 页面头部 | 标题 28/42 + 副标题 `把一次次记录，连成自己的故事`（设计无返回箭头） | `JournalPageHeader` 28/42 + tagline；保留返回键（P0-4） | 字号/文案一致；返回键为产品保留项 |
| 筛选 | 1 选中（64×48 @x0=24，primary 底白字）+ 2 未选（61.7×46.5，白底 outline） | 1 选中 **64.0×48.0** @x0=24.38 + 2 未选 **61.7×46.5** | ✅ 3 项一致 |
| 日期组头 | 左 `10月17日`（ink x 25.5–106，y 197–214.5）+ 右 `孕18周 + 3天`（`#356B53`，x 307.5–386.5） | 左 `10月24日` x0=25.9；右 `孕22周 + 6天` primary，x0=309.71–386.29（右缘=24dp 边距） | ✅ 结构/颜色/对齐一致 |
| 记录行 | 白卡 364×72，图标容器 44×44，标题 16、副标题 12 muted、chevron | 行 **72.38** dp、**363.43** 宽；图标容器 **44.19** dp；titleMedium/bodySmall/chevron | ✅ |
| 杏色备注卡 | `#FAEAD8` 364×152，文字 `#80582C`，底部 `日常备注 · 只有日期，没有具体时间` | `#FAEAD8` **363.43×108.95**；`#80582C` 9,453 px；说明格式一致 | ✅（高度随正文行数） |
| 底部 CTA | 364×52 @ y 690–742（设计含 94dp 底导） | **363.43×52.57** @ y 825.9–878.48（内容底 12dp padding，无顶部 CTA） | ✅ |
| 搜索框/说明段 | 无 | 代码已移除（`timeline-query` 0 处、说明段 0 处） | ✅ |

---

## 3. before → after 结构变化

| 维度 | before（方案 A 前） | after（方案 A） |
|---|---|---|
| 主体 | 大卡片信息流（标题 20 + 时间/时区 + 依据 + 摘要 + “查看原始记录”文本按钮） | 72 dp 记录行 + 44 dp 图标容器（整行可点） |
| 日期 | ISO 日期 `2026-11-02` 单行，无孕周 | `10月24日` + 右侧孕周 `孕22周 + 6天` |
| 备注 | 卡片正文 | JOURNAL 专属杏色卡（`#FAEAD8`/`#80582C`） |
| 筛选 | 7 类（全部/产检/资料/方案变更/服药/健康/日常，横向滚动） | **3 类**（全部/产检/日常）；其他事件在「全部」降级为记录行 |
| CTA | 顶部 `写日常记录`（363.43×52.57 @ y=134.86） | **底部** `写一条日常备注`（363.43×52.57 @ y=825.9–878.48） |
| 搜索/说明 | 搜索框 + 说明段 | 移除（设计无） |
| 「全部资料」 | TimelineScreen(type=DOCUMENT) 的时间线过滤视图 | 独立 `AllDocumentsScreen` 资料列表（跨产检/药品照片，点击进原始记录页） |

> 视觉量化数据：before 截图无 `#FAEAD8`/`#80582C`（0 px）、无 44 dp 图标容器、卡片高度 250.67/183.62 dp；after 为上述 §0 数值。

---

## 4. 测试改动清单（Lead 已授权；逐条说明，未放宽任何产品断言）

| 文件 | 改动 | 理由 |
|---|---|---|
| `NavigationExperienceTest.kt` | ① `journalDraftSourceEditDeleteUndoAndTimelineFilterSurviveBack`：时间线 CTA 文案 `写日常记录`→`写一条日常备注`（2 处，编辑器页文案不变）；② 原 `timelineOpensEveryOriginalSourceAndReturnsToSelectedType` **重写**为 `timelineShowsVisitAndJournalRecordsWithThreeFilters`：断言 tagline、3 个筛选（旧 4 类筛选不存在）、日期孕周（`孕22周 + 6天`）、VISIT 行打开产检详情、产检筛选下无日常行、日常筛选下杏色卡打开原始记录（症状/正文）、底部 CTA 打开编辑器；③ **新增** `recordsHubAllDocumentsKeepsMetadataEditAndOriginalPreview`：记录枢纽→全部资料→资料行→原始记录页完成「资料元数据编辑（含外部更新冲突提示/重开保存）+ 查看原件」覆盖。 | 按新 IA 重写深链；移除的 PLAN/INTAKE/MEASUREMENT/DOCUMENT 时间线深链**不是覆盖丢失**：这些事件仍在「全部」可见（代码与数据层未删），各自的原始记录/编辑能力由原有模块用例与新增的「全部资料」用例覆盖（`DocumentImportTest`/`ExportUiTest`/`Measurement*`/`Intake*`/`Medication*` 等）。 |
| `ExtremeLayoutMatrixPagesTest.kt` | p2 时间线：补 EDD 使孕周可见；底部 CTA 断言改为「写一条日常备注」；移除旧「时间未记录/查看原始记录」文本断言，改为日期组头孕周 + 杏色卡说明的 `assertNoTruncation`；保留 3 轮 `scrollAndAudit` 不截断审计与截图。 | 新布局没有这些旧文案；不截断断言保留且覆盖新元素。 |
| `UiStateMatrixTest.kt` | s2 时间线空态：CTA 断言改为「写一条日常备注」（断言可点击）+ 新增「日常」筛选选中检查；空态文案断言不变。 | 新 IA 的 CTA/筛选。 |
| `UiSnapshotCoverageTest.kt` | `snap16Timeline`：适配 `TimelineScreen(pregnancy, …)` 新签名 + 补 EDD + `showPage` 支持 subtitle（传设计 tagline）；补种测量记录与资料（让截图覆盖设计示例中的测量行/资料行）。 | 截图需反映新 IA/设计对照。 |
| `ExtremeLayoutMatrixTest.kt`（**Lead 单独授权**） | t7/t8 中时间线页 CTA 点击文案 `写日常记录`→`写一条日常备注`（2 处）；因 CTA 固定在底部、无需 `performScrollTo`，同时去掉该 scroll 调用（避免“无滚动祖先”异常）；t7 截图 testTag「写日常记录」保持不变。 | 不修改断言语义、不删步骤，仅适配新 IA。 |

> 未改动：`UiSnapshotTest.kt`（不涉及 Timeline）、`DocumentImportTest`/`JournalEntryRepositoryTest`（只测 TimelineRules 数据层，不受 UI IA 影响）。

---

## 5. 测试与构建结果（emulator-5556）

| 测试类 | 结果 | Time |
|---|---|---|
| `NavigationExperienceTest`（24 tests） | **OK** | 见 `_tools/instrument-navigation-light.log` |
| `UiStateMatrixTest`（7 tests） | **OK** | 见 `_tools/instrument-uistatematrix-light.log` |
| `UiSnapshotCoverageTest`（17 tests） | **OK** | 见 `_tools/instrument-coverage-light.log` |
| `UiSnapshotTest`（9 tests） | **OK** | 见 `_tools/instrument-supplementary-light.log` |
| `ExtremeLayoutMatrixPagesTest`（6 tests，200% 字体+深色+横屏） | **OK** | 见 `_tools/instrument-extreme-pages.log` |
| JVM `testDebugUnitTest` | **342/342** | `_tools/unit-test-summary.txt` |

> 构建/APK：NavigationExperienceTest/UiStateMatrixTest/UiSnapshotTest/ExtremeLayoutMatrixPagesTest 运行于 12:08 的 app APK；之后仅修正了「全部资料」记录行副标题在摘要已含「已关联本次产检」时重复拼接的问题（1 行字符串条件），并用 12:24 终版 APK 重跑 UiSnapshotCoverageTest **OK (17)** 生成最终截图；其余类断言不涉及该文案，行为等价。_tools/apk-info.txt 记录终版 sha256。

> 说明：首轮 `NavigationExperienceTest` 因两处测试适配问题（原始记录页与时间线卡同时在语义树导致 `assertExists` 多节点、全部资料返回层级断言顺序）与一处与本次改动无关的云盘校验用例**间歇失败**，修复测试适配后重跑 **OK (24)**；云盘用例单跑复跑均通过（`_tools/smoke-cloud-single.log`），属既有用例的偶发（该用例断言的是他人已修复的 Bug #4 文案）。

---

## 6. 证据与产物

```
docs/verification/2026-10-24-timeline-ia-a/
├─ README.md                          本文件
├─ measure_timeline.py                视觉量化脚本（行高/图标/筛选/杏色/CTA/日期孕周 + 设计基准）
├─ measurements-timeline.json         逐图量化结果
├─ after/
│  ├─ coverage/timeline.png           UiSnapshotCoverageTest 时间线（1080×2400，含 header tagline/3 筛选/日期孕周/记录行/杏色卡/底部 CTA）
│  ├─ navigation/c04-timeline.png     NavigationExperienceTest 真实 App 导航时间线截图
│  ├─ navigation/c04-journal.png      日常记录路由截图
│  ├─ coverage/ (22) / supplementary/ (9)  其余快照
│  └─ extreme-pages/p2-timeline.png   200%字体+深色+横屏时间线（不截断审计截图）
└─ _tools/
   ├─ build.log / build-test-apk.log / unit-test-summary.txt / apk-info.txt / install.log
   ├─ instrument-*.log                5 类 UI 测试日志
   ├─ smoke-cloud-single.log          云盘用例单跑复跑
   ├─ measurements-timeline.txt       量化控制台摘要
   ├─ device-baseline-before/after.txt、device-files-*.txt
   └─ emulator-baseline-restored.txt  跑后基线恢复记录
```

---

## 7. 限制与剩余风险

1. **模拟器非真机**：结论来自 API36 模拟器；真机（小米 11 Ultra 离线）字体栅格/系统栏/进度可能不同，需真机恢复后复核视觉。
2. **设计稿示例数据不算差异**：设计 12 使用示例日期/医院/图片数；实现截图使用合成数据，行高/边距/颜色/组件一致即达对齐，行文字内容不同不算差异。
3. **顶部返回键保留**：设计无返回箭头，按 P0-4 保留返回键（导航/测试依赖），副标题已改为设计 tagline，不与「返回上一级」重复。
4. **图标语义为近似映射**：设计行图标（体重/文件/日历）在仓库 SVG 资产中没有一一命名记录，实现按可用资产近似（VISIT=122c6、DOCUMENT/JOURNAL/PLAN/INTAKE=86969、MEASUREMENT=42641）；尺寸/容器与设计一致。
5. **杏色卡高度随正文行数变化**（设计样本 152 dp，本实现单行正文为 108.95 dp；padding/圆角/配色/说明格式一致）。
6. **极端配置仅 p2 时间线用例覆盖**；全量矩阵由 Lead 在完成后统一执行（本任务已跑 `ExtremeLayoutMatrixPagesTest` 整类 6/6）。
7. **深色主题**：时间线深色仅由极端配置用例 p2 截图（p2-timeline.png）覆盖，未做深色逐项像素量化。
8. 本任务未改 `Theme.kt`；`Apricot/Amber` 复用 P0-2 已加入的主题常量。


---

## 8. 最终量化明细（`measurements-timeline.json`，2026-10-24 12:25）

| 指标 | after（coverage/timeline.png） | 设计 12-Timeline | before（p1-polish/light/coverage/timeline.png） |
|---|---|---|---|
| 记录行 | **3 行**：72.38 / 71.62 / 72.38 dp × 363.43 dp | 3 行 72 dp × 364 dp | 无 72 dp 行（旧大卡 250.67 / 183.62 dp） |
| 图标容器 | **3 个 44.19×44.19 dp** | 3 个 44×44 | 无 |
| 杏色卡 | **363.43×108.95 dp**（`#FAEAD8` 252,291 px / `#80582C` 9,453 px） | 364×152（`#FAEAD8` / `#80582C`） | 0 px（无杏色卡） |
| 筛选 | 1 选中 **64.0×48.0** @x0=24.38 + 2 未选 **61.71×46.48** | 1 选中 64×48 @24 + 2 未选 62×46 | 1 选中 64×48.76 + 3 个可见未选（共 7 类，横向滚动） |
| 日期组头 | 左 ink 25.9–94.48 dp；右孕周 primary `#356B53`，x 309.33–387.05 dp（primary_px=1117、muted=0） | 左 26–105.5；右 `#356B53` x 307.5–386.5 dp | 无孕周（右侧仅筛选条带文字），日期为 ISO 文本 |
| 底部 CTA | **363.43×52.57** @ y 825.9–878.48 | 364×52 @ y 690–742（含 94 dp 底导） | 无（旧实现为顶部 CTA 363.43×52.57 @ y=134.86） |
| 顶部 CTA | 0 | 0 | 1（363.43×52.57 @ y=134.86） |

`after/navigation/c04-timeline.png`（真实 App 导航、日常筛选态）：杏色卡 363.43×108.95 dp、3 筛选（「日常」选中）、底部 CTA 363.43×51.81 dp，无顶部 CTA；用于证明真实路由下同样生效。

> 说明：`after/coverage/timeline.png` 由 `UiSnapshotCoverageTest#snap16Timeline` 生成（补种产检/日常/测量/资料事件 + EDD），覆盖设计「全部」视图中的记录行与杏色卡形态；`after/extreme-pages/p2-timeline.png` 为 200% 字体 + 深色 + 横屏下的时间线不截断审计截图。

### 运行间偶发说明

- 最终一轮覆盖率（含补种增强）首次运行时 `snap09DrugCatalog` 在 20 s 等待后超时（该类注释已记录该用例在模拟器负载下 Room Flow 首帧偶发慢；与时间线改动无关），随后单独重跑 `UiSnapshotCoverageTest` **OK (17 tests)** 并重新拉取截图；此前一轮完整运行同样 **OK (17)**。
- `NavigationExperienceTest` 首轮因本文测试适配问题（见 §5 末尾说明）出现 3 个失败，修正后整类 **OK (24 tests)**；其中云盘校验用例为既有偶发，单跑复跑通过（`_tools/smoke-cloud-single.log`）。


---

## 9. Lead 全量矩阵复核与测试同步修复（2026-10-24）

- 首轮全量矩阵（368 tests）：**367 通过 / 1 失败**，失败为 `NavigationExperienceTest#measurementDraftTrendDetailDeleteAndUndoKeepExactValue`（`Failed to perform text input … TestTag = 'measurement-value'` 未找到）：全量高负载下测量编辑器首帧稍慢，测试在字段组合前就输入；单独跑该类 24/24 通过，属**测试同步脆弱**而非产品缺陷。
- Lead 修复（仅测试同步，不放宽断言）：进入测量编辑器后增加 `ui.waitUntil(10_000) { onAllNodesWithTag("measurement-value") 非空 }` 再 `performTextInput`。
- 验证：单用例 **OK (1 test)**；随后重跑全量矩阵（结果见下文/`lead-full-matrix-round12b.log`）。
- 证据：`_tools/lead-full-matrix-round12.log`（首轮 367/368 + 失败栈）、`_tools/lead-full-matrix-round12b.log`（修复后重跑）、`_tools/lead-single-measurement-round12.log`（单用例）。
- 第二轮全量矩阵（368 tests）：**367 通过 / 1 失败**，失败为 `cloudConnectionValidatesBeforeNetworkAndClearsUnsavedPasswordOnReturn`（输入 `bad:name` 后等待/断言「请检查账号…」时节点尚未出现）；单跑通过，属同类**测试同步脆弱**（全量负载 + IME/滚动时机）。
- 同步修复（Cloud 用例，不放宽断言）：点击「测试连接」后先 `waitUntil` 等待「请填写账号」节点；输入 `bad:name` 后先 `waitUntil` 文本出现，再点击并 `waitUntil` 等待「请检查账号，不能包含冒号或控制字符」节点，再断言。
- 验证：单用例 **OK (1 test)**；随后第三次全量矩阵（结果见 `lead-full-matrix-round12c.log`）。
- **第三轮全量矩阵（最终）：OK (368 tests)，0 失败，EXIT=0**（`_tools/lead-full-matrix-round12c.log`）。两处测试同步修复后（measurement 等待字段组合、cloud 等待校验文案节点）整类/全量稳定通过；产品断言未放宽。
