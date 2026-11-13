# 小米 11 Ultra（Android 13 / MIUI 14）真机验证：OEM 省电限制与提醒端到端投递

日期：2026-10-24 01:02–01:45（Asia/Shanghai）。
设备：`M2102K1C`（Xiaomi 11 Ultra，codename `star`），MIUI `V14.0.22.12.5.DEV` / Android 13 / SDK 33，1440×3200 560 dpi，`PAGE_SIZE=4096`（非 16 KiB 页），无线调试。
应用：`cn.yunji.journal` `versionCode 27 / 0.26.0-dev / schema14`，**全新安装**（设备此前未安装），合成数据，验证后已彻底删除。

本轮按用户指示从小米 14 Pro 切换到小米 11 Ultra 继续真机验收；14 Pro 已先恢复 Owner、删除隔离用户（其真实资料与升级前备份未被改动），随后该机无线调试关闭并从 adb 消失。

## 方法与前置

- mDNS 发现设备后，用用户提供的配对码完成 `adb pair`；配对成功后 `adb connect` 可用。
- MIUI 14 默认拦截两项 adb 能力：`adb install` 返回 `INSTALL_FAILED_USER_RESTRICTED`，`adb shell input` 抛 `INJECT_EVENTS` SecurityException。用户开启开发者选项中的「USB安装」与「USB调试（安全设置）」后恢复；据此完成安装（debug vc27）与 adb 驱动 UI。
- 通知权限用 `pm grant POST_NOTIFICATIONS` 授予（Android 13 首次引入该运行时权限），`SCHEDULE_EXACT_ALARM` appop 允许；首次启动 COLD 894 ms，无崩溃。

## 已通过的检查

1. **启动与基础功能**：全新库 schema14 创建，首页/我的/提醒设置/用药方案等页面正常，无 FATAL/SQLite 异常。
2. **提醒设置页（MIUI 14）**：通知权限「已允许显示通知」、精确提醒「可使用精确提醒」、厂商引导与「打开自启动设置 / 应用信息」入口、提醒类型、自定义提醒区块（无进行中孕期时提示「请先创建或选择进行中的孕期」）。
3. **系统通知设置页**：进入 `com.android.settings/.Settings$AppNotificationSettingsActivity`；MIUI 14 行项为 允许通知、通知过滤规则（系统推荐）、桌面图标角标权限、悬浮通知权限、锁屏通知权限、发声/振动/常驻通知权限、通知类别（孕迹提醒）。与 HyperOS 3 的差异见 `miui14-oem-surface.txt`。
4. **自启动入口**：进入 `com.miui.securitycenter/.permcenter.autostart.AutoStartManagementActivity`；首次弹「关于自启动的说明」，页面最初为「禁止53个应用自启动」并把孕迹列在禁止列表。
5. **OEM 省电策略**：提醒页「允许后台运行」进入 `com.miui.powerkeeper/.ui.HiddenAppsConfigActivity`，默认档位为「智能限制后台运行(推荐)」；可选 无限制 / 超 10 分钟关闭 / 禁止后台运行。
6. **提醒到点投递（关键）**：
   - **默认省电策略下未投递**：应用退到后台约 2 秒后即被 MIUI SmartPower 置入 hibernation（日志见证据）；01:22 到点的提醒没有产生通知，`delivered-alarms`/`missed-alarms` 均为空；当时 `dumpsys notification` 显示该应用 `importance=NONE`、appops `POST_NOTIFICATION: ignore`（仅记录现象，不归因）。
   - **按应用内引导把省电策略设为「无限制」后**：页面显示「已加入电池优化白名单」，自启动页变为「允许1个应用自启动（孕迹）」；随后 01:33 的定时提醒**准点投递**（通知 `when=01:33:00.041`，仅晚 41 ms，标题「孕迹提醒」、正文「打开孕迹解锁后查看」、`visibility=PRIVATE`、channel `reminders`），通知栏可见（截图见证据）。
   - 额外观察：01:22 的错过提醒在应用下一次运行（01:30:23）被应用内 15 分钟补投窗口重放（通知 `when=01:30:23.861`），说明错过补投机制在 MIUI 上也生效。

## 结论

- 在小米 11 Ultra（MIUI 14 / Android 13 / 4 KiB 页）上，应用的提醒链路可工作，但 **MIUI 默认的「智能限制后台运行(推荐)」会在数秒内冻结应用，导致到点提醒不投递**；按应用内厂商引导设置为「无限制」（并允许自启动）后，到点提醒在后台准点投递。这为 C05 的 OEM 限制说明提供了真机证据。
- 真实到点投递（合成数据）在 Android 13 + MIUI 14 上首次闭环；此前该场景只在模拟器与小米 14 Pro（HyperOS 3）上验证过。

## 未覆盖 / 边界

- 未测试：MIUI 14 的通知权限弹窗（本轮用 pm grant 授予）、锁屏视觉（设备有锁屏凭据，未锁屏）、自定义提醒界面（需日期/时间选择器）、通知权限拒绝→到点投递、重启/时区端到端、长期待机、正式用户库。
- 只验证了一次合成提醒；41 ms 为单次样本，不代表长期或所有机型的准点保证。
- 该设备为 MIUI 开发版 ROM（bootloader 已解锁），结论不外推到其他 MIUI/HyperOS 版本。
- 测试数据已通过应用内「删除孕期 → 全局回收站 → 彻底删除」清除，回收站为空；推送的临时 APK 已从 `/sdcard/Download` 删除；应用（开发版）保留安装以便后续验证。

## 产物

- `device-info.txt`：设备、系统、应用安装信息。
- `method-and-install.txt`：切换设备、配对、MIUI 开发者选项与安装记录。
- `miui14-oem-surface.txt`：MIUI 14 提醒设置/通知设置/自启动/省电策略页面与默认状态。
- `delivery-before-after.txt`：默认省电策略未投递、改为无限制后准点投递与错过补投的详细证据（含通知记录字段与 SmartPower 日志）。
- `delivery-notifications-top.png`：通知栏截图（已裁剪到两条孕迹提醒与系统通知，未包含第三方个人内容）。
