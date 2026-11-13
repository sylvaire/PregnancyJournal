package cn.yunji.journal.data

import cn.yunji.journal.core.database.journalWrite
import cn.yunji.journal.core.database.*
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.*
import java.util.UUID

val MEDICATION_FREQUENCIES = linkedMapOf("DAILY_TIMES" to "每天固定时刻", "WEEKLY_TIMES" to "每周指定日期", "ONCE" to "临时一次", "AS_NEEDED" to "按需记录", "TEXT_ONLY" to "仅记录医嘱原文")
fun medicationStatusLabel(value: String) = when(value) { "ACTIVE" -> "进行中"; "PAUSED" -> "已暂停"; else -> "已结束" }

@Serializable
data class MedicationForm(val id: String? = null, val pregnancyId: String = "", val medicationId: String? = null,
    val name: String = "", val specification: String = "", val dosageForm: String = "", val dose: String = "", val unit: String = "",
    val frequency: String = "DAILY_TIMES", val times: String = "", val weekdays: String = "", val startDate: String = LocalDate.now().toString(),
    val endDate: String = "", val instructions: String = "", val source: String = "", val zoneId: String = ZoneId.systemDefault().id,val expectedMedicationRevision: Long? = null)

object MedicationRules {
    fun normalize(form: MedicationForm): MedicationForm {
        require(form.name.trim().isNotEmpty() && form.name.trim().length <= 100) { "请填写药品名称，最多100字" }
        require(form.specification.length <= 120 && form.dosageForm.length <= 40 && form.instructions.length <= 10_000 && form.source.length <= 240) { "填写内容超过长度限制" }
        require(form.frequency in MEDICATION_FREQUENCIES) { "请选择支持的频次" }
        val dose = form.dose.trim(); val unit = form.unit.trim()
        require(dose.isEmpty() == unit.isEmpty()) { "剂量与单位需要同时填写或同时留空" }
        require(unit.length <= 20) { "剂量单位最多20字" }
        val normalizedDose = if (dose.isEmpty()) "" else {
            require(Regex("[0-9]{1,12}(\\.[0-9]{1,6})?").matches(dose) && BigDecimal(dose).signum() > 0) { "剂量需为大于0的数字，最多6位小数" }
            BigDecimal(dose).stripTrailingZeros().toPlainString()
        }
        val start = runCatching { LocalDate.parse(form.startDate) }.getOrElse { throw IllegalArgumentException("请选择有效的开始日期") }
        val end = if (form.frequency == "ONCE") start else form.endDate.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("请选择有效的结束日期") } }
        require(end == null || !end.isBefore(start)) { "结束日期不能早于开始日期" }
        ZoneId.of(form.zoneId)
        val scheduled = form.frequency in setOf("DAILY_TIMES", "WEEKLY_TIMES", "ONCE")
        val times = if (scheduled) form.times.trim().split(Regex("[,，\\s]+")) else emptyList()
        require(!scheduled || (times.size in 1..12 && times.all { Regex("[0-2][0-9]:[0-5][0-9]").matches(it) && runCatching { LocalTime.parse(it) }.isSuccess } && times.distinct().size == times.size)) { "请填写不重复的24小时制时刻，例如08:00,20:00，最多12个" }
        require(form.frequency != "ONCE" || times.size == 1) { "临时一次仅填写一个时刻" }
        val weekdays = if (form.frequency == "WEEKLY_TIMES") form.weekdays.split(',').filter { it.isNotBlank() } else emptyList()
        require(form.frequency != "WEEKLY_TIMES" || (weekdays.isNotEmpty() && weekdays.all { Regex("[1-7]").matches(it) } && weekdays.distinct().size == weekdays.size)) { "请至少选择一个星期" }
        require(form.frequency != "TEXT_ONLY" || form.instructions.isNotBlank()) { "请填写医嘱原文" }
        return form.copy(name = form.name.trim(), specification = form.specification.trim(), dosageForm = form.dosageForm.trim(), dose = normalizedDose, unit = unit,
            times = times.sorted().joinToString(","), weekdays = weekdays.sorted().joinToString(","), endDate = end?.toString().orEmpty())
    }
}

class MedicationRepository(private val db: JournalDatabase, private val clock: Clock) {
    private val dao = db.journalDao()
    fun medications(pregnancyId: String) = dao.medications(pregnancyId)
    fun plans(pregnancyId: String) = dao.medicationPlans(pregnancyId)
    fun history(logicalId: String) = dao.medicationHistory(logicalId)
    fun observeDrug(id: String)=dao.observeMedication(id)
    suspend fun drugForm(pregnancyId: String,id: String?): DrugForm {
        require(dao.pregnancy(pregnancyId)?.let {it.deletedAt==null}==true)
        val old=id?.let {requireNotNull(dao.medication(it))}
        require(old==null || old.pregnancyId==pregnancyId && old.deletedAt==null)
        return DrugForm(requestId=old?.id ?: UUID.randomUUID().toString(),id=id,pregnancyId=pregnancyId,expectedRevision=old?.revision,
            name=old?.name.orEmpty(),specification=old?.specification.orEmpty(),dosageForm=old?.dosageForm.orEmpty())
    }
    suspend fun saveDrug(input: DrugForm): String {
        val f = DrugRules.normalize(input)
        return db.journalWrite {
            UUID.fromString(f.requestId)
            require(dao.pregnancy(f.pregnancyId)?.let {it.deletedAt==null}==true) {"请先恢复所属孕期"}
            val old=f.id?.let {requireNotNull(dao.medication(it))}
            if(old==null)dao.medication(f.requestId)?.let {row ->
                require(row.pregnancyId==f.pregnancyId && row.deletedAt==null)
                dao.clearDraft("drug",f.draftKey);return@journalWrite row.id
            }
            require(old==null || old.pregnancyId==f.pregnancyId && old.deletedAt==null && old.revision==f.expectedRevision) {"药品已变化，请重新打开后编辑"}
            val row=MedicationEntity(old?.id ?: f.requestId,f.pregnancyId,f.name,f.specification,f.dosageForm,old?.createdAt ?: clock.millis(),revision=(old?.revision ?: 0)+1)
            if(old==null)dao.insertMedication(row) else dao.updateMedication(row)
            record("medication",row.id,row.revision);dao.clearDraft("drug",f.draftKey);row.id
        }
    }
    suspend fun form(pregnancyId: String, id: String?): MedicationForm {
        require(dao.pregnancy(pregnancyId)?.let { it.deletedAt == null } == true) { "孕期不存在或已删除" }
        if (id == null) return MedicationForm(pregnancyId = pregnancyId)
        val plan = requireNotNull(dao.medicationPlan(id)); require(plan.pregnancyId == pregnancyId && plan.deletedAt == null)
        val drug = requireNotNull(dao.medication(plan.medicationId))
        return MedicationForm(plan.id, pregnancyId, drug.id, drug.name, drug.specification, drug.dosageForm, plan.dose.orEmpty(), plan.unit.orEmpty(),
            plan.frequencyType, plan.times, plan.weekdays, plan.startDate, plan.endDate.orEmpty(), plan.instructions, plan.source, plan.zoneId,drug.revision)
    }
    suspend fun save(input: MedicationForm): String {
        val form = MedicationRules.normalize(input)
        return db.journalWrite {
            require(dao.pregnancy(form.pregnancyId)?.let { it.deletedAt == null } == true) { "请先恢复所属孕期" }
            val previous = form.id?.let { requireNotNull(dao.medicationPlan(it)) }
            require(previous == null || (previous.pregnancyId == form.pregnancyId && previous.deletedAt == null && previous.status != "ENDED" && dao.latestMedicationPlan(previous.logicalPlanId)?.id == previous.id)) { "方案已有更新或已结束，请返回重新打开" }
            val drug = if (form.medicationId != null) requireNotNull(dao.medication(form.medicationId)) else {
                require(previous == null)
                MedicationEntity(UUID.randomUUID().toString(), form.pregnancyId, form.name, form.specification, form.dosageForm, clock.millis()).also {
                    dao.insertMedication(it); record("medication", it.id, 1)
                }
            }
            require(drug.pregnancyId == form.pregnancyId && drug.deletedAt == null && (previous == null || previous.medicationId == drug.id)) { "药品与孕期不匹配" }
            if(form.medicationId!=null)require(if(form.expectedMedicationRevision!=null)form.expectedMedicationRevision==drug.revision else form.name==drug.name && form.specification==drug.specification && form.dosageForm==drug.dosageForm) {"药品信息已变化，请重新选择药品或重新打开方案"}
            val id = UUID.randomUUID().toString()
            val entity = MedicationPlanEntity(id, form.pregnancyId, drug.id, previous?.logicalPlanId ?: id, (previous?.version ?: 0) + 1,
                form.dose.ifEmpty { null }, form.unit.ifEmpty { null }, form.frequency, form.times, form.weekdays, form.zoneId, form.startDate, form.endDate.ifEmpty { null },
                previous?.status ?: "ACTIVE", form.instructions, form.source.trim(), maxOf(clock.millis(), (previous?.effectiveAt ?: Long.MIN_VALUE) + 1),
                medicationName=drug.name,medicationSpecification=drug.specification,medicationDosageForm=drug.dosageForm)
            dao.insertMedicationPlan(entity); record("medicationPlan", id, 1)
            dao.clearDraft("medicationPlan", form.id ?: "new:${form.pregnancyId}")
            id
        }
    }
    suspend fun changeStatus(id: String, status: String): Unit = db.journalWrite {
        val old = requireNotNull(dao.medicationPlan(id))
        require(dao.pregnancy(old.pregnancyId)?.let { it.deletedAt == null } == true && old.deletedAt == null)
        require(dao.latestMedicationPlan(old.logicalPlanId)?.id == id) { "方案已有更新，请重新打开" }
        require(old.status != "ENDED" && status in setOf("ACTIVE", "PAUSED", "ENDED") && old.status != status) { "此状态不能变更" }
        val updated = old.copy(id = UUID.randomUUID().toString(), version = old.version + 1, status = status, effectiveAt = maxOf(clock.millis(), old.effectiveAt + 1), revision = 1)
        dao.insertMedicationPlan(updated); record("medicationPlan", updated.id, 1)
    }
    private suspend fun record(type: String, id: String, revision: Long) { dao.recordChange(ChangeEntity(entityType = type, entityId = id, revision = revision, operation = "UPSERT", createdAt = clock.millis())) }
}
