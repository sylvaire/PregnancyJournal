# D02 外部保存位置半成品提示

日期：2026-10-23。范围：加密备份已开始写入系统文件位置，随后回读失败时的清理结果和用户提示。

- 产物：`app/src/main/java/cn/yunji/journal/data/BackupExporter.kt`、`MainActivity.kt`、`app/src/androidTest/java/cn/yunji/journal/BackupExporterTest.kt`。
- 验证：`:app:assembleDebug :app:assembleDebugAndroidTest` 成功；专用 API36/16KiB 模拟器 `BackupExporterTest` 16/16 通过，含两项新故障注入测试。删除被拒绝时保留半成品并返回专用异常；删除成功时目标消失且保留原始失败原因。
- 限制：这是受控 FileProvider 故障注入；尚未在真实第三方文件提供方上复核 UI 文案和手动清理路径。系统拒绝删除时应用无法强制清理，用户必须到保存位置删除该文件。
