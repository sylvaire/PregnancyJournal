# D07 摘要 PDF 系统保存器往返

日期：2026-10-23。设备：项目 `.local/avd/PJ_API36_16K.avd`，Android API36，16KiB 页。仅在项目专用模拟器运行，合成数据不涉及个人医疗资料。

新增 `ExportSystemJourneyTest.mainExportsReadableSummaryPdfThroughSystemSavePicker`：在正式 Main 的「我的 → 导出资料」勾选明文确认，启动 Android DocumentsUI 系统保存器，保存随机命名的 PDF，确认应用二次交接，等待生产导出页显示「就诊摘要已生成并通过文件回读校验」。测试从返回 URI 检查 `%PDF-` 文件头，再交给 Android `PdfRenderer` 打开并确认至少一页。最后删除系统文件和精确创建的合成孕期、日期修订与变更记录，并验证档案不存在。既有 ZIP 旅程一起回归。

验证：`assembleDebugAndroidTest` 成功；`targeted.log` 中 ZIP/PDF **2/2 通过**，`download-post.txt` 显示 `/sdcard/Download` 为 `total 0`。首轮 `full-api36.log` 执行到 `ProcessRecoveryTest` 后模拟器进程退出、ADB 设备消失，没有最终测试报告；该轮记为**中断，非全量通过**。冷启动重试 `full-api36-retry.log` 中 ZIP/PDF 再次 2/2 通过，但 `NavigationExperienceTest` 有 5 项界面等待/可见性失败，随后系统记录应用 `ANR in cn.yunji.journal`、`Reason: No response to onStartJob`，仪器返回 `shortMsg=Process crashed`；这轮也**不能算全量通过**。当时的导航类直接隔离复跑 `navigation-targeted.log` 也被模拟器/仪器进程崩溃打断。系统重启后，修正下述遮挡时序，封面单项 `navigation-cover-fixed.log` **1/1**、导航整类 `navigation-class.log` **23/23** 通过，再取得下述完整回归报告。

最终回归：修正后的测试包在同一专用 API36/16KiB 模拟器上跑完 `full-api36-after-fix.log`，**305 项执行、0 失败，`OK (305 tests)`，耗时 584.206 秒**。其中 `ExportSystemJourneyTest` 两项、`NavigationExperienceTest` 23 项均在完整顺序中通过；TalkBack 结构测试因服务关闭按前置条件跳过（已另有启用服务时的 1/1 专项证据）。跑后 `/sdcard/Download` 为 `total 0`，`accessibility_enabled=0`。前两轮中断/失败日志仍保留，最终通过不抹去其排障价值。

排障：初版使用了 ZIP 成功文案断言；真实 PDF 成功文案不同。系统保存后还会出现“正在生成就诊摘要”的提示框，需要关闭才可读取导出页状态。两处都只调整测试流程和断言，生产导出代码未改动。

导航类复核：`navigation-cover-diagnostic2.log` 的实际语义树显示「已设为本次产检封面」Snackbar (`Actions=[Dismiss]`) 覆盖了底部「关联产检」按钮；`performClick` 落在提示层，没有弹出关联对话框。测试现在调用提示的真实 `Dismiss` 语义动作、确认提示消失，再对「关联产检」做真实点击，保留后续关联所有权断言。此次修复只涉及 `NavigationExperienceTest.kt`；其他四项在冷启动全量中的超时未独立复现，不能据此称其根因已确定。

边界：此项证明 API36 模拟器上正式 Main → 系统保存器 → 应用回读 → PDF 渲染器的往返；未覆盖真机/OEM、低内存、大 PDF、第三方分享接收与 TalkBack 朗读。
