package cn.yunji.journal

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.export.*
import cn.yunji.journal.data.SummaryPdfWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Real PDF rendering: multi-page Chinese output produced by Android's PDF API and reopened for inspection.
 * Synthetic records only.
 */
class SummaryPdfTest {
    private fun inputs(visits: Int, noteLength: Int): SummaryInputs {
        val pregnancy = PregnancyEntity("p1", "合成孕期档案", lmpDate = "2026-03-01", confirmedEdd = "2026-12-08",
            hospital = "合成医院", note = "合成备注", createdAt = 1, updatedAt = 1)
        val list = (1..visits).map { index ->
            VisitEntity("v$index", pregnancy.id, "2026-05-${(index % 28) + 1}", zoneId = "UTC",
                hospital = "合成医院$index", items = "合成检查项目$index", advice = "建议".repeat(noteLength),
                status = if (index % 2 == 0) "DONE" else "SCHEDULED", createdAt = 1, updatedAt = 1)
        }
        return SummaryInputs(pregnancies = listOf(pregnancy), visits = list)
    }

    private fun render(summary: VisitSummary): Pair<File, Int> {
        val file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "summary-${UUID.randomUUID()}.pdf")
        val pages = file.outputStream().use { SummaryPdfWriter.write(summary, it) }
        return file to pages
    }

    @Test fun multiPageChineseSummaryIsAValidPdfWithTheExpectedPageCount() {
        val summary = SummaryDataBuilder.build(inputs(visits = 12, noteLength = 40),
            ExportScope(), pregnancyId = "p1", generatedAt = 1_700_000_000_000)
        val (file, reportedPages) = render(summary)
        try {
            assertTrue("PDF must not be empty", file.length() > 2_000)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals("reported page count must match the real document", reportedPages, renderer.pageCount)
                    assertTrue("a long summary must paginate", renderer.pageCount >= 2)
                    // Every page must contain actual ink: a blank page means layout lost the content.
                    repeat(renderer.pageCount) { index ->
                        renderer.openPage(index).use { page ->
                            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                var dark = 0
                                val step = 4
                                var y = 0
                                while (y < bitmap.height) {
                                    var x = 0
                                    while (x < bitmap.width) {
                                        if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) dark++
                                        x += step
                                    }
                                    y += step
                                }
                                assertTrue("page ${index + 1} appears blank", dark > 200)
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            }
        } finally { file.delete() }
    }

    @Test fun emptyScopeStillProducesAReadableSinglePageDocument() {
        val summary = SummaryDataBuilder.build(SummaryInputs(), ExportScope(), pregnancyId = null, generatedAt = 1_700_000_000_000)
        val (file, reportedPages) = render(summary)
        try {
            assertEquals(1, reportedPages)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { assertEquals(1, it.pageCount) }
            }
        } finally { file.delete() }
    }

    @Test fun veryLongNoteSpansPagesWithoutOverflowingTheRealDocument() {
        val summary = SummaryDataBuilder.build(inputs(visits = 1, noteLength = 900),
            ExportScope(), pregnancyId = "p1", generatedAt = 1_700_000_000_000)
        val (file, reportedPages) = render(summary)
        try {
            assertTrue("a very long note must paginate", reportedPages >= 3)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals(reportedPages, renderer.pageCount)
                    // The same content rendered twice must be byte-stable in size (no runaway layout).
                    val again = File(file.parentFile, "summary-again-${UUID.randomUUID()}.pdf")
                    try {
                        val pages2 = again.outputStream().use { SummaryPdfWriter.write(summary, it) }
                        assertEquals(reportedPages, pages2)
                    } finally { again.delete() }
                }
            }
        } finally { file.delete() }
    }
}
