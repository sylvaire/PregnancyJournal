package cn.yunji.journal.core.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class ExportSourceRef(val section: String, val id: String, val label: String, val parentId: String? = null)

@Serializable
data class ExportObjectRef(val objectId: String, val attachmentId: String, val name: String, val mime: String,
    val plainSha256: String, val path: String)

/** Written last, so it can state exactly what the package contains. */
@Serializable
data class ExportManifest(
    val header: ExportHeader,
    val counts: Map<String, Int>,
    val objects: List<ExportObjectRef>,
    val packets: List<String>,
)

@Serializable
data class FieldEntry(val key: String, val label: String, val type: String, val note: String)

/** Machine-readable field dictionary shipped inside the package. */
@Serializable
data class FieldDictionary(val format: String = "YJ-FIELDS-1",
    val sections: Map<String, List<FieldEntry>>, val excluded: List<String>)

@Serializable
data class ExportHeader(
    val format: String = "YJ-EXPORT-1",
    val generatedAt: Long,
    val vaultId: String,
    /** ISO date range actually applied, for the reader's benefit. */
    val from: String?,
    val to: String?,
    val includeTrash: Boolean,
    val includeOriginals: Boolean,
) {
    override fun toString() = "ExportHeader(<redacted>)"
}

/**
 * Writes the plain-text structured package: a ZIP containing the manifest, the field dictionary, one JSON
 * array per section, a source index and (optionally) the decrypted originals.
 *
 * The package is intentionally *not* encrypted: the caller must obtain explicit user confirmation and warn
 * that the result is unprotected. Originals are streamed and hashed, never buffered whole.
 */
object StructuredExportWriter {
    /** Streaming keeps memory flat; 0 as the entry time keeps the output reproducible. */
    private const val BUFFER = 64 * 1024

    private val json = Json { encodeDefaults = true; explicitNulls = true; prettyPrint = false }

    fun write(
        destination: OutputStream,
        header: ExportHeader,
        scope: ExportScope,
        sections: List<ExportSection>,
        sources: List<ExportSourceRef>,
        originals: List<ExportOriginal>,
        checkpoint: () -> Unit = {},
    ): ExportSummary {
        require(sections.map { it.name }.distinct().size == sections.size) { "导出范围重复" }
        sections.forEach { require(it.name in scope.sections) { "导出包含未选择的范围" } }
        val counting = CountingOutputStream(destination)
        var originalBytes = 0L
        val counts = linkedMapOf<String, Int>()
        val written = mutableListOf<ExportObjectRef>()
        val packets = mutableListOf<String>()
        ZipOutputStream(counting).use { zip ->
            fun entry(name: String, body: (OutputStream) -> Unit) {
                checkpoint()
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                body(zip)
                zip.closeEntry()
                packets += name
            }
            entry("README.txt") { out -> out.write(readme().toByteArray(Charsets.UTF_8)) }
            entry("fields.json") { out -> out.write(json.encodeToString(fieldDictionary()).toByteArray(Charsets.UTF_8)) }
            entry("sources.json") { out -> out.write(json.encodeToString(sources).toByteArray(Charsets.UTF_8)) }
            sections.forEach { section ->
                counts[section.name] = section.rows.size
                entry("data/${section.name}.json") { out -> writeRows(out, section) }
            }
            originals.forEach { original ->
                checkpoint()
                val digest = MessageDigest.getInstance("SHA-256")
                val path = "originals/${original.objectId}"
                zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                val digestOut = DigestOutputStream(NoCloseOutputStream(zip), digest)
                val size = original.open().use { input ->
                    val buffer = ByteArray(BUFFER); var total = 0L
                    while (true) {
                        checkpoint(); val n = input.read(buffer); if (n < 0) break
                        digestOut.write(buffer, 0, n); total += n
                    }
                    total
                }
                digestOut.flush()
                zip.closeEntry()
                packets += path
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                original.expectedSha256?.let { require(it == actual) { "原件校验失败，导出已中止" } }
                originalBytes += size
                written += ExportObjectRef(original.objectId, original.objectId, original.fileName, original.mime, actual, path)
            }
            // The manifest is written last so it can state exactly what the package contains.
            entry("manifest.json") { out ->
                val manifest = ExportManifest(header, counts.toMap(), written, packets.sorted())
                out.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            }
        }
        return ExportSummary(counts.toMap(), written.size, originalBytes, counting.count)
    }

    /** One JSON array per section, written row by row so a large section never materialises as one string. */
    private fun writeRows(out: OutputStream, section: ExportSection) {
        val keys = ExportFieldCatalog.keys(section.name)
        out.write("[".toByteArray(Charsets.UTF_8))
        section.rows.forEachIndexed { index, row ->
            if (index > 0) out.write(",".toByteArray(Charsets.UTF_8))
            // Export only catalogued keys, in catalog order: an unexpected column cannot leak by accident.
            val ordered = LinkedHashMap<String, String?>()
            keys.forEach { key -> ordered[key] = row.values[key] }
            val encoded = json.encodeToString(
                MapSerializer(String.serializer(), String.serializer().nullable), ordered)
            out.write(encoded.toByteArray(Charsets.UTF_8))
        }
        out.write("]".toByteArray(Charsets.UTF_8))
    }

    private fun fieldDictionary() = FieldDictionary(
        sections = ExportFieldCatalog.sections.mapValues { (_, fields) ->
            fields.map { FieldEntry(it.key, it.label, it.type, it.note) }
        },
        excluded = ExportFieldCatalog.exclusions,
    )

    private fun readme(): String = buildString {
        appendLine("孕迹结构化数据导出")
        appendLine()
        appendLine("本文件包是明文数据，未加密。请自行妥善保管，避免通过不安全的渠道发送或长期留在共享位置。")
        appendLine("包内包含：manifest.json（范围与生成时间）、fields.json（字段说明）、")
        appendLine("data/（各业务表的 UTF-8 JSON 数组）、sources.json（来源索引）、originals/（原件，如已选择）。")
        appendLine("空值以 null 表示，与空字符串含义不同。")
        appendLine()
        appendLine("本包不包含以下内容：")
        ExportFieldCatalog.exclusions.forEach { appendLine("- $it") }
    }

    /** Prevents the digest stream from closing the ZIP when an original's stream is closed. */
    private class NoCloseOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(b: Int) = delegate.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
        override fun flush() = delegate.flush()
        override fun close() { flush() }
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count = 0L
            private set
        override fun write(b: Int) { delegate.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { delegate.write(b, off, len); count += len }
        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
