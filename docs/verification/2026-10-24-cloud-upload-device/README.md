# 云端上传设备级 E2E（TLS 假 DAV + 生产上传引擎）验证（2026-10-24）

- 对应共享任务：`task-6`（测试工程师 `upload-e2e`）。
- 验证对象：**新增** `app/src/androidTest/java/cn/yunji/journal/CloudUploadDeviceTest.kt`（非 UI 仪器测试，7 项）。
  只新增该测试文件与本证据目录；**未改** `app/src/main`、主文档/阶段记录/Figma、未操作小米真机。
- 目的：把 JVM 组合测试 `app/src/test/java/cn/yunji/journal/CloudBackupUploadDavTest.kt` 的云端上传覆盖点，
  移植成在**真实 Android 设备进程**里运行的 E2E：设备内 TLS 假 WebDAV 服务 + 真实 `WebDavClient` /
  `DavBackupRemote` / `CloudBackupUpload`，数据为合成的真实 GCM 密文对象。
- 设备：`emulator-5556`（PJ_API36_16K：Android 16 / API 36 / x86_64 / **16 KiB page** / 1080×2400 / 420 dpi）；
  运行基线 `font_scale=1.0`、浅色、竖屏（详见 `device.txt`）。**未操作小米真机**（11 Ultra / 14 Pro）。
- 边界：Gradle 独占（构建前 `--status` 确认 daemon 为 IDLE，无其他 Gradle 构建）；只写上述测试文件与本目录。

---

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| 设备 E2E（第 1 轮） | ✅ **`OK (7 tests)`**，Time **4.934 s**（`am instrument` 墙钟 6.2 s），0 失败 0 错误 0 跳过 |
| 设备 E2E（第 2 轮，稳定性） | ✅ **`OK (7 tests)`**，Time **4.846 s**（墙钟 6.0 s）；TestRunner `run finished: 7 tests, 0 failed, 0 ignored` |
| 构建 | ✅ `:app:assembleDebug :app:assembleDebugAndroidTest` **BUILD SUCCESSFUL in 11s**（exit 0，含 `compileDebugAndroidTestKotlin`） |
| JVM 单测 | ✅ 强制重跑 `:app:testDebugUnitTest --rerun`：**56 类 / 342 项**，0 失败 0 错误 0 跳过，BUILD SUCCESSFUL in 9s |
| logcat | ✅ 无 `FATAL`/`ANR`；`run finished: 7 tests, 0 failed, 0 ignored`、`VM exiting with result code 0` |
| 设备恢复 | ✅ 基线恢复 `font_scale=1.0`/浅色/竖屏/`stay_on=0`；应用与测试包已 force-stop；无 `cloud-upload-*` 夹具残留（`cleanup-restore.txt`） |
| 产品缺陷 | 无（本次未发现；未改 `app/src/main`） |

---

## 1. 方法

```
MockWebServer + HeldCertificate/HandshakeCertificates（设备内 TLS 假 DAV，仅 PROPFIND/MKCOL/PUT/GET/HEAD）
  → WebDavClient（生产 TLS 客户端：单证书信任、流式 PUT/GET、SHA-256 校验、无 trust-all）
  → DavBackupRemote（生产 DAV 适配：目录/身份封装/对象/清单/commit 语义）
  → CloudBackupUpload（生产同步 IO 引擎：CHECKING_DESTINATION→UPLOADING_OBJECTS→VERIFYING→COMMITTING）
```

夹具（`Fixture`）在设备 `cacheDir` 下创建隔离临时目录：合成 `VaultMaterial` + `RecoveryKey` 生成恢复封装，
用生产 `EncryptedFileStore` 加密两个合成附件，构造 `LogicalSnapshot`（合成孕期/产检/附件）并
`BackupManifest.encode` 出清单；每个用例独立目录、独立假 DAV，结束后 `vault.master.fill(0)` + 删除目录。
全程只访问 `localhost` 环回地址，不使用真实云盘、无凭据、无真实个人医疗数据。

### 与 JVM 测试的关系

与 `CloudBackupUploadDavTest` **覆盖点同源、断言语义不放宽**，差别只在运行环境与少量更严格断言：

- 同一覆盖点：阶段顺序、远端目录/对象/清单/`vault.json`/commit 内容、`CloudCommit.verify` 回读认证、
  重试不重写不可变对象与 commit（PUT 次数不变）、远端身份不符/对象损坏/源文件缺失/commit 回读缺失不返回成功且不静默覆盖。
- 设备版加强：额外断言 commit 是全部 PUT 中**最后**一次、且其前已上传 objects/snapshots；
  重试后**全部远端条目逐字节不变且数量不变**；回读缺失必须失败在 `云端提交回读缺失` 这一步（而非更早阶段）。
- 运行环境差异：真实 Android TLS 栈（平台 `SSLSocketFactory`）、设备文件系统、instrumentation 进程；
  不可使用 JVM 专属 API。

---

## 2. 用例（7 项）

| # | 测试方法 | 覆盖点 | 关键断言 |
|---|---|---|---|
| 1 | `firstUploadPublishesObjectsSnapshotIdentityAndCommitInPhaseOrder` | ① 首次上传成功与阶段顺序 | 阶段序列恰为 `CHECKING_DESTINATION, UPLOADING_OBJECTS, VERIFYING, COMMITTING`；receipt 的 snapshotId/writerId/writerSequence/capturedSequence(=21)；`objects/snapshots/commits` 目录存在；每个对象、清单、`vault.json` 字节级一致；`CloudCommit.verify` 认证通过；commit 是最后一个 PUT 且位于 `commits/` |
| 2 | `repeatedUploadReusesImmutableObjectsAndCommitWithoutRewriting` | ② 重复上传不重写不可变对象/commit | 第二次 receipt 与第一次相同；**PUT 次数不变**；commit 字节不变；所有远端条目字节与数量不变（无新增） |
| 3 | `foreignIdentityAtDestinationIsNeverOverwritten` | ③ 远端身份不符 | 上传失败；`vault.json` 保持外部身份字节；`objects/`、`commits/` 均无写入 |
| 4 | `corruptedRemoteObjectBlocksReuseInsteadOfBeingSilentlyReplaced` | ③ 远端已有对象损坏 | 上传失败；损坏字节**未被覆盖**；未发布 commit |
| 5 | `localValidationFailuresNeverWriteRemotely` | ③ 恢复封装不属于本库 / 本地源文件缺失 | 两种失败均成立；且假 DAV **零请求**（先本地校验、后远端写入） |
| 6 | `missingCommitReadbackNeverReportsSuccess` | ③ commit 回读缺失 | 返回失败而非成功；失败点为强制回读（`云端提交回读缺失`）；证明 commit 已写但回读失败不算成功 |
| 7 | `untrustedCertificateIsRejectedWithoutTrustAllFallback` | 生产客户端 TLS 信任边界 | 默认信任库下自签证书握手失败（`SSLException`/`IOException`），生产 `WebDavClient` 无 trust-all 回退 |

---

## 3. 设备与命令

设备事实（`device.txt`）：`emulator-5556` / `sdk_gphone16k_x86_64` / x86_64 / Android 16（API 36）/
page size **16384 B** / 1080×2400 / 420 dpi / `font_scale=1.0` / `Night mode: no` / `user_rotation=0`。

```powershell
# 1) 构建（Gradle 独占，先确认 `--status` 为 IDLE）
.\gradlew.bat --gradle-user-home .gradle-user --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest

# 2) 安装（-g 授予运行时权限；测试 APK 需 -t 允许 testOnly）
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 install -r -g -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk

# 3) 运行（非 UI，7 项）
adb -s emulator-5556 shell am instrument -w -r -e class cn.yunji.journal.CloudUploadDeviceTest cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner
```

结果行：第 1 轮 `Time: 4.934` / `OK (7 tests)`；第 2 轮 `Time: 4.846` / `OK (7 tests)`。
`testDebugUnitTest` 在组合构建中为 `UP-TO-DATE`，因此额外以 `--rerun` 强制重跑，得到当日新鲜结果
（56 类/342 项，0 失败）。构建摘要见 `build-summary.txt`、JVM 结果见 `jvm-rerun.txt`。

---

## 4. 限制

1. **假 DAV ≠ 真实服务商**：只实现适配器被允许依赖的协议子集（PROPFIND/MKCOL/PUT/GET/HEAD，207/401/404/412/405 等），
   不覆盖真实 WebDAV 服务商的鉴权体系（OAuth/应用密码/令牌刷新）、限流 `429/Retry-After` 退避、代理/网关、
   证书链与中间证书、服务端路径编码/重定向差异、真实网络抖动。**真实服务商联测仍未验收**。
2. **未在 11 Ultra 执行**：MIUI 拦截 instrumentation 测试 APK 安装（`INSTALL_FAILED_USER_RESTRICTED`），
   且小米 HyperOS/MIUI 会拦截仪器测试进程启动 Activity；因此云端/备份相关**非 UI 设备测试固定在 API36 模拟器**，
   真机（小米 11 Ultra / 14 Pro）尚未覆盖本用例。
3. **覆盖范围止于上传管线**：不经过 UI、WorkManager 调度、连接配置存储、上传后清理/保留策略
   （这些由既有 JVM/设备 UI 测试覆盖，本用例不重复）。
4. **设备侧未复测**：真实慢网/取消窗口（JVM `CloudBackupSlowNetworkTest` 已覆盖）、多写者仲裁
   （`CloudBackupUploadDavTest#ownBackupHistoryAndForeignWriterArbitrationAreResolvedOverTls` 保留在 JVM）、
   进程被强杀/断电下的上传恢复。
5. 假 DAV 与测试数据均为合成，不涉及真实个人医疗数据或真实云端凭据。

---

## 5. 本目录产物

| 文件 | 内容 |
|---|---|
| `README.md` | 本记录：方法、与 JVM 测试关系、设备/命令、结果、限制 |
| `test-code.md` | 测试代码清单（固件结构、7 个用例、与 JVM 用例对照） |
| `instrument-cloud-upload-device.txt` | 第 1 轮 `am instrument -w -r` 原始输出（`OK (7 tests)`） |
| `instrument-cloud-upload-device-run2.txt` | 第 2 轮原始输出（稳定性复跑） |
| `logcat-run2.txt` | 第 2 轮 TestRunner/logcat 记录（0 failed、VM exit 0、无 FATAL/ANR） |
| `device.txt` | 模拟器型号/API/页大小/分辨率/字体/主题/旋转 |
| `build-summary.txt` | 构建任务与 `BUILD SUCCESSFUL in 11s` 摘要 |
| `jvm-rerun.txt` | 强制重跑 JVM 单测的聚合结果（56 类/342 项 0 失败） |
| `hashes.txt` | 测试源文件、app APK、androidTest APK 的 SHA-256 |
| `cleanup-restore.txt` | 运行后模拟器基线恢复、应用/测试包 force-stop、夹具残留检查 |
