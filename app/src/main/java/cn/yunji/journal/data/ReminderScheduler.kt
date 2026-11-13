package cn.yunji.journal.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import cn.yunji.journal.core.database.*
import cn.yunji.journal.domain.ReminderEvent
import cn.yunji.journal.domain.ReminderRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class ReminderRebuild(val scheduled: Int, val exact: Boolean, val generation: Long)

class ReminderScheduler constructor(
    @param:ApplicationContext private val context: Context,
    private val db: JournalDatabase,
    private val clock: java.time.Clock,
    private val store: ReminderStore,
    private val notifier: ReminderNotifier,
    private val access: cn.yunji.journal.core.security.AccessState?=null,
) {
    private val dao = db.journalDao()
    private val intake = IntakeRepository(db, clock)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()
    private val lifecycleMonitor=Any()
    @Volatile private var stopped=false
    internal var requestRebuildEnabled = true

    fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
    fun requestRebuild() { if (requestRebuildEnabled && !stopped) ReminderWorker.enqueue(context) }
    fun postTest(): Boolean = notifier.postTest()
    fun visitsEnabled(): Boolean = store.visitsEnabled()
    fun medicationsEnabled(): Boolean = store.medicationsEnabled()
    fun setVisitsEnabled(enabled: Boolean) { store.setVisitsEnabled(enabled); requestRebuild() }
    fun setMedicationsEnabled(enabled: Boolean) { store.setMedicationsEnabled(enabled); requestRebuild() }
    fun notificationPermissionGranted(): Boolean = notifier.permissionGranted()

    /** Pure projection over encrypted rows; tests can inspect without scheduling an alarm. */
    internal suspend fun events(now: Long = clock.millis(), zone: ZoneId = ZoneId.systemDefault()): List<ReminderEvent> {
        val horizon = ReminderRules.horizon(now)
        val rawNow = now - Duration.ofMinutes(15).toMillis()
        val events = mutableListOf<ReminderEvent>()
        val pregnancies = dao.allPregnancies().filter { it.deletedAt == null && it.status == "ONGOING" }
        val customReminders = dao.allCustomReminders().groupBy { it.pregnancyId }
        val plans = dao.allMedicationPlans().filter { it.deletedAt == null }.associateBy { it.id }
        val intakes = dao.allIntakes().filter { it.deletedAt == null }.associateBy { it.occurrenceId }
        for (pregnancy in pregnancies) {
            customReminders[pregnancy.id].orEmpty().forEach { row ->
                events += ReminderRules.customEvents(row, rawNow, horizon, zone)
            }
            if (store.visitsEnabled()) {
                dao.allVisits().filter { it.pregnancyId == pregnancy.id }.forEach { visit ->
                    ReminderRules.visitEvent(visit, rawNow, horizon, zone)?.let { events += it }
                }
            }
            if (store.medicationsEnabled()) {
                val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                for (offset in 0..ReminderRules.HORIZON_DAYS) {
                    val date = today.plusDays(offset)
                    intake.ensureDay(pregnancy.id, date, zone)
                    dao.occurrencesForDate(pregnancy.id, date.toString()).forEach { occurrence ->
                        if (intakes[occurrence.id] != null) return@forEach
                        ReminderRules.occurrenceEvent(occurrence, plans[occurrence.planVersionId], rawNow, horizon)?.let { events += it }
                    }
                }
            }
        }
        val snoozes = store.snoozes()
        return events.mapNotNull { event ->
            val snoozed = snoozes[event.key]?.takeIf { it > now }
            when {
                snoozed != null -> event.copy(triggerAt = snoozed)
                event.triggerAt > now -> event
                else -> null
            }
        }.distinctBy { it.key }.sortedBy { it.triggerAt }
    }

    suspend fun rebuild(): ReminderRebuild = withContext(Dispatchers.IO) { mutex.withLock {
        check(!stopped) {"恢复期间已暂停提醒"}
        val now=clock.millis()
        val oldKeys = store.scheduledKeys()
        // A worker may win the race against a due AlarmManager callback. Deliver its
        // still-current event before invalidating the old generation, then dedupe the callback.
        val due=events(now-Duration.ofMinutes(15).toMillis())
            .filter { it.triggerAt<=now && (it.key in oldKeys || store.wasMissed(it.key,it.triggerAt) ||
                store.wasDelivered(it.key,it.triggerAt) && notifier.isActive(it.key)) }
        oldKeys.forEach { cancelAlarm(it) }
        val generation = store.nextGeneration()
        for(event in due) {
            if(!stillValid(event)) {store.clearMissed(event.key,event.triggerAt);continue}
            val delivered=store.wasDelivered(event.key,event.triggerAt)
            if(!delivered || notifier.isActive(event.key)) {
                if(notifier.post(event,generation)) {
                    if(!delivered)store.markDelivered(event.key,event.triggerAt)
                    store.clearMissed(event.key,event.triggerAt)
                } else if(!delivered)store.markMissed(event.key,event.triggerAt)
            }
        }
        store.pruneSnoozes(now)
        store.pruneDelivered(now-Duration.ofMinutes(15).toMillis())
        store.pruneMissed(now-Duration.ofMinutes(15).toMillis())
        val events = events()
        val exact = exactAllowed()
        val scheduled = mutableSetOf<String>()
        for (event in events) {
            if (schedule(event, generation, exact)) scheduled += event.key
        }
        store.saveScheduledKeys(scheduled)
        ReminderRebuild(scheduled.size, exact, generation)
    } }

    private fun schedule(event: ReminderEvent, generation: Long, exact: Boolean): Boolean {
        val pi = alarmPendingIntent(event, generation)
        return try {
            if (exact) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, event.triggerAt, pi)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, event.triggerAt, pi)
            true
        } catch (_: SecurityException) {
            runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, event.triggerAt, pi) }.isSuccess
        }
    }

    fun snooze(event: ReminderEvent): Long = synchronized(lifecycleMonitor) {
        check(!stopped) {"恢复期间已暂停提醒"}
        val at = ReminderRules.snoozeUntil(clock.millis())
        store.setSnooze(event.key, at)
        val generation = store.generation().takeIf { it > 0 } ?: store.nextGeneration()
        runCatching { schedule(event.copy(triggerAt = at), generation, exactAllowed()) }
        return at
    }

    suspend fun markTaken(event: ReminderEvent): String = withContext(Dispatchers.IO) {mutex.withLock {
        check(!stopped) {"恢复期间已暂停提醒"}
        markTakenLocked(event)
    }}
    private suspend fun markTakenLocked(event: ReminderEvent): String = withContext(Dispatchers.IO) {
        val occurrenceId = requireNotNull(event.occurrenceId) { "缺少服药计划" }
        val form = intake.form(event.pregnancyId, occurrenceId, null, "TAKEN", null)
        val id = intake.save(form)
        notifier.cancel(event.key)
        store.clearSnooze(event.key)
        requestRebuild()
        id
    }

    suspend fun stillValid(event: ReminderEvent): Boolean = withContext(Dispatchers.IO) {
        if(stopped)return@withContext false
        if (event.targetType == "TEST" && cn.yunji.journal.BuildConfig.DEBUG) return@withContext true
        val pregnancy = dao.pregnancy(event.pregnancyId)
        if (pregnancy == null || pregnancy.deletedAt != null || pregnancy.status != "ONGOING") return@withContext false
        when (event.targetType) {
            "VISIT" -> {
                val visit = dao.visit(event.targetId) ?: return@withContext false
                visit.deletedAt == null && visit.status == "SCHEDULED" && visit.reminderDate != null && visit.reminderTime != null &&
                    ReminderRules.visitEvent(visit, 0L, Long.MAX_VALUE)?.key == event.key
            }
            "MEDICATION" -> {
                val occurrence = dao.occurrence(event.targetId) ?: return@withContext false
                val plan = dao.medicationPlan(occurrence.planVersionId)
                occurrence.deletedAt == null && occurrence.state == "ACTIVE" && plan?.status == "ACTIVE" && plan.deletedAt == null &&
                    dao.intakeByOccurrence(occurrence.id) == null
            }
            "CUSTOM" -> {
                val row = dao.customReminder(event.targetId) ?: return@withContext false
                val date = runCatching { LocalDate.parse(event.key.split(':').getOrNull(2)) }.getOrNull()
                date != null && ReminderRules.customEventOn(row,date)?.key == event.key
            }
            "TEST" -> cn.yunji.journal.BuildConfig.DEBUG
            else -> false        }
    }

    fun cancelNotification(key: String) { notifier.cancel(key) }
    fun currentGeneration(): Long = store.generation()
    suspend fun deliverAlarm(event: ReminderEvent,generation: Long): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        if(event.targetType!="TEST" && !ReminderRules.isCurrentGeneration(generation,store.generation()))return@withLock false
        if(store.wasDelivered(event.key,event.triggerAt))return@withLock false
        if(!stillValid(event)) {notifier.cancel(event.key);return@withLock false}
        val current=if(event.targetType=="TEST")event else resolveAction(event.key,generation) ?: return@withLock false
        notifier.post(current,generation).also {
            if(it) {store.markDelivered(event.key,event.triggerAt);store.clearMissed(event.key,event.triggerAt)}
            else store.markMissed(event.key,event.triggerAt)
        }
    } }
    suspend fun resolveAction(key: String,generation: Long): ReminderEvent?=withContext(Dispatchers.IO) {
        if(stopped || !ReminderRules.isCurrentGeneration(generation,store.generation()) || key.length>220)return@withContext null
        val event=when {
            key.startsWith("MEDICATION:")-> {
                val row=dao.occurrence(key.removePrefix("MEDICATION:")) ?: return@withContext null
                ReminderRules.occurrenceEvent(row,dao.medicationPlan(row.planVersionId),0,Long.MAX_VALUE)
            }
            key.startsWith("VISIT:")-> {
                val id=key.removePrefix("VISIT:").substringBefore(':')
                val row=dao.visit(id) ?: return@withContext null
                ReminderRules.visitEvent(row,0,Long.MAX_VALUE)
            }
            key.startsWith("CUSTOM:")-> {
                val id=key.removePrefix("CUSTOM:").substringBefore(':')
                val row=dao.customReminder(id) ?: return@withContext null
                val date=runCatching { LocalDate.parse(key.split(':').getOrNull(2)) }.getOrNull() ?: return@withContext null
                ReminderRules.customEventOn(row,date)
            }
            else->null
        } ?: return@withContext null
        event.takeIf {it.key==key && stillValid(it)}
    }
    suspend fun confirmAuthenticatedAction(key: String,generation: Long,action: String): Unit=withContext(Dispatchers.IO) {mutex.withLock {
        check(!stopped) {"恢复期间已暂停提醒"}
        require(access?.canCommitNotification()==true) {"请重新认证后确认操作"}
        val event=requireNotNull(resolveAction(key,generation)) {"提醒已变化或已处理，请在应用中核对"}
        require(access.canCommitNotification()) {"认证已过期，请重新解锁"}
        when(action) {
            ReminderContract.ACTION_TAKEN->{require(event.targetType=="MEDICATION");markTakenLocked(event)}
            ReminderContract.ACTION_SNOOZE->snooze(event)
            else->throw IllegalArgumentException("不支持的提醒操作")
        }
        notifier.cancel(key)
    }}

    /** Waits for rebuild/authenticated actions, then invalidates and cancels all known old alarms. */
    suspend fun pauseForRestore()=withContext(Dispatchers.IO) {mutex.withLock {
        synchronized(lifecycleMonitor) {
            stopped=true;requestRebuildEnabled=false
            store.invalidateForRestore().forEach {key->cancelAlarm(key);notifier.cancel(key)}
        }
    }}
    suspend fun resumeAfterRestoreAbort()=mutex.withLock {
        synchronized(lifecycleMonitor){stopped=false;requestRebuildEnabled=true}
    }
    private fun alarmPendingIntent(event: ReminderEvent, generation: Long): PendingIntent {
        val intent = ReminderContract.alarmIntent(context, event, generation)
        return PendingIntent.getBroadcast(context, ReminderContract.notificationId(event.key), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancelAlarm(key: String) {
        val intent = android.content.Intent(context, ReminderAlarmReceiver::class.java).setAction(ReminderContract.ACTION_ALARM)
            .putExtra(ReminderContract.EXTRA_KEY, key)
        val pi = PendingIntent.getBroadcast(context, ReminderContract.notificationId(key), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        alarmManager.cancel(pi); pi.cancel()
    }
}

