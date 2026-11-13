package cn.yunji.journal.core.export

/** Which records and which parts of them an export contains. */
data class ExportScope(
    /** Inclusive lower bound on the record's own date field, ISO yyyy-MM-dd, or null for unbounded. */
    val from: String? = null,
    /** Inclusive upper bound, or null for unbounded. */
    val to: String? = null,
    val includeOriginals: Boolean = true,
    /** Trash entries are ordinary rows with a deletion marker; exporting them is opt-in. */
    val includeTrash: Boolean = false,
    val sections: Set<String> = ALL_SECTIONS,
) {
    init {
        require(from == null || isIsoDate(from)) { "导出起始日期无效" }
        require(to == null || isIsoDate(to)) { "导出结束日期无效" }
        require(from == null || to == null || from <= to) { "导出日期的起始不能晚于结束" }
        require(sections.isNotEmpty()) { "请至少选择一个导出范围" }
        require(sections.all { it in ALL_SECTIONS }) { "导出范围包含未知项目" }
    }

    /** True when a dated record falls inside the range. A record without a date is always included. */
    fun includesDate(value: String?): Boolean {
        if (value == null) return true
        if (from != null && value < from) return false
        if (to != null && value > to) return false
        return true
    }

    fun includesTrash(deletedAt: Long?): Boolean = includeTrash || deletedAt == null

    companion object {
        /** A syntactically valid but impossible date such as 2026-13-01 must be rejected, not exported. */
        private fun isIsoDate(value: String): Boolean =
            runCatching { java.time.LocalDate.parse(value, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE) }
                .map { it.toString() == value }.getOrDefault(false)

        val ALL_SECTIONS: Set<String> = linkedSetOf(
            "pregnancies", "date_revisions", "visits", "attachments", "deletion_batches",
            "medications", "medication_plans", "medication_occurrences", "medication_intakes",
            "metric_definitions", "measurements", "measurement_values", "journal_entries",
            "custom_reminders")
    }
}

/** One exported record: field key to text value, with null for "no value" as distinct from empty text. */
data class ExportRow(val values: Map<String, String?>) {
    override fun toString() = "ExportRow(fields=${values.size})"
}

data class ExportSection(val name: String, val rows: List<ExportRow>)

/** A plaintext original written alongside the JSON, streamed so large files never buffer in memory. */
class ExportOriginal(
    val objectId: String,
    val fileName: String,
    val mime: String,
    /** Expected plaintext SHA-256 when known; the writer verifies it while copying. */
    val expectedSha256: String? = null,
    val open: () -> java.io.InputStream,
)

data class ExportSummary(
    val countsBySection: Map<String, Int>,
    val originalsWritten: Int,
    val originalBytes: Long,
    val totalBytes: Long,
)
