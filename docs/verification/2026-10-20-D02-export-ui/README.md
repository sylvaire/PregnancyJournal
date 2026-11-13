# D02 正式本地保存入口接线

2026-10-20。最终debug/androidTest构建、81项JVM通过，lint0错误/20警告；API34专项16项通过（11.688秒）：BackupExporterTest6、IsolatedRestoreTest1、FileHandoffTest7、RecoveryUiTest2。证据见build.log及device.log。未发布新版、未安装手机。

入口为我的→本地备份。已确认恢复密钥后显示导出按钮，BACKUP请求绑定当前vaultId。系统CreateDocument新建application/octet-stream/.yunji文件；结果独立加密暂存并始终要求确认，锁定后先认证。MainActivity复核资料库绑定后调用导出服务；进度覆盖制作、自检、保存和目标回读，可取消；暂停沿用取消/等待关闭。取消/错误清除旧选择。

目标使用wt截断写入，关闭后回读最终文件，比较长度/SHA256，全部通过才显示成功。导出前私有归档和原件GCM认证、独立数据库13表回读仍执行。错误/取消尝试删除已打开的新CreateDocument目标，提供方不支持删除时不能保证删除成功；尚未打开的系统空文件可能保留。不会删除原始医疗资料。

新增验证：配置完成页面点击发起BACKUP/正确ownerId/.yunji文件名且无完整密钥；CreateDocument MIME/标题/请求绑定和结果确认在暂存重开后保持；真实Android FileProvider预填100000字节文件被截断、导出、回读校验，并能用恢复密钥再次打开。其他自检、源库/草稿保持、失败清理、锁定取消、孤儿目录和独立恢复通过。

范围限制：Compose按钮、FileHandoff、FileProvider和导出服务分段验证，不能声称已完成真实MainActivity→系统DocumentsUI→返回→保存的整条往返。提供方只写/拒绝删除、重新创建Activity/进程终止期间的目标重放与取消窗口、大文件/低内存/OEM组合仍待验收。D02未整体完成，D03–D07/E范围保留。