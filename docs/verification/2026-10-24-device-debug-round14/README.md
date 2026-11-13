# 小米 11 Ultra 复连真机调试（2026-10-24 下午，round 14）

- 设备：Xiaomi 11 Ultra（M2102K1C / star / MIUI V14.0.22.12.5.DEV / Android 13 / SDK33 / 1440×3200），
  无线调试 17:50 重新可用（mDNS `<adb设备标识> _adb-tls-connect._tcp <局域网地址>`），
  安装最新 APK（含 Timeline 方案 A、UI P0/P1、日期格式统一、Bug #1–#4）。
- 应用内截图受 `FLAG_SECURE` 限制，本目录证据以 uiautomator 语义树（`dumps/`）与落盘文件为准。

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| 安装/启动 | ✅ `lastUpdateTime=2026-10-24 17:52:29`，启动进入「今天」页，数据完整（M6-Test 6周+4天/预产期 2027-06-15） |
| **Timeline 方案 A（真机）** | ✅ 3 筛选「全部/产检/日常」；日期组头「10月31日」+ 右侧「孕7周 + 4天」；记录行（预约一次产检 / M6Check · M6Hospital、方案变更、服药、资料行）；杏色备注卡（M6/M6Symptom/日常备注 · 只有日期，没有具体时间）；底部 CTA「写一条日常备注」 |
| **日期格式统一（真机）** | ✅ 产检行「10月31日 · M6Check」、测量行「10月24日 18:01」；发现 1 处遗漏：回收站「删除于 2026-10-24」仍 ISO（Bug #6） |
| **通知** | ✅ 重新授予 POST_NOTIFICATIONS；「发送测试通知」实际投递（channel=reminders importance=4 category=reminder vis=PRIVATE） |
| **自定义提醒** | ✅ 新建 M6Reminder（一次、18:58）；`reminder-state.xml` 出现 `CUSTOM:…:2026-10-24:1` 与 `RTC_WAKEUP REMINDER_ALARM`；随孕期彻底删除后 scheduled-keys 清空、无待触发闹钟（仅有 cancelled 历史） |
| **健康指标** | ✅ 体重 60.5 kg 保存成功，列表显示「测量值 60.5 kg / 10月24日 18:01」 |
| **云盘连接校验** | ✅ 空账号 →「请填写账号」（Bug #4 真机验证）；非法地址 `not-a-url` →「请填写有效的HTTPS服务地址」 |
| **导出资料** | ✅ 勾选明文确认后按钮启用；结构化包 8,952 B（29 条记录、0 原件）→「导出完成并通过文件回读校验」；PDF 252,429 B，`%PDF-1.4` 头 →「就诊摘要已生成并通过文件回读校验」；SAF 建议名分别为 `孕迹导出-2026-10-24.zip` / `孕迹就诊摘要-2026-10-24.pdf` |
| **孕期删除/回收站/恢复/彻底删除** | ✅ 删除预览计数正确（1产检/0资料/1药品/1方案/1服药/1测量/1日常）→ 全局回收站 → 恢复（数据完整回到首页）→ 再次删除 → 彻底删除（回收站为空） |
| **Bug #5（新）** | ⚠️ 彻底删除资料/孕期后，`no_backup/vault/objects/` 4 个密文原件（mtime 仍为 02:06/02:08）未回收；UI 成功、TrashWorker/ImportWorker 均 SUCCESS；详见 `../2026-10-24-Xiaomi11Ultra-full-functional/bugs.md` Bug #5 |

## 1. 清理

- 设备上本次导出产物（zip/pdf）已拉取到 `exports/` 后从 `/sdcard/Download/` 删除；
- 合成孕期 M6-Test 已彻底删除（回收站空、无活动提醒），应用保留安装、数据为干净空态；
- 截图/日志：`dumps/` 18 份语义树；对象泄漏证据 `objects-after-pregnancy-purge.txt`。

## 2. 限制与待办

1. **Bug #5 待修复**：彻底删除未回收 objects 密文文件（需排查 objectProtection.purgeLocked/drainLocked、恢复后对象目录/代际与 object_pins 状态）。
2. 真机视觉像素级证据仍缺（FLAG_SECURE 无法截图）；可考虑 debug 构建关闭 FLAG_SECURE 专用验证包，或按结构证据验收。
3. 安全设置真机走查未做（需用户设备凭据/生物识别，不能自动化注入）。
4. 图标语义化仅完成低风险部分（底部导航「记录」→文档图标、记录枢纽/我的页部分行），提醒/安全设置仍待设计图标映射。
- 复跑：emulator-5556 全量矩阵 **OK (369 tests) / 0 失败 / EXIT=0**（含图标语义化与云盘用例同步修复，`../2026-10-24-remaining-debug/_tools/lead-full-matrix-round14.log`）。
