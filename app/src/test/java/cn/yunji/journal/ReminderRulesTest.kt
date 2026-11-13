package cn.yunji.journal

import cn.yunji.journal.core.database.MedicationOccurrenceEntity
import cn.yunji.journal.core.database.MedicationPlanEntity
import cn.yunji.journal.core.database.VisitEntity
import cn.yunji.journal.core.database.CustomReminderEntity
import cn.yunji.journal.domain.ReminderRules
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ReminderRulesTest {
    private val now = Instant.parse("2026-10-18T01:00:00Z").toEpochMilli()
    private val horizon = ReminderRules.horizon(now)

    private fun visit(date: String? = "2026-10-18", time: String? = "09:00", status: String = "SCHEDULED", deleted: Long? = null) = VisitEntity(
        id = "visit-1", pregnancyId = "pregnancy-1", scheduledDate = "2026-10-19", scheduledTime = "10:30", zoneId = "UTC",
        hospital = "合成医院", items = "合成产检", preparation = "", advice = "", status = status, createdAt = 1, updatedAt = 1,
        deletedAt = deleted, reminderDate = date, reminderTime = time,
    )

    private fun plan(status: String = "ACTIVE", deleted: Long? = null) = MedicationPlanEntity(
        id = "plan-1", pregnancyId = "pregnancy-1", medicationId = "med-1", logicalPlanId = "logical-1", version = 1,
        dose = "0.125", unit = "片", frequencyType = "DAILY_TIMES", times = "08:00", weekdays = "", zoneId = "UTC",
        startDate = "2026-10-18", endDate = null, status = status, instructions = "", source = "", effectiveAt = 1,
        deletedAt = deleted, medicationName = "合成药品",
    )

    private fun occurrence(at: Long = now + 3_600_000, state: String = "ACTIVE", deleted: Long? = null) = MedicationOccurrenceEntity(
        id = "occurrence-1", pregnancyId = "pregnancy-1", planVersionId = "plan-1", plannedDate = "2026-10-18",
        localTime = "09:00", ordinal = 0, scheduledAt = at, zoneId = "UTC", state = state, createdAt = 1, deletedAt = deleted,
    )

    @Test fun visitReminderUsesLocalWallClockAndSkipsInactiveOrPastRows() {
        val event = requireNotNull(ReminderRules.visitEvent(visit(), now, horizon, ZoneOffset.UTC))
        assertEquals("VISIT:visit-1:2026-10-18:09:00", event.key)
        assertEquals("产检提醒", event.title)
        assertTrue(event.body.contains("合成医院"))
        assertEquals(Instant.parse("2026-10-18T09:00:00Z").toEpochMilli(), event.triggerAt)
        assertNull(ReminderRules.visitEvent(visit(time = "00:30"), now, horizon, ZoneOffset.UTC))
        assertNull(ReminderRules.visitEvent(visit(date = null), now, horizon, ZoneOffset.UTC))
        assertNull(ReminderRules.visitEvent(visit(status = "CANCELLED"), now, horizon, ZoneOffset.UTC))
        assertNull(ReminderRules.visitEvent(visit(date = "2026-10-26"), now, horizon, ZoneOffset.UTC))
    }

    @Test fun visitReminderHandlesDstGapWithoutSilentMidnight() {
        val event = requireNotNull(ReminderRules.visitEvent(visit(date = "2026-03-08", time = "02:30"), Instant.parse("2026-03-08T00:00:00Z").toEpochMilli(),
            Instant.parse("2026-03-10T00:00:00Z").toEpochMilli(), ZoneId.of("America/New_York")))
        assertEquals(Instant.parse("2026-03-08T07:00:00Z").toEpochMilli(), event.triggerAt)
    }

    @Test fun medicationReminderRequiresActivePlanAndOccurrence() {
        val event = requireNotNull(ReminderRules.occurrenceEvent(occurrence(), plan(), now, horizon))
        assertEquals("MEDICATION:occurrence-1", event.key)
        assertEquals("plan-1", event.planVersionId)
        assertTrue(event.body.contains("合成药品"))
        assertNull(ReminderRules.occurrenceEvent(occurrence(), plan(status = "PAUSED"), now, horizon))
        assertNull(ReminderRules.occurrenceEvent(occurrence(state = "CANCELLED"), plan(), now, horizon))
        assertNull(ReminderRules.occurrenceEvent(occurrence(at = now - 1), plan(), now, horizon))
        assertNull(ReminderRules.occurrenceEvent(occurrence(), null, now, horizon))
    }

    @Test fun generationAndSnoozeBoundariesAreExplicit() {
        assertTrue(ReminderRules.isCurrentGeneration(3, 3))
        assertFalse(ReminderRules.isCurrentGeneration(2, 3))
        assertFalse(ReminderRules.isCurrentGeneration(0, 0))
        assertEquals(now + 10 * 60_000L, ReminderRules.snoozeUntil(now))
    }

    @Test fun customReminderProjectsOneTimeDailyAndWeeklyWithoutCrossingEndDate() {
        val base = CustomReminderEntity("custom-1","pregnancy-1","喝水","", "2026-10-18","10:00",
            createdAt=1,updatedAt=1)
        val one = ReminderRules.customEvents(base,now,horizon,ZoneOffset.UTC)
        assertEquals(listOf("CUSTOM:custom-1:2026-10-18:1"),one.map {it.key})
        val daily = ReminderRules.customEvents(base.copy(repeat="DAILY",endDate="2026-10-20"),now,horizon,ZoneOffset.UTC)
        assertEquals(listOf("2026-10-18","2026-10-19","2026-10-20"),daily.map {it.key.split(':')[2]})
        val weekly = ReminderRules.customEvents(base.copy(repeat="WEEKLY"),now,horizon,ZoneOffset.UTC)
        assertEquals(listOf("2026-10-18"),weekly.map {it.key.split(':')[2]})
        assertNull(ReminderRules.customEventOn(base.copy(enabled=false),java.time.LocalDate.parse("2026-10-18"),ZoneOffset.UTC))
    }
}
