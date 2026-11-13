# D02 异常中断后的私有明文工作目录清理

日期：2026-10-23。专用设备：项目 `.local/avd/PJ_API36_16K.avd`（Android API36、16KiB 页）；仅使用合成文件，未在个人手机运行进程终止测试。

修复 D02 进程矩阵记录的两个明确缺口：`cacheDir/export-work/job-<UUID>` 可含导出前解密原件，`noBackupFilesDir/vault/restore-validation/restore-<UUID>` 可含验证时的 `authenticated-original`。新增 `TransientWorkspaceCleanup`：只处理规范 UUID 命名、位于指定应用私有根目录且非符号链接的子目录；遍历时不跟随内部符号链接。导出与恢复各自登记进程内活动任务；启动后后台执行密钥无关的孤儿清理，原导入恢复流程再次重试，执行新的导出/恢复前也扫描。外部 SAF 目标不在此清理范围。

验证结果：

- `TransientWorkspaceCleanupTest` 2/2 通过；全量 JVM **340 项、0 失败、0 错误、0 跳过**；`lintDebug` **0 错误、35 警告**（既有依赖提示）。构建及 Android 测试编译成功，日志 `jvm-lint.log`、JUnit XML 与 lint XML 在 `app/build/`。
- `run-orphan-matrix.ps1` 在专用模拟器启动阶段 1，写入合成明文孤儿后由宿主 `am force-stop`，阶段 2 在新 PID 等待生产冷启动清理。最终 `orphan-phase2.log` 为真实 `INSTRUMENTATION_STATUS_CODE: 0`、`OK (1 test)`，27.736 秒；测试断言四类孤儿均消失、三类无关目录保留，最终只清理测试创建的精确子目录。`phase1` 的 `shortMsg=Process crashed` 是预期的宿主强制终止，不计失败。强制终止不等同于断电。
- 设备回归 `targeted-device-regression.log`：正式 ZIP/PDF 系统导出、恢复访问与备份导出 **25/25 通过**。跑后 `export-work` 与 `restore-validation` 无 `job-*` / `restore-*` 遗留。
- 代码复核后将 `RestoreWizard.prepare` 的退出清理收紧为“仅删除本次成功创建的 staging”，避免极端 UUID 撞名时误删先前目录；最终生产包 `post-review-build.log` 构建及 `RestoreWizardTest` JVM 通过，设备 `post-review-restore-device.log` 恢复访问 **9/9 通过**。这项边界修正不改变前述孤儿清理策略。

排障：首轮宿主脚本只看 `OK (1 test)`，实际阶段 2 被 Assume 跳过；已要求状态码 0 且无 -4。第二轮发现旧 marker 未清且没有独立 `pid=` 行，已在阶段 1 前精确删除旧 marker 并修正格式。第三轮实际执行后发现夹具把 `backupKeep` 错记为整个 `backup-work` 根目录，失败时的测试清理也作用到该专用模拟器的工作目录根路径；随即改为测试创建的 `keep-unrelated` 子目录，并在清理前校验规范绝对路径。最终第四轮通过。上述失败不计作产品验证通过；前几轮日志被同名重跑覆盖，错误及更正保留于此记录。

仍未覆盖：真实断电/文件系统故障、外部 SAF 半成品移除（用户选择位置权限受提供方约束）、OEM 后台限制、低内存/空间不足及大文件恢复。
