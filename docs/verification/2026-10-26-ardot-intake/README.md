# N15 服药历史对齐续作

- 日期：2026-10-26；阶段：M6；设计：Ardot 文件 `737849992357132` 的 N15 画板（本次未修改设计文件）。
- 实现：历史记录改为默认折叠的摘要行，显示日期、状态、药品、版本和来源；点开可查看原计划、实际时间、剂量、备注、时区，并修改或删除。历史页将搜索放在列表前，补记按钮及用药提示放在列表后。按日计划页保留原有操作。
- 代码：`app/src/main/java/cn/yunji/journal/feature/IntakeScreens.kt`；流程测试：`app/src/androidTest/java/cn/yunji/journal/NavigationExperienceTest.kt`。
- 验证：`assembleDebug`、`testDebugUnitTest`、`assembleDebugAndroidTest` 构建成功；模拟器 `scheduledIntakeEditDeleteUndoAndManualHistoryKeepOriginalPlan` 最终 **1/1** 通过，覆盖计划记录、历史展开、修改、删除、撤销、补记。折叠态截图：[intake-history-collapsed.png](screens/intake-history-collapsed.png)；展开态和补记后截图：[intake-history-flow.png](screens/intake-history-flow.png)。截图均为合成测试数据，且设备底部含保存提示。
- 视觉检查：折叠历史行已明显低于旧大卡；行内长药名仍可能换两行。N15 画板的“今天/历史/方案/补记”四个页签、今日服药聚合入口、方案历史独立入口仍未完全实现；当前沿用三个现有筛选，不能标记整页完全对齐。N19 补记编辑页首部及按日计划卡尚待继续；大字、深色和小米 11 Ultra 未验收。
