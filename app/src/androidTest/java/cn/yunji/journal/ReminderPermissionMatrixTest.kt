package cn.yunji.journal

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.yunji.journal.core.security.DeviceKeyStore
import cn.yunji.journal.data.SecurityStore
import cn.yunji.journal.data.ReminderNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Run twice with host-controlled permission; never changes a phone's settings. */
class ReminderPermissionMatrixTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun settingsReflectActualNotificationPermissionAfterFreshLaunch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(Build.VERSION.SDK_INT == 36 && Build.MODEL == "sdk_gphone16k_x86_64")
        assumeTrue(!DeviceKeyStore(context).strict() && !SecurityStore(context).lockEnabled())
        val expected = InstrumentationRegistry.getArguments().getString("matrixPermission")
        assumeTrue("指定 matrixPermission=denied|allowed 后才运行权限矩阵",expected in setOf("denied", "allowed"))
        val allowed = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        assertEquals("host must set the requested permission before launching Main", expected == "allowed", allowed)

        ui.waitUntil(10_000) { ui.onAllNodes(hasText("我的")).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("我的").performClick()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("提醒设置"))
        ui.onNodeWithText("提醒设置").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("reminder-permission").fetchSemanticsNodes().isNotEmpty() }
        if (allowed) {
            ui.onNodeWithTag("reminder-permission").assertTextEquals("已允许显示通知")
            ui.onNodeWithTag("reminder-test").assertExists()
        } else {
            ui.onNodeWithTag("reminder-permission").assertTextEquals("未允许；只能使用应用内待办")
            ui.onNodeWithTag("request-notification").assertExists()
            ui.onNodeWithText("系统通知设置").assertExists()
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val notifier = ReminderNotifier(context)
        manager.cancel(ReminderNotifier.TEST_ID)
        try {
            val posted = notifier.postTest()
            assertEquals("notification delivery must match the actual permission", allowed, posted)
            if (allowed) {
                ui.waitUntil(5_000) { manager.activeNotifications.any { it.id == ReminderNotifier.TEST_ID } }
                assertTrue(manager.activeNotifications.any { it.id == ReminderNotifier.TEST_ID })
            } else {
                assertFalse(manager.activeNotifications.any { it.id == ReminderNotifier.TEST_ID })
            }
        } finally {
            manager.cancel(ReminderNotifier.TEST_ID)
        }
    }
}
