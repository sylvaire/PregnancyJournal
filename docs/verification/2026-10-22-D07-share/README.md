# D07 单份原件分享：FileProvider 临时授权与最小暴露

- 日期：2026-10-22
- 版本：0.26.0-dev（未改版本号，仅工作区变更）
- 任务：共享任务 `task-4`（D07 单份原件分享；D07 行遗留「FileProvider 授权释放、分享后返回、授权被撤销后的恢复」）
- 状态：**生产实现 + JVM 26 项通过 + 设备定向 7/7 通过（修复 1 个真缺陷后，2026-10-22 15:00–15:03 emulator-5556）**；D07 单份原件分享的设备层验收完成

## 背景与目标

`docs/V1.0详细实施文档.md:252` 要求：单份原件分享使用临时 `FileProvider` URI、最小读取授权、限制授权文件范围。
`docs/剩余任务验收清单.md` D07 行与 `docs/已知限制.md:27` 此前记录的是「未实现/未验证」，本轮补齐实现与 JVM 层验证。

安全口径（来自需求与既有 D07 文案）：

1. 只允许分享**单份已解密到应用私有目录的原件**，不暴露私密库、缓存、整个 `filesDir`；
2. 独立 provider：authority `${applicationId}.share`、`android:exported=false`、`grantUriPermissions=true`，paths XML 只暴露 `filesDir/share` 一个临时目录；
3. 分享用 `Intent.createChooser` + 单个 `ACTION_SEND` + 仅 `FLAG_GRANT_READ_URI_PERMISSION`（无写、无持久授权）；
4. 分享返回（`ON_RESUME`）先 `revokeUriPermission` 再删除明文临时文件；应用启动清理残留；失败路径同样删干净、不落公共目录；
5. 确认文案沿用 D07 既有「导出即明文」提示。

## 生产改动

| 文件 | 行数 | 作用 |
| --- | --- | --- |
| `app/src/main/java/cn/yunji/journal/core/share/ShareOriginalPolicy.kt` | 77 | 纯策略：authority 校验、只读 flags、MIME 归一化、临时文件命名与安全基名、`ShareInvocation` 数据类；不依赖 Android SDK 之外的环境，可在 JVM 直接测 |
| `app/src/main/java/cn/yunji/journal/core/share/ShareTemporaryFiles.kt` | 63 | `filesDir/share` 目录管理：名字校验（禁 `/`、`\`、`..`、隐藏名、控制字符）、`contains/delete` 限定目录内、`clearAll`、`clearStale(now)`、`prepare(name)` 删除同名上一份 |
| `app/src/main/java/cn/yunji/journal/core/share/ShareRelease.kt` | 36 | 释放状态机：`revoke` → `delete` 顺序固定；同一 URI 重复释放幂等；revoke/delete 失败都记入 `ShareReleaseResult`，不抛出掩盖 |
| `app/src/main/java/cn/yunji/journal/core/share/OriginalSharer.kt` | 85 | 编排：`prepare(displayName, mime)` 建临时文件、`publish(file, mime)` 经 `FileProvider.getUriForFile` 生成 URI、`launch(invocation)` 走可注入 `ShareLauncher`（生产 `ContextShareLauncher` 包 `startActivity` 并捕获 `ActivityNotFoundException/SecurityException`）、`releaseAfterReturn()`、`discard(file)`、`clearResidual()`；`toIntent()` 写入单一 `EXTRA_STREAM`、`ClipData.newRawUri` 同一 URI、只读 flags，`chooser()` 用 `Intent.createChooser` |
| `app/src/main/java/cn/yunji/journal/core/share/ShareFileProvider.kt` | 20 | 独立 `FileProvider` 子类（用途单一，防止意外共享 capture provider 的 paths） |
| `app/src/main/java/cn/yunji/journal/core/share/OriginalShareDialog.kt` | 45 | 确认对话框：标题「分享这份资料的原件？」、明示「离开本应用后可能被其他应用读取原文件」、忙碌态不可重复点击、失败显示错误 |
| `app/src/main/AndroidManifest.xml` | +5 | 新增 `${applicationId}.share` provider：`exported=false`、`grantUriPermissions=true`、`meta-data @xml/share_paths`；原 capture provider 未改 |
| `app/src/main/res/xml/share_paths.xml` | 5 | 仅 `<files-path name="share" path="share/" />`，无 cache/外部/整个 filesDir |
| `app/src/main/java/cn/yunji/journal/feature/DocumentScreen.kt` | 356（含改动） | 每个资料行新增「分享原件」按钮（`testTag=share-<attachmentId>`）；确认后 `importer.exportOriginalTo(id, output, revision)` 只解密到私有分享目录，再 `publish` + `launch`；失败 `discard` 并提示；`ON_RESUME` 调 `releaseAfterReturn()` 撤销并删除；进入页面时清理残留 |

隔离性：`FileProvider.getUriForFile` 只接受 `share_paths.xml` 内的文件；`OriginalSharer.publish` 在生成 URI 前还会校验 `temporary.contains(file)` 与 authority，防止越界对象被发布。

## 测试命令与结果

以下命令均在 `D:\Devtools\Code\CodexWorkspace\PregnancyJournal` 执行，每次只跑一条 Gradle 命令（构建互斥与 ui-matrix 共享）：

| # | 命令原文 | 结果 |
| --- | --- | --- |
| 1 | `$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'; .\gradlew.bat :app:testDebugUnitTest --tests "cn.yunji.journal.ShareOriginalPolicyTest" --tests "cn.yunji.journal.ShareReleaseTest" --tests "cn.yunji.journal.ShareTemporaryFilesTest" --tests "cn.yunji.journal.ShareWiringTest" --console=plain` | BUILD SUCCESSFUL，2s；4 类 / 26 项 / 0 失败（junit/ 下 4 个 XML） |
| 2 | `.\gradlew.bat :app:testDebugUnitTest --console=plain` | BUILD SUCCESSFUL，13s；**49 类 / 292 项 / 0 失败 / 0 跳过**（基线 45 类/266 项，+4 类 +26 项），XML 见 `junit-full/` |
| 3 | `.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain` | BUILD SUCCESSFUL，2s（首次失败为缺少 `chooser` 扩展导入，已修；随后把 `getParcelableExtra` 弃用调用改为 `IntentCompat.getParcelableExtra`） |
| 4 | `.\gradlew.bat :app:lintDebug --console=plain` | BUILD SUCCESSFUL，46s；**0 errors / 35 warnings**，与 task-3 基线完全一致（NewerVersionAvailable=13、GradleDependency=9、UseKtx=9、AndroidGradlePluginVersion=2、ExifInterface=2）；首轮曾出现 `UseKtx` 警告（`Uri.parse`），改用 `String.toUri()` 后消除 |
| 5 | `.\gradlew.bat :app:testDebugUnitTest --console=plain`（修复后最终复核） | BUILD SUCCESSFUL，15.6s；**49 类 / 292 项 / 0 失败 / 0 跳过**（与修复前一致，确认 1 行修复无回归），日志 `jvm-full-final.log`、XML `junit-full/` |
| 6 | `.\gradlew.bat :app:lintDebug --console=plain`（修复后最终复核） | BUILD SUCCESSFUL，62.1s；**0 errors / 35 warnings**（分布不变：NewerVersionAvailable=13、GradleDependency=9、UseKtx=9、AndroidGradlePluginVersion=2、ExifInterface=2），`lint-final.log` / `lint-results-debug.xml` |

## JVM 用例（26 项，全部通过）

`ShareOriginalPolicyTest`（10）
- shareFlagsAreReadOnly / shareFlagsCarryNoWriteOrPersistableBits / shareGrantNeedsTheReadBit
- invocationSendsExactlyOneContentStream / invocationRejectsNonContentAndUnsafeUris
- shareTypeKeepsTheConcreteMimeAndDropsParameters
- shareAuthorityIsTheApplicationIdPlusTheDedicatedSuffix / shareAuthorityRejectsMalformedApplicationIds
- safeBaseNameStripsTraversalSeparatorsAndControlCharacters
- temporaryNameIsStableSafeAndUniquePerToken

`ShareTemporaryFilesTest`（8）
- prepareCreatesNamesOnlyInsideTheShareDirectory / prepareRejectsTraversalSeparatorsAndHiddenNames
- containsAndDeleteRejectFilesOutsideTheShareDirectory
- directoryNameMustBeTheDedicatedShareDirectory
- prepareClearsThePreviousStagedOriginal
- clearAllRemovesResidueAndKeepsTheDirectory
- clearStaleRemovesOldFilesAndKeepsFreshOnes / clearStaleRemovesEntriesWithUnknownTimestamps

`ShareReleaseTest`（6）
- releaseRevokesBeforeDeleting（顺序断言：revoke 回调先于 delete）
- secondReleaseIsANoOpForTheSameUri（幂等）
- revokeFailureStillDeletesAndIsReported / deleteFailureKeepsTheRevokedGrantReported
- releaseWithoutUriStillDeletesResidue / failuresWithoutMessageGetReadableFallbacks

`ShareWiringTest`（2，读取真实 `res/xml/share_paths.xml` 与 `AndroidManifest.xml` 文本断言，防回归）
- pathsXmlExposesOnlyTheTemporaryShareDirectory
- manifestDeclaresTheShareProviderNonExportedWithReadGrantsOnly

## 设备定向用例（`OriginalShareDeviceTest`，7 项，2026-10-22 已执行：7/7 通过）

1. `providerIsRegisteredNonExportedWithGrantOnly`：authority=`${packageName}.share`、`exported=false`、`grantUriPermissions=true`、无读写 permission。
2. `onlyTheShareDirectoryIsReachableThroughTheProvider`：`filesDir/outside-original.bin`、`noBackupFilesDir/vault/gen/active/journal.db`、`cacheDir/capture/stolen.jpg` 经 provider 生成 URI 必须抛 `IllegalArgumentException`。
3. `shareIntentCarriesExactlyOneReadOnlyStream`：ACTION_SEND、type、仅读 flag、无写/持久位、URI 前缀 `.share/`、chooser 内 EXTRA_INTENT 结构正确、provider 可读该 URI、`publish` 不自行启动。
4. `launchPassesExactlyThePublishedInvocation`：`launch` 只把已发布 invocation 交给注入的 `ShareLauncher`。
5. `returningRevokesThenDeletesTheTemporaryCopy`：`releaseAfterReturn` 返回 `revoked=true/deleted=1`，临时文件消失，原 URI 不可再读。
6. `residualPlaintextIsClearedWhenTheScreenStarts`：`clearResidual` 清空残留。
7. `shareNamesNeverEscapeTheShareDirectory`：`../../vault.bin` 等名字被安全化，仍落在 share 目录。

### 设备执行结果（2026-10-22 15:00–15:03，emulator-5556 / API36 / 16KiB）

前置校验：设备已恢复基线（`font_scale=1.0`、浅色、竖屏 1080x2400），`adb devices` 仅 emulator-5556=device，无 `connectedDebugAndroidTest`/Gradle worker 占用；设备信息见 `device-info.txt`。

| # | 命令原文 | 结果 |
| --- | --- | --- |
| 1（修复前） | `$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'; .\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.yunji.journal.OriginalShareDeviceTest --console=plain` | BUILD FAILED，13.5s；7 项 **1 失败**（`shareIntentCarriesExactlyOneReadOnlyStream`，`AssertionError: expected:<1> but was:<0>`），见 `device-targeted-pre-fix.log` |
| 2（修复后重跑） | 同上 | BUILD SUCCESSFUL，26.6s；**7 项 / 0 失败 / 0 跳过**，XML 测试耗时 0.964s；跑后 `am force-stop cn.yunji.journal` 退出码 0 |

**设备中发现并修复的真实缺陷（1 行语义修复）**：`ShareInvocation.toIntent()` 中 `Intent(action).apply { ...; addFlags(flags) }` 的 `flags` 被内层 `Intent` 接收者遮蔽，读到的是 `Intent.flags`（0），导致 `EXTRA_STREAM` 的内层 stream intent 实际缺少 `FLAG_GRANT_READ_URI_PERMISSION`——chooser 外层有只读 flag，但接收方通过 `EXTRA_STREAM` 拿到的 intent 没有读授权。JVM 测试无法覆盖（android `Intent` 不可在 JVM 实例化，既有 JVM 只断言 `ShareInvocation.flags` 与 chooser 外层）。修复：`addFlags(this@toIntent.flags)`，chooser 的 `also` 块显式 `this.flags`（`core/share/OriginalSharer.kt`）。修复后设备用例 7/7 通过，含「内层 stream intent 带只读 flag、无写/持久位、provider 可读该 URI、返回后 revoke 先于 delete 且 URI 不可再读」断言。

用例耗时（修复后 XML）：`shareIntentCarriesExactlyOneReadOnlyStream` 0.042s，其余 6 项 0.004–0.014s。跑前/跑后设备配置一致（`device-info.txt` / `device-info-post.txt`）。

## 覆盖与未覆盖

已覆盖（JVM 26 项）：
- URI authority 计算与非法 applicationId 拒绝；
- 分享 intent 结构与 flags（只读、单 URI、无持久授权、ClipData 同 URI）；
- 临时目录路径限制、名字安全化、越界文件拒绝、残留清理与时钟过期；
- 释放顺序（先 revoke 后 delete）、重复释放幂等、revoke/delete 失败上报；
- paths XML 与 Manifest provider 声明（文本级回归保护）；
- 生产接线：资料行分享按钮、确认对话框、`ON_RESUME` 释放、失败 `discard`、页面进入清残留。

已覆盖（设备端 7 项，2026-10-22 全部通过）：
- provider 注册：authority `${packageName}.share`、`exported=false`、`grantUriPermissions=true`、无读写 permission；
- 目录隔离：`filesDir` 其他文件、`noBackupFilesDir` 私密库、`cacheDir` 相机缓存都无法经该 provider 生成 URI；
- 真实 intent：ACTION_SEND、单 `EXTRA_STREAM` + 同 URI `ClipData`、仅 `FLAG_GRANT_READ_URI_PERMISSION`（修复后内层 stream intent 也带 flag）、无写/持久位、chooser 结构、provider 可读该 URI；
- 返回释放：`releaseAfterReturn` 撤销授权并删除临时文件、原 URI 不可再读；`clearResidual` 清残留；注入 launcher 只收到已发布 invocation；名字越界不逃出 share 目录。

未覆盖（明确保留）：
- **跨应用 revoke 的真实可见性**：仪器测试进程与应用同 UID（同进程 instrumentation），无法观察「另一个应用在 revoke 后读失败」；设备用例用「`releaseAfterReturn` 返回 `revoked=true` + 临时文件已删 + 原 URI 不可读」近似证明，真实跨应用授权撤销需人工/双应用验证，设备跑完后仍保留此未覆盖项；
- 真实第三方分享目标（微信/邮箱/文件管理器）的实际读取与返回时序；
- 接收方把明文复制/转发（应用无法限制，属用户行为）；
- 真机 OEM、API 26 设备、数百 MiB 原件与低内存；`SAF/媒体库`预览；
- 分享进行中进程被杀：临时文件可能残留到下次进入页面清理，URI 授权在重启前可能仍存在（文件已删则不可读）。

## 风险与设计取舍

- 明文只写 `filesDir/share`（应用私有），生命周期为「发布 → 返回即删」；若进程中途死亡，下次进入页面 `clearResidual` 清理，不存在长期明文目录。
- provider 只暴露 `share/` 一个路径；即使其他应用拿到 URI，也只能读这**一份**临时文件，且返回后文件即删。
- 授权不使用持久 flag，不调用 `takePersistableUriPermission`；仅在 intent 上做一次性 read grant。
- `ON_RESUME` 是「用户从分享目标返回」的可靠信号；`clearResidual` 作为进程重启保险，二者都不依赖 Activity 结果回调。
- 分享失败（无目标、解密失败、越界）统一 `discard` 并提示，不留下可被再次打开的明文。

## 证据文件

| 文件 | 说明 |
| --- | --- |
| `jvm-full.log` / `junit-full/` | 全量 JVM 命令日志与 49 类 JUnit XML |
| `jvm-share.log` / `junit/` | 4 个新测试类定向日志与 XML |
| `jvm-summary.txt` | 全量/定向类项数汇总 |
| `androidtest-compile.log` | androidTest 编译命令日志（BUILD SUCCESSFUL） |
| `device-targeted.log` | 设备命令修复后重跑日志（BUILD SUCCESSFUL，7 项 0 失败，26.6s） |
| `device-targeted-pre-fix.log` | 修复前失败证据（原 `device-targeted.log` 已被重跑覆盖；此文件为修复前控制台输出逐字摘录 + 当时 XML 解析，已明确标注非原始拷贝） |
| `junit/device-OriginalShareDeviceTest.xml` | 修复后设备 JUnit XML（tests=7 / failures=0 / errors=0 / skipped=0，测试耗时 0.964s） |
| `junit/device-report-index.html` | 设备测试 HTML 报告快照 |
| `device-info.txt` / `device-info-post.txt` | 设备跑前/跑后配置（SDK36、`sdk_gphone16k_x86_64`、PAGE_SIZE 16384、font_scale 1.0、浅色、竖屏 1080x2400，跑前跑后一致） |
| `lint.log` / `lint-results-debug.xml` / `lint-summary.txt` | lint 原始报告与 0/35 计数 |
| `jvm-full-final.log` | 修复后最终全量 JVM 复核日志（BUILD SUCCESSFUL，15.6s，49 类/292 项/0 失败/0 跳过） |
| `lint-final.log` | 修复后最终 lint 复核日志（BUILD SUCCESSFUL，62.1s，0 errors/35 warnings） |

## 结论

生产实现完成、JVM 全绿（49 类/292 项/0 失败）、lint 与基线持平（0 errors/35 warnings）、设备定向 7/7 通过。设备执行中发现并修复 1 个真实授权缺陷（内层 `EXTRA_STREAM` intent 缺 `FLAG_GRANT_READ_URI_PERMISSION`），修复后设备证据齐全（`device-targeted.log`、`device-targeted-pre-fix.log`、`junit/device-*.xml`、`device-info*.txt`）。D07 单份原件分享的代码/测试层验收完成；`docs/剩余任务验收清单.md` D07 行与 `docs/已知限制.md:27` 由 Lead 回填。未覆盖项如上（跨应用 revoke 可见性、真实第三方目标、真机/OEM/API26/大文件、进程中途被杀）。
