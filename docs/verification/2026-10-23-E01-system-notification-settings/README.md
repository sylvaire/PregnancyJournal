# E01 系统通知设置手动授权往返

- 日期：2026-10-23；设备：专用 `PJ_API36_16K` 模拟器，API36/16KiB。
- 正式应用路径：“我的 → 提醒设置 → 系统通知设置”；在 Android 设置页点击该应用的通知总开关，返回应用后确认“已允许显示通知”，再通过页面按钮发送测试通知并观察系统活动通知。
- 新增 `ReminderPermissionDialogTest#systemSettingsGrantRefreshesReminderPageAndPostsTestNotification`；从权限拒绝态开始，最终定向 **1/1 通过**（4.536 秒），`INSTRUMENTATION_CODE: -1`。见 [设备日志](device-test.log)。测试通知已取消，模拟器通知权限恢复允许。
- 边界：只验证此专用模拟器的系统设置页结构；小米等 OEM 设置页和用户手动关闭后再打开、跨进程恢复仍待验证。
