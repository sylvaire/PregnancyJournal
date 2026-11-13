# D02 100 MiB 原件正式系统保存往返

日期：2026-10-23。设备：专用 `emulator-5556`，`PJ_API36_16K`，API36、16KiB 页；测试前 `/data` 可用约 7.9 GiB。所有数据为流式生成的合成原件，使用随机唯一 ID，未使用真实手机或医疗资料。

产物：`app/src/androidTest/java/cn/yunji/journal/BackupSystemJourneyTest.kt`。增加 `hundredMiBOriginalSurvivesMainAndSystemSavePicker`，测试包 `:app:assembleDebugAndroidTest` 成功。

验证范围：在真实应用资源图中创建 100 MiB 合成加密原件及孕期/产检/资料行；从正式 Main 进入系统 DocumentsUI 保存备份，经应用二次确认后回读系统目标；断言文件大小不少于 100 MiB、用合成恢复密钥认证归档、隔离 SQLCipher 恢复后找到同一资料，并将归档中的原件解密回读到 100 MiB。收尾等待对象 pin 释放，删除并断言合成数据库行消失，删除源加密对象与系统保存文件，恢复原恢复槽。

结果：带实际大小与解密断言的整类测试见 `class-final.log`：`OK (3 tests)`，0 失败，29.444 秒。最终又加入数据库清理断言，单项复测见 `device-cleanup-final.log`：`OK (1 test)`，0 失败，19.444 秒。`device.log` 为首次小范围运行，`device-final.log` 为增加大小与解密断言后单项运行，均通过；最终以 `device-cleanup-final.log` 为准。

边界：当前系统内置文件提供方和标准内存环境；第三方提供方、保存中断、低内存正式界面、空间不足与真机/OEM 仍未验收。
