# 小米 14 Pro 真机验证：隔离用户矩阵与 MIUI 仪器测试限制（E01/C05 增量）

日期：2026-10-23 23:09–23:45（Asia/Shanghai）。
设备：`23116PN5BC`（Xiaomi 14 Pro），HyperOS `OS3.0.309.0.WNBCNXM`，Android 16 / SDK 36，无线调试。
应用：`cn.yunji.journal` `versionCode 27 / 0.26.0-dev`（与源码一致，见同批 `2026-10-23-E01-xiaomi-cross-version-upgrade`）。

本次真机工作分两部分：①在**独立 Android 用户**（`user 10`，名称 PJTest）中运行仪器测试，避免触碰用户主用户（user 0）的真实资料；②记录无法运行的部分与原因。所有合成数据只存在于隔离用户，验证结束后已删除该用户；未在个人数据上运行清库型仪器测试。

## 隔离用户方法

- `pm create-user PJTest` → user 10（成功；无需 root）。
- `pm install-existing --user 10 cn.yunji.journal` 与应用/测试包当前版本；`pm grant --user 10 … POST_NOTIFICATIONS`；`appops set --user 10 … SCHEDULE_EXACT_ALARM allow`。
- `am switch-user 10` 后在 user 10 前台运行 `am instrument --user 10 …`；结束后 `am switch-user 0`、`pm remove-user 10`，手机恢复仅 Owner + XSpace。
- 首次 `pm install-existing` 克隆的是 user 0 中**旧版测试 APK**，导致 `CustomReminderUiTest` 找不到类；更新测试 APK 后需重新克隆。此操作顺序已记录为教训，不是产品缺陷。

## 已通过的真机定向（隔离用户，非 UI）

`am instrument --user 10 … ReminderNotificationTest,ReminderSchedulerTest`：**7/7 通过**。

- `ReminderNotificationTest` 2/2：HyperOS 上经真实 AlarmManager → BroadcastReceiver → 系统通知；通知 `visibility=PRIVATE`、标题/正文为通用文案，合成 TEST 事件。
- `ReminderSchedulerTest` 5/5：合成加密库 + 固定时钟下的 7 天投影、generation 递增与调度键替换、稍后提醒、重建替换旧代际、未认证动作拒绝。
- `AccessStateTest` 4/4：全量运行中断前已完成（即时/1 分钟/5 分钟锁定与通知提交租赁语义），均为非 UI 设备测试。

证据：`targeted-batch1-summary.txt`、`targeted-batch1-raw.txt`、`full-suite-partial-run1.txt`。

## 关键发现：MIUI 阻止仪器测试进程启动 Activity

隔离用户中运行任何需要启动 Activity 的仪器测试（`createAndroidComposeRule<ComponentActivity>` 或 `ActivityScenario.launch(MainActivity)`）都会无限等待，原因不是产品崩溃，而是 MIUI 的活动启动权限拦截：

```
D/ActivityStarterImpl: MIUILOG- Permission Denied Activity : Intent { … cmp=cn.yunji.journal/.MainActivity } pkg : cn.yunji.journal uid : 1010554
I/ActivityTaskManager: START u10 {… cn.yunji.journal/.MainActivity} … callingPackage cn.yunji.journal (BAL_ALLOW_PERMISSION) result code=102
```

- AOSP 的后台活动启动检查已因 `BAL_ALLOW_PERMISSION` 放行，是 MIUI 叠加的 `Permission Denied Activity` 拦截（对应 MIUI「后台弹出界面」权限），`result code=102` 表示启动取消，没有窗口产生，测试框架永久等待。
- 从 shell `am start` 启动是允许的（shell 有权限），但 `am instrument` 会重启应用进程，测试内的启动仍从后台进程发起，因此预启动桌面 Activity 不能规避。
- 影响：全量 338 项中，所有 UI 类（`NavigationExperienceTest`、`PageMatrixBaseTest` 子类、`UiSnapshotTest`、`CloudCleanupUiTest`、`ExportUiTest` 等）在未授予该 MIUI 权限时都无法在小米真机自动执行；非 UI 设备测试不受影响。

证据：`miui-activity-launch-denied.txt`、`instrumented-ui-blocked.txt`。全量首轮在 4 项 `AccessStateTest` 通过后卡在 `BackgroundReliabilityUiTest`，由宿主终止；`shortMsg=Process crashed` 是终止动作导致的进程结束，不是产品崩溃。

## 未完成 / 下一步

- 小米真机 UI 仪器矩阵：需先在 MIUI 中给应用授予「后台弹出界面」（或等效权限），或改为 **adb 驱动的 UI 操作**（shell `am start` 可用）逐项验证。
- 锁屏隐私视觉、自定义提醒实际界面增删改、系统通知设置页往返（小米设置页差异）本轮未在真机完成；此前已在专用 API36/16KiB 模拟器通过。
- 通知权限拒绝→允许的真实到点投递、冷进程/重启/时区等端到端场景在小米真机上仍未执行（相关测试类目前带模拟器型号前置条件）。
- 未在真机运行任何清库或写真实资料的测试；个人数据未被读取或导出。

## 产物

- `instrumented-ui-blocked.txt`：各次 UI 类尝试与超时终止记录。
- `miui-activity-launch-denied.txt`：MIUI 拦截日志与 `result code=102` 说明。
- `targeted-batch1-summary.txt` / `targeted-batch1-raw.txt`：7/7 非 UI 定向通过记录与原始 `am instrument` 输出。
- `full-suite-partial-run1.txt`：全量首轮原始输出（338 项计划，4 项通过后中断）。
