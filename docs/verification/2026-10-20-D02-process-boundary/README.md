# D02 已登记开始后的真实进程终止边界

2026-10-20，专用API34模拟器，debug版本。BackupProcessBoundaryTest显式分prepare/restart阶段（boundaryStage参数），普通测试不提供参数会跳过，避免留下跨测试夹具。

prepare通过正式FileHandoff登记BACKUP、接收合成FileProvider URI并持久化startBackup，已有目标含固定非医疗合成字节。准备1项通过；显式启动MainActivity，确认PID存活后执行外部am force-stop，确认PID消失，再运行独立restart测试：PID不同，正式MainActivity显示“上次备份需要重新选择位置”，无“继续”入口，持久状态保持，重复startBackup拒绝，目标字节不变。restart 1项通过（2.696秒）。结束清理本次handoff、合成目标、夹具标记，未更改恢复设置/应用锁/医疗记录。

此证据覆盖已登记开始后的崩溃重启边界，不覆盖正在写入大文件时随机杀进程、SAF提供方死亡或正式活动库恢复。目标是合成FileProvider文件，真实DocumentsUI往返有前述独立证据。D02仍未整体验收。

先前一次尝试发现测试框架结束后进程已经退出，未将该情况作为force-stop成功；改为显式启动并确认活PID再终止。初次编译漏SecurityStore导入已修正。最终构建和两次测试日志见同目录。