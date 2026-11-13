package cn.yunji.journal.data

import cn.yunji.journal.core.database.journalWrite
import cn.yunji.journal.core.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlinx.serialization.json.*

data class DeletionImpact(val visits: Int, val documents: Int, val pending: Int, val plans: Int = 0, val intakes: Int = 0, val measurements: Int = 0, val entries: Int = 0,val medications: Int=0)

/** Database flags precede every file operation. Batch ownership, not timestamps, determines restoration. */
class TrashRepository(private val db: JournalDatabase, private val importer: DocumentImporter, private val clock: Clock) {
    private val dao = db.journalDao()
    private val cleanup = Mutex()
    val batches get() = dao.trashBatches()
    val retained get() = dao.retainedDocuments()
    val jobs get() = dao.observeImportJobs()
    suspend fun parentAvailable(visitId: String): Boolean {
        val visit = dao.visit(visitId) ?: return false
        return dao.pregnancy(visit.pregnancyId)?.let { it.deletedAt == null } == true
    }
    suspend fun documentParentAvailable(document: AttachmentEntity): Boolean {
        document.visitId?.let {return parentAvailable(it)}
        val drug=document.medicationId?.let {dao.medication(it)} ?: return false
        return drug.deletedAt==null && dao.pregnancy(drug.pregnancyId)?.let {it.deletedAt==null}==true
    }
    /** Active visits in the retained document's original pregnancy, for relinking after a visit deletion. */
    suspend fun relinkTargets(document: AttachmentEntity): List<VisitEntity> {
        val source = document.visitId?.let { dao.visit(it) } ?: return emptyList()
        return dao.allVisits().filter { it.pregnancyId == source.pregnancyId && it.deletedAt == null }
    }
    private suspend fun drugReferenced(id: String)=dao.allMedicationPlans().any {it.medicationId==id}
    suspend fun purgeReason(batch: TrashBatchRow): String? = if(batch.kind=="MEDICATION" && drugReferenced(batch.rootId)) "历史方案仍引用此药品，保留至所属孕期整体清理" else null
    suspend fun restoreReason(batch: TrashBatchRow): String? {
        if (batch.state != "DELETED") return "正在彻底清理，无法恢复"
        if (batch.kind == "PREGNANCY") return null
        if(batch.kind=="MEDICATION") {
            val drug=dao.medication(batch.rootId) ?: return "药品已不存在"
            return if(dao.pregnancy(drug.pregnancyId)?.let {it.deletedAt==null}==true) null else "请先恢复所属孕期"
        }
        if(batch.kind=="JOURNAL") {
            val record=dao.journalEntry(batch.rootId) ?: return "记录已不存在"
            return if(dao.pregnancy(record.pregnancyId)?.let {it.deletedAt==null}==true) null else "请先恢复所属孕期"
        }
        if (batch.kind == "MEASUREMENT") {
            val record = dao.measurement(batch.rootId) ?: return "记录已不存在"
            return if (dao.pregnancy(record.pregnancyId)?.let { it.deletedAt == null } == true) null else "请先恢复所属孕期"
        }
        if (batch.kind == "INTAKE") {
            val record = dao.intake(batch.rootId) ?: return "记录已不存在"
            if (dao.pregnancy(record.pregnancyId)?.let { it.deletedAt == null } != true) return "请先恢复所属孕期"
            if(dao.medicationPlan(record.planVersionId)?.deletedAt!=null) return "请先恢复所属药品"
            val other = record.sourceOccurrenceId?.let { dao.intakeByOccurrence(it) }
            return if (other != null && other.id != record.id) "同一计划已有新记录，请先处理新记录" else null
        }
        return if (parentAvailable(batch.rootId)) null else "请先恢复所属孕期"
    }
    suspend fun batchRestorable(batch: TrashBatchRow) = restoreReason(batch) == null

    suspend fun impact(kind: String, id: String): DeletionImpact = withContext(Dispatchers.IO) {
        val visits = dao.allVisits().filter { if (kind == "PREGNANCY") it.pregnancyId == id else it.id == id }
        val ids = visits.map { it.id }.toSet()
        val meds=if(kind=="PREGNANCY")dao.allMedications().filter {it.pregnancyId==id}.map {it.id}.toSet() else if(kind=="MEDICATION")setOf(id) else emptySet()
        val plans=dao.allMedicationPlans().filter {if(kind=="PREGNANCY")it.pregnancyId==id else kind=="MEDICATION" && it.medicationId==id}
        val planIds=plans.map {it.id}.toSet()
        DeletionImpact(visits.count { it.deletedAt == null }, dao.allAttachments().count { (it.visitId in ids || it.medicationId in meds) && it.deletedAt == null },
            dao.allImportJobs().count { (it.visitId in ids || it.medicationId in meds) && it.state in setOf("QUEUED", "IMPORTING", "FAILED") },
            plans.filter {it.deletedAt==null}.distinctBy {it.logicalPlanId}.size,
            dao.allIntakes().count {it.planVersionId in planIds && it.deletedAt==null},
            if (kind == "PREGNANCY") dao.allMeasurements().count { it.pregnancyId == id && it.deletedAt == null } else 0,
            if (kind == "PREGNANCY") dao.allJournalEntries().count {it.pregnancyId==id && it.deletedAt==null} else 0,
            dao.allMedications().count {it.id in meds && it.deletedAt==null})
    }

    suspend fun delete(kind: String, id: String, schedule: Boolean = true): String = withContext(Dispatchers.IO) {
        require(kind in setOf("PREGNANCY", "VISIT", "INTAKE", "MEASUREMENT", "JOURNAL","MEDICATION"))
        val batch = DeletionBatchEntity(UUID.randomUUID().toString(), kind, id, clock.millis())
        db.journalWrite<Unit> {
            if(kind=="MEDICATION") {
                val drug=requireNotNull(dao.medication(id))
                require(drug.deletedAt==null && dao.pregnancy(drug.pregnancyId)?.let {it.deletedAt==null}==true)
                dao.putDeletionBatch(batch)
                dao.updateMedication(drug.copy(deletedAt=batch.deletedAt,deletionBatchId=batch.id,revision=drug.revision+1));change("medication",id,drug.revision+1,"DELETE")
                val plans=dao.allMedicationPlans().filter {it.medicationId==id};val planIds=plans.map {it.id}.toSet()
                plans.filter {it.deletedAt==null}.forEach {dao.updateMedicationPlan(it.copy(deletedAt=batch.deletedAt,deletionBatchId=batch.id,revision=it.revision+1));change("medicationPlan",it.id,it.revision+1,"DELETE")}
                dao.allOccurrences().filter {it.planVersionId in planIds && it.deletedAt==null}.forEach {dao.putOccurrence(it.copy(deletedAt=batch.deletedAt,deletionBatchId=batch.id,revision=it.revision+1));change("medicationOccurrence",it.id,it.revision+1,"DELETE")}
                dao.allIntakes().filter {it.planVersionId in planIds && it.deletedAt==null}.forEach {dao.updateIntake(it.copy(deletedAt=batch.deletedAt,deletionBatchId=batch.id,occurrenceId=null,revision=it.revision+1));change("medicationIntake",it.id,it.revision+1,"DELETE")}
                dao.allAttachments().filter {it.medicationId==id && it.deletedAt==null}.forEach {doc ->
                    dao.putAttachment(doc.copy(deletedAt=batch.deletedAt,deletionBatchId=batch.id,revision=doc.revision+1));change("attachment",doc.id,doc.revision+1,"DELETE")
                    dao.importJob(doc.id)?.let {dao.putImportJob(it.copy(state="DELETED",phase="DONE",error=null))}
                }
                dao.allImportJobs().filter {it.medicationId==id && dao.attachment(it.id)==null && it.state in setOf("QUEUED","IMPORTING","FAILED")}.forEach {dao.putImportJob(it.copy(state="CANCELLED",phase="CANCELLED",error=null))}
                return@journalWrite
            }
            if(kind=="JOURNAL") {
                val record=requireNotNull(dao.journalEntry(id))
                require(record.deletedAt==null && dao.pregnancy(record.pregnancyId)?.let {it.deletedAt==null}==true)
                dao.putDeletionBatch(batch)
                dao.updateJournalEntry(record.copy(deletedAt=batch.deletedAt,deletionBatchId=batch.id,revision=record.revision+1))
                change("journalEntry",id,record.revision+1,"DELETE");return@journalWrite
            }
            if (kind == "MEASUREMENT") {
                val record = requireNotNull(dao.measurement(id))
                require(record.deletedAt == null && dao.pregnancy(record.pregnancyId)?.let {it.deletedAt==null}==true)
                dao.putDeletionBatch(batch)
                dao.updateMeasurement(record.copy(deletedAt=batch.deletedAt,deletionBatchId=batch.id,revision=record.revision+1))
                change("measurement",id,record.revision+1,"DELETE"); return@journalWrite
            }
            if (kind == "INTAKE") {
                val record = requireNotNull(dao.intake(id))
                require(record.deletedAt == null && dao.pregnancy(record.pregnancyId)?.let { it.deletedAt == null } == true)
                dao.putDeletionBatch(batch)
                dao.updateIntake(record.copy(deletedAt = batch.deletedAt, deletionBatchId = batch.id, occurrenceId = null, revision = record.revision + 1))
                change("medicationIntake", id, record.revision + 1, "DELETE")
                return@journalWrite
            }
            val pregnancy = if (kind == "PREGNANCY") requireNotNull(dao.pregnancy(id)) else {
                val visit = requireNotNull(dao.visit(id)); require(visit.deletedAt == null)
                requireNotNull(dao.pregnancy(visit.pregnancyId))
            }
            require(pregnancy.deletedAt == null)
            dao.putDeletionBatch(batch)
            val visits = dao.allVisits().filter { if (kind == "PREGNANCY") it.pregnancyId == id else it.id == id }
            val ids = visits.map { it.id }.toSet()
            val medicationIds=if(kind=="PREGNANCY")dao.allMedications().filter {it.pregnancyId==id}.map {it.id}.toSet() else emptySet()
            if (kind == "PREGNANCY") {
                val updated = pregnancy.copy(deletedAt = batch.deletedAt, deletionBatchId = batch.id, updatedAt = clock.millis(), revision = pregnancy.revision + 1)
                dao.putPregnancy(updated); change("pregnancy", id, updated.revision, "DELETE")
                dao.allJournalEntries().filter {it.pregnancyId==id && it.deletedAt==null}.forEach {
                    dao.updateJournalEntry(it.copy(deletedAt=batch.deletedAt,deletionBatchId=batch.id,revision=it.revision+1));change("journalEntry",it.id,it.revision+1,"DELETE")
                }
                dao.allMetricDefinitions().filter {it.pregnancyId==id && it.deletedAt==null}.forEach {
                    dao.updateMetricDefinition(it.copy(deletedAt=batch.deletedAt,deletionBatchId=batch.id,revision=it.revision+1)); change("metricDefinition",it.id,it.revision+1,"DELETE")
                }
                dao.allMeasurements().filter {it.pregnancyId==id && it.deletedAt==null}.forEach {
                    dao.updateMeasurement(it.copy(deletedAt=batch.deletedAt,deletionBatchId=batch.id,revision=it.revision+1)); change("measurement",it.id,it.revision+1,"DELETE")
                }
                dao.allMedications().filter { it.pregnancyId == id && it.deletedAt == null }.forEach {
                    dao.updateMedication(it.copy(deletedAt = batch.deletedAt, deletionBatchId = batch.id, revision = it.revision + 1))
                    change("medication", it.id, it.revision + 1, "DELETE")
                }
                dao.allMedicationPlans().filter { it.pregnancyId == id && it.deletedAt == null }.forEach {
                    dao.updateMedicationPlan(it.copy(deletedAt = batch.deletedAt, deletionBatchId = batch.id, revision = it.revision + 1))
                    change("medicationPlan", it.id, it.revision + 1, "DELETE")
                }
                dao.allOccurrences().filter { it.pregnancyId == id && it.deletedAt == null }.forEach {
                    dao.putOccurrence(it.copy(deletedAt = batch.deletedAt, deletionBatchId = batch.id, revision = it.revision + 1))
                    change("medicationOccurrence", it.id, it.revision + 1, "DELETE")
                }
                dao.allIntakes().filter { it.pregnancyId == id && it.deletedAt == null }.forEach {
                    dao.updateIntake(it.copy(deletedAt = batch.deletedAt, deletionBatchId = batch.id, occurrenceId = null, revision = it.revision + 1))
                    change("medicationIntake", it.id, it.revision + 1, "DELETE")
                }
            }
            visits.filter { it.deletedAt == null }.forEach { visit ->
                val updated = visit.copy(deletedAt = batch.deletedAt, deletionBatchId = batch.id, updatedAt = clock.millis(), revision = visit.revision + 1)
                dao.putVisit(updated); change("visit", visit.id, updated.revision, "DELETE")
            }
            if (kind == "PREGNANCY") dao.allAttachments().filter { (it.visitId in ids || it.medicationId in medicationIds) && it.deletedAt == null }.forEach { attachment ->
                val updated = attachment.copy(deletedAt = batch.deletedAt, deletionBatchId = batch.id, revision = attachment.revision + 1)
                dao.putAttachment(updated); change("attachment", attachment.id, updated.revision, "DELETE")
                dao.importJob(attachment.id)?.let { dao.putImportJob(it.copy(state = "DELETED", phase = "DONE", error = null)) }
            }
            // Existing originals remain accessible when only their visit is removed; unfinished imports cannot resume into a deleted parent.
            dao.allImportJobs().filter { (it.visitId in ids || it.medicationId in medicationIds) && dao.attachment(it.id) == null && it.state in setOf("QUEUED", "IMPORTING", "FAILED") }.forEach {
                dao.putImportJob(it.copy(state = "CANCELLED", phase = "CANCELLED", error = null))
            }
        }
        if (schedule) importer.scheduleTrashCleanup()
        batch.id
    }

    suspend fun restore(id: String): Unit = withContext(Dispatchers.IO) { db.journalWrite<Unit> {
        val batch = requireNotNull(dao.deletionBatch(id))
        require(batch.state == "DELETED") { "正在彻底删除或已清理，无法恢复" }
        if(batch.kind=="MEDICATION") {
            val drug=requireNotNull(dao.medication(batch.rootId));require(dao.pregnancy(drug.pregnancyId)?.let {it.deletedAt==null}==true) {"请先恢复所属孕期"}
        }
        if(batch.kind=="JOURNAL") {
            val record=requireNotNull(dao.journalEntry(batch.rootId))
            require(dao.pregnancy(record.pregnancyId)?.let {it.deletedAt==null}==true) {"请先恢复所属孕期"}
        }
        dao.allJournalEntries().filter {it.deletionBatchId==id}.forEach {
            dao.updateJournalEntry(it.copy(deletedAt=null,deletionBatchId=null,revision=it.revision+1));change("journalEntry",it.id,it.revision+1,"RESTORE")
        }
        if (batch.kind == "MEASUREMENT") {
            val record = requireNotNull(dao.measurement(batch.rootId))
            require(dao.pregnancy(record.pregnancyId)?.let {it.deletedAt==null}==true) {"请先恢复所属孕期"}
        }
        dao.allMetricDefinitions().filter {it.deletionBatchId==id}.forEach {
            dao.updateMetricDefinition(it.copy(deletedAt=null,deletionBatchId=null,revision=it.revision+1)); change("metricDefinition",it.id,it.revision+1,"RESTORE")
        }
        dao.allMeasurements().filter {it.deletionBatchId==id}.forEach {
            dao.updateMeasurement(it.copy(deletedAt=null,deletionBatchId=null,revision=it.revision+1)); change("measurement",it.id,it.revision+1,"RESTORE")
        }
        if (batch.kind == "INTAKE") {
            val record = requireNotNull(dao.intake(batch.rootId))
            require(dao.pregnancy(record.pregnancyId)?.let { it.deletedAt == null } == true) { "请先恢复所属孕期" }
            require(dao.medicationPlan(record.planVersionId)?.let {it.deletedAt==null}==true) {"请先恢复所属药品"}
        }
        if (batch.kind == "VISIT") {
            val visit = requireNotNull(dao.visit(batch.rootId))
            require(dao.pregnancy(visit.pregnancyId)?.deletedAt == null && dao.pregnancy(visit.pregnancyId) != null) { "请先恢复所属孕期" }
        }
        dao.allPregnancies().filter { it.deletionBatchId == id }.forEach {
            val updated = it.copy(deletedAt = null, deletionBatchId = null, revision = it.revision + 1, updatedAt = clock.millis())
            dao.putPregnancy(updated); change("pregnancy", it.id, updated.revision, "RESTORE")
        }
        dao.allVisits().filter { it.deletionBatchId == id }.forEach {
            val updated = it.copy(deletedAt = null, deletionBatchId = null, revision = it.revision + 1, updatedAt = clock.millis())
            dao.putVisit(updated); change("visit", it.id, updated.revision, "RESTORE")
        }
        dao.allMedications().filter { it.deletionBatchId == id }.forEach {
            dao.updateMedication(it.copy(deletedAt = null, deletionBatchId = null, revision = it.revision + 1))
            change("medication", it.id, it.revision + 1, "RESTORE")
        }
        dao.allMedicationPlans().filter { it.deletionBatchId == id }.forEach {
            dao.updateMedicationPlan(it.copy(deletedAt = null, deletionBatchId = null, revision = it.revision + 1))
            change("medicationPlan", it.id, it.revision + 1, "RESTORE")
        }
        dao.allOccurrences().filter { it.deletionBatchId == id }.forEach {
            dao.putOccurrence(it.copy(deletedAt = null, deletionBatchId = null, revision = it.revision + 1))
            change("medicationOccurrence", it.id, it.revision + 1, "RESTORE")
        }
        dao.allIntakes().filter { it.deletionBatchId == id }.forEach {
            val other = it.sourceOccurrenceId?.let { pointId -> dao.intakeByOccurrence(pointId) }
            require(other == null || other.id == it.id) { "同一计划已有新记录，不能覆盖恢复" }
            dao.updateIntake(it.copy(deletedAt = null, deletionBatchId = null, occurrenceId = it.sourceOccurrenceId, revision = it.revision + 1))
            change("medicationIntake", it.id, it.revision + 1, "RESTORE")
        }
        dao.allAttachments().filter { it.deletionBatchId == id }.forEach {
            val job = dao.importJob(it.id)
            if (job?.state == "DELETED") {
                val updated = it.copy(deletedAt = null, deletionBatchId = null, revision = it.revision + 1)
                dao.putAttachment(updated); dao.putImportJob(job.copy(state = "READY", phase = "DONE"))
                change("attachment", it.id, updated.revision, "RESTORE")
            }
        }
        dao.putDeletionBatch(batch.copy(state = "RESTORED"))
    } }

    suspend fun purge(id: String, schedule: Boolean = true): Unit = withContext(Dispatchers.IO) {
        db.journalWrite<Unit> {
            val batch = requireNotNull(dao.deletionBatch(id))
            require(batch.state in setOf("DELETED", "PURGING"))
            require(batch.kind!="MEDICATION" || !drugReferenced(batch.rootId)) {"历史方案仍引用此药品，请随孕期整体清理"}
            dao.putDeletionBatch(batch.copy(state = "PURGING"))
            if (batch.kind == "PREGNANCY") {
                val visits = dao.allVisits().filter { it.pregnancyId == batch.rootId }.map { it.id }.toSet()
                val meds=dao.allMedications().filter {it.pregnancyId==batch.rootId}.map {it.id}.toSet()
                // Include independently deleted files too; block their restore before any file is removed.
                dao.allAttachments().filter { it.visitId in visits || it.medicationId in meds }.forEach { attachment ->
                    dao.importJob(attachment.id)?.let { dao.putImportJob(it.copy(state = "PURGING", phase = "PURGING", error = null)) }
                }
            }
        }
        if (schedule) importer.scheduleTrashCleanup()
    }

    /** May be resumed after process death. Only successfully cleaned files permit final metadata scrubbing. */
    suspend fun resume(): Unit = withContext(Dispatchers.IO) { cleanup.withLock {
        val expiry = clock.millis() - Duration.ofDays(30).toMillis()
        dao.pendingDeletionBatches().filter { it.state == "DELETED" && it.deletedAt <= expiry }.forEach { if(it.kind!="MEDICATION" || !drugReferenced(it.rootId))purge(it.id, schedule = false) }
        // Batch-owned files share the parent retention period, not an independent timer.
        dao.allAttachments().filter { it.deletedAt != null && it.deletionBatchId == null && it.deletedAt <= expiry }.forEach {
            if (dao.importJob(it.id)?.state == "DELETED") importer.purge(it.id, schedule = false)
        }
        dao.pendingImports().filter { it.state in setOf("CANCELLED", "PURGING") }.forEach { importer.process(it.id) }
        for (batch in dao.pendingDeletionBatches().filter { it.state == "PURGING" }) {
            if(batch.kind=="MEDICATION") {
                for(doc in dao.allAttachments().filter {it.medicationId==batch.rootId}) {importer.purge(doc.id,schedule=false);importer.process(doc.id)}
                db.journalWrite<Unit> {
                    check(!drugReferenced(batch.rootId) && dao.allAttachments().none {it.medicationId==batch.rootId})
                    dao.medication(batch.rootId)?.let {row ->dao.updateMedication(row.copy(name="已删除药品",specification="",dosageForm="",revision=row.revision+1));dao.clearDraft("drug",row.id);change("medication",row.id,row.revision+1,"PURGE")}
                    dao.putDeletionBatch(batch.copy(state="PURGED"))
                }
                continue
            }
            if(batch.kind=="JOURNAL") {
                db.journalWrite<Unit> {
                    dao.journalEntry(batch.rootId)?.let {row ->
                        dao.updateJournalEntry(row.copy(entryDate="1970-01-01",entryTime=null,zoneId="UTC",offsetSeconds=null,symptom="",mood="",text="",createdAt=0,updatedAt=clock.millis(),revision=row.revision+1))
                        dao.clearDraft("journalEntry",row.id);change("journalEntry",row.id,row.revision+1,"PURGE")
                    }
                    dao.putDeletionBatch(batch.copy(state="PURGED"))
                }
                continue
            }
            if (batch.kind == "MEASUREMENT") {
                db.journalWrite<Unit> {
                    dao.measurement(batch.rootId)?.let { record ->
                        dao.deleteMeasurementValues(record.id)
                        dao.updateMeasurement(record.copy(measuredAt=0,zoneId="UTC",scenario="NONE",postMealMinutes=null,source="",note="",updatedAt=clock.millis(),revision=record.revision+1))
                        dao.clearDraft("measurement",record.id); change("measurement",record.id,record.revision+1,"PURGE")
                    }
                    dao.putDeletionBatch(batch.copy(state="PURGED"))
                }
                continue
            }
            if (batch.kind == "INTAKE") {
                db.journalWrite<Unit> {
                    val record = dao.intake(batch.rootId)
                    if (record != null) {
                        dao.updateIntake(record.copy(occurrenceId = null, sourceOccurrenceId = null, status = "REMOVED", actualAt = null, eventDate = "1970-01-01", zoneId = "UTC", dose = null, unit = null, note = "", updatedAt = clock.millis(), revision = record.revision + 1))
                        dao.clearDraft("intake", record.id)
                        change("medicationIntake", record.id, record.revision + 1, "PURGE")
                    }
                    dao.putDeletionBatch(batch.copy(state = "PURGED"))
                }
                continue
            }
            val visits = dao.allVisits().filter { if (batch.kind == "PREGNANCY") it.pregnancyId == batch.rootId else it.id == batch.rootId }
            val ids = visits.map { it.id }.toSet()
            val medicationIds=if(batch.kind=="PREGNANCY")dao.allMedications().filter {it.pregnancyId==batch.rootId}.map {it.id}.toSet() else emptySet()
            if (batch.kind == "PREGNANCY") {
                for (attachment in dao.allAttachments().filter { it.visitId in ids || it.medicationId in medicationIds }) {
                    importer.purge(attachment.id, schedule = false)
                    importer.process(attachment.id)
                }
                check(dao.allAttachments().none { it.visitId in ids || it.medicationId in medicationIds })
            }
            db.journalWrite<Unit> {
                check(dao.deletionBatch(batch.id)?.state == "PURGING")
                for (visit in visits) {
                    val old = requireNotNull(dao.visit(visit.id))
                    dao.putVisit(old.copy(scheduledDate = "1970-01-01", scheduledTime = null, zoneId = "UTC", hospital = "", items = "", preparation = "", advice = "",
                        actualDate = null, actualTime = null, actualHospital = null, actualResult = null,
                        gestationalDays = null, dateRevisionId = null, coverAttachmentId = null, status = "CANCELLED", revision = old.revision + 1))
                    dao.clearDraft("visit", old.id); dao.clearDraft("actualVisit", old.id)
                    change("visit", old.id, old.revision + 1, "PURGE")
                }
                if (batch.kind == "PREGNANCY") {
                    val old = requireNotNull(dao.pregnancy(batch.rootId))
                    dao.putPregnancy(old.copy(title = "已删除孕期", lmpDate = null, confirmedEdd = null, hospital = "", note = "", revision = old.revision + 1))
                    dao.removeDateRevisions(old.id)
                    dao.allCustomReminders().filter { it.pregnancyId == old.id }.forEach {
                        change("customReminder", it.id, it.revision + 1, "PURGE")
                    }
                    dao.purgeCustomReminders(old.id)
                    dao.clearDraft("pregnancy", old.id); dao.clearDraft("visit", "new:${old.id}")
                    val entryIds=dao.allJournalEntries().filter {it.pregnancyId==old.id}.map {dao.clearDraft("journalEntry",it.id);change("journalEntry",it.id,it.revision+1,"PURGE");it.id}.toSet()
                    dao.purgeJournalEntries(old.id)
                    dao.pendingDeletionBatches().filter {it.kind=="JOURNAL" && it.rootId in entryIds}.forEach {dao.putDeletionBatch(it.copy(state="PURGED"))}
                    val measurementIds=dao.allMeasurements().filter {it.pregnancyId==old.id}.map {
                        dao.deleteMeasurementValues(it.id); dao.clearDraft("measurement",it.id); change("measurement",it.id,it.revision+1,"PURGE"); it.id
                    }.toSet()
                    dao.allMetricDefinitions().filter {it.pregnancyId==old.id}.forEach {change("metricDefinition",it.id,it.revision+1,"PURGE")}
                    dao.purgeMeasurements(old.id); dao.purgeMetricDefinitions(old.id)
                    dao.pendingDeletionBatches().filter {it.kind=="MEASUREMENT" && it.rootId in measurementIds}.forEach {dao.putDeletionBatch(it.copy(state="PURGED"))}
                    val intakeIds = dao.allIntakes().filter { it.pregnancyId == old.id }.map { record ->
                        change("medicationIntake", record.id, record.revision + 1, "PURGE"); dao.clearDraft("intake", record.id); record.id
                    }.toSet()
                    dao.allOccurrences().filter { it.pregnancyId == old.id }.forEach { change("medicationOccurrence", it.id, it.revision + 1, "PURGE") }
                    dao.purgeIntakes(old.id); dao.purgeOccurrences(old.id)
                    dao.pendingDeletionBatches().filter { it.kind == "INTAKE" && it.rootId in intakeIds }.forEach { dao.putDeletionBatch(it.copy(state = "PURGED")) }
                    dao.allMedicationPlans().filter { it.pregnancyId == old.id }.forEach { change("medicationPlan", it.id, it.revision + 1, "PURGE"); dao.clearDraft("medicationPlan", it.id) }
                    dao.allMedications().filter { it.pregnancyId == old.id }.forEach { change("medication", it.id, it.revision + 1, "PURGE");dao.clearDraft("drug",it.id) }
                    dao.releaseMedicationImportOwners(old.id)
                    dao.purgeMedicationPlans(old.id); dao.purgeMedications(old.id)
                    dao.pendingDeletionBatches().filter {it.kind=="MEDICATION" && it.rootId in medicationIds}.forEach {dao.putDeletionBatch(it.copy(state="PURGED"))}
                    dao.clearDraft("medicationPlan", "new:${old.id}")
                    dao.allDrafts().filter { draft ->
                        draft.feature in setOf("visit", "actualVisit", "medicationPlan", "intake", "measurement", "journalEntry","drug") && runCatching {
                            Json.parseToJsonElement(draft.payload).jsonObject["pregnancyId"]?.jsonPrimitive?.content == old.id
                        }.getOrDefault(false)
                    }.forEach { dao.clearDraft(it.feature, it.draftId) }
                    change("pregnancy", old.id, old.revision + 1, "PURGE")
                    dao.pendingDeletionBatches().filter { it.kind == "VISIT" && it.rootId in ids }.forEach { dao.putDeletionBatch(it.copy(state = "PURGED")) }
                }
                dao.putDeletionBatch(batch.copy(state = "PURGED"))
            }
        }
    } }

    private suspend fun change(type: String, id: String, revision: Long, operation: String) {
        dao.recordChange(ChangeEntity(entityType = type, entityId = id, revision = revision, operation = operation, createdAt = clock.millis()))
    }
}
