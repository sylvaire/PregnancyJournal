# M6 小米 11 Ultra 全功能调试（孕期功能 / UI 对齐 / Bug 修复）
 
## 2026-10-24 首页 Figma 对齐增量

| 日期 | 任务 | 完成内容 | 产物路径或链接 | 验证结果 |
|---|---|---|---|---|
| 2026-10-24 | E02 / Today UI | 首页按 Figma 节点 `12:138` 调整首屏层级、概览、安排、快捷入口、最近资料和备份提示；保留既有业务入口 | [验证记录](../verification/2026-10-24-home-figma-alignment/README.md)、`app/src/main/java/cn/yunji/journal/feature/JournalApp.kt`、`IntakeScreens.kt`、`JournalNavigation.kt` | 构建、单元测试、lint 通过；模拟器首页定向 UI 2/2；真机视觉待验收 |
| 2026-10-24 | E02 / 真机安装 | 通过无线调试将首页对齐版覆盖安装至小米 11 Ultra，保留现有应用数据；启动应用 | [安装记录](../verification/2026-10-24-home-figma-alignment/README.md) | ADB `Success`；进程存在；`0.26.0-dev` / vc27；真机视觉待验收 |
| 2026-10-24 | E02 / 日期空态修复 | 区分已保存但超出孕周可计算范围的预产期或未来末次月经日期，与尚未填写日期；提供核对文案和修改入口 | [验证记录](../verification/2026-10-24-home-figma-alignment/README.md)、`app/src/main/java/cn/yunji/journal/feature/JournalApp.kt` | 构建/单元测试/lint 通过；修复包已装小米 11 Ultra，首页提示真机语义复核通过；修改入口跳转待复核 |
| 2026-10-25 | E02 / 风格一致性复审 | 当前版本模拟器重截 31 张图，逐组检查主导航、记录详情、云盘/导出、提醒/安全、表单、资料与空态；列出 P1/P2 整改项 | [审查记录](../verification/2026-10-25-style-audit/README.md) | UI 截图测试 27/27；仅完成审查，整改与真机视觉待验收 |
| 2026-10-25 | E02 / 风格整改 | 原始记录详情分卡、云盘/导出分组、提醒弹窗主次按钮与日期入口、表单日期显示、资料缩略图占高、安全选项布局 | [整改记录](../verification/2026-10-25-style-polish/README.md)、[整改截图](../verification/2026-10-25-style-polish/screens/) | 构建/单元测试/lint 通过；最终相关界面用例 41/41；小米 11 Ultra 覆盖安装并启动，真机视觉待验收 |
| 2026-10-25 | E02 / Ardot 风格对齐增量 | 对照 N01/N02/N13 修改记录首页、管理孕期、药品目录的页面层级、搜索与操作；保留原业务入口 | [本轮记录和截图](../verification/2026-10-25-ardot-app-alignment/README.md)、`TimelineScreens.kt`、`JournalApp.kt`、`DrugScreens.kt` | 构建通过，JVM 348 项/0 失败；三个截图用例 3/3，孕期管理修订重测 1/1；真机和其余画板待验收 |
| 2026-10-25 | E02 / Ardot 对齐续作 | N13 药品列表/操作行、N03 日常表单、N14 测量表单、N06 提醒状态卡；保留原数据与业务操作 | [本轮记录和截图](../verification/2026-10-25-ardot-app-alignment-2/README.md)、`DrugScreens.kt`、`TimelineScreens.kt`、`MeasurementScreens.kt`、`ReminderSettingsScreen.kt` | 构建和 JVM 348 项通过；四个截图用例与药品编辑/照片/删除端到端专项通过。N06 功能区、其余画板及真机/大字体待验收 |

变更说明：Figma 示例使用填充资料；应用按真实数据展示日期和资料空状态。无服药计划时保留可点开的安排空状态，防止首屏布局跳动。下一步：有真实日期、最近资料、字体放大和横屏时继续视觉复核，并在小米真机复核页面入口。

## 基本信息

- 状态：进行中
- 开始日期：2026-10-24
- 最近更新：2026-10-24
- 对应任务 ID：E01 真机矩阵、E02 UI/视觉、C05 提醒、D01–D07 备份/上传/恢复/导出、B01–B06 / C01–C06 功能回归
- 前置阶段与阅读入口：[项目进度](../项目进度.md)、[剩余任务验收清单](../剩余任务验收清单.md)、[V1.0 详细实施文档](../V1.0详细实施文档.md)、[Figma 状态](../design/figma-state.json)、[M5 记录](M5-E01E02发布阻断与材料.md)

## 目标与范围

用户目标（2026-10-24）：**使用小米 11 Ultra 对整个孕期功能做调试**，记录「显示、添加、删除、备份、上传、恢复」等功能是否正常；检查 **UI 是否与 Figma 设计稿对齐**；检查 **Figma 未设计的界面是否与整体风格统一美观**；**记录遇到的 bug 并修复**。

验收条件：
1. 上述功能类别在小米 11 Ultra（M2102K1C / Android 13 / MIUI 14 / 4 KiB 页）上逐项走查并留证（通过/失败/阻塞/未覆盖，不允许“未测算通过”）。
2. UI 对齐：Figma 20 个设计屏与实现逐屏比对；Figma 未设计的实现屏给出风格一致性结论。
3. 发现的 bug 记录到验证目录并给出修复/未修复结论；修复需重新验证。

## 设备与方法

- 设备：Xiaomi 11 Ultra `M2102K1C`（`star`），MIUI `V14.0.22.12.5.DEV` / Android 13 / SDK 33 / `PAGE_SIZE=4096`，无线调试（配对后 `<adb设备标识>`）。
- 应用：`cn.yunji.journal` `0.26.0-dev` / versionCode 27 / schema14（debug）。
- 方法：adb 驱动 UI（MIUI 拦截仪器测试 Activity 启动，不能跑 Compose UI 测试）；`uiautomator dump` 读结构与文案；`dumpsys`/`run-as` 读状态；系统级截图可用，但 **应用窗口设了 `FLAG_SECURE`（MainActivity.kt:85），真机 adb 截图对应用内容为空**——UI 视觉比对改用模拟器 Compose `captureToImage`（UiSnapshotTest/新增截图测试），真机只做结构与功能核对。这一限制本身作为机制记录，不作为 bug。
- 合成数据：全部使用合成档案与资料；验证后清理（或明确保留原因）。

## 完成清单

| 日期 | 任务 | 完成内容 | 产物路径或链接 | 验证结果 |
|---|---|---|---|---|
| 2026-10-24 | M6 启动 | 设备就绪、应用全新安装 vc27/schema14；建立功能清单与 UI 比对素材流程 | [功能验证目录](../verification/2026-10-24-Xiaomi11Ultra-full-functional/README.md) | 安装/启动通过 |
| 2026-10-24 | 显示走查 | 首页/产检列表与详情/资料列表与 PDF 预览/记录枢纽/日常记录/用药方案/服药记录/首页统计逐项核对 | [flows](../verification/2026-10-24-Xiaomi11Ultra-full-functional/flows/) | 通过（结构/文案/状态一致） |
| 2026-10-24 | 添加走查 | 孕期建档（含预产期/医院/备注）、产检预约（日期/时间/项目/准备）、产检提醒、图片与 PDF 导入、用药方案、服药记录、日常记录 | 同上 | 通过；表单校验文案正确（剂量/单位成对、剂量>0） |
| 2026-10-24 | 提醒调度核对 | 产检提醒键 `VISIT:…:2026-10-30:09:00` 与 14 条 `MEDICATION` 键入库，AlarmManager 有对应 `RTC_WAKEUP` | `flows/02-04-reminder-state.txt` | 通过 |
| 2026-10-24 | Bug 记录 | 发现用药/服药页 3 处“通知提醒未提供/未启用”过期文案（与已实现提醒矛盾） | [bugs.md](../verification/2026-10-24-Xiaomi11Ultra-full-functional/bugs.md) | Bug #1 已确认 |
| 2026-10-24 | 备份走查 | 「生成恢复密钥→核对两组→导出加密备份（SAF 保存到 Download）→回读校验」通过；文件 61,716 B；恢复后自动生成安全副本 61.72 kB（核对标签 42440c3f） | `flows/07-backup-*.xml`、`flows/07-safety-copy-after-restore.xml` | 通过 |
| 2026-10-24 | 恢复走查 | 本地 `.yunji` 恢复：密钥校验→预览计数→确认→完成；恢复后首页/产检/资料/用药/服药/日常与统计一致，vault 切换为代际目录 | `flows/07-restore-*` | 通过 |
| 2026-10-24 | Bug #2 发现与修复 | 恢复预览把已彻底删除的孕期墓碑计入统计（预览 2 vs 实际 1）；改 `RestoreWizard.kt` 预览计数排除 PURGED 批次，构建+JVM 通过后真机预览复测为 1 | [bugs.md](../verification/2026-10-24-Xiaomi11Ultra-full-functional/bugs.md) | 已修复并真机回归 |
| 2026-10-24 | UI P0-2 修复 | `Theme.kt` 补齐 M3 1.2+ `surfaceContainer*`/`surfaceTint`/`inverse*`/`errorContainer` 等角色（暖白绿色板；对话框/菜单=White、Switch 轨道=Outline），新增 Apricot/Amber/Danger/DangerSoft 常量 | [theme-fix 验证](../verification/2026-10-24-ui-theme-fix/README.md) | 通过：构建+JVM 342/342+快照 17/17；对话框 #ECE6F0→#FFFFFF、Switch 轨道 #E6E0E9→#DDE4DA、22 张无淡紫灰残留 |
| 2026-10-24 | 真机彻底删除（断连前） | 产检资料 PDF → 回收站 → 彻底删除：确认弹窗文案正确，确认后回收站 0/“回收站为空”；对象文件回收计数因设备断连未复核 | — | 部分通过；待重连复核对象清理 |
| 2026-10-24 | 全量矩阵（Timeline A 后） | emulator-5556 全量 `am instrument`：**OK (368 tests) / 0 failed / EXIT=0**（含时间线新 IA、NavigationExperienceTest 24、快照/极端布局/数据/备份恢复全部用例）；期间修复两处全量负载下的测试同步脆弱（measurement-value 等待、cloud 校验文案等待） | [lead-full-matrix-round12c.log](../verification/2026-10-24-timeline-ia-a/_tools/lead-full-matrix-round12c.log) | 通过 |
| 2026-10-24 | **Timeline 方案 A 重做** | TimelineScreens 重写：3 筛选（全部/产检/日常）、72dp 记录行+44dp 图标容器、日期分组（M月d日）+ 右侧孕周（EDD/LMP 推算）、杏色备注卡（#FAEAD8/#80582C）、底部 CTA「写一条日常备注」；其他事件类型保留为记录行但无专属筛选；「全部资料」改独立列表页；同步更新 NavigationExperienceTest/ExtremeLayoutMatrix*/UiStateMatrixTest | [timeline-ia-a](../verification/2026-10-24-timeline-ia-a/README.md) | 通过：JVM 342/342、Navigation 24、Coverage 17、Supplementary 9、UiStateMatrix 7、ExtremePages 6；行高 72.38dp/图标 44.19dp/杏色卡 363×109dp/CTA 363×52.6dp |
| 2026-10-24 | 模拟器补位功能走查 | 11 Ultra 离线期间在 emulator-5556 完成 A 自定义提醒（创建/编辑/删除/一次/每天/每周、CUSTOM 调度键+RTC_WAKEUP+通知投递）、B 健康指标（体重新增/趋势/编辑/删除/草稿保留）、C 云盘连接 UI（表单/非法地址/失败提示）、D 导出资料（结构化 zip 5,898 B + 就诊摘要 PDF 160,730 B，落盘并经回读校验）；E 安全设置按风险跳过 | [emulator-functional-coverage](../verification/2026-10-24-emulator-functional-coverage/README.md) | A/B/D 正常，C 基本正常；发现 Bug #4（空账号提示误导） |
| 2026-10-24 | Bug #4 修复 | 云盘连接空账号提示由「请检查账号，不能包含冒号或控制字符」拆分为「请填写账号」；同步更新 NavigationExperienceTest 断言（空白→请填写账号、含冒号→原提示） | [lead-rerun 日志](../verification/2026-10-24-emulator-functional-coverage/_tools/lead-rerun-nav-cloud-bug4-fix.log) | 已修复并回归：NavigationExperienceTest+CloudConnectionUiTest OK(27) |
| 2026-10-24 | 重建 AVD 后全量矩阵（round 18 收尾） | 原 emulator AVD 缺失，`avdmanager` 重建 Android 36 16KiB（`PJ_API36_16K`）后跑矩阵：第一次 369/1（CustomReminderUiTest 删除确认框未及时出现）、第二次 369/1（云盘用例输入状态未就绪）——均为测试同步脆弱，分别加 `waitUntil` 后单用例 OK(1)；**第三次 OK (369 tests) / 0 失败 / EXIT=0**。模拟器基线恢复 | [device-debug-round18](../verification/2026-10-24-device-debug-round18/README.md) | 通过 |
| 2026-10-24 | 真机应用锁守卫验证 + 数据清理（round 18） | 11 Ultra 复连（22:28，`<局域网地址>`）后验证：未设置设备锁屏凭据时点击「开启应用锁」→ 应用进入锁定层并提示「请先设置系统锁屏凭据或录入可用的生物识别；旧设备严格模式需要强生物识别。」，应用锁未静默开启；force-stop 重启后正常解锁。合成孕期数据已彻底清理（回收站空、gen objects 0） | [device-debug-round18](../verification/2026-10-24-device-debug-round18/README.md) | 通过（真机完整 PIN 链路因用户未设置设备锁仍受限） |
| 2026-10-24 | 发布型构建安全验证（round 18） | 构建 performance（R8 release-like，`BUILD_TYPE=performance`、`VISUAL_VERIFICATION=false`）并安装到 Android 36（16KiB）模拟器：窗口标志含 `SECURE`、1080×2400 截图全黑（2,592,000 像素均黑）、应用正常启动；确认 `-PvisualVerification=true` 截图开关不影响发布构建安全行为 | [release-security-verification](../verification/2026-10-24-release-security-verification/README.md) | 通过（performance 变体，未跑其完整矩阵/正式签名） |
| 2026-10-24 | **真机像素级 UI 验证（round 16）** | 新增验证构建开关（`-PvisualVerification=true` 时 `MainActivity` 不加 FLAG_SECURE，默认/release 均保持安全，`SecureFlagPolicyTest` 覆盖）；11 Ultra 安装验证包后截取 17 张真机截图（含深色），与 Figma 10 屏并排对比；实测色板与设计令牌一致（#F7F8F3/#E7F0E6/#FFFFFF/#356B53/#223C32/#DDE4DA，时间线杏色卡 #FAEAD8）；Timeline 方案 A 真机视觉高度一致；Figma 未设计页面风格统一；安全设置页因设计内 `SecureFlagPolicy.SecureOn` 不可截图（保留语义树证据）。截图后重装默认包验证 `FLAG_SECURE` 恢复 | [device-visual-round16](../verification/2026-10-24-device-visual-round16/README.md) | 通过：JVM 342/342（含 SecureFlagPolicyTest）；全量矩阵见证据；设备合成数据已清理 |
| 2026-10-24 | **Bug #5/#6 修复（round 15）** | Bug #5：legacy 布局恢复后 `vault/journal.db` + `vault/objects` 从未回收，导致彻底删除不释放密文原件——`VaultLayout` 新增 `legacyLeftovers()/reclaimLegacyLayout()`（rename→fsync→delete，可续做），`RestoreReclaim` 在 previousId=null 但存在 legacy 残留时提供回收；Bug #6：回收站「删除于」改中文 `displayDate()` | [device-debug-round15](../verification/2026-10-24-device-debug-round15/README.md) | 通过：JVM 342/342（RestoreReclaimTest 19/19）、11 Ultra 真机回收 legacy 后 objects/journal.db 全消失、active 代际完好；回收站显示「删除于 10月24日」 |
| 2026-10-24 | **11 Ultra 复连真机调试（round 14）** | 设备 17:50 恢复无线调试（`<局域网地址>`），安装最新 APK 并完成：Timeline 方案 A 真机结构确认（3 筛选/日期+孕周/杏色备注卡/底部 CTA）、日期格式（产检 10月31日、测量 10月24日 18:01）、通知权限+测试通知投递、自定义提醒（CUSTOM 调度+闹钟、随孕期清理）、健康指标（60.5kg）、云盘校验（空账号→请填写账号、非法地址→请填写有效的HTTPS服务地址）、导出 zip 8,952B/PDF 252,429B（均回读校验通过）、孕期删除→回收站→恢复→彻底删除全链路 | [device-debug-round14](../verification/2026-10-24-device-debug-round14/README.md) | 通过；新发现 Bug #5（彻底删除后 objects 4 个密文文件未回收，待修复）、Bug #6（回收站删除于 ISO 日期） |
| 2026-10-24 | 全量矩阵（剩余调试项后） | emulator-5556 全量 `am instrument`：**OK (369 tests) / 0 failed / EXIT=0**（含日期格式统一、Switch 禁用态截图、Timeline/UI 全部用例） | `remaining-debug/_tools/lead-full-matrix-round13.log` | 通过 |
| 2026-10-24 | 剩余调试项收尾 | ①P1-7 日期/时间展示格式统一（新增 `DisplayFormat.kt`，41 处展示层；同年 `M月d日`、跨年 `yyyy年M月d日`、时间 `HH:mm`；表单/导出仍 ISO）；②Switch 禁用态像素证据（4 状态截图：禁用轨道 #E4E7E6/滑块 #CCD2D0，启用轨道 #356B53）；③安全设置模拟器走查完成（临时设备凭据 2580：开启应用锁→立即锁定→错误 PIN→正确 PIN 解锁→关闭应用锁，语义树证据；已清理凭据并恢复锁屏状态） | [remaining-debug](../verification/2026-10-24-remaining-debug/README.md) | JVM 342/342、NavigationExperienceTest+Switch 25/25；全量矩阵见证据 |
| 2026-10-24 | P1 遗留：空态标题色 | `JournalEmptyState` 标题由继承 primary(#356B53) 改为显式 ink(#223C32)；重跑两套快照，trash-empty/local-backup 标题带 before→after 像素 diff 确认仅颜色变化（4,903→4,854 / 5,028→5,012 px） | [ui-p1-polish §8](../verification/2026-10-24-ui-p1-polish/README.md) | 通过：构建+JVM 342/342、Coverage 17、Supplementary 9 |
| 2026-10-24 | 全量仪器矩阵回归（P1 后） | emulator-5556 全量 `am instrument`：**OK (367 tests) / 0 failed / EXIT=0**（含 P1 开关/空态与 Bug #3 视口断言） | [ui-p1-polish/full-matrix-after-p1-367-tests.log](../verification/2026-10-24-ui-p1-polish/full-matrix-after-p1-367-tests.log) | 通过 |
| 2026-10-24 | P1 UI 收敛 | `JournalSwitch` 替换 6 处 Switch（未选中滑块/轨道 4.46:1、选中 6.21:1）；`JournalEmptyState` 统一 8 处空态（soft 卡+图标+标题）；深色表面 4 张（Alert/DatePicker #213228、Dropdown #1B2A20、Snackbar #E3EADF，无浅色残留） | [ui-p1-polish](../verification/2026-10-24-ui-p1-polish/README.md) | 通过：JVM 342/342、Coverage 17、Supplementary 9、Surfaces 4+4(深色)、NavigationExperienceTest 23；遗留空态标题色偏 primary |
| 2026-10-24 | 全量仪器矩阵回归 | emulator-5556 全量 `am instrument`（无 filter）：**OK (367 tests) / 0 failed / EXIT=0 / Time 772.3s**，覆盖 UI/数据/备份恢复/云盘/恢复/进程重建/快照等全部仪器测试 | [ui-evidence-gaps/full-matrix-lead-367-tests.log](../verification/2026-10-24-ui-evidence-gaps/full-matrix-lead-367-tests.log) | 通过（模拟器全量矩阵） |
| 2026-10-24 | UI 证据缺口补齐 | 新增 `ImeInsetLayoutTest`（键盘弹出 document-list 443.05dp ≥300dp 断言，修复前 106.7dp）；新增 `UiSurfaceSnapshotTest` 抓 AlertDialog/DatePicker/Snackbar/DropdownMenu 4 张真实表面截图并测容器色（#FFFFFF/#223C32，无淡紫灰）；深色主题跑 coverage+supplementary 共 31 张，主色均为深色令牌、对比度 5.98–14.04（文字级均 AA） | [ui-evidence-gaps](../verification/2026-10-24-ui-evidence-gaps/README.md) | 通过：IME OK(1)、surfaces OK(4)、light/dark coverage OK(17)+OK(17)、JVM 342/342 |
| 2026-10-24 | Bug #3 修复（IME 双内边距） | 键盘弹出时内容区被重复扣减（Scaffold safeDrawing 已含 IME + Box `.imePadding()` + Restore Column `.imePadding()`），文档列表视口一度仅 106.7dp；移除 2 处重复 imePadding，IME 统一由 Scaffold 处理 | [ime-inset-fix](../verification/2026-10-24-ime-inset-fix/README.md) | 修复：构建+JVM 342/342、NavigationExperienceTest 23/23；专项视口断言待补 |
| 2026-10-24 | UI P0-4 修复 | `JournalPage` 改用 `JournalPageHeader`（28/42 标题+12/18「返回上一级」副标题、24dp 边距，保留返回键 testTag/语义）；删除 Export 重复 H1、Cloud/Recovery/Restore 9 处 24sp→20sp | [ui-header-fix](../verification/2026-10-24-ui-header-fix/README.md) | 通过：JVM 342/342、快照 17/17+9/9、NavigationExperienceTest 23/23（1 处测试同步适配经 Lead 授权）；标题 19.05→26.7dp、副标题 11.4dp、Export 标题 2→1 |
| 2026-10-24 | UI P0-1/3/6 修复 | `JournalTag` 替换 23 个 FilterChip（48dp/主色选中/描边未选/保留 selected 语义）；Recovery/Restore/Export 19 个原生按钮→52dp JournalButton；9 文件 10 处页边距 20→24dp | [ui-consistency-fix](../verification/2026-10-24-ui-consistency-fix/README.md) | 通过：JVM 342/342、快照 17/17+9/9、NavigationExperienceTest 23/23、UiStateMatrix 7/7；标签 32→48.4dp、按钮 40→52.19dp、x0=24dp |
| 2026-10-24 | 云端上传 E2E（模拟器） | 新增非 UI 设备测试 `CloudUploadDeviceTest`（设备内 TLS 假 DAV + 生产 `CloudBackupUpload`/`DavBackupRemote`/`WebDavClient`，7 用例覆盖阶段顺序/重复上传不重写/失败路径），emulator-5556 两轮均 **OK (7 tests)**（4.9s），JVM 强制重跑 342/342；真机受 MIUI 拦截测试 APK 限制未执行 | [cloud-upload-device](../verification/2026-10-24-cloud-upload-device/README.md) | 通过（模拟器）；真实服务商联测仍缺 |
| 2026-10-24 | 云盘/备份设备测试 | 11 Ultra 无法安装 instrumentation APK（MIUI 限制），改在 API36 模拟器运行 39 项非 UI 设备测试（云恢复/DAV/备份导出/安全副本/调度/清理），**39/39 通过、50.2s**，并回归 RestoreWizard 改动 | `flows/08-cloud-backup-device-tests-emulator.txt` | 通过（模拟器）；上传设备级 E2E 待真实服务 |
| 2026-10-24 | 删除走查（部分） | 资料删除/回收站恢复、产检删除→全局回收站（报告另存）→恢复并重新关联；撤销 Snackbar 因 4 秒窗口错过未完成验证 | `flows/07-delete-*` | 部分通过；彻底删除/孕期删除待续 |
| 2026-10-24 | Bug #1 修复回归 | 改 `MedicationScreens.kt`（2 处）与 `IntakeScreens.kt`（1 处）为准确文案；构建+JVM 测试通过，安装到 11 Ultra 后三处页面文案核对通过、旧文案消失 | `flows/06-fix-*.xml` | 已修复并真机回归 |
| 2026-10-24 | UI 素材 | Figma 19/20 屏导出+设计令牌+未设计界面映射（figma-analyst）；模拟器新增 `UiSnapshotCoverageTest` 17 项 → 22 张实现截图（1080×2400，17/17 通过，含提醒设置/自定义提醒/安全/云盘×3/本地备份/回收站/导出×2/恢复×2/药品×2/健康编辑/时间线×2/日常编辑/孕期档案/产检×2） | [figma-ui-alignment](../verification/2026-10-24-figma-ui-alignment/)（figma/、app-screens/manifest.md） | 素材就绪；逐屏比对进行中（ui-compare） |
| 2026-10-24 | UI 逐屏评审 | 19 设计屏 vs 22 实现截图 + 像素/几何测量：可比 6 项中一致 0/部分一致 5/不一致 1（Timeline）；未设计界面可评 10 项全部有偏差；P0 问题 6 类（筛选标签、M3 默认容器色、按钮体系、头部层级、Timeline 布局、页边距）已写入 Bug/待修清单 | [comparison/README.md](../verification/2026-10-24-figma-ui-alignment/comparison/README.md) / [findings.csv](../verification/2026-10-24-figma-ui-alignment/comparison/findings.csv) | 结论已出；修复与回归待下一轮 |
| 2026-10-24 | Figma 同步板核对 | `50:581`「06 · 当前实现」板在当前画布 **不存在**（node not found），`docs/design/figma-state.json` 的登记已过期 | `figma/implementation-board/_PROVENANCE.md` | 需用户确认是否有意删除 |

## 决策与变更

- 真机截图受 `FLAG_SECURE` 限制：不为了截图而削弱安全机制；UI 视觉比对使用模拟器 Compose 截图 + Figma 导出的设计屏；真机以语义树/文案/行为为准，并在记录中注明。
- 上传/恢复：优先使用设备内 TLS 假 DAV 的非 UI 设备测试验证生产链路；外部真实 WebDAV 服务商需用户提供可用地址与凭据，未提供前不宣称真实服务商联测通过。
- 与并行任务分工：Figma 素材提取与模拟器 UI 截图由 teammate 完成；11 Ultra 为单一设备资源，由 Lead 独占串行操作。

## 未完成项与风险

- Figma 未设计界面较多（提醒设置、自定义提醒编辑器、安全设置/锁定、云盘连接与清理、导出/恢复向导、回收站、药品目录/编辑、健康指标编辑、时间线详情、档案编辑、产检详情/编辑等），风格一致性结论需逐个给出。
- 真机无法截图应用内容，视觉对齐结论以模拟器渲染为准，真机以结构/行为为准。
- 真实 WebDAV 服务商、正式签名包、无障碍读屏、低内存等仍需外部条件或专门矩阵。

## 继续工作的入口

- 最后完成的步骤：M6 第四/五轮——UI P0-1/3/6（标签/按钮/页边距）与 P0-4（二级页头部层级）修复及模拟器量化验证；修复 Bug #3（键盘弹出时双重 IME 内边距压缩）。
- 下一项具体工作：①等待用户重新开启 11 Ultra「无线调试」或改 USB，恢复真机收尾（对象回收复核、孕期删除/恢复、自定义提醒、健康指标、云盘连接页、导出资料，安装新 APK 回归）；②补 Bug #3 的专项视口回归断言（键盘态 document-list 视口高度）与深色/菜单/Snackbar 截图状态；③P0-5 Timeline 布局需用户确认「设计稿回改还是实现回改」后再动。
- 需要读取的文件、Figma 页面或节点：`docs/design/figma-state.json`（20 屏节点）、`verification/2026-10-24-figma-ui-alignment/`。
- 需要执行的验证：功能清单逐项；UI 逐屏比对；bug 修复后回归。

## 阶段验收与交接

进行中。未验收前不写通过。

## 2026-10-25 演示数据填充

- 完成内容：在小米 11 Ultra 上通过应用界面创建独立演示孕期 `DEMO-20261025`（虚构末次月经 2026-07-01），录入一条产检预约、一条日常记录及一条体重测量。均使用 `DEMO` 标记，保留原有档案。
- 产物：[演示数据与验证记录](../verification/2026-10-25-xiaomi-demo-data/README.md)。本轮未修改应用代码或设计稿。
- 验证证据：新档案在管理页和首页可见；预约项目 `DEMO-RoutineCheck`、日常症状 `DEMO-MildFatigue`、体重 62.5 kg / 来源 `DEMO-Scale` 分别在保存后的列表可见；临时手机界面转储已删除。
- 遗留问题与下一步：本轮未做视觉截图及所有页面回归；继续 M6 既有真机视觉/扩展状态验收。演示数据用完后可在应用内按档案名称定向清理。

## 2026-10-25 Figma 缺页补齐与风格核对

- 完成内容：核对旧 20 屏索引、应用路由与当前界面审查，整理 16 张待补主屏、部分覆盖状态及统一样式验收门槛；未修改应用代码。
- 产物：[缺失页面与风格复核清单](../design/缺失页面与风格复核-2026-10-25.md)。
- 验证证据：本地页面映射与 2026-10-25 模拟器截图审查可用；Figma `create_session`、`whoami`、`get_metadata`、`use_figma` 均因连接服务传输错误失败，**未创建画布节点，不能标记补页完成**。
- 遗留问题与下一步：连接恢复后先读取实时画布，再将清单转为可编辑设计屏，记录节点 ID、版本范围并逐屏检查风格与裁切；旧同步板是否存在须重新核对。
- 2026-10-25 变更记录：用户指定改在 Ardot 文件 `737849992357132` 补页；Ardot 四项接口均因 MCP 传输错误未返回，浏览器被引导至登录页。已登记目标链接，未产生画布修改；待连接与登录可用后在该文件继续。
- 2026-10-25 Ardot MCP 配置核对：Codex 用户配置 `C:/Users/sun/.codex/config.toml` 已有 `[mcp_servers.ardot-remote]` 与 `url = "https://ardot.tencent.com/mcp"`。`codex mcp get ardot-remote` 显示 enabled=true、transport=streamable_http、URL 正确；`codex mcp list` 的 Auth 为 Unsupported。配置无需重复写入；此前接口调用仍为网络传输错误，尚未验证连接/授权成功。
- 2026-10-25 Ardot OAuth 排查增量：以无令牌请求 MCP 端点得到 HTTP 401，`WWW-Authenticate` 提供 OAuth 资源元数据及 `mcp:use` 范围；执行 `codex mcp login ardot-remote` 已生成授权链接并在默认浏览器打开。当前等待用户在 Ardot 页面完成登录/同意授权及本机回调，未记录临时授权链接、令牌或个人凭据，连接成功待验证。
- 2026-10-25 Ardot 补页交付：OAuth 回调显示登录成功，目标 Ardot 文件可读写；在原页面新增 N01–N16 共 16 张 412×915 可编辑主屏，节点清单见 [ardot-state.json](../design/ardot-state.json)。复用原按钮/输入框/记录行组件与色彩变量，清理二级页底部导航和模板搜索提示；16/16 节点与布局结构可读。Ardot `capture_screenshot` 被自动审批拒绝：会将私有画板内容上传到外部截图服务 URL；已停止该方式，**视觉验收未完成**。锁定页等独立状态、交互连线仍待补。
- 2026-10-25 Ardot 补充画板：继续新增 N17–N22 六张独立状态，共 22 张新增画板；补齐应用锁定、药品编辑、服药补记、产检日历、资料预览和产检资料。N17–N22 结构检查可读，告警仍为原模板留白和组件边界；截图审批限制未解除，视觉验收与深色/大字/错误状态及原型连线仍待完成。
- 2026-10-25 Ardot 视觉复核：用户明确批准画板截图后取得 N01–N22 共 22 张初版截图并逐张查看。发现并修正 N02 缺创建入口、N11 缺 PDF 选项、N12 空态/操作同屏、N20 月历缺失及日期错位；N02/N11/N12 修订后重截通过，N20 末次修订仅结构检查无最终截图。证据见 [视觉复核记录](../verification/2026-10-25-ardot-visual/README.md)。深色/大字/错误分支与交互连线仍待处理。






## 2026-10-24 04:24 设备断连（阻塞真机后续步骤）

- 现象：在 11 Ultra 上完成「产检资料 PDF → 回收站 → 彻底删除」（UI 回收站归零、提示“回收站为空”）后，约 04:24 无线调试连接丢失；`adb devices` 不再出现该机，`adb mdns services` 无广播，`adb connect <局域网地址>` 被拒绝（目标计算机积极拒绝），但手机仍在线（ping <局域网地址> 通）。
- 判断：手机侧「无线调试」开关/会话已关闭（MIUI 可能在锁屏/休眠后关闭），不是应用问题；需用户重新打开 开发者选项 → 无线调试（必要时重新配对），或改用 USB 连接。
- 影响：本轮后续真机步骤（彻底删除的对象文件回收计数复核、孕期删除→回收站→恢复/彻底删除、自定义提醒/健康指标添加、云盘连接页 UI）暂停；已完成的 UI/DB 状态记录见下。
- 已确认的真机结果（断连前）：资料彻底删除确认弹窗“彻底删除这份资料？这将删除本应用保存的原件与缩略图，无法恢复。手机相册和文件原件不受影响。”→ 确认后回收站（0）、“回收站为空”；`no_backup/vault/objects` 仍有 4 个文件（PDF 原件/缩略图尚未被后台清理任务移除，属**异步清理的待复核项**，不占用 Bug 编号；重连后若长时间不回收再按 Bug 编号记录）。

## 2026-10-25 Ardot 剩余页面对齐续作

- 完成内容：按 Ardot N04/N05/N16 重排原始日常详情、日期依据历史和实际就诊表单；N22 产检资料增加类型标签并缩小缩略图；N12 全局回收站增加分类和搜索。原有数据操作保留。
- 产物：[逐页矩阵](../design/Ardot-App页面对齐矩阵-2026-10-25.md)、[代码/截图/验证记录](../verification/2026-10-25-ardot-remaining/README.md)。设计文件 `737849992357132`，画板 N04、N05、N12、N16、N22；本次未修改 Ardot 画板。
- 验证证据：debug 构建、单元测试和 androidTest 构建成功；模拟器 N04/N05/N16 截图 3/3、N22 截图通过、N04 编辑/删除/撤销 1/1、N12 删除/资料保留/恢复及空态 2/2。N04 首次回归因保存 Snackbar 遮挡删除按钮失败，收起编辑后的原始内容后重跑通过。
- 遗留问题：N16 设计包含当前模型没有的就诊时间、医院、检查结果；N22 操作区和 N12 真实项目卡仍高于示意稿；其余画板状态、大字体、深色及 11 Ultra 真机视觉仍未验收。下一步继续 N15/N19 服药记录、N20 月历和 N21 资料预览状态，再回到 N06–N11 及 N17/N18。
- 2026-10-25 追加：N15 历史增加“仅看补记”和日期/药品搜索；N19 备注、补记说明与保存操作重排。debug/单元/androidTest 构建通过，模拟器计划记录→编辑→删除→撤销→补记→历史 1/1，通过并保存 [历史及补记截图](../verification/2026-10-25-ardot-remaining/README.md)。N15 真实记录卡仍偏高，N19 首部方案信息和设计字段顺序未完全一致；下一步继续 N20/N21 与剩余页面。
- 2026-10-25 再追加：N20 月历态重排标题、筛选、月历和预约行；debug/单元/androidTest 构建通过，模拟器跨月→取消→返回 1/1，通过并保存 [新版月历截图](../verification/2026-10-25-ardot-remaining/screens/visit-calendar.png)。Ardot N20-final 日期列顺序错误，App 保留正确真实日期。N21 仅代码核对，真实全屏原件预览功能保留，图片/PDF 新版截图仍待采集；随后继续 N06–N11、N17/N18 与 N22 剩余状态。
- 2026-10-25 提醒/安全/药品追加：N07 编辑器从弹窗改为页面式表单，N08 安全设置和 N17 锁定页增加重点状态卡，N18 药品编辑补照片/方案说明层级。[代码及验证](../verification/2026-10-25-ardot-security-reminders/README.md)。debug、单元、androidTest 构建通过；提醒流程及 N07/N18 截图 5/5，N07/N08/N17 截图 3/3。模拟器最终截图复制被自动审批服务因额度限制拒绝，未形成项目内视觉终审；小米 11 Ultra 未安装本轮包。N07 返回草稿、N18 独立备注/即时上传及 N06/N09–N11 仍待处理，待服务恢复后先补最终截图再继续验收。
- 2026-10-25 安全/备份/导出追加：前条截图复制中断已恢复，N07/N08/N17 最终截图已归档并复核；N08 首屏压缩严格模式说明，N09 备份状态与立即上传前置，N10 增加真实密钥状态重点卡，N11 纵排日期、收起详细范围、前置导出方式。产物与画板差异见 [代码及截图记录](../verification/2026-10-25-ardot-backup-export/README.md) 和 [逐页矩阵](../design/Ardot-App页面对齐矩阵-2026-10-25.md)。`assembleDebug/testDebugUnitTest/assembleDebugAndroidTest` 成功；模拟器 N08–N11 与导出 8/8、最终云盘/导出 10/10 通过，N09/N11 最终截图复核。仅完成代码与模拟器验证，11 Ultra 未安装本轮包；N06 功能区、N09 清理/错误态、N10 恢复向导、N11 有效导出态及大字/深色/真机仍待处理。
- 2026-10-25 N06 提醒设置追加：加入通知权限、产检、用药、自定义提醒与调度检查五个状态入口，点击可定位到原有详细控制区；[本轮截图及验证](../verification/2026-10-25-ardot-reminder-index/README.md)。构建/单元/androidTest 构建通过；初轮截图测试因同名标题定位冲突失败 1 项，改用新入口后模拟器 5/5 通过，截图复核首屏层级接近 N06。尚未在 11 Ultra 验证；图标语义、未授权/关闭态、大字及其他画板仍待收尾。
- 2026-10-25 N21/N22 资料与预览追加：N22 合并导入入口并折叠资料卡次要操作，N21 补当前图片全屏与 PDF 放大翻页截图；代码和截图见 [本轮记录](../verification/2026-10-25-ardot-documents-preview/README.md)。debug/单元/androidTest 构建通过；首轮导入截图因弹窗未关闭失败 1 项，修正测试后导入/空态/预览 4/4，折叠卡/封面关联/预览 4/4，图片全屏截图补测 1/1。仅模拟器验证，小米 11 Ultra 未安装；多资料、失败/回收站、PDF 首页、大字横屏及真机仍待验收。
- 2026-10-26 N12 全局回收站追加：删除批次改为紧凑项目行，详情按需展开；保留规则移至列表后，搜索后打开项目会收起键盘，彻底删除确认按钮改危险色。[代码、截图与验证](../verification/2026-10-26-ardot-trash/README.md)。debug/单元/androidTest 构建通过；分类/搜索后恢复首次因键盘遮挡超时，修复焦点后 1/1；产检保留资料/孕期恢复、药品恢复、空态及确认打开→取消→恢复均通过。截图复核折叠行与红色危险按钮；仅模拟器验证，独立资料/保留资料卡、多条/清理中、大字/深色/11 Ultra 仍待验收。
- 2026-10-26 N12 三类条目收尾追加：单独删除资料、保留资料也改为紧凑行；保留说明放在列表后，点开保留资料仍可预览与重新关联。[最终截图及测试](../verification/2026-10-26-ardot-trash/README.md)。三项构建成功，保留资料/孕期恢复组合 2/2、单独删除资料恢复 1/1；最终说明位置和合成名称调整后组合测试首次出现关联目标点击偶发失败，单例通过，加入滚动定位后组合复测 2/2。三类列表截图已复核；多条、清理中、大字/深色与小米 11 Ultra 尚未验收。
- 2026-10-26 N15 服药历史续作：历史记录改可展开紧凑摘要行，搜索位于列表前，补记和提示后置；详情及编辑/删除保留。[代码、折叠与展开截图、验证](../verification/2026-10-26-ardot-intake/README.md)。debug、单元、androidTest 构建通过；模拟器完整服药流程最终 1/1。Ardot N15 四页签、今日服药/方案历史独立入口、按日计划密度及 N19 首部、大字/深色/11 Ultra 仍未验收。下一步继续 N15 按日计划与 N19，再处理其余设计状态。
- 2026-10-28 N19 补记表单续作：日期、时间前置，药品与方案版本收进选择控件，用量随后显示，说明后置。[改动和验证记录](../verification/2026-10-28-ardot-intake-editor/README.md)。debug、单元、androidTest 构建成功；当前设备与 AVD 列表均为空，未运行模拟器、未采新版截图、未完成视觉验收。下一步设备恢复后跑完整服药流程，复核长药名/大字/键盘，再继续 N15 按日计划。
- 2026-10-28 N15 按日计划续作：日期控件收起，固定计划和当天补记改紧凑可展开行，展开保留记录及操作，补记按钮后置。[代码与验证记录](../verification/2026-10-28-ardot-intake-day/README.md)。debug、单元、androidTest 构建成功；ADB 无设备，点击流程和新版截图未验收。下一步设备恢复后验证按日计划→记录→补记及日期切换，再处理四页签/搜索/方案历史。
- 2026-10-28 N15 四入口续作：顶部接通今天、历史、方案和补记；仅看补记留在历史页，今天页支持药品/时间搜索。[代码与验证记录](../verification/2026-10-28-ardot-intake-navigation/README.md)。debug、单元、androidTest 构建成功；ADB 仍无设备，四入口跳转、搜索和新版截图未验收。下一步设备恢复后先跑完整服药流程并采 N15/N19 截图，再验小屏/大字/深色和方案历史。
- 2026-10-28 N15/N19 设备恢复及验收追加：此前“AVD 列表为空”因未设置项目本地 AVD 路径，现恢复 `PJ_API36_16K`。N15 操作行与文档图标、N19 单行方案选择/分钟时间/状态字段/日历图标完成；[最终代码、截图与测试](../verification/2026-10-28-ardot-intake-navigation/README.md)。debug、单元、androidTest 构建成功，末轮说明行微调后 debug 再构建成功；模拟器完整服药流程最终 1/1。N15 今天/历史、N19 补记截图已复核；多计划、日期切换、大字/深色、长药名及小米 11 Ultra 仍待验收。下一步继续矩阵余项和多状态视觉复核。
- 2026-10-28 N20 产检日历续作：月份操作收在“切换月份”，日期格与卡片间距压缩，日历态新建按钮固定在底部导航上方。[代码、截图与验证](../verification/2026-10-28-ardot-visit-calendar/README.md)。debug、单元、androidTest 构建成功；API36 模拟器首屏按钮可见、跨月/取消/返回最终 1/1，截图复核通过。Ardot N20-final 的十月日期列错误未引入 App；小屏、大字、深色、多预约及小米真机仍待验收。下一步处理矩阵中剩余页面和状态。
- 2026-10-28 N16 实际就诊记录续作：增加实际就诊时间、医院和结果独立字段，schema 14→15 自动迁移；详情展示、备份校验、导出、摘要及彻底删除同步处理。[代码、截图与验证](../verification/2026-10-28-ardot-actual-visit/README.md)。debug/单元/androidTest 构建成功，API36 模拟器旧 schema 1–14 升级、独立保存、界面保存和最终截图专项各 1/1，截图已复核。仅合成数据与模拟器验证；真实旧库、小屏/大字/深色/键盘和小米 11 Ultra 未验收。下一步继续 N22/N21/N12 多状态与其余矩阵项。
- 2026-10-29 N22 多资料与失败态续作：多份资料改紧凑行，操作点开可见；导入中/失败项在图片/PDF 筛选时不隐藏，清除筛选同时恢复全部类型。[代码、截图与验证](../verification/2026-10-28-ardot-documents-states/README.md)。debug/单元/androidTest 构建成功；API36 模拟器多条及失败状态 2/2、最终合成名称截图 1/1，截图已复核。N22 回收站多条/清理中、混合类型、导入中、大字/深色/横屏和小米 11 Ultra 未验收；下一步继续这些状态与 N21 PDF 首页/横屏。
- 2026-10-29 N22 回收站多条续作：两份以上回收站资料默认紧凑，展开保留恢复和彻底删除；清理中显示进度与禁用操作。[代码、截图与验证](../verification/2026-10-29-ardot-document-trash/README.md)。debug/单元/androidTest 构建成功，API36 模拟器两份→展开→恢复→计数 2→1 专项 1/1，截图已复核。清理中动态截图及大字/深色/横屏/小米真机未验收；下一步 N21 PDF 首页/横屏和其他矩阵项。
- 2026-10-29 N21 PDF 首页续作：既有完整 PDF 流程补 `1 / 3` 首页断言和标准缩放截图。[截图与验证](../verification/2026-10-29-ardot-pdf-first-page/README.md)。测试包构建成功，API36 模拟器首页→翻页→跳页→放大→后台清理→删除 1/1；截图已查看。生产预览代码未变，横屏/大字/深色、复杂真实 PDF 和小米真机仍待验收。下一步 N21 横屏与 N22 清理中/混合类型。


























- 2026-11-12 剩余问题排查：按用户“排查项目剩余问题”要求，在项目本地 `PJ_API36_16K`（API36 / 16 KiB 页 / 1080x2400 / 420dpi / 字体 1.0 / 浅色 / 竖屏）复核当前代码基线。JVM **350 项 / 0 失败**（57 类）、lint **0 错误 / 15 警告**；仪器矩阵首轮 **379 项 / 3 失败**，逐条定位后确认为 N04/N06/N22 改版导致的过时断言（非产品回归），已修复并单跑通过。另发现并修复生产侧缺陷：`LogicalSnapshot.schemaVersion` 固定为 14、`SnapshotRelations` 仅接受 `12..14`，而当前库为 schema 15，备份被标成旧版本；改为 `SCHEMA_VERSION=15` + `OLDEST_READABLE_SCHEMA=12`，并新增 `LogicalSnapshotTest.snapshotSchemaVersionTracksCurrentDatabaseSchema` 以导出 schema 编号防止再次漂移。同时纠正数据字典（schema15 / 19 张表 / 新增 `custom_reminders` 与 N16 三字段）、已知限制、备份格式、用户指南、README、测试验收报告的过期版本行。**未修复**：自定义提醒仅进入加密备份与恢复，未进入结构化导出与摘要 PDF（`ExportScope.ALL_SECTIONS` 仍 13 段）；N07 返回草稿、N18 独立备注字段、N22 清理中/混合类型状态、N20 设计稿日期列、签名包与版本号冻结。根因与复现命令见[排查记录](../verification/2026-11-12-remaining-issues-audit/README.md)。仅模拟器与 JVM 验证，未安装真机，未做视觉验收。
- 2026-11-12 追加（最终矩阵与结论）：修复 5 项测试同步问题后，在 `PJ_API36_16K`（16 KiB 页）执行全量 `am instrument`。首次运行的 `app-debug.apk` 时间戳早于本轮生产代码修改（验证的是旧包，虽同为 379/379 但不覆盖 schema 修复），已重新 `assembleDebug` 并复跑：**`OK (379 tests)` / 0 失败 / 0 跳过 / 850.734 s**；JVM **351 项 / 0 失败**（57 类）、lint **0 错误 / 15 警告**。日志 `.local/full-matrix-2026-11-12-verified.log`（旧包运行见 `…-final.log`）。仍开放项与上一级条目一致，不以本轮结果改变任何画板或真机验收状态。
- 2026-11-12 小米 11 Ultra 孕妇全流程真机走查（坚果云真实 WebDAV）：按用户要求以真实孕妇使用者路径走查，覆盖安装 `0.26.0-dev` 后完成建档/产检/资料/用药/服药/日常/健康/回收站/提醒/本地备份/结构化导出，并首次在**真实服务商**打通云端备份与恢复。证据：[走查记录](../verification/2026-11-12-xiaomi11ultra-pregnant-journey/README.md)。
  - 通过：LMP 2026-08-01 → 14周+5天、预产期 2027-05-08 计算正确；产检草稿跨页保留；图片导入 5,885 B 加密保存并生成缩略图；方案次日正确生成 08:00 计划点；补记/日常/体重 62.5 kg 与趋势图正常；测试通知实际投递（channel=reminders/importance=4/PRIVATE）；回收站删除→计数 1→恢复→归零；本地加密备份 44,086 B 回读校验通过；结构化导出 71 条记录 + 1 原件。
  - 云端：坚果云连接 → 设主设备 → 上传 22:25/22:54 两次 → curl 独立核对 commits/snapshots(28,727 B)/objects(5,917+8,497 B) → 用恢复密钥列出 2 快照 → 下载校验 → 预览（孕期3/产检2/原件1/方案2/服药1/健康2/日常2/提醒0）→ 恢复完成；代际 active=`ea675306…`、previous=`0a340084…`，原件两代际均存在。错误密钥被正确拒绝且未改动数据。
  - 问题（7 项）：**P0-1** 父目录留空时连接测试失败（坚果云根目录 `PUT` 404）；**P0-2** `.yunji-probe-*` 隐藏目录 `DELETE` 403 导致清理死锁；**P1-3** 结构化导出无 `custom_reminders`（13 段口径）；**P1-4** 摘要 PDF 按钮在结构化导出后点击无响应；**P2-5** 键盘推动布局致连续输入落错字段（本轮 2 次）；**P2-6** 子页连按返回穿透到系统设置；**P2-7**「立即上传备份」需向上滚动才可见。
  - 已修复 P0-1/P0-2：`CloudProbe` 探测改写入自建非隐藏子目录（目录不存在则不写文件）、`CloudConnectionRepository` 清理兼容旧 `<name>.bin` 且先删子项、UI 父目录说明改为要求填子目录；新增 2 项 `CloudProbeTest` 回归（根目录拒 PUT 仍能探测、探测目录非隐藏）。JVM **353/0**、lint **0 错误/15 警告**。坚果云硬限制（根目录 DELETE 恒 403）如实记录，不改写为已解决。
  - 未做：本轮未在修复后重新完整跑一遍真机上传/恢复（仅 JVM + curl 序列验证）；未安装到真机的 P1/P2 项待排期；云端遗留 `.yunji-probe-test-001/002` 需用户在坚果云网页端手动删除。
- 2026-11-13 真机走查问题修复：按用户「修复排查出来的问题」逐项处理。
  - P1-3（导出缺自定义提醒）：`ExportScope.ALL_SECTIONS` 13→14、`ExportFieldCatalog` 新增该段 13 字段、`ExportRowMapper.customReminders`、`ExportService` 按孕期/日期/回收站读取、`ExportScreen` 段名「自定义提醒」；新增 `StructuredExportTest.customRemindersAreExportedWithCataloguedFields`。真机导出页显示「已选择 14 类资料」，zip 含 `data/custom_reminders.json`（`PrenatalCheckup`/`DAILY`/`enabled=true`），总记录 72。
  - P1-4（摘要 PDF 按钮无响应）：根因是系统保存器返回 → `coverSession()` → `clearSensitiveCloud()` → `ExportController.leave()` 把 `plaintextAcknowledged` 清空，返回后 `canStart=false` 按钮静默禁用。修复：`leave()` 只清运行态与结果、保留范围与明文确认；新增 `reset()` 供 `onCleared()` 使用；`exportLauncher` 改为每次读取（避免持有重建前的旧引用）；`start()` 捕获启动器异常并显示提示；`MainActivity.launchFile` 在 `busy` 时给出提示。新增 `ExportControllerTest` 5 项（两类导出都走启动器、必须确认明文、缺启动器报错、返回后仍可导出、`reset` 清空）。真机：导出 PDF → 取消 → 再导出，两次均弹出保存器并生成 `孕迹就诊摘要-2026-11-13.pdf`（4 页 / 277,218 B / 回读校验通过）。
  - P2-5（长表单焦点）：`FormField` 加 `BringIntoViewRequester` + `onFocusChanged`，焦点后延迟 150ms 滚入视野。真机点击「检查项目」后字段自动滚到键盘上方。
  - P2-6/P2-7 复查撤销：返回穿透是自动化脚本连按返回所致（`NavDisplay.onBack` 根页不消费属正常）；上传入口进入即在首屏，先前记录为脚本滚动残留。
  - 验证：JVM **359/0**（58 类）、lint **0 错误/15 警告**、API36/16KiB 全量 **`OK (379 tests)` / 0 失败 / 0 跳过 / 813.414 s**（`.local/full-matrix-2026-11-13.log`）。本轮仅模拟器 + 真机交互验证；未新增视觉验收，未改变画板验收状态。
- 2026-11-13 P0-1 深挖（父目录留空 + 坚果云）：上一轮把 P0-1 记为已修，但真机复测仍出现 `Failed requirement.`。本轮定位到两个独立根因并闭环。
  - 根因 A（服务商约束）：`CloudProbe` 探测目录名 `yunji-probe-<uuid>` 共 48 字符；坚果云返回 `400 IllegalArgument / sandbox name is too long`。curl 逐长度实测：20/24/28 → 201；32/36/40/44/48 → 400。修复为 `yjp-<8 hex>`（12 字符）。
  - 根因 B（自引入）：`CloudConnectionStore.beginProbe/pending` 的校验正则仍强制 `.yunji-probe-<uuid>`，改名后 `require` 无消息抛 `Failed requirement.` 并被原样展示。修复：正则兼容 `yjp-<8 hex>` 与历史 `.yunji-probe-<uuid>`，补中文消息；`CloudConnectionController.message()` 对空/英文内部断言改输出可读中文。
  - 附带：`CloudBackupCoordinator.run()` 需要恢复密钥，但 UI 无引导；`CloudBackupController` 增加 `recoveryConfigured` 前置检查，提示移到上传按钮正上方（原先在页面下方另一卡片，易被视口裁掉）。
  - 验证：临时 JVM 测试直连坚果云 → `PROBE_OK: writable=true verified=true`；新增 `JianguoRootFolderProbeTest`（凭据经 instrumentation 参数传入）→ `OK (1 test)`；模拟器 UI 全流程 → 父目录留空「连接可读写，测试文件已回读校验」→「连接已加密保存」→「最近上传：11月12日 17:10」→ curl 核对云端 2 snapshot（955 B）+ 2 commit（660 B）；缺密钥时按钮上方显示「请先在「本地备份」生成并核对恢复密钥，再上传云端备份。」
  - 回归：JVM **363/0**（59 类）、lint **0 错误/15 警告**、API36/16KiB 全量 **`OK (380 tests)`**（首轮）+ 最终轮日志 `.local/full-matrix-2026-11-13-final.log`。诊断用 `YJ_UPLOAD` 日志与 `TempJianguoProbeTest` 已全部移除。
- 2026-11-13 P0-2 清理死锁闭环：`CloudConnectionRepository` 清理遗留探测目录时，服务商拒绝删除（坚果云根目录 `DELETE` 恒 403）不再抛异常——新增 `UNDELETABLE={403,405,501}`，三处清理（旧 `<name>.bin`、目录内子项、目录本身）均容忍该状态，并在清理尝试后无条件 `store.probeCleaned()` 清除本机标记，消除「必须清理才能重测」死锁。`CloudProbe` 收窄 `cleaned` 语义（内容已清理），目录删除结果单独记录；`canDelete` 仍如实反映服务商限制。新增 `CloudProbeTest.leftoverProbeDirectoryThatProviderRefusesToDeleteStillAllowsRetest`（mock 增加 `denyDirectoryDelete` 精确模拟）与设备测试 `JianguoRepeatedProbeTest`（真实坚果云、父目录留空、连续两轮 + 保存）。实测两轮均 `writable/verified=true`、`cleanupPending=false`、`canDelete=false`、`saved=true folder=[]`。回归：JVM **364/0**（59 类）、lint 0 错误/15 警告、矩阵 **`OK (381 tests)`**。真机侧新连 Xiaomi 14 Pro，因锁屏与 MIUI 仪器进程 DNS 限制未完成复测，已如实记录。
- 2026-11-13 403 容忍后完整上传复验：模拟器 + 真实坚果云 + 父目录留空，走完整 UI：建档 → 恢复密钥生成并核对（第1组 ZUQMLY / 第8组 dlYTfq）→ **连续两次**测试连接均「连接可读写，测试文件已回读校验」（无待清理、无 `Failed requirement.`）→ 保存连接 → 立即上传 → 「最近上传：11月12日 18:33」+「云端备份已提交并回读验证」。curl 核对新 vault `4449b60c-52e4-4326-b9ac-ca2940f7e2ca`：vault.json 265 B、snapshot 955 B、commit 660 B；下载后本地计算 sha256 = `5fcc2309…a278d`，与 commit `cipherSha256` 完全一致，尺寸/snapshotId 均匹配，信封头 `PJE\x01`——提交正确绑定快照。真机未完成：11 Ultra 无线调试端口全拒（37633/5555/40585/34457），需机主重开；Xiaomi 14 Pro 锁屏 + MIUI 限制仪器进程 DNS（shell curl 207），IP 直连证书校验失败（000）。回归：JVM 364/0、lint 0 错误/15 警告、矩阵 `OK (381 tests)`。
- 2026-11-13 真机定向对照实验（排除 isOneShot 假设）：在 Xiaomi 14 Pro（`<局域网地址>`，锁屏状态、无需 UI）用生产 `WebDavClient` 直连坚果云做 6 组对照：`put(bytes)`/`putFile`(one-shot) 各在 2 段与真实 4 段备份路径下、以及 20 次 404 GET 后再 PUT——**全部成功**（219–7545 ms），证明 body 类型、多级路径、`If-None-Match`、连接污染、DNS 均非挂起原因。追加实验：同流程后静置 35 秒再 PUT → **挂起复现**，与真实上传（PUT 前已运行约 30 秒）吻合；且 `callTimeout(60s)` 不触发，说明进程被执行环境暂停而非网络层超时。诊断产物（`DavPutBodyComparisonTest.kt`、`YJ_R`/`YJ_PF` 日志）已全部删除，`grep` 无残留；JVM **364/0**、lint **0 错误/15 警告**。真机上传复验仍需解锁后执行。
- 2026-11-13 云端测试残留清理与再次复验：发现 `pregnancy-journal/` 下累积 25 个 vault（23 个来自反复测试的隔离子库），使 `verifiedCommitWriterIds` 每次上传前需认证全部 commit（18 个时约 13s）。已逐级清理，保留 2 个有真实数据的 vault，`deleted=23 failed=0`。清理后前置耗时下降但真机上传仍在 PUT 阶段无返回；`am start` 后 `topResumedActivity` 仍为其他应用，确认锁屏阻断前台 Activity。真机复验需机主解锁，或直接在手机上走 UI 完成后再用 curl 核对。JVM 364/0、lint 0 错误/15 警告保持。
- 2026-11-13 真机上传成功证据与结论修正：复查云端发现 `pregnancy-journal/cdb8c41a-e4ac-4276-a8bd-74b2159783f0/`（getlastmodified 11-13 04:33）含完整 vault.json(265B)/snapshots(512B)/commits(660B)，下载后本地 SHA-256 `16af83a3…b0f5` 与 commit `cipherSha256` 完全一致、snapshotId 与文件名一致、信封头 `PJE\x01` —— 即**真机已完成「保存连接 → 立即上传备份 → commit 绑定 snapshot」全链路**。此前判定"未完成"是因为该次 `am instrument` 因进程暂停未返回 OK，未核对云端产物。同时清理 2 个仅含 objects 的空 vault（2c66f1b1、5db54072）。准确表述：真机上传链路可用，部分运行会被锁屏静置暂停但不影响已完成的 PUT/COMMIT。JVM 364/0、lint 0 错误/15 警告。
- 2026-11-13 **P0 根因：HTTP/2 流级超时（已修复）**。设备解锁后重跑真机测试，取得明确异常 `SocketTimeoutException at okhttp3.internal.http2.Http2Stream$StreamTimeout`——此前所有"设备网络策略/进程冻结"的推断均不成立，真正原因是与坚果云协商 HTTP/2 后的流级超时（也解释了 curl(1.1) 成功、`callTimeout` 不触发、偶发成功）。修复 `WebDavClient` 固定 `.protocols(listOf(HTTP_1_1))`；新增 `WebDavClientTest.clientAlwaysNegotiatesHttp11` 并用"临时移除修复→测试失败"验证其有效性。验证：真机 `JianguoRepeatedProbeTest` OK(1)/13.4s；模拟器 `JianguoUploadVerifyTest` OK(1)/20.7s，`lastPhase=COMMITTED`；curl 核对 `cipherSha256` 与实测 SHA-256 完全一致（`fb0f21b5…b8f4`）、cipherSize 512、snapshotId 匹配、信封头 `PJE\x01`。附带记录：设备密钥轮换后旧加密状态未自动清理（`AEADBadTagException`），列为后续改进项。
