# 11 Ultra 复连后的安全设置守卫验证与数据清理（2026-10-24 round 18）

## 背景

- 22:28 小米 11 Ultra 无线调试恢复（`adb connect <局域网地址>` 成功，M2102K1C / Android 13），安装的是 19:05 默认 debug 构建（`FLAG_SECURE` 开启）。
- 此前的真机安全设置走查因需要设备 PIN/生物识别而无法自动化；本轮验证“未设置设备锁屏凭据”时应用锁的**守卫行为**，不修改用户手机锁屏设置。

## 结果

1. 打开「我的 → 安全设置」：页面显示「未开启，打开应用时可直接查看本机记录」，可点「开启应用锁」（`dumps\01-security-settings-real-device.xml`）。
2. 点击「开启应用锁」：应用进入锁定层并显示错误：
   **「请先设置系统锁屏凭据或录入可用的生物识别；旧设备严格模式需要强生物识别。」**（`dumps\02-enable-lock-guard-no-device-credential.xml`）
   —— 与实现预期一致：应用锁依赖系统认证，手机未配置设备锁/生物识别时不会静默开启，也不会写入半成品状态。
3. 恢复：`am force-stop` 后重新启动，应用正常打开且未处于锁定状态（应用锁未开启，`dumps\03-relaunch-unlocked-after-force-stop.xml`）。
4. 数据清理：本节测试前设备上残留的合成孕期（我的孕期，0 周，1 次产检）已整体删除并彻底清除，回收站为空、`vault/gen/*/objects` 对象数 0（`dumps\04-trash-empty-after-cleanup.xml`）。

## 结论与限制

- **真机应用锁完整链路（开启→立即锁定→PIN 解锁→关闭）仍无法在本机完成**：该 11 Ultra 未设置系统锁屏凭据；为不改变用户手机安全设置，未代为设置 PIN/生物识别。若需要完成该项，请在手机上设置一个临时 PIN/指纹后告知，或接受“模拟器已完整走查（round 14）+ 真机守卫行为已验证”的结论。
- 应用锁启用失败时不会留下锁定态（已验证 force-stop 恢复）。

## 证据

`dumps/01-04*.xml`（uiautomator 语义树）。

## 回归（2026-10-24 深夜，重建 AVD 后）

原 emulator-5556 的 AVD 在会话恢复后缺失，已用 `avdmanager` 重建 Android 36 16KiB AVD（`PJ_API36_16K`，pixel_6）。
- 首次全量矩阵 369 tests / 1 失败：`CustomReminderUiTest#createEditAndDeleteFromReminderSettings`（删除确认对话框未及时出现，冷启动/负载下测试同步脆弱）→ 增加 `waitUntil` 等待 `custom-reminder-confirm-delete` 后单用例 OK(1)；
- 第二次 369 / 1 失败：`NavigationExperienceTest#cloudConnectionValidatesBeforeNetworkAndClearsUnsavedPasswordOnReturn`（输入后控制器状态未就绪即触发校验超时）→ 增加等待 `model.cloud.state.username == "bad:name"` 并把消息等待放宽到 15s 后单用例 OK(1)；
- **第三次全量矩阵：OK (369 tests)，0 失败，EXIT=0**（`_tools/lead-full-matrix-round18c-green.log`；前两次日志 `...round18-first.log`、`...round18b-second.log`）。
- 说明：两次失败均为测试同步脆弱（未放宽产品断言），修复后连续通过；模拟器基线已恢复（浅色/竖屏/font 1.0）。
