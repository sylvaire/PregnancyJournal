package cn.yunji.journal.core.export

import cn.yunji.journal.core.database.*

/**
 * Maps business entities to export rows.
 *
 * Only keys present in [ExportFieldCatalog] are produced, values are rendered as text (timestamps stay
 * epoch milliseconds so nothing depends on the reader's locale), and a null column stays null so the
 * package can distinguish "not recorded" from "recorded as empty".
 */
object ExportRowMapper {
    private fun text(value: String?): String? = value
    private fun number(value: Long?): String? = value?.toString()
    private fun number(value: Int?): String? = value?.toString()
    private fun flag(value: Long?): String? = value?.toString()

    fun pregnancies(rows: List<PregnancyEntity>) = rows.map {
        ExportRow(linkedMapOf(
            "id" to text(it.id), "title" to text(it.title), "lmpDate" to text(it.lmpDate),
            "confirmedEdd" to text(it.confirmedEdd), "hospital" to text(it.hospital), "note" to text(it.note),
            "status" to text(it.status), "createdAt" to number(it.createdAt), "updatedAt" to number(it.updatedAt),
            "revision" to number(it.revision), "deletedAt" to flag(it.deletedAt), "deletionBatchId" to text(it.deletionBatchId)))
    }

    fun dateRevisions(rows: List<PregnancyDateRevisionEntity>) = rows.map {
        ExportRow(linkedMapOf(
            "id" to text(it.id), "pregnancyId" to text(it.pregnancyId), "lmpDate" to text(it.lmpDate),
            "confirmedEdd" to text(it.confirmedEdd), "source" to text(it.source), "effectiveAt" to number(it.effectiveAt)))
    }

    fun visits(rows: List<VisitEntity>) = rows.map {
        ExportRow(linkedMapOf(
            "id" to text(it.id), "pregnancyId" to text(it.pregnancyId), "scheduledDate" to text(it.scheduledDate),
            "scheduledTime" to text(it.scheduledTime), "zoneId" to text(it.zoneId), "hospital" to text(it.hospital),
            "items" to text(it.items), "preparation" to text(it.preparation), "advice" to text(it.advice),
            "actualDate" to text(it.actualDate), "actualTime" to text(it.actualTime),
            "actualHospital" to text(it.actualHospital), "actualResult" to text(it.actualResult),
            "gestationalDays" to number(it.gestationalDays),
            "dateRevisionId" to text(it.dateRevisionId), "status" to text(it.status),
            "createdAt" to number(it.createdAt), "updatedAt" to number(it.updatedAt), "revision" to number(it.revision),
            "deletedAt" to flag(it.deletedAt), "deletionBatchId" to text(it.deletionBatchId),
            "coverAttachmentId" to text(it.coverAttachmentId), "reminderDate" to text(it.reminderDate),
            "reminderTime" to text(it.reminderTime)))
    }

    fun attachments(rows: List<AttachmentEntity>) = rows.map {
        ExportRow(linkedMapOf(
            "id" to text(it.id), "visitId" to text(it.visitId), "medicationId" to text(it.medicationId),
            "name" to text(it.name), "mime" to text(it.mime), "category" to text(it.category),
            "documentDate" to text(it.documentDate), "hospital" to text(it.hospital), "note" to text(it.note),
            "plainSize" to number(it.plainSize), "plainSha256" to text(it.plainSha256),
            "createdAt" to number(it.createdAt), "deletedAt" to flag(it.deletedAt),
            "deletionBatchId" to text(it.deletionBatchId), "revision" to number(it.revision),
            "cipherSize" to number(it.cipherSize), "cipherSha256" to text(it.cipherSha256),
            "objectId" to text(it.objectId), "thumbnailId" to text(it.thumbnailId),
            "thumbnailSize" to number(it.thumbnailSize), "thumbnailCipherSize" to number(it.thumbnailCipherSize),
            "thumbnailSha256" to text(it.thumbnailSha256), "thumbnailCipherSha256" to text(it.thumbnailCipherSha256)))
    }

    fun deletionBatches(rows: List<DeletionBatchEntity>) = rows.map {
        ExportRow(linkedMapOf(
            "id" to text(it.id), "kind" to text(it.kind), "rootId" to text(it.rootId),
            "deletedAt" to number(it.deletedAt), "state" to text(it.state)))
    }

    fun medications(rows: List<MedicationEntity>) = rows.map {
        ExportRow(linkedMapOf(
            "id" to text(it.id), "pregnancyId" to text(it.pregnancyId), "name" to text(it.name),
            "specification" to text(it.specification), "dosageForm" to text(it.dosageForm),
            "createdAt" to number(it.createdAt), "deletedAt" to flag(it.deletedAt),
            "deletionBatchId" to text(it.deletionBatchId), "revision" to number(it.revision)))
    }

    fun plans(rows: List<MedicationPlanEntity>) = rows.map {
        ExportRow(linkedMapOf(
            "id" to text(it.id), "pregnancyId" to text(it.pregnancyId), "medicationId" to text(it.medicationId),
            "logicalPlanId" to text(it.logicalPlanId), "version" to number(it.version), "dose" to text(it.dose),
            "unit" to text(it.unit), "frequencyType" to text(it.frequencyType), "times" to text(it.times),
            "weekdays" to text(it.weekdays), "zoneId" to text(it.zoneId), "startDate" to text(it.startDate),
            "endDate" to text(it.endDate), "status" to text(it.status), "instructions" to text(it.instructions),
            "source" to text(it.source), "effectiveAt" to number(it.effectiveAt), "deletedAt" to flag(it.deletedAt),
            "deletionBatchId" to text(it.deletionBatchId), "revision" to number(it.revision),
            "medicationName" to text(it.medicationName), "medicationSpecification" to text(it.medicationSpecification),
            "medicationDosageForm" to text(it.medicationDosageForm)))
    }

    fun occurrences(rows: List<MedicationOccurrenceEntity>) = rows.map {
        ExportRow(linkedMapOf(
            "id" to text(it.id), "pregnancyId" to text(it.pregnancyId), "planVersionId" to text(it.planVersionId),
            "plannedDate" to text(it.plannedDate), "localTime" to text(it.localTime), "ordinal" to number(it.ordinal),
            "scheduledAt" to number(it.scheduledAt), "zoneId" to text(it.zoneId), "state" to text(it.state),
            "createdAt" to number(it.createdAt), "deletedAt" to flag(it.deletedAt),
            "deletionBatchId" to text(it.deletionBatchId), "revision" to number(it.revision)))
    }

    fun intakes(rows: List<MedicationIntakeEntity>) = rows.map {
        ExportRow(linkedMapOf(
            "id" to text(it.id), "pregnancyId" to text(it.pregnancyId), "planVersionId" to text(it.planVersionId),
            "occurrenceId" to text(it.occurrenceId), "sourceOccurrenceId" to text(it.sourceOccurrenceId),
            "status" to text(it.status), "actualAt" to flag(it.actualAt), "eventDate" to text(it.eventDate),
            "zoneId" to text(it.zoneId), "dose" to text(it.dose), "unit" to text(it.unit), "note" to text(it.note),
            "recordedAt" to number(it.recordedAt), "updatedAt" to number(it.updatedAt), "revision" to number(it.revision),
            "deletedAt" to flag(it.deletedAt), "deletionBatchId" to text(it.deletionBatchId)))
    }

    fun definitions(rows: List<MetricDefinitionEntity>) = rows.map {
        ExportRow(linkedMapOf(
            "id" to text(it.id), "pregnancyId" to text(it.pregnancyId), "logicalId" to text(it.logicalId),
            "version" to number(it.version), "kind" to text(it.kind), "name" to text(it.name),
            "unit" to text(it.unit), "valueKeys" to text(it.valueKeys), "createdAt" to number(it.createdAt),
            "deletedAt" to flag(it.deletedAt), "deletionBatchId" to text(it.deletionBatchId),
            "revision" to number(it.revision)))
    }

    fun measurements(rows: List<MeasurementEntity>) = rows.map {
        ExportRow(linkedMapOf(
            "id" to text(it.id), "pregnancyId" to text(it.pregnancyId), "definitionId" to text(it.definitionId),
            "measuredAt" to number(it.measuredAt), "zoneId" to text(it.zoneId), "scenario" to text(it.scenario),
            "postMealMinutes" to number(it.postMealMinutes), "source" to text(it.source), "note" to text(it.note),
            "recordedAt" to number(it.recordedAt), "updatedAt" to number(it.updatedAt), "revision" to number(it.revision),
            "deletedAt" to flag(it.deletedAt), "deletionBatchId" to text(it.deletionBatchId)))
    }

    fun values(rows: List<MeasurementValueEntity>) = rows.map {
        ExportRow(linkedMapOf(
            "measurementId" to text(it.measurementId), "valueKey" to text(it.valueKey),
            "decimalValue" to text(it.decimalValue)))
    }

    fun entries(rows: List<JournalEntryEntity>) = rows.map {
        ExportRow(linkedMapOf(
            "id" to text(it.id), "pregnancyId" to text(it.pregnancyId), "entryDate" to text(it.entryDate),
            "entryTime" to text(it.entryTime), "zoneId" to text(it.zoneId), "offsetSeconds" to number(it.offsetSeconds),
            "symptom" to text(it.symptom), "mood" to text(it.mood), "text" to text(it.text),
            "createdAt" to number(it.createdAt), "updatedAt" to number(it.updatedAt), "revision" to number(it.revision),
            "deletedAt" to flag(it.deletedAt), "deletionBatchId" to text(it.deletionBatchId)))
    }

    fun customReminders(rows: List<CustomReminderEntity>) = rows.map {
        ExportRow(linkedMapOf(
            "id" to text(it.id), "pregnancyId" to text(it.pregnancyId), "title" to text(it.title),
            "note" to text(it.note), "startDate" to text(it.startDate), "localTime" to text(it.localTime),
            "repeat" to text(it.repeat), "endDate" to text(it.endDate),
            "enabled" to it.enabled.toString(),
            "createdAt" to number(it.createdAt), "updatedAt" to number(it.updatedAt),
            "revision" to number(it.revision), "deletedAt" to flag(it.deletedAt)))
    }
}
