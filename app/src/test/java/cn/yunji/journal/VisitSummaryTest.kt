package cn.yunji.journal

import cn.yunji.journal.core.export.*
import org.junit.Assert.*
import org.junit.Test

/** Visit summary composition and pagination: Chinese wrapping, empty values, and a non-clinical marker. */
class VisitSummaryTest {
    /** Fixed-width wrapping so pagination is deterministic in tests: 10 characters per physical line. */
    private fun wrap(width: Int = 10): (SummaryLine) -> List<SummaryLine> = { line ->
        val text = line.text
        when {
            text.isEmpty() -> listOf(line)
            else -> text.chunked(width).map { line.copy(text = it) }
        }
    }

    private fun summary(vararg sections: SummarySection, generatedAt: Long = 1_700_000_000_000) =
        VisitSummary(title = "就诊摘要", scopeLabel = "2026-01-01 至 2026-12-31", generatedAt = generatedAt,
            sections = sections.toList())

    @Test fun headerCarriesScopeGenerationTimeAndNonClinicalMarker() {
        val pages = SummaryPaginator.paginate(summary(), linesPerPage = 20, wrapLines = wrap())
        val text = pages.flatMap { it.lines }.joinToString("\n") { it.text }
        assertTrue(text.contains("2026-01-01 至 2026-12-31"))
        assertTrue(text.contains("生成时间："))
        assertTrue("the document must be marked as user-organised", text.contains(VisitSummary.MARKER))
        assertTrue("the marker must state it is not a clinical record", text.contains("不是医疗机构出具的记录"))
    }

    @Test fun emptyAndBlankValuesAreExplicitlyShownAndDistinctFromText() {
        val section = VisitSummaryComposer.section("孕期信息",
            VisitSummaryComposer.keyValue("预产期", null),
            VisitSummaryComposer.keyValue("建档医院", "   "),
            VisitSummaryComposer.keyValue("末次月经", "2026-03-01"))
        // Wide enough that each field stays on one physical line, so content can be asserted directly.
        val text = SummaryPaginator.paginate(summary(section), 30, wrap(60)).flatMap { it.lines }.joinToString("\n") { it.text }
        assertEquals(2, Regex(VisitSummary.EMPTY).findAll(text).count())
        assertTrue(text.contains("末次月经：2026-03-01"))
        assertFalse("a real value must never become the empty marker", text.contains("末次月经：${VisitSummary.EMPTY}"))
    }

    @Test fun everyPageIsAttributableAndNoContentIsLost() {
        val long = "很长的备注".repeat(30) // 150 characters -> 15 physical lines at width 10
        val section = VisitSummaryComposer.section("产检与备注",
            SummaryBlock.Paragraph(long),
            VisitSummaryComposer.keyValue("医生建议", "多休息"))
        val pages = SummaryPaginator.paginate(summary(section), linesPerPage = 10, wrapLines = wrap())
        assertTrue("a long note must span more than one page", pages.size >= 2)
        // The reconstructed text must contain the whole note, so nothing was truncated.
        val joined = pages.flatMap { it.lines }.filter { it.kind != SummaryLine.Kind.CONTINUATION }.joinToString("") { it.text }
        assertTrue(joined.contains(long))
        assertTrue(joined.contains("医生建议：多休息"))
        // Pages after the first that belong to a section carry a continuation header.
        pages.drop(1).forEach { page ->
            assertTrue("page ${page.index} must be attributable",
                page.lines.any { it.kind == SummaryLine.Kind.CONTINUATION })
        }
    }

    @Test fun sectionTitleIsNotOrphanedAtTheBottomOfAPage() {
        val first = VisitSummaryComposer.section("第一段", SummaryBlock.Paragraph("短".repeat(40)))
        val second = VisitSummaryComposer.section("第二段", VisitSummaryComposer.keyValue("值", "1"))
        val pages = SummaryPaginator.paginate(summary(first, second), linesPerPage = 12, wrapLines = wrap())
        pages.forEach { page ->
            val titleIndex = page.lines.indexOfFirst { it.kind == SummaryLine.Kind.SECTION && it.text == "第二段" }
            if (titleIndex >= 0) {
                assertTrue("a section title must be followed by its content on the same page",
                    page.lines.drop(titleIndex + 1).isNotEmpty())
            }
        }
        assertTrue("second section must still be rendered",
            pages.flatMap { it.lines }.any { it.text == "第二段" })
    }

    @Test fun pagesRespectCapacityAndAreNumberedFromOne() {
        val section = VisitSummaryComposer.section("指标", SummaryBlock.Paragraph("数据".repeat(100)))
        val pages = SummaryPaginator.paginate(summary(section), linesPerPage = 14, wrapLines = wrap())
        pages.forEachIndexed { index, page ->
            assertEquals(index + 1, page.index)
            assertTrue("page must not exceed capacity", page.lines.size <= 14)
            assertTrue("page must not be empty", page.lines.isNotEmpty())
        }
    }

    @Test fun minimumCapacityIsEnforced() {
        assertTrue(runCatching { SummaryPaginator.paginate(summary(), 4, wrap()) }.isFailure)
    }

    @Test fun tableRowsKeepTheirColumnsAndHeaderFlag() {
        val section = VisitSummaryComposer.section("用药",
            SummaryBlock.TableRow(listOf("日期", "药品", "剂量"), header = true),
            SummaryBlock.TableRow(listOf("2026-05-01", "合成药品", "1片")))
        val lines = SummaryPaginator.paginate(summary(section), 30, wrap(40)).flatMap { it.lines }
        val header = lines.single { it.kind == SummaryLine.Kind.TABLE && it.emphasized }
        assertTrue(header.text.contains("日期  |  药品  |  剂量"))
        assertTrue(lines.any { it.text.contains("2026-05-01  |  合成药品  |  1片") })
    }
}
