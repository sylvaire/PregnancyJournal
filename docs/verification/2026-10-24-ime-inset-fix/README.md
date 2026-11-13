# Bug #3 修复验证：键盘弹出时双重 IME 内边距压缩（2026-10-24）

- 现象（由 `ui-header` 在 P0-4 修复期间捕捉的失败态证据）：文档列表搜索框聚焦、键盘弹出后 `document-list` 可见视口仅约 **106.7dp**（uiautomator bounds `[0,354][1080,634]`），空态按钮“清除搜索与筛选”落在视口外导致点击未命中；`NavigationExperienceTest#pdfNavigationMetadataSearchAndBackgroundCleanup` 因此超时。
- 根因：`JournalPage` 的 Scaffold `contentWindowInsets = WindowInsets.safeDrawing` 已包含 IME inset，内容 Box 又额外 `.imePadding()`；`RestoreScreen` Column 再叠加一次 `.imePadding()` → 键盘出现时内容被重复扣减。
- 修复（2 处）：
  - `feature/JournalNavigation.kt:377` 内容 Box 移除 `.imePadding()`（SnackbarHost 的 imePadding 保留）。
  - `feature/RestoreScreen.kt:27` Column 移除 `.imePadding()`。
- 回归（emulator-5556 / API36 / 16KiB）：
  - 构建 `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest` → BUILD SUCCESSFUL；JVM 342/342。
  - `NavigationExperienceTest` 整类 **OK (23 tests)，EXIT=0**（含文档搜索/编辑/键盘路径；日志 `lead-rerun-navigation-experience.log`）。
  - 修复前（P0-4 header 修复后、测试滚动同步适配已加入时）Lead 复核整类 **OK (23 tests)**（`lead-rerun-navigation-before-ime-fix.log`），说明测试适配可绕过该缺陷；真正的失败态证据是 ui-header 首轮 1/23 失败与其 uiautomator 视口记录（106.7dp）。测试侧滚动同步适配保留为防御性同步，未放宽断言。
- 限制/待办：
  - **专项视口回归断言已补（2026-10-24 晚）**：新增 `ImeInsetLayoutTest`（androidTest）：键盘弹出后 `document-list` 高度 **443.05dp**（断言 ≥300dp；修复前基线 106.7dp），无键盘 755.43dp、收起后恢复 755.43dp；`am instrument` **OK (1 test)**。证据见 [2026-10-24-ui-evidence-gaps](../2026-10-24-ui-evidence-gaps/README.md)。
  - 仅模拟器验证；真机（11 Ultra）当前离线，键盘/输入法行为（MIUI bdmiime）待重连后复核。
- 相关证据：`docs/verification/2026-10-24-ui-header-fix/_tools/debug-failing-state-viewport.png`、`ui.xml`。


