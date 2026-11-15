# 0.28.0 发布验证

2026-11-15；M6-RELEASE-028。

本地离线运行 assembleRelease、testDebugUnitTest、lintDebug，2m27s成功。JVM60类371项，0失败/错误/跳过；lint0错误31警告。正式BuildConfig为DEBUG=false、VISUAL_VERIFICATION=false，版本0.28.0 / code30，schema15。

本地R8产物未签名，仅验证构建；正式签名由GitHub Release APK工作流完成，尚待云端运行及附件核验。

本轮设备矩阵见[全卡片记录](../2026-11-15-all-cards/README.md)。此次版本更新未重复跑全量设备矩阵。原始仪器日志保留输出末尾空行；源代码与文档正文空白检查通过，日志按原样归档。
