# 本机发布版验证宿主

仅用于专用模拟器，不随孕迹发布。此APK包含自己的测试框架与Kotlin依赖，以Instrumentation连接SDK调试证书签名的真实release缩减/混淆副本；不要求目标保留测试API，不含应用源代码依赖。

测试源复用`ReleaseBackupUiSmokeTest.java`，只调用Android框架/JUnit，不直接调用被混淆的应用类。模型/API、安全设置和目标非debuggable检查通过后才运行。恢复设置先保存后还原，临时密钥只在内存，不输出截图/界面树/原始密钥，测试输出结束删除。

构建`:release-validation:assembleDebug`。仅在专用模拟器使用，不能对个人手机/真实用户版本运行。正式签名及发布不属于此宿主用途。
