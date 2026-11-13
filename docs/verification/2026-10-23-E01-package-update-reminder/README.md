# E01 覆盖安装后提醒恢复

日期：2026-10-23。设备：专用 `PJ_API36_16K` 模拟器，Android 16 / API36 / 16KiB 页。合成产检预约为 2026-10-25 12:00 GMT，不含个人医疗数据。

## 验证

1. 应用与测试包构建成功，测试包安装后建立合成预约：[准备](prepare.log) `OK (1 test)`。覆盖安装前持久提醒代际为 **44**、预约键在调度集合内，见 [before-update.xml](before-update.xml)。
2. 对同一 debug APK 执行保留数据的 `adb install -r`，系统返回 `Success`。不打开应用界面，12 秒后持久提醒代际为 **45**、预约键仍在，见 [after-update-before-ui.xml](after-update-before-ui.xml)。ActivityManager 日志中可见覆盖安装期间拉起 `ReminderRescheduleReceiver` 进程；该日志未直接打印广播 action，结合 Manifest 的 `MY_PACKAGE_REPLACED` 注册与本次代际变化支持广播重建结论。
3. 同一界面打开前的 `dumpsys alarm` 发现应用 `RTC_WAKEUP` / `REMINDER_ALARM` 在预约目标时间，见 [alarm-after-update-before-ui.txt](alarm-after-update-before-ui.txt)。后续仪器 [核对](verify.log) `OK (1 test)`，加密库预约仍在并清理测试孕期/预约。
4. 最终测试状态文件为空，提醒 `scheduled-keys` 为空。覆盖安装未清除应用数据。

## 边界

本次使用相同版本 debug APK 的覆盖安装，验证的是包替换事件后的恢复路径；跨版本安装、签名发布包更新、小米/OEM 后台限制和其他 API 尚未验收。E01 整体继续进行。
