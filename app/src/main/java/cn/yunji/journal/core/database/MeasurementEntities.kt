package cn.yunji.journal.core.database

import androidx.room3.*
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "metric_definitions", indices = [Index("pregnancyId"), Index(value = ["logicalId", "version"], unique = true)], foreignKeys = [ForeignKey(entity = PregnancyEntity::class, parentColumns = ["id"], childColumns = ["pregnancyId"])])
data class MetricDefinitionEntity(@PrimaryKey val id: String, val pregnancyId: String, val logicalId: String,
    val version: Int, val kind: String, val name: String, val unit: String, val valueKeys: String,
    val createdAt: Long, val deletedAt: Long? = null, val deletionBatchId: String? = null, val revision: Long = 1)

@Serializable
@Entity(tableName = "measurements", indices = [Index("pregnancyId"), Index("definitionId"), Index("measuredAt")], foreignKeys = [
    ForeignKey(entity = PregnancyEntity::class, parentColumns = ["id"], childColumns = ["pregnancyId"]),
    ForeignKey(entity = MetricDefinitionEntity::class, parentColumns = ["id"], childColumns = ["definitionId"]),
])
data class MeasurementEntity(@PrimaryKey val id: String, val pregnancyId: String, val definitionId: String,
    val measuredAt: Long, val zoneId: String, val scenario: String, val postMealMinutes: Int?,
    val source: String, val note: String, val recordedAt: Long, val updatedAt: Long,
    val revision: Long = 1, val deletedAt: Long? = null, val deletionBatchId: String? = null)

@Serializable
@Entity(tableName = "measurement_values", primaryKeys = ["measurementId", "valueKey"], foreignKeys = [ForeignKey(entity = MeasurementEntity::class, parentColumns = ["id"], childColumns = ["measurementId"])])
data class MeasurementValueEntity(val measurementId: String, val valueKey: String, val decimalValue: String)
