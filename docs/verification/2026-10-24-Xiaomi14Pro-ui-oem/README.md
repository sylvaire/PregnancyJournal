# 小米 14 Pro 真机 UI/OEM 子集（提醒设置 · 权限弹窗 · 系统设置入口 · 自启动 · 锁屏观察）

日期：2026-10-24 00:40–01:10（Asia/Shanghai）。
设备：`23116PN5BC`（Xiaomi 14 Pro），HyperOS `OS3.0.309.0.WNBCNXM`，Android 16 / SDK 36，无线调试。
应用：`cn.yunji.journal` `versionCode 27 / 0.26.0-dev / schema14`。

**方法**：在前一轮发现 MIUI 会拦截仪器测试进程启动 Activity 之后，本轮改用 **adb 驱动 UI**（`am start` + `input tap/swipe` + `uiautomator dump` + `screencap`），在**隔离二级用户**（`pm create-user PJTest` → user 11）中执行；不触碰 Owner（user 0）的个人资料，检查后 `am switch-user 0` 并 `pm remove-user 11`。所有通知与提醒均为合成内容。

## 已通过 / 已观察

1. **提醒设置页在 HyperOS 正常渲染**：通知权限、精确提醒、后台可靠性、小米/Redmi 厂商引导、提醒类型、自定义提醒、调度检查均出现；空资料库时自定义提醒显示“请先创建或选择进行中的孕期”（符合按孕期关联的产品语义）。
2. **MIUI 通知权限弹窗**：从应用内「开启通知权限」进入真实系统弹窗，文案为「允许“孕迹”发送通知？」「包括消息推送、桌面图标角标、声音和振动提醒等…」，按钮为「拒绝 / 始终允许」（与 AOSP 的“不允许/允许”措辞不同，OEM 差异）。点击「始终允许」后页面刷新为「已允许显示通知」并出现「发送测试通知」。
3. **测试通知实际投递**：通知记录为 `channel=reminders`、`importance=4`、`visibility=PRIVATE`、`category=reminder`、`AUTO_CANCEL`；通知栏显示合成的「孕迹提醒测试」文案。信道回读 `mLockscreenVisibility=-1000`（应用创建时设为 PRIVATE，HyperOS 侧回读为 NO_OVERRIDE，仅记录不归因）。
4. **系统通知设置入口往返**：点击「系统通知设置」进入 `com.android.settings/.Settings$AppNotificationSettingsActivity`（MIUI 应用通知页），可读到「允许通知 / 焦点通知 / 通知权限设置 / 桌面角标 / 悬浮通知 / 锁屏通知 / 发声 / 振动 / 常驻通知 / 通知类别：孕迹提醒」；返回可回到应用。
5. **自启动设置入口 + 真实 OEM 发现**：点击「打开自启动设置」进入 `com.miui.securitycenter/...AutoStartManagementActivity`（自启动管理）；页面显示 **「禁止1个应用自启动」且孕迹开关为关闭**——说明 HyperOS 上该新装应用的自启动默认被禁止。这与应用内的厂商引导（安全中心→应用管理→自启动、省电策略→无限制）一致，也意味着**重启/后台冻结后提醒可能被延迟**这一 OEM 限制在真机成立。本轮未修改该开关，测试用户已删除。
6. **锁屏观察（未通过）**：在「允许通知=开、锁屏通知=开、系统允许私有内容=1、勿扰关闭」条件下，应用通知在通知栏正常显示，但**两次锁屏（前台/后台各一次）都未在锁屏上出现**；锁屏只显示 `pkg=android user=-1` 的系统级“已连接到无线调试”通知。原因未归因（可能是 MIUI 二级用户锁屏策略或信道 `lockscreenVisibility` 回读差异）。Owner 用户有锁屏凭据且含个人数据，未用于锁屏测试。因此 **C05「锁屏视觉」在小米真机仍未验收**。

## 结论与边界

- 本轮首次在小米真机上完成「提醒设置页 + 系统权限弹窗 + 测试通知 + 系统通知设置页 + 自启动入口」的 UI/OEM 子集验收，并发现自启动默认禁止这一真实 OEM 限制。
- 仍未验收：锁屏隐私视觉（真机未显示，原因未证实）、自定义提醒实际到点投递（需先创建合成孕期，本轮未做）、通知权限拒绝→允许的到点投递、冷进程/重启/时区端到端、正式用户库。
- 隔离用户中的 UI 操作通过 adb 驱动完成，不等同于 Compose 仪器测试，也不覆盖无障碍/极端配置下的真机行为。

## 产物

- `device-and-method.txt`：设备、用户与操作方式、清理读回。
- `miui-permission-dialog.txt`：权限弹窗文案与结果。
- `miui-app-notification-settings.txt`：MIUI 应用通知页行项与开关状态。
- `miui-autostart.txt`：自启动管理页与「禁止自启动」发现。
- `notification-record.txt`：通知记录与信道回读（已脱敏，仅本应用）。
- `lock-screen-observation.txt`：锁屏两次尝试的现象、前置条件与边界。
- `lockscreen-1.png` / `lockscreen-2.png`：锁屏截图（仅系统通知）。
- `notification-shade.png`：通知栏中的合成测试通知。
- `miui-app-notification-settings.png` / `miui-autostart-management.png`：系统设置页截图。
