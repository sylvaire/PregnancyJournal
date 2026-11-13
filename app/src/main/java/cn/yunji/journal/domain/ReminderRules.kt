package cn.yunji.journal.domain

import cn.yunji.journal.core.database.MedicationOccurrenceEntity
import cn.yunji.journal.core.database.MedicationPlanEntity
import cn.yunji.journal.core.database.VisitEntity
import cn.yunji.journal.core.database.CustomReminderEntity
import java.time.Duration
import java.time.LocalTime
import java.time.LocalDate
import java.time.ZoneId

/** Pure 7-day reminder projection; the scheduler may rebuild it at any time from encrypted business rows. */
data class ReminderEvent(
    val key: String,
    val pregnancyId: String,
    val targetType: String,
    val targetId: String,
    val occurrenceId: String? = null,
    val planVersionId: String? = null,
    val triggerAt: Long,
    val title: String,
    val body: String,
    val publicBody: String = "孕迹提醒：有一条待确认记录",
)

object ReminderRules {
    const val HORIZON_DAYS = 7L
    const val SNOOZE_MINUTES = 10L
    fun horizon(now: Long): Long = now + Duration.ofDays(HORIZON_DAYS).toMillis()

    fun visitEvent(visit: VisitEntity, now: Long, horizon: Long, zone: ZoneId = ZoneId.systemDefault()): ReminderEvent? {
        if (visit.deletedAt != null || visit.status != "SCHEDULED") return null
        val date = runCatching { java.time.LocalDate.parse(visit.reminderDate ?: return null) }.getOrNull() ?: return null
        val time = runCatching { LocalTime.parse(visit.reminderTime ?: return null) }.getOrNull() ?: return null
        val trigger = MedicationSchedule.resolve(date, time, zone).toEpochMilli()
        if (trigger <= now || trigger > horizon) return null
        val summary = listOf(visit.scheduledDate + " " + (visit.scheduledTime ?: "时间待定"), visit.items, visit.hospital)
            .filter { it.isNotBlank() }.joinToString(" · ")
        return ReminderEvent(
            key = "VISIT:${visit.id}:${visit.reminderDate}:${visit.reminderTime}",
            pregnancyId = visit.pregnancyId, targetType = "VISIT", targetId = visit.id, triggerAt = trigger,
            title = "产检提醒", body = summary, publicBody = "孕迹提醒：有一次产检安排",
        )
    }

    fun customEvents(row: CustomReminderEntity, now: Long, horizon: Long, zone: ZoneId = ZoneId.systemDefault()): List<ReminderEvent> {
        if (row.deletedAt != null || !row.enabled) return emptyList()
        val start = runCatching { LocalDate.parse(row.startDate) }.getOrNull() ?: return emptyList()
        val first = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1).let { if (it.isAfter(start)) it else start }
        return (0..HORIZON_DAYS.toInt()+1).map { first.plusDays(it.toLong()) }
            .mapNotNull { customEventOn(row,it,zone) }
            .filter { it.triggerAt > now && it.triggerAt <= horizon }
    }

    fun customEventOn(row: CustomReminderEntity, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): ReminderEvent? {
        if (row.deletedAt != null || !row.enabled) return null
        val start = runCatching { LocalDate.parse(row.startDate) }.getOrNull() ?: return null
        val end = row.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return null }
        val time = runCatching { LocalTime.parse(row.localTime) }.getOrNull() ?: return null
        if (date.isBefore(start) || end != null && date.isAfter(end)) return null
        if (when (row.repeat) { "NONE" -> date != start; "DAILY" -> false; "WEEKLY" -> date.dayOfWeek != start.dayOfWeek; else -> true }) return null
        return ReminderEvent(
            key = "CUSTOM:${row.id}:$date:${row.revision}", pregnancyId = row.pregnancyId,
            targetType = "CUSTOM", targetId = row.id,
            triggerAt = MedicationSchedule.resolve(date,time,zone).toEpochMilli(),
            title = "自定义提醒", body = row.title + row.note.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            publicBody = "孕迹提醒：有一条待查看提醒",
        )
    }

    fun occurrenceEvent(occurrence: MedicationOccurrenceEntity, plan: MedicationPlanEntity?, now: Long, horizon: Long): ReminderEvent? {
        if (occurrence.deletedAt != null || occurrence.state != "ACTIVE") return null
        if (plan == null || plan.deletedAt != null || plan.status != "ACTIVE") return null
        if (occurrence.scheduledAt <= now || occurrence.scheduledAt > horizon) return null
        val name = plan.medicationName.ifBlank { "用药" }
        val dose = listOf(plan.dose, plan.unit).filterNotNull().joinToString(" ")
        val body = listOf(name, dose.ifBlank { "按方案记录" }, occurrence.localTime).joinToString(" · ")
        return ReminderEvent(
            key = "MEDICATION:${occurrence.id}",
            pregnancyId = occurrence.pregnancyId, targetType = "MEDICATION", targetId = occurrence.id,
            occurrenceId = occurrence.id, planVersionId = plan.id, triggerAt = occurrence.scheduledAt,
            title = "用药提醒", body = body, publicBody = "孕迹提醒：有一条用药记录待确认",
        )
    }

    fun snoozeUntil(now: Long): Long = now + Duration.ofMinutes(SNOOZE_MINUTES).toMillis()
    fun isCurrentGeneration(intentGeneration: Long, currentGeneration: Long): Boolean = intentGeneration == currentGeneration && intentGeneration > 0
}
