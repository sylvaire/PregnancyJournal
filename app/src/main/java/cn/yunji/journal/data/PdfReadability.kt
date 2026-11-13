package cn.yunji.journal.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Cheap structural check for PDF files before the platform parser touches them.
 *
 * On API 26 the emulator's pdfium keeps process-wide state after one failed load: handing a
 * truncated document to [android.graphics.pdf.PdfRenderer] makes every later renderer in the same
 * process fail with `file not in PDF format or corrupted`, until the process restarts (verified on
 * the API 26 emulator by `Api26PdfProbeTest`). The check therefore rejects files that cannot be
 * complete PDFs (missing header, missing trailing `%%EOF`, implausibly small) before any native
 * call. It does not parse the cross-reference table and is not a substitute for the IOException
 * handling in the import and preview paths.
 */
internal object PdfReadability {
    private const val MIN_BYTES = 32L
    private const val TRAILER_SCAN = 4096L
    private val HEADER = "%PDF-".toByteArray(Charsets.US_ASCII)

    /** @return a human-readable reason when [file] is not structurally a complete PDF, else null. */
    fun problem(file: File): String? {
        if (!file.isFile) return "文件不存在"
        if (file.length() < MIN_BYTES) return "文件长度不足"
        return RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(HEADER.size)
            input.readFully(header)
            if (!header.contentEquals(HEADER)) return@use "缺少 PDF 文件头"
            val scan = minOf(input.length(), TRAILER_SCAN).toInt()
            input.seek(input.length() - scan)
            val tail = ByteArray(scan)
            input.readFully(tail)
            if (String(tail, Charsets.ISO_8859_1).contains("%%EOF")) null else "缺少 PDF 文件尾"
        }
    }

    fun requireReadable(file: File) {
        problem(file)?.let { throw IOException("PDF 文件不完整：$it") }
    }
}
