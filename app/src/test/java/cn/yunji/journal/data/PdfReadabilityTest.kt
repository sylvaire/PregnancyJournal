package cn.yunji.journal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * API 26 的 pdfium 会被一次失败的 PdfRenderer 打开永久污染，因此结构明显不完整的 PDF 必须在
 * 进入原生解析前被挡住。这里固定这条门禁的判定边界。
 */
class PdfReadabilityTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun file(bytes: ByteArray): File = temporary.newFile().also { it.writeBytes(bytes) }

    @Test fun completeDocumentPassesTheGate() {
        assertNull(PdfReadability.problem(file(complete())))
    }

    @Test fun missingTrailerIsRejected() {
        assertEquals("缺少 PDF 文件尾", PdfReadability.problem(file(complete().dropLast(6).toByteArray())))
    }

    @Test fun missingHeaderIsRejected() {
        val bytes = "not a pdf at all, only text that is long enough".toByteArray() + complete()
        assertEquals("缺少 PDF 文件头", PdfReadability.problem(file(bytes)))
    }

    @Test fun truncatedSyntheticDocumentIsRejected() {
        assertNotNull(PdfReadability.problem(file("%PDF-1.7\ntruncated synthetic document".toByteArray())))
    }

    @Test fun tinyFileIsRejected() {
        assertEquals("文件长度不足", PdfReadability.problem(file(byteArrayOf(37, 80, 68, 70))))
    }

    @Test fun missingFileIsRejected() {
        assertEquals("文件不存在", PdfReadability.problem(File(temporary.root, "absent.pdf")))
    }

    @Test(expected = IOException::class)
    fun requireReadableThrowsForBrokenDocument() {
        PdfReadability.requireReadable(file("%PDF-1.7\ntruncated".toByteArray()))
    }

    private fun complete(): ByteArray =
        ("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Count 0>>endobj\n" +
            "trailer<</Root 1 0 R>>\nstartxref\n0\n%%EOF\n").toByteArray()
}
