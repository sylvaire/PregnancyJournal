# N15 按日计划密度对齐

- 日期：2026-10-28；阶段：M6；设计依据：Ardot 文件 `737849992357132`，N15 画板，项目内截图 `../2026-10-25-ardot-visual/screens/N15.png`。本次未修改 Ardot 文件。
- 实现：按日计划首屏增加“今天的服药/指定日期的服药”标题和“切换日期”入口，原日期框与前一天/今天/后一天操作可展开；固定计划与当天补记改为可展开记录行，默认显示时间、状态、药品，展开保留原计划、方案用量、时区、实际记录与已服用/跳过/编辑/删除操作；补记主按钮和安全说明置于列表后。
- 产物：`app/src/main/java/cn/yunji/journal/feature/IntakeScreens.kt`；适配流程测试入口 `app/src/androidTest/java/cn/yunji/journal/NavigationExperienceTest.kt`。
- 验证：`assembleDebug`、`testDebugUnitTest`、`assembleDebugAndroidTest` 成功。`adb devices` 仍为空，故**未运行新版点击流程、未采截图、未完成视觉验收**。本次只是实现与构建验证，不代表 N15 全页对齐。
- 遗留：设计画板的四页签、页面搜索、方案历史独立入口尚未对应；长药名、大字、深色、日期切换、滚动后操作与小米 11 Ultra 待设备验证。下一步先恢复设备并运行 `scheduledIntakeEditDeleteUndoAndManualHistoryKeepOriginalPlan`，采按日计划/补记新版截图，再补其余入口。

## 2026-10-28 后续验证

- 已恢复项目本地 API36 模拟器，四入口、今天页搜索、按日紧凑行及方案/补记入口已接通，合成完整服药流程 **1/1** 通过，最终 [N15 截图和记录](../2026-10-28-ardot-intake-navigation/README.md) 已归档。多计划、日期切换、大字、深色与小米真机仍待验收。
