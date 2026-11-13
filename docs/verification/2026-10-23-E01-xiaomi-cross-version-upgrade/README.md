# 小米 14 Pro 跨版本保留数据升级与提醒恢复（E01 真机增量）

日期：2026-10-23 23:05–23:12（Asia/Shanghai）。
设备：`23116PN5BC`（Xiaomi 14 Pro，codename shennong），HyperOS `OS3.0.309.0.WNBCNXM`，Android 16 / SDK 36，1440×3200 / 560 dpi，无线调试（`adb-tls-connect`）。
应用：`cn.yunji.journal`，从 `versionCode 26 / 0.25.0-dev / schema13` 保留数据覆盖升级到 `versionCode 27 / 0.26.0-dev / schema14`。

本项只验证“真机上跨版本保留数据升级 + schema13→14 真实用户库迁移 + 提醒重建”，不构成小米/OEM 完整验收。文档不记录任何个人医疗内容，只记录结构性事实与校验值。

## 目的与范围

此前 E01 的“同版本 APK 覆盖安装后后台重建”已在专用 API36/16KiB 模拟器通过；C05 自定义提醒的 schema1–13 合成加密库迁移也已通过。尚未验证的是**真实用户库（非合成）在真机上跨版本升级**以及升级后提醒是否恢复。本轮用用户手机上已有的真实加密库完成这两点。

不做：正式签名包升级、跨签名发布包、清库型仪器测试、个人数据内容读取或外传。

## 前置状态（升级前，23:05 采集）

- 已安装版本：`versionCode=26`、`versionName=0.25.0-dev`，`lastUpdateTime=2026-10-22 21:45:49`，`firstInstallTime=2026-10-18 01:04:13`（保留数据安装历史）。
- 资料库：`no_backup/vault/journal.db` 278,528 字节，md5 `023bf6e3e61f33d7214dec5292a39dc7`；`objects/` 下 6 个不可变加密原件共约 12.5 MiB（md5 见 `objects-md5-before-after.txt`）。
- 提醒状态（`shared_prefs/reminder-state.xml`）：`generation=42`，`scheduled-keys` 7 条（均为 MEDICATION），`snoozes` 为空。
- 权限与能力：`POST_NOTIFICATION: allow`、`SCHEDULE_EXACT_ALARM: allow`、已加入电池优化白名单（`dumpsys deviceidle whitelist` 含该包）。
- `dumpsys alarm`：`Pending alarms per uid` 中该应用 uid（`u0a554`）为 **1**，即升级前 AlarmManager 中只有 1 个待触发项；7 条提醒键仍在 prefs 中但没有对应的 7 个提醒闹钟。该差异原因未证实（可能与安装/后台清理后未重新打开应用、系统取消闹钟有关），本轮只记录事实，不归因。
- `objects/` 原件 md5 与升级后一致，作为“不可变原件不被迁移改动”的对照。

## 备份与签名核对

- 设备内备份：`run-as cn.yunji.journal cp -a no_backup/vault files/vault-backup-before-vc27`，随后对 `journal.db` 与 6 个原件逐一核对 md5，与源目录完全一致（`vault-md5-before.txt`、`objects-md5-before-after.txt`）。备份留在应用私有目录，未导出个人数据到电脑或仓库。
- 签名兼容：已安装 vc26 APK 与当前 debug APK 均为 `CN=Android Debug`，证书 SHA-256 同为 `b6614bc40cd64e8e2bd38419055afd3328d94bba29bcba309b100ff84d3cde20`（`signing-compat.txt`），因此可以 `adb install -r` 保留数据升级。

## 升级与启动

- `adb -s <xiaomi> install -r -g app/build/outputs/apk/debug/app-debug.apk` → `Success`（23:06:10）。
- 升级后包信息：`versionCode=27`、`versionName=0.26.0-dev`、`lastUpdateTime=2026-10-23 23:06:10`。
- 启动：`am start -W -n cn.yunji.journal/.MainActivity` → `Status: ok`、`LaunchState: COLD`、`TotalTime 903 ms`、`WaitTime 914 ms`；进程存活（`pidof` 非空）。
- 日志核对（升级+启动窗口）：无 `FATAL EXCEPTION`、无 `Process: cn.yunji.journal` 崩溃、无 `Room cannot verify the data integrity`、无 SQLCipher 相关异常行（`logcat-check.txt`）。

## 迁移与数据完整性

- Room 会在打开数据库时校验 identity hash；应用未崩溃且首页可交互，说明 schema13→14 迁移已执行并通过校验。
- `no_backup/vault/journal.db` 在首次启动后被重写（WAL 49,472 字节，应用进程重启后 checkpoint 为 0），最终 md5 `9a8caecba6449521723b39151aff6f6b`，大小 290,816 字节；属迁移写入的预期变化（`vault-md5-after.txt`）。
- `objects/` 6 个加密原件升级前后 md5 完全一致，迁移未改动原件（`objects-md5-before-after.txt`）。
- 页面可读性：首页正常渲染孕期档案/服药计划/产检入口；`我的 → 提醒设置` 页面正常打开，并出现 schema14 新增的「自定义提醒 / 新建提醒」区块，无崩溃。具体医疗内容未写入文档、未截图留档。

## 提醒重建与闹钟

- 升级后首次启动，`reminder-state` 由 `generation=42` 推进到 44→45，`scheduled-keys` 仍为 7 条；其中一条每日用药键由旧键替换为新键（投影窗口滚动导致，符合每日点生成语义）。
- `dumpsys alarm` 升级后出现 7 个 `tag=*walarm*:cn.yunji.journal.REMINDER_ALARM` 的 `RTC_WAKEUP` 闹钟（`alarms-after-upgrade.txt`），对应 7 条提醒键；稍后采样中该 uid 待触发闹钟数为 7（此前含 1 个维护类闹钟时为 8，维护闹钟触发后回到 7）。
- 结论：跨版本升级后应用从迁移后的真实加密库重建了 7 天提醒投影，并重新向 AlarmManager 注册了提醒闹钟。

## 结论与边界

- 在本机小米 14 Pro（HyperOS 3.0.309）上，`0.25.0-dev / vc26 / schema13` → `0.26.0-dev / vc27 / schema14` 的**保留数据覆盖升级成功**：真实用户库完成 schema13→14 迁移、原件未变、应用可交互、7 天提醒重建并重新注册 AlarmManager。
- 未覆盖：正式签名包/跨签名升级、schema 迁移失败回滚演练、升级后长时间待机与 OEM 清理、通知实际投递与锁屏视觉（另见真机矩阵记录）、其他 OEM/API。
- 升级前的“prefs 有 7 条键但只有 1 个待触发闹钟”差异如实保留，原因未证实。

## 产物

- `device-info.txt`：设备与系统属性。
- `version-and-launch.txt`：升级前后版本、安装与启动结果。
- `signing-compat.txt`：签名核对。
- `vault-md5-before.txt` / `vault-md5-after.txt`：资料库哈希。
- `objects-md5-before-after.txt`：6 个原件升级前后哈希对照。
- `reminder-state-before-after.txt`：提醒代际与调度键数量。
- `alarm-before-after.txt` / `alarms-after-upgrade.txt`：AlarmManager 待触发项。
- `logcat-check.txt`：崩溃/数据库校验异常过滤结果。
