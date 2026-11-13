package cn.yunji.journal

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.yunji.journal.data.ReminderContract
import cn.yunji.journal.data.ReminderNotifier
import cn.yunji.journal.data.ReminderStore
import cn.yunji.journal.domain.ReminderEvent
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Debug-only TEST target goes through AlarmManager, BroadcastReceiver, generation check and notification channel. */
class ReminderNotificationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var scheduledKey: String? = null

    @After fun tearDown() {
        val key = scheduledKey ?: return
        context.getSystemService(NotificationManager::class.java).cancel(ReminderContract.notificationId(key))
        val manager = context.getSystemService(AlarmManager::class.java)
        val intent = android.content.Intent(context, cn.yunji.journal.data.ReminderAlarmReceiver::class.java)
            .setAction(ReminderContract.ACTION_ALARM).putExtra(ReminderContract.EXTRA_KEY, key)
        PendingIntent.getBroadcast(context, ReminderContract.notificationId(key), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { manager.cancel(it); it.cancel() }
    }

    @Test fun exactAlarmPostsPrivateNotificationThroughReceiver() {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        assumeTrue(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val manager = context.getSystemService(AlarmManager::class.java)
        assumeTrue(manager.canScheduleExactAlarms())
        val store = ReminderStore(context)
        val generation = store.nextGeneration()
        val key = "TEST:${UUID.randomUUID()}"
        scheduledKey = key
        val event = ReminderEvent(key = key, pregnancyId = "test", targetType = "TEST", targetId = key,
            triggerAt = System.currentTimeMillis() + 2_000, title = "孕迹提醒测试", body = "合成测试通知", publicBody = "孕迹提醒")
        val intent = ReminderContract.alarmIntent(context, event, generation)
        val pending = PendingIntent.getBroadcast(context, ReminderContract.notificationId(key), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, event.triggerAt, pending)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val targetId = ReminderContract.notificationId(key)
        val deadline = System.currentTimeMillis() + 20_000
        var found: android.app.Notification? = null
        while (System.currentTimeMillis() < deadline && found == null) {
            found = notificationManager.activeNotifications.firstOrNull { it.id == targetId }?.notification
            if (found == null) Thread.sleep(200)
        }
        assertNotNull("Reminder notification was not delivered", found)
        assertEquals("孕迹提醒", found?.extras?.getCharSequence("android.title")?.toString())
        assertEquals("打开孕迹解锁后查看", found?.extras?.getCharSequence("android.text")?.toString())
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, found?.visibility)
        ReminderNotifier(context).cancel(key)
    }

    @Test fun existingForegroundNotificationIsRedactedWithoutLosingAction() {
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        val notifier=ReminderNotifier(context)
        val key="TEST:${UUID.randomUUID()}";scheduledKey=key
        val id=ReminderContract.notificationId(key)
        val action=PendingIntent.getActivity(context,id,android.content.Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val original=android.app.Notification.Builder(context,ReminderNotifier.CHANNEL).setSmallIcon(cn.yunji.journal.R.drawable.ic_launcher)
            .setContentTitle("synthetic private title").setContentText("synthetic private body")
            .setStyle(android.app.Notification.BigTextStyle().bigText("synthetic private expanded body"))
            .addAction(android.app.Notification.Action.Builder(null,"已服用",action).build()).build()
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.notify(id,original)
        val postedDeadline=System.currentTimeMillis()+5_000
        while(manager.activeNotifications.none {it.id==id} && System.currentTimeMillis()<postedDeadline)Thread.sleep(50)
        assertTrue("Synthetic notification was not posted",manager.activeNotifications.any {it.id==id})
        notifier.redactActive()
        val redactedDeadline=System.currentTimeMillis()+5_000
        while(manager.activeNotifications.firstOrNull {it.id==id}?.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()!="孕迹提醒" && System.currentTimeMillis()<redactedDeadline)Thread.sleep(50)
        val redacted=manager.activeNotifications.single {it.id==id}.notification
        assertEquals("孕迹提醒",redacted.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        assertEquals("打开孕迹解锁后查看",redacted.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString())
        assertEquals(1,redacted.actions.size);assertEquals(action,redacted.actions.single().actionIntent)
    }
}
