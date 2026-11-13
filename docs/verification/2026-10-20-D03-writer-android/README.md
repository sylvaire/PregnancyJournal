# D03 Android目录同步验证

2026-10-20：完整97项JVM/0失败，assembleDebug、assembleDebugAndroidTest、lintDebug成功，lint0错误/20警告；专用API34模拟器2项CloudWriterDurabilityTest通过（0.297秒）。保留数据更新模拟器开发包；未操作手机。

CloudWriterStore强制注入目录同步，不允许遗漏；加密临时文件fd.sync后原子替换，再同步目录才发放序号。AndroidCloudWriter使用no_backup/vault，在打开组件时同步父目录，替换状态后同步状态目录。Android实现用公开Os.open(O_RDONLY)、fstat/S_ISDIR验证及fsync/close；初次使用SDK未公开的O_DIRECTORY导致编译失败，已修正，保留失败和最终成功日志。API参考：[Android Os](https://developer.android.com/reference/android/system/Os)。

JVM新增注入目录同步失败：调用抛异常，不返回预留，后续成功序号为3而非复用已写的2。模拟器测试在独立随机no_backup测试目录使用合成VMK/资料库ID，验证真实目录同步/原子替换后的重开与20次4线程独立实例预留唯一；清理临时目录和内存密钥。不使用正式vault状态，不更改恢复/锁定设置。

未进行物理断电、跨进程并发、API26或OEM测试；不得将fsync调用成功扩大为硬件断电保证。上传编排、持久备份状态页与D04主设备流程仍待实现，云端备份尚不可用。