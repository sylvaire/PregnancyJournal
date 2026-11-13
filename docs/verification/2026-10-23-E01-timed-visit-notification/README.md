# E01 产检提醒到点投递

- 日期：2026-10-23；设备：专用 `PJ_API36_16K` 模拟器，API36/16KiB。
- 在 `ReminderPermissionDialogTest#deniedPermissionKeepsVisitAndRegrantRebuildsItsAlarm` 的 `dialogDecision=visit-journey-timed` 路径中，创建合成预约及约 1–2 分钟后的提醒；经真实系统弹窗拒绝、再次允许，从正式设置页重建提醒，并等待系统到点投递。断言对应预约通知 ID 存在、标题为“孕迹提醒”、锁屏可见性为 `PRIVATE`。
- 首轮模拟器默认 `SCHEDULE_EXACT_ALARM` 未开放，前置条件跳过（`INSTRUMENTATION_STATUS_CODE: -4`），见 [首轮日志](device-test.log)；未将它计为通过。
- 临时将专用模拟器精确闹钟 app-op 设为 `allow` 后，最终测试 **1/1 通过**，历时 73.904 秒，见 [最终日志](device-test-exact.log)。结束后恢复 app-op 为原先的 `default`、通知权限为允许，取消测试通知并精确清理合成预约。
- 边界：验证了具备精确闹钟能力的 API36 模拟器上、应用运行时的到点投递；非精确降级的实际延迟、锁屏实机视觉、进程被杀、系统设置页授权与小米/OEM 行为仍待验证。
