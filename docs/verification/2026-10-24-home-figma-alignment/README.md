# 首页 Figma 对齐验证（2026-10-24）

- 设计基线：[Figma 文件](https://www.figma.com/design/qwUxmzTnSJtzppU5eZwHcy?node-id=12-138)，节点 `12:138`（`03 Today`）；本次读取会话 `35abcfa5-480c-46e0-8662-8642db828380`。本地基线：[首页设计预览](../../design/previews/首页.png)。
- 实现范围：首页的标题、孕期概览、今日安排、三张快捷卡、最近资料、备份状态及底部导航；预约、服药、资料、健康指标、日常备注、档案和备份入口接入现有页面。无用药计划时展示可点击空状态，保持两条安排的版面结构。原有新建预约、管理档案与服药统计仍在首屏以下，可滚动访问。
- 验证截图：[最终模拟器截图](01-today-final.png)。设备为 Android 模拟器 `emulator-5556`，截图夹具使用合成数据，孕期日期与最近资料缺失时展示真实空状态；因此文案与 Figma 示例数据不同。
- 验证命令：`:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`，结果 `BUILD SUCCESSFUL`；`UiSnapshotTest#homeFigmaSectionsOpenTheirDestinations` 与 `UiSnapshotTest#snap01Today`，结果 `OK (2 tests)`。检查了首屏没有旧入口外露及快捷入口可进入相应页面。
- 尚未完成：本次未在小米真机上复测视觉；完整字体缩放、横屏和真实资料填充下的首页视觉矩阵留待后续验证。Figma 仅作为 UI 基线，不代表新增了独立的业务能力。

## 2026-10-24 小米 11 Ultra 安装复核

- 无线调试设备 `M2102K1C` 已连接；将本次构建的 `app-debug.apk` 以覆盖安装方式安装，ADB 返回 `Success`，未卸载或清除应用数据。
- 启动应用后 `pidof cn.yunji.journal` 返回进程 ID `14429`；设备报告 `versionCode=27`、`versionName=0.26.0-dev`。
- 本次确认安装和进程启动；未对真机首页视觉与个人资料状态作验收。

## 2026-10-24 已填日期仍显示“待补充日期”修复

- 真机只读取首页语义标志并在本地计算日期区间，未保存或输出个人日期：已存在医生确认的预产期，但距当前日期超过 280 天。`PregnancyRules.age()` 对这种情况返回空；首页之前把“有日期但无法计算”与“未填日期”合并为同一个“待补充日期”。
- 首页现区分两种状态：已有预产期但无法计算时显示“已保存日期 · 暂无法计算孕周”“请核对预产期”，说明超过 40 周并提供“修改日期”；未来的末次月经日期也给出对应提示。正常日期的孕周计算和首屏结构保持原逻辑。
- 验证：`:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 为 `BUILD SUCCESSFUL`；修复包覆盖安装小米 11 Ultra，ADB `Success`。真机首页语义检查：出现“请核对预产期”“已保存日期”“修改日期”，不再出现“待补充日期”。覆盖安装未清除应用数据。
- 限制：未读取个人日期，也未修改档案；“修改日期”入口的端到端跳转尚未取得可靠确认，因此不记作通过。
