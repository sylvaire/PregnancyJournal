package cn.yunji.journal.data

import android.content.Context
import android.net.Uri
import cn.yunji.journal.core.export.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/** Raised when the chosen destination already holds content, so a user file is never overwritten. */
class ExportDestinationOccupiedException : IllegalStateException("所选位置已有内容，请新建一个文件")

/**
 * Writes plaintext exports to a user-chosen SAF destination.
 *
 * Contract:
 * - the destination must be empty: an existing file is never overwritten;
 * - the write is streamed, then *read back through the provider* and structurally verified, so a silently
 *   truncated document is reported as a failure instead of a success;
 * - no persistable URI permission is requested for exports, and any grant this app may still hold for the
 *   destination is released when the export finishes.
 */
class ExportSafWriter(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    private companion object {
        const val TAIL = 64
    }

    private fun requireEmpty(destination: Uri) {
        require(destination.scheme == "content") { "请选择可写入的导出位置" }
        resolver.openInputStream(destination).use { input ->
            requireNotNull(input) { "无法读取所选导出位置" }
            if (input.read() != -1) throw ExportDestinationOccupiedException()
        }
    }

    suspend fun writeStructuredPackage(destination: Uri, header: ExportHeader, scope: ExportScope,
        sections: List<ExportSection>, sources: List<ExportSourceRef>, originals: List<ExportOriginal>): ExportSummary =
        withContext(Dispatchers.IO) {
            val task = currentCoroutineContext()
            requireEmpty(destination)
            val summary = requireNotNull(resolver.openOutputStream(destination, "wt")).use { output ->
                StructuredExportWriter.write(output, header, scope, sections, sources, originals) { task.ensureActive() }
            }
            task.ensureActive()
            verifyZip(destination, summary)
            releaseTransientAccess(destination)
            summary
        }

    suspend fun writeSummaryPdf(destination: Uri, summary: VisitSummary): Int = withContext(Dispatchers.IO) {
        val task = currentCoroutineContext()
        requireEmpty(destination)
        val pages = requireNotNull(resolver.openOutputStream(destination, "wt")).use { output ->
            SummaryPdfWriter.write(summary, output)
        }
        task.ensureActive()
        verifyPdf(destination)
        releaseTransientAccess(destination)
        pages
    }

    /** Reopens the archive and confirms the manifest is present and readable, not just that bytes exist. */
    private fun verifyZip(destination: Uri, summary: ExportSummary) {
        var sawManifest = false
        requireNotNull(resolver.openInputStream(destination)).use { input ->
            ZipInputStream(input.buffered(64 * 1024)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "manifest.json") {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        require(text.contains("YJ-EXPORT-1")) { "导出包回读校验失败" }
                        sawManifest = true
                    }
                }
            }
        }
        require(sawManifest) { "导出包缺少清单，回读校验失败" }
        require(summary.totalBytes > 0) { "导出内容为空" }
    }

    /** A PDF must start with its header and end with the end-of-file marker; both are cheap to check. */
    private fun verifyPdf(destination: Uri): Long {
        var size = 0L
        var tail = ByteArray(0)
        requireNotNull(resolver.openInputStream(destination)).use { input ->
            val head = ByteArray(5)
            require(input.read(head) == 5 && String(head) == "%PDF-") { "导出文件不是有效的 PDF" }
            size = 5
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                size += n
                // Counting via read() avoids provider-specific skip() behaviour and keeps memory flat.
                tail = if (n >= TAIL) buffer.copyOfRange(n - TAIL, n)
                else (tail + buffer.copyOf(n)).let { if (it.size <= TAIL) it else it.copyOfRange(it.size - TAIL, it.size) }
            }
        }
        require(size > 512) { "导出文件过小，可能未写入完整" }
        require(String(tail, Charsets.ISO_8859_1).contains("%%EOF")) { "PDF 缺少结束标记，回读校验失败" }
        return size
    }

    /**
     * Exports are one-shot writes, so no persistable grant should remain.
     *
     * Only a grant this app actually holds may be released: calling `releasePersistableUriPermission` for a
     * grant that was never taken makes some providers (observed with the MediaStore provider on API34)
     * crash inside `UriPermissionOwner`, which kills the provider process.
     */
    fun releaseTransientAccess(destination: Uri) {
        val held = resolver.persistedUriPermissions.any { it.uri == destination }
        if (!held) return
        runCatching {
            resolver.releasePersistableUriPermission(destination,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }
}
