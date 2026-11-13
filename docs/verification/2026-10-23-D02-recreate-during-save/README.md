# D02 保存中页面重建调查

日期：2026-10-23。设备：项目专用 `PJ_API36_16K`，API36/16KiB 页。测试原件为 100 MiB 合成流。此项调查**未通过验收**。

探针在正式 Main 报告“正在保存备份文件”时重建 Activity。首轮 `device-initial.log` 和持久授权诊断 `device-grant-diagnostic.log` 均出现读取系统目标的 `SecurityException`；重建前 `persistedUriPermissions` 的读写授权断言已通过，不能据此断言应用从未取得授权。第三轮 `device-delete-check.log` 返回 `Process crashed`；`provider-crash.log` 指向 `android.process.media` 内 `DownloadProvider.updateMediaProvider` 因 MediaStore 条目缺失崩溃，ActivityManager 因应用依赖该提供方而终止应用进程。探针未能独立证明目标状态，已从普通测试类移除；这些失败不计入通过数。

现场清理：共享存储未发现 `Yunji-Journey-*.yunji` 合成文件；一次性严格匹配清理用例删除崩溃遗留的唯一合成孕期/产检/原件、文件交接与测试恢复槽，见 `fixture-cleanup.log`，`OK (1 test)`。该一次性用例之后已从源码删除。若测试前已有恢复槽，其原始字节只保存在崩溃测试进程内，现无法恢复；专用模拟器当前无恢复槽，后续测试须重新生成。不涉及用户手机或资料。

缓解与验证：中断后的重开弹窗现在要求检查并删除本次创建的未验证文件，再重新导出；`docs/用户指南.md` 同步。API36/16KiB 两阶段进程恢复测试见 `process-prepare.log`、`process-restart.log`，各 `OK (1 test)`；测试核对提示可见、无继续入口、旧目标字节不变和防重放。最终正常、确认前重建、100 MiB 完整保存的回归见 `system-journey-final.log`：`OK (3 tests)`，0 跳过。

限制：本轮没有解决系统下载提供方在保存中重建时的崩溃，也没有验证外部目标在所有取消/崩溃时序都被清理。仍需独立测试提供方、其他系统镜像及真机/OEM 的故障矩阵。
