package cn.yunji.journal

import cn.yunji.journal.data.*
import org.junit.Assert.*
import org.junit.Test

class MedicationRulesTest {
    private fun form() = MedicationForm(name = "合成药品", dose = "00.125000", unit = "片", times = "20:00，08:00", startDate = "2026-10-18")
    @Test fun decimalAndTimesAreCanonicalWithoutFloatingPoint() {
        val normalized = MedicationRules.normalize(form())
        assertEquals("0.125", normalized.dose)
        assertEquals("08:00,20:00", normalized.times)
    }
    @Test fun invalidDosePairsAndAmbiguousTimesAreRejected() {
        val invalid = listOf(form().copy(unit = ""), form().copy(dose = "0"), form().copy(dose = "1e3"), form().copy(dose = "0.1234567"),
            form().copy(times = "08:00,08:00"), form().copy(times = "24:00"), form().copy(endDate = "2026-10-17"), form().copy(frequency = "WEEKLY_TIMES"))
        invalid.forEach { input -> try { MedicationRules.normalize(input); fail("Invalid rule accepted") } catch (_: IllegalArgumentException) { } }
    }
    @Test fun textAndAsNeededHaveNoScheduleAndOnceHasOneDate() {
        val text = MedicationRules.normalize(form().copy(frequency = "TEXT_ONLY", instructions = "仅保存原文", weekdays = "1,3"))
        assertEquals("", text.times); assertEquals("", text.weekdays)
        assertEquals("", MedicationRules.normalize(form().copy(frequency = "AS_NEEDED")).times)
        assertEquals("2026-10-18", MedicationRules.normalize(form().copy(frequency = "ONCE", times = "08:00", endDate = "2025-01-01")).endDate)
    }
    @Test fun drugRulesTrimAndBoundOnlyBasicInformation() {
        val form = DrugForm(pregnancyId = "p", name = " 合成药品 ", specification = " 规格 ", dosageForm = " 片剂 ")
        val normalized = DrugRules.normalize(form)
        assertEquals("合成药品", normalized.name); assertEquals("规格", normalized.specification); assertEquals("片剂", normalized.dosageForm)
        assertEquals("p", normalized.pregnancyId)
        listOf(form.copy(name = ""), form.copy(name = "a".repeat(101)), form.copy(specification = "s".repeat(121)), form.copy(dosageForm = "f".repeat(41))).forEach { invalid ->
            try { DrugRules.normalize(invalid); fail("Invalid drug accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
