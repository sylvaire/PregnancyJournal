# E01 通知权限系统弹窗往返

日期：2026-10-23。设备：项目专用 `emulator-5556`，`PJ_API36_16K`，API36/16KiB 页。测试前权限为允许；每次先停止应用、撤销 `POST_NOTIFICATIONS` 并清除 `user-set/user-fixed` 标记，使系统弹窗可真实出现，finally 恢复原先允许状态。未触碰用户手机。

- 产物：`app/src/androidTest/java/cn/yunji/journal/ReminderPermissionDialogTest.kt`；`ReminderPermissionMatrixTest.kt` 在缺少矩阵参数时改为前置条件跳过。测试包构建成功。
- 独立分支：应用提醒设置页点击“开启通知权限”，Permission Controller 实际弹窗点“拒绝”见 `deny.log`，`OK (1 test)`；点“允许”见 `allow.log`，`OK (1 test)`。两者均通过应用页面与真实系统授权状态断言。
- 连续用户路径：同一应用会话里先拒绝、再次点击申请并允许，页面变为已允许；随后直接发送测试通知并在 `NotificationManager.activeNotifications` 中读到测试 ID，finally 清除通知。初次结果见 `deny-then-allow.log`；加上实际通知投递断言后的最终结果见 `deny-then-allow-final.log`，`OK (1 test)`，状态码 -1。
- 收尾：模拟器权限读回 `granted=true`，且清除测试通知；未保留 `user-set/user-fixed` 标记。

边界：不证明产检/用药定时提醒、已有待办内容在拒绝期间保留、系统设置页手动授权、真机/OEM 或无障碍朗读。E01/E02 仍未整体验收。
