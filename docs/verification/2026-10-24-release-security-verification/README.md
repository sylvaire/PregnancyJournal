# 发布型构建安全行为验证（performance / R8 release-like，2026-10-24 round 18）

目的：确认新增的 `-PvisualVerification=true` 截图开关**不会削弱发布构建**——release-like（performance，R8 minify + debug 签名）构建必须保持 `FLAG_SECURE`。

## 方法

- 构建：`./gradlew :app:assemblePerformance`（`BUILD_TYPE=performance`，与 release 同混淆配置、不可调试、debug 签名）；
- 生成的 `BuildConfig`：`BUILD_TYPE = "performance"`、`VISUAL_VERIFICATION = false`（`_tools/performance-buildconfig.txt`）；
- 在 Android 36（16KiB）模拟器安装并启动，检查窗口标志与 `screencap` 内容。

## 结果

1. 窗口标志：`fl=LAYOUT_IN_SCREEN **SECURE** LAYOUT_INSET_DECOR ...`（`_tools/performance-build-window-flags.txt`）——发布型构建仍启用 `FLAG_SECURE`；
2. `screencap` 结果：1080×2400 全黑（2,592,000 像素均为 `(0,0,0)`，`performance-build-screenshot-black.png`）——应用内容不可截屏；
3. 应用正常启动（未崩溃），证明 R8 混淆后安全标志逻辑仍生效；
4. 与 round 16 对照：`-PvisualVerification=true` 的 debug 验证包才可截图（真机/模拟器均验证），默认 debug 与 performance/release 均保持安全。

## 限制

- 未运行 release/performance 变体的完整仪器矩阵（`performance` 变体需要单独 testBuildType 配置，属后续发布流程项）；
- performance 构建为 debug 签名，仅用于行为验证，不代表正式发布签名产物。
