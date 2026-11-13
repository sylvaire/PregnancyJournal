package cn.yunji.journal.data

import cn.yunji.journal.core.database.AttachmentEntity
import cn.yunji.journal.core.database.ImportJobEntity
import java.text.Normalizer
import java.util.Locale

val DOCUMENT_CATEGORIES = linkedMapOf("OTHER" to "其他", "ULTRASOUND" to "超声", "LAB" to "检验", "SCREENING" to "筛查", "PRESCRIPTION" to "处方", "RECORD" to "病历","PACKAGE" to "药品包装")
val DOCUMENT_SORTS = listOf("最近导入", "最早导入", "名称顺序")

/** Match supplied metadata; do not imply OCR or PDF full-text indexing. */
fun queryDocuments(jobs: List<ImportJobEntity>, attachments: List<AttachmentEntity>, query: String, category: String, sort: Int): List<ImportJobEntity> {
    val metadata = attachments.associateBy { it.id }
    fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val needle = normalized(query.trim())
    val comparator = when (sort) {
        1 -> compareBy<ImportJobEntity> { it.createdAt }.thenBy { it.id }
        2 -> compareBy<ImportJobEntity> { normalized(metadata[it.id]?.name ?: it.name) }.thenBy { it.id }
        else -> compareByDescending<ImportJobEntity> { it.createdAt }.thenBy { it.id }
    }
    return jobs.filter { job ->
        val attachment = metadata[job.id]
        job.state in setOf("QUEUED", "IMPORTING", "READY", "FAILED") && attachment?.deletedAt == null &&
            (category.isEmpty() || (attachment?.category ?: "OTHER") == category) &&
            normalized(listOf(attachment?.name ?: job.name,attachment?.note.orEmpty(),attachment?.hospital.orEmpty()).joinToString(" ")).contains(needle)
    }.sortedWith(comparator)
}
