# Ardot 安全、备份与导出页面对齐（2026-10-25）

## 范围与产物

- 设计文件 `737849992357132`，对照 N08–N11；设计截图在 `../2026-10-25-ardot-visual/screens/`。
- 实现截图：[安全设置](../2026-10-25-ardot-remaining/screens/security-settings.png)、[云盘连接](../2026-10-25-ardot-remaining/screens/cloud-connection.png)、[本地备份](../2026-10-25-ardot-remaining/screens/local-backup.png)、[导出资料](../2026-10-25-ardot-remaining/screens/export-screen.png)。此前缺失的 [自定义提醒](../2026-10-25-ardot-remaining/screens/custom-reminder-editor.png) 与 [锁定页](../2026-10-25-ardot-remaining/screens/vault-locked.png) 最终截图也已归档。
- 本轮未修改 Ardot 文件；未安装小米 11 Ultra，未读取或改动真机资料。截图使用模拟器测试夹具。

## 完成的界面调整

- N08：严格模式的长说明移至操作区后，首屏可见锁定状态、超时选项、验证方式、严格模式、立即锁定和隐私行。
- N09：真实备份状态与立即上传操作置于连接表单之前；最近上传、自动备份、云端清理使用现有状态，不写入示例值。连接配置和详细备份操作仍保留。
- N10：本地备份的起始和密钥就绪阶段增加密钥状态重点卡；导出、恢复及安全副本流程保留。
- N11：日期字段纵向排列，详细范围默认收起但可展开；两种导出方式移到首屏，范围统计和预览结果移到其后。明文确认及禁用条件保留。
- `ExportUiTest` 先展开范围后操作分类选项，与新版界面一致。

## 验证及视觉结论

- `:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest` 成功；末次文字修订后 `:app:assembleDebug` 再次成功。
- 模拟器 N08–N11 截图及导出操作：`OK (8 tests)`；最后 N09/N11 截图、云盘连接与导出专项：`OK (10 tests)`。
- 最终截图复核：N09 上传按钮在首屏，N11 两种导出方式在首屏。N08 底部解释需滚动；N10 真实操作比示意画板丰富，因此只认定层级局部对齐。N11 截图夹具未确认明文导出，按钮呈预期禁用态。

## 遗留与下一步

- N09 云端清理入口仍在连接表单下方；需补错误与空状态。
- N10 恢复向导各阶段尚未逐屏比对；N11 需补范围展开及有效可导出状态截图。
- N06 提醒设置、N21/N22 资料预览与资料卡、N12/N15 列表密度仍待继续；大字体、深色及小米 11 Ultra 视觉未验收。
