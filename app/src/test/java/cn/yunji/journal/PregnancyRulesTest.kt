package cn.yunji.journal

import cn.yunji.journal.domain.PregnancyRules
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PregnancyRulesTest {
    @Test fun confirmedEddOverridesLmp() {
        val age = PregnancyRules.age(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 5, 1), LocalDate.of(2027, 3, 17))!!
        assertEquals(18L, age.weeks); assertEquals(3L, age.remainingDays)
    }
    @Test fun handlesLeapYearAndMissingDates() {
        assertEquals(1L, PregnancyRules.age(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 28), null)!!.days)
        assertNull(PregnancyRules.age(LocalDate.now(), null, null))
        assertNull(PregnancyRules.age(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), null))
    }
}
