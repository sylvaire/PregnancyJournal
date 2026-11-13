# A02 可访问性与极端配置矩阵（2026-10-22）

目标：补齐 `docs/剩余任务验收清单.md` 第 15 行 A02「全页面空/错/加载、大字体 200%、深色、TalkBack、旋转」与第 36 行 E01 中「200% 字体 + 深色 + 横屏组合，资料/长表单仍未验收」的遗留；先覆盖 E01 明确记录失败/未验收的**资料列表**与**长表单（孕期档案 / 产检保存）**路径。

## 设备与配置

- 设备：`PJ_API36_16K`（emulator-5556），SDK 36、model `sdk_gphone16k_x86_64`、x86_64、`PAGE_SIZE=16384`、DDR/内存约 2.5 GiB（软渲染、无快照）。
- 极端配置（`device-config.ps1 -Mode extreme`）：
  - `settings put system font_scale 2.0`（系统字体 200%）
  - `cmd uimode night yes`（深色）
  - `settings put system accelerometer_rotation 0` + `settings put system user_rotation 1`
  - `cmd window user-rotation -d 0 lock 1`（API36 上必须带 `-d 0`，否则该命令无效、界面仍竖屏）
  - 校验：`cur=2400x1080`、`ROTATION_90`、`mFixedToUserRotation=true`、`USER_ROTATION_LOCKED`
- 基线配置（`device-config.ps1 -Mode baseline`）：font_scale 1.0、浅色、竖屏、`cmd window user-rotation -d 0 free`（校验 `cur=1080x2400`）。
- 注意：仅写 `settings put system user_rotation` 在 API 36 不会真正旋转，必须用 `cmd window user-rotation -d 0 lock 1`（早期尝试只写 settings 或漏 `-d 0` 时界面仍竖屏，导致 run5/run6 的 8 项被 `assumeTrue` 整体跳过）；模拟器重启会重置字体/深色/旋转，未重设时用例整体跳过（**skip 不等于通过**）。

## 命令（原文）

```powershell
$env:GRADLE_USER_HOME='<工作区>\.gradle-user'
adb -s emulator-5556 shell settings put system font_scale 2.0
adb -s emulator-5556 shell cmd uimode night yes
adb -s emulator-5556 shell settings put system accelerometer_rotation 0
adb -s emulator-5556 shell settings put system user_rotation 1
adb -s emulator-5556 shell cmd window user-rotation -d 0 lock 1
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=cn.yunji.journal.ExtremeLayoutMatrixTest" --console=plain
```

（`connectedDebugAndroidTest` 不支持 `--tests`，只能用 `-Pandroid.testInstrumentationRunnerArguments.class`。）

## 结果

**通过运行**：`device-matrix-145748.log`（14:57:48 启动），`Starting 8 tests` → `8/8 completed (0 failed)`，`BUILD SUCCESSFUL in 1m8s`，退出码 0；JUnit：`junit-device-extreme.xml` = `tests=8 failures=0 errors=0 skipped=0 time=57.314`。

| 用例 | 覆盖 | 耗时 |
|---|---|---|
| t1 四入口 + 首页长列表 | 档案/首页/记录/产检四个一级入口可达，首页列表在 200% 字体+横屏下可滚动、不截断 | 7.37s |
| t2 孕期编辑器 | 编辑器表单在大字体+横屏下可滚动可达 | 5.44s |
| t3 创建孕期保存 | 长表单填名→保存→回到列表并显示新档案 | 5.57s |
| t4 创建孕期长表单保存 | 极端键盘遮挡下：输入→`hideKeyboard()`→滚到「保存档案」→保存→数据库出现标题 | 6.51s |
| t5 创建产检保存 | 表单输入→收起键盘→保存→数据落库 | 5.27s |
| t6 资料列表预览与元数据 | 资料列表「极端布局报告」名称完整（无水平裁剪）、点开预览显示页数与元数据 | 9.68s |
| t7 日常记录编辑器 | 大字体横屏下记录编辑器可编辑、底部保存按钮完全可见 | 7.05s |
| t8 TalkBack 语义基础 | 四入口与资料列表关键控件具备 contentDescription 且可聚焦（语义树断言，不验证读屏语音） | 9.11s |

截图（`screens/`，运行中拉取）：`t1-home-list.png`、`t1-profiles-root-list.png`、`t1-records-hub-list.png`、`t1-visits-list.png`、`t3-pregnancy-editor.png`、`t4-editor-filled.png`、`t6-documents-list.png`、`t6-document-preview.png`、`t7-journal-editor.png`。截图目录在测试结束、应用被卸载时会被系统清理，必须在运行中 `adb pull`；t2/t5/t8 未产出截图（仅日志与断言）。

## 迭代中发现并修复的问题（均属测试/夹具缺陷，未确认产品缺陷）

1. **t4 活锁（run2 阻塞 15 分钟直到进程被杀）**：200% 字体 + 横屏时输入法占据大半屏，`performScrollToNode(hasText("保存档案"))` 在持续产生的帧上永不 idle。修法：输入后先 `hideKeyboard()` 再滚动（t7 已有同类先例），并在 `performScrollToNode` 前断言输入框已写入；保存标题断言改为包含实际标题。
2. **长表单默认标题导致误判**：`PregnancyForm.title` 默认值为「我的孕期」，`performTextInput` 会追加为「极端布局新档案我的孕期」。修法：改用 `performTextReplacement`。
3. **t6 断言误报**：原 `assertNoTruncation` 用 `didOverflowWidth` 判断裁剪，但 Compose 中该标志表示「段落宽度超过 **布局约束**」，而 `Text` 节点宽度可能小于约束（如 `size=445x111`，`paragraph=2040x111`），造成「文字完整却报截断」。修法：仅在「节点用满约束宽度且 didOverflowWidth」时判水平裁剪，垂直裁剪用 `size.height + 1 < paragraph.height`，最大行数用 `didExceedMaxLines`。
4. **t1 断言时机**：首页标题「今天，慢慢来」只在首页 tab 内存在，切到其它 tab 后 `nodes=0`。修法：把标题断言移入对应 tab 的遍历分支。
5. **t3 惰性列表**：滚到列表末尾后该节点已被回收，`nodes=0`。修法：断言移入惰性列表的逐项遍历中就地执行。
6. **环境故障（非产品问题）**：2.5 GiB 内存下模拟器 `system_server` 反复重启（`cmd: Can't find service: settings`、bootanim 循环、`Zygote: ... signal 9`），冷启动 AVD（`-no-snapshot-load`）+ 清理遗留 adb 进程后恢复；遗留 `adb logcat` 进程会锁定重定向文件，日志改用时间戳文件名。

变更文件：`app/src/androidTest/java/cn/yunji/journal/ExtremeLayoutMatrixTest.kt`（测试文件，424→约 470 行）；`docs/verification/2026-10-22-A02-ui-matrix/device-config.ps1`（极端/基线配置脚本）。本轮未修改产品代码。

## 覆盖与未覆盖

- 已覆盖：资料列表与预览、孕期/产检长表单、日常记录编辑器、四入口与首页列表、关键控件语义节点检查，在「200% 字体 + 深色 + 横屏」组合下 8/8 通过。
- 未覆盖：TalkBack 实际读屏语音与焦点顺序；深色模式对比度数值（未做对比度量化）；E01 矩阵中其它页面（服药、就诊、时间线、药品方案、导出/分享等）的极端配置；字体 200% 下的实际键盘遮挡可用性（本轮观察到输入法占据大半屏、保存按钮在屏外，用户需手动收起键盘——记录为可用性观察，未做界面修改）；真机/厂商 ROM/OEM 后台限制；最低 API 26（无镜像，E01 环境阻塞）。

## 证据文件

| 文件 | 说明 |
|---|---|
| `device-config.ps1` | 极端/基线配置与校验脚本 |
| `device-matrix-145748.log` | 通过运行完整输出（8/8） |
| `junit-device-extreme.xml` | 通过运行的 JUnit XML |
| `device-info.txt` | 运行后捕获的设备信息（SDK/模型/PAGE_SIZE/字体/深色/旋转/window displays） |
| `logcat-pass-excerpt.log` | `ExtremeMatrix` 步骤日志与崩溃关键字摘录 |
| `device-extreme-run1.log` / `run2.log` | 首轮 8 项 2 过 6 败；次轮 t4 挂死直至进程被杀（低内存/UTP 中止） |
| `device-matrix-run3.log` / `run4.log` / `run5.log` | 断言修复迭代（5/8、7/8、8 跳过=配置被重置） |
| `device-t4-isolated.log` / `device-t4b-isolated.log` / `device-t4c-isolated.log` / `device-t4-t6.log` | t4 活锁复现、修复后复跑、默认标题追加、t4+t6 联跑 |
| `device-t6-dbg.log` | t6 断言误报排查 |
| `shot-t4-hang.png` / `shot-t4-hang-2.png` | t4 活锁现场截图（输入法遮挡、保存按钮不可见） |
| `screens/` | 通过运行的 9 张现场截图 |
| `compile2.log` / `compile3.log` | `compileDebugAndroidTestKotlin` 编译输出 |
| `jvm-full.log` | 同期 `:app:testDebugUnitTest` 全量输出 |

## 结论

A02「资料/长表单 + 200% 字体/深色/横屏」矩阵在目标 API36 16 KiB 页模拟器上 **8/8 通过（0 失败 0 跳过）**；E01 记录的 34 项组合中本次闭环了资料与长表单子集，其余页面矩阵与真机/OEM/最低 API 26 仍保留为未验收项。测试后设备已恢复基线配置并交由 D03/D07 设备验证使用。
