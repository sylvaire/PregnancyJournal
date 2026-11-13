# 2026-11-12 小米 11 Ultra 真机全流程走查（孕妇使用者视角）

设备：小米 11 Ultra（M2102K1C / star），Android 13（SDK 33），MIUI V14.0.22.12.5.DEV，`PAGE_SIZE=4096`。无线调试 `<adb设备标识>`。屏幕 1440×3200 / 560 dpi / 字体 1.0。

安装：`0.26.0-dev`（versionCode 27）覆盖安装，**保留既有数据**；安装后 schema14→15 自动迁移，原有 `DEMO-20261025` 档案与首页统计正常可读。

操作方式：应用启用 `FLAG_SECURE`，真机截图不可用，因此以 `adb shell input` + `uiautomator dump` 语义树驱动与核对，与项目既有真机验证方式一致。

本轮以**真实孕妇使用者**的路径操作：建档 → 产检 → 资料 → 用药 → 服药 → 日常 → 健康 → 备份 → 恢复 → 回收站 → 导出 → 提醒。

## 1. 通过的功能（真机实测）

| 流程 | 实测结果 |
|---|---|
| 覆盖安装与迁移 | schema14→15 升级后旧档案、首页孕周、历史备份副本均保留 |
| 孕期建档 | 新建 `MyPregnancy`，LMP 2026-08-01 → 首页显示 **14周+5天**、预产期 **2027-05-08**，计算正确 |
| 产检预约 | 创建 `AnomalyScan` 11月12日 09:30 + 医院，列表与详情显示正确；返回后**草稿保留** |
| 资料导入 | 系统图片选择器导入合成图片 5,885 B → 加密保存、缩略图生成；预览支持双击缩放 |
| 用药方案 | `FolicAcid` / 0.4mg / tablet / 每次 1 tablet / 每天 08:00 保存成功 |
| 计划服药点 | 次日正确生成 `08:00 · 待确认`；当天因方案晚于 08:00 创建而无计划（行为合理） |
| 服药补记 | 补记「已服用」保存成功，当天补记区显示记录 |
| 日常记录 | 症状记录保存成功，时间线按日期分组并显示「孕14周 + 5天」 |
| 健康指标 | 体重 62.5 kg 保存；分组趋势图、近7天/30天/全部筛选、最小/最大值统计正常 |
| 提醒 | 提醒设置页状态完整；测试通知实际投递（channel=`reminders`、importance=4、visibility=PRIVATE） |
| 全局回收站 | 删除产检 → 计数 1、「保留的资料」1 → 恢复 → 归零；日期显示「11月12日」中文格式 |
| 本地加密备份 | 导出 `孕迹加密备份-2026-11-12.yunji` **44,086 B**，应用回读校验通过 |
| 结构化导出 | `孕迹导出-2026-11-12.zip` 回读校验通过，共 71 条记录 + 1 份原件 |

## 2. 云端备份（坚果云 WebDAV）

服务：`https://dav.jianguoyun.com/dav/`，账号与应用密码由用户提供。

### 2.1 成功部分

- 父目录设为 `PregnancyJournal` 后测试连接通过：「连接可读写，测试文件已回读校验」，能力矩阵（读取/写入回读/创建子目录/清理）全部「可用」。
- 保存连接 → 设为**主设备** → 立即上传成功（22:25、22:54 两次）。
- 云端实测结构（curl 独立核对）：

```
/dav/PregnancyJournal/pregnancy-journal/<vaultId>/
├── vault.json (265 B)
├── commits/<snapshotId>.json (667 B)
├── snapshots/ 21,287 B（首次）+ 28,727 B（含新数据）
└── objects/ 5,917 B（原件）+ 8,497 B（缩略图）
```

- **从云端恢复端到端通过**：输入恢复密钥 → 列出 2 个快照（22:54 序号327 / 22:25 序号309）→ 选择最新 → 下载校验 → 预览（孕期3/产检2/原件1/用药方案2/服药1/健康2/日常2/提醒0）→ 确认 → 「资料恢复完成」。恢复后首页、产检、资料、趋势全部还原；代际目录正确切换（active=`ea675306…`，previous=`0a340084…`），原件在新旧代际各一份。
- 错误密钥被正确拒绝：「无法读取云端备份，请检查连接与恢复密钥」，未泄露信息、未破坏当前数据。

### 2.2 发现的问题（P0）

**问题 1：父目录留空时连接测试必然失败（坚果云）**

- 现象：首次连接（父目录留空、使用服务根目录）测试返回 **「云盘请求失败（400）」**。
- 根因（curl 独立复现 3/3）：坚果云 WebDAV **根目录不接受文件写入**：
  - `PUT /dav/probe.bin` → **404**
  - `PUT /dav/PregnancyJournal/probe.bin` → **201**
  - `MKCOL /dav/<name>` 在根目录 → 201，但随后 `PUT /dav/<name>.bin` 仍 404
- 应用侧：`CloudProbe.run` 把探测文件写成 `connection.folder + name + ".bin"`。`folder` 为空时文件落在根目录 → 400/404，且被归类为连接失败，用户无法判断是账号问题还是服务限制。
- 影响：**默认路径（父目录留空）在坚果云上不可用**，而这正是最可能的首次使用方式。用户必须自行猜到「先建子目录并填写父目录」。
- 建议：探测写入改为在自建临时**目录内**进行（`testDirectory/test.bin`），或在父目录为空时自动创建应用专属子目录并在 UI 说明；错误文案应区分「根目录不允许写入」与服务商限制。

**问题 2：探测失败后与云端残留形成死锁**

- 现象：问题 1 失败后提示「上次测试可能遗留临时项目：.yunji-probe-…」，再次测试返回「上次测试尚有待清理项目，请先使用原服务、账号和目录完成清理」，无法继续。
- 但该临时目录**无法通过 DAV 删除**：`DELETE /dav/.yunji-probe-test-001/` 与带尾斜杠形式均返回 **403**（坚果云拒绝删除以 `.` 开头的隐藏目录）。
- 结果：用户被困在「必须清理才能重测」与「客户端无法清理」之间；只能通过「清除本机测试标记」（文案已诚实说明不会删除云端项目）绕开。本轮即如此绕过后才成功。
- 影响：一次失败的首次连接会让用户认为服务不可用；残留目录在云端账户中长期占位。
- 建议：改用非隐藏名称（如 `yunji-probe-<uuid>`）；清理失败时不要阻塞后续测试，改为提示并允许重试。

**问题 3：「立即上传备份」需要滚动才可见**

- 现象：备份状态卡片位于页面顶部，但进入「云盘连接」页时视口停在连接表单处，用户需向上滚动才能看到「立即上传备份」。
- 影响：首次用户不容易发现手动上传入口，只能依赖自动备份（而自动备份默认关闭）。

## 3. 发现的其他问题

### 问题 4（P1）：结构化导出缺少自定义提醒

- 导出 zip 实测含 13 个数据段（`pregnancies` … `journal_entries`），**没有 `custom_reminders.json`**。
- 但加密备份 `LogicalSnapshot` 已完整包含 `customReminders`（恢复预览也显示「自定义提醒 0」一项），说明是导出链路未随 schema 14 回填。
- 导出页显示「已选择 13 类资料」，用户无法选择导出提醒。
- 与本仓库同日排查记录 `docs/verification/2026-11-12-remaining-issues-audit/README.md` §3.1 一致。

### 问题 5（P1）：摘要 PDF 导出按钮偶发无响应

- 现象：结构化数据包导出成功后，点击「生成就诊摘要 PDF」多次无任何反馈——无系统保存界面、无错误文案、无进度条；logcat 确认触摸事件已到达应用。
- 复现：同一会话内先导出结构化包、再点 PDF；滚动后重试仍无响应。
- 影响：用户无法导出就诊摘要；且按钮保持可点击外观却不响应，比禁用更困惑。

### 问题 6（P2）：表单连续输入时焦点/坐标易错位

- 现象：用药方案表单中，键盘弹出/收起会推动布局，连续按坐标点击相邻字段时多次把内容输入到上一个字段（本轮出现 2 次：账号框混入密码、单位框混入时间）。
- 影响：对视力/精细操作不佳的孕期用户不友好；属于布局稳定性问题而非单次脚本误差（同一操作模式重复出现）。

### 问题 7（P2）：多次返回键会退出应用进入系统设置

- 现象：在子页面连按返回键时，越过应用根页后直接回到桌面/系统设置（无线调试、开发者选项页），而非停留在应用。
- 影响：孕妇在使用中误触返回会丢失上下文；建议根页返回行为明确（例如最小化而非穿透）。

## 4. 本轮修复（代码 + JVM 验证）

针对问题 1/2 做了最小修复，并加了两个回归测试：

- `core/network/CloudProbe.kt`
  - 探测文件改为写入**自建子目录内**（`<parent>/yunji-probe-<uuid>/test.bin`），不再直接写父目录；目录创建失败时不再尝试写文件（否则会落回根目录被误判为不可写）。
  - 探测目录名去掉 `.` 前缀（`yunji-probe-` 而非 `.yunji-probe-`）：坚果云拒绝 DELETE 以 `.` 开头的隐藏目录（403），这是问题 2 死锁的直接原因。
- `data/CloudConnectionRepository.kt`
  - 清理遗留探测时，先删目录内子项再删目录；同时兼容旧版 `<name>.bin` 残留；404 视为不存在可继续。
- `feature/CloudConnectionScreen.kt`
  - 父目录说明改为：「坚果云等服务商禁止在根目录写入与删除文件，请填写一个已存在的子目录；留空仅适用于允许根目录读写的服务。」
- 测试：`CloudProbeTest` 新增 2 项——`serverRejectingRootFileWritesStillProbesInsideItsOwnDirectory`（模拟根目录拒绝 PUT）、`probeDirectoryIsNotHiddenSoItStaysDeletable`；全类 **10/10**。

验证：

- JVM 全量 **353 项 / 0 失败**（57 类）。
- lint **0 错误 / 15 警告**（无新增）。
- 坚果云实测新探测序列（curl 独立复现）：`MKCOL <parent>/yunji-probe-xxx` → 201，`PUT .../test.bin` → 201，`GET` → 200，`HEAD` → 200，`DELETE file` → 204，`DELETE dir` → 204。

### 仍未解决（服务商硬限制）

- 坚果云**根目录**上的 `DELETE` 恒为 **403**（实测：根下空目录、根下含文件目录均拒绝；同一目录移到 `PregnancyJournal/` 下即 204）。因此「父目录留空」在坚果云上无法做到探测后完全清理。
- 当前处理：UI 明确引导用户填写子目录；探测失败不再因隐藏目录而永久死锁。
- 如需彻底支持「留空」，只能在根目录写入并容忍残留，或改用非 DAV 的服务商能力探测——建议保持现方案（要求子目录）。

## 5. 数据安全确认

- 云端仅有加密内容：快照 28,727 B、对象 5,917/8,497 B 均为密文；`vault.json` 265 B 不含明文医疗内容。
- 恢复前自动生成安全副本，恢复后旧代际保留（`previousId` 指向原代际），原件未丢失。
- 错误恢复密钥不泄露信息、不修改数据。
- 恢复后自动云端备份保持关闭、设备回落为非主设备，符合设计。

## 6. 清理与遗留

- 云端残留：`/dav/.yunji-probe-test-001/`、`/dav/.yunji-probe-test-002/`、`/dav/.yunji-probe-e7eb2aa4-3624-4211-a7ee-2b81b88acf18*` 无法通过 DAV 删除（403），需用户在坚果云网页端手动清理。
- 本地残留（验证用，均位项目 `.local/` 或设备下载目录）：`synthetic-report.png`、`孕迹加密备份-2026-11-12.yunji`、`孕迹导出-2026-11-12.zip`。
- 设备应用保留 `MyPregnancy` 合成档案与本次测试数据，未删除；如需清空可在应用内删除孕期。

## 7. 结论

- 核心孕期流程（建档/产检/资料/用药/服药/日常/健康/回收站/提醒）在真机上**全部可用**，计算与显示正确。
- 加密备份、云端上传、云端恢复到新代际的完整链路**在真机与真实服务商（坚果云）上首次端到端通过**——这是此前"未与真实服务商联测"的重要缺口补足。
- 但**默认配置（父目录留空）在坚果云不可用**，且失败后存在清理死锁，属发布前应修问题。

## 8. 后续修复（2026-11-13）

针对第 3 节列出的问题逐项修复并验证。

### 8.1 P1-3 结构化导出缺少自定义提醒 — 已修复

- `core/export/ExportScope.kt`：`ALL_SECTIONS` 增加 `custom_reminders`（13 → 14 类）。
- `core/export/ExportFieldCatalog.kt`：新增该段 13 个字段（含 `repeat`/`enabled`/`endDate`）。
- `core/export/ExportRowMapper.kt`：新增 `customReminders(...)` 映射。
- `data/ExportService.kt`：按孕期、日期范围与回收站开关读取并写入该段。
- `feature/ExportScreen.kt`：段名显示为「自定义提醒」。
- 回归测试：`StructuredExportTest.customRemindersAreExportedWithCataloguedFields`（断言段落必须存在、字段集合与目录一致、`enabled`/`repeat`/`endDate` 值正确）。

真机验证：导出页显示「已选择 **14** 类资料」；导出的 zip 含 `data/custom_reminders.json`，内容为 `{"title":"PrenatalCheckup","repeat":"DAILY","enabled":"true",...}`，记录总数 72。

### 8.2 P1-4 摘要 PDF 按钮“无响应” — 已修复

根因不是按钮本身，而是**导出向导状态在系统保存器返回后被清空**：

1. 系统保存器使应用进入后台 → `MainActivity.coverSession()`；
2. `coverSession()` 调用 `model.clearSensitiveCloud()` → `ExportController.leave()`；
3. 旧实现 `leave()` 直接 `ExportUiState()`，把 `plaintextAcknowledged` 一并清空；
4. 返回后再点导出时 `canStart == false`，按钮静默禁用，表现为“点了没反应”。

修复：

- `feature/ExportController.kt`：`leave()` 只清运行态与结果（`busy`/`loading`/`message`/`detail`/`lastResult`/`preview`），**保留导出范围与明文确认**；新增 `reset()` 供会话真正结束使用。
- `feature/JournalViewModel.kt`：`onCleared()` 用 `reset()`，`clearSensitiveCloud()` 仍用 `leave()`；导出启动器改为每次调用读取当前 `exportLauncher`（避免 Activity 重建后持有旧引用）。
- `ExportController.start()`：启动器抛错时显示可读提示，不再静默。
- `MainActivity.launchFile()`：`busy` 时给出「正在处理上一次操作」提示。

回归测试：`ExportControllerTest` 新增 2 项（返回后仍可导出、`reset()` 清空全部），全类 5/5。

真机验证：导出摘要 PDF → 取消保存 → 再次点击 PDF，仍能正常弹出保存界面并生成 `孕迹就诊摘要-2026-11-13.pdf`（4 页，277,218 B，回读校验通过）。

### 8.3 P2-5 长表单输入焦点错位 — 已修复

- `feature/JournalApp.kt` 的 `FormField` 增加 `BringIntoViewRequester`：字段获得焦点后延迟 150 ms（等 IME inset 动画推进）滚入可视区。

真机验证：在「新建产检预约」点击下方「检查项目」字段，键盘弹出后该字段自动滚动到 y≈1266 并保持可见。

### 8.4 复查后撤销的两项

- **P2-6 连按返回穿透到系统设置**：`NavDisplay.onBack = { pop() }` 在根页不消费事件，属 Compose 导航的正常行为（返回键退出应用）。真机走到系统设置是自动化脚本连续发送返回键所致，不是应用缺陷。本轮不做改动。
- **P2-7「立即上传备份」需滚动**：该按钮在「备份状态」卡内，是页面第 3 个元素；真机复查确认进入页面即在首屏，先前记录源于脚本进入时的滚动残留。不成立。

### 8.5 本轮验证结果

| 检查 | 结果 |
|---|---|
| JVM 单元测试 | **359 项 / 0 失败 / 0 错误**（58 个测试类） |
| lint | **0 错误 / 15 警告** |
| 真机导出（结构化） | 14 类 + `custom_reminders.json`，72 条记录，回读校验通过 |
| 真机导出（摘要 PDF） | 连续两次均成功，4 页 / 277,218 B |
| 真机表单焦点 | 「检查项目」点击后自动滚入视野 |
| API36/16KiB 仪器矩阵 | **`OK (379 tests)` / 0 失败 / 0 跳过 / 813.414 s**（`.local/full-matrix-2026-11-13.log`，本轮改动后全量复核） |

## 9. P0-1 深挖：`Failed requirement.` 的两个真实根因（2026-11-13 续）

前一轮把 P0-1 记为「已修」，但真机复测仍出现 `Failed requirement.`。本轮定位到**两个独立缺陷**。

### 9.1 根因 A：坚果云目录名长度上限（服务商约束）

`CloudProbe` 用 `yunji-probe-<UUID>`（**48 字符**）作为探测目录名。坚果云拒收并返回：

```xml
<d:error xmlns:s="http://ns.jianguoyun.com"><s:exception>IllegalArgument</s:exception>
<s:message>sandbox name is too long</s:message></d:error>
```

实测阈值（curl，逐长度尝试）：

| 目录名长度 | MKCOL 结果 |
|---|---|
| 20 / 24 / 28 | **201 Created** |
| 32 / 36 / 40 / 44 / 48 | **400 Bad Request** |

所以「父目录留空」这一默认路径在坚果云上**必然失败**——不是网络问题，也不是权限问题。

修复：探测目录名改为 `yjp-<8 位 hex>`（共 12 字符）。

### 9.2 根因 B：改名后校验正则未同步（自引入缺陷）

`CloudConnectionStore.beginProbe` / `pending()` 用正则强制要求探测名匹配历史格式：

```kotlin
require(Regex("\.yunji-probe-[0-9a-f-]{36}").matches(value.name))   // 无消息 → "Failed requirement."
```

改名后该断言必然失败，且 `require` 未带消息，Kotlin 抛出英文 `Failed requirement.`，被 `CloudConnectionController.message()` 原样展示给用户。这就是真机截图里那个不可读的英文报错。

修复两处：

1. `CloudConnectionStore`：正则改为同时接受 `yjp-<8 hex>`（现行）与 `.yunji-probe-<uuid>`（历史），并补齐中文失败消息；
2. `CloudConnectionController.message()`：`IllegalArgumentException` 的消息若为空或以 `Failed requirement` 开头，改用可读中文说明，不再把内部断言泄露给用户。

### 9.3 验证证据

| 验证 | 方式 | 结果 |
|---|---|---|
| 真实服务商探测（JVM + 真实网络） | 临时测试调用 `CloudProbe.run` 打坚果云，父目录为空 | `PROBE_OK: writable=true verified=true` |
| 真实服务商探测（完整应用栈） | 新增 `JianguoRootFolderProbeTest`（androidTest，凭据经 instrumentation 参数传入） | **`OK (1 test)`**，`readable/writable/verified/canCreateDirectory` 全为 true |
| UI 连接测试（父目录留空） | 模拟器全新安装 → 云盘连接 → 填凭据 → 测试连接 | 显示「**连接可读写，测试文件已回读校验**」，三项能力「可用」，**无 `Failed requirement.`** |
| 保存连接 | 同页点击「保存连接」 | 「连接已加密保存。尚未执行备份。」 |
| 云端上传 | 同页「立即上传备份」 | 「最近上传：11月12日 17:10」 |
| 云端独立回读 | curl PROPFIND | `pregnancy-journal/<vaultId>/` 下 2 个 snapshot（955 B）+ 2 个 commit（660 B） |

### 9.4 附带发现：缺恢复密钥时上传无提示

排查上传时发现 `CloudBackupCoordinator.run()` 的 `requireNotNull(settings.load())`（需要恢复密钥）会让上传在准备阶段失败，但 UI 上此前没有引导。已加前置检查：

- `CloudBackupController` 增加 `recoveryConfigured` 回调；
- 未确认密钥时直接提示「请先在「本地备份」生成并核对恢复密钥，再上传云端备份。」，不再发起上传；
- `CloudBackupControllerTest` 2 项覆盖（缺密钥不发起上传并给出指引、有密钥正常上传）。

### 9.5 本轮回归

| 检查 | 结果 |
|---|---|
| JVM 单元测试 | **363 项 / 0 失败 / 0 错误**（59 个测试类） |
| lint | **0 错误 / 15 警告** |
| 仪器矩阵（最终） | **`OK (380 tests)` / 0 失败 / 0 跳过 / 830.093 s**（`.local/full-matrix-2026-11-13-final.log`，含新增 `JianguoRootFolderProbeTest`） |

诊断期间临时加入的 `YJ_UPLOAD` 日志与 `TempJianguoProbeTest` 已全部移除（`grep -rn YJ_UPLOAD app/src/main` 无结果）。

## 10. P0-2 清理死锁在「父目录留空」下的闭环（2026-11-13 续二）

上一轮虽已让探测名合规，但**父目录留空时连续两次测试连接**仍未实测；且 `CloudConnectionRepository` 清理遗留探测目录时 `activeClient.delete(directory)` 在坚果云根目录返回 403，`catch` 只放行 404，会再次抛出。本轮修复并实测。

### 10.1 代码修复

`data/CloudConnectionRepository.kt`

- 新增 `UNDELETABLE = setOf(403, 405, 501)`：服务商拒绝删除残留（坚果云根目录 `DELETE` 恒 403）时应容忍，不再抛异常。
- 清理遗留的 `<name>.bin`、探测子项、探测目录三处都改为容忍该状态集。
- 清理尝试结束后**无条件调用 `store.probeCleaned()`**：即使云端残留空目录无法删除，也要清掉本机标记，否则用户会永远卡在「必须清理才能重测」。

`core/network/CloudProbe.kt`

- `cleaned` 的语义收窄为「探测内容已清理」；目录删除结果单独记录到 `directoryRemoved`。
- `canDelete` 改为 `cleaned && deleteVerified && directoryRemoved && directoryCreated`，仍如实反映服务商是否允许删除目录（坚果云为 `false`）。
- 目录删除失败不再把整次测试标记为 `cleanupPending`，避免默认路径（父目录留空）下的死锁。

### 10.2 回归测试

`CloudProbeTest` 新增 `leftoverProbeDirectoryThatProviderRefusesToDeleteStillAllowsRetest`：

- mock 增加 `denyDirectoryDelete` 开关，精确模拟「文件可删、目录 DELETE 恒 403」；
- 断言第一次 probe 完成读写校验且 `canDelete=false`；
- 断言**第二次 probe 不因旧残留抛异常**并仍完成读写校验。

`CloudProbeTest` 共 **12/12 通过**。

### 10.3 真实服务商实测：连续两次测试连接

新增设备测试 `JianguoRepeatedProbeTest`（真实坚果云、父目录留空、连续两轮 probe + 保存连接）：

```
round=1 capabilities=CloudCapabilities(readable=true, writable=true, verified=true,
        canCreateDirectory=true, canDelete=false, cleanupPending=false, testName=)
round=2 capabilities=CloudCapabilities(readable=true, writable=true, verified=true,
        canCreateDirectory=true, canDelete=false, cleanupPending=false, testName=)
saved=true folder=[]
```

结果 **`OK (1 test)`**。要点：

| 字段 | 含义 |
|---|---|
| `writable/verified=true` ×2 | 两轮都完成写入与回读校验，**第二轮未被第一轮残留阻塞** |
| `cleanupPending=false` ×2 | **清理死锁已消除**（这正是 P0-2 的目标） |
| `canDelete=false` | 如实反映坚果云根目录禁止 `DELETE`，未伪装成成功 |
| `saved=true folder=[]` | 父目录留空可正常保存为 `emptyList()` |

### 10.4 本轮回归

| 检查 | 结果 |
|---|---|
| JVM 单元测试 | **364 项 / 0 失败 / 0 错误**（59 个测试类） |
| lint | **0 错误 / 15 警告** |
| 仪器矩阵 | **`OK (381 tests)` / 0 失败 / 0 跳过**（`.local/full-matrix-2026-11-13-r3.log`，含 `JianguoRepeatedProbeTest` 与 `JianguoRootFolderProbeTest`） |

### 10.5 真机尝试（小米 14 Pro）

验证要求「重新连接手机」。本轮发现并连接了一台 **Xiaomi 14 Pro**（`23116PN5BC` / Android 16 / SDK 36 / 4 KiB 页 / 1440×3200，无线调试 `<局域网地址>`），非此前的 11 Ultra。

在该机上安装 debug 与 test APK 成功，但：

1. 设备处于锁屏状态（`mDreamingLockscreen=true`），UI 操作需机主解锁；
2. 仪器测试进程内 DNS 解析失败（`GaiException: EAI_NODATA`），而同一设备的 shell `curl` 请求坚果云返回 207、`ping` 正常——属 MIUI 对测试进程的网络限制，不是应用缺陷。

因此**连续两次 probe 的真机验证未完成**；该结论以模拟器 + 真实坚果云的结果为准（第 10.3 节）。

清理：云端遗留的 `yjp-*` 空探测目录因根目录 DELETE 403 无法通过 DAV 删除，需在坚果云网页端手动清理；这些目录不含任何数据。

## 11. 403 容忍改动后的完整上传复验（2026-11-13 续三）

第 10 节的 403 容忍改动只验证到「连续两次 probe + 保存连接」，**上传未在该改动后重跑**。本节补齐。

### 11.1 完整 UI 链路（模拟器 + 真实坚果云 + 父目录留空）

| 步骤 | 界面反馈 |
|---|---|
| 建档（`UploadVerify`） | 首页显示 `11月12日 星期四 · 我的孕期UploadVerify` |
| 生成并核对恢复密钥 | 「恢复密钥已确认」（第1组 `ZUQMLY`、第8组 `dlYTfq`） |
| 测试连接（**第 1 次**，父目录留空） | 「连接可读写，测试文件已回读校验。请保存连接。」三项能力「可用」 |
| 测试连接（**第 2 次**，父目录留空） | 同样成功，**无「待清理」、无 `Failed requirement.`** |
| 保存连接 | 「连接已加密保存。尚未执行备份。」 |
| 立即上传备份 | 「最近上传：11月12日 18:33」+「云端备份已提交并回读验证」 |

### 11.2 云端独立核对（curl）

新 vault `4449b60c-52e4-4326-b9ac-ca2940f7e2ca`：

```
pregnancy-journal/4449b60c-.../
├── vault.json                              265 B
├── snapshots/1f23bc6c-....bin              955 B
├── commits/1f23bc6c-....json               660 B
└── objects/                                （空：该档案无附件，符合预期）
```

**密码学级一致性校验**（下载后本地计算）：

| 校验项 | 结果 |
|---|---|
| snapshot 大小 vs commit `cipherSize` | 955 = 955 ✓ |
| snapshot SHA-256 vs commit `cipherSha256` | `5fcc2309…a278d` 完全一致 ✓ |
| commit `snapshotId` vs 文件名 | `1f23bc6c-c5cc-4554-807d-dbbf5e73c2e0` 一致 ✓ |
| 信封头 | `PJE\x01`（加密信封，非明文）✓ |

即：提交正确绑定到快照，云端内容未损坏或被篡改。

### 11.3 真机状态（未完成，如实记录）

- **11 Ultra**：无线调试端口（37633/5555/40585/34457）全部主动拒绝，`<局域网地址>` 可 ping 通但 adb 服务未开启。需机主在设备上重新打开「无线调试」。
- **Xiaomi 14 Pro**（`<局域网地址>`，本轮新连接）：设备锁屏（`mDreamingLockscreen=true`），无法进行 UI 操作；仪器测试进程 DNS 解析失败（`EAI_NODATA`），而同机 shell `curl` 访问坚果云返回 207 —— 属 MIUI 对测试进程的网络限制。IP 直连因证书/SNI 校验被拒（`000`），无法绕过。

因此**真机侧上传复验未完成**。上述结论基于模拟器 + 真实坚果云账户 + 真实网络 + 完整 Compose UI 点击路径，与真机的差异仅在设备 ROM 层。

## 12. 真机上传卡点的逐层定位（2026-11-13 续四）

真机（Xiaomi 14 Pro，`<局域网地址>`）上 `JianguoUploadVerifyTest` 在 `runner.upload()` 处无返回。本轮用逐层日志把它定位到**网络层**，排除应用缺陷。

### 12.1 定位过程

在 `CloudBackupRunner` → `CloudBackupCoordinator` → `CloudBackupPipeline` 逐点埋点（日志仅在排查期存在，完成后已全部移除）：

| 阶段 | 真机结果 |
|---|---|
| `CloudConnectionRepository.probe` | ✅ `readable/writable/verified=true`、`canCreateDirectory=true`、`canDelete=false`、`cleanupPending=false` |
| `repository.save` | ✅ `saved=true folder=[]`（父目录留空） |
| `RecoveryKeySetup.confirm` + `RecoverySettings.save` | ✅ `slot confirmed` / `recovery slot saved` |
| `runner.upload()` → `uploadGate.enter()` | ✅ 立即通过 |
| `runtime.resources()` / `loadConnection` / 构造 coordinator | ✅ 全部通过 |
| `coordinator.run()` → `settings.load()`（恢复槽） | ✅ `slot ok` |
| `pipeline.run` → `writer.reserve()` | ✅ `reserved seq=1` |
| `withSnapshot` | ✅ 进入并执行 body（约 1 s） |
| `BackupManifest.encode` / `LocalBackupArchive.write` / `verify` | ✅ `self checked`（毫秒级） |
| **`uploader(...)`（DavBackupRemote PUT）** | ❌ **无后续日志，进程处于 S 态，`/proc/<pid>/net/tcp` 无到坚果云的 ESTABLISHED 连接** |

即：**读库、加密、自检、暂存全部成功，唯独 HTTPS 请求从未发出**。

### 12.2 进一步定位（第二轮，修正上一轮结论）

上一轮判为「网络被阻断」并不准确——后续更细的埋点证明 **GET 是通的**：

```
up: remote.prepare → 8.5 s → prepare done
up: verifiedCommitWriterIds → writers={}          ← GET 列表成功
up: CloudBackupUpload.upload
cu: objects=0
cu: prepare(2nd) → 4 s
cu: uploading snapshot
uv: verifyDownloaded first → 404 (GET 成功)
uv: 404 -> putFile                                ← 卡在这里
```

即：**读（GET/PROPFIND）全部成功，唯独 `PUT` 挂起**，且超过 OkHttp 的 `callTimeout(60s)` 仍不抛异常。

补充对照实验：

| 执行者 | 操作 | 结果 |
|---|---|---|
| 设备 shell（`shell` uid） | `curl -T` PUT 到坚果云 | **201 / 0.36 s** |
| 应用 uid（`run-as cn.yunji.journal curl`） | 同一条 PUT | **000 / 25 s 超时** |

设备网络策略检查：`restrict-background: disabled`、`RUN_ANY_IN_BACKGROUND: allow`、standby bucket=5（EXEMPTED）、已加入 Doze 白名单、`deviceidle` deep/light 均 `INACTIVE`、屏幕 `Awake`——均未显示限制。

`putFile` 挂起时进程内可见到 443 的连接处于 `FIN_WAIT1`。

### 12.3 判定

- 应用侧逻辑无缺陷：同一构建在**模拟器**上 20.7 s 完成并 `COMMIT` 成功；真机上 `probe` 的 `MKCOL`/`PUT`/`GET` 也全部成功（说明代码路径正确）。
- 真机特定环境下，**该应用 uid 的 HTTPS `PUT` 被挂起**（GET 正常），且不受已知 Doze/standby/后台策略开关影响；`shell` uid 的相同 PUT 正常。
- 结论：**属该 MIUI 版本对特定应用保活/网络的策略行为**，未能在本会话内定位到具体开关，也未能绕过。**不将其归因为应用缺陷。**

### 12.4 该轮真机取得的有效证据

尽管上传未完成，以下真机结论成立并已记录：

- 父目录留空的 `probe` 在真机通过（`writable/verified=true`、`cleanupPending=false`）——即 P0-1/P0-2 的修复在真机路径上被验证。
- 保存连接在真机成功（`saved=true folder=[]`）。

### 12.5 仍未完成

「真机 → 立即上传备份 → curl 比对 SHA-256」需设备解锁后重跑。解锁命令：

```bash
ADB_DEVICE=<局域网地址> DAV_USER=... DAV_PASS=... ./scripts/verify-device-upload.sh
```

## 13. 定向对照实验：排除 `isOneShot` 与路径因素（2026-11-13 续五）

上一轮怀疑「`putFile` 的 `isOneShot()=true` 导致 PUT 挂起」。本轮在**小米 14 Pro 真机**上做 5 组对照实验（新增 `DavPutBodyComparisonTest`，无需解锁，直接调用生产 `WebDavClient`）。

### 13.1 实验结果（全部真实坚果云）

| # | 场景 | 结果 |
|---|---|---|
| 1 | `put(bytes)`，2 段路径 | **OK 219–7545 ms** |
| 2 | `putFile()`（`isOneShot=true`），2 段路径 | **OK 512–523 ms** |
| 3 | `put(bytes)`，真实 4 段备份路径（`pregnancy-journal/<vaultId>/snapshots/x.bin`） | **OK 412–511 ms** |
| 4 | `putFile()`（`isOneShot=true`），同一 4 段路径 | **OK 509–609 ms** |
| 5 | 先发 20 次 404 GET（模拟 `verifiedCommitWriterIds`），再 `putFile()` | **OK 506 ms** |

**结论 1**：`isOneShot=true` **不是**原因——同一路径下 `put(bytes)` 与 `putFile()` 都成功。
**结论 2**：多级路径、`If-None-Match: *`、`Content-Type`、404 前置请求**都不是**原因。
**结论 3**：curl（shell uid）与 OkHttp（应用 uid）对同一路径的 PUT 均成功，**PUT 不被阻断**。

### 13.2 追加实验：进程运行时长

| # | 场景 | 结果 |
|---|---|---|
| 6 | 上述流程完成后 `Thread.sleep(35_000)`，再 `putFile()` | **挂起**（复现真实上传的现象） |

即：**同一进程内先正常运行十余秒 → 再经过约 35 秒静置 → 之后发出的 PUT 挂起**。真实 `JianguoUploadVerifyTest` 在 PUT 前已运行约 30 秒（probe + save + 恢复密钥 + 自检），与该模式吻合。

同时观察到 `OkHttpClient.callTimeout(60s)` 在挂起时**不生效**——说明进程被执行环境暂停，而非网络层超时。

### 13.3 判定

- 应用网络代码正确：6 组对照实验在真机上全部通过（含与真实备份完全相同的路径与 body 类型）。
- 挂起与**进程生命周期/静置时长**相关，与请求内容无关；`callTimeout` 不生效说明是进程级暂停。
- 该行为发生在设备锁屏（`deviceLocked=1`、`mWakefulness=Dozing`）且已加入 Doze 白名单、standby bucket=EXEMPTED、屏幕强制 Awake 的情况下。
- **不归因为应用缺陷**；在无人工解锁的条件下无法进一步区分是 MIUI 电源管理、Binder 冻结还是测试 instrumentation 会话被降级。

### 13.4 已排除的假设清单

| 假设 | 验证方式 | 结论 |
|---|---|---|
| `isOneShot=true` 导致挂起 | 同路径 `put` vs `putFile` 对照 | 排除 |
| 多级目录不存在 | 逐级 MKCOL 后重测 | 排除 |
| `If-None-Match` / `Content-Type` 头 | 头组合对照 | 排除 |
| 连接池被 404 污染 | 20×404 后立即 PUT | 排除 |
| DNS / 证书 / SNI | shell curl 与 OkHttp 均成功 | 排除 |
| `callTimeout` 未配置 | 已配置 60s，挂起时不触发 | 说明进程被暂停 |

### 13.5 诊断代码已清理

`DavPutBodyComparisonTest.kt` 已删除；`YJ_R` / `YJ_PF` 日志已全部移除（`grep` 无残留）。回归 **JVM 364/0**、lint **0 错误 / 15 警告**。

## 14. 心跳保活实验（2026-11-13 续六）

针对第 13 节发现的「静置 ~35 秒后 PUT 挂起」，验证是否可由应用侧心跳缓解。

### 14.1 实验设计

`DavIdleKeepaliveTest`：先用 5 次 404 GET 把 client 用热（贴近真实流程），随后静置 35 秒，**每 5 秒发一次 PROPFIND 心跳**，结束后立刻 `putFile`。

对照为第 13 节实验 #6（同样静置 35 秒但不发心跳 → 挂起）。

### 14.2 结果

**没能跑到心跳阶段就无输出**：测试进程在静置/心跳期间即被冻结，`logcat` 无任何 `DavKeepalive` 日志，进程仍存在但无进展。

### 14.3 结论

- 应用侧的周期性心跳**无法**阻止该冻结——冻结发生在进程调度层，不受应用级网络活动影响。
- 这进一步确认第 13 节的判定：不是网络、不是请求内容、不是连接管理，而是**设备在锁屏静置状态下暂停该测试进程**。

### 14.4 累计排除清单（真机实测）

| 假设 | 结论 |
|---|---|
| `isOneShot=true` 导致 PUT 挂起 | 排除（同路径 `put`/`putFile` 均成功） |
| 多级目录不存在（409） | 排除（逐级 MKCOL 后成功） |
| `If-None-Match` / `Content-Type` 头 | 排除 |
| 404 请求污染连接池 | 排除（20×404 后 PUT 506 ms 成功） |
| DNS / 证书 / SNI / IP 直连 | 排除（shell curl 与 OkHttp 均成功） |
| 应用 uid 被限制网络 | 排除（同进程内 PUT 多次成功） |
| 应用级心跳保活可缓解 | 排除（心跳阶段即被冻结） |
| `callTimeout(60s)` 保护 | 无效（冻结时定时器不推进） |

### 14.5 剩余唯一未验证路径

在设备**解锁并保持活动**的状态下执行 `JianguoUploadVerifyTest`。这是本会话无法自行达成的条件（锁屏凭据为机主私有）。

诊断产物已删除（`DavIdleKeepaliveTest.kt`），`grep` 确认无 `YJ_*` 日志残留。回归 **JVM 364/0**、lint **0 错误 / 15 警告**。

## 15. 云端测试残留清理与再次复验（2026-11-13 续七）

排查中发现云端积累了 **25 个 vault**，其中 23 个是本轮反复测试时每次新建隔离子库产生的，只有 2 个承载真实数据。

### 15.1 影响

`DavBackupRemote.verifiedCommitWriterIds` 会在每次上传前逐个认证所有已存在的 commit（用于多设备仲裁）。vault 越多，PUT 前置耗时越长——实测 18 个 vault 时为 **13 秒**（第 13 节时间线）。这既放大了「静置后冻结」的暴露窗口，也是对本轮测试对用户云端账户造成的实际污染。

### 15.2 清理

用 `PROPFIND` 逐级枚举并 `DELETE`，保留 2 个有真实数据的 vault（`b68f729e…`、`4449b60c…`），删除其余 23 个：

```
deleted=23 failed=0
```

清理后 `pregnancy-journal/` 下只剩 2 个 vault，`verifiedCommitWriterIds` 的扫描量降到 1/10。

### 15.3 清理后再次复验

- 前置耗时确实下降，但 `JianguoUploadVerifyTest` 在锁屏设备上**仍在 PUT 阶段无返回**。
- 追加确认：`am start -n cn.yunji.journal/.MainActivity` 后 `topResumedActivity` 仍为其他应用的 Activity —— 锁屏状态下应用无法成为前台 Activity。

### 15.4 结论

清理消除了「测试污染云端」这个副作用与一部分耗时放大因素，但**未改变**「锁屏静置后进程被暂停」这一根本阻塞。真机上传复验仍需解锁后执行。

### 15.5 需要机主操作

1. **解锁 Xiaomi 14 Pro**（`<局域网地址>`），或
2. **重新开启 11 Ultra 无线调试**并提供端口，或
3. **直接在手机上操作**：孕迹 → 我的 → 云盘连接 → 填坚果云凭据（父目录留空）→ 测试连接 → 保存连接 → 立即上传备份。完成后告知时间，用 curl 核对。

## 16. 真机上传成功的云端证据（2026-11-13 续八）

复查云端时发现：**真机上传实际上成功过**，只是测试进程被暂停导致没有回报结果。

### 16.1 证据

云端 `pregnancy-journal/cdb8c41a-e4ac-4276-a8bd-74b2159783f0/`（`getlastmodified` = **Thu, 12 Nov 2026 20:33:46 GMT**，即北京时间 **11-13 04:33**，对应一次真机 `JianguoUploadVerifyTest` 运行窗口）：

| 文件 | 大小 | 校验 |
|---|---|---|
| `vault.json` | 265 B | `vaultId` = 目录名 ✓ |
| `snapshots/eae2865d-….bin` | **512 B** | `cipherSize` 512 ✓ |
| `commits/eae2865d-….json` | 660 B | `snapshotId` = 文件名 ✓ |

**密码学校验**：

```
commit.cipherSha256 : 16af83a308e84157b17ee2b6b45cef3f7ccc6dc4e12de7495a3c74b92da3b0f5
实测 SHA-256        : 16af83a308e84157b17ee2b6b45cef3f7ccc6dc4e12de7495a3c74b92da3b0f5
一致                : True
信封头              : b'PJE'
```

即：**真机上完成了「保存连接 → 立即上传备份 → 云端 commit 绑定 snapshot」的完整链路**，且哈希与提交记录完全一致。

之所以此前判定为"未完成"，是因为该测试进程在 PUT 完成后被系统暂停，`am instrument` 没有返回 `OK`，而我没有去云端核对这次运行的实际产物。

### 16.2 补充观察

- 同期还有 2 个只创建了 `objects/` 与 `vault.json`、没有 `snapshots/`+`commits/` 的 vault（`2c66f1b1…`、`5db54072…`），对应「prepare 成功、PUT 前中断」的运行，已清理。
- 最新一次复跑（`7937216e…`）同样停在 `prepare` 之后。

### 16.3 结论修正

「真机上传从未成功」这一说法**不成立**——`cdb8c41a` 证明至少有一次完整成功且哈希校验通过。准确的表述是：

- 真机**上传链路可用**，且已产出通过 SHA-256 校验的云端提交；
- 部分运行会因设备锁屏静置导致测试进程被暂停，表现为 `am instrument` 无返回、`callTimeout` 不触发（第 14 节）；
- 该暂停不影响已经完成的 PUT/COMMIT，因此云端仍留下了完整产物。

### 16.4 云端清理

清理了仅含 `objects/vault.json` 的空 vault（`2c66f1b1…`、`5db54072…`）。当前保留 3 个：

- `b68f729e…`、`4449b60c…` — 更早的完整快照
- `cdb8c41a…` — **真机上传成功的证据**

## 17. 根因确认：HTTP/2 流级超时（2026-11-13 续九）

设备解锁后重跑，终于拿到了明确异常：

```
java.net.SocketTimeoutException: timeout
    at okhttp3.internal.http2.Http2Stream$StreamTimeout.newTimeoutException(Http2Stream.kt:675)
```

**根因是 HTTP/2**，不是设备网络策略、不是 `isOneShot`、不是 DNS。

这解释了此前所有异常现象：

| 现象 | 解释 |
|---|---|
| `curl`（HTTP/1.1）成功，应用（HTTP/2）挂起 | 协议不同 |
| `callTimeout(60s)` 不触发 | 超时来自 HTTP/2 流级计时器，不是 OkHttp call 级 |
| 有时成功有时失败 | HTTP/2 连接复用状态与对端流控不同 |
| 6 组对照实验全部成功 | 那些请求量小、未触发流级超时窗口 |

### 17.1 修复

`core/network/WebDavClient.kt`：

```kotlin
.protocols(listOf(okhttp3.Protocol.HTTP_1_1))
```

固定 HTTP/1.1，不依赖与坚果云的 HTTP/2 协商。

### 17.2 回归测试

新增 `WebDavClientTest.clientAlwaysNegotiatesHttp11`：用拦截器读取 `Response.protocol`，断言实际协商结果为 `HTTP_1_1`。

**有效性已验证**：临时移除 `.protocols(...)` 后该测试立即失败（`expected:<[http/1.1]> but was:<[h2]>`），恢复后通过。

### 17.3 真机/模拟器验证

| 环境 | 步骤 | 结果 |
|---|---|---|
| 真机 14 Pro（已解锁） | `JianguoRepeatedProbeTest`（父目录留空、连续两轮） | **`OK (1 test)`**，13.4 s |
| 模拟器 API36/16KiB | `JianguoUploadVerifyTest`（保存连接 → 上传） | **`OK (1 test)`**，20.7 s |
| 模拟器 API36/16KiB | 全量仪器矩阵（修复后） | **`OK (382 tests)` / 0 失败 / 0 跳过** |
| 全量 JVM | `testDebugUnitTest` | **365 项 / 0 失败**（59 类，含新增 HTTP/1.1 断言） |
| lint | `lintDebug` | **0 错误 / 15 警告** |

模拟器上传结果：

```
upload success=true  message=云端备份已提交并回读验证
lastSnapshotId=d7500198-4dc2-43ba-b894-5d0392aa5cce
lastPhase=COMMITTED  lastError=null
```

### 17.4 curl 独立核对

vault `3639dbe2-a6b3-462e-a1b2-e4e3cc762140`：

| 校验项 | 结果 |
|---|---|
| `vault.json` vaultId vs 目录名 | 一致 ✓ |
| `snapshotId` vs 文件名 | `d7500198-4dc2-43ba-b894-5d0392aa5cce` 一致 ✓ |
| `cipherSize` vs 实际 | 512 = 512 ✓ |
| `cipherSha256` vs 实测 SHA-256 | `fb0f21b57728ddc79a4f6139538979301ece80414150ea9c382eaa99e2d5b8f4` **完全一致** ✓ |
| 信封头 | `PJE` ✓ |

### 17.5 顺带修复的真机环境问题

重跑时还遇到 `javax.crypto.AEADBadTagException: BAD_DECRYPT`——设备上残留的 `cloud-probe.enc` / 隔离库加密数据与当前设备密钥不匹配。清理后消失。

这暴露一个可改进点：**设备密钥轮换后，旧的加密状态文件不会自动失效清理**，会以解密失败的形式暴露给用户。已记录为后续改进项（未在本轮修改）。
