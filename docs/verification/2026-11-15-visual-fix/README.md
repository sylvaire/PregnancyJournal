# 组件视觉问题修复与验收

日期：2026-11-15。任务：M6-VISUAL-FIX。基线：[7项诊断](../2026-11-15-visual-review/README.md)。

## 实施清单

| 问题 | 当前实现 | 对应代码 |
|---|---|---|
| V01 名称/状态被各半宽度挤窄 | 按完整内容测量，放得下时分列；不足时上下排列，保留所有名称和原值 | Components2.JournalContentHeading |
| V02 深色删除抢眼 | 整行危险按钮使用警示描边，与深浅色保持同一视觉层级；保留点击区域和原确认流程 | Components.JournalDangerButton |
| V03 药品管理套卡过多 | 管理区只留一层普通卡片与邻近行内操作；不重复药名，选中药品补主色描边 | DrugScreens.DrugCatalogScreen |
| V04 首页空态过重 | 无日期用普通卡片/20sp标题，去重复状态；有正常孕周保留原主摘要。服药仅未完成计划用强调底色 | JournalApp.HomeScreen、IntakeScreens.HomeIntakeSummary |
| V05 图标遗漏 | 药品统一胶囊，时间线使用历史，首页健康快捷使用波形 | DrugScreens、TimelineScreens、JournalApp |
| V06 次要操作规则不清 | 两个次要按钮入口共用同一描边实现；主操作填充、行内普通文字、危险操作描边 | Components、Components2 |
| V07 浅色轮廓过淡 | outline交互边框改为#91A294，outlineVariant装饰分隔保持#DDE4DA | Theme、JournalDivider |

关联修正：错误提示内的“重试”改为普通动作；禁用次要按钮的边框随禁用态淡化。没有新增按钮下说明或恢复中间分隔线，没有变更数据库、剂量/数值计算和业务回调。统一使用规则见[组件操作与强调规范](../../design/组件操作与强调规范.md)。

## 截图对比

以下新截图已打开检查；普通深浅色、200%字体竖屏及深色横屏的最终排版也已复核：

| 内容 | 修改前 | 修改后 |
|---|---|---|
| 方案长名称 | [各半宽度，标题两行](../2026-11-15-visual-review/dark/05-medications.png) | [内容测量后名称完整成行](dark/05-medications.png) |
| 药品管理 | [嵌套导航卡与红色实底删除](../2026-11-15-visual-review/dark/drug-catalog.png) | [单层管理区、胶囊、选中边框、描边删除](dark/drug-catalog.png) |
| 预约操作 | [深色实底删除](../2026-11-15-visual-review/dark/visit-detail-actions.png) | [次要/危险均描边、主操作突出](dark/visit-detail-actions.png) |
| 记录图标 | [时间线叶片](../2026-11-15-visual-review/dark/03-records-hub.png) | [历史图标](dark/03-records-hub.png) |
| 首页空日期 | [40sp加整块强调底色](../2026-11-15-visual-review/dark/01-today.png) | [普通卡片与20sp标题](dark/01-today.png) |

最终代表样本：

- [浅色首页](light/01-today.png)、[浅色描边字段](light/drug-editor.png)、[导出设置](light/export-screen.png)
- [浅色药品管理](light/drug-catalog.png)、[浅色预约](light/visit-detail-actions.png)
- [200%方案](large/medications.png)、[200%提醒](large/reminder-card.png)、[200%预约](large/visit-detail-actions.png)
- [横屏方案](landscape/medications.png)、[横屏预约](landscape/visit-detail-actions.png)、[横屏提醒](landscape/reminder-card.png)

大字标题不再被状态挤成多行，版本与重复规则完整成行。药品管理不再有大块绿色套两张导航卡，主操作填充与次要/危险描边的区分在两个主题中一致。长页面超过视口时仍可继续滚动；不把截图边缘的续接当作文字截断。

首页截图的日期状态一致，但首轮基线深色服药摘要尚处于无计划显示，新截图已读取到2项计划；不使用这一差异宣称业务变化或作严格像素比较。浅色基线具有相同计划状态，最终对比应优先使用它。

## 构建、回归及关联问题

默认安全APK、测试APK、lint及JVM[首次构建](visual-fix-build.log)成功1m54s。JVM371项、失败0、错误0、跳过0；lint0错误31警告。

- [普通批次](visual-fix-normal.txt)：52项中51通过、1失败，324.785s。通过的51项含11项截图导航、20项补充页面、9项既有视觉回归、长药品列表、7项编辑/保存/删除/撤销/恢复完整流程与3项提醒流程。
- 失败项为新增长标题测试。先补齐缺少的页面导入后测试包21s构建成功；随后发现短摘要的右对齐字形落在自身宽度外，见[原始测量](visual-fix-heading-glyph.txt)。移除重复TextAlign.End，仍由Row分配将摘要置右。修复后字形范围从583..848px回到0..265px，与自身265px宽度一致，见[中间诊断](visual-fix-heading-final.txt)。
- Paragraph保留父级848px测量宽度，使hasVisualOverflow继续报宽度溢出，即使字形已位于0..265px。最终测试改为逐字符检查真实字形四边是否在文字区域内、无省略且短状态单行；这仍能捕捉修复前583..848px的越界，不是放弃边界断言。[普通定向1/1通过](visual-fix-glyph-check.txt)，3.833s。
- 该生产修订后[构建及lint](visual-fix-final-build.log)成功1m30s，0错误31警告；[浅色8/8](visual-fix-light.txt)，37.395s；[200%浅色竖屏6/6](visual-fix-large.txt)，29.076s，含字形边界、方案/提醒/预约/药品及长列表操作；[200%深色横屏19/19](visual-fix-landscape.txt)，116.194s，含14项全页面矩阵、字形边界及4项重点截图。

最后追加：大字药品搜索提示中“型”字孤立换行，已简化为“搜索药品”，查询仍覆盖名称/规格/剂型；首页进度条轨道改用装饰outlineVariant，避免交互轮廓加深后把非交互轨道也加重。[最终补充构建](visual-fix-polish-build.log)成功1m2s，lint0错误31警告；[普通补充2/2](visual-fix-polish-normal.txt)，12.295s；[200%搜索提示1/1](visual-fix-polish-large.txt)，5.854s。

已打开最终[无日期且无计划首页](dark/home-no-plan.png)、[正常孕周且无计划首页](dark/home-valid-age-no-plan.png)、[大字搜索提示](large/drug-catalog-final.png)：空状态使用普通底色，正常孕周仍保留大字和强调底色，搜索提示不再孤字换行。最后这些局部修订后未重复前面51项完整流程或19项横屏矩阵，不将分批通过写成同一批全量通过。

## 小米更新及验证边界

最终安全APK已通过USB保留资料覆盖更新小米11 Ultra。[原预约复验](visual-fix-phone-actions.txt)：取消与删除均可打开对应确认弹窗，用返回退出，两按钮仍可见；未确认真实取消/删除。页面不恢复按钮下说明或中间分隔线，手机原资料保持。

最后搜索提示/进度条修订的APK也已覆盖安装并启动成功。原预约弹窗复验发生在这一纯文案/装饰修订之前，两处修订不涉及预约按钮回调；没有重复点击手机真实记录的确认动作。

定位过程中曾因页面尚未就绪而未找到原预约，检查已安全停止；随后刷新页面结构、直接定位原预约标题后完成复验。临时本地检查助手现会拒绝使用未成功刷新的旧界面结构，不涉及应用业务修改。

手机安全窗口保持（VISUAL_VERIFICATION=false），视觉证据来自同一实现的专用模拟器与合成内容，不是手机像素验收。操作验收覆盖相关流程，不称全量仪器测试；未做读屏/无障碍调试。检查结束模拟器恢复100%字体、竖屏、自动旋转和原深色。

## 完成验收

| 要求 | 验收依据 | 结果 |
|---|---|---|
| V01内容标题完整与布局平衡 | 普通字形定向1/1、200%竖屏6/6及横屏19/19，方案/提醒对比截图 | 通过 |
| V02删除权重收敛且确认仍可用 | 深浅色预约/药品截图、7项相关流程、手机确认弹窗退出 | 通过 |
| V03药品管理简化且选中与邻近操作保留 | 普通/大字药品截图及长列表操作 | 通过 |
| V04空态收敛且正常孕周突出 | 最终普通补充2/2及两种首页截图 | 通过 |
| V05语义图标统一 | 记录时间线、药品选中行及首页健康快捷图标截图，引用文件存在 | 通过 |
| V06按钮角色统一，重试非危险操作 | 同一描边实现、深浅色按钮截图、既有9项视觉操作回归 | 通过 |
| V07交互边框与装饰区分 | 浅色输入框/导出截图，outline与outlineVariant分别定义，进度轨道使用后者 | 通过 |
| 不恢复说明/中间分隔、不改手机真实记录 | 源码核对、深浅色及大字预约截图、手机非破坏性复验 | 通过 |

本轮交付已完成，无已知阻塞或未处理的本轮问题。保留所有先失败后修订的日志，不回写历史验证结果。
