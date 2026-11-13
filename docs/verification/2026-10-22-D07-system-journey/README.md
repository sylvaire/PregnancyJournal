# D07 Main → Android 系统保存器结构化导出往返

日期：2026-10-22。设备：项目 `.local/avd` 中的 `PJ_API36_16K` 专用模拟器（Android 16/API36、16KiB 页）；不在个人手机运行清库型测试。

新增 `ExportSystemJourneyTest.kt`：从正式 Main 的「我的 → 导出资料」进入，确认明文导出，启动 Android DocumentsUI `CreateDocument`，填写随机 ZIP 文件名，完成系统保存与应用二次确认；等待生产导出页报告「导出完成并通过文件回读校验」，验证交接状态已清、保存的 ZIP 可回读且含 `manifest.json`、`fields.json`，最后通过原 URI 删除测试文件。API36 专用门控避免在其他 API 的全量矩阵中误用该设备流程。

验证：

- `:app:assembleDebugAndroidTest` 成功。
- `targeted-first.log`、`targeted-repeat1.log`～`repeat3.log`：去除高频监听后，定向测试连续 **4/4 通过**，各为 `OK (1 test)`。
- `full-api36.log`：新用例纳入全量后 **303 项执行、261 通过、42 前置条件跳过、0 失败，`OK (303 tests)`**，耗时 570.518 秒。D07 新用例在完整顺序中通过。
- 跑后 `/sdcard/Download` 为 `total 0`；先前失败留下的 4 个零字节 `Yunji-Export-System-*.zip` 已按精确文件名从专用模拟器删除，未留下测试明文。

排障记录：首版测试遗漏系统保存后的应用二次确认；随后用后台线程每 5ms 读取加密交接文件，导致偶发保存结果未及时接收、留下 0 字节测试文件。`pre-fix-picker-diagnostic.log` 保留该失败证据。最终测试取消轮询，只在确认框显示后一次性读取持久交接 URI，再继续导出；不修改生产代码。

覆盖边界：此项证明结构化 ZIP 的正式 Main/系统保存器往返与文件回读，不能代替摘要 PDF 的正式 Main 往返、第三方分享接收方、真机/OEM/低内存及大文件矩阵。
