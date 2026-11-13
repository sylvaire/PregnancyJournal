package cn.yunji.journal.domain

import cn.yunji.journal.core.database.*
import java.math.BigDecimal

object MeasurementRules {
    fun decimal(raw: String): String {
        val text = raw.trim()
        require(Regex("[0-9]{1,12}(\\.[0-9]{1,6})?").matches(text)) { "请输入最多12位整数、6位小数的非负数值" }
        return BigDecimal(text).stripTrailingZeros().toPlainString()
    }
    data class Group(val logicalId: String, val unit: String, val scenario: String, val minutes: Int?, val key: String)
    data class Point(val measurement: MeasurementEntity, val definition: MetricDefinitionEntity, val value: String)
    fun groups(definitions: List<MetricDefinitionEntity>, records: List<MeasurementEntity>, values: List<MeasurementValueEntity>): Map<Group, List<Point>> {
        val defs = definitions.associateBy { it.id }
        val rows = records.filter { it.deletedAt == null }.associateBy { it.id }
        return values.mapNotNull { value ->
            val row = rows[value.measurementId] ?: return@mapNotNull null
            val def = defs[row.definitionId] ?: return@mapNotNull null
            Group(def.logicalId, def.unit, row.scenario, row.postMealMinutes, value.valueKey) to Point(row, def, value.decimalValue)
        }.groupBy({ it.first }, { it.second }).mapValues { (_, points) -> points.sortedWith(compareBy({ it.measurement.measuredAt }, { it.measurement.id })) }
    }
}
