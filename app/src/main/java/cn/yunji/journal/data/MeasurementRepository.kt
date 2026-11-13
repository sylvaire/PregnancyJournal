package cn.yunji.journal.data

import cn.yunji.journal.core.database.*
import cn.yunji.journal.domain.MeasurementRules
import kotlinx.serialization.Serializable
import java.time.*
import java.util.UUID

@Serializable
data class MeasurementForm(val requestId: String = UUID.randomUUID().toString(), val pregnancyId: String = "",
    val id: String? = null, val expectedRevision: Long? = null, val definitionId: String = "", val values: Map<String,String> = emptyMap(),
    val date: String = "", val time: String = "", val zoneId: String = ZoneId.systemDefault().id, val offsetSeconds: Int? = null,
    val scenario: String = "FASTING", val minutes: String = "120", val source: String = "手动记录", val note: String = "", val originalMeasuredAt: Long? = null) {
    val draftKey get() = id ?: "new:$pregnancyId"
}

class MeasurementRepository(private val db: JournalDatabase, private val clock: Clock) {
    private val dao = db.journalDao()
    fun definitions(id: String) = dao.metricDefinitions(id)
    fun records(id: String) = dao.measurements(id)
    fun values(id: String) = dao.measurementValues(id)
    private suspend fun parent(id: String) { require(dao.pregnancy(id)?.let { it.deletedAt == null } == true) { "请先创建或恢复孕期" } }
    suspend fun ensureBuiltIns(id: String): Unit = db.journalWrite {
        parent(id)
        val definitions = dao.allMetricDefinitions().filter { it.pregnancyId == id && it.deletedAt == null }
        listOf(listOf("WEIGHT","体重","kg","value"), listOf("PRESSURE","血压","mmHg","systolic,diastolic,pulse"), listOf("GLUCOSE","血糖","mmol/L","value")).forEach { (kind,name,unit,keys) ->
            if (definitions.none { it.kind == kind }) {
                val defId = UUID.randomUUID().toString()
                dao.insertMetricDefinition(MetricDefinitionEntity(defId,id,defId,1,kind,name,unit,keys,clock.millis()))
                change("metricDefinition",defId,1)
            }
        }
    }
    suspend fun saveDefinition(pregnancyId: String, name: String, unit: String, previousId: String? = null): String = db.journalWrite {
        parent(pregnancyId)
        require(name.trim().length in 1..40 && unit.trim().length in 1..20) { "指标名称需1–40字，单位需1–20字" }
        val old = previousId?.let { requireNotNull(dao.metricDefinition(it)) }
        require(old == null || (old.pregnancyId == pregnancyId && old.kind == "CUSTOM" && old.deletedAt == null))
        require(old == null || dao.allMetricDefinitions().none { it.logicalId == old.logicalId && it.version > old.version }) { "指标定义已更新，请重新打开" }
        if (old != null && old.name == name.trim() && old.unit == unit.trim()) return@journalWrite old.id
        val id = UUID.randomUUID().toString()
        dao.insertMetricDefinition(MetricDefinitionEntity(id,pregnancyId,old?.logicalId ?: id,(old?.version ?: 0)+1,"CUSTOM",name.trim(),unit.trim(),"value",clock.millis()))
        change("metricDefinition",id,1); id
    }
    suspend fun form(pregnancyId: String, id: String? = null): MeasurementForm {
        ensureBuiltIns(pregnancyId)
        val old = id?.let { requireNotNull(dao.measurement(it)) }
        require(old == null || old.pregnancyId == pregnancyId && old.deletedAt == null)
        val def = old?.definitionId ?: dao.allMetricDefinitions().first { it.pregnancyId == pregnancyId && it.kind == "WEIGHT" && it.deletedAt == null }.id
        val actual = old?.let { Instant.ofEpochMilli(it.measuredAt).atZone(ZoneId.of(it.zoneId)) } ?: clock.instant().atZone(ZoneId.systemDefault())
        return MeasurementForm(requestId = old?.id ?: UUID.randomUUID().toString(), pregnancyId = pregnancyId, id = id, expectedRevision = old?.revision,
            definitionId = def, values = old?.let { dao.valuesForMeasurement(it.id).associate { v -> v.valueKey to v.decimalValue } }.orEmpty(),
            date = actual.toLocalDate().toString(), time = actual.toLocalTime().withNano(0).toString(), zoneId = actual.zone.id, offsetSeconds = actual.offset.totalSeconds,
            scenario = old?.scenario?.takeIf { it != "NONE" } ?: "FASTING", minutes = old?.postMealMinutes?.toString() ?: "120", source = old?.source ?: "手动记录", note = old?.note.orEmpty(), originalMeasuredAt=old?.measuredAt)
    }
    suspend fun save(form: MeasurementForm): String = db.journalWrite {
        UUID.fromString(form.requestId); parent(form.pregnancyId)
        val old = form.id?.let { requireNotNull(dao.measurement(it)) }
        if (old == null) dao.measurement(form.requestId)?.let {
            require(it.pregnancyId == form.pregnancyId && it.deletedAt == null) { "记录已删除，请重新打开" }
            dao.clearDraft("measurement",form.draftKey); return@journalWrite it.id
        }
        require(old == null || old.pregnancyId == form.pregnancyId && old.deletedAt == null && old.revision == form.expectedRevision) { "记录已变化，请重新打开后修改" }
        val def = requireNotNull(dao.metricDefinition(form.definitionId))
        require(def.pregnancyId == form.pregnancyId && def.deletedAt == null)
        // Editing a measurement retains its original definition, even after a unit version change.
        require(old == null || old.definitionId == def.id) { "修改时保留原指标与单位，新指标请另记一条" }
        val keys = def.valueKeys.split(",")
        require(form.values.keys.all { it in keys })
        val required = if (def.kind == "PRESSURE") listOf("systolic","diastolic") else keys
        require(required.all { !form.values[it].isNullOrBlank() }) { "请填写完整测量值" }
        val values = keys.mapNotNull { key -> form.values[key]?.takeIf { it.isNotBlank() }?.let { key to MeasurementRules.decimal(it) } }.toMap()
        val zone = ZoneId.of(form.zoneId)
        val local = LocalDate.parse(form.date).atTime(LocalTime.parse(form.time))
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) { "该时区不存在此时刻，请核对时间" }
        val offset = offsets.firstOrNull { it.totalSeconds == form.offsetSeconds } ?: offsets.first()
        val original = old?.let {Instant.ofEpochMilli(it.measuredAt).atZone(ZoneId.of(it.zoneId))}
        val preserve = original != null && form.originalMeasuredAt==old.measuredAt && form.zoneId==old.zoneId &&
            original.toLocalDateTime().withNano(0)==local && original.offset==offset
        val at = if (preserve) old!!.measuredAt else local.toInstant(offset).toEpochMilli()
        require(at <= clock.millis()) { "测量时间不能晚于现在" }
        require(form.source.length <= 120 && form.note.length <= 10_000)
        val scenario = if (def.kind == "GLUCOSE") form.scenario else "NONE"
        require(scenario in setOf("FASTING","POST_MEAL","OTHER","NONE"))
        val minutes = if (scenario == "POST_MEAL") form.minutes.toIntOrNull().also { require(it != null && it in 1..1440) { "请填写餐后分钟数（1–1440）" } } else null
        val row = MeasurementEntity(old?.id ?: form.requestId,form.pregnancyId,def.id,at,zone.id,scenario,minutes,form.source.trim(),form.note,
            old?.recordedAt ?: clock.millis(),clock.millis(),(old?.revision ?: 0)+1)
        if (old == null) dao.insertMeasurement(row) else { dao.updateMeasurement(row); dao.deleteMeasurementValues(row.id) }
        dao.insertMeasurementValues(values.map { (key,value) -> MeasurementValueEntity(row.id,key,value) })
        change("measurement",row.id,row.revision); dao.clearDraft("measurement",form.draftKey); row.id
    }
    private suspend fun change(type: String,id: String,revision: Long) = dao.recordChange(ChangeEntity(entityType=type,entityId=id,revision=revision,operation="UPSERT",createdAt=clock.millis()))
}
