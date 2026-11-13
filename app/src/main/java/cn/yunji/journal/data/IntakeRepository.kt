package cn.yunji.journal.data

import cn.yunji.journal.core.database.*
import cn.yunji.journal.domain.MedicationSchedule
import kotlinx.serialization.Serializable
import java.time.*
import java.util.UUID

@Serializable
data class IntakeForm(val requestId: String = UUID.randomUUID().toString(), val pregnancyId: String = "", val planVersionId: String = "",
    val occurrenceId: String? = null, val recordId: String? = null, val expectedRevision: Long? = null,
    val status: String = "TAKEN", val actualDate: String = "", val actualTime: String = "", val zoneId: String = ZoneId.systemDefault().id,
    val dose: String = "", val unit: String = "", val note: String = "", val originalActualAt: Long? = null,
    val plannedDate: String? = null, val plannedTime: String? = null, val plannedZoneId: String? = null, val offsetSeconds: Int? = null) {
    val draftKey get() = recordId ?: "new:$pregnancyId:${occurrenceId ?: "manual"}"
}

class IntakeRepository(private val db: JournalDatabase, private val clock: Clock) {
    private val dao = db.journalDao()
    fun versions(pregnancyId: String) = dao.observePlanVersions(pregnancyId)
    fun occurrences(pregnancyId: String, date: String) = dao.occurrences(pregnancyId, date)
    fun history(pregnancyId: String) = dao.intakes(pregnancyId)
    fun plannedHistory(pregnancyId: String) = dao.observeOccurrences(pregnancyId)

    suspend fun ensureDay(pregnancyId: String, date: LocalDate, zone: ZoneId): Unit = db.journalWrite {
        requireParent(pregnancyId)
        refreshDay(pregnancyId, date, zone)
    }

    /** Called only under the database writer gate; completed events retain their original basis. */
    private suspend fun refreshDay(pregnancyId: String, date: LocalDate, zone: ZoneId) {
        val plans = dao.allMedicationPlans().filter { it.pregnancyId == pregnancyId && it.deletedAt == null }
        val byPlan = plans.associateBy { it.id }
        val records = dao.allIntakes().filter { it.pregnancyId == pregnancyId && it.deletedAt == null }.associateBy { it.occurrenceId }
        val previous = dao.occurrencesForDate(pregnancyId, date.toString()).filter { it.deletedAt == null }
        val candidates = MedicationSchedule.points(plans, date, zone).associateBy { it.id }
        val preserved = previous.filter { point ->
            val plan = byPlan[point.planVersionId]
            records[point.id] != null || (point.scheduledAt <= clock.millis() && plan != null && MedicationSchedule.applicable(plan, plans, date, point.localTime, point.scheduledAt))
        }
        for (old in previous) {
            val desired = when {
                preserved.any { it.id == old.id } -> old.copy(state = "ACTIVE")
                candidates[old.id] != null -> candidates.getValue(old.id).copy(createdAt = old.createdAt, revision = old.revision)
                else -> old.copy(state = "CANCELLED")
            }
            if (desired != old) {
                val updated = desired.copy(revision = old.revision + 1)
                dao.putOccurrence(updated); change("medicationOccurrence", updated.id, updated.revision, "UPSERT")
            }
        }
        val existing = previous.map { it.id }.toSet()
        for (point in candidates.values.filter { it.id !in existing }) {
            val created = point.copy(createdAt = clock.millis())
            dao.putOccurrence(created); change("medicationOccurrence", point.id, 1, "UPSERT")
        }
    }

    suspend fun form(pregnancyId: String, occurrenceId: String? = null, recordId: String? = null, status: String = "TAKEN", date: String? = null): IntakeForm {
        requireParent(pregnancyId)
        val record = recordId?.let { requireNotNull(dao.intake(it)).also { row -> require(row.pregnancyId == pregnancyId && row.deletedAt == null) } }
        val point = (record?.sourceOccurrenceId ?: occurrenceId)?.let { requireNotNull(dao.occurrence(it)).also { row -> require(row.pregnancyId == pregnancyId && row.deletedAt == null) } }
        val plan = (record?.planVersionId ?: point?.planVersionId)?.let { dao.medicationPlan(it) } ?: dao.allMedicationPlans()
            .filter { it.pregnancyId == pregnancyId && it.deletedAt == null }.groupBy { it.logicalPlanId }.values.map { it.maxBy { p -> p.version } }
            .sortedWith(compareBy<MedicationPlanEntity> { it.status != "ACTIVE" }.thenByDescending { it.effectiveAt }).firstOrNull()
        require(plan != null && plan.pregnancyId == pregnancyId && plan.deletedAt == null) { "请先记录用药方案" }
        val zone = record?.zoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()
        val actual = record?.actualAt?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: clock.instant().atZone(zone)
        return IntakeForm(requestId = record?.id ?: UUID.randomUUID().toString(), pregnancyId = pregnancyId, planVersionId = plan.id,
            occurrenceId = point?.id, recordId = record?.id, expectedRevision = record?.revision, status = record?.status ?: status,
            actualDate = if (record == null && point == null && date != null && LocalDate.parse(date) <= actual.toLocalDate()) date else actual.toLocalDate().toString(),
            actualTime = if (record == null) actual.toLocalTime().withSecond(0).withNano(0).toString() else actual.toLocalTime().withNano(0).toString(), zoneId = zone.id,
            dose = if (record != null) record.dose.orEmpty() else if (status == "TAKEN") plan.dose.orEmpty() else "",
            unit = if (record != null) record.unit.orEmpty() else if (status == "TAKEN") plan.unit.orEmpty() else "", note = record?.note.orEmpty(), originalActualAt = record?.actualAt,
            plannedDate = point?.plannedDate, plannedTime = point?.localTime, plannedZoneId = point?.zoneId, offsetSeconds = actual.offset.totalSeconds)
    }

    suspend fun save(input: IntakeForm): String = db.journalWrite {
        UUID.fromString(input.requestId)
        requireParent(input.pregnancyId)
        require(input.status in setOf("TAKEN", "SKIPPED") && input.note.length <= 10_000) { "请检查状态或备注长度" }
        val plan = requireNotNull(dao.medicationPlan(input.planVersionId)) { "方案版本不存在" }
        require(plan.pregnancyId == input.pregnancyId && plan.deletedAt == null) { "方案与孕期不匹配" }
        if (input.recordId == null) {
            dao.intake(input.requestId)?.let { existing ->
                require(existing.pregnancyId == input.pregnancyId && existing.deletedAt == null) { "这次记录已删除，请刷新后重试" }
                dao.clearDraft("intake", input.draftKey)
                return@journalWrite existing.id
            }
            input.occurrenceId?.let { dao.intakeByOccurrence(it) }?.let { existing ->
                require(existing.pregnancyId == input.pregnancyId && existing.deletedAt == null)
                dao.clearDraft("intake", input.draftKey)
                return@journalWrite existing.id
            }
        }
        val previous = input.recordId?.let { requireNotNull(dao.intake(it)) }
        require(previous == null || (previous.pregnancyId == input.pregnancyId && previous.deletedAt == null && previous.revision == input.expectedRevision && previous.planVersionId == input.planVersionId && previous.sourceOccurrenceId == input.occurrenceId)) { "记录已有更新，请返回重新打开" }
        val point = input.occurrenceId?.let { requireNotNull(dao.occurrence(it)) }
        if (point != null) {
            require(point.pregnancyId == input.pregnancyId && point.planVersionId == plan.id && point.deletedAt == null)
            if (previous == null) {
                refreshDay(input.pregnancyId, LocalDate.parse(point.plannedDate), ZoneId.of(point.zoneId))
                require(dao.occurrence(point.id)?.state == "ACTIVE") { "该计划已变更，请返回刷新" }
            }
        }
        require(point != null || input.status == "TAKEN") { "补记仅记录实际服药，跳过须选择计划项" }
        val zone = runCatching { ZoneId.of(input.zoneId) }.getOrElse { throw IllegalArgumentException("时区无效") }
        val actualAt = if (input.status == "TAKEN") {
            val date = runCatching { LocalDate.parse(input.actualDate) }.getOrElse { throw IllegalArgumentException("请选择有效的实际日期") }
            val time = runCatching { LocalTime.parse(input.actualTime) }.getOrElse { throw IllegalArgumentException("请填写24小时制实际时间，例如08:30") }
            val offsets = zone.rules.getValidOffsets(date.atTime(time))
            require(offsets.isNotEmpty()) { "这个时区当天不存在该时刻，请核对实际时间" }
            val selectedOffset = offsets.firstOrNull { it.totalSeconds == input.offsetSeconds } ?: offsets.first()
            val original = previous?.actualAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
            val value = if (original != null && original.toLocalDate() == date && original.toLocalTime().withNano(0) == time.withNano(0) && previous.zoneId == zone.id && original.offset == selectedOffset)
                previous.actualAt!! else date.atTime(time).toInstant(selectedOffset).toEpochMilli()
            require(value <= clock.millis()) { "实际服药时间不能晚于现在" }
            value
        } else null
        val normalized = if (input.status == "TAKEN") MedicationRules.normalize(MedicationForm(name = "实际记录", dose = input.dose, unit = input.unit, frequency = "AS_NEEDED", startDate = input.actualDate, zoneId = input.zoneId)) else null
        val entity = MedicationIntakeEntity(previous?.id ?: input.requestId, input.pregnancyId, plan.id, point?.id, point?.id, input.status, actualAt,
            actualAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() } ?: point!!.plannedDate,
            zone.id, normalized?.dose?.ifEmpty { null }, normalized?.unit?.ifEmpty { null }, input.note,
            previous?.recordedAt ?: clock.millis(), clock.millis(), (previous?.revision ?: 0) + 1)
        if (previous == null) dao.insertIntake(entity) else dao.updateIntake(entity)
        change("medicationIntake", entity.id, entity.revision, "UPSERT")
        dao.clearDraft("intake", input.draftKey)
        entity.id
    }

    private suspend fun requireParent(id: String) { require(dao.pregnancy(id)?.let { it.deletedAt == null } == true) { "请先恢复所属孕期" } }
    private suspend fun change(type: String, id: String, revision: Long, operation: String) {
        dao.recordChange(ChangeEntity(entityType = type, entityId = id, revision = revision, operation = operation, createdAt = clock.millis()))
    }
}
