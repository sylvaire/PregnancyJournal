package cn.yunji.journal.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalDao {
    @Insert suspend fun insertObjectPins(rows: List<BackupObjectPin>)
    @Query("SELECT * FROM backup_object_pins") suspend fun objectPins(): List<BackupObjectPin>
    @Query("DELETE FROM backup_object_pins WHERE leaseId=:leaseId") suspend fun removeObjectPins(leaseId: String)
    @Upsert suspend fun retireObject(row: RetiredObject)
    @Query("SELECT * FROM retired_objects") suspend fun retiredObjects(): List<RetiredObject>
    @Query("DELETE FROM retired_objects WHERE objectId=:objectId") suspend fun removeRetiredObject(objectId: String)
    @Query("SELECT * FROM measurement_values ORDER BY measurementId,valueKey") suspend fun allMeasurementValues(): List<MeasurementValueEntity>
    @Query("SELECT * FROM deletion_batches ORDER BY deletedAt,id") suspend fun allDeletionBatches(): List<DeletionBatchEntity>
    @Query("SELECT * FROM attachments WHERE medicationId=:id ORDER BY createdAt,id") fun medicationAttachments(id: String): Flow<List<AttachmentEntity>>
    @Query("SELECT * FROM import_jobs WHERE medicationId=:id ORDER BY createdAt,id") fun medicationImports(id: String): Flow<List<ImportJobEntity>>
    @Query("SELECT * FROM medications WHERE id=:id") fun observeMedication(id: String): Flow<MedicationEntity?>
    @Query("UPDATE import_jobs SET medicationId=NULL WHERE medicationId IN (SELECT id FROM medications WHERE pregnancyId=:id) AND state IN ('PURGED','REMOVED')") suspend fun releaseMedicationImportOwners(id: String)
    @androidx.room3.Transaction
    @Query("SELECT * FROM pregnancies WHERE id=:id AND deletedAt IS NULL") fun timeline(id: String): Flow<TimelineSnapshot?>
    @Query("SELECT * FROM journal_entries WHERE id=:id") suspend fun journalEntry(id: String): JournalEntryEntity?
    @Query("SELECT * FROM journal_entries") suspend fun allJournalEntries(): List<JournalEntryEntity>
    @Insert suspend fun insertJournalEntry(row: JournalEntryEntity)
    @Update suspend fun updateJournalEntry(row: JournalEntryEntity)
    @Query("DELETE FROM journal_entries WHERE pregnancyId=:id") suspend fun purgeJournalEntries(id: String)
    @Query("SELECT * FROM metric_definitions WHERE pregnancyId=:id AND deletedAt IS NULL ORDER BY createdAt,id") fun metricDefinitions(id: String): Flow<List<MetricDefinitionEntity>>
    @Query("SELECT * FROM metric_definitions") suspend fun allMetricDefinitions(): List<MetricDefinitionEntity>
    @Query("SELECT * FROM metric_definitions WHERE id=:id") suspend fun metricDefinition(id: String): MetricDefinitionEntity?
    @Insert suspend fun insertMetricDefinition(row: MetricDefinitionEntity)
    @Update suspend fun updateMetricDefinition(row: MetricDefinitionEntity)
    @Query("SELECT * FROM measurements WHERE pregnancyId=:id AND deletedAt IS NULL ORDER BY measuredAt DESC,id") fun measurements(id: String): Flow<List<MeasurementEntity>>
    @Query("SELECT * FROM measurements") suspend fun allMeasurements(): List<MeasurementEntity>
    @Query("SELECT * FROM measurements WHERE id=:id") suspend fun measurement(id: String): MeasurementEntity?
    @Insert suspend fun insertMeasurement(row: MeasurementEntity)
    @Update suspend fun updateMeasurement(row: MeasurementEntity)
    @Query("SELECT v.* FROM measurement_values v JOIN measurements m ON m.id=v.measurementId WHERE m.pregnancyId=:id AND m.deletedAt IS NULL") fun measurementValues(id: String): Flow<List<MeasurementValueEntity>>
    @Query("SELECT * FROM measurement_values WHERE measurementId=:id") suspend fun valuesForMeasurement(id: String): List<MeasurementValueEntity>
    @Insert suspend fun insertMeasurementValues(rows: List<MeasurementValueEntity>)
    @Query("DELETE FROM measurement_values WHERE measurementId=:id") suspend fun deleteMeasurementValues(id: String)
    @Query("DELETE FROM measurements WHERE pregnancyId=:id") suspend fun purgeMeasurements(id: String)
    @Query("DELETE FROM metric_definitions WHERE pregnancyId=:id") suspend fun purgeMetricDefinitions(id: String)
    @Query("SELECT * FROM medication_occurrences WHERE pregnancyId=:pregnancyId AND plannedDate=:date ORDER BY scheduledAt,id") fun occurrences(pregnancyId: String, date: String): Flow<List<MedicationOccurrenceEntity>>
    @Query("SELECT * FROM medication_occurrences WHERE pregnancyId=:pregnancyId AND plannedDate=:date ORDER BY scheduledAt,id") suspend fun occurrencesForDate(pregnancyId: String, date: String): List<MedicationOccurrenceEntity>
    @Query("SELECT * FROM medication_occurrences") suspend fun allOccurrences(): List<MedicationOccurrenceEntity>
    @Query("SELECT * FROM medication_occurrences WHERE pregnancyId=:pregnancyId AND deletedAt IS NULL ORDER BY scheduledAt,id") fun observeOccurrences(pregnancyId: String): Flow<List<MedicationOccurrenceEntity>>
    @Query("SELECT * FROM medication_occurrences WHERE id=:id") suspend fun occurrence(id: String): MedicationOccurrenceEntity?
    @Upsert suspend fun putOccurrence(entity: MedicationOccurrenceEntity)
    @Query("SELECT * FROM medication_intakes WHERE pregnancyId=:pregnancyId AND deletedAt IS NULL ORDER BY COALESCE(actualAt,recordedAt) DESC,id") fun intakes(pregnancyId: String): Flow<List<MedicationIntakeEntity>>
    @Query("SELECT * FROM medication_intakes") suspend fun allIntakes(): List<MedicationIntakeEntity>
    @Query("SELECT * FROM medication_intakes WHERE id=:id") suspend fun intake(id: String): MedicationIntakeEntity?
    @Query("SELECT * FROM medication_intakes WHERE occurrenceId=:id") suspend fun intakeByOccurrence(id: String): MedicationIntakeEntity?
    @Insert suspend fun insertIntake(entity: MedicationIntakeEntity)
    @Update suspend fun updateIntake(entity: MedicationIntakeEntity)
    @Query("DELETE FROM medication_intakes WHERE pregnancyId=:id") suspend fun purgeIntakes(id: String)
    @Query("DELETE FROM medication_occurrences WHERE pregnancyId=:id") suspend fun purgeOccurrences(id: String)
    @Query("SELECT * FROM medication_plans WHERE pregnancyId=:pregnancyId AND deletedAt IS NULL ORDER BY effectiveAt,id") fun observePlanVersions(pregnancyId: String): Flow<List<MedicationPlanEntity>>
    @Query("SELECT * FROM medications WHERE pregnancyId=:pregnancyId AND deletedAt IS NULL ORDER BY createdAt,id") fun medications(pregnancyId: String): Flow<List<MedicationEntity>>
    @Query("SELECT * FROM medications") suspend fun allMedications(): List<MedicationEntity>
    @Query("SELECT * FROM medications WHERE id=:id") suspend fun medication(id: String): MedicationEntity?
    @Insert suspend fun insertMedication(entity: MedicationEntity)
    @Update suspend fun updateMedication(entity: MedicationEntity)
    @Query("SELECT * FROM medication_plans") suspend fun allMedicationPlans(): List<MedicationPlanEntity>
    @Query("SELECT * FROM medication_plans WHERE id=:id") suspend fun medicationPlan(id: String): MedicationPlanEntity?
    @Query("SELECT * FROM medication_plans WHERE logicalPlanId=:id ORDER BY version DESC LIMIT 1") suspend fun latestMedicationPlan(id: String): MedicationPlanEntity?
    @Query("SELECT * FROM medication_plans p WHERE pregnancyId=:pregnancyId AND deletedAt IS NULL AND version=(SELECT MAX(version) FROM medication_plans WHERE logicalPlanId=p.logicalPlanId) ORDER BY effectiveAt DESC,id") fun medicationPlans(pregnancyId: String): Flow<List<MedicationPlanEntity>>
    @Query("SELECT * FROM medication_plans WHERE logicalPlanId=:id AND deletedAt IS NULL ORDER BY version DESC") fun medicationHistory(id: String): Flow<List<MedicationPlanEntity>>
    @Insert suspend fun insertMedicationPlan(entity: MedicationPlanEntity)
    @Update suspend fun updateMedicationPlan(entity: MedicationPlanEntity)
    @Query("DELETE FROM medication_plans WHERE pregnancyId=:id") suspend fun purgeMedicationPlans(id: String)
    @Query("DELETE FROM medications WHERE pregnancyId=:id") suspend fun purgeMedications(id: String)
    @Query("SELECT * FROM attachments ORDER BY createdAt, id") suspend fun allAttachments(): List<AttachmentEntity>
    @Query("SELECT * FROM import_jobs ORDER BY createdAt, id") suspend fun allImportJobs(): List<ImportJobEntity>
    @Query("SELECT * FROM import_jobs ORDER BY createdAt, id") fun observeImportJobs(): Flow<List<ImportJobEntity>>
    @Query("SELECT * FROM deletion_batches WHERE id = :id") suspend fun deletionBatch(id: String): DeletionBatchEntity?
    @Query("SELECT * FROM deletion_batches WHERE state IN ('DELETED','PURGING') ORDER BY deletedAt, id") suspend fun pendingDeletionBatches(): List<DeletionBatchEntity>
    @Upsert suspend fun putDeletionBatch(batch: DeletionBatchEntity)
    @Query("SELECT b.*, CASE WHEN b.kind = 'PREGNANCY' THEN COALESCE(p.title,'孕期档案') WHEN b.kind = 'VISIT' THEN COALESCE(NULLIF(v.items,''),'产检记录') WHEN b.kind = 'MEDICATION' THEN COALESCE(dm.name,'药品') WHEN b.kind = 'JOURNAL' THEN '日常记录' WHEN b.kind = 'MEASUREMENT' THEN COALESCE(md.name,'测量记录') ELSE COALESCE(m.name,'服药记录') || ' · ' || COALESCE(i.eventDate,'') END AS title, (SELECT COUNT(*) FROM prenatal_visits WHERE deletionBatchId = b.id) AS visitCount, (SELECT COUNT(*) FROM attachments WHERE deletionBatchId = b.id) AS documentCount, (SELECT COUNT(DISTINCT logicalPlanId) FROM medication_plans WHERE deletionBatchId = b.id) AS planCount, (SELECT COUNT(*) FROM medication_intakes WHERE deletionBatchId = b.id) AS intakeCount, (SELECT COUNT(*) FROM measurements WHERE deletionBatchId = b.id) AS measurementCount, (SELECT COUNT(*) FROM journal_entries WHERE deletionBatchId=b.id) AS entryCount FROM deletion_batches b LEFT JOIN medications dm ON b.kind='MEDICATION' AND dm.id=b.rootId LEFT JOIN pregnancies p ON b.kind = 'PREGNANCY' AND p.id = b.rootId LEFT JOIN prenatal_visits v ON b.kind = 'VISIT' AND v.id = b.rootId LEFT JOIN medication_intakes i ON b.kind = 'INTAKE' AND i.id=b.rootId LEFT JOIN medication_plans mp ON mp.id=i.planVersionId LEFT JOIN medications m ON m.id=mp.medicationId LEFT JOIN measurements ms ON b.kind='MEASUREMENT' AND ms.id=b.rootId LEFT JOIN metric_definitions md ON md.id=ms.definitionId WHERE b.state IN ('DELETED','PURGING') ORDER BY b.deletedAt DESC,b.id")
    fun trashBatches(): Flow<List<TrashBatchRow>>
    @Query("SELECT a.* FROM attachments a JOIN prenatal_visits v ON v.id=a.visitId JOIN pregnancies p ON p.id=v.pregnancyId WHERE a.deletedAt IS NULL AND v.deletedAt IS NOT NULL AND p.deletedAt IS NULL ORDER BY a.createdAt DESC,a.id")
    fun retainedDocuments(): Flow<List<AttachmentEntity>>
    @Query("DELETE FROM pregnancy_date_revisions WHERE pregnancyId = :id") suspend fun removeDateRevisions(id: String)
    @Query("SELECT * FROM import_jobs WHERE visitId = :visitId ORDER BY createdAt, id")
    fun importJobs(visitId: String): Flow<List<ImportJobEntity>>
    @Query("SELECT * FROM import_jobs WHERE state IN ('QUEUED', 'IMPORTING', 'PURGING', 'CANCELLED') OR (state = 'READY' AND phase = 'THUMBNAIL') ORDER BY createdAt, id")
    suspend fun pendingImports(): List<ImportJobEntity>
    @Query("SELECT * FROM import_jobs WHERE id = :id")
    suspend fun importJob(id: String): ImportJobEntity?
    @Upsert suspend fun putImportJob(job: ImportJobEntity)
    @Query("UPDATE import_jobs SET phase = :phase WHERE id = :id AND state IN ('QUEUED','IMPORTING','READY')")
    suspend fun setImportPhase(id: String, phase: String)
    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun attachment(id: String): AttachmentEntity?
    @Query("SELECT * FROM attachments WHERE visitId = :visitId ORDER BY createdAt, id")
    fun attachments(visitId: String): Flow<List<AttachmentEntity>>
    @Upsert suspend fun putAttachment(entity: AttachmentEntity)
    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun removeAttachment(id: String)
    @Query("SELECT * FROM attachments WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC, id")
    fun recycledAttachments(): Flow<List<AttachmentEntity>>
    @Query("SELECT * FROM attachments WHERE id = :id")
    fun observeAttachment(id: String): Flow<AttachmentEntity?>
    @Query("SELECT * FROM pregnancies WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun pregnancies(): Flow<List<PregnancyEntity>>
    @Query("SELECT * FROM pregnancies ORDER BY createdAt")
    suspend fun allPregnancies(): List<PregnancyEntity>
    @Query("SELECT * FROM pregnancies WHERE id = :id")
    suspend fun pregnancy(id: String): PregnancyEntity?
    @Upsert suspend fun putPregnancy(entity: PregnancyEntity)
    @Upsert suspend fun putDateRevision(entity: PregnancyDateRevisionEntity)
    @Query("SELECT * FROM pregnancy_date_revisions WHERE pregnancyId = :id ORDER BY effectiveAt DESC, rowid DESC LIMIT 1")
    suspend fun latestDateRevision(id: String): PregnancyDateRevisionEntity?
    @Query("SELECT * FROM pregnancy_date_revisions ORDER BY effectiveAt")
    suspend fun allDateRevisions(): List<PregnancyDateRevisionEntity>
    @Query("SELECT * FROM pregnancy_date_revisions ORDER BY effectiveAt DESC, rowid DESC")
    fun dateRevisions(): Flow<List<PregnancyDateRevisionEntity>>
    @Query("SELECT * FROM prenatal_visits WHERE deletedAt IS NULL ORDER BY scheduledDate, scheduledTime")
    fun visits(): Flow<List<VisitEntity>>
    @Query("SELECT * FROM prenatal_visits ORDER BY scheduledDate")
    suspend fun allVisits(): List<VisitEntity>
    @Query("SELECT * FROM prenatal_visits WHERE id = :id")
    suspend fun visit(id: String): VisitEntity?
    @Query("SELECT * FROM prenatal_visits WHERE id = :id")
    fun observeVisit(id: String): Flow<VisitEntity?>
    @Upsert suspend fun putVisit(entity: VisitEntity)
    @Query("SELECT * FROM custom_reminders ORDER BY startDate, localTime, id")
    suspend fun allCustomReminders(): List<CustomReminderEntity>
    @Query("SELECT * FROM custom_reminders WHERE deletedAt IS NULL ORDER BY startDate, localTime, id")
    fun customReminders(): Flow<List<CustomReminderEntity>>
    @Query("SELECT * FROM custom_reminders WHERE id = :id")
    suspend fun customReminder(id: String): CustomReminderEntity?
    @Upsert suspend fun putCustomReminder(entity: CustomReminderEntity)
    @Query("DELETE FROM custom_reminders WHERE id = :id") suspend fun deleteCustomReminder(id: String)
    @Query("DELETE FROM custom_reminders WHERE pregnancyId = :id") suspend fun purgeCustomReminders(id: String)
    @Insert suspend fun recordChange(entity: ChangeEntity): Long
    @Query("SELECT COALESCE(MAX(sequence), 0) FROM change_log")
    suspend fun currentSequence(): Long
    @Query("SELECT * FROM drafts WHERE feature = :feature AND draftId = :id")
    suspend fun draft(feature: String, id: String): DraftEntity?
    @Query("SELECT * FROM drafts") suspend fun allDrafts(): List<DraftEntity>
    @Upsert suspend fun saveDraft(draft: DraftEntity)
    @Query("DELETE FROM drafts WHERE feature = :feature AND draftId = :id")
    suspend fun clearDraft(feature: String, id: String)
}


