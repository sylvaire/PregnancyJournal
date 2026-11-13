package cn.yunji.journal.core.database

import androidx.room3.*
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "medications", indices = [Index("pregnancyId")], foreignKeys = [ForeignKey(entity = PregnancyEntity::class, parentColumns = ["id"], childColumns = ["pregnancyId"])])
data class MedicationEntity(@PrimaryKey val id: String, val pregnancyId: String, val name: String, val specification: String,
    val dosageForm: String, val createdAt: Long, val deletedAt: Long? = null, val deletionBatchId: String? = null, val revision: Long = 1)

@Serializable
@Entity(tableName = "medication_plans", indices = [Index("pregnancyId"), Index("medicationId"), Index(value = ["logicalPlanId", "version"], unique = true)], foreignKeys = [
    ForeignKey(entity = PregnancyEntity::class, parentColumns = ["id"], childColumns = ["pregnancyId"]),
    ForeignKey(entity = MedicationEntity::class, parentColumns = ["id"], childColumns = ["medicationId"]),
])
data class MedicationPlanEntity(@PrimaryKey val id: String, val pregnancyId: String, val medicationId: String,
    val logicalPlanId: String, val version: Int, val dose: String?, val unit: String?, val frequencyType: String,
    val times: String, val weekdays: String, val zoneId: String, val startDate: String, val endDate: String?,
    val status: String, val instructions: String, val source: String, val effectiveAt: Long,
    val deletedAt: Long? = null, val deletionBatchId: String? = null, val revision: Long = 1,
    @ColumnInfo(defaultValue="''") val medicationName: String = "",
    @ColumnInfo(defaultValue="''") val medicationSpecification: String = "",
    @ColumnInfo(defaultValue="''") val medicationDosageForm: String = "")
