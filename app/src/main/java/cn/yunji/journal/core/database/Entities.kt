package cn.yunji.journal.core.database

import androidx.room3.Entity
import androidx.room3.ColumnInfo
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "pregnancies", indices = [Index("status"), Index("updatedAt")])
data class PregnancyEntity(
    @PrimaryKey val id: String,
    val title: String,
    val lmpDate: String? = null,
    val confirmedEdd: String? = null,
    val hospital: String = "",
    val note: String = "",
    val status: String = "ONGOING",
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val deletedAt: Long? = null,
    val deletionBatchId: String? = null,
)

@Serializable
@Entity(tableName = "pregnancy_date_revisions", indices = [Index("pregnancyId")], foreignKeys = [
    ForeignKey(entity = PregnancyEntity::class, parentColumns = ["id"], childColumns = ["pregnancyId"]),
])
data class PregnancyDateRevisionEntity(
    @PrimaryKey val id: String, val pregnancyId: String, val lmpDate: String?,
    val confirmedEdd: String?, val source: String, val effectiveAt: Long,
)

@Serializable
@Entity(tableName = "prenatal_visits", indices = [Index(value = ["pregnancyId", "scheduledDate"])], foreignKeys = [
    ForeignKey(entity = PregnancyEntity::class, parentColumns = ["id"], childColumns = ["pregnancyId"]),
])
data class VisitEntity(
    @PrimaryKey val id: String, val pregnancyId: String,
    val scheduledDate: String, val scheduledTime: String? = null, val zoneId: String,
    val hospital: String = "", val items: String = "", val preparation: String = "",
    val advice: String = "", val actualDate: String? = null,
    val gestationalDays: Long? = null, val dateRevisionId: String? = null,
    val status: String = "SCHEDULED", val createdAt: Long, val updatedAt: Long,
    val revision: Long = 1, val deletedAt: Long? = null, val deletionBatchId: String? = null,
    val coverAttachmentId: String? = null,
    val reminderDate: String? = null, val reminderTime: String? = null,
    val actualTime: String? = null, val actualHospital: String? = null, val actualResult: String? = null,
)

@Serializable
@Entity(tableName = "change_log")
data class ChangeEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val entityType: String, val entityId: String, val revision: Long,
    val operation: String, val createdAt: Long,
)

@Entity(tableName = "drafts", primaryKeys = ["feature", "draftId"])
data class DraftEntity(val feature: String, val draftId: String, val payload: String, val updatedAt: Long)

@Entity(tableName = "import_jobs", indices = [Index("visitId"),Index("medicationId")], foreignKeys = [
    ForeignKey(entity = VisitEntity::class, parentColumns = ["id"], childColumns = ["visitId"]),
    ForeignKey(entity = MedicationEntity::class, parentColumns = ["id"], childColumns = ["medicationId"]),
])
data class ImportJobEntity(
    @PrimaryKey val id: String, val visitId: String?, val sourceUri: String,
    val name: String, val createdAt: Long, val state: String = "QUEUED", val error: String? = null,
    @ColumnInfo(defaultValue = "'WAITING'") val phase: String = "WAITING",
    val medicationId: String? = null,
)

@Serializable
@Entity(tableName = "attachments", indices = [Index("visitId"),Index("medicationId")], foreignKeys = [
    ForeignKey(entity = VisitEntity::class, parentColumns = ["id"], childColumns = ["visitId"]),
    ForeignKey(entity = MedicationEntity::class, parentColumns = ["id"], childColumns = ["medicationId"]),
])
data class AttachmentEntity(
    @PrimaryKey val id: String, val visitId: String?, val name: String, val mime: String,
    val plainSize: Long, val cipherSize: Long, val plainSha256: String, val cipherSha256: String,
    val thumbnailId: String?, val thumbnailSize: Long?, val thumbnailCipherSize: Long?,
    val thumbnailSha256: String?, val thumbnailCipherSha256: String?, val createdAt: Long,
    val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "1") val revision: Long = 1,
    @ColumnInfo(defaultValue = "'OTHER'") val category: String = "OTHER",
    val deletionBatchId: String? = null,
    val documentDate: String? = null,
    @ColumnInfo(defaultValue = "''") val hospital: String = "",
    @ColumnInfo(defaultValue = "''") val note: String = "",
    val medicationId: String? = null,
    /** Immutable ciphertext object; replacement writes a new object before switching this pointer. */
    val objectId: String? = null,
)

@Serializable
@Entity(tableName = "deletion_batches")
data class DeletionBatchEntity(@PrimaryKey val id: String, val kind: String, val rootId: String,
    val deletedAt: Long, val state: String = "DELETED")

data class TrashBatchRow(val id: String, val kind: String, val rootId: String, val deletedAt: Long,
    val state: String, val title: String, val visitCount: Int, val documentCount: Int, val planCount: Int, val intakeCount: Int, val measurementCount: Int, val entryCount: Int)

