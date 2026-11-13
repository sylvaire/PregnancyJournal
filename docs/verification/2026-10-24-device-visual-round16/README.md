# 小米 11 Ultra 真机像素级 UI 验证（2026-10-24，round 16）

## 0. 背景与方法

此前 11 Ultra 真机截图受应用 `FLAG_SECURE` 限制（`MainActivity` 对所有构建设置安全标志），只能以 uiautomator 语义树+文件系统做结构验证，无法做像素级视觉对比。本轮新增**显式验证构建开关**：

- `app/build.gradle.kts`：`buildConfigField("boolean","VISUAL_VERIFICATION", providers.gradleProperty("visualVerification").orElse("false"))`；
- `MainActivity.secureFlagRequired()`：仅当 `VISUAL_VERIFICATION=true` 时不加 `FLAG_SECURE`；普通 debug/release/performance 一律保持安全标志；
- 新增 JVM 测试 `SecureFlagPolicyTest`（普通构建须安全、验证构建可放开、默认构建为安全）；
- 用 `./gradlew -PvisualVerification=true :app:assembleDebug` 构建验证包安装到 11 Ultra，截图后用默认构建重装并确认 `FLAG_SECURE` 恢复（窗口 `fl=... SECURE`、`screencap` 返回 0 字节）。

设备：Xiaomi 11 Ultra（1440×3200 / 560dpi / Android 13）；截图时浅色、竖屏、字体默认；合成数据（孕期 LMP 2026-10-24、1 次产检、1 条日常记录、1 条体重测量）。

## 1. 证据清单

- `shots/`：17 张真机截图（今天、产检列表/详情、记录枢纽、时间线、日常记录编辑器、用药方案、服药记录、全部资料、健康指标、我的、云盘连接、本地备份、导出资料、提醒设置、开启应用锁系统认证框、深色模式今天/时间线）；
- `comparisons/`：10 张 Figma vs 11 Ultra 并排对比图 + `00-device-screens-overview.png` 总览；
- `_tools/`：截图/对比/测色脚本、`device-colors.txt`、`default-build-window-flags.txt`（FLAG_SECURE 恢复证据）。

## 2. 关键结论（真机像素级）

1. **色板与设计令牌一致**（真机截图测色）：
   - 浅色：背景 `#F7F8F3`、soft `#E7F0E6`、卡片 `#FFFFFF`、主色 `#356B53`、正文 `#223C32`、描边 `#DDE4DA`；
   - 时间线杏色备注卡：`#FAEAD8`（456,522 px 实测）。
2. **Timeline 方案 A 真机视觉与 Figma 高度一致**：3 筛选（全部/产检/日常，选中主色填充）、日期组头+右侧孕周、72dp 白卡记录行+44dp soft 图标容器、杏色备注卡、底部主 CTA「写一条日常备注」；对比图 `comparisons/12-Timeline__vs__05-timeline.png`。
3. **今天/产检/产检详情/用药/健康/我的/云盘/备份/导出/资料** 均与设计语言一致（卡片圆角、24dp 页边距、标题层级、主色按钮）；今天页信息架构与 Figma 仍有已知差异（设计含「今天的安排/随手记一笔/最近资料」，实现为产检卡+快捷入口+今日服药卡），属既有 IA 差异（见 `2026-10-24-figma-ui-alignment` 备忘），本轮确认**视觉风格统一**。
4. **Figma 未设计页面风格一致**：记录枢纽、日常记录编辑器、全部资料、云盘连接、本地备份、导出资料、提醒设置等使用同一色板/卡片/按钮/空态体系（总览图可对照）。
5. **深色模式**：深色底部导航/卡片/主色为深色令牌（`#141D17` 背景、`#294C39` 卡片、`#A5D0B3` 强调、`#E3EADF` 文字），无浅色残留；`shots/20-today-dark.png`。

## 3. 安全页面的截图限制（设计使然）

`安全设置` 以全屏 `Dialog` 呈现且 `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)` 显式安全（同理恢复向导/确认锁屏等），即使验证构建也无法截图；该页保留 uiautomator 语义树证据与模拟器快照（`2026-10-24-ui-p1-polish` 等）。`shots/17-security-auth-prompt.png` 为系统认证框（系统窗口，可截图）。

## 4. 构建与回归

- `-PvisualVerification=true` 仅用于截图；默认构建 `BuildConfig.VISUAL_VERIFICATION=false`，11 Ultra 重装默认包后窗口 `fl=... SECURE`、`screencap` 0 字节，安全行为已恢复。
- JVM：342/342（含 `SecureFlagPolicyTest`）。
- 全量仪器矩阵：首轮 368/369（`openingAndReturningWithoutChangesDoesNotCreateDraft` 全量负载下编辑器首帧未就绪，属测试同步脆弱）→ 增加 `waitUntil` 等待表单节点后单用例 OK(1)、重跑 **OK (369 tests) / 0 失败 / EXIT=0**（`_tools/lead-full-matrix-round16b-green.log`，首轮日志 `lead-full-matrix-round16-first.log`）。
- 设备数据已清理（合成孕期/记录彻底删除、vault gen objects 0）；11 Ultra 保留默认构建 APK 与空数据。

## 5. 限制

- 安全设置页/部分安全对话框按设计不可截图；
- 深色模式仅今天页有效截图（时间线深色截图与今天页重复，未纳入对比）；
- 设计稿示例数据（孕 18 周等）与合成数据（孕 0 周）不同，属数据差异而非样式差异。


- **发布型构建安全复核（round 18）**：performance（R8 release-like）构建窗口 SECURE、screencap 全黑、应用正常启动，见 `../2026-10-24-release-security-verification/README.md`。
