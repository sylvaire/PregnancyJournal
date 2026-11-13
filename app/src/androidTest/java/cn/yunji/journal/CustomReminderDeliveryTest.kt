package cn.yunji.journal

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room3.Transactor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.yunji.journal.data.ReminderContract
import cn.yunji.journal.data.ReminderStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZonedDateTime

/** Real AlarmManager -> receiver -> encrypted business row -> private system notification. */
class CustomReminderDeliveryTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()

    @Test fun scheduledCustomReminderPostsPrivateNotificationAtDueTime() {
        verifyDelivery(exactRequired=true)
    }

    @Test fun inexactCustomReminderStillDeliversWithExactAlarmDenied() {
        verifyDelivery(exactRequired=false)
    }

    @Test fun lateGrantReplaysRecentlyMissedCustomReminder() {
        assumeTrue(Build.VERSION.SDK_INT==36 && Build.MODEL=="sdk_gphone16k_x86_64")
        assumeTrue(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_DENIED)
        val resources=ui.activity.runtime.resources()
        assumeTrue("此专项需要精确闹钟能力以确认权限拒绝期间已到点",resources.reminders.exactAllowed())
        val at=ZonedDateTime.now().plusSeconds(18).withNano(0)
        var pregnancy:String?=null
        var reminder:String?=null
        var key:String?=null
        try {
            pregnancy=runBlocking {resources.repository.savePregnancy(null,"合成迟授权测试",null,null,"","")}
            reminder=runBlocking {resources.repository.saveCustomReminder(null,requireNotNull(pregnancy),"合成错过提醒","仅限测试",
                at.toLocalDate(),at.toLocalTime(),"NONE",null)}
            val event=runBlocking {resources.reminders.events().single {it.targetType=="CUSTOM" && it.targetId==reminder}}
            key=event.key
            val scheduled=runBlocking {resources.reminders.rebuild()}
            assertTrue(scheduled.exact)
            assertTrue(ReminderStore(context).scheduledKeys().contains(event.key))
            val manager=context.getSystemService(NotificationManager::class.java)
            val waitUntil=event.triggerAt+5_000
            while(System.currentTimeMillis()<waitUntil)Thread.sleep(200)
            val missedDeadline=System.currentTimeMillis()+10_000
            while(!ReminderStore(context).wasMissed(event.key,event.triggerAt) && System.currentTimeMillis()<missedDeadline)Thread.sleep(100)
            assertTrue("拒绝权限时实际闹钟未登记未送达状态",ReminderStore(context).wasMissed(event.key,event.triggerAt))
            assertFalse("拒绝通知期间错误标记已送达",ReminderStore(context).wasDelivered(event.key,event.triggerAt))
            assertFalse("拒绝通知期间出现系统通知",manager.activeNotifications.any {it.id==ReminderContract.notificationId(event.key)})
            runBlocking {resources.reminders.rebuild()}
            assertFalse("拒绝权限时重建应取消已到点旧闹钟",ReminderStore(context).scheduledKeys().contains(event.key))
            assertTrue("拒绝权限时重建丢失未送达状态",ReminderStore(context).wasMissed(event.key,event.triggerAt))
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
            assertTrue(resources.reminders.notificationPermissionGranted())
            runBlocking {resources.reminders.rebuild()}
            val deadline=System.currentTimeMillis()+10_000
            var posted:android.service.notification.StatusBarNotification?=null
            while(posted==null && System.currentTimeMillis()<deadline) {
                posted=manager.activeNotifications.firstOrNull {it.id==ReminderContract.notificationId(event.key)}
                if(posted==null)Thread.sleep(100)
            }
            assertNotNull("到点后重新授权未补投近期提醒",posted)
            assertEquals("打开孕迹解锁后查看",posted?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertEquals(Notification.VISIBILITY_PRIVATE,posted?.notification?.visibility)
            assertTrue(ReminderStore(context).wasDelivered(event.key,event.triggerAt))
            assertFalse(ReminderStore(context).wasMissed(event.key,event.triggerAt))
            Log.i("CustomReminderDelivery","late grant catchup delayMs=${requireNotNull(posted).postTime-event.triggerAt}")
        } finally {
            key?.let {context.getSystemService(NotificationManager::class.java).cancel(ReminderContract.notificationId(it))}
            runBlocking {
                reminder?.let {runCatching {resources.repository.deleteCustomReminder(it)}}
                pregnancy?.let {parent->
                    resources.database.useConnection(isReadOnly=false){connection->
                        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE){
                            connection.usePrepared("DELETE FROM pregnancy_date_revisions WHERE pregnancyId=?"){it.bindText(1,parent);it.step()}
                            connection.usePrepared("DELETE FROM change_log WHERE entityId=?"){it.bindText(1,parent);it.step()}
                            reminder?.let {id->connection.usePrepared("DELETE FROM change_log WHERE entityId=?"){it.bindText(1,id);it.step()}}
                            connection.usePrepared("DELETE FROM pregnancies WHERE id=?"){it.bindText(1,parent);it.step()}
                        }
                    }
                }
                resources.reminders.rebuild()
            }
        }
    }

    @Test fun deniedNotificationPermissionKeepsCustomReminderWithoutPosting() {
        assumeTrue(Build.VERSION.SDK_INT==36 && Build.MODEL=="sdk_gphone16k_x86_64")
        assumeTrue(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_DENIED)
        val resources=ui.activity.runtime.resources()
        val at=ZonedDateTime.now().plusSeconds(30).withNano(0)
        var pregnancy:String?=null
        var reminder:String?=null
        var key:String?=null
        try {
            pregnancy=runBlocking {resources.repository.savePregnancy(null,"合成权限拒绝测试",null,null,"","")}
            reminder=runBlocking {resources.repository.saveCustomReminder(null,requireNotNull(pregnancy),"合成受限事项","仅保留在应用内",
                at.toLocalDate(),at.toLocalTime(),"NONE",null)}
            val event=runBlocking {resources.reminders.events().single {it.targetType=="CUSTOM" && it.targetId==reminder}}
            key=event.key
            val scheduled=runBlocking {resources.reminders.rebuild()}
            assertTrue(ReminderStore(context).scheduledKeys().contains(event.key))
            assertFalse(resources.reminders.notificationPermissionGranted())
            assertFalse(runBlocking {resources.reminders.deliverAlarm(event,scheduled.generation)})
            assertFalse(ReminderStore(context).wasDelivered(event.key,event.triggerAt))
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.id==ReminderContract.notificationId(event.key)
            })
            assertNotNull(runBlocking {resources.database.journalDao().customReminder(requireNotNull(reminder))})
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
            assertTrue(resources.reminders.notificationPermissionGranted())
            val restored=runBlocking {resources.reminders.rebuild()}
            assertTrue(ReminderStore(context).scheduledKeys().contains(event.key))
            assertFalse(ReminderStore(context).wasDelivered(event.key,event.triggerAt))
            val manager=context.getSystemService(NotificationManager::class.java)
            val deadline=System.currentTimeMillis()+150_000
            var posted:android.service.notification.StatusBarNotification?=null
            while(posted==null && System.currentTimeMillis()<deadline) {
                posted=manager.activeNotifications.firstOrNull {it.id==ReminderContract.notificationId(event.key)}
                if(posted==null)Thread.sleep(200)
            }
            assertNotNull("重新允许通知后系统闹钟未投递自定义提醒",posted)
            assertEquals("打开孕迹解锁后查看",posted?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertEquals(Notification.VISIBILITY_PRIVATE,posted?.notification?.visibility)
            assertTrue(ReminderStore(context).wasDelivered(event.key,event.triggerAt))
            Log.i("CustomReminderDelivery","permission restored generation=${restored.generation} delayMs=${requireNotNull(posted).postTime-event.triggerAt}")
        } finally {
            key?.let {context.getSystemService(NotificationManager::class.java).cancel(ReminderContract.notificationId(it))}
            runBlocking {
                reminder?.let {runCatching {resources.repository.deleteCustomReminder(it)}}
                pregnancy?.let {parent->
                    resources.database.useConnection(isReadOnly=false){connection->
                        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE){
                            connection.usePrepared("DELETE FROM pregnancy_date_revisions WHERE pregnancyId=?"){it.bindText(1,parent);it.step()}
                            connection.usePrepared("DELETE FROM change_log WHERE entityId=?"){it.bindText(1,parent);it.step()}
                            reminder?.let {id->connection.usePrepared("DELETE FROM change_log WHERE entityId=?"){it.bindText(1,id);it.step()}}
                            connection.usePrepared("DELETE FROM pregnancies WHERE id=?"){it.bindText(1,parent);it.step()}
                        }
                    }
                }
                resources.reminders.rebuild()
            }
        }
    }

    private fun verifyDelivery(exactRequired:Boolean) {
        assumeTrue(Build.VERSION.SDK_INT==36 && Build.MODEL=="sdk_gphone16k_x86_64")
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        val resources=ui.activity.runtime.resources()
        assumeTrue("运行前请把模拟器精确闹钟权限设为测试目标状态",resources.reminders.exactAllowed()==exactRequired)
        val at=ZonedDateTime.now().plusSeconds(if(exactRequired)18 else 10).withNano(0)
        var pregnancy:String?=null
        var reminder:String?=null
        var key:String?=null
        try {
            pregnancy=runBlocking {resources.repository.savePregnancy(null,"合成自定义到点测试",null,null,"","")}
            reminder=runBlocking {resources.repository.saveCustomReminder(null,requireNotNull(pregnancy),"合成到点事项","不进入通知内容",
                at.toLocalDate(),at.toLocalTime(),"NONE",null)}
            val event=runBlocking {resources.reminders.events().single {it.targetType=="CUSTOM" && it.targetId==reminder}}
            key=event.key
            val scheduled=runBlocking {resources.reminders.rebuild()}
            assertEquals(exactRequired,scheduled.exact)
            assertTrue(ReminderStore(context).scheduledKeys().contains(event.key))
            val manager=context.getSystemService(NotificationManager::class.java)
            val id=ReminderContract.notificationId(event.key)
            val deadline=System.currentTimeMillis()+(if(exactRequired)50_000 else 150_000)
            var posted:android.service.notification.StatusBarNotification?=null
            while(posted==null && System.currentTimeMillis()<deadline) {
                posted=manager.activeNotifications.firstOrNull {it.id==id}
                if(posted==null)Thread.sleep(200)
            }
            assertNotNull("自定义提醒到点后未出现系统通知",posted)
            assertEquals("孕迹提醒",posted?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertEquals("打开孕迹解锁后查看",posted?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertEquals(Notification.VISIBILITY_PRIVATE,posted?.notification?.visibility)
            assertFalse(posted?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.contains("合成到点事项") == true)
            val delivered=requireNotNull(posted)
            Log.i("CustomReminderDelivery","exact=$exactRequired scheduledAt=${event.triggerAt} postedAt=${delivered.postTime} delayMs=${delivered.postTime-event.triggerAt}")
        } finally {
            key?.let {context.getSystemService(NotificationManager::class.java).cancel(ReminderContract.notificationId(it))}
            runBlocking {
                reminder?.let {runCatching {resources.repository.deleteCustomReminder(it)}}
                pregnancy?.let {parent->
                    resources.database.useConnection(isReadOnly=false){connection->
                        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE){
                            connection.usePrepared("DELETE FROM pregnancy_date_revisions WHERE pregnancyId=?"){it.bindText(1,parent);it.step()}
                            connection.usePrepared("DELETE FROM change_log WHERE entityId=?"){it.bindText(1,parent);it.step()}
                            reminder?.let {id->connection.usePrepared("DELETE FROM change_log WHERE entityId=?"){it.bindText(1,id);it.step()}}
                            connection.usePrepared("DELETE FROM pregnancies WHERE id=?"){it.bindText(1,parent);it.step()}
                        }
                    }
                }
                resources.reminders.rebuild()
            }
        }
    }
}
