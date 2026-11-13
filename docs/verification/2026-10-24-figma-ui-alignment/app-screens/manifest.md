# 2026-10-24 Figma UI 对齐 · 实现侧界面截图清单（app-screens）

本目录是“Figma 未设计 / 未同步”的实现界面证据：在专用模拟器上真实渲染当前 App 实现并截图，
用于与 `docs/verification/2026-10-24-figma-ui-alignment/figma/` 中的 Figma 素材做逐屏视觉对齐检查。

- 任务：共享任务 task-3（`docs/verification/2026-10-24-figma-ui-alignment/`）
- 生成日期：2026-10-24
- 测试类：`app/src/androidTest/java/cn/yunji/journal/UiSnapshotCoverageTest.kt`（新增，17 个 `@Test`）
- 运行结果：**17 tests / 0 failures / 0 errors，OK (17 tests)，Time: 48.756**（`instrument-run.log`）
- 设备：`emulator-5556`（PJ_API36_16K · Android 16 / API 36 · x86_64 · 1080×2400 @420dpi）
  基线：`font_scale=1.0`、浅色（Night mode: no）、竖屏；应用 `cn.yunji.journal` 0.26.0-dev / vc27
- 截图方式：优先把可注入状态的屏幕直接渲染为 Compose 内容（SecuritySettingsScreen / ExportContent /
  CloudConnectionContent / RestoreContent / RecoveryContent 等），需要真实数据或模型的屏幕沿用
  `PageMatrixBaseTest` 的加密数据库夹具；页面统一包进 App 真实使用的 `JournalPage`（标题栏 + 返回）。
  普通页面用 `ui.onRoot().captureToImage()` 截 Compose 根视图；自定义提醒编辑器是独立对话框窗口，
  Compose 无法整窗截取，改用 `UiAutomation.takeScreenshot()` 整屏截图（因此含系统状态栏）。
- 尺寸：所有 PNG 均为 1080×2400（等价 412×915 dp，与 Figma 画板一致），均 > 90KB、可正常打开。
- 真机（小米 11 Ultra）因 `MainActivity` 设置 `FLAG_SECURE` 无法截图，故本组证据全部来自模拟器。

## 逐屏清单

任务优先级对应关系见“任务项”列。状态 `ok` 表示已真实渲染并落盘；`skipped` 见文末说明。

| # | 画面 | 文件 | Composable / 页面标题（路由） | 任务项 | 截图状态 | 截图时的实际状态 |
|---|------|------|------------------------------|--------|----------|------------------|
| 1 | 提醒设置（上） | `reminder-settings.png` | `ReminderSettingsScreen(model)` / 提醒设置 | 1 | ok | 通知权限“未允许”（仅应用内待办）、非精确提醒（可打开系统设置）、未加入电池优化白名单、提醒类型开关可见 |
| 2 | 提醒设置（下） | `reminder-settings-custom.png` | 同上（滚动到“调度检查”） | 1 | ok | 提醒类型（产检/用药开关）、自定义提醒区块含“每日叶酸提醒”提醒行与“新建提醒”按钮、调度检查标题 |
| 3 | 自定义提醒编辑器 | `custom-reminder-editor.png` | `CustomReminderEditor`（对话框，新建态） | 2 | ok | 标题/备注/日期/时间 + 一次/每天/每周单选，默认“一次”；未填标题（保存校验未触发）。UiAutomation 整屏 |
| 4 | 安全设置 | `security-settings.png` | `SecuritySettingsScreen(SecurityUi(enabled=true, strict=false, timeout=5))` / 安全设置 | 3 | ok | 应用锁已开启、离开锁定 5 分钟、普通模式说明、立即锁定可用 |
| 5 | 云盘连接（上） | `cloud-connection.png` | `CloudConnectionContent` fixture / 云盘连接 | 4 | ok | 已保存连接（坚果云示例地址）、应用密码留空沿用、测试连接/保存连接按钮、连接检查全部可用（合成状态，不联网） |
| 6 | 云盘连接 · 云端备份 | `cloud-connection-backup.png` | 同上（滚动到自动备份开关） | 4 | ok | 最近成功/最近尝试时间、快照号、立即上传备份、主设备角色、自动备份与仅 Wi-Fi 开关 |
| 7 | 云盘连接 · 云端清理 | `cloud-connection-cleanup.png` | 同上（滚动到清理说明） | 4 | ok | 上次尝试时间与完成状态、保留快照 3 / 删除提交 2 / 删除对象 5 / 新登记孤立 1、立即清理按钮及保留策略说明 |
| 8 | 本地加密备份 | `local-backup.png` | `RecoveryContent(stage=READY) + SafetyCopiesPanel` / 本地加密备份 | 5 | ok | 恢复入口“从备份恢复资料”、导出加密备份按钮、恢复前安全副本空态“还没有安全副本”（915dp 内单屏完整呈现） |
| 9 | 全局回收站（空态） | `trash-empty.png` | `TrashScreen(model)` / 全局回收站 | 6 | ok | 删除的项目（0）/ 保留的资料（0）、“全局回收站为空”、保留 30 天说明 |
| 10 | 导出资料（上） | `export-screen.png` | `ExportContent(ExportUiState())` / 导出资料 | 7 | ok | 日期范围留空、包含原件/回收站开关、导出范围默认全部勾选 |
| 11 | 导出资料（下，禁用态） | `export-screen-disabled.png` | 同上（滚动到底部） | 7 | ok | “我了解导出内容为明文”未勾选；导出结构化数据包 / 生成就诊摘要 PDF 两个按钮均为禁用态 |
| 12 | 恢复向导（默认态） | `restore-wizard.png` | `RestoreContent(RestoreUiState())` / 恢复资料 | 8 | ok | SELECT 态：选择加密备份、从云端备份恢复、整体替换与安全副本说明 |
| 13 | 恢复向导（密钥态） | `restore-wizard-key.png` | 同上（点击“选择加密备份”后 stage=KEY） | 8 | ok | 恢复密钥输入框（密码遮挡）、校验备份按钮、重新选择文件 |
| 14 | 管理药品（目录） | `drug-catalog.png` | `DrugCatalogScreen(pregnancyId, model)` / 管理药品 | 9 | ok | 新增药品入口 + 已录入药品“复合维生素片（孕妇型）”卡（规格/剂型/编辑信息/包装照片/删除） |
| 15 | 新增药品编辑器 | `drug-editor.png` | `DrugEditorScreen(DrugForm(), model, busy=false)` / 新增药品 | 9 | ok | 药品名称/规格/剂型输入、保存药品按钮、说明文案（空表单默认态） |
| 16 | 健康指标编辑器 | `measurement-editor.png` | `MeasurementEditorScreen(form, model, busy=false)` / 记录测量 | 10 | ok | 指标“体重 · kg”、测量值输入、测量日期/时间、时区、来源、备注、保存测量 |
| 17 | 日常记录编辑器 | `journal-entry-editor.png` | `JournalEntryEditorScreen(form, model, busy=false)` / 写日常记录 | 12 | ok | 发生日期/时间、症状/心情/日常备注（预填示例内容）、保存日常记录 |
| 18 | 孕期档案编辑器 | `pregnancy-editor.png` | `PregnancyEditor(form, busy=false)` / 编辑孕期档案 | 13 | ok | 档案名称、预产期、末次月经、建档医院、备注、保存档案（已填写示例日期） |
| 19 | 产检详情 | `visit-detail.png` | `VisitDetail(visit, busy=false, …)` / 产检详情 | 14 | ok | 已预约产检卡、提醒（未设置）、准备事项、产检资料/修改预约/记录实际就诊/取消预约/删除入口 |
| 20 | 产检编辑器 | `visit-editor.png` | `VisitEditor(VisitForm, busy=false)` / 新建产检预约 | 14 | ok | 预约日期/时间、医院、检查项目、准备事项、保存预约（新建默认态） |
| 21 | 孕期时间线 | `timeline.png` | `TimelineScreen(pregnancyId, "", model)` / 孕期时间线 | 11 | ok | 写日常记录入口、类型筛选、搜索框，及已种子数据（日常记录/产检）时间线条目 |
| 22 | 来源详情（日常记录） | `timeline-source-detail.png` | `TimelineSourceScreen(pregnancyId, "JOURNAL", id, model)` / 原始记录 | 11 | ok | 日常记录来源详情：标题、日期时间、依据、症状/心情/正文、录入时间、修改/删除入口 |

## 未出图 / 跳过项（如实记录，未伪造）

- **时间线来源详情的其余类型**（VISIT / PLAN / DOCUMENT / MEASUREMENT / INTAKE）：任务标注“可跳过”；
  本轮只出 `JOURNAL` 一例（其余类型需要更多跨模块种子数据与额外导航，收益低于时间成本）。
- **孕期档案“日期依据历史”页**（`PregnancyHistoryScreen` / “日期依据历史”）：需要先产生一次日期修订
  （保存孕期后再修改日期）才能出现“当前依据/历史依据”记录；本轮未在 20–30 分钟预算内实现该前置动作，
  该页为纯卡片列表、与孕期编辑器同风格，暂不单独出图。
- **安全设置的其它状态**（未开启应用锁 / 严格模式开启）与**锁定页**：本轮只出“已开启应用锁 + 普通模式”
  主状态；严格模式会改变后台行为，不适合在截图会话中真实切换。
- **系统级设置页**（系统通知设置、精确提醒设置、电池优化、自启动）：属于系统 UI，非本 App 实现界面，
  不计入“实现侧截图”。
- **用药方案编辑器 / 服药记录编辑器**：不在本任务 14 项清单内（Figma 已有 09/10 设计稿）；如需补充可后续扩展。

## 复现命令

```powershell
# 构建测试 APK（本轮已完成，宿主 build 输出已有产物）
.\gradlew.bat --gradle-user-home .gradle-user --offline :app:assembleDebugAndroidTest

# 安装并在专用模拟器上定向运行（仅 emulator-5556，严禁操作小米真机）
adb -s emulator-5556 install -r -t -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r -e class cn.yunji.journal.UiSnapshotCoverageTest `
  cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner

# 取回截图（设备路径 → 本目录）
adb -s emulator-5556 pull /storage/emulated/0/Android/data/cn.yunji.journal/files/verification/figma-ui-alignment/ `
  docs/verification/2026-10-24-figma-ui-alignment/app-screens/
```

## 说明与限制

- 截图中的“提醒设置 → 通知权限”状态来自 instrumentation 测试 Activity（包 `cn.yunji.journal.test`）的
  权限状态，而非正式 App 包；因此显示“未允许；只能使用应用内待办”，属测试宿主环境差异，文案与布局本身
  是正式实现。其余页面均为真实 Composable，除云盘/导出/恢复注入的合成状态外不伪造数据。
- 云盘连接、导出资料、恢复向导的截图使用注入的合成状态（不联网、不执行导出/恢复），以便稳定呈现
  “已连接 + 云端备份/清理”“未确认明文 → 按钮禁用”“默认/密钥态”等关键视觉状态。
- `reminder-settings-background.png` 与 `local-backup-safety-copies.png` 在首轮生成后经核对为与主图完全
  相同（该内容在 915dp 内单屏即可完整呈现、无需滚动），已删除避免重复。
- 设备基线在运行前后读回核对（运行后 `font_scale=1.0`、浅色、竖屏 `1080x2400/420dpi`），应用已
  `am force-stop cn.yunji.journal`；现场记录见 `../device-info-baseline.txt`、`../device-info-post.txt`。
- 本次只改测试代码（新增 `UiSnapshotCoverageTest.kt`），未改 `app/src/main`、未改 Figma、未改主文档。
