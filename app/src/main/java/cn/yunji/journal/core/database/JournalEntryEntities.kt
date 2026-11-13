package cn.yunji.journal.core.database

import androidx.room3.*
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "journal_entries", indices = [Index(value = ["pregnancyId", "entryDate"])], foreignKeys = [
    ForeignKey(entity = PregnancyEntity::class, parentColumns = ["id"], childColumns = ["pregnancyId"]),
])
data class JournalEntryEntity(@PrimaryKey val id: String, val pregnancyId: String, val entryDate: String,
    val entryTime: String?, val zoneId: String, val offsetSeconds: Int?, val symptom: String, val mood: String, val text: String,
    val createdAt: Long, val updatedAt: Long, val revision: Long = 1, val deletedAt: Long? = null, val deletionBatchId: String? = null)

data class TimelineVisit(@Embedded val visit: VisitEntity,
    @Relation(parentColumns=["id"],entityColumns=["visitId"]) val documents: List<AttachmentEntity>,
    @Relation(parentColumns=["id"],entityColumns=["visitId"]) val jobs: List<ImportJobEntity>)
data class TimelineMeasurement(@Embedded val measurement: MeasurementEntity,
    @Relation(parentColumns=["id"],entityColumns=["measurementId"]) val values: List<MeasurementValueEntity>)
data class TimelineMedication(@Embedded val medication: MedicationEntity,
    @Relation(parentColumns=["id"],entityColumns=["medicationId"]) val documents: List<AttachmentEntity>,
    @Relation(parentColumns=["id"],entityColumns=["medicationId"]) val jobs: List<ImportJobEntity>)

/** Read all sources under one Room read transaction; events never cache duplicate medical content. */
data class TimelineSnapshot(@Embedded val pregnancy: PregnancyEntity,
    @Relation(entity=VisitEntity::class,parentColumns=["id"],entityColumns=["pregnancyId"]) val visits: List<TimelineVisit>,
    @Relation(parentColumns=["id"],entityColumns=["pregnancyId"]) val drugs: List<MedicationEntity>,
    @Relation(parentColumns=["id"],entityColumns=["pregnancyId"]) val plans: List<MedicationPlanEntity>,
    @Relation(parentColumns=["id"],entityColumns=["pregnancyId"]) val intakes: List<MedicationIntakeEntity>,
    @Relation(parentColumns=["id"],entityColumns=["pregnancyId"]) val occurrences: List<MedicationOccurrenceEntity>,
    @Relation(parentColumns=["id"],entityColumns=["pregnancyId"]) val definitions: List<MetricDefinitionEntity>,
    @Relation(entity=MeasurementEntity::class,parentColumns=["id"],entityColumns=["pregnancyId"]) val measurements: List<TimelineMeasurement>,
    @Relation(parentColumns=["id"],entityColumns=["pregnancyId"]) val entries: List<JournalEntryEntity>,
    @Relation(entity=MedicationEntity::class,parentColumns=["id"],entityColumns=["pregnancyId"]) val medicationFiles: List<TimelineMedication> = emptyList())

