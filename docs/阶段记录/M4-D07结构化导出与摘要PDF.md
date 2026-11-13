# M4 D07 结构化导出与就诊摘要 PDF

## 2026-10-22 系统保存器结构化导出往返增量

- 完成内容：新增正式 Main「我的 → 导出资料 → 明文确认 → Android DocumentsUI 保存 ZIP → 应用二次确认 → 生产导出完成」端到端仪器测试；校验持久交接清理、ZIP `manifest.json`/`fields.json` 回读，并删除测试文件。
- 产物：`app/src/androidTest/java/cn/yunji/journal/ExportSystemJourneyTest.kt`、[设备证据](../verification/2026-10-22-D07-system-journey/README.md)。
- 验证：专用 API36/16KiB 模拟器定向连续 4/4 通过；纳入全量后 **303 项执行、261 通过、42 前置条件跳过、0 失败**；下载目录最终为空。
- 排障：首版测试遗漏应用二次确认，随后高频轮询加密交接文件引入锁争用；已移除轮询并通过四次重复与全量回归。失败日志保留，先前 4 个零字节测试文件已清理。
- 遗留与下一步：摘要 PDF 正式 Main/系统保存器往返、真机/OEM、第三方分享接收方、数百 MiB/低内存等矩阵仍待验收；本项不代表 D07/M4 全部完成。

## 基本信息

- 状态：核心（数据包/字段字典/摘要组装分页/真实PDF/SAF与权限）完成并验证；界面入口与数据库接线未完成
- 开始/更新日期：2026-10-21
- 对应任务：D07（依赖 B05 资料与 C04 记录）
- 阅读入口：项目进度、V1.0实施文档7.3与12章D07行、D02记录。

## 范围与边界

结构化数据包（UTF-8 JSON、字段说明、原件、来源索引）与就诊摘要 PDF（范围、孕期、产检、用药、选定指标、生成时间、非临床标记），经 SAF 写入用户选择位置；不得把一屏截图拉伸为整份 PDF。界面向导、单份原件分享授权释放、无障碍/字体矩阵不在本轮。

## 完成清单

| 日期 | 任务 | 完成内容 | 产物 | 验证 |
|---|---|---|---|---|
| 2026-10-21 | D07 | 导出范围校验（真实日历日期、日期区间、区段选择）与日期/回收站过滤 | `core/export/ExportScope.kt` | 含于10项JVM |
| 2026-10-21 | D07 | 13段字段字典、内部字段标记与“不导出内容”清单 | `core/export/ExportFieldCatalog.kt` | 含于10项JVM |
| 2026-10-21 | D07 | 结构化包写入：清单最后写、JSON null 区分空串、只导出登记键、原件流式哈希、取消检查点 | `core/export/StructuredExportWriter.kt` | 10项JVM通过 |
| 2026-10-21 | D07 | 摘要组装（孕期/产检/用药/指标、来源标注、未填写）与分页（续页页眉、节标题不孤立、超长不截断） | `core/export/VisitSummary.kt`、`core/export/SummaryDataBuilder.kt` | 7项JVM通过 |
| 2026-10-21 | D07 | A4 多页中文 PDF 渲染（PdfDocument，按最长行高计算容量） | `data/SummaryPdfWriter.kt` | 3项设备测试通过 |
| 2026-10-21 | D07 | SAF 目的地：非空目标拒绝、提供方回读校验、不申请持久授权并释放残留授权 | `data/ExportSafWriter.kt` | 4项设备测试通过 |
| 2026-10-21 | D07 | 全量回归、lint、模拟器安装与证据归档 | `docs/verification/2026-10-21-D07-export/` | 222项JVM、183项API34通过；lint0错误/24警告 |

## 决策与变更

- 清单最后写入：计数、对象清单与包内文件列表只有在全部内容写完后才可知，先写会得到不完整或需要回填的清单。
- 只导出字段字典登记的键：即使实体新增列，导出也不会意外带出未评估的字段。
- 空值用 JSON null、展示用“未填写”，二者都不等同于空字符串，避免读者把“未记录”当成“记录为空”。
- 出版容量按最高行（节标题24pt）计算，宁可每页少几行也不让标题行溢出页边。
- 渲染器做二次换行保护：即使分页估算偏差，也不会把文字画到页边之外。
- SAF 导出不申请持久授权，写完后防御性释放；回读校验用流式计数而非 `skip(Long.MAX_VALUE)`，因为真实提供方会对其抛 `IOException`。

## 未完成项与风险

- 导出向导界面（日期范围/字段/原件/输出位置/明文确认）与真实数据库查询接线未做，用户目前无法从界面进入导出。
- 单份原件分享的 FileProvider 授权释放、分享后返回、授权被撤销后的恢复未验证。
- 数百MiB原件的低内存矩阵、系统字体200%/TalkBack/深色/旋转下的摘要排版未验证。
- D02–D06 既有未验收矩阵、E01/E02 继续保留。

## 继续工作的入口

- 最后完成：0.21.0-dev/versionCode22，222项JVM、183项API34、lint0错误/24警告；模拟器覆盖安装启动。
- 下一项：导出向导界面与数据库接线（补完D07），随后E01/E02；或回补云端快照下载恢复。
- 文件：`ExportScope.kt`、`ExportFieldCatalog.kt`、`StructuredExportWriter.kt`、`VisitSummary.kt`、`SummaryDataBuilder.kt`、`SummaryPdfWriter.kt`、`ExportSafWriter.kt`。
- 验证：界面向导、SAF 真实往返与授权释放、大包低内存、无障碍与字体矩阵。

## 阶段验收与交接

本轮 D07 核心通过。0.21.0-dev/versionCode22 在专用 API34 模拟器覆盖安装并启动；222项JVM与183项API34回归通过，lint0错误/24警告。手机未连接，未安装。证据见[verification/2026-10-21-D07-export/README.md](../verification/2026-10-21-D07-export/README.md)。**未接入界面，不宣称 D07 完整或 M4 验收。**

## 验证过程记录

- 字段字典初版用 `Map<String, Any>` 编码，kotlinx.serialization 无 `Any` 序列化器（7项失败，并连带掩盖取消测试的真实异常）；改为 `@Serializable FieldDictionary` 后通过。
- `ExportScope` 初版只用正则校验日期，`2026-13-01` 能通过；改为 `LocalDate.parse` 并回比字符串。
- `ExportSummary.originalsWritten` 初版写成 `counts.values.sum().let { originals.size }`（无意义表达式），改为实际写入数量。
- 摘要“空值区分”测试初版未考虑换行：15字值在10字宽下被拆行导致 `contains` 失败；改用足够宽的排版断言内容。
- SAF 测试初版调用 `ParcelFileDescriptor.open(uri.path)`（MediaStore 无文件系统路径）与 `input.skip(Long.MAX_VALUE)`，真实提供方抛 `IOException Invalid argument`；改为通过 resolver 回读并流式计数。
- 设备测试初版因应用 APK 未同步安装而报 `ClassNotFoundException`，重新构建并安装两个包后通过；不计入失败。

## 2026-10-21 导出向导接线复核与范围修复开始

当前源码已有ExportScreen/Controller/Runner/Service/Main接线，旧阶段记录的“未做”仅代表历史验收范围。本轮复核发现Service原件准备遍历全部附件、来源索引未遵循选中区段和回收站、健康测量未按本地记录日期过滤，可能导出范围外内容。先修复这些边界并加真实SQLCipher→SAF回归；尚未将现有接线认定完整验收。

## 2026-10-21 范围与交接保护验证

原件、来源索引、测量日期范围已修复；损坏或缺失的交接设置不再默认全量导出。222项JVM和24项API34隔离测试通过，构建及lint任务成功。现有界面/数据库接线已取得隔离证据，实际系统往返、生命周期和一致性矩阵未验收。证据：[范围验证](../verification/2026-10-21-D07-scope/README.md)。下一步审核并发快照一致性及导出生命周期。

## 2026-10-21 一致性修复开始

导出/预览/摘要的关联记录改为同一短事务读取，复用项目现有写入互斥入口，事务外准备原件及写入提供方，避免长期阻塞编辑。原件读取核对快照revision。新增真实数据库原子编辑交错测试；待构建验证。原件固定与回收站原件导出仍待补齐，不标记完整一致性验收。

### 一致性增量验证结果

短事务读取与原件revision检查已实现。222项JVM、25项API34通过，构建/lint成功；新增关联记录原子编辑交错验证。证据：[一致性增量](../verification/2026-10-21-D07-consistency/README.md)。下一步为仅选中对象的固定租约、回收站原件与撤销等待，再完成实际系统往返。未宣称完整一致性验收。

## 2026-10-21 选中原件租约实现开始

扩展ObjectProtection仅固定选中对象，与记录快照在同一事务捕获；导出读取固定元数据，支持显式选择的回收站原件，复用关闭等待并分块检查取消。新增回收站原件、替换保留及关闭等待测试，待验证。

## 2026-10-21 界面接线验证通过（0.22.0-dev）

上一段「待验证」的租约测试已实际运行：ExportServiceDeviceTest 共12项通过，含选中对象租约、替换后保留旧原件再回收、关闭时取消并等待清理、原子编辑交错、时区换算等。新增 ExportControllerTest 8项与 ExportUiTest 4项；完整回归207项API34通过（382.332秒）、222项JVM/0失败、lint0错误/24警告，最终包0.22.0-dev/versionCode23在模拟器覆盖安装启动。[证据](../verification/2026-10-21-D07-export-wiring/README.md)。

### 决策变更：回收站原件改为「只导出记录信息」

上一段计划“支持显式选择的回收站原件”。实测发现导入器的原件读取路径会拒绝已删除附件（回收站待清理），若要按原计划导出就必须放宽这条既有安全守卫。为过测试而放宽生产守卫不可接受，故改为：勾选“包含回收站记录”时导出其**记录信息**（含对象引用），不导出其明文原件，并在结果里明确写出“回收站原件 N 份未包含（回收站只导出记录信息）”，不静默少给。相应测试重写为断言该策略，而非断言原计划的导出行为。

### 本轮修复

- 追加的测试文件丢失三处换行（`}    @Test`／`}    private suspend fun`），编译失败；补齐后编译通过。
- 新增“导出资料”入口使“我的”页内容变长，把“用药方案”行推到可视区外，导致两项既有用药导航测试点击落空；按仓库既有约定改为先 `performScrollTo()` 再点击，两项通过。
- 多次出现应用/测试 APK 不同步（`ClassNotFoundException`、`NoSuchMethodError: withSelected`），均重新构建并安装两个包后通过；不计入通过。
- 原件缺失用例的夹具使用非 UUID 的 `a1`，实际先被 `store.path()` 以“对象编号不正确”拒绝，未测到目标行为；改为 UUID 附件标识后才精确验证“原件缺失必须整体失败”。

### 仍未验收

单份原件分享的 FileProvider 授权释放与分享后返回、授权被撤销后的恢复、数百MiB原件低内存矩阵、字体200%/TalkBack/深色/旋转下的摘要排版、真机安装与系统往返；D02–D06 既有矩阵与 E01/E02 继续保留。导出链路只在模拟器以合成数据验证，**不宣称 D07 或 M4 完整验收**。


## 2026-10-22 选中原件租约复核

修正回收站元数据导出时错误固定已删除原件的问题，保持既定‘只导出回收站记录信息’策略；修复测试文件末尾格式错误。0.25.0-dev源码构建/lint成功，235项JVM与28项API34导出专项通过。[证据](../verification/2026-10-22-D07-selected-pins/README.md)。未执行完整设备矩阵或真机系统往返，不改变D07/M4总体未验收状态。

## 2026-10-22 单份原件分享实现（FileProvider 临时授权与最小暴露）

内容：补齐 D07 遗留的「单份原件分享」生产实现。新增独立 `${applicationId}.share` FileProvider（`exported=false`、`grantUriPermissions=true`，paths 仅暴露 `filesDir/share`），新增 `core/share/` 4 个纯逻辑单元（策略/临时目录/释放状态机/编排）与确认对话框；资料详情行新增「分享原件」：确认后先 `importer.exportOriginalTo` 把该份原件解密到私有分享目录，再生成 content URI，经 `Intent.createChooser` + 单个 `ACTION_SEND` + 仅 `FLAG_GRANT_READ_URI_PERMISSION` 发出；用户返回（`ON_RESUME`）时先 `revokeUriPermission` 再删除临时文件，页面进入时清理残留，失败/越界路径 `discard` 不留明文。不改既有 capture provider、私密库、UI 矩阵 5 个文件。

产物：
- 生产：`app/src/main/java/cn/yunji/journal/core/share/{ShareOriginalPolicy,ShareTemporaryFiles,ShareRelease,OriginalSharer,ShareFileProvider,OriginalShareDialog}.kt`、`app/src/main/java/cn/yunji/journal/feature/DocumentScreen.kt`、`app/src/main/AndroidManifest.xml`（新增 provider）、`app/src/main/res/xml/share_paths.xml`。
- 测试：`app/src/test/java/cn/yunji/journal/{ShareOriginalPolicy,ShareTemporaryFiles,ShareRelease,ShareWiring}Test.kt`（26 项）；`app/src/androidTest/java/cn/yunji/journal/OriginalShareDeviceTest.kt`（7 项；设备端已于 2026-10-22 执行通过，见本文件末章节）。

验证证据：`.\gradlew.bat :app:testDebugUnitTest` → 49 类/292 项/0 失败/0 跳过（基线 45 类/266 项，+4 类/+26 项）；定向 4 类/26 项/0 失败；`compileDebugAndroidTestKotlin` 通过；`lintDebug` 0 errors/35 warnings 与基线一致。原始日志、JUnit XML、lint 报告见 [validate](../verification/2026-10-22-D07-share/README.md)。

遗留：设备定向 androidTest（provider 注册与不可导出、仅 share 目录可达、intent 结构与 flags、返回后 revoke 先于 delete、残留清理）当时尚未执行，等模拟器 emulator-5556 空闲后跑；第三方分享目标真实往返、进程中途被杀、真机/OEM、API26、数百 MiB 原件未覆盖。`docs/剩余任务验收清单.md` D07 行与 `docs/已知限制.md:27` 待设备结果后由 Lead 回填，不提前标记完成。（注：设备定向已于 2026-10-22 执行通过，7/7 且修复 1 个内层 intent flags 缺陷，见本文件末「2026-10-22 设备定向验证」章节。）

下一步：等 Lead 通知设备空闲 → 跑 `OriginalShareDeviceTest` 并回填本节与证据 README；随后清理分享目录并确认无残留授权。

## 2026-10-22 设备定向验证（补充上文「遗留」）

执行：`$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'; .\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.yunji.journal.OriginalShareDeviceTest --console=plain`（emulator-5556，API36 16KiB；Lead 释放设备后串行执行；前置校验设备基线 font_scale=1.0、浅色、竖屏 1080x2400，无 Gradle worker 占用）。

**结果**：修复前 BUILD FAILED 13.5s，7 项 1 失败（`shareIntentCarriesExactlyOneReadOnlyStream`，`AssertionError: expected:<1> but was:<0>`）。根因：`ShareInvocation.toIntent()` 中 `Intent(action).apply { ...; addFlags(flags) }` 的 `flags` 被内层 `Intent` 接收者遮蔽为 `Intent.flags`(0)，导致 `EXTRA_STREAM` 内层 stream intent 缺 `FLAG_GRANT_READ_URI_PERMISSION`——chooser 外层有只读 flag 但接收方拿不到读授权；JVM 无法实例化 android `Intent`，既有 JVM 用例只覆盖 `ShareInvocation.flags` 与 chooser 外层，因此漏测。修复：`addFlags(this@toIntent.flags)` 并显式 `this.flags`（`core/share/OriginalSharer.kt`，1 行语义修复，Lead 已批准）。修复后重跑 BUILD SUCCESSFUL 26.6s，**7 项 / 0 失败 / 0 跳过**（XML 测试耗时 0.964s）；跑后 `am force-stop cn.yunji.journal` 退出码 0；设备跑前/跑后配置一致。

**产物**：`OriginalShareDeviceTest.kt`（7 项已执行通过）、`core/share/OriginalSharer.kt`（缺陷修复）、设备证据 `device-targeted.log`、`device-targeted-pre-fix.log`（修复前失败摘录，原日志被重跑覆盖）、`junit/device-OriginalShareDeviceTest.xml`、`junit/device-report-index.html`、`device-info.txt`、`device-info-post.txt`（均在 [verification/2026-10-22-D07-share](../verification/2026-10-22-D07-share/README.md)）。

**遗留（仍未覆盖）**：跨应用 revoke 真实可见性（仪器测试同 UID）、真实第三方分享目标往返、接收方转发明文、真机/OEM、API26、数百 MiB 与低内存、分享中进程被杀。`docs/剩余任务验收清单.md` D07 行与 `docs/已知限制.md:27` 由 Lead 回填；D07 代码/测试层验收完成。

## 2026-10-23 摘要 PDF 系统往返增量

状态：D07 阶段继续进行中。正式 Main →「我的 → 导出资料」→ Android DocumentsUI → 就诊摘要 PDF 的系统往返增加 API36/16KiB 专用设备测试；合成孕期仅用于产生非空摘要，测试在结束时精确删除并断言不存在，保存的 PDF 也通过原 URI 删除。产物：[ExportSystemJourneyTest.kt](../../app/src/androidTest/java/cn/yunji/journal/ExportSystemJourneyTest.kt) 与 [验证记录](../verification/2026-10-23-D07-pdf-system-journey/README.md)。

验证：2026-10-23，`assembleDebugAndroidTest` 成功；ZIP/PDF 系统旅程 2/2 通过，PDF 文件头与 `PdfRenderer` 页数通过，模拟器 `/sdcard/Download` 为 `total 0`。首轮全量 API36 回归在 `ProcessRecoveryTest` 后因模拟器进程退出而中断，无最终报告；冷启动重试中 ZIP/PDF 再次通过，但导航类 5 项界面失败，随后系统记录 `ANR in cn.yunji.journal` / `No response to onStartJob`，仪器进程以 `Process crashed` 结束。两轮均不计全量通过，导航类隔离复核进行中。初版测试误等 ZIP 文案且未关闭“正在生成”提示，已改正；生产代码未变。

遗留：真机/OEM、低内存与大文件、第三方分享接收/授权撤销可见性及前述矩阵继续保留。下一步读取全量回归结果并更新 E01/D07 汇总，再推进其余未验收项。

### 2026-10-23 全量中断后隔离复核

API36 全量重试中的导航类 5 项失败，随后应用后台任务 ANR 与仪器进程崩溃；直接隔离复跑同样因模拟器状态异常崩溃，均保留失败/中断日志。重启系统后封面用例诊断确认 Snackbar 覆盖底部「关联产检」真实点击位置；测试改为通过 Snackbar 自带 `Dismiss` 语义动作关闭提示，再执行真实点击。单项 1/1、整类 23/23 通过，生产代码未改。最终第三轮完整 API36/16KiB 回归 **305 项、0 失败，`OK (305 tests)`，584.206 秒**；系统文件清理后 `/sdcard/Download` 为 `total 0`。证据：[本轮验证记录](../verification/2026-10-23-D07-pdf-system-journey/README.md)。其余四项在完整重跑中通过，先前慢速/ANR 条件尚未独立定性；D07 真机/OEM、低内存和第三方分享矩阵仍保留。
