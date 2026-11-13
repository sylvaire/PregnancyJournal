# D02 已开始目标防重放

2026-10-20。最终debug/androidTest构建、81项JVM通过，lint0错误/20警告；18项API34专项通过（13.33秒）：BackupExporterTest7、FileHandoffTest8、RecoveryUiTest2、IsolatedRestoreTest1。原始证据见build.log及device.log。未发布新版本、未安装手机。

FileResult增加默认false的backupStarted，兼容旧选择记录。FileHandoff.startBackup在制作前原子持久化开始状态；重开后canApply=false，重复claim拒绝且状态保持。MainActivity对已开始结果不提供继续按钮，说明原目标保留并要求重新选择；拒绝重放分支不会清除正在执行的选择。

BackupExporter在制作前和即将打开输出前两次读取目标首字节，只有新空文件才进入写入。发现已保存内容或制作期间发生变化时抛出专用异常，touched仍为false，因此既不覆盖也不删除该文件。旧专项允许截断既有内容的策略已明确取消；本轮证明已有文件原字节保持。新空文件仍使用wt并执行最终回读。

FileHandoff.clearIf按请求ID条件清理。MainActivity成功、备份取消和错误使用对应ID消费记录，旧Activity不能清掉新选择。专项验证错误ID清理无效、正确ID清理成功。

新增/修订测试覆盖持久重开、重复及过期ID claim、canApply禁用、错误ID清理隔离；已有目标和制作期间变化目标原字节保持，pin/工作目录释放；新空目标正常写入/回读和完整独立恢复继续通过。不是实际杀进程/Activity旋转全流程测试，不能据此宣布完整重建矩阵通过。

剩余：真实MainActivity/系统保存完整往返，实际进程终止与旋转/取消窗口，提供方权限/删除失败，大文件/低内存/空间不足/OEM。D02及完整P0/A–E范围继续保留。