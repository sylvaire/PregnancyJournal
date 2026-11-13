package cn.yunji.journal.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import cn.yunji.journal.core.database.JournalDatabase
import cn.yunji.journal.core.database.journalWrite
import cn.yunji.journal.core.export.*
import cn.yunji.journal.core.backup.TransientWorkspaceCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the user chose in the export wizard. */
@kotlinx.serialization.Serializable
data class ExportRequest(
    val from: String? = null,
    val to: String? = null,
    val includeOriginals: Boolean = true,
    val includeTrash: Boolean = false,
    val sections: Set<String> = ExportScope.ALL_SECTIONS,
    val summaryPregnancyId: String? = null,
) {
    fun scope() = ExportScope(from, to, includeOriginals, includeTrash, sections)
}

/** Carries wizard options through the SAF handoff, which stores text only. */
object ExportRequestCodec {
    private val json = kotlinx.serialization.json.Json { encodeDefaults = true }
    fun encode(request: ExportRequest): String = json.encodeToString(request)
    fun decode(payload: String?): ExportRequest {
        // Missing options must never silently expand a previously selected scope.
        val text = requireNotNull(payload)
        val fields = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
        require(fields?.keys == setOf("from", "to", "includeOriginals", "includeTrash", "sections", "summaryPregnancyId"))
        return json.decodeFromString<ExportRequest>(text).also { it.scope() }
    }
}

data class ExportResult(val success: Boolean, val message: String, val detail: String? = null)

/**
 * Extracts business rows from the live database and writes a plaintext export to a user-chosen location.
 *
 * Originals are materialised to a private temporary directory before packaging (the transport writer needs
 * a stream per object); the directory is always removed afterwards, and available space is checked first so
 * a large export fails with a clear message instead of a half-written file.
 */
class ExportService(
    private val context: Context,
    private val database: JournalDatabase,
    private val importer: DocumentImporter,
    private val vaultId: String,
    private val clock: Clock,
) {
    private val writer = ExportSafWriter(context)
    private val tempRoot = File(context.cacheDir, "export-work")
    companion object {
        private val activeJobs = ConcurrentHashMap.newKeySet<String>()
        internal fun recover(context: Context) = TransientWorkspaceCleanup.recover(
            File(context.cacheDir, "export-work"), setOf(TransientWorkspaceCleanup.exportJob), activeJobs)
    }

    suspend fun exportDataPackage(destination: Uri, request: ExportRequest): ExportResult = withContext(Dispatchers.IO) {
        val scope = request.scope()
        recover(context)
        val name = "job-${UUID.randomUUID()}"
        activeJobs += name
        val work = File(tempRoot, name)
        try {
            require(work.mkdirs()) { "无法创建导出临时目录" }
            // Freeze related rows together; release the transaction before slow file/provider IO.
            importer.objectProtection.withSelected(capture = {
                val selected = selectedAttachments(scope)
                (selected to readSections(scope, selected)) to
                    if (request.includeOriginals) selected.filter { it.deletedAt == null }.map { it.objectId ?: it.id } else emptyList()
            }) { (attachments, sections) ->
            val (originals, omittedTrash) = if (request.includeOriginals && scope.sections.contains("attachments")) {
                val needed = attachments.filter { it.deletedAt == null }.sumOf { it.plainSize }
                val available = runCatching { StatFs(tempRoot.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
                require(available > needed + 32L * 1024 * 1024) { "可用空间不足，无法准备导出原件" }
                materialiseOriginals(attachments, work)
            } else emptyList<ExportOriginal>() to 0
            val summary = writer.writeStructuredPackage(
                destination,
                ExportHeader(generatedAt = clock.millis(), vaultId = vaultId, from = scope.from, to = scope.to,
                    includeTrash = scope.includeTrash, includeOriginals = request.includeOriginals),
                scope, sections, buildSources(sections), originals)
            ExportResult(true, "导出完成并通过文件回读校验",
                "共 ${summary.countsBySection.values.sum()} 条记录，原件 ${summary.originalsWritten} 份" +
                    if (omittedTrash > 0) "；回收站原件 $omittedTrash 份未包含（回收站只导出记录信息）" else "")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { ExportResult(false, e.message ?: "导出未完成") }
        finally {
            // Removes the materialised originals as well; nothing plaintext survives the export.
            try { TransientWorkspaceCleanup.deleteTree(work) }
            finally { activeJobs -= name }
        }
    }

    suspend fun exportSummaryPdf(destination: Uri, request: ExportRequest): ExportResult = withContext(Dispatchers.IO) {
        val scope = request.scope()
        try {
            val summary = summary(scope, request.summaryPregnancyId)
            require(summary.sections.isNotEmpty()) { "所选范围内没有可导出的记录" }
            val pages = writer.writeSummaryPdf(destination, summary)
            ExportResult(true, "就诊摘要已生成并通过文件回读校验", "共 $pages 页")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { ExportResult(false, e.message ?: "摘要未生成") }
    }

    suspend fun summary(scope: ExportScope, pregnancyId: String?): VisitSummary = withContext(Dispatchers.IO) {
        val dao = database.journalDao()
        val inputs = database.journalWrite { SummaryInputs(
            pregnancies = dao.allPregnancies(), visits = dao.allVisits(), medications = dao.allMedications(),
            plans = dao.allMedicationPlans(), intakes = dao.allIntakes(), definitions = dao.allMetricDefinitions(),
            measurements = dao.allMeasurements(), values = dao.allMeasurementValues()) }
        SummaryDataBuilder.build(inputs, scope, pregnancyId, clock.millis())
    }

    /** Counts tell the wizard what an export would contain before the user commits to it. */
    suspend fun preview(request: ExportRequest): Map<String, Int> = withContext(Dispatchers.IO) {
        database.journalWrite { readSections(request.scope()) }.associate { it.name to it.rows.size }
    }

    private suspend fun readSections(scope: ExportScope, selectedAttachments: List<cn.yunji.journal.core.database.AttachmentEntity>? = null): List<ExportSection> {
        val dao = database.journalDao()
        val pregnancies = dao.allPregnancies().filter { scope.includesTrash(it.deletedAt) }
        val selected = pregnancies.map { it.id }.toSet()
        val visits = dao.allVisits().filter { it.pregnancyId in selected }
            .filter { scope.includesDate(it.scheduledDate) }.filter { scope.includesTrash(it.deletedAt) }
        val attachments = selectedAttachments ?: selectedAttachments(scope)
        val medications = dao.allMedications().filter { it.pregnancyId in selected }.filter { scope.includesTrash(it.deletedAt) }
        val plans = dao.allMedicationPlans().filter { it.pregnancyId in selected }
            .filter { scope.includesDate(it.startDate) }.filter { scope.includesTrash(it.deletedAt) }
        val occurrences = dao.allOccurrences().filter { it.pregnancyId in selected }
            .filter { scope.includesDate(it.plannedDate) }.filter { scope.includesTrash(it.deletedAt) }
        val intakes = dao.allIntakes().filter { it.pregnancyId in selected }
            .filter { scope.includesDate(it.eventDate) }.filter { scope.includesTrash(it.deletedAt) }
        val definitions = dao.allMetricDefinitions().filter { it.pregnancyId in selected }.filter { scope.includesTrash(it.deletedAt) }
        val measurements = dao.allMeasurements().filter { it.pregnancyId in selected }
            .filter { scope.includesDate(java.time.Instant.ofEpochMilli(it.measuredAt).atZone(java.time.ZoneId.of(it.zoneId)).toLocalDate().toString()) }
            .filter { scope.includesTrash(it.deletedAt) }
        val measurementIds = measurements.map { it.id }.toSet()
        val values = dao.allMeasurementValues().filter { it.measurementId in measurementIds }
        val entries = dao.allJournalEntries().filter { it.pregnancyId in selected }
            .filter { scope.includesDate(it.entryDate) }.filter { scope.includesTrash(it.deletedAt) }
        val reminders = dao.allCustomReminders().filter { it.pregnancyId in selected }
            .filter { scope.includesDate(it.startDate) }.filter { scope.includesTrash(it.deletedAt) }
        val batches = dao.allDeletionBatches().filter { scope.includeTrash }

        val all = linkedMapOf(
            "pregnancies" to ExportRowMapper.pregnancies(pregnancies),
            "date_revisions" to ExportRowMapper.dateRevisions(dao.allDateRevisions().filter { it.pregnancyId in selected }),
            "visits" to ExportRowMapper.visits(visits),
            "attachments" to ExportRowMapper.attachments(attachments),
            "deletion_batches" to ExportRowMapper.deletionBatches(batches),
            "medications" to ExportRowMapper.medications(medications),
            "medication_plans" to ExportRowMapper.plans(plans),
            "medication_occurrences" to ExportRowMapper.occurrences(occurrences),
            "medication_intakes" to ExportRowMapper.intakes(intakes),
            "metric_definitions" to ExportRowMapper.definitions(definitions),
            "measurements" to ExportRowMapper.measurements(measurements),
            "measurement_values" to ExportRowMapper.values(values),
            "journal_entries" to ExportRowMapper.entries(entries),
            "custom_reminders" to ExportRowMapper.customReminders(reminders))
        // Preserve the catalog order and honour the chosen sections.
        return ExportScope.ALL_SECTIONS.filter { it in scope.sections }
            .map { ExportSection(it, all.getValue(it)) }
    }

    private fun buildSources(sections: List<ExportSection>): List<ExportSourceRef> = sections.flatMap { section ->
        when(section.name) {
            "visits" -> section.rows.map { row -> ExportSourceRef("visits",requireNotNull(row.values["id"]),
                "${row.values["scheduledDate"].orEmpty()} ${row.values["hospital"].orEmpty()}".trim(),row.values["pregnancyId"]) }
            "pregnancies" -> section.rows.map { row -> ExportSourceRef("pregnancies",requireNotNull(row.values["id"]),row.values["title"].orEmpty()) }
            else -> emptyList()
        }
    }

    private suspend fun selectedAttachments(scope: ExportScope): List<cn.yunji.journal.core.database.AttachmentEntity> {
        if("attachments" !in scope.sections)return emptyList()
        val dao=database.journalDao()
        val pregnancies=dao.allPregnancies().filter {scope.includesTrash(it.deletedAt)}.map {it.id}.toSet()
        val visits=dao.allVisits().filter {it.pregnancyId in pregnancies && scope.includesTrash(it.deletedAt) && scope.includesDate(it.scheduledDate)}.map {it.id}.toSet()
        val medications=dao.allMedications().filter {it.pregnancyId in pregnancies && scope.includesTrash(it.deletedAt)}.map {it.id}.toSet()
        return dao.allAttachments().filter {row -> scope.includesTrash(row.deletedAt) && scope.includesDate(row.documentDate) &&
            (row.visitId?.let {it in visits} ?: (row.medicationId?.let {it in medications} ?: false)) }
    }
    /**
     * Streams each plaintext original to a private file, then exposes it as a re-openable stream.
     *
     * Originals of trashed attachments are deliberately not decrypted: the importer refuses them, and a
     * trashed item is pending purge. The package still carries their metadata, and the count of omitted
     * originals is reported so a reader can tell the difference between "not included" and "missing".
     */
    private suspend fun materialiseOriginals(attachments: List<cn.yunji.journal.core.database.AttachmentEntity>, work: File):
        Pair<List<ExportOriginal>, Int> {
        val result = mutableListOf<ExportOriginal>()
        var omittedTrash = 0
        for (attachment in attachments.sortedBy { it.createdAt }) {
            if (attachment.deletedAt != null) { omittedTrash++; continue }
            val objectId = attachment.objectId ?: attachment.id
            val file = File(work, objectId)
            try {
                importer.materialisePinnedOriginal(attachment, file)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) {
                // A single unreadable original must not silently ship a shorter package: fail the export.
                throw IllegalStateException("原件「${attachment.name}」无法读取，导出已中止")
            }
            result += ExportOriginal(objectId, attachment.name, attachment.mime, attachment.plainSha256) { file.inputStream() }
        }
        return result to omittedTrash
    }
}
