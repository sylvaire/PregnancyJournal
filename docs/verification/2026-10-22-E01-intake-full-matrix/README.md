# E01 API36 全量服药保存失败复现

## 最终复核（2026-10-22）

- `full-semantic-click-diagnostic.log`：普通点击后记录数 0；对同一保存按钮执行语义点击后写入成功。该诊断证明保存数据和仓储路径可用，问题发生在测试的实际点击时序；此探针只用于定位，已从最终测试代码移除。
- `full-one-click-settled.log`：在服药编辑的输入和滚动后等待界面稳定、确认按钮可见，再实际点击，服药用例通过；但日常记录修改后立即点击出现同类超时，整套 302 项仍 1 失败。
- `navigation-class-settled.log`：两处输入后点击均同步后，`NavigationExperienceTest` **23/23 通过**。
- `full-two-clicks-settled.log`：一致版本应用包与测试包在专用 API36/16KiB 模拟器运行全量，**302 项执行、260 项通过、42 项按前置条件跳过、0 失败，`OK (302 tests)`**，耗时 529.866 秒；两处保存仍通过真实 `performClick`，没有改为语义点击或直接仓储写入。
- 最终源码移除了失败后写入测试库的诊断探针，只保留非破坏性的状态/界面输出；`:app:compileDebugAndroidTestKotlin` 再次通过。此最后清理只涉及失败分支，成功路径与全量运行版本相同。

结论范围：API36 当前自动化全量矩阵通过；真实手机输入法/OEM 手势体验与用户数据场景仍需单独验收，不能由模拟器矩阵替代。

日期：2026-10-22。专用 `PJ_API36_16K` 模拟器；应用包与仪器测试包均由当前工作区构建并覆盖安装。未在个人手机运行清库型测试。

- `full-current-apk.log`：302 项执行，1 项失败；`BackgroundReliabilityUiTest` 在当前应用包上定向复核 `OK (1 test)`，此前单独失败源于测试包更新后应用包仍是旧构建。
- `full-direct-save-diagnostic.log`：302 项执行，仍仅 `NavigationExperienceTest#scheduledIntakeEditDeleteUndoAndManualHistoryKeepOriginalPlan` 失败。失败时数据库服药记录数 0、`formReady=true`、`busy=false`、计划项 `ACTIVE`，同一 `IntakeForm` 直接调用 `IntakeRepository.save` 返回成功。编辑页仍显示剂量 `0.25` 和保存按钮。
- 两轮结果均说明全量发布门禁未通过；直接保存成功只把调查范围收窄到界面提交路径，不证明是输入法、点击事件或忙碌状态的哪一种竞争。

下一步：记录点击前后的输入法可见性与点击事件/错误事件，复现并修复根因后重跑完整 API36 矩阵；保留本次失败日志作回归对照。
