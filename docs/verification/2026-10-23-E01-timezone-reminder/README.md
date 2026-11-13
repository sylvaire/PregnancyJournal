# E01 时区切换后产检提醒重建

日期：2026-10-23。设备：专用 `PJ_API36_16K`，Android 16 / API36 / 16KiB 页。使用两天后的合成产检预约，不含个人医疗数据。

## 流程与结果

1. 设备原时区 `GMT`、自动时区开；安装时区验证测试，建立 2026-10-25 12:00 的预约提醒。准备阶段 [prepare.log](prepare.log) 为 `OK (1 test)`，原绝对触发时间 `1792929600000` 毫秒（GMT 12:00），准备阶段记录的代际为 21；实际切换前状态为代际 22。
2. 临时关闭自动时区，通过系统 `cmd alarm set-timezone Asia/Shanghai` 真实切换；不打开应用界面。应用状态代际变为 **23**，预约调度键仍在，见 [after-zone-before-ui.xml](after-zone-before-ui.xml)。系统 AlarmManager 当前闹钟变为 `1792900800000` 毫秒，比原来提前 **8 小时**，对应上海时间 2026-10-25 12:00，见 [alarm-after-zone-before-ui.txt](alarm-after-zone-before-ui.txt)。广播冷启动进程记录亦可见于当时 logcat。
3. 仪器核对新时区投影、代际增加、预约键、系统 PendingIntent，结果 [verify.log](verify.log) `OK (1 test)`；finally 清理合成预约和孕期，测试状态文件为 `<map />`。
4. 恢复 `GMT` 和自动时区（`auto_time_zone=1`）；最终提醒状态无 `scheduled-keys`。生产代码未因本次测试修改，仅新增分阶段设备测试。

## 上海→GMT 反向切换补验

- 首轮反向准备 `OK (1 test)`；切回 GMT 后未打开界面，系统闹钟已在 `1792929600000` 毫秒（比上海本地 12:00 的绝对时间晚 8 小时），且代际 31→32。随后仪器核对在 `PendingIntent.FLAG_NO_CREATE` 查询上 **1 项失败**，原始日志见 [reverse-verify.log](reverse-verify.log)。更早采集的 [系统闹钟](reverse-alarm-before-ui.txt) 确实存在；查询与 `MainActivity` 启动后的再次重建并行，瞬时取消/替换是可能原因，尚无直接日志证明。本次失败保留，不计通过。
- 核对测试改为由宿主在界面启动前采集系统闹钟，仪器只核对持久代际、调度键与按当前时区重新计算的预约触发时间；未改变生产逻辑，也未放宽系统闹钟应存在的要求。重跑准备 [1/1 通过](reverse-prepare-rerun.log)，上海→GMT 后界面启动前代际 **37→39**、预约键保留，见 [状态](reverse-after-zone-before-ui-rerun.xml)；系统闹钟为 `1792929600000` 毫秒，见 [系统闹钟](reverse-alarm-before-ui-rerun.txt)；仪器 [1/1 通过](reverse-verify-rerun.log)。
- 两轮合成数据均清理；最终设备回到 `GMT`、`auto_time_zone=1`，测试状态文件为空。

## 边界

覆盖 API36 模拟器 GMT↔上海的真实时区变化。跨夏令时、系统时间手调、小米/OEM 后台限制和其他 API 尚未验收；E01 整体继续进行。
