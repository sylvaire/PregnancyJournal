# C05 自动云端备份失败通知

日期：2026-10-23。针对 V1.0 清单中尚未实现的“备份失败提醒”。

## 行为

- 自动云端备份连续失败达到 **3 次**后发一条“自动备份需要检查”通知；检测到其他设备写入、自动备份被阻断时立即发一条。失败原因、云端地址、凭据和医疗内容不会进入通知。
- 同一失败轮次用本机持久标记去重，用户清除通知后，后续重试不再次弹出。成功提交、关闭自动备份、切换设备角色或恢复资料库时清除标记/通知；新一轮失败可再次提示。
- 通知使用独立“备份状态”渠道，锁屏为 PRIVATE，并提供更通用的 PUBLIC 版本；没有系统通知权限时不发送，但既有备份失败状态和重试机制保持可用。通知点击打开应用，用户在备份设置查看具体状态。
- 通知系统调用失败不会把已成功提交的备份误判失败，也不会改变自动重试计数。

产物：`BackupFailureNotifier.kt`、`CloudBackupWorker.kt`、`CloudSchedulerStore.kt`、`CloudBackupCoordinator.kt`、`CloudBackupRunner.kt`、`VaultRuntime.kt`、`ReminderSettingsScreen.kt` 及设备测试。

## 验证

- `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug` 成功；JVM **340 项/0 失败**，lint **0 错误/25 报告项**。
- 专用 API36/16KiB 模拟器 [最终定向设备回归](final-device-regression.log) **9/9 通过**：通知阈值/同轮去重/新轮重置、冲突立即通知与隐私文本、调度状态跨实例和恢复清理，以及正式提醒设置页入口。
- 同设备 [云端设置与恢复邻接回归](cloud-restore-regression.log) **14/14 通过**，覆盖连接界面、云端清理界面和恢复后设备调度设置。
- 首轮设备回归 [8 项中 1 项失败](device-regression.log)：测试在请求取消通知后立即读取系统通知列表，未等待 Android 异步移除。改为等待实际移除后，[复跑 8/8](device-regression-rerun.log)，最终加上提醒设置页 9/9。原始失败保留；生产实现未因该失败降低断言。
- 测试通知与隔离的 SharedPreferences 已清理；`dumpsys notification` 未发现活动的测试通知 ID `700004`。

## 遗留

未通过真实 WebDAV 服务商制造连续自动失败，尚无 Worker→网络失败→系统通知的完整端到端设备证据；小米/OEM 省电和通知渠道设置仍待验收。C05 自定义提醒仍未实现，E01/E02 整体仍未验收。现有 Figma 提醒设置稿尚未同步本次说明文字。
