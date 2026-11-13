# E01 通知权限与产检预约保留

- 日期：2026-10-23；设备：专用 `PJ_API36_16K` 模拟器，API36/16KiB。
- 新增 `ReminderPermissionDialogTest#deniedPermissionKeepsVisitAndRegrantRebuildsItsAlarm`：在应用加密库创建合成孕期与未来产检预约，设置提醒；走正式“我的 → 提醒设置”及系统通知权限弹窗，先拒绝后允许。
- 拒绝后验证原预约仍可从加密库读取，7 天提醒事件中仍有该预约；重新允许后点击“立即重建未来7天提醒”，验证调度键包含该预约。
- 最终源码构建 `:app:assembleDebugAndroidTest` 成功；定向测试 `OK (1 test)`、`INSTRUMENTATION_CODE: -1`，见 [设备日志](device-test.log)。合成记录由测试事务删除并确认，模拟器通知权限恢复允许。
- 范围限制：验证了记录保留和重新调度，尚未等待真实预约时间触发通知；系统设置页手动授权、真机/OEM 和进程被杀场景未覆盖。
