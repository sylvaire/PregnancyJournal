# 模拟器补位功能走查（小米 11 Ultra 离线期间，2026-10-24）

- 对应共享任务：`task-11`（负责人 `ui-header`；补充 `task-10` 之后的功能证据缺口）。
- 目的：小米 11 Ultra 连续多轮离线，先在专用模拟器上完成**真机尚未走查**的功能 UI 级覆盖，作为真机恢复后的复核基线。
- 设备：`emulator-5556`（PJ_API36_16K：Android 16 / API 36 / x86_64 / 16 KiB page / 1080×2400 / 420dpi = 411.43×914.29 dp）；基线浅色、竖屏、`font_scale=1.0`、`stay_on=0`。
- App：0.26.0-dev / versionCode 27（`app-debug.apk` sha256 `18ABC03A…C4FE`，含 P0/P1 UI 修复；未重新构建）。
- 方法：`pm clear` 清空数据后从引导页开始，用 `adb shell input tap/text/swipe` 驱动真实 `MainActivity`；证据以 **`uiautomator dump` 语义树 / `run-as` 文件 / `dumpsys alarm|notification` / `/sdcard/Download` 文件清单** 为准（应用设 `FLAG_SECURE`，adb `screencap` 无法截应用内容，未用截图作主证据）。
- 合成数据：孕期档案 `PJ Emulator`；提醒 `CUSTOM-A(-EDITED)`/`CUSTOM-DAILY`/`CUSTOM-WEEKLY`；测量体重 60.5→61 kg；导出文件写入 Download。`input text` 仅支持 ASCII，故中文输入用 ASCII 合成串（表单本身接受任意文本）。
- 限制：模拟器非真机；无真实 WebDAV 服务；E 安全设置按 Lead 指示跳过；未见 `FLAG_SECURE` 直截图。

---

## 0. 结论摘要

| 功能 | 结论 | 关键证据 |
|---|---|---|
| A. 自定义提醒 | ✅ **正常**（创建/列表/编辑/删除、一次/每天/每周、调度键、alarm、通知投递均验证） | `flows/07-21*`、`reminder-state*.txt`、`alarm-*.txt`、`notification-*.txt` |
| B. 健康指标 | ✅ **正常**（新增体重测量→保存→列表→分组趋势→编辑→删除；草稿返回重进保留） | `flows/43-51*` |
| C. 云盘连接 UI | ✅ **基本正常**（表单渲染/校验/失败提示）；⚠️ 发现 1 处轻微文案问题（空账号提示误导，见 §4） | `flows/52-55*` |
| D. 导出资料 | ✅ **正常**（明文勾选前按钮 disabled→勾选后 enabled；结构化数据包 5,898 B、就诊摘要 PDF 160,730 B 均落盘 Download 且页面回读校验通过）；⚠️ 1 处 UX 观察（SAF 返回后需再次勾选明文，见 §4） | `flows/23-41*`、`download-ls-final.txt`、`export-data-2026-10-24.zip`、`visit-summary-2026-10-24.pdf` |
| E. 安全设置 | ⏭️ **未覆盖**（按 Lead 指示跳过；立即锁定/应用锁有锁死风险，模拟器无恢复手段） | — |

**发现的 bug（轻微，未修）**：空账号时校验文案为「请检查账号，不能包含冒号或控制字符」，对“空账号”场景有误导（见 §4.1，含代码定位与复现步骤）。

---

## 1. 启动与引导（flows/00-03）

1. `am force-stop cn.yunji.journal` + `pm clear cn.yunji.journal`（清空后应用数据为空）→ `am start -n cn.yunji.journal/.MainActivity`。
2. 欢迎页文案：`轻轻记录 安心留存`、`创建我的孕期档案`、`无需注册账号 · 数据保存在本机`、底部 今天/产检/记录/我的。
3. 创建孕期档案：档案名称 `我的孕期`（预填）→ 改为 `PJ Emulator` → `保存档案`；首页显示 `今天，慢慢来`、`10月24日 星期六 · PJ Emulator`、`待补充日期`、`产检安排 / 还没有产检预约`，Toast `记录已加密保存到本机`。
4. 证据：`flows/00-welcome.xml`、`01-pregnancy-editor.xml`、`02-home-after-profile.xml`、`03-home-after-save.xml`。

---

## 2. A. 自定义提醒（C05）— ✅ 正常

步骤与结果：

| 步骤 | 操作 | 结果（语义树/文件证据） |
|---|---|---|
| 1 | 我的 → 提醒设置 | 页面含 通知权限/提醒时间/后台可靠性/提醒类型/自定义提醒/调度检查；初始 `未允许；只能使用应用内待办`、`将使用非精确提醒，系统可能推迟`（`flows/05-reminder-settings.xml`） |
| 2 | 授予 `POST_NOTIFICATIONS` + `appops set … SCHEDULE_EXACT_ALARM allow`（为通知投递测试），重进页面 | `flows/06-reminder-settings-bottom.xml` |
| 3 | 新建提醒 → 标题 `CUSTOM-A`、备注 `synthetic-note-a`、日期 2026-10-24、时间 02:06（当前时间+2 分钟）、重复 `一次` → 保存 | 列表行 `CUSTOM-A` / `2026-10-24 02:06 · 仅一次` + 编辑/删除；Toast `提醒已保存`（`flows/07-13*`） |
| 4 | 状态文件 `shared_prefs/reminder-state.xml` | `scheduled-keys` 出现 `CUSTOM:27f5e530-…:2026-10-24:1`（generation 5）（`flows/reminder-state.xml.txt`） |
| 5 | `dumpsys alarm` | `RTC_WAKEUP #4 … cn.yunji.journal`，tag `*walarm*:cn.yunji.journal.REMINDER_ALARM`，`origWhen=2026-10-24 02:06:00`，`exactAllowReason=permission`（`flows/alarm-after-custom.txt`） |
| 6 | 通知投递（到期后） | `dumpsys notification --noredact` 出现 `NotificationRecord pkg=cn.yunji.journal id=741828836 channel=reminders importance=4 category=reminder vis=PRIVATE`，`seen=true`，内部 `android.title=孕迹提醒`、`android.text=打开孕迹解锁后查看`，publicVersion `孕迹提醒：有一条待查看提醒`（`flows/notification-after-due.txt`、`notification-text.txt`） |
| 7 | 编辑：列表 → 编辑 → 标题改 `CUSTOM-A-EDITED` → 保存 | 列表更新为 `CUSTOM-A-EDITED`；状态 generation 5→6、调度键变为 `…:2026-10-24:2`（`flows/14-15*`、`reminder-state-after-edit.txt`） |
| 8 | 删除：编辑/删除 → 「删除提醒？…将被删除，之后不会再通知」→ 删除 | 列表空；`scheduled-keys` 清空，`delivered-alarms` 记录已投递键 `CUSTOM:…:2026-10-24:2|1792807560000`；Toast `提醒已删除`；alarm 取消（`flows/16-17*`、`reminder-state-after-delete.txt`、`alarm-after-delete.txt`） |
| 9 | 重复选项补充验证：新建 `CUSTOM-DAILY`（每天）与 `CUSTOM-WEEKLY`（每周） | 列表分别显示 `2026-10-24 03:14 · 每天` / `2026-10-24 03:24 · 每周`；状态文件出现 daily 1 键 + weekly 7 键（2026-10-24~30），`dumpsys alarm` 对应 8 个 `RTC_WAKEUP`（`flows/18-19*`、`reminder-state-daily-weekly.txt`、`alarm-daily-weekly.txt`） |
| 10 | 清理：删除 daily、weekly | 列表恢复空态；`scheduled-keys` 清空（`flows/20-21*`、`reminder-state-final-empty.txt`） |

> 隐私符合设计：通知内部标题/正文被脱敏（`打开孕迹解锁后查看`），公开版仅提示有待查看提醒，未泄露药名/医院。

---

## 3. B. 健康指标 — ✅ 正常

| 步骤 | 操作 | 结果 |
|---|---|---|
| 1 | 我的 → 健康指标 | 空态卡 `还没有测量记录 / 体重、血压、血糖及自定义指标均可记录。`（`flows/43-measurements-page.xml`） |
| 2 | 记录测量（体重 · kg）→ 测量值输入 `60.5` | 编辑器含 测量值(kg)/测量日期 2026-10-24/测量时间/时区 GMT/来源/备注；`flows/44-measurement-editor.xml` |
| 3 | **草稿保留**：输入后返回上一级 → 再进编辑 | 重新进入时测量值仍为 `60.5`（`flows/45-draft-restored.xml`） |
| 4 | 保存测量 | 列表行 `体重`、`测量值 60.5 kg`、`2026-10-24 02:45:51 Z`、详情/修改/删除；Toast `已保存到本机`（`flows/46*`） |
| 5 | 分组趋势 | 近7天/近30天/全部；`体重 · kg · 测量值`、`1条原始读数`、散点趋势（content-desc `测量散点趋势，详细数值见下方列表`）、`最小 60.5 · 最大 60.5 kg`（`flows/47-measurement-trend.xml`） |
| 6 | 修改：测量值 60.5 → `61` → 保存 | 列表更新 `测量值 61 kg`，Toast `已保存到本机`（`flows/48-49*`） |
| 7 | 删除 → 「删除这条测量？移入全局回收站，可恢复；30天后安排清理。」→ 删除 | 空态恢复；Snackbar `已移入全局回收站 · 撤销`（`flows/50-51*`） |

> 说明：本轮只验证体重（kg）指标；血压/血糖/自定义指标与单位切换未逐项走查（同一编辑器/同一保存路径，风险低）。

---

## 4. C. 云盘连接 UI — ✅ 基本正常（1 处轻微文案问题）

| 步骤 | 操作 | 结果 |
|---|---|---|
| 1 | 我的 → 云盘连接 | 表单渲染：`连接你的云盘`、服务地址（预填 `https://dav.jianguoyun.com/dav/`）、账号、应用密码（含 显示）、备份父目录、浏览云盘目录、测试连接/保存连接、云端备份小节（`flows/52-cloud-connection.xml`） |
| 2 | 服务地址改为 `not-a-url` → 测试连接 | 校验提示 **`请填写有效的HTTPS服务地址`**（`flows/54-cloud-invalid-endpoint.xml`） |
| 3 | 服务地址改为 `https://127.0.0.1:9/dav/`（不可达）→ 测试连接 | 校验提示 **`请检查账号，不能包含冒号或控制字符`**（因账号为空，先于网络测试被拦下；`flows/55-cloud-unreachable.xml`） |

### 4.1 发现的 bug（轻微，未修，仅记录）

- **现象**：服务地址填写为合法 HTTPS、但**账号留空**时，点「测试连接」提示「请检查账号，不能包含冒号或控制字符」，而不是提示“请填写账号”。文案对空值场景有误导。
- **复现**：我的 → 云盘连接 → 服务地址填 `https://127.0.0.1:9/dav/` → 账号留空 → 点「测试连接」→ 提示「请检查账号，不能包含冒号或控制字符」。
- **代码定位**（只读核对，未修改）：`app/src/main/java/cn/yunji/journal/core/network/CloudConnection.kt:13`
  `require(user.isNotBlank() && user.length<=256 && ':' !in user && user.none(Char::isISOControl)) {"请检查账号，不能包含冒号或控制字符"}`
  —— 空账号与非法字符共用同一条 message，建议按条件拆分文案（如空值时提示“请填写账号”）。
- **严重度**：轻微（仅提示文案，不影响功能/数据）；已按任务要求**报告 Lead，不擅自修复**。
- **未覆盖**：真实 WebDAV 成功连接 / 证书错误 / 目录浏览（无真实服务）；「保存连接」成功路径（需真实可用连接）。

---

## 5. D. 导出资料 — ✅ 正常（1 处 UX 观察）

| 步骤 | 操作 | 结果 |
|---|---|---|
| 1 | 我的 → 导出资料 | P0-4 页头 `导出资料` + `返回上一级`；说明段、日期范围（留空）、包含原件/回收站 Switch、导出范围 14 项、统计当前范围、明文勾选、两个导出按钮（`flows/23-export-initial.xml`） |
| 2 | 未勾选明文时按钮状态（原始语义树） | 明文 CheckBox `checked=false`；两个导出按钮容器 `clickable=true enabled=false`（不可点，`flows/24-export-bottom-unacked-raw.xml`） |
| 3 | 统计当前范围 | `当前范围共 2 条记录`、`pregnancies：1`、`date_revisions：1`（`flows/25-export-preview.xml`） |
| 4 | 勾选「我了解导出内容为明文，将自行妥善保管」 | 明文 CheckBox `checked=true`；两个导出按钮容器 `enabled=true`（`flows/26/28-export-acked-raw.xml`） |
| 5 | 导出结构化数据包 → SAF（已在 Download，默认文件名 `孕迹导出-2026-10-24.zip`）→ SAVE | 出现应用恢复确认「继续处理所选文件？已恢复1个文件的选择结果，确认后继续。」→ 继续 → Toast `正在导出结构化数据包，结果会显示在导出页。`；文件 **`/sdcard/Download/孕迹导出-2026-10-24.zip` 5,898 B（>0）**；页面 `导出完成并通过文件回读校验 / 共 2 条记录，原件 0 份`（`flows/29-32*`、`download-ls-after-zip-done.txt`、`download-ls-final.txt`） |
| 6 | 生成就诊摘要 PDF | 重新勾选明文（见 §5.1）→ PDF 导出 → SAF 默认 `孕迹就诊摘要-2026-10-24.pdf` → SAVE → 「继续处理所选文件？」确认 → 继续 → 文件 **`/sdcard/Download/孕迹就诊摘要-2026-10-24.pdf` 160,730 B（>0）**；页面 `就诊摘要已生成并通过文件回读校验 / 共 1 页`（`flows/33-41*`、`download-ls-final.txt`） |
| 7 | 导出文件留档 | 两个文件已 `adb pull` 到 `flows/`（zip 5,898 B、pdf 160,730 B，均可用 `unzip -l`/PDF 头校验） |

### 5.1 UX 观察（非 bug，记录）

- 每次从 SAF 返回后，应用会展示「**继续处理所选文件？已恢复1个文件的选择结果，确认后继续。**」确认框（两次导出均出现），这是应用对 OS picker 返回后 Activity 重建的恢复机制；确认后导出正常完成。
- 该重建同时会**重置明文勾选状态**：完成一次导出后，页面上「我了解导出内容为明文」变回未勾选，两个导出按钮回到 `enabled=false`，需要用户重新勾选才能进行下一次导出（`flows/35-export-buttons-state-raw.xml` 显示 enabled=false，`37-export-state.json.xml` 中 ack `checked=false`）。属“每次导出重新确认明文”的安全语义，可接受；如产品希望“一次勾选、当页多次导出”，需持久化 ack 状态（本轮仅记录，未改代码）。

---

## 6. E. 安全设置 — ⏭️ 未覆盖

按 Lead 指示跳过：应用锁/严格模式/立即锁定涉及应用内 PIN 与锁定页解锁，若设置后无法自动恢复会阻塞后续走查；模拟器上未开启任何安全设置，应用保持可直接进入。

---

## 7. 设备与数据保留状态

- 走查结束后：`am force-stop cn.yunji.journal`、`am force-stop cn.yunji.journal.test`；应用进程未运行。
- **数据保留**（未清理，供后续复核）：孕期档案 `PJ Emulator`（存于 `no_backup/vault/` 加密库 + `files/datastore/`）；自定义提醒与测量记录已在走查中删除（`shared_prefs/reminder-state.xml` 的 `scheduled-keys` 为空）；导出文件保留在 `/sdcard/Download/`（zip/pdf 各 1 个）。
- 权限状态：为通知投递测试授予 `POST_NOTIFICATIONS` 与 `SCHEDULE_EXACT_ALARM`（appops allow），保留授权。
- 设备基线：浅色、竖屏、`font_scale=1.0`、`stay_on_while_plugged_in=0`、`wm size=1080x2400`、`wm density=420`（详见 `_tools/emulator-baseline-restored.txt`）。
- 应用数据路径：`databases/` 为空、`files/{datastore,profileInstalled}`、`no_backup/{vault,androidx.work.workdb,document-viewers}`、`shared_prefs/reminder-state.xml`（`flows/app-data-state.txt`）。

---

## 8. 未覆盖项与原因

| 项 | 原因 |
|---|---|
| E 安全设置（应用锁/严格模式/立即锁定） | 按 Lead 指示跳过（锁死风险，模拟器无恢复手段） |
| 云盘真实连接成功/证书错误/目录浏览/保存连接成功 | 无真实 WebDAV 服务；只验证 UI 校验与失败提示 |
| 健康指标 血压/血糖/自定义指标、单位切换 | 时间盒限制；同一编辑器/保存路径，风险低，待真机复核 |
| 提醒：每个渠道的通知样式/角标/声音、精确/非精确对比 | 只验证投递（channel=reminders）与文案脱敏；系统通知 UI 细节需真机 |
| 附件/资料、产检、用药等其余功能 | 非本任务范围（真机走查已覆盖部分，模拟器补位仅限本任务 4 项） |
| 应用 `FLAG_SECURE` 下截图 | 无法用 adb screencap；证据以语义树/文件/系统服务为准（视觉可由 `UiSnapshot*Test` 已有快照补充，不在本目录） |

---

## 9. 证据索引

```
docs/verification/2026-10-24-emulator-functional-coverage/
├─ README.md                         本文件
├─ flows/                            77 个证据文件：
│  ├─ 00-03*.xml                     引导页/创建孕期档案/首页
│  ├─ 04-06*.xml                     我的/提醒设置（顶部/底部）
│  ├─ 07-21*.xml + reminder-state*.txt + alarm-*.txt + notification-*.txt   自定义提醒全流程
│  ├─ 23-41*.xml + download-ls*.txt + 2 个导出文件                          导出资料全流程
│  ├─ 43-51*.xml                     健康指标全流程
│  ├─ 52-55*.xml                     云盘连接表单/校验
│  ├─ app-data-state.txt             走查后应用数据目录
│  └─ last.xml                       最近一次 uiautomator dump
└─ _tools/
   ├─ ui.ps1                          adb UI 驱动脚本（dump/tap/input/key/swipe，可复跑）
   ├─ device-baseline-before.txt      走查前设备状态（如生成）
   └─ emulator-baseline-restored.txt  跑后基线恢复记录
```

> flows/ 中同时保留了导出文件原件（adb pull 后重命名为 ASCII 便于工具处理）：export-data-2026-10-24.zip（5,898 B，17 个条目，含 README.txt/fields.json 12,440 B/manifest.json 867 B/data/pregnancies.json 258 B 等，flows/export-zip-entries.txt）与 visit-summary-2026-10-24.pdf（160,730 B，%PDF-1.4 头、含 %%EOF，flows/export-pdf-header.txt）。均为合成数据，无真实医疗内容。

---

## 10. 限制

1. **模拟器 ≠ 真机**：全部结论来自 API36 模拟器；MIUI 后台策略、通知样式、SAF 行为可能与小米真机不同，真机恢复后需复核（尤其 B/C/D 的系统交互部分）。
2. **视觉未验证**：`FLAG_SECURE` 导致不能截应用内容；本任务以语义树/状态为准，页面视觉一致性见既有 `UiSnapshot*` 证据。
3. **合成数据/ASCII 输入**：`adb input text` 不支持中文，文本字段使用 ASCII 合成串；不涉及中文输入法行为验证。
4. **时间盒**：A 全流程 + D 全流程完整；B 仅体重指标；C 仅校验/失败路径；E 未覆盖。
5. 未修改任何产品代码/测试/主文档；发现的轻微文案问题只报告、未修。

