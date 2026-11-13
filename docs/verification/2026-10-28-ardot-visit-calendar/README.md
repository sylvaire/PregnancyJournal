# N20 产检日历对齐续作

- 日期：2026-10-28；阶段：M6；设计依据：Ardot 文件 `737849992357132` 的 N20-final 画板，本地截图 `../2026-10-25-ardot-visual/screens/N20-final.png`。本次未修改 Ardot 文件。
- 实现：月份标题与“切换月份”移到日历卡上方；上月/本月/下月操作按需展开；日期格高度从 48dp 收为 36dp，卡片间距缩小；日历态“新建产检预约”固定在底部导航上方，内容列表留出空间，避免遮住最后一条预约。
- 产物：`app/src/main/java/cn/yunji/journal/feature/PrenatalScreens.kt`；测试 `app/src/androidTest/java/cn/yunji/journal/NavigationExperienceTest.kt`；[模拟器截图](screens/calendar.png)。
- 验证：`assembleDebug`、`testDebugUnitTest`、`assembleDebugAndroidTest` 成功；API36 模拟器 `calendarMonthAndCancellationRemainConsistentAfterReturning` 最终 **1/1** 通过，明确断言新建按钮在日历首屏可见，并覆盖月份切换、取消预约、返回列表。截图已查看，按钮确实可见，预约行和日历没有被固定按钮盖住。
- 设计差异：N20-final 画板的 2026 年 10 月日期列顺序错误；应用保留正确的星期四为 10 月 1 日。应用额外保留列表/日历切换及“全部”筛选，以便访问现有预约列表和全部状态。
- 遗留：小屏、字体放大、深色、多个预约、跨月选择日期、键盘/系统栏和小米 11 Ultra 真机尚未验收。N20 不能据此视为所有状态完成。
