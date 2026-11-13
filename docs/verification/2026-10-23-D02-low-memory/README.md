# D02 受限内存与大原件复核

日期：2026-10-23。测试数据全部为流式生成的合成原件；`LargeBackupArchiveTest` finally 删除其缓存目录和临时密钥。未使用用户手机或资料。

- 当前目标平台：`PJ_API36_16K`，API36，16KiB 页，`MemTotal=2531836 kB`。扩展测试门禁后 `am instrument -w -r -e class cn.yunji.journal.LargeBackupArchiveTest ...` 返回 `OK (3 tests)`，三项状态码 0，总时长 17.133 秒。
- 低 RAM 尝试：API36 启动参数 `-memory 1024` 被 Play Store/16KiB 镜像提高到 2560 MB；启动日志见 `emulator-1g.out.log`。实际 `MemTotal=2531836 kB`，不得称为 1 GiB 验证。
- 受限内存平台：项目现有 `PregnancyJournal_API26`，API26 x86_64，启动参数 `-memory 1024` 被镜像提高到 1536 MB；实际 `MemTotal=1530664 kB`，启动日志见 `emulator-api26-1g.out.log`。安装同一 debug 与测试包后测试 `OK (3 tests)`，三项状态码 0，总时长 17.602 秒。
- 覆盖：32 MiB 与 100 MiB 原件加密归档、恢复密钥认证、隔离 SQLCipher 恢复、原件解密回读；GCM 认证中取消后 staging 删除且源文件保留。
- 收尾：关闭 API26，按原配置重启 `PJ_API36_16K`；设备命令读回 `sys.boot_completed=1`、API36、`ro.boot.qemu.avd_name=PJ_API36_16K`、`PAGE_SIZE=16384`。

限制：1 GiB 条件未达到；没有低内存实体手机、系统内存回收/强杀、空间不足或正式界面的大文件系统保存往返。不能将此矩阵视为 D02/E01 的最终低内存验收。
