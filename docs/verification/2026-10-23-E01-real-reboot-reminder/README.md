# E01 真实系统重启后提醒恢复

日期：2026-10-23。设备：专用 `PJ_API36_16K` 模拟器，Android 16 / API36 / 16KiB 页。合成产检预约设在两天后，不含个人医疗数据。

## 发现与修复

- 首轮原接收器收到 `BOOT_COMPLETED`，但进程约 2.7 秒后结束；30 秒后提醒代际仍为 3，开机恢复未通过。
- 第二轮仅延长异步入队生命周期，Worker 已启动但开机期间进程被系统回收；界面打开前代际仍为 9。后续仪器测试虽然通过，但会启动 `MainActivity`，不能证明广播自身完成恢复。
- 最终让 `ReminderRescheduleReceiver` 在 `goAsync()` 生命周期内直接执行 `ReminderScheduler.rebuild()`；失败时保留 WorkManager 入队兜底。避免开机时新启动的 Worker 进程被回收而失去本次恢复机会。

## 最终验证

- 安装修复版后准备阶段 `OK (1 test)`，重启前 `generation=16`，调度键为合成预约。
- 真实 `adb reboot` 后等待系统开机，不打开应用界面；从应用私有状态读取到 `generation=17` 且调度键保留，见 [direct-pre-ui-state.xml](direct-pre-ui-state.xml)。系统 `dumpsys alarm` 中有对应 `RTC_WAKEUP`、`cn.yunji.journal.REMINDER_ALARM`、2026-10-25 12:00 的 PendingIntent，见 [direct-alarm-before-ui.txt](direct-alarm-before-ui.txt)。
- 随后仪器核对 `OK (1 test)`，验证预约仍在加密库、PendingIntent 可查询，并在 finally 清理合成孕期/预约与测试状态，见 [direct-verify.log](direct-verify.log)。`cold-process-alarm-test.xml` 清理后为 `<map />`。
- `:app:testDebugUnitTest :app:lintDebug` 构建成功，JVM **340 项/0 失败**，lint **0 错误/36 警告**。此前 API36 全量 319 项的通过证据发生在本次接收器修改前，本次未重复全量设备测试。

## 边界

这是 API36 模拟器上的一次真实重启。小米/OEM 开机自启限制、锁屏/省电、长待机、时区变更与其他 API 仍未由本次证明；E01 整体未验收。
