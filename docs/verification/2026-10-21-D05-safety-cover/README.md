# D05 安全副本删除确认随宿主遮挡关闭

2026-10-21，在当前0.21.0开发源上修复。Main通过LocalSafetyActionsAllowed传递covered的反值；SafetyCopiesPanel在宿主遮挡时立即不渲染独立Dialog，并清除已选条目。恢复可见后必须重新点删除，导出入口同样受宿主允许状态约束。正常后台暂停原有选择清理仍保留。

原因：Compose Dialog是独立窗口，父Box的alpha/semantics遮挡不保证Dialog随之隐藏；原逻辑只依赖Lifecycle暂停，未覆盖仍处于RESUMED时的应用内遮挡。

验证：完整222项JVM/0失败、应用与测试构建、lint通过。API34专用模拟器12项通过（4.058秒）：SafetyCopiesUi1、SafetyCopyExporter1、FileHandoff10。UI用例在确认框打开后变更宿主允许状态，检查框消失、源文件存在、重新允许后框不复活，再次明确确认只删除所选夹具。导出/防重放回归通过。

测试以CompositionLocal切换模拟宿主遮挡，未执行真实系统凭据/严格模式完整流程，不能替代该矩阵。没有清除正式资料或操作手机，未另发版本。D07现有导出文件与阶段核心交付保留，后续按当前源码核对数据库/界面接线与验证，不能只依据旧“入口未接入”文档重复实现。

产物：MainActivity.kt、feature/SafetyCopiesScreen.kt、androidTest/SafetyCopiesUiTest.kt。日志build.log、device.log。D05云端下载、旧代际管理、实际进程/严格认证及其他既有矩阵继续保留。
