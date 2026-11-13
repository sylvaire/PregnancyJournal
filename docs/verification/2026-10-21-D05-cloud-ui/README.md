# 云端恢复界面接线（0.24.0-dev）

2026-10-22，`0.24.0-dev`/versionCode25/schema13。本轮把上一轮的云端下载核心接到恢复界面，用户现在可以从界面选择云端备份恢复。

- `api34-suite.log`：参考设备完整回归 **210 项 / 0 失败**（373.6 秒）。
- `junit-RestoreControllerTest.xml`：控制器 14 项（含 5 项新增云端流程）。
- `targeted-restore-ui.log`：恢复界面 6 项（含 3 项新增云端用例）。
- `summary.json` / `apk-sha256.json` / `lint-results.txt`：汇总、交付包哈希、lint（0 错误/24 警告）。

## 本轮实现

**控制器**（`RestoreController`）
- 新增阶段 `CLOUD`（输入恢复密钥并查找）与 `CLOUD_LIST`（选择云端快照）；
- `startCloud` / `findCloud` / `selectSnapshot`，`prepare()` 按阶段分派到 `prepareCloud` 或本地 `prepare`；
- 保持既有不变量：开始校验前清空界面中的密钥、离开页面清空密钥与选择、失败后回到可重试阶段且不泄露底层异常文本。

**界面**（`RestoreContent`）
- SELECT 阶段新增「从云端备份恢复」；
- CLOUD 阶段：密钥输入（遮罩）、查找按钮、查找中禁用输入并显示进度、说明需要已校验的云盘连接；
- CLOUD_LIST 阶段：按时间与序号列出可恢复快照、必须先选择才可「下载并校验」、明确说明下载完成后仍需确认才会替换。

**生产实现**（`LocalRestoreAccess`）
- 抽出共用尾部 `registerPrepared(file, recoveryKey)`（无恢复槽时生成 RAM 候选 → `runtime.prepareRestore` 校验并暂存代际），本地与云端两条路径行为一致；
- `listCloudSnapshots`：要求**已保存且通过校验**的云盘连接，用 `CloudSnapshotFetcher.discover` 只列出本恢复密钥能打开的远端资料库；
- `prepareCloud`：在同一 `restore-input` 暂存区下载（复用既有的作业目录与清理逻辑），随后走共用尾部；
- 可注入 `clock` 与 WebDAV 客户端工厂，便于后续补端到端测试。

## 未完成的验证（重要）

- **`LocalRestoreAccess` 的云端粘合层没有端到端测试**：列出/下载/暂存这三步只有编译级验证；`CloudSnapshotFetcher`（8 项）与控制器门控（5 项）分别有测试，但“真实 WebDAV → LocalRestoreAccess → 预览”的整链未跑。补齐方式明确：把 mockwebserver/okhttp-tls 加入 androidTest，用 TLS 假 DAV 服务在设备上跑通。
- 未验证：真实服务商下载、断网/慢网与中途取消、数百 MiB 低内存、真机安装与系统往返。
- `prepareCloud` 会为每个候选调用一次“列举快照”以定位所选快照，属可接受的额外往返，但慢网下会增加等待（未优化）。
