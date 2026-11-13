package cn.yunji.journal.core.backup

import cn.yunji.journal.core.database.*
import kotlinx.serialization.Serializable

/** Production dictionary: no drafts, import source URIs, device wrappers or cloud credentials. */
@Serializable
data class LogicalSnapshot(val formatVersion: Int=1,val schemaVersion: Int=SCHEMA_VERSION,val vaultId: String,val snapshotId: String,
    val writerId: String,val writerSequence: Long,val capturedSequence: Long,val createdAt: Long,
    val pregnancies: List<PregnancyEntity> = emptyList(),val dateRevisions: List<PregnancyDateRevisionEntity> = emptyList(),
    val visits: List<VisitEntity> = emptyList(),val attachments: List<AttachmentEntity> = emptyList(),
    val deletionBatches: List<DeletionBatchEntity> = emptyList(),val medications: List<MedicationEntity> = emptyList(),
    val plans: List<MedicationPlanEntity> = emptyList(),val occurrences: List<MedicationOccurrenceEntity> = emptyList(),
    val intakes: List<MedicationIntakeEntity> = emptyList(),val definitions: List<MetricDefinitionEntity> = emptyList(),
    val measurements: List<MeasurementEntity> = emptyList(),val values: List<MeasurementValueEntity> = emptyList(),
    val entries: List<JournalEntryEntity> = emptyList(),
    val customReminders: List<CustomReminderEntity> = emptyList()) {
    override fun toString()="LogicalSnapshot(<redacted>)"

    companion object {
        /** 当前数据库 schema 版本。新增迁移时必须同步上调，[SnapshotRelations] 据此校验备份版本。 */
        const val SCHEMA_VERSION = 15

        /** 仍可读取的最早备份 schema 版本（旧备份继续可恢复）。 */
        const val OLDEST_READABLE_SCHEMA = 12
    }
}
