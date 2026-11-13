# N15 四入口与搜索对齐

- 日期：2026-10-28；阶段：M6；设计依据：Ardot 文件 `737849992357132` 的 N15 画板，截图 `../2026-10-25-ardot-visual/screens/N15.png`。本次未修改 Ardot。
- 实现：顶部改为“今天 / 历史 / 方案 / 补记”四入口；“方案”进入已有用药方案页并可继续查看版本历史，“补记”进入真实补记表单；“仅看补记”移至历史列表内部。今天页新增按药品名称或计划时间搜索，筛选固定计划和当天补记；原日期切换、记录编辑/删除流程保留。
- 产物：`app/src/main/java/cn/yunji/journal/feature/IntakeScreens.kt`、`app/src/main/java/cn/yunji/journal/feature/JournalNavigation.kt`；导航测试文案适配 `app/src/androidTest/java/cn/yunji/journal/NavigationExperienceTest.kt`。
- 验证：`assembleDebug`、`testDebugUnitTest`、`assembleDebugAndroidTest` 成功。`adb devices` 为空，因此四入口跳转、搜索、日期切换与完整记录流程**尚未运行设备测试，且无新版截图**。
- 待复核：四入口在小屏与大字下的布局；“方案”页的版本历史是否满足画板期望；搜索无结果与当天补记并存状态；返回动画、深色模式及小米 11 Ultra。恢复设备后先运行 `scheduledIntakeEditDeleteUndoAndManualHistoryKeepOriginalPlan`，再逐项点击四入口并采 N15/N19 新截图。

## 2026-10-28 设备恢复与复核追加

- 原先直接运行 `emulator -list-avds` 未设置项目本地 `ANDROID_AVD_HOME`，因此“没有 AVD”的判断不准确。项目 `.local/avd` 中原有 `PJ_API36_16K`。在当前受限环境直接启动会因宿主端口和硬件加速失败；使用项目 AVD 路径及系统权限启动后，`emulator-5554` 上线并完成开机。为排查临时创建的 `PJ_UI_API36` 已从项目 `.local/avd` 清理。
- N15 进一步补齐“补记或跳过”“方案历史”两条操作行，计划/历史行使用设计稿对应的文档图标。N19 药品选择收为单行，新补记默认时间显示到分钟，增加只读“已服用”状态，并调整说明行的日历图标与文案。补记不能标记跳过；跳过仍在对应计划行内执行。
- 最新 `assembleDebug`、`testDebugUnitTest`、`assembleDebugAndroidTest` 成功；最终说明行微调后 `assembleDebug` 再次成功。模拟器 `scheduledIntakeEditDeleteUndoAndManualHistoryKeepOriginalPlan` 最终 **1/1** 通过，覆盖四入口可见、方案页跳转返回、搜索无结果、计划记录、历史展开、修改、删除、撤销和补记。
- 最终截图已逐张查看：[N15 今天](screens/day.png)、[N15 历史](screens/history-collapsed.png)、[N19 补记](screens/manual-editor.png)。均为合成药品数据。N15 首屏结构与 N15 画板接近；画板中的两条固定服药是示例，实际行数由真实方案决定。N19 保留了实际剂量与单位分栏、方案版本选择和时区/草稿说明，故整体比简化设计稿略高。
- 仍未验收：小屏/大字/深色、真实长药名、多个固定计划与补记并存、跨日搜索、方案历史版本细节、小米 11 Ultra。当前结果仅证明 API36 模拟器的这一个合成流程及三张视觉状态。
