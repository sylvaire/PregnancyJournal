# D03/D05 网络鲁棒性验证：慢网、大文件、取消窗口、上传中编辑、进程终止（0.25.0-dev）

2026-10-22，`0.25.0-dev`/versionCode26/schema13。本轮补齐 `docs/剩余任务验收清单.md` 中 D03/D05 的网络鲁棒性遗留中**可在 JVM 内重复验证**的部分，不宣称真实服务商、真机断电或 OEM 矩阵已通过。

## 结论

- 新增 `app/src/test/java/cn/yunji/journal/CloudBackupSlowNetworkTest.kt`：**13 项 JVM 测试全部通过**（JUnit 记录合计 4.438 秒），连续两次定向运行均通过。
- 全量 JVM：**44 类 / 250 项 / 0 失败 / 0 跳过**（基线 43 类/237 项 → +1 类 13 项，无回归）。
- Android lint：**0 错误 / 35 警告**。与 D04 归档基线（0/29）相比多出 6 条，全部为 `gradle/libs.versions.toml` 与 `gradle/wrapper/gradle-wrapper.properties` 的“依赖/插件有新版本”检查（GradleDependency +5、AndroidGradlePluginVersion +1），**与本次改动无关**；本次新增/修改文件在 lint 中 0 条，既有 ExifInterface/UseKtx 共 11 条未变化。详见 `lint-summary.txt`。
- **本轮未修改任何 `app/src/main` 生产代码**：13 项测试全部通过，未暴露需要修复的真实缺陷；D04 刚完成的 `DavBackupRemote.verifiedCommitWriterIds` 读取上限修复保持不变。

## 命令原文（PowerShell，工作区根目录）

```powershell
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user'
# 定向：新增类 13 项
.\gradlew.bat :app:testDebugUnitTest --tests "cn.yunji.journal.CloudBackupSlowNetworkTest" --console=plain   # exit 0
# 全量 JVM
.\gradlew.bat :app:testDebugUnitTest --console=plain                                                        # exit 0
# lint
.\gradlew.bat :app:lintDebug --console=plain                                                                # exit 0
```

## 覆盖矩阵（6 个维度 → 13 项测试）

| 维度 | 测试 | 关键断言 |
|---|---|---|
| ① 慢响应/分块节流下上传进度与取消 | `slowUploadCancelMidStreamPublishesNothingAndRetrySucceeds` | 3 MiB 对象按 64 KiB 分块（每块 1 ms 节流）；第 20 次 checkpoint 抛 `CancellationException` 后远端只有身份文件、无对象、无提交；重连重试流式写入 ≥48 个 checkpoint 并只产生 1 个提交 |
| ① 恢复（下载）进度与取消 | `restoreCancelMidStreamRemovesStagingAndPartialObjects` | 2 MiB 云端对象下载到首块落盘后取消：抛取消异常，`objects/` 暂存目录被删除、无 `.yunji` 归档、无残留部分文件 |
| ② 大文件/长时流式内存不膨胀 | `largeDownloadStreamsInBoundedMemory` | 8 MiB 对象下载：checkpoint ≥128（64 KiB/块）、目的地长度与 SHA-256 正确、运行期堆增长 ≤6 MiB（实测远低于对象大小） |
| ③ 上传中本地源被修改 | `mutatedSourceAfterPreflightFailsBeforeCommit` | 预检通过后、首个对象 PUT 前替换源文件相同长度内容 → 上传报“备份源文件校验失败”，远端无对象、无提交 |
| ③ 上传中本地源被删除 | `deletedSourceAfterPreflightFailsClosedWithoutCommit` | 预检通过后删除源文件 → 在写对象前失败，无对象、无提交（身份文件可已写入） |
| ④ 取消窗口：PUT 后、COMMIT 前 | `cancelBeforeCommitLeavesVerifiedObjectsAndRetryOnlyPublishesCommit` | `COMMITTING` 阶段取消：对象与快照已验证存在、0 提交；重试只新增 1 次 PUT（仅提交） |
| ④ 取消窗口：COMMIT 后、回读前 | `readbackFailureAfterCommitLeavesExactlyOneCommitAndRetrySucceeds` | 提交 PUT 已成功、随后读回返回 500：无成功回执但提交已持久；重试复用该提交，PUT 次数不增、提交字节不变 |
| ⑤ 进程终止后状态恢复 | `writerStateRebuiltAfterTerminationKeepsIdentityAndAdvancesSequence` | 规避持久化在 `cloud-writer.enc`：重建 `CloudWriterStore` 后 writerId 不变、序号 +1 不复用；同清单重放 0 新增 PUT；JUnit 120s 超时验证不死锁 |
| ⑥ 远端错误：prepare 500 | `prepareServerErrorFailsWithoutPartialLayoutAndRetrySucceeds` | 首个 PROPFIND 500 立即失败且不创建任何目录/身份文件；重试成功且仅 1 提交 |
| ⑥ 远端错误：对象 PUT 500 | `objectPutServerErrorFailsBeforeCommitAndRetrySucceeds` | PUT 500 在提交前失败；重试补齐对象并只产生 1 提交 |
| ⑥ 远端错误：提交 409 | `commitConflictFailsClosedAndRetryPublishes` | 409 不被静默覆盖：无提交、对象保留；重试重发提交成功 |
| ⑥ 远端错误：提交 412（对端已有相同提交） | `identicalCommitConflictIsReusedNotDuplicated` | 412 后回读同字节提交 → 视为成功并复用，不产生第二个提交 |
| ⑥ 网络中断：回读校验连接被断 | `interruptedVerifyDownloadFailsClosedAndRetryPublishesOnce` | 校验刚上传对象的 GET 被断连 → 不以成功上报、不提交；重试校验通过并只产生 1 提交 |

## 首次失败与原因（如实记录）

首轮定向运行 13 项中 1 项失败：`slowUploadCancelMidStream...` 在**重试**时报 `云端对象内容校验失败`。定位为**测试夹具缺陷，不是生产代码缺陷**：取消发生在流式 PUT 中途时，MockWebServer 的 dispatcher 可能在请求体只到达一部分时被调用，测试假服务把截断的请求体当成完整对象保存，导致重试的“先回读复用”看到不完整对象。真实 WebDAV 服务端不会暴露未完成 PUT 的对象。修正：假服务校验 `Content-Length` 与实际收到字节数，不一致时返回 400 且不落盘；取消后等待 200 ms 让服务端收尾。修正后 13/13 通过，生产断言未放宽。该“服务端不得暴露半成品 PUT”的前提已列入下方真实服务商风险项。

## 未覆盖 / 不应由本轮声称通过的项

- **真实云盘服务商**：412/409/429/507 与配额、限流、`Retry-After` 实际行为；服务端是否保证 PUT 原子可见（本地假服务已强制该假设，但未对真实服务商验证）。
- **真实慢网与客户端超时边界**：`WebDavClient` 固定 connect 20s / read 30s / call 60s，本环境不做真实超时等待；取消建模用生产 checkpoint（与取消按钮/WorkManager 取消同一路径），不是真实 TCP 慢速链路。
- **真机进程终止/断电**：本轮以“重建持久 writer 状态”建模，不替代 `adb` 强杀、断电、OEM 后台限制矩阵。
- **恢复全链路**：取消测试只覆盖 `CloudSnapshotFetcher` 下载与暂存清理；严格模式系统认证、恢复向导 UI、确认回收/再次恢复生命周期仍属 D05 设备验证。
- **数百 MiB 低内存**：本轮最大 8 MiB 流式下载；`EncryptedFileStore` 100 MiB 上限与真机低内存矩阵未测。
- **API26、无障碍/字体/深色/旋转、TalkBack**：不在本轮范围。

## 证据文件

| 文件 | 内容 |
|---|---|
| `targeted-slow-network.log` | 定向测试命令原始输出（BUILD SUCCESSFUL，13 项） |
| `TEST-cn.yunji.journal.CloudBackupSlowNetworkTest.xml` | 新增类 JUnit XML（13 tests / 0 failures / 4.438s） |
| `jvm-full-suite.log` | 全量 JVM 命令原始输出 |
| `jvm-summary.txt` | 全量类/项/失败数与基线对照 |
| `lint-results.txt` / `lint-results.xml` | 当前 lint 报告（0 错误 / 35 警告） |
| `lint-summary.txt` | 与 D04 基线按 issue 类型的差异说明 |

复现：在工作区根目录执行上文三条命令即可（本目录日志为 2026-10-22 本机 Windows/API36 工具链产物）。
