# C05 自定义提醒增量验证（2026-10-23）

## 实现范围

- schema14 新增孕期关联的加密 `custom_reminders` 表，保留 schema13→14 自动迁移；用户可在提醒设置页创建、编辑、删除一次/每天/每周的本地提醒，重复提醒可设置结束日期。
- 调试版标记为 `0.26.0-dev`（versionCode 27），便于与先前 schema13 包区分并执行保留数据覆盖安装。
- 未来7天投影和 AlarmManager 复用既有重建、时区处理、隐私通知链路；提醒键包含日期与业务修订号，编辑后旧回调失效。单次提醒要求未来时间。
- 逻辑快照包含自定义提醒；关联/内容校验、隔离恢复、读回校验及恢复预览数量均已接入；孕期彻底删除时清理关联提醒。

## 已执行验证

- 离线 `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`：成功；JVM **342/342**、0 失败/跳过；lint **0 错误、25 警告**。后续表单默认时间/布局微调及 versionCode27 修改再运行 `assembleDebug :app:testDebugUnitTest :app:lintDebug` 成功。编译生成 `app/schemas/cn.yunji.journal.core.database.JournalDatabase/14.json` 和 Room 13→14 `CREATE TABLE` 自动迁移实现。
- API36/16KiB 专用模拟器 `emulator-5556`，保留数据覆盖安装 debug 与测试包；`ReminderSchedulerTest,IsolatedRestoreTest` **9/9 通过**，含自定义提醒增删改、旧修订键失效、加密归档恢复后逐项读回。
- APK 包元信息核对为 versionCode27 / `0.26.0-dev`。最终包保留数据覆盖安装到模拟器并启动 `cn.yunji.journal/.MainActivity`，进程保持运行（PID 21538）；只证明启动，不证明旧 schema13 库迁移成功。
- 后续把现有迁移夹具扩展到 schema1–13：用 schema13 的导出建表语句生成真实 SQLCipher 加密库，再由 schema14 代码打开，逐项核对旧档案、草稿、产检资料、药品/方案、服药、测量及空自定义提醒表；`DocumentImportTest#schemaOneThroughThirteenUpgradeKeepsExistingRecordsAndDrafts` **1/1 通过**。此前“仅证明启动”的限制由此专项补足。
- `CustomReminderUiTest#createEditAndDeleteFromReminderSettings` 使用独立加密库，走“我的→提醒设置”的实际 Compose 界面，验证新建每日提醒、编辑标题/修订号、确认删除与数据库结果，**1/1 通过**；夹具清理其数据库。
- `ReminderSchedulerTest#customReminderEditReplacesRegisteredAlarmAndDeleteCancelsIt` 在系统 AlarmManager 中核对旧 `PendingIntent` 被取消、新键已注册、删除后无闹钟，**1/1 通过**。
- `CustomReminderDeliveryTest#scheduledCustomReminderPostsPrivateNotificationAtDueTime` 使用应用真实加密库创建合成自定义提醒，精确系统闹钟到点后经广播产生私密系统通知，标题/正文为通用文字，**1/1 通过**（19.888 秒）；合成记录与通知由测试清理。测试时将专用模拟器精确闹钟 app-op 临时设为 allow，结束后确认恢复 `default`；通知列表未发现合成提醒残留。
- 新增上述测试后离线 `assembleDebug / assembleDebugAndroidTest / testDebugUnitTest / lintDebug` 成功；JVM 342/342，lint 0 错误、25 警告。
- 在精确闹钟 app-op 为 `default` 且调度器判定 `exact=false` 时，`CustomReminderDeliveryTest#inexactCustomReminderStillDeliversWithExactAlarmDenied` 实际经 AlarmManager→广播→私密通知，**连续两轮各 1/1 通过**；第二轮计划时刻与系统通知 `postTime` 差 **+30 ms**。这只是专用模拟器单次样本，不代表其他系统/省电条件的准点保证。
- 新增系统日期/时间选择器与每周结束日期的实际 UI 测试。首次编译缺少 Espresso 平台控件测试依赖，已在版本目录与 androidTest 中补齐。首轮定位“提醒时间”匹配背景标题与按钮而失败；改为点击可交互按钮。随后程序化设置系统时间控件出现回填时序不稳：单项通过、组内一轮失败；测试在设置控件后清除焦点并等待 Compose 回填。最终默认配置整组 `CustomReminderUiTest` **3/3 通过**，包括空标题/过期单次提醒保留表单提示、日期时间/结束日期选择并正确落库。
- 200% 字体+深色+横屏专项首轮被输入法遮挡日期按钮；收键盘并滚动后，横排重复选项未可靠选中，结束日期入口缺失。将重复规则改为纵向单选项，并在打开系统选择器前清除输入焦点；最终相同配置的选择器/每周结束日期路径 **1/1 通过**，恢复基线后整组 UI 再次 **3/3 通过**。测试脚本结束后读回 font_scale=1.0、浅色、竖屏、精确闹钟 app-op=`default`，未见合成通知残留。
- 本轮最终 `assembleDebug / assembleDebugAndroidTest / testDebugUnitTest / lintDebug` 构建成功；JVM **342/342**，lint **0 错误、26 警告**。
- 通知权限拒绝专项：宿主临时撤销专用 API36/16KiB 模拟器的 `POST_NOTIFICATIONS`，运行 `CustomReminderDeliveryTest#deniedNotificationPermissionKeepsCustomReminderWithoutPosting` **1/1 通过**。合成自定义提醒仍在加密库及调度键中；在权限拒绝状态调用实际交付逻辑返回未投递、无对应系统通知，也未错误标记已送达。测试删除合成提醒/档案并重建；宿主随即恢复通知权限，精确闹钟 app-op 仍为 `default`。这是交付逻辑专项，不等同于系统闹钟到点、权限重新允许后补发或锁屏视觉验收。新增测试后 `assembleDebugAndroidTest / testDebugUnitTest / lintDebug` 构建通过。
- 将同一专项扩展为权限拒绝→重新允许→真实系统闹钟到点：拒绝时合成提醒仍在加密库和调度键中，交付逻辑未发通知/未标记已送达；测试通过 `UiAutomation` 重新授予权限、重建提醒后，由非精确 AlarmManager 到点经接收器实际显示私密通知，并标记已送达，**1/1 通过**。本次单次样本通知 `postTime` 比计划时间晚 **22.258 秒**；不代表长期待机或其他设备延迟。合成提醒/档案/通知已清理，宿主确认通知权限恢复为 granted、精确闹钟 app-op=`default`，通知转储未检出合成标题。修改后 `assembleDebugAndroidTest / testDebugUnitTest / lintDebug` 构建通过。此测试在前一次权限拒绝的时间点手动调用交付逻辑以核对抑制行为，随后由系统闹钟验证重新授权后的实际投递；它不覆盖提醒已在拒绝状态下到点后再授权的补发策略。
- 新增到点后再授权专项 `CustomReminderDeliveryTest#lateGrantReplaysRecentlyMissedCustomReminder`：专用模拟器临时开放精确闹钟并撤销通知权限；真实系统闹钟到点时 `ReminderAlarmReceiver` 日志为 `delivery attempted posted=false`，无通知且未标记已送达。约 5 秒后测试重新授予权限并调用提醒重建，近期到点事件补投私密系统通知且标记已送达，**1/1 通过**；本次 `postTime` 比计划时间晚 **5.208 秒**。离线 `assembleDebugAndroidTest / testDebugUnitTest / lintDebug` 成功；合成提醒/档案/通知清理，宿主核对通知权限 granted、精确闹钟 app-op=`default`。专项证明短时间内主动重建可补投，尚不证明系统设置授权返回界面时一定自动重建、超过15分钟后的策略或OEM行为。

## 待验收与下一步

- 已完成 schema13 合成加密库迁移、界面增删改、日期/时间与结束日期选择、空标题/过期输入，以及 200% 字体+深色+横屏的提醒选择器路径；其他异常与无障碍组合、正式用户库与跨设备恢复仍需最终矩阵。
- 精确与非精确闹钟的自定义到点通知、权限拒绝时保留数据/不显示通知、到点前重新授权的实际投递、到点后约5秒授权并主动重建的补投均已在专用模拟器专项通过；系统设置授权返回界面的自动重建、超过15分钟后的处理、锁屏视觉、长期待机、OEM 和小米真机仍未验证。
- 一次/每天/每周之外的复杂重复规则不在当前 UI 范围；如需求扩大须另行定义。旧 Figma 提醒设置稿尚未同步新增区块。
