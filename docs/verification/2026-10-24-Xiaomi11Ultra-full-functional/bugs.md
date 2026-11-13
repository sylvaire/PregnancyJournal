# 小米 11 Ultra 全功能调试：Bug 与观察清单

设备：Xiaomi 11 Ultra（`M2102K1C` / `star`，MIUI `V14.0.22.12.5.DEV` / Android 13 / SDK 33），应用 `0.26.0-dev` / versionCode 27 / schema14（debug，全新安装）。
本轮为第一轮功能走查（显示 / 添加），删除、备份、上传、恢复与 UI 对齐在后续轮次继续。

## Bug

### Bug #1（已修复并真机回归）：用药/服药页仍有“通知提醒未提供/未启用”的过期文案

- 现象：用药方案页底部写“记录医生交代的方案，可在服药记录中确认、跳过或补记。**通知提醒仍待后续提供。**”；新增/修改用药方案表单底部写“录入时区：Asia/Shanghai · **通知提醒尚未启用**”；服药记录页底部写“……不提供补服或剂量建议。**通知提醒尚未启用。**”
- 实际行为：C05 已经实现用药提醒——同一设备上创建方案后，`reminder-state` 出现 7 天 × 每日时刻的 `MEDICATION:*` 调度键，`dumpsys alarm` 也有对应 `RTC_WAKEUP` 闹钟（见 `flows/02-04-reminder-state.txt`），到点通知链路已在小米 11 Ultra 上实际验证通过（2026-10-24 早些时候）。
- 影响：文案与真实能力矛盾，会让用户以为用药提醒不可用；属于用户可见的误导信息。
- 位置：`app/src/main/java/cn/yunji/journal/feature/MedicationScreens.kt:34`、`:110`；`app/src/main/java/cn/yunji/journal/feature/IntakeScreens.kt:101`。
- 修复：更新为准确文案——用药方案页改为“用药提醒按方案时刻生成，受通知权限与系统省电策略影响。”；用药表单改为“录入时区：… · 用药提醒按本机时区计算”；服药记录页改为“计划提醒按方案时刻与通知设置发送。”。仅改 2 个文件 3 处字符串，未改行为。
- 回归（2026-10-24 02:35–02:40，小米 11 Ultra，安装修复后的 `0.26.0-dev`/vc27）：用药方案页、用药表单底部、服药记录页均显示新文案，旧字符串不再出现；`:app:assembleDebug` 与 `:app:testDebugUnitTest` 构建/测试成功。证据：`flows/06-fix-medication-list-copy.xml`、`flows/06-fix-medication-form-copy.xml`、`flows/06-fix-intake-copy.xml`。

## 观察（未判定为 Bug，待下轮复核）

1. **Android Photo Picker 导入的图片文件名**：本机推送的 `m6-doc.png` 经 Photo Picker 选择后，资料列表显示为 Provider 生成的 `1000000164.jpg`，而不是原文件名。需确认是否应优先使用 `OpenableColumns.DISPLAY_NAME`；该行为也可能由系统 Photo Picker 的 URI 决定。
2. **资料元数据编辑**：用脚本尝试“改名/改分类/改日期/填医院备注→保存”时，表单回到了列表但列表仍显示旧名称/分类。由于同一轮里 adb 文本输入 + 返回键在 MIUI 14 上多次导致页面返回（见下），**暂不能判定为产品缺陷**，下轮用更稳的输入方式复核。
3. **MIUI 14 的 adb 文本输入**：`adb shell input text` 遇到空格会截断（如 “M6 Hospital” 只输入 “M6”）；同时输入法弹出时机不稳定，`keyevent 4`（返回）在键盘未弹出时会直接返回上一页，导致脚本误操作。属测试方法限制，已在记录中注明。
4. **uiautomator 读取底部导航 bounds**：MIUI 14 上底部四个一级入口在 dump 中 bounds 为 `0,0-0,0`（文本存在），按坐标点击仍可用；属工具/系统差异，不判为产品问题。
5. **应用截图**：`MainActivity` 全窗口 `FLAG_SECURE`，adb 截图应用内容为空文件（系统 UI 可截）。属隐私设计，不是 Bug；UI 视觉核对改用模拟器 Compose 截图。
6. **导入提示**：选择文件后出现“文件已加入处理队列。”Snackbar，需要手动“知道了”关闭；导入在后台完成（`ImportWorker` SUCCESS），当前不判为问题。
7. **MIUI 14 安装/输入限制**：默认需在开发者选项开启「USB安装」「USB调试（安全设置）」才能 adb 安装与模拟点击；属 OEM 限制，已在阶段记录中记录。


## UI 与 Figma 对齐问题（来自 `2026-10-24-figma-ui-alignment/comparison/`，待修复）

评审方法：19 张 Figma 设计屏（2x 导出）vs 22 张 API36 模拟器 Compose 实现截图 + 像素/几何测量；真机因 `FLAG_SECURE` 无常截图，结论为模拟器视觉级。详细逐屏表与证据见 [comparison/README.md](../2026-10-24-figma-ui-alignment/comparison/README.md) 与 `findings.csv`。

**P0（明显，建议优先修复）**
1. **筛选/标签体系与设计不符**：设计为 48 dp、选中主色填充+白字；实现用 M3 `FilterChip`（约 32 dp、选中浅绿底）。影响产检/时间线/回收站/服药/健康/资料/安全等全部列表页。
2. **M3 默认容器色未纳入主题**：对话框底色出现 `#ECE6F0`、Switch 未选中轨道 `#E6E0E9` 等 M3 默认紫灰，脱离暖白绿色板，影响 20+ 对话框/菜单/Snackbar。属明显视觉不一致。
3. **按钮体系混排**：Recovery/Restore/Export 页面使用 40 dp 原生 M3 按钮，而设计/其余页面是 52 dp `JournalButton` 胶囊。
4. **二级页头部层级**：设计为 `PageHeader`（28/42 Bold + “返回 xxx”副标题），实现为 M3 TopAppBar 20 sp 标题且无副标题。
5. **12 Timeline 整体偏离设计**：设计为 72 dp 记录行 + 日期分组/孕周 + 杏色备注卡 + 底部 CTA；实现为大卡片信息流。属信息架构差异，需用户确认“设计稿回改还是实现回改”。
6. **页边距三套并存**：24 dp（设计）/20 dp（新页面）/带 4 dp 叠层（提醒设置）——建议统一 24 dp。

**P1（轻微，随版优化）**
7. 日期/时间展示格式不统一（`2026-10-23` vs 设计中 `10月17日`）；数字与中文间空格不一致。
8. 图标语义不统一（记录枢纽/我的、底部导航“记录”）。
9. 空态样式未按设计 17 的范式（浅绿卡+图标+标题+说明+按钮）；回收站空态最弱。
10. 页面 AppBar 标题与内容 H1 重复。
11. 危险操作底/横幅未用设计中的 `dangerSoft #FCECE7`。

**未设计界面风格结论**：色板、字号层级、圆角、卡片、`JournalButton` 主/次按钮与设计令牌高度一致；偏差集中在上述 P0 项与新页面沿用原生 M3 控件、丢弃卡片/记录行范式。可评的 10 项未设计界面全部存在偏差（明显 3：回收站空态、导出资料、自定义提醒弹窗；轻微 7）；6 项因无截图无法评估。

**待用户确认**：Timeline/备份拆页/提醒位置等 IA 差异是“设计稿回改”还是“实现回改”；Figma 第 20 屏（RecoveryKey）导出缺失与「06 · 当前实现」同步板 50:581 消失。

## Bug #2（已修复并真机回归）：恢复预览把已“彻底删除”的孕期墓碑计入“孕期档案”数量

- 现象（小米 11 Ultra，2026-10-24 03:1x）：本地加密备份后进入「从备份恢复资料」→ 选择 `.yunji` → 输入恢复密钥 → 预览显示 **孕期档案 2 / 产检记录 1 / 资料原件 2 / 用药方案 1 / 服药记录 1 / 健康记录 0 / 日常备注 1 / 自定义提醒 0**；恢复完成后「管理孕期」只有 **1 个**（M6-Test，进行中），「全局回收站」为 **0**。
- 根因：pregnancy 的“彻底删除”并不物理删行，而是把记录改写成墓碑（`TrashRepository.kt` purge 分支：`title="已删除孕期"` 等字段清空、`removeDateRevisions`），删除批次变为 `PURGED`；备份快照 `ObjectProtection.capture` 使用 `dao.allPregnancies()` 包含所有行，恢复预览直接 `snapshot.pregnancies.size`，于是把不可见的墓碑也计入，和 UI 可见数据不一致。
- 修复：`core/backup/RestoreWizard.kt` 的预览计数改为排除 `PURGED` 批次涉及的记录（按 `deletionBatchId`/批次的 `kind+rootId`/所属孕期过滤），备注说明“回收站中尚未清理的记录仍计入”。只改预览计数，不改备份内容与恢复行为。
- 回归：构建 + JVM 测试通过后安装到 11 Ultra，对同一备份再次预览：**孕期档案 1**，其余计数不变；恢复流程不受影响。证据：`flows/07-restore-preview-counts-before-fix.xml`（修复前 2）、`flows/07-restore-preview-counts-after-fix.xml`（修复后 1）。

## Bug #3（已修复，待专项回归测试补充）：键盘弹出时内容区被双重 IME 内边距压缩

- 现象/证据（2026-10-24 UI 头部修复期间由 `ui-header` 的失败态 uiautomator 证据发现）：文档列表搜索框聚焦、键盘弹出后，`document-list` 的可见视口仅约 **106.7dp 高**（bounds `[0,354][1080,634]`），列表几乎不可用；测试中“清除搜索与筛选”空态按钮落在视口外，点击未命中。
- 根因：`JournalPage` 的 Scaffold 使用 `contentWindowInsets = WindowInsets.safeDrawing`（Compose 的 `safeDrawing` **已包含 IME  inset**），内容 Box 又额外加了 `.imePadding()`；`RestoreScreen` 的 Column 还再叠加一次 `.imePadding()` → 键盘出现时内容被重复扣减，视口被压到极小。
- 修复（2026-10-24）：移除 `JournalNavigation.kt` 内容 Box 的 `.imePadding()`（保留 SnackbarHost 的 imePadding）与 `RestoreScreen.kt` Column 的 `.imePadding()`；IME 由 Scaffold 的 safeDrawing 内边距统一处理。未改其他布局/行为。
- 回归：构建 + JVM 通过；`NavigationExperienceTest` 整类 23/23 通过（含搜索/编辑等键盘路径）。**专项视口回归测试待补**（建议新增“键盘弹出时 document-list 视口高度 ≥ 300dp”的布局断言），此前失败态证据见 `docs/verification/2026-10-24-ui-header-fix/_tools/`（debug-failing-state-viewport.png/ui.xml）。
8. **系统组件文案随系统 locale（P2/i18n 观察）**：模拟器（en-US）上 M3 `DatePicker` 显示 "Select date"/"October 2026" 等英文，而应用界面为中文，形成中英混排；小米 11 Ultra（中文系统）不受影响。属应用未声明固定 locale / 未做 i18n 的已知边界，记录为非阻断观察，若需双语支持再补资源。

## Bug #4（已修复，待模拟器 UI 回归）：云盘连接“空账号”提示误导

- 发现：2026-10-24 模拟器补位走查（task-11，`docs/verification/2026-10-24-emulator-functional-coverage/README.md` §4.1）：云盘连接表单中账号留空时，提示语为「请检查账号，不能包含冒号或控制字符」，对“未填写”场景有误导。
- 根因：`core/network/CloudConnection.kt` 的 `normalized()` 把 `isNotBlank()` 与“不含冒号/控制字符/长度限制”合并在同一个 `require` 中，共用一条错误文案。
- 修复：拆分为两条校验——空账号提示「请填写账号」；长度/冒号/控制字符仍提示「请检查账号，不能包含冒号或控制字符」。仅改文案分支，不改变校验规则。
- 回归（2026-10-24）：构建 + JVM 通过；emulator-5556 上 `NavigationExperienceTest`（已同步更新断言：账号为空→「请填写账号」、含冒号→「请检查账号，不能包含冒号或控制字符」）+ `CloudConnectionUiTest` 共 **OK (27 tests)**，EXIT=0。日志：`docs/verification/2026-10-24-emulator-functional-coverage/_tools/lead-rerun-nav-cloud-bug4-fix.log`。


## Bug #5（已修复并真机验证，2026-10-24 傍晚）：彻底删除资料/孕期后，legacy 密文原件文件未从对象目录回收

- 现象（小米 11 Ultra，2026-10-24 17:5x–18:2x，最新 APK）：
  - 恢复备份（03:14）后删除 PDF→「彻底删除」、删除图片→「彻底删除」，再整体「删除孕期→彻底删除」；
  - 每次 UI 均显示成功（回收站归零、空态正常），`TrashWorker`/`ImportWorker` 返回 `SUCCESS`，但 `no_backup/vault/objects/` 始终保留 **4 个 .bin 密文文件**：
    `1a18a00b…bin` 3,143 B、`88441908…bin` 35,745 B、`a7ebfa43…bin` 4,076 B、`f938857f…bin` 6,389 B（mtime 仍为 02:06/02:08，早于恢复时间）；
  - 强制运行待处理 WorkManager 任务、重启应用/后台后再查，文件仍在；孕期彻底删除后再次检查仍为同样 4 个文件。
- 影响：磁盘空间泄漏；与「彻底删除…无法恢复」的用户承诺不一致（文件仍驻留本机，虽为 SQLCipher/GCM 密文，需密钥才能读取）。属隐私/存储类缺陷，优先级高。
- 初步定位：`ObjectProtection.purgeLocked` 对“被 pin 或被其他附件引用”的对象只写入 `retired_objects` 延后删除，`drainLocked` 在引用消失后才真正 `delete()`；当前现象符合“删除被延后且未再 drain 成功”的路径。对象 mtime 早于恢复，疑似与恢复后的对象目录/代际或残留 `object_pins` 有关，需在设备上进一步核对 `object_pins`、`retired_objects` 与对象存储目录（`vault/objects` vs `vault/gen/<id>/objects`）。
- 复现证据：`docs/verification/2026-10-24-device-debug-round14/objects-after-pregnancy-purge.txt`、`dumps/12-pregnancy-purge-confirm.xml`、`dumps/13-trash-empty-after-purge.xml`、`README.md` §0。
- 根因（已确认）：恢复前资料库为 **legacy 布局**（`vault/journal.db` + `vault/objects`）；从 legacy 布局恢复时 pointer 从 `activeId=null` 切到新代际且 `previousId=null`，既有回收只处理 previousId 指向的代际目录，legacy 数据库/对象目录从不清理；「彻底删除」只作用于当前代际 `gen/<id>/objects`，故 legacy 密文原件永久残留。\n- 修复：`VaultLayout` 新增 `legacyLeftovers()`/`reclaimLegacyLayout()`（rename→fsync→delete，可中断续做，active 代际不可触碰）；`RestoreReclaim.inspect()/reclaim()` 在 `previousId=null` 但有 legacy 残留时提供 AVAILABLE（`availableLegacy`）并随确认回收一并清理；新增 3 个单元测试。\n- 回归：JVM 342/342（RestoreReclaimTest 19/19）；11 Ultra 真机安装修复版 → 本地备份页出现「恢复前的旧资料」→ 回收并确认 → `vault/objects`、`vault/journal.db`、`legacy.reclaim` 全部消失、active 代际完好；新孕期「彻底删除」后 `gen/<id>/objects` 为空。证据：`../2026-10-24-device-debug-round15/README.md`。\n- 状态：**已修复并真机验证**。

## Bug #6（已修复并真机验证，低优先级）：回收站“删除于”仍使用 ISO 日期

- 现象（同轮真机）：全局回收站条目显示「删除于 2026-10-24」，而 P1-7 已将其他展示层统一为「M月d日 / yyyy年M月d日」；`TrashScreen` 展示遗漏一处。
- 影响：日期展示不一致（观感），无功能风险。
- 修复：`TrashScreen.deletionDate()` 改用共享 `displayDate()`（同年 `M月d日`）。\n- 回归：11 Ultra 真机回收站显示「删除于 10月24日」；JVM 通过。状态：**已修复并真机验证**。

