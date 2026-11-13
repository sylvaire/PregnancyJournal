# 云端恢复端到端验证（0.25.0-dev）

2026-10-22，`0.25.0-dev`/versionCode26/schema13。本轮补上上一轮明确标注的验证缺口：**生产适配层的云端恢复端到端测试**。

- `api34-suite.log`：参考设备完整回归 **213 项 / 0 失败**（378.604 秒）。
- `targeted-cloud-e2e.log`：`LocalRestoreCloudTest` **3 项通过**（10.438 秒）。
- `e2e-scope.json`：覆盖范围与夹具说明。
- `summary.json` / `apk-sha256.json` / `lint-results.txt`：汇总、交付包哈希、lint（0 错误/24 警告）。

## 做法

在 `androidTest` 引入 `mockwebserver` 与 `okhttp-tls`，**在设备内启动一个 TLS WebDAV 子集服务**（只实现适配器允许依赖的 PROPFIND 列表与字节精确 GET），然后让真实的 `LocalRestoreAccess` 走完整链路：

```
TLS 假 DAV → CloudSnapshotFetcher（认证提交/逐对象校验/重建归档）
           → LocalRestoreAccess.registerPrepared → RestoreWizard 校验并暂存代际
           → 预览 → 用户确认 → Runtime 切换
```

夹具里**云端的资料库与本机资料库故意不同**（两个独立 vault），因此这条链路验证的是**恢复到新设备**的场景，而不是同机自恢复。

## 覆盖内容

| 用例 | 断言 |
|---|---|
| 列出→下载→预览→提交 | `listCloudSnapshots` 只返回该恢复密钥能打开的快照；预览数量/序号与快照一致；下载暂存目录被清理；**确认前不暂停后台**；提交后库内档案与快照完全一致；恢复后不是主设备 |
| 错误密钥与远端篡改 | 错误密钥列不出任何快照；远端对象被篡改时中止下载；**不留下任何暂存**；未修改库内容 |
| 云盘连接前提 | 未保存连接时明确拒绝；已保存但**未通过校验**的连接同样拒绝 |

## 仍未覆盖

真实云盘服务商（配额、限流、`DELETE`/`last-modified` 实际行为）、断网与慢网下的进行中取消、数百 MiB 对象低内存、真机安装与系统文件选择往返、API26、无障碍/字体/深色/旋转矩阵。以上均未在本轮声称通过。
