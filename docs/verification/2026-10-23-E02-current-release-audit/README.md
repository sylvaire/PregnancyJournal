# 0.25.0-dev 未签名 release APK 审计快照

日期：2026-10-23。针对当前工作区 `0.25.0-dev` / versionCode 26 的本地 `:app:assembleRelease`，R8 构建成功（首次 1 分 40 秒，B03 动画修订后重建 45 秒）。最终产物：[app-release-unsigned.apk](../../../app/build/outputs/apk/release/app-release-unsigned.apk)，11,453,949 字节；SHA-256 `C32C5CF9FA07C6311C8AB0C4A491CC2BF6FE229EC4DC1E911B7D655EA95B7C6E`，见 [sha256.txt](sha256.txt)。该哈希仅标识本次本地未签名构建，不是对外发布校验值。

## 包内核对

- `aapt dump badging`：包名 `cn.yunji.journal`，minSdk 26、targetSdk 36，versionName 仍为 **`0.25.0-dev`**，见 [badging.txt](badging.txt)。因此目前还不是 V1.0 定稿版本。
- 合并 Manifest：`allowBackup=false`、`usesCleartextTraffic=false`；未发现 `android:debuggable=true`；组件导出状态见 [manifest-tree.txt](manifest-tree.txt)。权限列表见 [permissions.txt](permissions.txt)，包括提醒、电池优化申请、网络、生物识别和 WorkManager 所需权限；没有相机、定位或宽泛存储权限。
- `zipalign -c -P 16 -v 4` 对 APK 通过，见 [zipalign-16k.txt](zipalign-16k.txt)。四个 ABI 的 `libsqlcipher.so` 三个 LOAD 段对齐均为 `0x4000`，见 [elf-load-alignment.txt](elf-load-alignment.txt)。R8 `mapping.txt` 已生成，约 61 MB；映射文件需作为敏感发布工件在仓库外妥善保管。
- `classes.dex` 字面量定向扫描：`10.0.2.2`、`127.0.0.1`、`localhost`、`example.com`、`test-password` 各 0 次，见 [test-literals-scan.txt](test-literals-scan.txt)。`mockwebserver` 出现 1 次，附近为 OkHttp 名称字符串；此扫描不构成凭据或完整依赖审计。
- `apksigner verify` 报 `DOES NOT VERIFY / Missing META-INF/MANIFEST.MF`，符合 **未签名** 产物，见 [signature-check.txt](signature-check.txt)。本包不能作为正式更新包交付。

## 未完成

V1.0 版本号与签名配置未定稿；正式签名包、升级安装和签名包 SHA-256 尚不存在。小米真机当前不在线，未对本次 release 产物做设备运行验证。E01/E02 整体验收仍未完成。

变更记录：同日 B03 预约动画去掉底层整页透明度叠加后，重新执行 release 构建、APK 配置/权限、16 KiB 对齐、ELF 对齐、字面量和签名状态检查；旧哈希 `4EB16D1B...` 已失效，以当前 [sha256.txt](sha256.txt) 为准。

后续状态：本次审计完成后又实现 C05 备份失败通知，源码已变化；上述哈希只标识 **B03 修订后的构建快照**，不再代表最新源码。最终发布前须重新构建并重做包审计。
