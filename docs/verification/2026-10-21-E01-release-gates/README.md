# E01 发布阻断项与设备矩阵证据（0.22.0-dev）

2026-10-21，`0.22.0-dev`/versionCode23/schema13。本目录是[测试验收报告](../../测试验收报告.md)的证据附件。

- `gate-summary.json`：本轮各项结论与实测数字汇总。
- `api34-suite.log`：参考设备（API34 模拟器）完整回归 **207 项 / 0 失败**（363.603 秒）。
- `api36-16k-suite.log`：目标 API 36 + **16 KiB 页**设备上同一套件 **207 项 / 2 失败**（205 通过）。
- `page-size-16k.txt`：该设备 `getconf PAGE_SIZE` = **16384**。
- `nativeloader-16k.txt`：16 KiB 页设备上 `libsqlcipher.so` 加载 `ok`（两次，覆盖安装前后）。
- `elf-load-alignment.txt`：`libsqlcipher.so` 四个 ABI 的 `LOAD` 段对齐均为 `0x4000`（16 KiB）。
- `zipalign-16k.txt`：`zipalign -c -P 16 -v 4` 对交付开发包的全部原生库校验通过。
- `performance-samples.log` / `performance-test.log`：性能基线原始输出（30 样本）。
- `apk-permissions.txt`：开发包权限清单。
- `install-upgrade-check.json`：干净安装与升级安装结果。

## 关键数字

| 项 | 结果 |
|---|---|
| JVM | 222 / 0（42 类） |
| API34 仪器测试 | 207 / 0 |
| API36 + 16 KiB 页 | 205 / 207 通过（2 失败，见下） |
| lint | 0 错误 / 24 警告 |
| release(R8) | 构建成功，未签名，约 11.4 MB |
| 记录保存 P95 | 3.0 ms（目标 ≤500） |
| 首屏可交互 P95 | 869 ms（目标 ≤2000） |
| 16 KiB 页 | 静态 + 运行时均通过 |

## 未通过/未执行（不隐藏）

1. `pdfNavigationMetadataSearchAndBackgroundCleanup` 在 API36 上失败：向资料搜索框注入文本后其状态未改变，空态未出现；API34 通过，两设备输入法相同，原因未定位。保留为未决项。
2. `scheduledIntakeEditDeleteUndoAndManualHistoryKeepOriginalPlan` 在整轮运行时超时，单独复跑通过；判为负载偶发，不计入通过。
3. **最低 API 26 未验证**：离线环境无 android-26 系统镜像。
4. 未执行：低内存真机、厂商后台限制、系统字体 200%/TalkBack/深色/旋转、分享后返回、大报告缩放、权限拒绝后恢复、真实 WebDAV 服务商、真机断电/进程终止。
5. 未生成**签名**发布包：签名密钥须在仓库外管理，未获授权前不生成、不发布。
