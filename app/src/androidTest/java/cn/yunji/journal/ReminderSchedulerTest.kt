package cn.yunji.journal

import android.content.Context
import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.database.JournalDatabase
import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.time.*
import java.util.UUID

/** Synthetic encrypted database and fixed clock; no notification permission is required to project events. */
class ReminderSchedulerTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var db: JournalDatabase
    private lateinit var repo: JournalRepository
    private lateinit var store: ReminderStore
    private lateinit var scheduler: ReminderScheduler
    private val clock: Clock = Clock.systemUTC()
    private val now get() = clock.millis()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("reminder-state", Context.MODE_PRIVATE).edit().clear().commit()
        root = File(context.cacheDir, "reminder-test-${UUID.randomUUID()}").apply { mkdirs() }
        db = JournalDatabase.open(context, File(root, "test.db"), VaultCrypto.randomKey())
        repo = JournalRepository(db, clock)
        store = ReminderStore(context)
        scheduler = ReminderScheduler(context, db, clock, store, ReminderNotifier(context))
        scheduler.requestRebuildEnabled = false
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::root.isInitialized) root.deleteRecursively()
        context.getSharedPreferences("reminder-state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private suspend fun pregnancy() = repo.savePregnancy(null, "合成提醒档案", null, null, "", "")

    @Test fun customReminderCrudProjectsAndInvalidatesEvents() = runBlocking {
        val pregnancy = pregnancy()
        val date = LocalDate.now(clock).plusDays(1)
        val id = repo.saveCustomReminder(null,pregnancy,"合成事项","测试备注",date,LocalTime.NOON,"DAILY",date.plusDays(2))
        val first = scheduler.events(now,ZoneId.systemDefault()).filter { it.targetType=="CUSTOM" }
        assertEquals(3,first.size)
        assertTrue(first.all { it.targetId==id && it.body.contains("合成事项") })
        val generation = store.nextGeneration()
        assertNotNull(scheduler.resolveAction(first.first().key,generation))
        repo.saveCustomReminder(id,pregnancy,"已修改","",date,LocalTime.of(13,0),"NONE",null)
        assertNull(scheduler.resolveAction(first.first().key,generation))
        val revised = scheduler.events(now,ZoneId.systemDefault()).filter { it.targetType=="CUSTOM" }
        assertEquals(1,revised.size)
        assertTrue(revised.single().body.contains("已修改"))
        repo.deleteCustomReminder(id)
        assertTrue(scheduler.events(now,ZoneId.systemDefault()).none { it.targetType=="CUSTOM" })
        assertNull(scheduler.resolveAction(first.first().key,generation))
    }

    @Test fun customReminderEditReplacesRegisteredAlarmAndDeleteCancelsIt() = runBlocking {
        val pregnancy=pregnancy()
        val date=LocalDate.now(clock).plusDays(2)
        val id=repo.saveCustomReminder(null,pregnancy,"合成调度","",date,LocalTime.NOON,"NONE",null)
        val first=scheduler.rebuild()
        val oldKey=store.scheduledKeys().single {it.startsWith("CUSTOM:$id:")}
        assertTrue(first.scheduled>=1)
        fun pending(key:String)=PendingIntent.getBroadcast(context,ReminderContract.notificationId(key),
            android.content.Intent(context,ReminderAlarmReceiver::class.java).setAction(ReminderContract.ACTION_ALARM)
                .putExtra(ReminderContract.EXTRA_KEY,key),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNotNull(pending(oldKey))

        repo.saveCustomReminder(id,pregnancy,"合成调度修改","",date,LocalTime.of(13,0),"NONE",null)
        scheduler.rebuild()
        val newKey=store.scheduledKeys().single {it.startsWith("CUSTOM:$id:")}
        assertNotEquals(oldKey,newKey)
        assertNull(pending(oldKey))
        assertNotNull(pending(newKey))
        repo.deleteCustomReminder(id)
        scheduler.rebuild()
        assertFalse(store.scheduledKeys().contains(newKey))
        assertNull(pending(newKey))
    }

    @Test fun unauthenticatedNotificationActionCannotWriteOrSnooze() = runBlocking {
        val pregnancy=pregnancy()
        repo.medications.save(MedicationForm(pregnancyId=pregnancy,name="合成认证药品",dose="1",unit="片",times="08:00",startDate=LocalDate.now(clock).toString(),zoneId="UTC"))
        val event=scheduler.events(now,ZoneOffset.UTC).first {it.targetType=="MEDICATION"}
        val generation=store.nextGeneration()
        assertNotNull(scheduler.resolveAction(event.key,generation))
        assertTrue(runCatching {scheduler.confirmAuthenticatedAction(event.key,generation,ReminderContract.ACTION_TAKEN)}.isFailure)
        assertTrue(runCatching {scheduler.confirmAuthenticatedAction(event.key,generation,ReminderContract.ACTION_SNOOZE)}.isFailure)
        assertTrue(db.journalDao().allIntakes().isEmpty())
        store.nextGeneration();assertNull(scheduler.resolveAction(event.key,generation))
    }

    @Test fun eventsProjectVisitsAndMedicationWithinSevenDaysAndDropTakenDoses() = runBlocking {
        val pregnancy = pregnancy()
        val visit = repo.saveVisit(null, pregnancy, LocalDate.now(clock).plusDays(2), LocalTime.of(10, 30), "", "合成产检", "")
        repo.setVisitReminder(visit, LocalDate.now(clock).plusDays(1).toString(), "12:00")
        val plan = repo.medications.save(MedicationForm(pregnancyId = pregnancy, name = "合成提醒药品", dose = "0.125", unit = "片",
            times = "08:00,20:00", startDate = LocalDate.now(clock).toString(), zoneId = "UTC"))
        val events = scheduler.events(now, ZoneOffset.UTC)
        assertTrue(events.any { it.targetType == "VISIT" && it.targetId == visit })
        val medication = events.filter { it.targetType == "MEDICATION" }.sortedBy { it.triggerAt }
        assertTrue(medication.size >= 10)
        assertTrue(medication.all { it.planVersionId == plan && it.occurrenceId != null })
        assertTrue(events.zipWithNext().all { it.first.triggerAt <= it.second.triggerAt })
        val first = medication.first()
        val record = scheduler.markTaken(first)
        assertEquals(1, db.journalDao().allIntakes().size)
        assertEquals(record, scheduler.markTaken(first))
        assertEquals(1, db.journalDao().allIntakes().size)
        assertTrue(scheduler.events(now, ZoneOffset.UTC).none { it.key == first.key })
    }

    @Test fun snoozeKeepsOriginalOccurrenceButMovesNextAlarmProjection() = runBlocking {
        val pregnancy = pregnancy()
        repo.medications.save(MedicationForm(pregnancyId = pregnancy, name = "合成稍后药品", dose = "0.5", unit = "片",
            times = "08:00", startDate = LocalDate.now(clock).toString(), zoneId = "UTC"))
        val event = scheduler.events(now, ZoneOffset.UTC).first { it.targetType == "MEDICATION" }
        val occurrence = requireNotNull(db.journalDao().occurrence(event.targetId))
        val before = clock.millis()
        val snoozed = scheduler.snooze(event)
        assertTrue(snoozed in (before + 10 * 60_000L)..(before + 10 * 60_000L + 2_000L))
        val projected = scheduler.events(now, ZoneOffset.UTC).single { it.key == event.key }
        assertEquals(snoozed, projected.triggerAt)
        assertEquals(occurrence.scheduledAt, db.journalDao().occurrence(event.targetId)?.scheduledAt)
        assertTrue(db.journalDao().allIntakes().isEmpty())
    }

    @Test fun rebuildSchedulesAlarmsAndReplacesPreviousGeneration() = runBlocking {
        val pregnancy = pregnancy()
        val visit = repo.saveVisit(null, pregnancy, LocalDate.now(clock).plusDays(2), LocalTime.of(10, 30), "", "合成产检", "")
        repo.setVisitReminder(visit, LocalDate.now(clock).plusDays(1).toString(), "12:00")
        repo.medications.save(MedicationForm(pregnancyId = pregnancy, name = "合成重建药品", dose = "0.125", unit = "片",
            times = "08:00", startDate = LocalDate.now(clock).toString(), zoneId = "UTC"))
        val first = scheduler.rebuild()
        assertTrue(first.generation >= 1)
        assertTrue(first.scheduled >= 2)
        assertTrue(store.scheduledKeys().isNotEmpty())
        val second = scheduler.rebuild()
        assertEquals(first.generation + 1, second.generation)
        assertTrue(store.scheduledKeys().isNotEmpty())
        store.setVisitsEnabled(false); store.setMedicationsEnabled(false)
        val cleared = scheduler.rebuild()
        assertEquals(0, cleared.scheduled)
        assertTrue(store.scheduledKeys().isEmpty())
    }

    @Test fun rebuildAtDueTimeDeliversOnceAndLateAlarmDoesNotDuplicate() = runBlocking {
        assumeTrue(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
        val pregnancy=pregnancy()
        val tomorrow=LocalDate.now(clock).plusDays(1)
        val visit=repo.saveVisit(null,pregnancy,tomorrow.plusDays(1),LocalTime.of(14,0),"","并发提醒测试","")
        repo.setVisitReminder(visit,tomorrow.toString(),"12:00")
        val event=scheduler.events().single {it.targetId==visit}
        val first=scheduler.rebuild()
        assertTrue(store.scheduledKeys().contains(event.key))
        val later=ReminderScheduler(context,db,Clock.fixed(Instant.ofEpochMilli(event.triggerAt+5_000L),ZoneOffset.UTC),
            store,ReminderNotifier(context))
        later.requestRebuildEnabled=false
        val manager=context.getSystemService(NotificationManager::class.java)
        val id=ReminderContract.notificationId(event.key)
        try {
            val recovered=later.rebuild()
            assertEquals(first.generation+1,recovered.generation)
            assertTrue(manager.activeNotifications.any {it.id==id})
            assertTrue(store.wasDelivered(event.key,event.triggerAt))
            assertFalse("旧闹钟回调不得再次投递",later.deliverAlarm(event,first.generation))
            val refreshed=later.rebuild()
            assertEquals(recovered.generation+1,refreshed.generation)
            assertTrue("通知在重建后仍应可见",manager.activeNotifications.any {it.id==id})
            manager.cancel(id)
            later.rebuild()
            assertFalse("用户清除通知后不应重新弹出",manager.activeNotifications.any {it.id==id})
        } finally {
            manager.cancel(id)
            store.setVisitsEnabled(false)
            later.rebuild()
        }
    }

    @Test fun alarmFirstThenRebuildKeepsNotificationWithoutRealerting() = runBlocking {
        assumeTrue(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
        val pregnancy=pregnancy()
        val tomorrow=LocalDate.now(clock).plusDays(1)
        val visit=repo.saveVisit(null,pregnancy,tomorrow.plusDays(1),LocalTime.of(14,0),"","并发提醒另一顺序","")
        repo.setVisitReminder(visit,tomorrow.toString(),"12:00")
        val event=scheduler.events().single {it.targetId==visit}
        val first=scheduler.rebuild()
        val manager=context.getSystemService(NotificationManager::class.java)
        val id=ReminderContract.notificationId(event.key)
        val later=ReminderScheduler(context,db,Clock.fixed(Instant.ofEpochMilli(event.triggerAt+5_000L),ZoneOffset.UTC),
            store,ReminderNotifier(context))
        later.requestRebuildEnabled=false
        try {
            assertTrue(scheduler.deliverAlarm(event,first.generation))
            assertFalse("重复闹钟不得再次投递",scheduler.deliverAlarm(event,first.generation))
            assertTrue(manager.activeNotifications.any {it.id==id})
            later.rebuild()
            assertTrue("重建应保留已投递通知",manager.activeNotifications.any {it.id==id})
            assertTrue(store.wasDelivered(event.key,event.triggerAt))
            manager.cancel(id)
            later.rebuild()
            assertFalse("已清除通知不得重新弹出",manager.activeNotifications.any {it.id==id})
        } finally {
            manager.cancel(id)
            store.setVisitsEnabled(false)
            later.rebuild()
        }
    }

    @Test fun generationIncreasesAndScheduledKeysAreReplaceable() {
        val first = store.nextGeneration()
        val second = store.nextGeneration()
        assertEquals(first + 1, second)
        store.saveScheduledKeys(setOf("a", "b"))
        assertEquals(setOf("a", "b"), store.scheduledKeys())
        store.saveScheduledKeys(emptySet())
        assertTrue(store.scheduledKeys().isEmpty())
        assertTrue(store.visitsEnabled() && store.medicationsEnabled())
        store.setVisitsEnabled(false); store.setMedicationsEnabled(false)
        assertFalse(store.visitsEnabled()); assertFalse(store.medicationsEnabled())
    }
}
