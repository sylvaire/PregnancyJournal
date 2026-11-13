# C06 基础实现验证（2026-10-18）

此目录是开发检查点，不是C06阶段交付或V1.0验收。手机仍保留上一交付0.13.0-dev；本轮未覆盖安装手机，未改变手机认证或严格模式设置。数据库schema12不变，开发版本号尚未升级。

## 已取得的证据

- `build.log`：最终源码assembleDebug、assembleDebugAndroidTest、testDebugUnitTest、lintDebug成功；30项JVM测试通过，lint0错误/20警告。
- `emulator-regression.log`：首轮85项模拟器回归通过，包含6项独立目录/独立Keystore别名安全测试、未认证通知不写入、关闭PDF原生资源后清理明文。该轮早于最后的通知脱敏/手动锁定修订。
- `security-targeted.log`：最终修订后14项专项通过，覆盖全部新增密钥/通知测试、提醒仓储和PDF锁定清理；新增检查已显示通知的标题、正文与展开正文被隐藏，原操作入口保留。14项与前述85项有重叠，不相加成99项独立测试。
- 最终模拟器MainActivity冷启动成功，未发现AndroidRuntime异常；仅说明启动通过，不代表系统认证或UI完整验收。

## 实现范围

系统认证入口与安全设置、普通/严格封装格式、严格模式禁止普通open、会话撤销、预览原生资源释放、通知Activity认证确认入口、旧广播不再写医疗记录、通知后台脱敏。正常短暂遮挡保留系统选择器注册；真实锁定会销毁会话和医疗页面。

## 未通过验收的范围

尚未实测真实BiometricPrompt成功/取消、普通与严格切换及中断、凭据/生物识别变化。严格锁定会移除原页面的系统选择器注册，选图/相机/原件导出的跨会话结果回传仍需修复并验证；普通模式短暂遮挡的回传修订也需端到端测试。进程恢复、旋转、后台任务并发、最低API与OEM矩阵未完成。完整恢复密钥交互属于D阶段，现阶段不得清除数据来解决密钥异常。

## API核对

通知重建使用Android平台Notification.Builder.recoverBuilder，见[Android官方参考](https://developer.android.com/reference/android/app/Notification.Builder#recoverBuilder(android.content.Context,%20android.app.Notification))。曾误用Compat静态入口导致编译失败，随后改为平台入口；lint常量类型错误也已修正，最终检查成功。
