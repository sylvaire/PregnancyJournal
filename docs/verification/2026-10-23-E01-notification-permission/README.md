# E01 通知权限拒绝与恢复矩阵

日期：2026-10-23。设备：项目专用 `emulator-5556`，API36/16KiB。测试只通过宿主命令切换该模拟器的通知权限，不修改手机权限；测试后恢复此前的允许状态。

产物：`app/src/androidTest/java/cn/yunji/journal/ReminderPermissionMatrixTest.kt`。构建 `:app:assembleDebugAndroidTest` 成功。

执行方式：每次先停止应用，分别以 `pm revoke cn.yunji.journal android.permission.POST_NOTIFICATIONS` 和 `pm grant ...` 设置状态，再执行 `am instrument -w -r -e matrixPermission denied|allowed -e class cn.yunji.journal.ReminderPermissionMatrixTest cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner`。最后用 `pm grant` 恢复原状态并在 `dumpsys package` 中读回 `granted=true`。

结果：拒绝状态 `OK (1 test)`、状态码 -1；允许状态 `OK (1 test)`、状态码 -1；均没有跳过。拒绝状态断言应用内待办提示、请求权限和系统通知设置入口；允许状态断言已允许提示与发送测试通知入口。首轮宿主命令在拒绝状态通过后误判输出，未执行允许状态；修正结果判断后完整重跑两种状态。

首轮边界：初次运行只检查 UI，未发送通知；本轮其后已增加直接投递检查，结果见下。系统权限弹窗、已有待办数据和 OEM 后台策略仍未验证。

追加：测试增加 `ReminderNotifier.postTest()`。拒绝状态断言返回 false 且系统活动通知中无测试 ID；允许状态断言返回 true 且系统活动通知中出现测试 ID，finally 取消该通知。重新编译成功并完整重跑：两状态仍各 `OK (1 test)`、状态码 -1；权限最终读回 `granted=true`。边界更新为：已验证直接测试通知投递，但未验证系统弹窗、定时提醒触发、已有待办内容及 OEM 后台策略。
