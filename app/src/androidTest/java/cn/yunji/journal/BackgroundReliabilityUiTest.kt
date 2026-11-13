package cn.yunji.journal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.data.BackgroundAssist
import org.junit.Test

/**
 * 提醒设置页「后台可靠性」卡片：状态文案与厂商入口在真实设备上的可见性。
 *
 * 卡片是否出现厂商入口取决于 `Build.MANUFACTURER`/`Build.BRAND`（见 [cn.yunji.journal.data.VendorBackgroundPolicy]），
 * 因此断言跟随 [BackgroundAssist.plan] 的结果分支，而不是写死小米。
 */
class BackgroundReliabilityUiTest : PageMatrixBaseTest() {

    @Test
    fun reminderSettingsShowsBackgroundReliabilityCard() {
        launchApp()
        openProfileRow("提醒设置")

        ui.onNodeWithText("后台可靠性").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("reminder-battery").assertExists()
        ui.onNodeWithTag("open-app-details").assertExists()

        val plan = BackgroundAssist.plan()
        if (plan != null) {
            ui.onNodeWithTag("reminder-vendor-hint").performScrollTo().assertExists()
            ui.onNodeWithTag("open-autostart").assertExists()
        } else {
            ui.onNodeWithText("未识别到已知厂商限制；如提醒被延迟，请在系统设置中允许孕迹后台运行。").assertExists()
        }

        val batteryOk = BackgroundAssist.ignoringBatteryOptimizations(ApplicationProvider.getApplicationContext())
        if (batteryOk) {
            ui.onNodeWithText("已加入电池优化白名单，系统不会因省电冻结提醒").assertExists()
        } else {
            ui.onNodeWithText("未加入电池优化白名单；系统省电可能推迟或跳过提醒").assertExists()
            ui.onNodeWithTag("request-battery-ignore").assertExists()
        }
    }
}
