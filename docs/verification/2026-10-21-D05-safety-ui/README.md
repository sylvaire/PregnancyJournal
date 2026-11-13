# D05 安全副本界面与系统导出（0.19.0-dev）

2026-10-21，0.19.0-dev/versionCode20/schema13。本轮把「恢复前安全副本」的列表/删除/系统导出接入本地备份页，并完成验证。

- verification.log：171项API34模拟器回归全部通过，399.56秒，运行于最终0.19.0构建。
- targeted-safety-copy.log：安全副本三项定向测试通过（SafetyCopiesUiTest / SafetyCopyExporterTest / SafetyCopyStoreDeviceTest，4.737秒）。
- earlier-full-run-preceding-build.log：版本号提升前一次完整回归（171项通过），用于对照。
- jvm-results.json：185项JVM单元测试通过。
- lint-results.txt：0错误/24条既有警告。
- apk-sha256.json：交付开发包SHA256与版本。
- install-check.json：模拟器覆盖安装并启动通过；手机本轮未连接，未安装。

## 本轮修正

`SafetyCopyExporterTest` 原先用 `IS_PENDING=1` 的 MediaStore 行作为导出目标，该行在发布前不可读，占用探测抛 `ENOENT` 导致失败。生产入口使用 `ActivityResultContracts.CreateDocument("application/octet-stream")`，得到的是已存在且为空、可正常读取的文档，与该夹具不符。因此修正夹具为「先写入零字节使文档实体化」，与真实 CreateDocument 目的地一致；同时新增一条断言：**目的地不可读时必须失败**，不得报告导出成功——保留“空目标检查 + 完成回读校验”的失败关闭语义，未放宽任何生产校验。

## 未记录为通过的运行

版本号提升后的首次完整回归中，`NavigationExperienceTest.bookingDraftSurvivesTopBarReturn` 因滚动时序失败：该用例对“首个可滚动节点”执行 `performScrollToNode`，在负载下可能选中不含目标字段的滚动容器。单独复跑该用例通过（7.029秒，1项）。该次失败日志随后被最终干净运行覆盖，未能归档，此处如实说明而不以“通过”代替。

已按仓库既有约定加固该用例：先 `waitUntil` 目标字段出现，再对字段本身执行 `performScrollTo()`，不再猜测滚动容器。加固后 NavigationExperienceTest 23项通过（212.188秒），最终完整回归171项通过。

## 覆盖内容

- 本机安全副本面板：列出 `vault/restore-safety` 下受限识别的副本、显示日期与大小、刷新、二次确认后删除；恢复日志存在时禁止管理操作。
- 系统导出：加密副本原样导出到用户选择位置，绑定当前 vault 与副本元数据（id/修改时间/大小）防重放；目标必须为空；完成后整文件回读比对 SHA-256 才算成功；失败保留源副本与可能部分写入的目标，提示重新选择位置。

## 未覆盖

真实设备进程终止/断电、严格模式系统认证完整往返、云端快照下载恢复、旧代际确认回收与安全副本保留策略、授权引用释放、OEM/字体/无障碍矩阵；D02/D03/D04 既有未验收矩阵与 D06/D07、E01/E02 继续保留。
