package cn.yunji.journal.core.database

import androidx.room3.*
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "medication_occurrences", indices = [Index(value = ["pregnancyId", "plannedDate"]), Index("planVersionId")], foreignKeys = [
    ForeignKey(entity = PregnancyEntity::class, parentColumns = ["id"], childColumns = ["pregnancyId"]),
    ForeignKey(entity = MedicationPlanEntity::class, parentColumns = ["id"], childColumns = ["planVersionId"]),
])
data class MedicationOccurrenceEntity(@PrimaryKey val id: String, val pregnancyId: String, val planVersionId: String,
    val plannedDate: String, val localTime: String, val ordinal: Int, val scheduledAt: Long, val zoneId: String,
    val state: String = "ACTIVE", val createdAt: Long, val deletedAt: Long? = null, val deletionBatchId: String? = null, val revision: Long = 1)

@Serializable
@Entity(tableName = "medication_intakes", indices = [Index(value = ["pregnancyId", "eventDate"]), Index("planVersionId"), Index("sourceOccurrenceId"), Index(value = ["occurrenceId"], unique = true)], foreignKeys = [
    ForeignKey(entity = PregnancyEntity::class, parentColumns = ["id"], childColumns = ["pregnancyId"]),
    ForeignKey(entity = MedicationPlanEntity::class, parentColumns = ["id"], childColumns = ["planVersionId"]),
    ForeignKey(entity = MedicationOccurrenceEntity::class, parentColumns = ["id"], childColumns = ["sourceOccurrenceId"]),
])
data class MedicationIntakeEntity(@PrimaryKey val id: String, val pregnancyId: String, val planVersionId: String,
    val occurrenceId: String?, val sourceOccurrenceId: String?, val status: String, val actualAt: Long?, val eventDate: String,
    val zoneId: String, val dose: String?, val unit: String?, val note: String, val recordedAt: Long, val updatedAt: Long,
    val revision: Long = 1, val deletedAt: Long? = null, val deletionBatchId: String? = null)
