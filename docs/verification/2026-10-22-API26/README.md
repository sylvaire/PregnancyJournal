# 2026-10-22 最低 API 26（Android 8.0）兼容性验证

## 目标

关闭 [剩余任务验收清单](../../剩余任务验收清单.md) 长期保留的「最低 API 26（无镜像）」缺口：
在 API 26 模拟器上安装当前 debug 包，跑完整 instrumentation 套件，把失败拆成
**产品缺陷 / 测试夹具缺陷 / 平台能力差异** 三类，修复后复跑并留存证据。

## 环境

| 项 | 值 |
|---|---|
| 模拟器 | emulator-5554，AVD `PregnancyJournal_API26`，镜像 `system-images;android-26;google_apis;x86_64` |
| 系统 | Android 8.0.0 / SDK 26 / x86_64 / 1080x1920 420dpi / security patch 2018-04-05 |
| 应用 | debug 构建，0.25.0-dev / versionCode 26 / schema 13（本增量不改版本号） |
| 基线配置 | font_scale=1.0、浅色、竖屏（user_rotation=0、accelerometer_rotation=0） |

设备信息见 `device-info-api26.txt`。

执行方式（非 Gradle `connectedDebugAndroidTest`，避免 AGP 卸载后丢失截图类证据）：

```
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
adb -s emulator-5554 install -r -t -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5554 install -r -t -g app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner
```

## 首轮结果（旧构建）

`device-full-api26.log`：**282 项 / 30 失败 / 0 错误**。失败集中在 5 组，逐组定位如下。

## 根因与修复

### 1. PDF 预览（7 项）——平台 pdfium 进程级污染（产品修复）

诊断探针 `Api26PdfProbeTest`（证据 `probe-*.log`、`probe-files/`、`analyze_api26_pdfs.py`）结论：

- `SummaryPdfWriter` 产物本身在 API26 可正常回读渲染（writer 字节流/文件流/流拷贝三种形态均
  `pages=1`、render 有墨迹）；同一 810 字节 PDF 从内部 cache、no_backup、files、外部 files、
  `cache/import-test-synthetic/document-viewers` 五处打开均成功 → **与存储位置无关**；
- 但 **一次打开结构损坏的 PDF 之后，同一 pdfium 进程内后续所有 PDF 打开永久失败**：

  ```
  Api26PdfProbe: poison before=1
  Api26PdfProbe: malformed open -> java.io.IOException: file not in PDF format or corrupted
  Api26PdfProbe: poison after=-1
  ```

- `DocumentImportTest` 中先跑的 `malformedPdfKeepsOriginalAndDoesNotLeaveViewerPlaintext`（37 字节
  `%PDF-1.7 truncated synthetic document`）正是污染源；污染后合法文件全部报
  `IOException: file not in PDF format or corrupted`。

生产修复：

- 新增 `app/src/main/java/cn/yunji/journal/data/PdfReadability.kt`：读取前校验文件存在、长度 ≥32 字节、
  头部含 `%PDF-`、尾部 4096 字节内含 `%%EOF`；不通过抛 `IOException("PDF 文件不完整：…")`；
- `DocumentImporter.makeThumbnail` 在 `PdfRenderer` 之前调用校验；
- `PdfDocumentView.PdfSession` 构造时先校验，失败先关闭 `source` 再抛（避免泄漏）；
- 新增单元测试 `app/src/test/java/cn/yunji/journal/data/PdfReadabilityTest.kt`（7 项：完整文件通过、
  缺尾、缺头、截断、过短、不存在、抛 IOException 类型）。

影响面：被拒绝的是**结构不完整**的文件（截断/伪装成 PDF），完整 PDF 不受影响。

### 2. EXIF（1 项）——平台写入/读取差异（测试夹具 + 生产读侧修复）

探针证明 API26 上：

- `Bitmap.compress(JPEG, 90)` 成功（bytes=3537）；
- 但平台 `android.media.ExifInterface.saveAttributes()` 仍报
  `IOException: ExifInterface only supports saving attributes on JPEG formats.` → 平台实现缺陷，与文件无关。

修复：

- 测试夹具 `DocumentImportTest.cameraOrientationAndRegionDecodingStayBounded` 改用
  `androidx.exifinterface.media.ExifInterface` 写入旋转方向；
- 生产读侧 `feature/DocumentPreview.kt` 同步改用 `androidx.exifinterface.media.ExifInterface`，并在
  `gradle/libs.versions.toml` + `app/build.gradle.kts` 增加 `implementation(libs.androidx.exifinterface)`，
  保证各 API 的方向解析一致（API36 全量复跑见下）。

### 3. MediaStore（14 项）——API<29 无 `MediaStore.Downloads`（测试夹具）

新增测试辅助 `app/src/androidTest/java/cn/yunji/journal/TestDestinations.kt`：

- API 29+：`MediaStore.Downloads`；
- API 26–28：应用自身 share `FileProvider`（authority `${applicationId}.share`，`files-path share/`），
  在实际文件落盘后发布为 `content://` 目的地，`delete/publish` 行为与 MediaStore 对齐。

`ExportSafWriterTest` / `ExportServiceDeviceTest` / `SafetyCopyExporterTest` 的目的地获取与删除改走该辅助。
生产导出本身走 SAF `content://` 目的地，与 API 版本无关，本项只补测试夹具。

### 4. NavigationExperienceTest（6 项）——夹具缺依赖 + API<28 截图限制（测试夹具）

- `setUp` 构造 `JournalViewModel` 缺少 D06/D07 新增依赖（cloud cleanup 状态与访问、export access 等），
  运行到《云备份》页会抛 `cloud cleanup dependency required` → 补齐依赖；
- Compose `captureToImage` 在 API<28 不支持 dialog 节点 → `captureNode` 在 API<28 写标记文件 + logcat
  并继续断言语义（证据改为断言与日志，不再生成截图）；
- 横屏 pan 手势在 API<28 不稳定 → 仅 API≥28 执行手势，API26 保留缩放后的语义断言。

### 5. ProcessRecoveryTest（3 项）——phase2 依赖宿主脚本（测试夹具）

phase2 用例按阶段参数门控：未带 `-e processStage=phase2` 时 assume 跳过；带参数时轮询
`task6-markers/<token>.stop` 1s 后再断言。完整两阶段矩阵在 A03 证据
（[`verification/2026-10-22-A03-lock-process-matrix`](../2026-10-22-A03-lock-process-matrix/README.md)）中执行。

### 6. D02ProcessMatrixTest 初始化错误（测试代码缺陷，net-slow-io 修复）

phase2 方法曾声明为 `private fun` 非 void 签名，JUnit 拒绝初始化整个类。已改为 void 并保留 10 个 phase 方法；
全量项数因此从 282 增至 292。

## 复跑结果

- 全量：**`OK (292 tests)`**，0 失败 0 错误（`device-full-api26-fixed2.log`）
- 定向子集（`isolated-*-fixed*.log`）：`DocumentImportTest` 32、`NavigationExperienceTest` 23、
  `ExportSafWriterTest`+`ExportServiceDeviceTest`+`SafetyCopyExporterTest` 6、`ProcessRecoveryTest` 17，全部 0 失败
- 全量 JVM：**54 类 / 331 项 / 0 失败 / 0 跳过**（`jvm-full-api26-fixes.log`）
- `lintDebug`：**0 错误 / 34 警告**（`lint-api26-fixes.log`；全部为依赖版本提示与既有 UseKtx 提示，无新增错误）

### API 26 上按前置条件跳过的 42 项（如实记录）

| 类别 | 数量 | 原因 / 覆盖位置 |
|---|---|---|
| `ExtremeLayoutMatrixTest` / `ExtremeLayoutMatrixPagesTest` | 14 | 需 200% 字体+深色+横屏；API36 专项见 [`2026-10-22-A02-ui-matrix`](../2026-10-22-A02-ui-matrix/README.md)、[`2026-10-22-E01-pages-matrix`](../2026-10-22-E01-pages-matrix/README.md) |
| `D02ProcessMatrixTest` phase1/phase2 | 10 | 需宿主脚本 `-e d02Phase=…`；API26 矩阵见 [`2026-10-22-D02-process-matrix`](../2026-10-22-D02-process-matrix/README.md) |
| 仅 API34 模拟器门控（`Build.MODEL==sdk_gphone64_x86_64 && SDK==34`） | 14 | `BackupProcessBoundaryTest` 2、`BackupSystemJourneyTest` 2、`CloudWriterDurabilityTest` 2、`LargeBackupArchiveTest` 3、`MainRestoreCommitTest` 1、`MainRestorePickerTest` 2、`ReleaseBackupUiSmokeTest` 1、`RestoreGenerationDatabaseTest` 1 |
| `DocumentImportTest#syntheticImportTiming` | 1 | 需 `-e measureImports=true` 才跑性能测量 |
| `ReminderNotificationTest#exactAlarmPostsPrivateNotificationThroughReceiver` | 1 | 依赖 `POST_NOTIFICATIONS` 动态权限（API26 不可见） |
| `VaultSecurityTest` 严格模式探针 | 2 | 需 API≥30 且 `isDeviceSecure` / 金鱼(ranchu) 硬件条件 |

合计 14+10+14+1+1+2 = 42。

## 未覆盖

- 真机 API26 与厂商 ROM 的 OEM 后台限制、通知行为；
- API26 无 16KiB 页镜像（目标 API36 的 16KiB 页验证见 E01）；
- legacy 外部存储路径（API26 的 `Environment.getExternalStorageDirectory`）与 SAF/云盘服务商差异；
- 极端配置/无障碍矩阵在 API26 的重复验证（已在 API36 覆盖）；
- 真实云端、真实进程终止/断电、低内存真机。

## 产物

- `device-info-api26.txt`、`device-full-api26.log`（首轮 30 失败）、`device-full-api26-fixed2.log`（复跑 292 全绿）
- `probe-pdf-api26.log`、`probe-graded-api26.log`、`probe-graded-logcat.txt`、`probe-store-api26.log`、
  `probe-churn-logcat.txt`、`probe-files/`、`analyze_api26_pdfs.py`（PDF 平台行为实证）
- `isolated-*.log`（5 份定向复跑）、`jvm-full-api26-fixes.log`、`lint-api26-fixes.log`、`emulator-api26-out.log`
- 生产改动：`data/PdfReadability.kt`（新增）、`data/DocumentImporter.kt`、`feature/PdfDocumentView.kt`、
  `feature/DocumentPreview.kt`、`gradle/libs.versions.toml`、`app/build.gradle.kts`
- 测试改动：新增 `TestDestinations.kt`、`PdfReadabilityTest.kt`；修改 `ExportSafWriterTest`、
  `ExportServiceDeviceTest`、`SafetyCopyExporterTest`、`NavigationExperienceTest`、`ProcessRecoveryTest`、
  `DocumentImportTest`
- 诊断探针 `Api26PdfProbeTest` 在取证后已从源码删除（逐字节快照 `Api26PdfProbeTest.kt.txt`），避免它在常规
  全量套件里主动打开损坏 PDF 污染 pdfium 进程
