# D05 云端快照下载与恢复核心（0.23.0-dev）

2026-10-21，`0.23.0-dev`/versionCode24/schema13。本轮实现**云端快照下载**并修复一个导致系统提供方崩溃的真实缺陷。

- `api34-suite.log`：参考设备完整回归 **207 项 / 0 失败**（383.126 秒）。
- `junit-CloudSnapshotFetcherTest.xml`：新增 **8 项**云端下载/发现测试，0 失败。
- `logcat-no-fatal.txt`：修复后整轮运行 logcat **无任何 FATAL EXCEPTION**。
- `summary.json` / `apk-sha256.json` / `lint-results.txt`：汇总、交付包哈希与 lint（0 错误/24 警告）。

## 本轮实现

**流式下载原语**（`WebDavClient.downloadTo`）
- 恒定内存地把远端对象写入本地文件，边写边算 SHA-256，校验长度与哈希；
- 任何失败都会删除目标文件，**不会留下可被误认为完整的半成品**；
- 拒绝已存在的目标文件，要求 `Accept-Encoding: identity`。

**云端快照下载**（`CloudSnapshotFetcher`）
1. 校验恢复密钥格式（格式错误立即报错，而不是含糊地显示“没有备份”）；
2. 读取并认证提交：`CloudCommit.verify` 把清单与该文件名、写入者、序号绑定；
3. 逐对象下载并按 `cipherSize`/`cipherSha256` 校验；
4. 重建为标准 `.yunji` 归档（`vault.json` + `manifest.enc` + `objects/`）；
5. **用用户恢复密钥再校验一次**，通过后才交给既有恢复向导。

之所以重建归档而不是直接写库：既有恢复路径（校验 → 隔离导入 → 有日志的原子切换）已经逐项验证过，复用同一容器可以只维护一套保证。

**新设备发现**（`discover`）
- 列出远端 `pregnancy-journal/*` 目录，读取各自 `vault.json`，只返回**本恢复密钥能打开**的资料库；
- 这让“恢复到新设备”成立：新设备本地 vault id 与原设备不同，起标识作用的是**远端恢复槽**而不是本机状态；
- 发现出的候选可直接列举与下载，不要求本机存在匹配的恢复槽。

## 修复的缺陷（发布阻断级）

`ExportSafWriter.releaseTransientAccess` 原本“防御性”地对任意目标调用 `releasePersistableUriPermission`。当应用并未持有该授权时，**系统 MediaStore 提供方进程会崩溃**：

```
FATAL EXCEPTION: com.android.providers.media.internal.modules.utils.BackgroundThread
Process: com.google.android.providers.media.module
java.lang.NullPointerException ... UriPermissionOwner.removeWritePermission / revokeUriPermission
```

表现为整轮仪器测试 `INSTRUMENTATION_RESULT: shortMsg=Process crashed`。修复：先检查 `persistedUriPermissions` 是否真的持有该 URI 的授权，只在持有时释放。修复后 207 项全通过且 logcat 无 FATAL。

## 测试覆盖

| 用例 | 断言 |
|---|---|
| 下载并重建归档 | 提交/清单认证通过；重建的归档可用恢复密钥打开；**原件逐字节一致** |
| 远端对象被篡改 | 哈希不匹配即中止，且不留归档 |
| 提交/清单/对象缺失 | 分别失败，且不产生部分输出 |
| 恢复密钥错误 | 不产生可用归档 |
| 列举可恢复快照 | 只返回能认证的，按序号/时间倒序；清单缺失的不列出 |
| 跨资料库与非法标识 | 拒绝路径穿越式标识、不存在的快照、其他 vault 的身份 |
| 新设备发现 | 只返回本密钥能打开的资料库，并能直接列举与下载 |
| 错误/畸形恢复密钥 | 错误密钥→空列表；畸形密钥→明确报错 |

## 未完成

- **界面入口未接线**：`RestoreAccess`/恢复页面尚无“从云端恢复”的列表与下载入口，用户目前无法从界面使用该能力。
- 未做：真实 WebDAV 服务商的下载行为、断网/慢网与中途取消、数百 MiB 对象低内存、真机安装。
- 下载暂存目录的清理目前要求调用方负责（生产接线时需与既有 `restore-input` 生命周期合并）。
