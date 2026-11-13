# 界面风格一致性审查（2026-10-25）

## 范围和证据

- 当前源码构建的 debug APK 与 androidTest APK 安装于 Android 36 / 1080×2400 / 420dpi 模拟器，运行 `UiSnapshotTest`、`UiSnapshotCoverageTest`，`OK (27 tests)`。
- 本次新截取 31 张截图，存于 [`../2026-10-24-style-audit/current/`](../2026-10-24-style-audit/current/)；数据均为测试夹具。对照当前首页、产检、记录、我的，以及 Figma 已定的浅底、深绿、白卡、柔和绿、24dp 边距、统一页头与按钮样式。
- 小米 11 Ultra 默认包启用安全截图限制；本次是模拟器视觉审查，不宣称真机逐屏视觉已复核。截图也不能证明读屏与动态字体状态。

## 逐组结论

| 步骤 | 页面与截图 | 状态 | 具体问题或结论 |
|---|---|---|---|
| 1 | [今天](../2026-10-24-style-audit/current/figma-sync/01-today.png)、[产检](../2026-10-24-style-audit/current/figma-sync/02-visits.png)、[记录](../2026-10-24-style-audit/current/figma-sync/03-records-hub.png)、[我的](../2026-10-24-style-audit/current/figma-sync/04-profiles.png) | 统一 | 色板、页边距、主按钮和白色记录行一致；测试夹具日期空态与设计示例数据不同。 |
| 2 | [孕期时间线](../2026-10-24-style-audit/current/figma-ui-alignment/timeline.png)、[产检详情](../2026-10-24-style-audit/current/figma-ui-alignment/visit-detail.png)、[用药方案](../2026-10-24-style-audit/current/figma-sync/05-medications.png)、[服药记录](../2026-10-24-style-audit/current/figma-sync/06-intakes.png)、[健康指标](../2026-10-24-style-audit/current/figma-sync/07-measurements.png) | 基本统一 | 卡片与按钮体系一致；服药记录的日期显示仍是 ISO，与其他展示文字的中文日期样式不同。 |
| 3 | [原始记录详情](../2026-10-24-style-audit/current/figma-ui-alignment/timeline-source-detail.png) | 明显偏离，P1 | 标题下连续堆叠日期、时区、症状、心情与重复的备注标题；缺少列表/详情惯用的白卡和分组，阅读层次弱。建议用摘要卡与分组字段，保留删除的独立危险区。 |
| 4 | [云盘连接及备份/清理](../2026-10-24-style-audit/current/figma-ui-alignment/cloud-connection-cleanup.png)、[导出资料](../2026-10-24-style-audit/current/figma-ui-alignment/export-screen.png)、[本地备份](../2026-10-24-style-audit/current/figma-ui-alignment/local-backup.png)、[恢复](../2026-10-24-style-audit/current/figma-ui-alignment/restore-wizard.png) | 部分偏离，P1 | 云盘状态、检查结果和清理统计直接铺在背景上，出现截断的快照标识；导出范围是长串无容器复选行，视觉上像系统设置页。建议按“状态/操作/高级信息”分组为白卡，技术标识折叠到详情；导出项用分组选择卡。备份与恢复页主按钮/配色一致，长说明可缩短。 |
| 5 | [提醒设置](../2026-10-24-style-audit/current/figma-ui-alignment/reminder-settings.png)、[新建提醒弹窗](../2026-10-24-style-audit/current/figma-ui-alignment/custom-reminder-editor.png)、[安全设置](../2026-10-24-style-audit/current/figma-ui-alignment/security-settings.png) | 部分偏离，P1/P2 | 设置页白卡、标签和开关基本统一；新建提醒弹窗的“取消/保存”都只是文字，主次关系弱，日期/时间文字入口也比表单日期控件更隐蔽。安全设置的锁定时间选项纵向单列且间距大，三项占用较多首屏空间。 |
| 6 | [档案编辑](../2026-10-24-style-audit/current/figma-ui-alignment/pregnancy-editor.png)、[预约编辑](../2026-10-24-style-audit/current/figma-ui-alignment/visit-editor.png)、[测量编辑](../2026-10-24-style-audit/current/figma-ui-alignment/measurement-editor.png)、[日常编辑](../2026-10-24-style-audit/current/figma-ui-alignment/journal-entry-editor.png) | 轻微偏离，P2 | 表单结构/按钮统一；日期值用 `yyyy-MM-dd`，而列表和详情普遍用中文日期。测量页还显示 `HH:mm:ss` 与 `GMT`，对普通用户显得偏技术。建议只调整展示格式，存储格式维持原样。 |
| 7 | [产检资料](../2026-10-24-style-audit/current/figma-sync/08-visit-documents.png)、[药品管理](../2026-10-24-style-audit/current/figma-ui-alignment/drug-catalog.png)、[回收站](../2026-10-24-style-audit/current/figma-ui-alignment/trash-empty.png) | 基本统一，有一处 P2 | 白卡和空态一致；资料卡中的大幅空白预览区域压过标题、状态与操作，信息重心失衡。需在不同图片/PDF 缩略图条件下复核后确定统一预览比例。 |

## 优先顺序

1. **P1：原始记录详情**统一白卡与字段层级；**P1：云盘状态/导出资料**统一内容分组，减少直接铺陈的技术状态。
2. **P1：新建提醒弹窗**强化保存主操作与日期/时间入口。
3. **P2：表单日期/时区/时间展示**采用与其他页面一致的友好格式；**P2：资料缩略图**控制高度；**P2：安全设置**压缩锁定时间选项占高。

本次仅审查和记录，未修改页面代码；优先级表示视觉整改建议，不等同功能缺陷或已实现。
