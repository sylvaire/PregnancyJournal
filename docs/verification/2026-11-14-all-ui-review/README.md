# 全界面与组件检查清单

日期：2026-11-14。状态：本轮逐项检查与修复完成。覆盖29个路由、23个含Compose的源文件及MainActivity临时界面；代码阅读、截图与交互验证分别记录。真机及完整无障碍发布验收仍保留。

## 导航页面

| 页面 | 代码检查 | 本轮模拟器视觉/交互证据与结论 |
|---|---|---|
| TodayRoute | 已查日期、待办、资料与入口 | final-copy/today.png；补充日期直达编辑，过去/今天/未来预约文案与详情回调通过；大字体快捷入口自动调整列数；安排信息重复仍可精简 |
| VisitsRoute | 已查列表、筛选、日历与底部动作 | main-pages/02-visits.png、component-after/calendar-large.png；完整日期与选中语义通过；底部遮挡已复现并修复，普通/200%字号点击预约均正确进入详情 |
| ProfilesRoute | 已查导航及可用条件 | main-pages/04-profiles.png；建议按记录/备份/设置分组缩短查找 |
| RecordsRoute | 已查分类/搜索/无匹配 | main-pages/03-records-hub.png；搜索明确限定分类；保留详细入口 |
| TimelineRoute | 已查加载/失败/空/筛选/日期分组 | screens-final、screens-light200/timeline.png；可区分未来预约与已发生事件 |
| TimelineSourceRoute | 已查原始内容、展开及动作 | 两种主题的timeline-source-detail.png；症状省略可通过原始内容查看，不能当永久丢失 |
| JournalEntryEditorRoute | 已查可空字段、草稿及保存 | 两种主题的journal-entry-editor.png；有内容后隐藏重复空内容提示 |
| PregnancyManagerRoute | 已查当前档案/切换/编辑/结束/删除 | main-pages/figma-sync/04b-pregnancy-management.png；当前与列表重复展示可继续精简 |
| TrashRoute | 已查两类资料/筛选/恢复条件/清理确认 | 两种主题的trash-empty.png、component-after/trash-tabs-large.png；200%标签整项换行通过 |
| MedicationsRoute | 已查空态、状态变更与版本入口 | main-pages/05-medications.png、component-after/medication-actions-large.png；修改/历史/暂停不再拆成竖排 |
| DrugCatalogRoute | 已查查询、选择及删除条件 | remaining-deep与remaining-light200/drug-catalog-*.png；12药品首尾选择及就近操作通过 |
| DrugEditorRoute | 已查字段约束/错误/保存/草稿 | 两种主题的drug-editor.png；先保存再添加照片说明清晰 |
| DrugPhotosRoute | 已查图片限制/导入/失败/照片操作/回收站 | remaining-deep与remaining-light200/drug-photos.png、drug-photo-import-options.png；不提供PDF导入，图像预览可见 |
| ReminderSettingsRoute | 已查授权/后台/规则/自定义/刷新 | rechecked-deep及22项大字号运行；目录跳转断言通过；文案区分规则已设与权限未允 |
| CloudConnectionRoute | 已查加载/校验/保存/密码/目录/备份/清理 | 两种主题cloud-connection及子状态；连接状态表述修正；输入后校验曾超时，测试复用既有键盘稳定等待，普通/200%字号复验通过 |
| RecoveryRoute | 已查生成/核对/就绪/副本/旧资料 | 两种主题local-backup、remaining-*/recovery-verify.png与safety-copy-reclaim.png；仅合成状态，不写出恢复密钥 |
| ExportRoute | 已查范围/忙碌/确认/结果/取消 | 两种主题export-screen.png；补充ZIP/JSON与PDF用途说明 |
| MedicationEditorRoute | 已查药品选择/频次/日期/校验/保存 | remaining-*/medication-editor-{top,bottom}.png；两种配置保存操作可滚动到达 |
| MedicationHistoryRoute | 已查版本、当时药品快照与定位 | remaining-*/medication-history.png；日期、剂量、单位与版本可读；历史较长时继续滚动 |
| IntakesRoute | 已查按日计划/历史/补记/修改/删除 | main-pages/figma-sync/06-intakes.png、component-after/intakes-large.png；顶部短动作整项换行；重复补记入口待整合 |
| MeasurementsRoute | 已查加载/失败/空/分组趋势/详情/指标管理 | main-pages/07-measurements.png、remaining-*/measurement-trend.png及metric-definition.png；错误不再同时称无数据，专项已通过 |
| MeasurementEditorRoute | 已查原始读数/单位/场景/时区/保存 | 两种主题measurement-editor.png；建议时间选择器辅助输入 |
| IntakeEditorRoute | 已查计划/补记/跳过/剂量/时区/保存 | remaining-*/intake-editor-{top,bottom}.png；200%可滚动到保存，不据此宣称所有键盘状态通过 |
| PregnancyHistoryRoute | 已查筛选/无匹配/当前与历史详情 | 两种主题dating-history.png；列表可增加修改时间，查询框造型待统一 |
| PregnancyEditorRoute | 已查名称/日期依据/可空/草稿 | 两种主题pregnancy-editor.png；清除日期动作分别命名，降低混淆 |
| VisitEditorRoute | 已查日期/待定时间/可空字段/保存 | 两种主题visit-editor.png；当前配置未发现重叠，输入键盘由流程用例继续验证 |
| VisitDetailRoute | 已查缺失/待进行/已就诊/取消/提醒 | 两种主题visit-detail.png；预约、实际就诊与提醒清楚区分；删除和取消须二次确认 |
| VisitDocumentsRoute | 已查资料/队列/错误/搜索/分类/更多/元信息/关联 | main-pages/08-visit-documents.png、UiStateMatrixTest；未将静态单张图当全流程通过 |
| VisitRecordRoute | 已查实际字段/关联预约/保存 | 两种主题actual-visit-editor.png；保留原预约信息，建议统一时间输入控件 |

`remaining-*`指`remaining-deep/`（深色1.0）与`remaining-light200/`（浅色200%）。表中「已查」表示已阅读相应布局与分支、保留结论，不代表每个分支都已执行真机测试。图片中的合成药品和数值只用于布局；不作医学判断。全部资料列表、欢迎页、安全与解锁页、原件分享、备份恢复等非单独路由页面在下表列出。

## 其他界面及公共组件

以下清单覆盖 feature 与 designsystem 下所有含 Composable 的文件；各文件中的弹窗、局部组件和状态也纳入检查。

| 源文件 | 检查状态 |
|---|---|
| CloudConnectionScreen.kt | 已查连接/目录弹窗/备份/清理；状态文案修正，技术细节可折叠 |
| DocumentPreview.kt | 已查图片生命周期/加载失败/手势/替换确认；两种配置整屏图核对系统栏与标题 |
| DocumentScreen.kt | 已查导入选择、进度、失败重试、资料元信息、关联、替换/删除/分享确认；行内动作在大字号仍可精简 |
| DrugScreens.kt | 已查目录与编辑；12药品就近动作与选择反馈通过 |
| ExportScreen.kt | 已查选择范围/明文确认/统计/导出/取消；补充格式说明 |
| IntakeScreens.kt | 已查首页摘要、按日/历史与编辑；操作换行通过，重复入口留作建议 |
| JournalApp.kt | 已查欢迎、首页、孕期、日期/时间字段、产检详情与确认；日期动作改善；首页快捷入口按实际文字宽度自动调整列数，200%短文案完整一行 |
| JournalNavigation.kt | 已查29路由、返回、根导航、草稿、保存/错误/撤销反馈与过渡；52项流程首轮50通过/2失败，日历产品遮挡及云盘测试同步分别修正，失败两项普通/200%字号复验均通过 |
| MeasurementScreens.kt | 已查原始/趋势/详情/定义弹窗/编辑；错误空态互斥专项通过，定义弹窗支持滚动 |
| MedicationScreens.kt | 已查列表状态/方案编辑/历史；大字号短操作整项换行 |
| PdfDocumentView.kt | 已查会话、加载/失败/返回、分页、跳页、缩放；两种配置PDF实渲染可见 |
| PrenatalScreens.kt | 已查日历、预约时间输入、实际就诊及依据历史；新建按钮单独占位，日历列表末项遮挡已修复，普通/200%字号均通过 |
| RecoveryScreen.kt | 已查各阶段与旧资料回收确认；标题去重，安全操作保护条件保留 |
| ReminderSettingsScreen.kt | 已查授权/厂商设置/规则/编辑器/删除/刷新；深色系统图标可见，取消绝对可靠性表述 |
| RestoreScreen.kt | 已查选择/密钥/云端/校验/确认/恢复/完成/错误；8个补充分支截图，整体替换提示清楚；文件名上下文待补 |
| SafetyCopiesScreen.kt | 已查读取/空/忙碌/失败/导出/删除确认；合成副本图核对两种配置 |
| SecurityScreen.kt | 已查应用锁、超时、严格模式、解锁忙碌与失败；关闭动作降级、术语简化，完整认证依赖系统 |
| TimelineScreens.kt | 已查分类/时间线/全部资料/日常编辑与来源；未来事件分组与趋势轴标签留作建议 |
| TrashScreen.kt | 已查影响核对、加载失败、恢复依赖与各类永久删除；长影响范围支持滚动 |
| Components.kt | 已查图标、按钮/危险按钮、开关、标签、卡、记录行、页头与空态；触摸高度和禁用标签修复 |
| Components2.kt | 已查次按钮/文字动作/字段/复选/加载/错误/分隔线/全屏页头/按压/键值；长值、标题、禁用危险动作修复 |
| Theme.kt | 已查字阶、明暗配色及备注卡；两种配置截图，未以肉眼代替全部对比度测量 |
| core/share/OriginalShareDialog.kt | 补入原清单遗漏；已查明文说明、临时授权和忙碌禁用；说明置于文件名前，内容支持滚动，长文件名200%专项及最终截图通过 |
| MainActivity.kt中的临时界面 | 已查系统认证入口、安全/恢复全屏、通知确认、文件选择恢复/中断及导出进度提示；真实外部选择器/厂商设置不等于模拟器截图已验收 |

## 首批代码风险（尚未实机复现）

1. Components2.kt / JournalKeyValue：值没有宽度约束，先测量的长医院名等可能占满行宽，将 weight(1f) 标签压缩至不可见。需验证长文本、大字体，并考虑窄屏堆叠。
2. Components2.kt / JournalTextAction：danger 分支优先于 enabled，禁用的危险动作仍呈现完整错误色，视觉上可能像可点击。需与普通禁用动作对照。
3. Components2.kt / JournalFullScreenHeader：标题与副标题单行省略，长标题和大字体可能丢失任务信息。需逐个全屏弹窗截图检查。

## 验收条件

每个页面及公共组件逐项记录证据与结论；覆盖正常、空、加载、失败、长文本、大字体、深色及适用的键盘/返回/确认状态。无法验证的项目明确标记，不宣称全量完成。

## 2026-11-14 第二批代码检查
已完整阅读 Components.kt、Components2.kt、Theme.kt，及产检列表/日历主体；尚未完成运行截图验证。

- 按钮、次按钮、危险按钮、复选行和标签采用最小高度而非固定高度，可随文本增加高度；RecordRow 的正文分配 weight，图标不抢占文字区。仍需大字体/深色截图确认。
- 新增风险 C04：PageHeader 的可点击副标题直接使用 Text.clickable，没有最小触摸高度及动作角色；首页切换孕期入口较难点击。
- 新增风险 C05：JournalTag 禁用且选中时沿用 onPrimary 的 38% 透明色，浅色背景上可能几乎看不清；需像素对照验证。
- 新增风险 P01：产检日历日期单元固定 height(36.dp)，内部包含日期文字与 4dp 圆点。200% 字体下有裁切风险；相邻日期触摸目标也需验证。
- 新增风险 P02：日期按钮只包含日数字，没有完整年月日的语义，也未显式提供 selected 语义；读屏可能无法说明选中的日期。
- 新增风险 P03：日历页标题写「按日期查看预约与实际就诊」，但说明明确按预约日期、圆点匹配 scheduledDate。应统一用户预期，或提供日期依据切换，不能把预约日期当实际就诊日期。
- 现有 UiSurfaceSnapshotTest 覆盖删除弹窗、日期选择器、撤销提示、分类菜单；UiSnapshotCoverageTest 提供页面截图入口，可用于本轮重新采集，历史截图不作为本轮验收证据。
- 设备检查：当前连接一台真机，模拟器未运行。下一步优先使用专用模拟器测试数据，避免覆盖真机已有资料。

## 2026-11-14 本轮设备采集启动
- 专用模拟器 emulator-5554 / PJ_API36_16K 已启动，sys.boot_completed=1；未向真机安装。
- 当前工作区以 -PvisualVerification=true 构建 assembleDebug/assembleDebugAndroidTest 成功（36s）；两个 APK 安装 Success。
- 正在执行 UiSnapshotCoverageTest、UiSurfaceSnapshotTest（统一执行会话 37923）。下一轮先轮询该会话，不重复启动。
- 待完成后从应用 external files 的 verification/figma-ui-alignment 读取截图并逐张检查；测试通过本身不等于视觉通过。
- 新增待复现 D01：DrugCatalogScreen 点击药品仅 selectedId=drug.id，管理区置于全部药品列表之后；长列表时选择反馈和操作可能不在视口内，且列表行没有选中态。
- 新增待复现 H01：MeasurementScreens 在 failure 不为空且 records 为空时，仍可能同时显示「还没有测量记录」；错误态与空态条件需互斥。

## 首批截图视觉检查（本轮实时采集）
已核对设备文件修改时间为本轮 2026-11-14 06:47–06:48 UTC（北京时间14:47–14:48）。设备字号1.0、深色开启；因此这批是深色基线，不能按测试注释称为浅色基线。

| 页面/截图 | 可见结论 | 优化项与边界 |
|---|---|---|
| screens/drug-catalog.png | 单药品下信息和操作完整可见，深色层次正常 | D01长列表问题尚未复现；删除入口与普通查看入口视觉相同，可考虑危险动作区分 |
| screens/measurement-editor.png | 输入框、单位与保存动作可见，无当前字号截断 | H02：时间要求时分秒，对日常手工录入偏繁琐；可提供时间选择器并允许默认秒值，保留原始精度 |
| screens/cloud-connection.png | 上传按钮靠前，连接信息分组清楚 | B01：「加密备份已准备好」与下面「连接设置已保存」含义接近但并不等价；应明确是否已备份、何时备份，不让“准备好”替代结果状态 |
| screens/local-backup.png | 导出与恢复主次明确，密钥提醒可见 | B02：页标题与大卡标题重复；可改卡标题为「备份准备就绪」，让密钥状态与下一步更直接。安全副本与主动导出备份需持续区分 |

这四张截图已实际打开检查。其余已采集截图未逐张阅读，不标记通过。截图使用隔离测试数据，无真实用户医疗资料。

## 第二批视觉检查（深色1.0，六个页面状态与四个临时表面）
| 截图 | 检查结论/优化建议 |
|---|---|
| screens-final/reminder-settings.png | R01：顶部通知未允许，下方产检/用药仍写已开启，易误解为能收到通知；建议写「已设置，待开启通知」。R02：「调度检查」改为「未来7天提醒」。目录与详细设置重复拉长页面。 |
| screens-final/custom-reminder-editor.png | 字段、重复规则、预览与保存完整。R03：深色背景上的状态栏图标仍黑色，几乎不可见；需核对生产全屏弹窗窗口系统栏设置。 |
| screens-final/restore-wizard.png | 替换而非合并的说明清晰，双来源入口明确；可增加选择→校验→预览→恢复步骤提示，降低不确定性。 |
| screens-final/restore-wizard-key.png | B03：输入密钥时未显示已选备份文件名，用户难确认选了哪份；建议保留文件名与大小。禁用校验按钮可辨认。 |
| screens-final/pregnancy-editor.png | 字段/日期依据说明完整；两处「清除日期」文案相同，建议分别标明预产期/末次月经日期，改善读屏与防误操作。 |
| screens-final/visit-editor.png | 当前字号布局与主要动作正常；可选字段与草稿提示明确。未验证键盘及错误态。 |
| dialogs/surface-alert-dialog.png | 说明保留时间与恢复途径，按钮完整；无当前配置裁切。 |
| dialogs/surface-date-picker.png | L01：中文应用内出现英文 Select date、月份与星期，取消/确定却是中文；需统一应用语言策略并验证中文系统。 |
| dialogs/surface-snackbar.png | 撤销提示与底部导航没有重叠，可见且易辨认；时长与读屏播报待检查。 |
| dialogs/surface-dropdown-menu.png | 分类选项全部可见；当前选择无勾选标记，可增加选中反馈。底层资料搜索框为直角，与其他页面圆角字段不一致。 |

已启动浅色、200%字号页面采集，运行会话67806；必须先轮询，勿重复启动。完成后另存 screenshots-light200，避免覆盖深色证据。最终恢复模拟器字号1.0与原深色设置。

## 第三批视觉检查及大字体结果
本轮打开 screens-final 中14张剩余主要截图：
- security-settings：S01 关闭应用锁与立即锁定都是强主按钮，建议弱化关闭动作；「设备凭据」可改为「手机锁屏密码」，严格模式解释需明确实际影响。
- vault-locked：S02 深色卡片配白色页面背景，明显与其他深色页不一致；需核对测试宿主背景与真实入口，暂不定为生产缺陷。
- export-screen：E01「结构化数据包」缺少格式与用途解释，可加「用于自行整理数据」；确认未勾选时禁用按钮可辨认。
- timeline：当前字号卡片与日期分组清楚；未来预约排在最新历史前，建议区分即将到来与已发生。
- timeline-source-detail：日常详情查看/修改/删除可辨认；非交互的症状卡使用 RecordRow 两行省略，长症状需验证完整内容入口。
- visit-detail：预约、提醒、实际就诊分组清楚；当前视口底部删除部分在折叠区之外，需滚动可达验证，不能仅凭截图判断裁切。
- dating-history：当前/历史可辨认；搜索框直角与表单圆角不一致；修改时间未在列表显式展示，可辅助识别历史版本。
- actual-visit-editor：当前字号字段、关联预约及保存均可见；实际时间仍为手填，归入 H02 时间控件统一建议。
- journal-entry-editor：内容已填仍显示大块「至少填一项」提示，建议空内容时再提示，减少冗余。
- drug-editor：必填和选填区分清楚；保存后再添加照片提示清晰，当前字号无明显截断。
- trash-empty：空态明确；「全局」是内部范围术语，可改「回收站」并用说明表达所有记录。
- reminder-settings-custom：调度解释包含启动/维护/重建等实现细节，建议移入帮助，主区只展示最近更新与刷新。
- cloud-connection-cleanup：快照/提交/对象/孤立与指数退避等技术词过多，建议用户主文案说明保留了哪些备份、清理结果、失败会自动重试，技术详情折叠。
- cloud-connection-backup：能力检查条目正常对齐；保存连接禁用原因可更明确。

浅色200%采集：20项运行、1失败，66.721秒。失败 snap01ReminderSettings 点击「调度检查」后 reminder-rebuild 不在视口；需判断分组滚动定位还是测试仅适用1.0，未放宽断言。screens-light200 已取回；其中 reminder-settings-custom 未重新生成，属于旧深色截图，不可作为大字体证据。其他截图仍待逐张检视。已恢复模拟器font_scale=1.0、night=yes。会话67806已结束，无运行中测试。

## M6-UX-ALL-07 定位进展
提醒页使用 BringIntoViewRequester 定位整块调度卡，测试要求卡内按钮可见。已在原断言前新增 reminder-schedule-after-jump 截图，未改变断言。测试包构建成功，单例复现执行中。覆盖总表已同步已查看的页面，剩余首页/我的/记录入口/孕期管理/用药/服药/健康列表/包装照片仍须新截图。

## M6-UX-ALL-08 失败原因修正
已打开 reminder-schedule-after-jump.png：视口仍在提醒设置顶部，调度入口在屏幕外。原测试直接 performClick 未先滚动到入口，不能据此证明产品跳转失败。测试改为 performScrollTo().assertIsDisplayed().performClick()，保留 reminder-rebuild 可见性断言；新增失败前截图保留。构建首次缓存移动失败、同命令重试成功，未清理缓存。
当前执行会话55130：先200%浅色提醒单例，再恢复字号1.0/深色，然后执行 UiSnapshotTest 补充主页面截图。下一轮必须先轮询此会话。提醒复验图片存 reminder-jump-visible-entry.png；主页面输出在设备 verification/figma-sync，完成后取回。

## M6-UX-ALL-09 实际复现、修复与回归

会话55130在续接时已不可查询，模拟器也已退出，不将整类采集记为成功。重启专用模拟器后补跑孕期管理与服药记录，`OK (2 tests)` / 15.38s；新图在 `main-pages/figma-sync/04b-pregnancy-management.png` 与 `06-intakes.png`。已逐张检查全部10张主要入口截图及23张有效浅色200%页面截图。`screens-light200/reminder-settings-custom.png` 是旧图，仍排除；`export-screen-disabled.png` 是重复状态。

| 问题 | 复现证据 | 实施与验证 |
|---|---|---|
| C01 长医院名挤掉「医院」标签 | component-before/key-value-long.png；基线断言失败 | 宽度不足时标签和值上下排列；完整文本和标签均显示，回归通过 |
| C03 预览标题被操作挤出视口 | component-before/preview-header-large.png；基线标题不可见 | 返回与标题在首行，操作另行并可换行；标题与三项操作可见、无省略 |
| C04 切换孕期入口高度不足 | 基线实测34.67dp，要求至少48dp | 提供48dp最小高度、按钮角色及动作说明；点击区域断言通过 |
| P01/P02 日期高度、日期语义及选中反馈 | 基线缺少完整年月日描述 | 日期最小高度48dp，完整日期/星期、安排与选中语义；选择/取消选择回归通过。未据此宣称TalkBack整体验收 |
| 大字体短按钮竖排 | component-before/intakes-large.png、medication-actions-large.png，浅色回收站截图 | 服药入口、方案动作和回收站标签支持整项换行；三个专项均通过，并断言短文案保持一行 |
| C02/C05 禁用视觉优先级 | 代码条件核对 | 禁用危险文字使用禁用色；禁用选中标签改为禁用底色/文字色；像素截图待后续补查 |
| H01 健康指标错误与空态同时出现 | 代码条件核对 | 空态只在加载结束且无错误时显示；错误分支仍需专门注入验证 |
| B01/R01/R02 状态表达 | 已查看云盘与提醒页截图 | 「云盘连接已保存」区分连接与备份；通知未允许时写「已设置 · 待开启通知权限」；「调度检查」改「未来7天提醒」 |
| R03 深色全屏系统栏图标 | 已查看自定义提醒编辑器深色截图 | 提醒编辑器与资料预览按明暗主题设置系统栏图标；修改后整屏截图待补 |

新增 `UiReviewRegressionTest`。基线6项中4失败、2通过，见 `component-baseline-result.txt`；修复后增加回收站并加强短按钮换行断言，**7项全部通过 / 35.946s**，见 `component-fixed-result.txt`。修复后6张截图位于 `component-after/`，全部实际打开复核。日历月份切换仍被挤成两行，追加整项换行修改，待下一批复验。

默认安全构建的JVM测试 **59类 / 365项 / 0失败**，lint通过。曾将截图构建开关与默认安全测试混用，造成`currentBuildDefaultsToSecure`失败；改为默认安全构建重新执行后通过，未放宽测试。

S02已核对：真实MainActivity解锁页外层有主题Surface；原截图测试缺少该容器造成深色卡片/白背景，属于测试宿主偏差。测试已补齐容器，需重新采图，未修改解锁页生产配色。

追加代码调整：D01药品管理操作放到所选药品正下方，并提供选中语义及危险删除样式，避免长列表选中后需要滚到列表末尾。新增12项补充采集覆盖用药编辑/历史、服药编辑、包装照片、图片/PDF预览、全部检查资料、趋势/指标弹窗、恢复分支、安全副本、欢迎页与禁用组件。当前只完成代码，构建及运行待验证。

仍保留的体验建议：首页安排与服药汇总重复、孕期日期缺失时缺少直接修改入口；「我的」长列表分组；服药页重复补记入口；手动时间输入简化；恢复时保留文件名；云端技术信息折叠；安全设置主次与术语；导出格式用途解释。未实施的建议不记为完成。

## M6-UX-ALL-10 剩余页面、交互缺陷及提示优化

本节更新09之后的实际结果；前文的“待验证”保留为当时状态，以本节及覆盖总表为准。

已逐张打开检查 `remaining-deep/` 与 `remaining-light200/` 各26张截图：用药编辑及历史、服药编辑、包装照片与导入选项、图片/PDF预览、全部资料、趋势与指标弹窗、恢复8种分支、安全副本、欢迎页和禁用组件。对应采集与断言各 **22/22通过**（80.142s、90.756s）；包含7项组件回归及3项提醒/解锁补采。`rechecked-deep/` 的4张提醒/解锁图也已核对。药品目录另用12条合成药品验证首尾选择，选中项下方即可操作；预览与提醒编辑器的深色系统栏图标已核对可见。解锁页补齐测试宿主Surface后显示正常，未修改生产配色。

| 项目 | 实施结果 | 验证证据 |
|---|---|---|
| 日历末项被底部新建按钮遮挡 | 实际点击预约却进入新建页；列表与按钮改为纵向独立占位 | `calendar-diagnostics/` 保留失败前后图；`calendar-fixed/`、`calendar-fixed-light200/` 保留正确详情图，普通/200%均实际打开核对 |
| 云盘输入后点击校验偶发无效 | 测试点击发生在键盘收起/布局变化期间，复用既有键盘及按钮位置稳定等待；产品校验规则保持 | 两个原失败用例在普通与200%配置均通过，见下表；原始失败日志保留 |
| H01 错误态误称没有记录 | 健康原始/趋势空态增加无错误前提 | 新增失败注入专项，断言有错误且不出现两种空提示；`final-light200/measurement-error-large.png`已查看 |
| 长文件名分享提示 | 明文与只读授权说明在前，名称单独展示；内容可滚动，操作保留 | `final-light200/original-share-long-name-final.png`已查看；3项分享专项全部通过 |
| 日期不全时缺少下一步 | 首页增加“补充日期”，两种清除日期动作分别命名 | `final-copy/pregnancy-editor.png`已查看；首页补采另记后续结果 |
| 安全与备份措辞 | 关闭应用锁降为次操作；改用手机锁屏密码等易懂表述；立即锁定明确；备份卡标题去重 | `final-copy/security-settings.png`、`local-backup.png`已查看 |
| 导出及备注说明 | 解释ZIP/JSON和PDF用途；已有备注内容时隐藏空内容提示 | `final-copy/export-screen.png`、`journal-entry-editor.png`已查看 |

`final-copy/` 中云盘连接、导入失败、多资料与资料回收站截图也已打开复核；连接保存与备份成功区分，失败给出原因及重新选择/重试入口。

| 运行 | 结果与范围 |
|---|---|
| `final-flow-result.txt` | **52项，50通过/2失败**（360.127s）；原失败不能改写为整组通过。其他状态、后台说明、表面和恢复用例通过 |
| `calendar-diagnostic-result.txt` | 单项再次复现日历错误目标；诊断截图确认属于实际遮挡 |
| `diagnostic-result.txt` | 4项中3通过/1日历失败；健康错误、云盘与长文件名诊断通过 |
| `calendar-fixed-result.txt` | **12/12通过**（52.769s），含两个原失败流程、8项组件/错误回归与2项分享检查 |
| `calendar-fixed-light200-result.txt` | **4/4通过**（36.404s），含两个原失败流程、健康错误与长文件名 |
| `share-final-result.txt` | 分享提示最终顺序调整后 **3/3通过**（16.156s），浅色200% |

默认安全构建在日历修复后完整JVM **59类/365项/0失败/0错误**、lint通过（`.local/ui-review-complete-standard.log`）；分享最终顺序调整后的完整检查同样通过，构建1m35s、lint **0错误/31警告**（`.local/ui-review-latest-standard.log`）。用于截图的构建开关仅在专用模拟器测试使用。

步骤10结束时未完成：横屏与键盘组合复验、补充首页截图、剩余状态流程最终结果（已在步骤11/12闭环）；TalkBack连续读屏、全部色彩对比度及真机/厂商界面不在已验证范围。仍未实施的体验建议：首页安排信息去重、“我的”分组、服药页重复入口整合、时间输入辅助、恢复文件名上下文、云盘技术详情折叠、趋势轴标签及日期选择器中文区域策略。日期补充入口、安全文案与导出格式说明已实施，不再列为未实施。

## M6-UX-ALL-11 状态与极端布局闭环

- 剩余状态流程 **26/26通过，87.637s**，见 `remaining-state-flows-result.txt`：云盘连接4项、清理7项、导出4项、安全副本1项、恢复密钥及回收6项、自定义提醒3项、首页1项。仅使用隔离/合成夹具，未访问真实云盘。
- 新首页图 `final-copy/today.png` 已打开，“补充日期”直接入口可见。`final-copy/cloud-backup-state.png` 已打开，最近成功与最近尝试分开；该旧组件宿主未提供Surface，白色外边不能当作正式深色页面缺陷，正式页面以既有两种主题截图为准。
- 横屏200%深色首轮6项中2失败；扩展14项中5失败，分别保存在 `extreme-pages-result.txt`、`extreme-fixed-result.txt`。原因是测试对惰性列表屏幕外条目直接定位、同名“通知权限”目录/标题匹配冲突，以及仍查找已替换的“返回”文字按钮。测试补齐容器滚动、限定非点击标题、改用现有全屏返回标记，保留原来的可见性、文字不截断、保存到库和返回断言。
- 最终横屏组合 **14/14通过，91.649s**，见 `extreme-final-result.txt`。覆盖四个根入口及底部导航、首页长列表、时间线、用药、提醒、安全、产检详情/编辑、孕期创建/编辑、资料列表/预览/元信息编辑、日常记录及语义检查。键盘收起后保存与实际数据库结果均有断言，不代表所有输入法均已验收。
- `extreme-pages/` 10张、`extreme-forms/` 9张图全部实际打开复核；长文本和按钮可读。滚动视口边缘的截取不算内容丢失；图片横屏200%下预览面积较小，仍可使用已有缩放。`p5-security.png` 同样是未包Surface的独立测试宿主，正式颜色以 `final-copy/security-settings.png` 为准。没有用旧 `n22-*` 图补本轮横屏证据。
- 新增 [14组主题核心配色计算](theme-contrast.md)，已核对主题实色文字配色；不扩大为半透明/禁用态、所有系统控件或完整无障碍认证。
- 设备已恢复字号1.0、深色及原旋转设置。步骤11结束时最终导航整类仍在运行，结果见步骤12。

## 后续体验建议及优先级

以下均已检查并记录，属于未实施的进一步体验优化，与已修复缺陷分开。

| 优先级 | 建议 | 预期收益与实施入口 |
|---|---|---|
| P2 | 恢复密钥输入页保留已选文件名/大小 | 避免选错后失去上下文；RestoreController/Activity需只保留受限展示元信息，不展示内容URI |
| P2 | “我的”按档案、数据保护、设置分组 | 缩短备份与安全入口查找；JournalNavigation的ProfilesRoute |
| P2 | 时间输入增加系统选择器 | 减少格式错误；MeasurementEditor/IntakeEditor/VisitRecord等统一，保留已有时间含义与时区 |
| P2 | 云盘技术信息改为可展开详情 | 主区说明是否备份、保留数量、失败重试；不隐藏会影响清理安全决策的条件 |
| P3 | 首页服药安排/统计、服药页补记入口去重 | 降低重复信息；明确下一项待办与当日汇总分别承担什么任务 |
| P3 | 时间线区分未来预约与已发生事件 | 回顾历史时减少误读，保留原始日期与来源入口 |
| P3 | 趋势增加轴/点值说明 | 降低读图成本，不加入医学判断 |
| P3 | 查询框圆角、分类当前选中标记、历史修改时间统一 | 提升一致性与识别效率；DocumentScreen、PregnancyHistory |
| P3 | 日期选择器明确跟随系统或应用中文 | 当前英文系统内中英混合；需先确定应用语言策略，再验证中文系统 |
| P3 | 大字体横屏预览提供更紧凑控制区 | 增加原件可视面积，保留完整标题与可达动作 |

完整TalkBack连续读屏、第三方输入法、真机/OEM系统界面及发布前设备矩阵保留在E01/E02，未据本轮模拟器检查宣称通过。

## M6-UX-ALL-12 最终结果与交付边界

- 最终导航整类 **26/26通过，280.005s**，见 `navigation-final-result.txt`。包含日历原失败流程及保存/返回/草稿/资料/PDF等导航行为；原52项首轮的失败日志仍保留。
- 首页三种预约日期（过去/今天/未来）及“补充日期”直达回调专项 **1/1通过，7.684s**，见 `home-date-states-result.txt`。该项使用合成HomeScreen状态，不等于完整MainActivity生产安全宿主；不包含真实服药/云端数据。
- 这组三种状态截图又发现C06：首页固定三列在200%时将“检查资料”等拆成3字加1字。`component-before/home-quick-actions-large.png` 保留原图。新增自适应列数，按当前字型、字号测得最宽标签并预留卡片内边距，列数在1–3之间调整。普通字号保留三入口布局；大字/窄宽度减少列数。
- 加强上述专项，要求三个快捷入口文字完整一行、可见且可点击；调整后连同首页目的页面跳转与主页面采集 **3/3通过，14.821s**，见 `home-grid-fixed-result.txt`。`component-after/home-quick-actions-large.png` 已实际打开，200%为两列加下一行，未再拆字；三种日期截图位于 `final-copy/home-appointment-*.png`，另有此前逐张审阅与回调断言。
- 此最后调整只影响首页快捷入口布局，未重跑此前26项导航/26项状态/14项横屏整组；最后3项针对性验证保留目的页面和回调断言。默认安全完整构建、设备测试编译、JVM和lint重新检查成功（1m19s），**59类/365项/0失败/0错误/0跳过，lint0错误/31警告**。证据见 `delivery-checks-result.txt` 和 `.local/ui-review-home-grid-build.log`。
- 当前APK由默认安全构建生成，`VISUAL_VERIFICATION=false`，已安装专用模拟器；没有安装真机、发布或提交代码。没有真实云盘访问、真实医疗资料或恢复密钥写入本轮证据。

本轮目标“逐一检查所有界面和组件，检查是否有可以优化的地方”已完成：页面与组件均有检查结论，已复现的遮挡、丢字和误导状态已修复并回归；其余进一步优化按上表保留。该完成结论不表示所有建议已实施，也不替代真机、连续读屏及完整发布验收。
