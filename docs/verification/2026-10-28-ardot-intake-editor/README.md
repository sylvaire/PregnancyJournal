# N19 补记服药表单顺序调整

- 日期：2026-10-28；阶段：M6；设计依据：Ardot 文件 `737849992357132` 的 N19 画板截图 `../2026-10-25-ardot-visual/screens/N19.png`。本次未修改设计文件。
- 实现：仅对无计划补记表单，将实际服药日期、时间移到首部；药品与方案版本通过带当前名称和版本的选择控件展示；用量字段紧随其后。原本首部两行重复说明移至表单末尾，保留实际记录时区与非用药建议提示。已有计划的记录表单维持原顺序。
- 产物：`app/src/main/java/cn/yunji/journal/feature/IntakeScreens.kt`。
- 验证：`assembleDebug`、`testDebugUnitTest`、`assembleDebugAndroidTest` 在 2026-10-26 代码调整后成功。2026-10-28 复核设备时 `adb devices` 为空，`D:\Android\Sdk\emulator\emulator.exe -list-avds` 为空，故本次**未运行模拟器流程、未采新版截图、未完成视觉验收**。
- 后续：恢复模拟器或连接小米设备后运行 `scheduledIntakeEditDeleteUndoAndManualHistoryKeepOriginalPlan`，采补记首屏截图并检查药名较长、字体放大、深色模式与键盘弹出时的选择控件；继续 N15 按日计划行与画板四入口的功能/设计对应关系。

## 2026-10-28 后续验证

- 已找到项目本地 AVD，先前“AVD 列表为空”是未设置 `ANDROID_AVD_HOME` 所致。API36 模拟器恢复后，N19 表单完成方案按钮单行、默认时间到分钟、状态字段及说明图标修订。[最终截图与完整流程证据](../2026-10-28-ardot-intake-navigation/README.md) 已补齐；大字、深色和小米真机仍待验收。
