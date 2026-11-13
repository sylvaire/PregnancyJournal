# N22 产检资料回收站多条状态

- 日期：2026-10-29；阶段：M6；设计依据：Ardot 文件 `737849992357132` 的 N22 画板，本地 `../2026-10-25-ardot-visual/screens/N22.png`。本轮未修改设计画板。
- 实现：本次产检回收站有多份资料时，每条默认显示紧凑摘要，点“操作”展开恢复与彻底删除；单条仍直接显示操作。正在彻底删除的条目继续显示进度并禁用操作。
- 产物：`app/src/main/java/cn/yunji/journal/feature/DocumentScreen.kt`、`app/src/androidTest/java/cn/yunji/journal/UiStateMatrixTest.kt`、[模拟器截图](screens/multiple-trash.png)。截图仅含合成数据。
- 验证：`assembleDebug`、`testDebugUnitTest`、`assembleDebugAndroidTest` 成功；API36 模拟器“两份资料移入回收站→展开其中一份→恢复→计数从 2 变 1” **1/1** 通过。截图已查看，两条摘要和操作入口可见且没有文字遮挡。
- 边界：尚未采集“正在彻底删除”动态截图，也未验证多条清理中的交互、大字/深色/横屏和小米 11 Ultra 真机。N21 PDF 首页/横屏与 N12 多条状态仍待继续。
