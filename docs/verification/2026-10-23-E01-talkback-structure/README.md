# E01 TalkBack 导航结构定向检查

日期：2026-10-23。设备：项目专用 `PJ_API36_16K` 模拟器（API36）；原设置 `accessibility_enabled=0`、`enabled_accessibility_services=null`。

临时启用系统已安装的 `com.google.android.marvin.talkback/.TalkBackService` 后，`dumpsys accessibility` 确认服务为 `Bound services`，触摸探索为 1。新增 `TalkBackNavigationDeviceTest.kt`，在真实 TalkBack 启用时以 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` 获取无障碍树，验证首页「今天 → 产检 → 记录 → 我的」四个可见标签的树顺序和逐一获得无障碍焦点。`talkback-enabled.log`：**1/1 通过**。

关闭服务后复核：`enabled_accessibility_services=null`、`accessibility_enabled=0`，绑定服务为空；`talkback-disabled.log` 显示该专用用例按前置条件跳过（`-4`），不会在普通矩阵中假装完成 TalkBack 实测。

首次开启时系统弹出 TalkBack 通知权限提示；结束后已撤销该次授予并清除 `USER_SET` 标记，复核 `POST_NOTIFICATIONS: granted=false`，避免改变模拟器的长期权限基线。

边界：曾尝试从仪器环境注入 TalkBack 滑动手势，焦点未移动；无法据此判定应用缺陷，未将该断言保留为验收。当前证据只证明服务实际启用时的无障碍树顺序与节点可聚焦；朗读内容、真实手势逐项焦点移动、其他页面读屏与手机/OEM 仍未验收。
