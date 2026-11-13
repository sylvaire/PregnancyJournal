# D05 真实SQLCipher代际与原件验证

2026-10-21：构建、完整152项JVM/0失败、lint0错误/20警告通过。专用API34模拟器新增RestoreGenerationDatabaseTest 1项通过（2.046秒），另IsolatedRestoreTest+LargeBackupArchiveTest 4项回归通过（20.918秒）。未重跑历史136项，未安装手机或发布新版本。

新增IsolatedRestore.buildDatabase桥接：在已认证归档作用域内真实导入SQLCipher、逐表集合及capturedSequence校验，关闭数据库后返回文件，避免复制仍含未合并WAL的打开数据库。RestoreWizard复制完成数据库及清单中每份已认证的原件/缩略图密文到gen/<id>/objects；文件同步完成后才进入PREPARED。验证器删除暂存目录时恢复代际仍保有自己的对象，失败删除整个未激活代际。

新专项只使用随机私有cache根、合成资料和RAM随机VMK，不触碰正式vault/设备密钥/恢复设置/医疗数据。真实归档→向导prepare→真实SQLCipher导入→commit回调重开SQLCipher并查询档案/附件/高水位→从代际objects认证解密原件，验证字节一致；已生效库不能被discard。finally擦除合成VMK、删除隔离目录。

仍未接正式Runtime：不同vault恢复VMK的设备封装安装、代际objects路由、旧数据安全备份、启动SWITCHED真实重开/回滚、非主设备/提醒重建、恢复确认/回收生命周期，以及UI/云端恢复。此测试证明真实引擎与对象存活的隔离链路，不是正式应用换机恢复成功；文件fsync不等同目录持久化/物理断电验收。原先“对象跨代际共享”的设计假设在不同VMK恢复时不适用，本修订采用恢复代际独立密文对象，Runtime路由需后续完成。