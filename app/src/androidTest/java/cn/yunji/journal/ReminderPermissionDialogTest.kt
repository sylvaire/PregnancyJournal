package cn.yunji.journal

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room3.Transactor
import cn.yunji.journal.core.security.DeviceKeyStore
import cn.yunji.journal.data.SecurityStore
import cn.yunji.journal.data.ReminderNotifier
import cn.yunji.journal.data.ReminderStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID

/** The host resets permission flags before each run and restores them afterwards. */
class ReminderPermissionDialogTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun permissionButton(allow: Boolean): AccessibilityNodeInfo {
        val end=SystemClock.elapsedRealtime()+15_000
        while(SystemClock.elapsedRealtime()<end){
            val root=automation.rootInActiveWindow
            if(root?.packageName?.toString()?.contains("permissioncontroller")==true){
                val queue=java.util.ArrayDeque<AccessibilityNodeInfo>();queue.add(root)
                while(queue.isNotEmpty()){
                    val node=queue.removeFirst()
                    val id=node.viewIdResourceName.orEmpty()
                    if(node.isVisibleToUser && id.endsWith(if(allow) ":id/permission_allow_button" else ":id/permission_deny_button"))return node
                    repeat(node.childCount){node.getChild(it)?.let(queue::add)}
                }
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("系统通知权限弹窗或${if(allow) "允许" else "拒绝"}按钮未出现")
    }

    private fun toggleSystemNotificationSwitch() {
        val deadline=SystemClock.elapsedRealtime()+15_000
        var row:AccessibilityNodeInfo?=null
        while(SystemClock.elapsedRealtime()<deadline && row==null){
            val root=automation.rootInActiveWindow
            if(root?.packageName?.toString()=="com.android.settings"){
                val queue=java.util.ArrayDeque<AccessibilityNodeInfo>();queue.add(root)
                while(queue.isNotEmpty()){
                    val node=queue.removeFirst()
                    if(node.viewIdResourceName=="com.android.settings:id/switch_text" &&
                        node.text?.toString()?.startsWith("All ")==true && node.text?.toString()?.endsWith(" notifications")==true){
                        row=node;break
                    }
                    repeat(node.childCount){node.getChild(it)?.let(queue::add)}
                }
            }
            if(row==null)SystemClock.sleep(100)
        }
        var target:AccessibilityNodeInfo?=requireNotNull(row){"系统应用通知总开关未出现"}
        while(target!=null && !target.isClickable)target=target.parent
        assertTrue("系统通知设置行不可点击",requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun allowedUserCanOpenSystemNotificationSettings() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(Build.VERSION.SDK_INT==36 && Build.MODEL=="sdk_gphone16k_x86_64")
        assumeTrue(!DeviceKeyStore(context).strict() && !SecurityStore(context).lockEnabled())
        assumeTrue(InstrumentationRegistry.getArguments().getString("settingsDecision")=="open-allowed")
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        ui.waitUntil(10_000){ui.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("我的").performClick()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("提醒设置"))
        ui.onNodeWithText("提醒设置").performClick()
        ui.onNodeWithTag("reminder-permission").assertTextEquals("已允许显示通知")
        ui.onNodeWithText("系统通知设置").performClick()
        ui.waitUntil(10_000){automation.rootInActiveWindow?.packageName?.toString()=="com.android.settings"}
        assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        ui.waitUntil(10_000){ui.onAllNodesWithText("已允许显示通知").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("reminder-permission").assertTextEquals("已允许显示通知")
    }

    @Test fun reminderPageReflectsPermissionAfterExternalSettingsRestart() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(Build.VERSION.SDK_INT==36 && Build.MODEL=="sdk_gphone16k_x86_64")
        assumeTrue(!DeviceKeyStore(context).strict() && !SecurityStore(context).lockEnabled())
        val decision=InstrumentationRegistry.getArguments().getString("settingsDecision")
        assumeTrue(decision in setOf("state-deny","state-allow"))
        val allowed=decision=="state-allow"
        assertEquals(if(allowed)PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        ui.waitUntil(10_000){ui.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("我的").performClick()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("提醒设置"))
        ui.onNodeWithText("提醒设置").performClick()
        ui.onNodeWithTag("reminder-permission").assertTextEquals(if(allowed)"已允许显示通知" else "未允许；只能使用应用内待办")
        ui.onNodeWithText("系统通知设置").assertExists()
        if(!allowed)return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.cancel(ReminderNotifier.TEST_ID)
        try {
            ui.onNodeWithTag("reminder-test").performClick()
            ui.waitUntil(5_000){manager.activeNotifications.any {it.id==ReminderNotifier.TEST_ID}}
        } finally {manager.cancel(ReminderNotifier.TEST_ID)}
    }

    @Test fun systemSettingsGrantRefreshesReminderPageAndPostsTestNotification() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(Build.VERSION.SDK_INT==36 && Build.MODEL=="sdk_gphone16k_x86_64")
        assumeTrue(!DeviceKeyStore(context).strict() && !SecurityStore(context).lockEnabled())
        assumeTrue(InstrumentationRegistry.getArguments().getString("settingsDecision")=="allow")
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        ui.waitUntil(10_000){ui.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("我的").performClick()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("提醒设置"))
        ui.onNodeWithText("提醒设置").performClick()
        ui.onNodeWithTag("reminder-permission").assertTextEquals("未允许；只能使用应用内待办")
        ui.onNodeWithText("系统通知设置").performClick()
        toggleSystemNotificationSwitch()
        val grantedAt=SystemClock.elapsedRealtime()+10_000
        while(SystemClock.elapsedRealtime()<grantedAt && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            SystemClock.sleep(100)
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        ui.waitUntil(10_000){ui.onAllNodesWithText("已允许显示通知").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("reminder-permission").assertTextEquals("已允许显示通知")
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.cancel(ReminderNotifier.TEST_ID)
        try {
            ui.onNodeWithTag("reminder-test").performClick()
            ui.waitUntil(5_000){manager.activeNotifications.any {it.id==ReminderNotifier.TEST_ID}}
        } finally {manager.cancel(ReminderNotifier.TEST_ID)}
    }

    @Test fun systemDialogDecisionUpdatesReminderSettings() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(Build.VERSION.SDK_INT==36 && Build.MODEL=="sdk_gphone16k_x86_64")
        assumeTrue(!DeviceKeyStore(context).strict() && !SecurityStore(context).lockEnabled())
        val decision=InstrumentationRegistry.getArguments().getString("dialogDecision")
        assumeTrue("指定 dialogDecision=deny|allow 后才运行系统弹窗矩阵",decision in setOf("deny","allow"))
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        ui.waitUntil(10_000){ui.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("我的").performClick()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("提醒设置"))
        ui.onNodeWithText("提醒设置").performClick()
        ui.onNodeWithTag("reminder-permission").assertTextEquals("未允许；只能使用应用内待办")
        ui.onNodeWithTag("request-notification").performClick()
        val button=permissionButton(decision=="allow")
        assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val allowed=decision=="allow"
        ui.waitUntil(10_000){
            (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)==allowed &&
                ui.onAllNodesWithText(if(allowed) "已允许显示通知" else "未允许；只能使用应用内待办").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("reminder-permission").assertTextEquals(if(allowed) "已允许显示通知" else "未允许；只能使用应用内待办")
        if(allowed)ui.onNodeWithTag("reminder-test").assertExists() else ui.onNodeWithTag("request-notification").assertExists()
    }

    @Test fun userCanAllowAfterDenyingTheFirstSystemDialog() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(Build.VERSION.SDK_INT==36 && Build.MODEL=="sdk_gphone16k_x86_64")
        assumeTrue(!DeviceKeyStore(context).strict() && !SecurityStore(context).lockEnabled())
        assumeTrue(InstrumentationRegistry.getArguments().getString("dialogDecision")=="deny-then-allow")
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        ui.waitUntil(10_000){ui.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("我的").performClick()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("提醒设置"))
        ui.onNodeWithText("提醒设置").performClick()
        ui.onNodeWithTag("request-notification").performClick()
        assertTrue(permissionButton(false).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        ui.waitUntil(10_000){
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_DENIED &&
                ui.onAllNodesWithTag("request-notification").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("reminder-permission").assertTextEquals("未允许；只能使用应用内待办")
        ui.onNodeWithTag("request-notification").performClick()
        assertTrue(permissionButton(true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        ui.waitUntil(10_000){
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED &&
                ui.onAllNodesWithText("已允许显示通知").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("reminder-permission").assertTextEquals("已允许显示通知")
        ui.onNodeWithTag("reminder-test").assertExists()
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.cancel(ReminderNotifier.TEST_ID)
        try {
            assertTrue(ReminderNotifier(context).postTest())
            ui.waitUntil(5_000){manager.activeNotifications.any {it.id==ReminderNotifier.TEST_ID}}
        } finally {manager.cancel(ReminderNotifier.TEST_ID)}
    }

    @Test fun deniedPermissionKeepsVisitAndRegrantRebuildsItsAlarm() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(Build.VERSION.SDK_INT==36 && Build.MODEL=="sdk_gphone16k_x86_64")
        assumeTrue(!DeviceKeyStore(context).strict() && !SecurityStore(context).lockEnabled())
        val decision=InstrumentationRegistry.getArguments().getString("dialogDecision")
        assumeTrue(decision in setOf("visit-journey","visit-journey-timed","visit-journey-inexact"))
        val timed=decision!="visit-journey"
        val inexact=decision=="visit-journey-inexact"
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val resources=ui.activity.runtime.resources()
        if(timed)assumeTrue("设备精确闹钟状态与测试矩阵不符",resources.reminders.exactAllowed()!=inexact)
        val store=ReminderStore(context)
        val originallyEnabled=store.visitsEnabled()
        var pregnancyId:String?=null
        var visitId:String?=null
        var reminderKey:String?=null
        try {
            store.setVisitsEnabled(true)
            val reminderAt=if(timed)ZonedDateTime.now().plusMinutes(2).withSecond(0).withNano(0)
                else ZonedDateTime.now().plusDays(1).withHour(12).withMinute(0).withSecond(0).withNano(0)
            pregnancyId=runBlocking {resources.repository.savePregnancy(null,"权限矩阵合成孕期 ${UUID.randomUUID()}",null,null,"","")}
            visitId=runBlocking {
                resources.repository.saveVisit(null,requireNotNull(pregnancyId),reminderAt.toLocalDate().plusDays(1),LocalTime.of(14,0),"","权限矩阵合成产检","").also {
                    resources.repository.setVisitReminder(it,reminderAt.toLocalDate().toString(),reminderAt.toLocalTime().toString())
                }
            }
            val visit=requireNotNull(visitId)
            val expected=runBlocking {resources.reminders.events().single {it.targetType=="VISIT" && it.targetId==visit}}
            reminderKey=expected.key
            ui.waitUntil(10_000){ui.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("我的").performClick()
            ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("提醒设置"))
            ui.onNodeWithText("提醒设置").performClick()
            ui.onNodeWithTag("request-notification").performClick()
            assertTrue(permissionButton(false).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            ui.waitUntil(10_000){ui.onAllNodesWithTag("request-notification").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            assertEquals(visit,runBlocking {resources.repository.visit(visit)}?.id)
            assertTrue(runBlocking {resources.reminders.events()}.any {it.key==expected.key})
            ui.onNodeWithTag("request-notification").performClick()
            assertTrue(permissionButton(true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            ui.waitUntil(10_000){context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED}
            ui.onNodeWithTag("reminder-rebuild").performScrollTo().performClick()
            ui.waitUntil(10_000){store.scheduledKeys().contains(expected.key)}
            assertEquals(visit,runBlocking {resources.repository.visit(visit)}?.id)
            assertTrue(store.scheduledKeys().contains(expected.key))
            if(timed){
                val manager=context.getSystemService(NotificationManager::class.java)
                val id=cn.yunji.journal.data.ReminderContract.notificationId(expected.key)
                val deadline=reminderAt.toInstant().toEpochMilli()+(if(inexact)5*60_000L else 30_000L)
                while(System.currentTimeMillis()<deadline && manager.activeNotifications.none {it.id==id})SystemClock.sleep(200)
                val notification=manager.activeNotifications.firstOrNull {it.id==id}?.notification
                assertTrue("产检提醒到点后未由系统投递",notification!=null)
                if(inexact)android.util.Log.i("ReminderDeviceTest","inexact delayMs=${System.currentTimeMillis()-reminderAt.toInstant().toEpochMilli()}")
                assertEquals("孕迹提醒",notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
                assertEquals(android.app.Notification.VISIBILITY_PRIVATE,notification?.visibility)
            }
        } finally {
            reminderKey?.let {key -> context.getSystemService(NotificationManager::class.java)
                .cancel(cn.yunji.journal.data.ReminderContract.notificationId(key)) }
            pregnancyId?.let {parent -> runBlocking {
                resources.database.useConnection(isReadOnly=false){connection ->
                    connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE){
                        visitId?.let {id ->
                            connection.usePrepared("DELETE FROM change_log WHERE entityType='visit' AND entityId=?"){it.bindText(1,id);it.step()}
                            connection.usePrepared("DELETE FROM prenatal_visits WHERE id=?"){it.bindText(1,id);it.step()}
                        }
                        connection.usePrepared("DELETE FROM pregnancy_date_revisions WHERE pregnancyId=?"){it.bindText(1,parent);it.step()}
                        connection.usePrepared("DELETE FROM change_log WHERE entityType='pregnancy' AND entityId=?"){it.bindText(1,parent);it.step()}
                        connection.usePrepared("DELETE FROM pregnancies WHERE id=?"){it.bindText(1,parent);it.step()}
                    }
                }
                assertEquals(null,resources.repository.pregnancy(parent))
            } }
            store.setVisitsEnabled(originallyEnabled)
            runBlocking {resources.reminders.rebuild()}
        }
    }
}
