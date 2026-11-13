package cn.yunji.journal

import cn.yunji.journal.core.database.MedicationPlanEntity
import cn.yunji.journal.domain.MedicationSchedule
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MedicationScheduleTest {
    private fun plan() = MedicationPlanEntity("v1", "preg", "med", "logical", 1, "0.125", "片", "DAILY_TIMES", "08:00,20:00", "", "UTC", "2026-01-01", null, "ACTIVE", "", "", 0)
    @Test fun planIntervalsPauseAndResumeNeverRewriteEarlierBasis() {
        val day = LocalDate.parse("2026-10-18")
        val original = plan()
        val pause = original.copy(id = "v2", version = 2, status = "PAUSED", effectiveAt = Instant.parse("2026-10-18T12:00:00Z").toEpochMilli())
        val resumed = original.copy(id = "v3", version = 3, dose = "0.25", effectiveAt = Instant.parse("2026-10-18T15:00:00Z").toEpochMilli())
        assertEquals(listOf("v1"), MedicationSchedule.points(listOf(original, pause), day, ZoneOffset.UTC).map { it.planVersionId })
        val points = MedicationSchedule.points(listOf(original, pause, resumed), day, ZoneOffset.UTC)
        assertEquals(listOf("v1", "v3"), points.map { it.planVersionId })
        assertEquals(points.map { it.id }, MedicationSchedule.points(listOf(resumed, original, pause), day, ZoneOffset.UTC).map { it.id })
    }
    @Test fun dstGapUsesFirstValidTimeAndOverlapProducesOnlyOnePoint() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), MedicationSchedule.resolve(LocalDate.parse("2026-03-08"), LocalTime.parse("02:30"), zone))
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), MedicationSchedule.resolve(LocalDate.parse("2026-11-01"), LocalTime.parse("01:30"), zone))
        assertEquals(1, MedicationSchedule.points(listOf(plan().copy(times = "01:30")), LocalDate.parse("2026-11-01"), zone).size)
    }
    @Test fun weeklyOnceTextAndAsNeededRespectTheirMeaning() {
        val day = LocalDate.parse("2026-10-18")
        assertEquals(2, MedicationSchedule.points(listOf(plan().copy(frequencyType = "WEEKLY_TIMES", weekdays = "7")), day, ZoneOffset.UTC).size)
        assertTrue(MedicationSchedule.points(listOf(plan().copy(frequencyType = "WEEKLY_TIMES", weekdays = "1")), day, ZoneOffset.UTC).isEmpty())
        assertTrue(MedicationSchedule.points(listOf(plan().copy(frequencyType = "ONCE", startDate = "2026-10-17")), day, ZoneOffset.UTC).isEmpty())
        listOf("AS_NEEDED", "TEXT_ONLY").forEach { assertTrue(MedicationSchedule.points(listOf(plan().copy(frequencyType = it)), day, ZoneOffset.UTC).isEmpty()) }
    }
}
