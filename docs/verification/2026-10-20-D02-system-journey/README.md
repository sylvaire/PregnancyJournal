# D02 正式系统保存与确认前Activity重建

2026-10-20。最终debug/androidTest构建、83项JVM通过，lint0错误/20警告。专用API34模拟器43项通过（222.294秒），TestRunner最终43项/0失败/0忽略：真实系统流程2、NavigationExperience23、BackupExporter7、FileHandoff8、RecoveryUi2、IsolatedRestore1。原始证据见build.log、device.log及导航时序XML。

BackupSystemJourneyTest只允许sdk_gphone64_x86_64/API34。安全锁/严格模式启用或存在未处理文件选择时跳过，不更改安全开关。本轮没有跳过。测试保存原加密恢复槽字节，临时以仅在内存中的合成恢复密钥生成恢复槽；真实MainActivity→我的→本地备份→导出→系统DocumentsUI设置测试文件名并保存→应用再次确认→成功提示。读取真实目标文件，用恢复密钥认证，再独立SQLCipher恢复。嵌套finally关闭页面、删除已定位测试输出、恢复原恢复槽/擦除临时主密钥与私有目录，即使输出清理失败仍恢复设置。

第二条流程在系统结果返回、尚未确认时实际调用ActivityScenario.recreate，重新收到同一选择结果；确认前目标仍为空，确认后成功并能恢复。没有主动提交/增删医疗表单，不在手机运行，不保存密钥截图/界面树/原始密钥日志。

初轮20项有1项在进入系统选择器前失败（initial-failure.log）。限定固定标签诊断确认未进入备份页；源代码的全局320ms防连点会丢弃根Tab后快速进入另一页面。生产修复NavigationTapGate按动作/目标抑制相同操作，保留连续返回保护，不靠测试睡眠绕过。新增两项JVM时序测试，23项完整导航页面回归通过。此修订不代表原预约动画帧率问题已解决。

范围：这是实际系统保存和确认前Activity重建，未覆盖保存中重建、实际进程终止、物理旋转/多窗口、系统认证/OEM、真实大文件/低内存/空间不足及提供方错误全矩阵。仅默认本机DocumentsUI提供方通过，不能泛化所有云盘提供方。源schema13，未发布新版本、未安装手机；D02及D03–D07/E范围保留。