# E01 应用进程退出后的产检提醒

- 日期：2026-10-23；设备：专用 `PJ_API36_16K` 模拟器，API36/16KiB。为界定到点窗口临时允许精确闹钟，结束后恢复 app-op 为 `default`。
- 两阶段 `ReminderColdProcessAlarmTest`：第一阶段在应用加密库建立合成孕期/预约、正式 `ReminderScheduler.rebuild()` 调度系统闹钟；仪器进程结束后确认应用 `pidof` 为空，Android AlarmManager 仍持有该闹钟。期间**未执行 force-stop**。第二阶段在到点后新进程核对通知、预约与时间，随后精确删除合成记录、重建调度并取消通知。
- 基线问题：[准备](prepare.log) 1/1 通过，系统在 00:07:00 为闹钟广播启动应用进程，但[核对](verify.log) 1/1 失败，未收到通知。诊断复现的[接收器日志](diagnostic-receiver.log)表明接收器在投递前发现事件代际变化。`PregnancyApplication.onCreate` 在闹钟冷启动时也立即启动 `ReminderWorker.recover()`，其重建更新代际，使同一闹钟被接收器丢弃。
- 修复：把应用打开时的提醒恢复入口从 `PregnancyApplication.onCreate` 移至 `MainActivity.onCreate`；引发冷启动的闹钟接收器因此可先完成当前事件，应用界面启动仍会恢复提醒，系统重启/时间变化仍由 `ReminderRescheduleReceiver` 触发。
- 修复后[准备](fix-prepare.log)与[核对](fix-verify.log)分别 1/1 通过。系统在 00:15 到点拉起接收器，[日志](fix-delivery.log)记载 `posted=true`，新进程回读的通知发布时间比计划时间晚 **1.805 秒**；确认隐私标题、`PRIVATE` 可见性和原预约记录。合成数据与测试通知已清理。
- 目标回归：提醒调度、通知、提醒设置及产检提醒设置/清除合计 [9/9 通过](reminder-regression.log)。
- 边界：这是正常进程退出后的冷启动投递，不等同于用户在系统设置里“强行停止”；Android 在 force-stop 后通常不投递该应用的闹钟。小米/OEM、省电限制、锁屏视觉及长时间待机仍未验收。
