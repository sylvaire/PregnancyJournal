package cn.yunji.journal

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room3.Transactor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.yunji.journal.core.security.DeviceKeyStore
import cn.yunji.journal.data.ReminderContract
import cn.yunji.journal.data.ReminderStore
import cn.yunji.journal.data.SecurityStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Host runs prepare and verify in separate app processes without force-stopping the alarm owner. */
class ReminderColdProcessAlarmTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val prefs get()=context.getSharedPreferences("cold-process-alarm-test",Context.MODE_PRIVATE)

    private fun requireDedicatedDevice(phase:String) {
        assumeTrue(Build.VERSION.SDK_INT==36 && Build.MODEL=="sdk_gphone16k_x86_64")
        assumeTrue(!DeviceKeyStore(context).strict() && !SecurityStore(context).lockEnabled())
        assumeTrue(InstrumentationRegistry.getArguments().getString("coldAlarmPhase")==phase)
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test fun prepareVisitAlarmForColdProcess() {
        prepareAlarm("prepare",ZonedDateTime.now().plusMinutes(2).withSecond(0).withNano(0),true)
    }

    @Test fun prepareVisitAlarmForRealReboot() {
        prepareAlarm("boot-prepare",ZonedDateTime.now().plusDays(2).withHour(12).withMinute(0).withSecond(0).withNano(0),false)
    }

    @Test fun prepareVisitAlarmForPackageUpdate() {
        prepareAlarm("update-prepare",ZonedDateTime.now().plusDays(2).withHour(12).withMinute(0).withSecond(0).withNano(0),false)
    }

    @Test fun prepareVisitAlarmForTimeZoneChange() {
        prepareAlarm("zone-prepare",ZonedDateTime.now().plusDays(2).withHour(12).withMinute(0).withSecond(0).withNano(0),false)
    }

    @Test fun prepareVisitAlarmForReverseTimeZoneChange() {
        prepareAlarm("zone-reverse-prepare",ZonedDateTime.now().plusDays(2).withHour(12).withMinute(0).withSecond(0).withNano(0),false)
    }

    private fun prepareAlarm(phase:String,at:ZonedDateTime,exactRequired:Boolean) {
        requireDedicatedDevice(phase)
        val resources=ui.activity.runtime.resources()
        if(exactRequired)assumeTrue("需要精确闹钟以界定后台投递窗口",resources.reminders.exactAllowed())
        assertFalse("已有未清理的合成预约",prefs.contains("pregnancy"))
        val store=ReminderStore(context)
        val originalVisits=store.visitsEnabled()
        var pregnancy:String?=null
        var visit:String?=null
        try {
            store.setVisitsEnabled(true)
            pregnancy=runBlocking {resources.repository.savePregnancy(null,"后台提醒合成孕期",null,null,"","")}
            visit=runBlocking {
                resources.repository.saveVisit(null,requireNotNull(pregnancy),at.toLocalDate().plusDays(1),LocalTime.of(14,0),
                    "","后台提醒合成产检","").also {resources.repository.setVisitReminder(it,at.toLocalDate().toString(),at.toLocalTime().toString())}
            }
            val event=runBlocking {resources.reminders.events().single {it.targetType=="VISIT" && it.targetId==visit}}
            val rebuild=runBlocking {resources.reminders.rebuild()}
            if(exactRequired)assertTrue(rebuild.exact)
            assertTrue(store.scheduledKeys().contains(event.key))
            assertTrue(prefs.edit().putString("pregnancy",pregnancy).putString("visit",visit).putString("key",event.key)
                .putLong("triggerAt",event.triggerAt).putLong("generation",rebuild.generation)
                .putString("originalZone",ZoneId.systemDefault().id)
                .putBoolean("visitsEnabled",originalVisits).commit())
            Log.i("ColdAlarmDeviceTest","scheduledAt=${event.triggerAt} generation=${rebuild.generation}")
        } catch(error:Throwable) {
            cleanup(pregnancy,visit,null,originalVisits)
            throw error
        }
    }

    @Test fun verifyDeliveredAlarmAndCleanFixture() {
        requireDedicatedDevice("verify")
        val pregnancy=prefs.getString("pregnancy",null)
        val visit=prefs.getString("visit",null)
        val key=prefs.getString("key",null)
        val at=prefs.getLong("triggerAt",0L)
        val originalVisits=prefs.getBoolean("visitsEnabled",true)
        try {
            assertNotNull("准备阶段未保存合成孕期",pregnancy)
            assertNotNull("准备阶段未保存合成预约",visit)
            assertNotNull("准备阶段未保存提醒键",key)
            assertTrue("准备阶段未保存触发时间",at>0)
            val posted=context.getSystemService(NotificationManager::class.java).activeNotifications
                .firstOrNull {it.id==ReminderContract.notificationId(requireNotNull(key))}
            assertNotNull("应用进程退出后，产检通知未到达",posted)
            assertEquals("孕迹提醒",posted?.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE,posted?.notification?.visibility)
            assertEquals(requireNotNull(visit),runBlocking {ui.activity.runtime.resources().repository.visit(visit)}?.id)
            Log.i("ColdAlarmDeviceTest","coldProcessDelayMs=${requireNotNull(posted).postTime-at}")
        } finally {
            cleanup(pregnancy,visit,key,originalVisits)
        }
    }

    @Test fun verifyBootRebuildAndCleanFixture() {
        verifyRebuildAndCleanFixture("boot-verify",true)
    }

    @Test fun verifyPackageUpdateRebuildAndCleanFixture() {
        verifyRebuildAndCleanFixture("update-verify",false)
    }

    private fun verifyRebuildAndCleanFixture(phase:String,checkPendingIntent:Boolean) {
        requireDedicatedDevice(phase)
        val pregnancy=prefs.getString("pregnancy",null)
        val visit=prefs.getString("visit",null)
        val key=prefs.getString("key",null)
        val before=prefs.getLong("generation",0L)
        val originalVisits=prefs.getBoolean("visitsEnabled",true)
        try {
            assertNotNull("准备阶段未保存合成孕期",pregnancy)
            assertNotNull("准备阶段未保存合成预约",visit)
            val alarmKey=requireNotNull(key){"准备阶段未保存提醒键"}
            assertTrue(before>0)
            val store=ReminderStore(context)
            assertTrue("真实重启后未更新提醒代际",store.generation()>before)
            assertTrue("真实重启后未恢复预约调度",store.scheduledKeys().contains(alarmKey))
            if(checkPendingIntent) {
                val intent=android.content.Intent(context,cn.yunji.journal.data.ReminderAlarmReceiver::class.java)
                    .setAction(ReminderContract.ACTION_ALARM)
                val pending=PendingIntent.getBroadcast(context,ReminderContract.notificationId(alarmKey),intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                assertNotNull("系统闹钟未重新注册",pending)
            }
            assertEquals(visit,runBlocking {ui.activity.runtime.resources().repository.visit(requireNotNull(visit))}?.id)
            Log.i("ColdAlarmDeviceTest","bootGenerationBefore=$before after=${store.generation()}")
        } finally {cleanup(pregnancy,visit,key,originalVisits)}
    }

    @Test fun verifyTimeZoneRebuildAndCleanFixture() {
        verifyTimeZoneChange("zone-verify","Asia/Shanghai")
    }

    @Test fun verifyReverseTimeZoneRebuildAndCleanFixture() {
        verifyTimeZoneChange("zone-reverse-verify","GMT")
    }

    private fun verifyTimeZoneChange(phase:String,targetZone:String) {
        requireDedicatedDevice(phase)
        val pregnancy=prefs.getString("pregnancy",null)
        val visit=prefs.getString("visit",null)
        val key=prefs.getString("key",null)
        val before=prefs.getLong("generation",0L)
        val oldAt=prefs.getLong("triggerAt",0L)
        val originalVisits=prefs.getBoolean("visitsEnabled",true)
        try {
            assertNotNull("准备阶段未保存合成孕期",pregnancy)
            assertNotNull("准备阶段未保存合成预约",visit)
            val alarmKey=requireNotNull(key){"准备阶段未保存提醒键"}
            val zone=ZoneId.systemDefault()
            assertEquals(targetZone,zone.id)
            val originalZone=ZoneId.of(requireNotNull(prefs.getString("originalZone",null)))
            val expected=Instant.ofEpochMilli(oldAt).atZone(originalZone)
                .toLocalDate().atTime(12,0).atZone(zone).toInstant().toEpochMilli()
            assertNotEquals("时区切换未改变绝对触发时间",oldAt,expected)
            val store=ReminderStore(context)
            assertTrue("时区广播未更新提醒代际",store.generation()>before)
            assertTrue("时区广播未恢复预约调度",store.scheduledKeys().contains(alarmKey))
            val event=runBlocking {ui.activity.runtime.resources().reminders.events()
                .single {it.targetType=="VISIT" && it.targetId==visit}}
            assertEquals("预约没有按当前系统时区投影",expected,event.triggerAt)
            // The host captures dumpsys alarm before this MainActivity starts. During Activity
            // startup another rebuild can briefly cancel and replace this PendingIntent.
            Log.i("ColdAlarmDeviceTest","zoneChangeGeneration=$before→${store.generation()} trigger=$oldAt→${event.triggerAt}")
        } finally {cleanup(pregnancy,visit,key,originalVisits)}
    }

    private fun cleanup(pregnancy:String?,visit:String?,key:String?,originalVisits:Boolean) {
        key?.let {context.getSystemService(NotificationManager::class.java).cancel(ReminderContract.notificationId(it))}
        val resources=ui.activity.runtime.resources()
        pregnancy?.let {parent -> runBlocking {
                resources.database.useConnection(isReadOnly=false){connection ->
                    connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE){
                        visit?.let {id ->
                            connection.usePrepared("DELETE FROM change_log WHERE entityType='visit' AND entityId=?"){it.bindText(1,id);it.step()}
                            connection.usePrepared("DELETE FROM prenatal_visits WHERE id=?"){it.bindText(1,id);it.step()}
                        }
                        connection.usePrepared("DELETE FROM pregnancy_date_revisions WHERE pregnancyId=?"){it.bindText(1,parent);it.step()}
                        connection.usePrepared("DELETE FROM change_log WHERE entityType='pregnancy' AND entityId=?"){it.bindText(1,parent);it.step()}
                        connection.usePrepared("DELETE FROM pregnancies WHERE id=?"){it.bindText(1,parent);it.step()}
                    }
                }
                assertNull(resources.repository.pregnancy(parent))
        } }
        ReminderStore(context).setVisitsEnabled(originalVisits)
        runBlocking {resources.reminders.rebuild()}
        assertTrue("合成提醒状态未清理",prefs.edit().clear().commit())
    }
}
