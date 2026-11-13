package cn.yunji.journal.data

import cn.yunji.journal.core.database.journalWrite
import cn.yunji.journal.core.database.*
import cn.yunji.journal.domain.PregnancyRules
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.*

class JournalRepository constructor(private val db: JournalDatabase, private val clock: Clock) {
    val medications by lazy { MedicationRepository(db, clock) }
    val intakes by lazy { IntakeRepository(db, clock) }
    val measurements by lazy { MeasurementRepository(db, clock) }
    val journalEntries by lazy { JournalEntryRepository(db, clock) }
    private val dao get() = db.journalDao()
    val pregnancies get() = dao.pregnancies()
    val visits get() = dao.visits()
    val customReminders get() = dao.customReminders()
    val dateRevisions get() = dao.dateRevisions()
    suspend fun pregnancy(id: String) = dao.pregnancy(id)
    suspend fun visit(id: String) = dao.visit(id)
    suspend fun saveCustomReminder(id: String?, pregnancyId: String, title: String, note: String,
        startDate: LocalDate, localTime: LocalTime, repeat: String, endDate: LocalDate?): String = db.journalWrite {
        require(title.trim().isNotEmpty() && title.trim().length <= 100 && note.length <= 1000)
        require(repeat in setOf("NONE", "DAILY", "WEEKLY"))
        require(endDate == null || !endDate.isBefore(startDate))
        require(repeat != "NONE" || cn.yunji.journal.domain.MedicationSchedule.resolve(startDate, localTime, ZoneId.systemDefault()).toEpochMilli() > clock.millis()) {
            "单次提醒时间必须在未来"
        }
        require(dao.pregnancy(pregnancyId)?.let { it.deletedAt == null && it.status == "ONGOING" } == true) { "请选择进行中的孕期" }
        val old = id?.let { requireNotNull(dao.customReminder(it)) { "提醒已删除" } }
        require(old == null || old.pregnancyId == pregnancyId && old.deletedAt == null)
        val now = clock.millis()
        val row = CustomReminderEntity(id = old?.id ?: UUID.randomUUID().toString(), pregnancyId = pregnancyId,
            title = title.trim(), note = note.trim(), startDate = startDate.toString(), localTime = localTime.toString(),
            repeat = repeat, endDate = endDate?.toString(), createdAt = old?.createdAt ?: now,
            updatedAt = now, revision = (old?.revision ?: 0) + 1)
        dao.putCustomReminder(row)
        dao.recordChange(ChangeEntity(entityType = "customReminder", entityId = row.id, revision = row.revision,
            operation = "UPSERT", createdAt = now))
        row.id
    }

    suspend fun deleteCustomReminder(id: String) = db.journalWrite {
        val old = requireNotNull(dao.customReminder(id)) { "提醒已删除" }
        dao.deleteCustomReminder(id)
        dao.recordChange(ChangeEntity(entityType = "customReminder", entityId = id, revision = old.revision + 1,
            operation = "DELETE", createdAt = clock.millis()))
    }

    suspend fun savePregnancy(id: String?, title: String, lmp: LocalDate?, edd: LocalDate?, hospital: String, note: String): String {
        PregnancyRules.validateName(title.trim())
        require(note.length <= 10_000 && hospital.length <= 120)
        val now = clock.millis()
        return db.journalWrite {
            val existing = id?.let { dao.pregnancy(it) }
            require(id == null || existing != null) { "档案不存在" }
            require(existing?.deletedAt == null) { "档案已删除，请从回收站恢复" }
            val entity = PregnancyEntity(id = existing?.id ?: UUID.randomUUID().toString(), title = title.trim(),
                lmpDate = lmp?.toString(), confirmedEdd = edd?.toString(), hospital = hospital.trim(), note = note,
                createdAt = existing?.createdAt ?: now, updatedAt = now,
                revision = (existing?.revision ?: 0) + 1, status = existing?.status ?: "ONGOING")
            dao.putPregnancy(entity)
            if (existing == null || existing.lmpDate != entity.lmpDate || existing.confirmedEdd != entity.confirmedEdd) {
                dao.putDateRevision(PregnancyDateRevisionEntity(UUID.randomUUID().toString(), entity.id,
                    entity.lmpDate, entity.confirmedEdd, if (edd != null) "DOCTOR" else if (lmp != null) "LMP" else "UNKNOWN", now))
            }
            dao.recordChange(ChangeEntity(entityType = "pregnancy", entityId = entity.id, revision = entity.revision,
                operation = "UPSERT", createdAt = now))
            dao.clearDraft("pregnancy", id ?: "new")
            entity.id
        }
    }

    suspend fun setPregnancyStatus(id: String, status: String) = db.journalWrite {
        require(status in setOf("ONGOING", "ENDED", "ARCHIVED"))
        val old = requireNotNull(dao.pregnancy(id))
        require(old.deletedAt == null)
        val entity = old.copy(status = status, updatedAt = clock.millis(), revision = old.revision + 1)
        dao.putPregnancy(entity)
        dao.recordChange(ChangeEntity(entityType = "pregnancy", entityId = id, revision = entity.revision,
            operation = "UPSERT", createdAt = clock.millis()))
    }

    suspend fun saveVisit(id: String?, pregnancyId: String, date: LocalDate, time: LocalTime?, hospital: String, items: String, preparation: String): String =
        db.journalWrite {
            val pregnancy = requireNotNull(dao.pregnancy(pregnancyId)) { "请先创建孕期档案" }
            require(pregnancy.deletedAt == null)
            require(hospital.length <= 120 && items.length <= 10_000 && preparation.length <= 10_000)
            val old = id?.let { dao.visit(it) }
            require(id == null || old != null)
            require(old == null || old.pregnancyId == pregnancyId)
            require(old?.deletedAt == null) { "记录已删除" }
            require(old == null || old.status != "CANCELLED") { "请先恢复已取消的预约" }
            val now = clock.millis()
            val entity = VisitEntity(id = old?.id ?: UUID.randomUUID().toString(), pregnancyId = pregnancyId,
                scheduledDate = date.toString(), scheduledTime = time?.toString(), zoneId = ZoneId.systemDefault().id,
                hospital = hospital.trim(), items = items, preparation = preparation,
                gestationalDays = if (old != null) old.gestationalDays else PregnancyRules.age(date, pregnancy.lmpDate?.let(LocalDate::parse), pregnancy.confirmedEdd?.let(LocalDate::parse))?.days,
                dateRevisionId = if (old != null) old.dateRevisionId else dao.latestDateRevision(pregnancyId)?.id,
                createdAt = old?.createdAt ?: now, updatedAt = now, revision = (old?.revision ?: 0) + 1,
                status = old?.status ?: "SCHEDULED", actualDate = old?.actualDate, advice = old?.advice.orEmpty(),
                actualTime = old?.actualTime, actualHospital = old?.actualHospital, actualResult = old?.actualResult,
                reminderDate = old?.reminderDate, reminderTime = old?.reminderTime)
            dao.putVisit(entity)
            dao.recordChange(ChangeEntity(entityType = "visit", entityId = entity.id, revision = entity.revision,
                operation = "UPSERT", createdAt = now))
            dao.clearDraft("visit", id ?: "new:$pregnancyId")
            entity.id
        }

    suspend fun completeVisit(id: String, actualDate: LocalDate, advice: String,
        actualTime: LocalTime? = null, actualHospital: String = "", actualResult: String = "") = db.journalWrite {
        val old = requireNotNull(dao.visit(id))
        require(old.deletedAt == null && old.status != "CANCELLED") { "已取消或删除的预约不能记录就诊" }
        require(dao.pregnancy(old.pregnancyId)?.deletedAt == null)
        require(!actualDate.isAfter(LocalDate.now(clock))) { "实际就诊日期不能晚于今天" }
        require(advice.length <= 10_000 && actualHospital.length <= 120 && actualResult.length <= 10_000)
        val updated = old.copy(status = "COMPLETED", actualDate = actualDate.toString(), advice = advice,
            actualTime = actualTime?.toString(), actualHospital = actualHospital.trim().takeIf { it.isNotEmpty() },
            actualResult = actualResult.trim().takeIf { it.isNotEmpty() },
            updatedAt = clock.millis(), revision = old.revision + 1)
        dao.putVisit(updated)
        dao.recordChange(ChangeEntity(entityType = "visit", entityId = id, revision = updated.revision,
            operation = "UPSERT", createdAt = clock.millis()))
        dao.clearDraft("actualVisit", id)
    }

    suspend fun setVisitCancelled(id: String, cancelled: Boolean): Unit = db.journalWrite {
        val old = requireNotNull(dao.visit(id)) { "预约不存在" }
        require(old.deletedAt == null)
        require(dao.pregnancy(old.pregnancyId)?.deletedAt == null)
        require(old.status != "COMPLETED") { "已完成的就诊不能取消预约" }
        val status = if (cancelled) "CANCELLED" else "SCHEDULED"
        if (old.status == status) return@journalWrite
        val updated = old.copy(status = status, updatedAt = clock.millis(), revision = old.revision + 1)
        dao.putVisit(updated)
        dao.recordChange(ChangeEntity(entityType = "visit", entityId = id, revision = updated.revision,
            operation = "UPSERT", createdAt = clock.millis()))
    }

    suspend fun setVisitReminder(id: String, date: String, time: String): Unit = db.journalWrite {
        val old = requireNotNull(dao.visit(id)) { "预约不存在" }
        require(old.deletedAt == null && old.status == "SCHEDULED") { "只有待进行的预约可以设置提醒" }
        require(dao.pregnancy(old.pregnancyId)?.let { it.deletedAt == null } == true) { "请先恢复所属孕期" }
        val reminderDate = runCatching { LocalDate.parse(date) }.getOrElse { throw IllegalArgumentException("请选择有效的提醒日期") }
        val reminderTime = runCatching { LocalTime.parse(time) }.getOrElse { throw IllegalArgumentException("请填写24小时制提醒时间，例如08:30") }
        val zone = ZoneId.systemDefault()
        val instant = cn.yunji.journal.domain.MedicationSchedule.resolve(reminderDate, reminderTime, zone)
        require(instant.toEpochMilli() > clock.millis()) { "提醒时间需要晚于现在" }
        val updated = old.copy(reminderDate = reminderDate.toString(), reminderTime = reminderTime.toString(), updatedAt = clock.millis(), revision = old.revision + 1)
        dao.putVisit(updated)
        dao.recordChange(ChangeEntity(entityType = "visit", entityId = id, revision = updated.revision, operation = "UPSERT", createdAt = clock.millis()))
    }

    suspend fun clearVisitReminder(id: String): Unit = db.journalWrite {
        val old = requireNotNull(dao.visit(id)) { "预约不存在" }
        require(old.deletedAt == null) { "预约已删除" }
        if (old.reminderDate == null && old.reminderTime == null) return@journalWrite
        val updated = old.copy(reminderDate = null, reminderTime = null, updatedAt = clock.millis(), revision = old.revision + 1)
        dao.putVisit(updated)
        dao.recordChange(ChangeEntity(entityType = "visit", entityId = id, revision = updated.revision, operation = "UPSERT", createdAt = clock.millis()))
    }

    suspend fun saveDraft(feature: String, id: String, payload: String): Unit = db.journalWrite {
        // A delayed editor flush must not repopulate content after permanent deletion.
        if (feature == "pregnancy") dao.pregnancy(id)?.let { require(it.deletedAt == null) }
        if (feature == "measurement") {
            val parentId = Json.parseToJsonElement(payload).jsonObject.getValue("pregnancyId").jsonPrimitive.content
            require(dao.pregnancy(parentId)?.let { it.deletedAt == null } == true)
            if (!id.startsWith("new:")) require(dao.measurement(id)?.let { it.deletedAt == null && it.pregnancyId == parentId } == true)
        }
        if (feature == "journalEntry") {
            val parentId = Json.parseToJsonElement(payload).jsonObject.getValue("pregnancyId").jsonPrimitive.content
            require(dao.pregnancy(parentId)?.let {it.deletedAt==null}==true)
            if(!id.startsWith("new:")) require(dao.journalEntry(id)?.let {it.deletedAt==null && it.pregnancyId==parentId}==true)
        }
        if(feature=="drug") {
            val parentId=Json.parseToJsonElement(payload).jsonObject.getValue("pregnancyId").jsonPrimitive.content
            require(dao.pregnancy(parentId)?.let {it.deletedAt==null}==true)
            if(!id.startsWith("new:"))require(dao.medication(id)?.let {it.deletedAt==null && it.pregnancyId==parentId}==true)
        }
        if (feature == "intake") {
            val parentId = Json.parseToJsonElement(payload).jsonObject.getValue("pregnancyId").jsonPrimitive.content
            require(dao.pregnancy(parentId)?.let { it.deletedAt == null } == true)
            if (!id.startsWith("new:")) require(dao.intake(id)?.let { it.deletedAt == null && it.pregnancyId == parentId } == true)
        }
        if (feature == "medicationPlan") {
            val parentId = Json.parseToJsonElement(payload).jsonObject.getValue("pregnancyId").jsonPrimitive.content
            require(dao.pregnancy(parentId)?.let { it.deletedAt == null } == true)
            if (!id.startsWith("new:")) require(dao.medicationPlan(id)?.let { it.deletedAt == null && it.pregnancyId == parentId } == true)
        }
        if (feature in setOf("visit", "actualVisit")) {
            dao.visit(id)?.let { visit ->
                require(visit.deletedAt == null)
                require(dao.pregnancy(visit.pregnancyId)?.deletedAt == null)
            }
            if (id.startsWith("new:")) dao.pregnancy(id.removePrefix("new:"))?.let { require(it.deletedAt == null) }
            if (id == "new") {
                val parentId = runCatching { Json.parseToJsonElement(payload).jsonObject["pregnancyId"]?.jsonPrimitive?.content }.getOrNull()
                parentId?.let { dao.pregnancy(it) }?.let { require(it.deletedAt == null) }
            }
        }
        dao.saveDraft(DraftEntity(feature, id, payload, clock.millis()))
    }
    suspend fun draft(feature: String, id: String): String? = dao.draft(feature, id)?.payload
    suspend fun clearDraft(feature: String, id: String) = dao.clearDraft(feature, id)

    suspend fun moveDraft(feature: String, oldId: String, newId: String, expectedPayload: String) = db.journalWrite {
        val old = dao.draft(feature, oldId)
        if (old?.payload == expectedPayload && dao.draft(feature, newId) == null) {
            dao.saveDraft(old.copy(draftId = newId))
            dao.clearDraft(feature, oldId)
        }
    }
}

