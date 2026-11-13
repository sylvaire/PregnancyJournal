# D02 当前目标系统保存往返

日期：2026-10-23。专用模拟器：`emulator-5556`，API36/16KiB，型号 `sdk_gphone16k_x86_64`。测试只使用合成恢复密钥，且原有夹具会恢复旧恢复槽、删除测试输出。

- 修改：`BackupSystemJourneyTest.kt` 的环境门禁增加专用 API36/16KiB 模拟器；原 API34 门禁保留，真机仍跳过。
- 构建：`:app:assembleDebugAndroidTest` 成功。
- 设备：`am instrument -w -r -e class cn.yunji.journal.BackupSystemJourneyTest ...`，`OK (2 tests)`，两项均状态码 0、无跳过，总时长 13.956 秒。
- 覆盖：正式 Main、系统 DocumentsUI、二次确认、目标回读、恢复密钥认证与隔离恢复；系统返回后确认前 Activity 重建亦通过。
- 限制：系统 DocumentsUI 内置提供方，不代表第三方提供方、物理真机、保存中重建或低内存矩阵。
