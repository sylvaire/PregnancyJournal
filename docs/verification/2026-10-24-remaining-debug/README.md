# 剩余调试任务收尾：日期格式统一 / Switch 禁用态证据 / 安全设置说明（2026-10-24）

- 对应目标：`完成剩下的调试任务`（goal revision 6）；11 Ultra 仍离线（无线调试自 04:24 断开），本目录覆盖可在模拟器完成的剩余项。
- 设备：`emulator-5556`（API36 / 1080×2400 / 420dpi，1dp=2.625px）；基线浅色/竖屏/font_scale=1.0。
- 改动范围：`app/src/main` 展示层格式化 + 新测试 `UiSwitchStateSnapshotTest`；未改 Theme.kt/主文档/阶段记录/Figma；未操作小米真机。

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| A. 日期/时间展示格式统一 | ✅ 新增 `feature/DisplayFormat.kt`（displayDate/displayDateTime/displayInstant），全仓 **41 处**展示层调用；表单/导出/备份数据层仍 ISO；JVM 342/342（含更新后的 `CloudCleanupControllerTest`） |
| B. Switch 禁用态像素证据 | ✅ 新增 `UiSwitchStateSnapshotTest`，4 状态同页截图；禁用轨道 `#E4E7E6`、禁用滑块 `#CCD2D0`/`#9AA6A2`，启用轨道 `#356B53`/滑块白；与启用态可区分 |
| C. 安全设置（应用锁/立即锁定） | ⏭️ **未在模拟器覆盖**：应用锁使用系统生物识别/设备凭据（`UnlockScreen`→系统认证），模拟器未配置设备锁屏凭据；启用需改动系统级屏幕锁设置，超出本次调试安全边界，留待 11 Ultra（有设备锁）重连后验证 |
| 测试 | ✅ 重建测试 APK 后 `NavigationExperienceTest + UiSwitchStateSnapshotTest` **OK (25 tests)**；JVM 342/342；全量矩阵结果见 §4 |

## 1. A. 日期展示格式统一

- 规则：同年 `M月d日`；跨年 `yyyy年M月d日`；带时间 `M月d日 HH:mm`；时间戳按事件时区换算；解析失败原样返回（不吞数据）。
- 示例（测试/界面实测）：`2026-10-24` → `10月24日`；`2023-11-15 06:13` → `2023年11月15日 06:13`（跨年）；`2026-10-17` + `09:30` → `10月17日 09:30`。
- 覆盖页面：JournalApp（产检行/实际就诊/提醒摘要）、PrenatalScreens、IntakeScreens、MedicationScreens、DocumentScreen、ReminderSettingsScreen、TimelineScreens（原始记录页）、CloudConnectionScreen/CloudCleanupController、RestoreScreen、MeasurementScreens 等。
- 语义文案保留：`时间未记录`、`时间待定` 等说明不变。
- 测试适配：`CloudCleanupControllerTest#timeLabelUsesRequestedZone` 期望从 `2023-11-15 06:13` 更新为 `2023年11月15日 06:13`（展示格式变化，非断言放宽）。
- 限制：日期选择器/表单输入值仍为 ISO（避免破坏输入语义）；未实现“昨天/前天”等相对日期。

## 2. B. Switch 禁用态像素证据

- 新增 `app/src/androidTest/java/cn/yunji/journal/UiSwitchStateSnapshotTest.kt`：同一页面渲染 disabled/on、disabled/off、enabled/on、enabled/off 四态，`captureToImage` 保存 `verification/remaining-debug/switch-states.png`。
- Pillow 实测：禁用未选中轨道 `#E4E7E6`（onSurface 低透明度混合）、禁用滑块 `#CCD2D0`（外圈 `#9AA6A2`）；启用选中轨道 `#356B53`（2227 px）、滑块白。禁用态与启用态颜色/对比可区分，且不含高饱和主色。
- 证据：`screenshots/switch-states.png`、`_tools/measure_switch_states.py`。

## 3. C. 安全设置模拟器走查（已完成，2026-10-24）

> 补充：本轮在 emulator-5556 上用临时设备凭据完成了应用锁全链路走查（取代原先“未覆盖”的说明）。

- 前置：`locksettings set-pin 2580` 为模拟器配置临时设备凭据（`locksettings verify --old 2580` 通过）。
- 步骤与结果（语义树证据 `security/01–09*.xml`）：
  1. 我的 → 安全设置 → 「开启应用锁」：弹出系统 BiometricPrompt（标题「解锁孕迹」/副标题「使用系统认证保护本机资料」/`PIN area`）；输入 PIN 后应用锁**开启成功**（页面变为「已开启，使用系统生物识别或设备凭据解锁」+「关闭应用锁」）。
  2. 「立即锁定」：应用立即切到锁定页「资料已锁定 / 解锁资料 / 解锁后继续查看，原有记录和草稿保留」；锁定期间不展示记录内容。
  3. 点「解锁资料」：系统 BiometricPrompt 再次出现；**先输入错误 PIN**（1111）→ 提示层保持、PIN 输入被清空（语义树无错误文案节点，实际系统错误提示不在应用语义树内）；**再输入正确 PIN**（2580）→ 解锁成功回到应用（我的页），数据与草稿保留。
  4. 「关闭应用锁」→ 系统认证（PIN 2580）→ 应用锁**关闭成功**（页面回到「未开启，打开应用时可直接查看本机记录」+「开启应用锁」）。
- 观察/限制：
  - 应用 `FLAG_SECURE` 使得 `screencap` 对应用窗口（含其上的系统凭据对话框）返回黑屏，因此以 uiautomator 语义树为证据，无有效截图；
  - 系统 PIN 输入使用 `adb shell input text` + Enter，属自动化注入（真机需人工/生物识别）；
  - 走查后已清理：应用锁关闭、`locksettings clear --old 2580`（凭据清除）、锁屏 disabled 状态恢复、应用 force-stop。
- 结论：**应用锁开启/立即锁定/错误与正确设备凭据解锁/关闭全链路正常**；真机（11 Ultra 有设备锁）重连后复核生物识别路径。

## 3b. 原“未覆盖”说明（历史）

- `SecuritySettingsScreen` 的「开启应用锁 / 立即锁定」依赖系统生物识别或设备凭据（`MainActivity` 的 `UnlockScreen`→系统认证）；emulator-5556 未设置锁屏 PIN/生物识别，启用会要求系统级凭据配置，存在锁定后无法自动恢复的风险（需 `pm clear` 甚至系统设置改动），故本轮不执行。
- 待 11 Ultra 重连后验证：开启应用锁→立即锁定→错误/正确设备凭据解锁→关闭应用锁（真机有设备锁，可完整走查）。

## 4. 测试与全量矩阵

- JVM：**342/342**（含 CloudCleanup 格式更新）。
- 期间一次 61 tests/2 failures 为**测试 APK 未重建**（应用 APK 已是中文日期、测试 APK 仍断言 ISO；及 cloud 用例旧版本），重建 `:app:assembleDebugAndroidTest` 并安装后 `NavigationExperienceTest + UiSwitchStateSnapshotTest` **OK (25 tests)**。
- **全量矩阵（最终）：OK (369 tests)，0 失败，EXIT=0**（`_tools/lead-full-matrix-round13.log`），包含新增 `UiSwitchStateSnapshotTest` 与日期格式改动后的全部仪器测试。

## 5. 剩余项（真机阻塞）

1. 11 Ultra 无线调试重连后：删除/回收站/孕期删除收尾、安全设置真机走查、云盘连接页/导出/自定义提醒/健康指标复核、新 APK 真机视觉/键盘回归（含 Timeline 方案 A 新界面）。
2. 真实 WebDAV 上传联测（需服务地址与凭据）。
3. 可选 P1：图标语义化（需 Figma 图标命名映射）、日期相对文案。



## 6. P1-8 图标语义化（部分完成，2026-10-24）

- 现状：仓库仅有 14 个 Figma 导出 SVG，文件名无语义（hex），此前「记录」底部导航与记录枢纽/我的页多行复用同一 `86969.svg`（叶形），被 UI 评审列为 P1-8。
- 本轮用临时快照测试把 14 个 SVG 并列渲染（`screenshots/icon-catalogue.png`），按可见图形做**低风险语义映射**：
  - 底部导航「记录」：`86969`（叶）→ **`cdc29`（文档）**，与设计“记录用文档图标”一致；
  - 记录枢纽：孕期时间线 → `5fbe7`（叶轮廓）、日常记录 → `86969`（叶）、全部资料 → `cdc29`（文档）、用药方案 → `42641`（日历/日程）、服药记录 → `7b4fe`（对勾）、健康指标 → `122c6`（日历）；
  - 我的页：管理孕期 → `d9f3c`（人像）、全局回收站 → `73f08`（禁止/移除）、云盘连接 → `62b4c`（网络）、本地备份 → `d34da`（文档）、导出资料 → `cdc29`（文档），用药/服药/健康与记录枢纽一致。
- 未改：提醒设置、安全设置仍沿用 `86969`（现有图标集中无“铃铛/盾牌”语义资产，避免臆造）；如设计侧提供图标命名映射可继续收敛。
- 证据：`screenshots/icon-catalogue.png`；临时测试 `IconCatalogueSnapshotTest.kt` 仅用于出图，完成后已从仓库移除，不进入产品测试集。
- **round 14 复跑（图标调整 + 云盘用例同步修复后）：OK (369 tests)，0 失败，EXIT=0**（`_tools/lead-full-matrix-round14.log`）。云盘用例改为以 `model.cloud.state.value.message` 断言非法字符文案（UI 文案渲染已由空白账号用例覆盖），避免全量负载下文本节点延迟导致的偶发超时。
