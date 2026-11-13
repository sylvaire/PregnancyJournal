package cn.yunji.journal.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class GestationalAge(val days: Long) {
    val weeks get() = days / 7
    val remainingDays get() = days % 7
    fun label() = "${weeks}周 + ${remainingDays}天"
}

object PregnancyRules {
    fun age(today: LocalDate, lmp: LocalDate?, confirmedEdd: LocalDate?): GestationalAge? {
        val start = confirmedEdd?.minusDays(280) ?: lmp ?: return null
        val days = ChronoUnit.DAYS.between(start, today)
        return if (days < 0) null else GestationalAge(days)
    }
    fun estimatedEdd(lmp: LocalDate?, confirmed: LocalDate?): LocalDate? = confirmed ?: lmp?.plusDays(280)
    fun validateName(value: String) { require(value.isNotBlank() && value.length <= 100) { "名称需为1至100个字符" } }
}
