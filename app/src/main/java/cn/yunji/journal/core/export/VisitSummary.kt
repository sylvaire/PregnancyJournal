package cn.yunji.journal.core.export

/** One renderable unit of the visit summary. Nothing here depends on Android. */
sealed interface SummaryBlock {
    data class Heading(val text: String, val level: Int = 1) : SummaryBlock
    data class Paragraph(val text: String) : SummaryBlock
    /** A labelled value; a null or blank value is rendered as [EMPTY] rather than omitted. */
    data class KeyValue(val label: String, val value: String?) : SummaryBlock
    data class TableRow(val cells: List<String>, val header: Boolean = false) : SummaryBlock
    data class Note(val text: String) : SummaryBlock
}

data class SummarySection(val title: String, val blocks: List<SummaryBlock>)

data class VisitSummary(
    val title: String,
    val scopeLabel: String,
    val generatedAt: Long,
    val sections: List<SummarySection>,
) {
    /** Marks the document as user-organised material, never a clinical record. */
    val marker: String get() = MARKER

    companion object {
        const val MARKER = "用户整理记录"
        const val EMPTY = "未填写"
    }
}

data class SummaryLine(val text: String, val kind: Kind, val emphasized: Boolean = false) {
    enum class Kind { TITLE, META, SECTION, BODY, KEY_VALUE, TABLE, NOTE, CONTINUATION }
}

data class SummaryPage(val index: Int, val lines: List<SummaryLine>)

/**
 * Composes the summary body and paginates it for a page of fixed line capacity.
 *
 * The renderer supplies the wrap measurement, so pagination is exercised without a device and the same
 * rules apply to the PDF. Empty values are kept as [VisitSummary.EMPTY] so a reader can tell "not recorded"
 * apart from "recorded as blank". Nothing is truncated: a long note simply occupies more lines.
 */
object VisitSummaryComposer {
    /** Every detail line is "label: value" so a reader can always see which field it came from. */
    fun keyValue(label: String, value: String?): SummaryBlock =
        SummaryBlock.KeyValue(label, value?.takeIf { it.isNotBlank() })

    fun section(title: String, vararg blocks: SummaryBlock) = SummarySection(title, blocks.toList())
}

object SummaryPaginator {
    /**
     * @param wrapLines splits one logical line into the physical lines a page can hold at this width;
     *   the renderer supplies the real measurement, so pagination is testable without a device.
     * @param linesPerPage physical capacity of one page.
     */
    fun paginate(summary: VisitSummary, linesPerPage: Int, wrapLines: (SummaryLine) -> List<SummaryLine>): List<SummaryPage> {
        require(linesPerPage >= 8) { "每页行数过小，无法排版" }
        val pages = mutableListOf<SummaryPage>()
        var current = mutableListOf<SummaryLine>()
        var currentSection: String? = null

        fun flush() {
            if (current.isEmpty()) return
            pages += SummaryPage(pages.size + 1, current)
            current = mutableListOf()
        }
        fun push(lines: List<SummaryLine>) {
            lines.forEach { line ->
                if (current.size >= linesPerPage) {
                    flush()
                    // A continuation header keeps every page attributable to its section.
                    currentSection?.let { current += SummaryLine(it, SummaryLine.Kind.CONTINUATION) }
                }
                current += line
            }
        }

        push(listOf(SummaryLine(summary.title, SummaryLine.Kind.TITLE, emphasized = true)))
        push(listOf(
            SummaryLine("范围：${summary.scopeLabel}", SummaryLine.Kind.META),
            SummaryLine("生成时间：${formatIso(summary.generatedAt)}", SummaryLine.Kind.META),
            SummaryLine("说明：本文档由${VisitSummary.MARKER}，不是医疗机构出具的记录。", SummaryLine.Kind.NOTE),
        ))
        summary.sections.forEach { section ->
            // Keep the section title with its first line rather than orphaning it at a page end.
            if (current.size + 2 > linesPerPage) flush()
            currentSection = section.title
            push(listOf(SummaryLine(section.title, SummaryLine.Kind.SECTION, emphasized = true)))
            section.blocks.forEach { block -> push(render(block, wrapLines)) }
        }
        flush()
        if (pages.isEmpty()) pages += SummaryPage(1, emptyList())
        return pages
    }

    private fun render(block: SummaryBlock, wrapLines: (SummaryLine) -> List<SummaryLine>): List<SummaryLine> = when (block) {
        is SummaryBlock.Heading -> wrapLines(SummaryLine(block.text, SummaryLine.Kind.SECTION, emphasized = true))
        is SummaryBlock.Paragraph -> wrapLines(SummaryLine(block.text, SummaryLine.Kind.BODY))
        is SummaryBlock.KeyValue -> wrapLines(SummaryLine("${block.label}：${block.value ?: VisitSummary.EMPTY}", SummaryLine.Kind.KEY_VALUE))
        is SummaryBlock.TableRow -> wrapLines(SummaryLine(block.cells.joinToString("  |  "), SummaryLine.Kind.TABLE, block.header))
        is SummaryBlock.Note -> wrapLines(SummaryLine(block.text, SummaryLine.Kind.NOTE))
    }

    internal fun formatIso(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
