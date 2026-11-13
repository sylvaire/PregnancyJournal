package cn.yunji.journal.domain

import cn.yunji.journal.core.database.MedicationOccurrenceEntity
import cn.yunji.journal.core.database.MedicationPlanEntity
import java.time.*
import java.util.UUID

object MedicationSchedule {
    /** Use the first valid instant after a gap and a single (earlier) offset in an overlap. */
    fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId): Instant {
        val local = date.atTime(time)
        val offsets = zone.rules.getValidOffsets(local)
        return if (offsets.isEmpty()) zone.rules.getTransition(local)!!.dateTimeAfter.atZone(zone).toInstant()
        else local.toInstant(offsets.first())
    }
    fun applicable(plan: MedicationPlanEntity, all: List<MedicationPlanEntity>, date: LocalDate, time: String, instant: Long): Boolean {
        if (plan.deletedAt != null || plan.status != "ACTIVE" || plan.frequencyType !in setOf("DAILY_TIMES", "WEEKLY_TIMES", "ONCE")) return false
        if (date < LocalDate.parse(plan.startDate) || (plan.endDate != null && date > LocalDate.parse(plan.endDate))) return false
        if (plan.frequencyType == "ONCE" && date.toString() != plan.startDate) return false
        if (plan.frequencyType == "WEEKLY_TIMES" && date.dayOfWeek.value.toString() !in plan.weekdays.split(',')) return false
        if (time !in plan.times.split(',')) return false
        val next = all.filter { it.logicalPlanId == plan.logicalPlanId && it.version > plan.version }.minByOrNull { it.version }
        return instant >= plan.effectiveAt && (next == null || instant < next.effectiveAt)
    }
    fun points(plans: List<MedicationPlanEntity>, date: LocalDate, zone: ZoneId): List<MedicationOccurrenceEntity> = plans.flatMap { plan ->
        if (plan.frequencyType !in setOf("DAILY_TIMES", "WEEKLY_TIMES", "ONCE")) emptyList() else plan.times.split(',').filter { it.isNotBlank() }.mapIndexedNotNull { ordinal, value ->
            val instant = resolve(date, LocalTime.parse(value), zone).toEpochMilli()
            if (!applicable(plan, plans, date, value, instant)) null else {
                val id = UUID.nameUUIDFromBytes("intake/${plan.id}/$date/$value/$ordinal".toByteArray(Charsets.UTF_8)).toString()
                MedicationOccurrenceEntity(id, plan.pregnancyId, plan.id, date.toString(), value, ordinal, instant, zone.id, createdAt = 0)
            }
        }
    }.sortedWith(compareBy<MedicationOccurrenceEntity> { it.scheduledAt }.thenBy { it.id })
}
