# 0.28.0 发布验证

2026-11-15；M6-RELEASE-028。

本地离线运行 assembleRelease、testDebugUnitTest、lintDebug，2m27s成功。JVM60类371项，0失败/错误/跳过；lint0错误31警告。正式BuildConfig为DEBUG=false、VISUAL_VERIFICATION=false，版本0.28.0 / code30，schema15。

本地R8产物未签名，仅验证构建；正式签名由[GitHub Release APK工作流](https://github.com/sylvaire/PregnancyJournal/actions/runs/43722738040)完成，2026-11-15 14:15:04 UTC已成功，构建、单元测试和上传均通过；附件已下载并完成本地核验。

本轮设备矩阵见[全卡片记录](../2026-11-15-all-cards/README.md)。此次版本更新未重复跑全量设备矩阵。原始仪器日志保留输出末尾空行；源代码与文档正文空白检查通过，日志按原样归档。

## 正式发布验收

- [Release v0.28.0](https://github.com/sylvaire/PregnancyJournal/releases/tag/v0.28.0)，正式发布，非预发布。
- 代码/标签提交：8a16e6e87e2661c369c2144603c65f6a130dd179；远端master与标签解引用已核对。
- 工作流43722738040成功，2026-11-15 14:08:48至14:15:04 UTC；构建、单元测试、附件上传通过。
- 正式APK：PregnancyJournal-v0.28.0-app-release.apk，11539848字节；已签名、非debuggable，版本0.28.0 / code30，minSdk26 / targetSdk36。
- SHA-256：`0af417450660b95cbf125465e99fbc045dded5e9cb2876c3d76d981072b4022b`。下载APK、本次上传的校验文件、GitHub资产digest三者一致。
- apksigner验证通过，签名证书SHA-256与下载的v0.27.0正式包一致；zipalign -c -P 16 4通过。
- APK未代装到用户手机，未改变手机资料。本地下载及构建包保留在.local；仓库只归档公开发布元数据与验证证据。

验收通过。特殊系统/网络前置矩阵与用户取消的TalkBack不因本次发布扩大为已验证。
