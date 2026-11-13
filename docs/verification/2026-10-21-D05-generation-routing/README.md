# D05 代际对象路由与恢复VMK封装

2026-10-21：完整155项JVM/0失败，构建及lint0错误/20警告通过。专用API34模拟器40项资料导入/导出/代际SQLCipher原件专项通过（60.497秒），正式MainActivity→DocumentsUI→保存→恢复校验1项通过（9.814秒）。合计41项，不扩大为历史136项全量。模拟器保留数据更新；未发布新版本或操作手机。

VaultLayout.activeObjects对旧布局返回vault/objects，对恢复代际返回gen/<id>/objects，缺失时明确失败，不自动创建空对象库。Runtime同一资源会话解析数据库与对象目录并显式注入DocumentImporter；本地/云端备份均复用该导入器绑定的对象目录，避免重读全局指针使已捕获快照混用另一个代际。恢复专项直接通过正式DocumentImporter.openPreview认证读取代际原件成功。

GenerationKeyEnvelope以设备VMK用途generation-key、AAD generation-key/v1/<deviceVaultId>/<generationId>封装恢复VMK，目标vaultId在加密正文中。仅内存处理，暂未落盘/接入Runtime密钥选择。返回的恢复会话由调用方擦除，派生密钥与明文缓冲清空。2项新JVM验证不同vault封装往返、设备密钥不变、错设备/错代际/篡改拒绝；新增对象目录测试验证缺失目录失败。

正式系统备份测试使用合成临时恢复设置，finally还原加密设置、删除测试输出；资料专项使用隔离库/合成资料。正式恢复入口仍未启用。下一步需把GenerationKeyEnvelope原子持久化到代际并让Runtime以设备解锁会话解封；同时处理锁定清理/启动失败回滚、恢复前安全备份、恢复设置/云盘身份/提醒非主设备重建、完成后的再次恢复生命周期。不能以本次封装测试宣称密钥安装已完成或可进行正式换机恢复。