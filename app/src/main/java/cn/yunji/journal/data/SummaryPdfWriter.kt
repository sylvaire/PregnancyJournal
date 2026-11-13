package cn.yunji.journal.data

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import cn.yunji.journal.core.export.*
import java.io.OutputStream

/**
 * Renders a [VisitSummary] into a multi-page A4 PDF with Android's own PDF API.
 *
 * Pagination is delegated to [SummaryPaginator] so the layout rules are the tested ones; this class only
 * measures text (including Chinese glyphs) and draws the resulting physical lines.
 */
object SummaryPdfWriter {
    // A4 at 72 dpi, the unit PdfDocument uses when no width/height are given.
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f
    private const val LINE_GAP = 4f
    private const val BOTTOM_MARGIN = 56f

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = Paint().apply {
        isAntiAlias = true
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        else Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private fun styleOf(line: SummaryLine): Pair<Paint, Float> = when (line.kind) {
        SummaryLine.Kind.TITLE -> paint(22f, true) to 30f
        SummaryLine.Kind.META -> paint(10f, false, Color.DKGRAY) to 15f
        SummaryLine.Kind.SECTION -> paint(15f, true) to 24f
        SummaryLine.Kind.KEY_VALUE -> paint(11.5f) to 17f
        SummaryLine.Kind.TABLE -> paint(10.5f, line.emphasized) to 15.5f
        SummaryLine.Kind.NOTE -> paint(10f, false, Color.DKGRAY) to 15f
        SummaryLine.Kind.BODY -> paint(11.5f) to 17f
        SummaryLine.Kind.CONTINUATION -> paint(9f, false, Color.GRAY) to 14f
    }

    /**
     * @return number of pages written.
     */
    fun write(summary: VisitSummary, destination: OutputStream): Int {
        val available = PAGE_HEIGHT - MARGIN - BOTTOM_MARGIN
        val maxWidth = PAGE_WIDTH - 2 * MARGIN
        val wrap: (SummaryLine) -> List<SummaryLine> = { line ->
            val (paint, _) = styleOf(line)
            wrapText(line, paint, maxWidth)
        }
        // Capacity uses the tallest line advance, so a page of headings can never spill past the margin.
        val tallest = 24f + LINE_GAP
        val linesPerPage = maxOf(8, (available / tallest).toInt())
        val pages = SummaryPaginator.paginate(summary, linesPerPage, wrap)
        val document = PdfDocument()
        try {
            pages.forEach { page ->
                val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, page.index).create()
                val pdfPage = document.startPage(info)
                draw(pdfPage.canvas, page)
                document.finishPage(pdfPage)
            }
            document.writeTo(destination)
        } finally { document.close() }
        return pages.size
    }

    private fun draw(canvas: Canvas, page: SummaryPage) {
        canvas.drawColor(Color.WHITE)
        var y = MARGIN + 12f
        val maxWidth = PAGE_WIDTH - 2 * MARGIN
        page.lines.forEach { line ->
            val (paint, advance) = styleOf(line)
            // A defensive re-wrap: a line that would overflow is split rather than drawn past the margin.
            wrapText(line, paint, maxWidth).forEach { physical ->
                canvas.drawText(physical.text, MARGIN, y, paint)
                y += advance + LINE_GAP
            }
        }
        val footer = paint(9f, false, Color.GRAY)
        canvas.drawText("第 ${page.index} 页 · ${VisitSummary.MARKER}", MARGIN, PAGE_HEIGHT - 28f, footer)
    }

    /** Greedy, glyph-by-glyph wrapping so CJK text (which has no spaces) breaks correctly. */
    internal fun wrapText(line: SummaryLine, paint: Paint, maxWidth: Float): List<SummaryLine> {
        if (line.text.isEmpty()) return listOf(line)
        if (paint.measureText(line.text) <= maxWidth) return listOf(line)
        val out = mutableListOf<SummaryLine>()
        val builder = StringBuilder()
        line.text.forEach { ch ->
            builder.append(ch)
            if (paint.measureText(builder.toString()) > maxWidth) {
                // Move the overflowing character to the next line.
                builder.deleteCharAt(builder.length - 1)
                if (builder.isNotEmpty()) out += line.copy(text = builder.toString())
                builder.setLength(0)
                builder.append(ch)
            }
        }
        if (builder.isNotEmpty()) out += line.copy(text = builder.toString())
        return out.ifEmpty { listOf(line) }
    }
}
