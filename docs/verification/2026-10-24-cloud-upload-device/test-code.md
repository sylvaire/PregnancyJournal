# 测试代码清单：`CloudUploadDeviceTest`（设备级云端上传 E2E）

- 文件：`app/src/androidTest/java/cn/yunji/journal/CloudUploadDeviceTest.kt`
- 类型：**非 UI** instrumentation 测试（无 Activity / Compose / Espresso），JUnit4 + `AndroidJUnitRunner`。
- 行数：291 行，7 个 `@Test`。
- SHA-256：`8D1C8B19A6F0C2601591D1158F1C6390BD92E8082628E2937260AAC75591D779`
  （app debug APK / androidTest APK 哈希见 `hashes.txt`）。
- 依赖：androidTest 既有依赖（`mockwebserver`、`okhttp-tls`、`androidx.test:runner`、`androidx.test.ext:junit`），
  **未新增依赖、未改构建脚本**。

## 1. 夹具结构

| 成员 | 作用 |
|---|---|
| `FakeDav : Dispatcher` | 设备内假 WebDAV：`PROPFIND`(Depth 0/1、207 multistatus)、`MKCOL`(201/405)、`PUT`(201；`If-None-Match: *` 冲突返回 412)、`GET`(200/404)、`HEAD`；记录 `files`、`directories`、`methods`、`paths`，可开关 `dropCommitReadback`（模拟 commit 回读 404） |
| `withDav(block)` | 生成自签 `HeldCertificate`（SAN=localhost）+ `HandshakeCertificates`，`MockWebServer.useHttps` 起 TLS 服务；客户端用**只信任该证书**的 `OkHttpClient`（HTTP/1.1），构造真实 `WebDavClient` 指向 `https://localhost:<port>/backup/` |
| `Fixture : AutoCloseable` | 设备 `cacheDir/cloud-upload-<uuid>` 隔离目录；合成 `VaultMaterial`/`RecoveryKey`/`RecoverySlot`；生产 `EncryptedFileStore` 加密 2 个合成附件；合成孕期/产检/附件 → `BackupManifest.encode` 为清单；`engine(client)` 返回真实 `CloudBackupUpload(DavBackupRemote(client, []))`；`close()` 抹零主密钥并删目录 |
| `receipt(fake, vaultId)` | 读取 `commits/` 下唯一的 commit 字节，供 `CloudCommit.verify` 回读认证 |

## 2. 用例与覆盖点

| # | `@Test` | 覆盖点（对应 task-6 ①②③） |
|---|---|---|
| 1 | `firstUploadPublishesObjectsSnapshotIdentityAndCommitInPhaseOrder` | ① 首次上传成功：阶段顺序、receipt 字段、远端目录/对象/清单/身份封装/commit、commit 顺序 |
| 2 | `repeatedUploadReusesImmutableObjectsAndCommitWithoutRewriting` | ② 重复上传：receipt 相同、PUT 次数不变、commit 与全部远端条目字节/数量不变 |
| 3 | `foreignIdentityAtDestinationIsNeverOverwritten` | ③ 远端身份不符：失败且不覆盖 `vault.json`、不写对象/commit |
| 4 | `corruptedRemoteObjectBlocksReuseInsteadOfBeingSilentlyReplaced` | ③ 远端对象损坏：失败且不覆盖损坏字节、不发布 commit |
| 5 | `localValidationFailuresNeverWriteRemotely` | ③ 恢复封装不属于本库 / 本地源文件缺失：失败且假 DAV 零请求 |
| 6 | `missingCommitReadbackNeverReportsSuccess` | ③ commit 回读缺失：不返回成功，失败点为强制回读 |
| 7 | `untrustedCertificateIsRejectedWithoutTrustAllFallback` | 生产客户端 TLS 信任边界：未受信证书被拒、无 trust-all 回退 |

## 3. 与 JVM `CloudBackupUploadDavTest` 的对照

| JVM 用例 | 设备侧对应 | 说明 |
|---|---|---|
| `uploadsAttachmentsAndCommitOverTlsAndReusesVerifiedObjects` | 用例 1 + 2 | 设备版拆为“首次”与“重复”两项，并加强顺序/字节不变断言 |
| `foreignIdentityAndCorruptedExistingObjectAreNeverOverwritten` | 用例 3 + 4 | 拆为身份不符与对象损坏两项 |
| `corruptedBytesAlreadyInCloudBlockReuseInsteadOfBeingSilentlyReplaced` | 用例 4 | 同语义 |
| `missingSourceFailsBeforeAnyRemoteRequest` | 用例 5 | 同语义 |
| `foreignVaultSlotAndUnreadableCommitNeverReturnSuccess` | 用例 5 + 6 | 恢复封装校验归入本地失败项；回读缺失单独成项并断言失败点 |
| `untrustedCertificateIsRejectedWithoutTrustAllFallback` | 用例 7 | 同语义，运行在 Android 平台 TLS 栈 |
| `ownBackupHistoryAndForeignWriterArbitrationAreResolvedOverTls` | —（未移植） | 多写者仲裁覆盖点保留在 JVM（见 README §4 限制 4） |

断言语义与 JVM 测试一致，**未放宽**；设备版新增：commit 为最后一次 PUT 且 objects/snapshots 先于 commit、
重试后全部远端条目逐字节不变、失败点校验（回读缺失）、假 DAV 零请求。
