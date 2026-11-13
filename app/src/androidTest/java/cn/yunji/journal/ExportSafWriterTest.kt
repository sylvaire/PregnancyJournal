package cn.yunji.journal

import android.content.ContentValues
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.database.PregnancyEntity
import cn.yunji.journal.core.database.VisitEntity
import cn.yunji.journal.core.export.*
import cn.yunji.journal.data.ExportDestinationOccupiedException
import cn.yunji.journal.data.ExportSafWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.zip.ZipInputStream

/** SAF destination handling: empty-target rule, real write, read-back verification, permission release. */
class ExportSafWriterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun destination(suffix: String): android.net.Uri =
        TestDestinations.empty(context, "Yunji-Export-${UUID.randomUUID()}.$suffix")

    private fun summaryInputs() = SummaryInputs(
        pregnancies = listOf(PregnancyEntity("p1", "合成孕期档案", lmpDate = "2026-03-01", createdAt = 1, updatedAt = 1)),
        visits = (1..6).map { VisitEntity("v$it", "p1", "2026-05-1$it", zoneId = "UTC", createdAt = 1, updatedAt = 1) })

    @Test fun summaryPdfIsWrittenAndVerifiedThroughTheProvider() = runBlocking {
        val writer = ExportSafWriter(context)
        val uri = destination("pdf")
        try {
            val summary = SummaryDataBuilder.build(summaryInputs(), ExportScope(), "p1", 1_700_000_000_000)
            val pages = writer.writeSummaryPdf(uri, summary)
            assertTrue("a summary of six visits must render at least one page", pages >= 1)
            val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
            assertEquals("%PDF-", String(bytes.copyOfRange(0, 5)))
            assertTrue("the document must carry its end marker",
                String(bytes.copyOfRange(bytes.size - 64, bytes.size), Charsets.ISO_8859_1).contains("%%EOF"))
            // The written document must be a genuinely readable PDF, not just bytes with a header.
            val file = java.io.File(context.cacheDir, "saf-${UUID.randomUUID()}.pdf")
            try {
                file.writeBytes(bytes)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { assertEquals(pages, it.pageCount) }
                }
            } finally { file.delete() }
        } finally { TestDestinations.delete(context, uri) }
    }

    @Test fun structuredPackageIsWrittenAndManifestIsReadableFromTheDestination() = runBlocking {
        val writer = ExportSafWriter(context)
        val uri = destination("zip")
        try {
            val body = "synthetic original".toByteArray()
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            val summary = writer.writeStructuredPackage(uri,
                ExportHeader(generatedAt = 1_700_000_000_000, vaultId = "vault-1", from = null, to = null,
                    includeTrash = false, includeOriginals = true),
                ExportScope(),
                listOf(ExportSection("pregnancies", listOf(ExportRow(linkedMapOf("id" to "p1", "title" to "合成档案"))))),
                listOf(ExportSourceRef("pregnancies", "p1", "合成档案")),
                listOf(ExportOriginal("o1", "原件.png", "image/png", sha) { body.inputStream() }))
            assertEquals(1, summary.countsBySection["pregnancies"])
            assertEquals(1, summary.originalsWritten)

            val entries = linkedMapOf<String, ByteArray>()
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                ZipInputStream(input.buffered(64 * 1024)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries[entry.name] = zip.readBytes()
                    }
                }
            }
            assertTrue(entries.containsKey("manifest.json"))
            assertTrue(String(entries.getValue("manifest.json")).contains("YJ-EXPORT-1"))
            assertArrayEquals(body, entries.getValue("originals/o1"))
        } finally { TestDestinations.delete(context, uri) }
    }

    @Test fun occupiedDestinationIsRefusedAndTheUserFileIsPreserved() = runBlocking {
        val writer = ExportSafWriter(context)
        val uri = destination("pdf")
        try {
            val original = "user content that must survive".toByteArray()
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(original) }
            val summary = SummaryDataBuilder.build(summaryInputs(), ExportScope(), "p1", 1)
            val error = runCatching { writer.writeSummaryPdf(uri, summary) }.exceptionOrNull()
            assertTrue("an occupied destination must be reported, not overwritten",
                error is ExportDestinationOccupiedException)
            requireNotNull(context.contentResolver.openInputStream(uri)).use { assertArrayEquals(original, it.readBytes()) }
        } finally { TestDestinations.delete(context, uri) }
    }

    @Test fun releasingATransientGrantIsSafeWhenNoneWasTaken() = runBlocking {
        val writer = ExportSafWriter(context)
        val uri = destination("zip")
        try {
            // Must not throw: exports never request a persistable grant.
            writer.releaseTransientAccess(uri)
            val summary = SummaryDataBuilder.build(summaryInputs(), ExportScope(), "p1", 1)
            val pdf = destination("pdf")
            try {
                writer.writeSummaryPdf(pdf, summary)
                writer.releaseTransientAccess(pdf)
                requireNotNull(context.contentResolver.openInputStream(pdf)).use { assertTrue(it.read() != -1) }
            } finally { TestDestinations.delete(context, pdf) }
        } finally { TestDestinations.delete(context, uri) }
    }
}
