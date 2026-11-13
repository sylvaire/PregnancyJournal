package cn.yunji.journal.core.export

import cn.yunji.journal.core.database.*
import java.time.Instant
import java.time.ZoneId

/** Business rows the summary is built from. Drafts, credentials and settings are deliberately absent. */
data class SummaryInputs(
    val pregnancies: List<PregnancyEntity> = emptyList(),
    val visits: List<VisitEntity> = emptyList(),
    val medications: List<MedicationEntity> = emptyList(),
    val plans: List<MedicationPlanEntity> = emptyList(),
    val intakes: List<MedicationIntakeEntity> = emptyList(),
    val definitions: List<MetricDefinitionEntity> = emptyList(),
    val measurements: List<MeasurementEntity> = emptyList(),
    val values: List<MeasurementValueEntity> = emptyList(),
)

/**
 * Turns business rows into the summary sections the PDF renders: pregnancy profile, visits, medication
 * plans with their recorded intakes, and the selected metrics.
 *
 * Every line states where it came from, missing values are written as [VisitSummary.EMPTY], and ordering is
 * deterministic so the same data always produces the same document.
 */
object SummaryDataBuilder {
    private fun dateOf(epochMillis: Long, zoneId: String?): String =
        Instant.ofEpochMilli(epochMillis).atZone(runCatching { ZoneId.of(zoneId ?: "UTC") }.getOrDefault(ZoneId.of("UTC")))
            .toLocalDate().toString()

    fun build(inputs: SummaryInputs, scope: ExportScope, pregnancyId: String?, generatedAt: Long): VisitSummary {
        val pregnancies = inputs.pregnancies.filter { pregnancyId == null || it.id == pregnancyId }
            .sortedBy { it.createdAt }
        val selected = pregnancies.map { it.id }.toSet()
        val visits = inputs.visits.filter { it.pregnancyId in selected }
            .filter { scope.includesDate(it.scheduledDate) }.filter { scope.includesTrash(it.deletedAt) }
            .sortedWith(compareBy({ it.scheduledDate }, { it.id }))
        val plans = inputs.plans.filter { it.pregnancyId in selected }
            .filter { scope.includesDate(it.startDate) }.filter { scope.includesTrash(it.deletedAt) }
        val medications = inputs.medications.associateBy { it.id }
        val intakes = inputs.intakes.filter { it.pregnancyId in selected }
            .filter { scope.includesDate(it.eventDate) }.filter { scope.includesTrash(it.deletedAt) }
        val definitions = inputs.definitions.associateBy { it.id }
        val measurements = inputs.measurements.filter { it.pregnancyId in selected }
            .filter { scope.includesDate(dateOf(it.measuredAt, it.zoneId)) }.filter { scope.includesTrash(it.deletedAt) }
            .sortedWith(compareBy({ it.measuredAt }, { it.id }))
        val valuesByMeasurement = inputs.values.groupBy { it.measurementId }

        val sections = mutableListOf<SummarySection>()
        pregnancies.forEach { pregnancy ->
            sections += VisitSummaryComposer.section("孕期信息（${pregnancy.title}）",
                VisitSummaryComposer.keyValue("名称", pregnancy.title),
                VisitSummaryComposer.keyValue("末次月经", pregnancy.lmpDate),
                VisitSummaryComposer.keyValue("预产期", pregnancy.confirmedEdd),
                VisitSummaryComposer.keyValue("建档医院", pregnancy.hospital),
                VisitSummaryComposer.keyValue("状态", statusLabel(pregnancy.status)),
                VisitSummaryComposer.keyValue("备注", pregnancy.note))
        }
        if (visits.isNotEmpty()) {
            val blocks = mutableListOf<SummaryBlock>(
                SummaryBlock.TableRow(listOf("预约日期", "时间", "医院", "状态", "实际就诊"), header = true))
            visits.forEach { visit ->
                blocks += SummaryBlock.TableRow(listOf(
                    visit.scheduledDate, visit.scheduledTime ?: VisitSummary.EMPTY, visit.hospital.ifBlank { VisitSummary.EMPTY },
                    statusLabel(visit.status), visit.actualDate ?: VisitSummary.EMPTY))
                if (visit.items.isNotBlank()) blocks += SummaryBlock.KeyValue("检查项目（${visit.scheduledDate}）", visit.items)
                if (visit.preparation.isNotBlank()) blocks += SummaryBlock.KeyValue("准备事项（${visit.scheduledDate}）", visit.preparation)
                if (visit.actualTime != null) blocks += SummaryBlock.KeyValue("实际就诊时间（${visit.scheduledDate}）", visit.actualTime)
                if (!visit.actualHospital.isNullOrBlank()) blocks += SummaryBlock.KeyValue("就诊医院（${visit.scheduledDate}）", visit.actualHospital)
                if (!visit.actualResult.isNullOrBlank()) blocks += SummaryBlock.KeyValue("检查结果（${visit.scheduledDate}）", visit.actualResult)
                if (visit.advice.isNotBlank()) blocks += SummaryBlock.KeyValue("医生建议（${visit.scheduledDate}）", visit.advice)
            }
            sections += SummarySection("产检记录（${visits.size} 次）", blocks)
        }
        if (plans.isNotEmpty() || intakes.isNotEmpty()) {
            val blocks = mutableListOf<SummaryBlock>()
            plans.sortedWith(compareBy({ it.logicalPlanId }, { it.version })).forEach { plan ->
                val medication = medications[plan.medicationId]
                val name = plan.medicationName.ifBlank { medication?.name ?: VisitSummary.EMPTY }
                blocks += SummaryBlock.KeyValue("药品（${plan.startDate}）", name)
                blocks += SummaryBlock.KeyValue("剂量", listOfNotNull(plan.dose, plan.unit).joinToString(" ").ifBlank { null })
                blocks += SummaryBlock.KeyValue("频次", frequencyLabel(plan.frequencyType))
                blocks += SummaryBlock.KeyValue("时间", plan.times)
                blocks += SummaryBlock.KeyValue("状态", statusLabel(plan.status))
                if (plan.instructions.isNotBlank()) blocks += SummaryBlock.KeyValue("用法说明", plan.instructions)
            }
            if (intakes.isNotEmpty()) {
                blocks += SummaryBlock.Note("服药记录（${intakes.size} 条）：" + intakes.groupingBy { statusLabel(it.status) }
                    .eachCount().entries.sortedBy { it.key }.joinToString("、") { "${it.key} ${it.value} 条" })
                blocks += SummaryBlock.TableRow(listOf("日期", "状态", "剂量"), header = true)
                intakes.sortedWith(compareBy({ it.eventDate }, { it.id })).forEach { intake ->
                    blocks += SummaryBlock.TableRow(listOf(intake.eventDate, statusLabel(intake.status),
                        listOfNotNull(intake.dose, intake.unit).joinToString(" ").ifBlank { VisitSummary.EMPTY }))
                }
            }
            sections += SummarySection("用药（${plans.size} 项方案）", blocks)
        }
        if (measurements.isNotEmpty()) {
            val blocks = mutableListOf<SummaryBlock>()
            measurements.groupBy { it.definitionId }.entries.sortedBy { it.key }.forEach { (definitionId, rows) ->
                val definition = definitions[definitionId]
                val name = definition?.name ?: VisitSummary.EMPTY
                val unit = definition?.unit.orEmpty()
                blocks += SummaryBlock.Heading("$name${if (unit.isBlank()) "" else "（$unit）"}")
                blocks += SummaryBlock.TableRow(listOf("日期", "场景", "数值", "备注"), header = true)
                rows.forEach { measurement ->
                    val text = valuesByMeasurement[measurement.id].orEmpty()
                        .sortedBy { it.valueKey }
                        .joinToString(" / ") { "${it.valueKey} ${it.decimalValue}" }
                        .ifBlank { VisitSummary.EMPTY }
                    blocks += SummaryBlock.TableRow(listOf(dateOf(measurement.measuredAt, measurement.zoneId),
                        scenarioLabel(measurement.scenario, measurement.postMealMinutes), text,
                        measurement.note.ifBlank { VisitSummary.EMPTY }))
                }
            }
            sections += SummarySection("选定指标（${measurements.size} 条）", blocks)
        }
        return VisitSummary(title = "就诊摘要", scopeLabel = scopeLabel(scope), generatedAt = generatedAt, sections = sections)
    }

    fun scopeLabel(scope: ExportScope): String {
        val from = scope.from ?: "不限"
        val to = scope.to ?: "不限"
        return "$from 至 $to" + if (scope.includeTrash) "（含回收站记录）" else ""
    }

    private fun statusLabel(value: String) = when (value) {
        "ONGOING" -> "进行中"; "ENDED" -> "已结束"; "ARCHIVED" -> "已归档"
        "SCHEDULED" -> "已预约"; "DONE" -> "已就诊"; "CANCELLED" -> "已取消"
        "ACTIVE" -> "进行中"; "PAUSED" -> "已暂停"; "TAKEN" -> "已服用"; "SKIPPED" -> "已跳过"
        "PENDING" -> "待记录"; else -> value.ifBlank { VisitSummary.EMPTY }
    }

    private fun frequencyLabel(value: String) = when (value) {
        "DAILY_TIMES" -> "每日固定时间"; "WEEKLY" -> "每周"; "ONCE" -> "仅一次"; "AS_NEEDED" -> "按需"
        "TEXT" -> "按原文"; else -> value.ifBlank { VisitSummary.EMPTY }
    }

    private fun scenarioLabel(scenario: String, postMealMinutes: Int?) = when (scenario) {
        "FASTING" -> "空腹"; "POST_MEAL" -> "餐后" + (postMealMinutes?.let { "${it}分钟" } ?: "")
        "RANDOM" -> "随机"; "NONE" -> VisitSummary.EMPTY; else -> scenario.ifBlank { VisitSummary.EMPTY }
    }
}
