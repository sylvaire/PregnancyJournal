# M6 界面风格整改（2026-10-25）

依据：[界面风格审查](../2026-10-25-style-audit/README.md)。本轮修改已有页面展示，不改变记录格式、备份格式或操作入口。

## 完成内容

| 项目 | 修改 | 当前截图 |
|---|---|---|
| 原始记录详情 | 摘要改柔和绿卡、详情改白卡，去掉与标题重复的日常备注文本；原计划日期改中文展示 | [原始记录详情](screens/figma-ui-alignment/timeline-source-detail.png) |
| 云盘连接 | 连接检查、云端备份、自动备份、云端清理分白卡；主状态不再展示截断的快照标识，清理统计仍可查看 | [云端清理](screens/figma-ui-alignment/cloud-connection-cleanup.png) |
| 导出资料 | 日期范围、导出范围、明文确认分卡；保留原勾选、统计与导出行为 | [导出资料](screens/figma-ui-alignment/export-screen.png) |
| 自定义提醒 | 日期/时间改整行描边按钮，保存改主色按钮，保留原文案与交互 | [提醒编辑弹窗](screens/figma-ui-alignment/custom-reminder-editor.png) |
| 日期与表单 | 共享日期选择控件改中文显示，内部仍存 ISO；测量时间标签改“时:分:秒”，当前时区显示“本机当前时区” | [档案编辑](screens/figma-ui-alignment/pregnancy-editor.png)、[测量编辑](screens/figma-ui-alignment/measurement-editor.png) |
| 产检资料与安全 | 缩略图区域 180dp→112dp；锁定时间三项改为横排 | [产检资料](screens/figma-sync/08-visit-documents.png)、[安全设置](screens/figma-ui-alignment/security-settings.png) |

## 验证

- `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`：`BUILD SUCCESSFUL`。
- 首轮相关界面用例 41 项执行、1 项失败：提醒弹窗按钮文案增加分隔符，旧断言未找到原文案；恢复原文案、仅保留按钮样式变化后，失败用例单项 `OK (1 test)`。
- 整改后 `UiSnapshotCoverageTest` + `UiSnapshotTest`：`OK (27 tests)`，本目录 `screens/` 保存 31 张当次截图；重点页面逐张检查，没有发现遮挡或页面结构断裂。
- 最终将上述截图用例与 `ExportUiTest`、`CustomReminderUiTest`、`CloudCleanupUiTest` 完整重跑：`OK (41 tests)`，0 失败。
- 小米 11 Ultra（M2102K1C）无线调试覆盖安装：ADB `Success`，启动后进程存在；未卸载或清除用户数据。

## 限制与下一步

- 默认构建启用安全截图限制；小米真机只确认安装/启动，逐屏视觉仍以模拟器截图为证。
- 缩略图容器已缩小，但测试用报告本身留白较多；真实图片、PDF 和长标题组合仍需真机/扩展状态复核。
- 字体放大、横屏、深色模式及真机逐屏视觉尚未在本轮复核。
