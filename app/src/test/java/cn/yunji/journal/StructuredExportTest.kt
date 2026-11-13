package cn.yunji.journal

import cn.yunji.journal.core.export.*
import cn.yunji.journal.core.database.VisitEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Structured plaintext package: catalogue-complete JSON, source index, streamed originals, real nulls. */
class StructuredExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = false }

    private fun header(now: Long = 1_700_000_000_000) = ExportHeader(generatedAt = now, vaultId = "vault-1",
        from = null, to = null, includeTrash = false, includeOriginals = true)

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun write(
        sections: List<ExportSection>,
        sources: List<ExportSourceRef> = emptyList(),
        originals: List<ExportOriginal> = emptyList(),
        scope: ExportScope = ExportScope(),
        checkpoint: () -> Unit = {},
    ): Pair<Map<String, ByteArray>, ExportSummary> {
        val buffer = ByteArrayOutputStream()
        val summary = StructuredExportWriter.write(buffer, header(), scope, sections, sources, originals, checkpoint)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(buffer.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries to summary
    }

    private fun row(vararg pairs: Pair<String, String?>) = ExportRow(linkedMapOf(*pairs))

    @Test fun actualVisitColumnsExportSeparatelyFromAppointment() {
        val visit = VisitEntity("v", "p", "2026-10-18", scheduledTime = "08:00", zoneId = "UTC",
            hospital = "预约医院", actualDate = "2026-10-18", actualTime = "09:30",
            actualHospital = "实际医院", actualResult = "合成检查结果", createdAt = 1, updatedAt = 1)
        val values = ExportRowMapper.visits(listOf(visit)).single().values
        assertEquals("08:00", values["scheduledTime"])
        assertEquals("预约医院", values["hospital"])
        assertEquals("09:30", values["actualTime"])
        assertEquals("实际医院", values["actualHospital"])
        assertEquals("合成检查结果", values["actualResult"])
        listOf("actualTime", "actualHospital", "actualResult").forEach { assertTrue(ExportFieldCatalog.keys("visits").contains(it)) }
    }

    @Test fun packageContainsReadmeFieldsManifestDataSourcesAndOriginal() {
        val body = "synthetic original bytes".toByteArray()
        val (entries, summary) = write(
            sections = listOf(ExportSection("pregnancies", listOf(row("id" to "p1", "title" to "合成档案")))),
            sources = listOf(ExportSourceRef("pregnancies", "p1", "合成档案")),
            originals = listOf(ExportOriginal("object-1", "报告.png", "image/png", sha(body)) { body.inputStream() }))

        listOf("README.txt", "fields.json", "sources.json", "manifest.json",
            "data/pregnancies.json", "originals/object-1").forEach {
            assertTrue("missing packet $it", entries.containsKey(it))
        }
        assertArrayEquals(body, entries.getValue("originals/object-1"))
        assertEquals(1, summary.originalsWritten)
        assertEquals(body.size.toLong(), summary.originalBytes)
        assertTrue(summary.totalBytes > 0)
        assertEquals(mapOf("pregnancies" to 1), summary.countsBySection)
        assertTrue(String(entries.getValue("README.txt")).contains("明文"))
    }

    @Test fun manifestIsWrittenLastAndStatesCountsAndObjects() {
        val body = "x".toByteArray()
        val (entries, _) = write(
            sections = listOf(ExportSection("journal_entries", listOf(row("id" to "e1"), row("id" to "e2")))),
            originals = listOf(ExportOriginal("o1", "a.png", "image/png", sha(body)) { body.inputStream() }))
        val manifest = json.parseToJsonElement(String(entries.getValue("manifest.json"))).jsonObject
        assertEquals(2, manifest["counts"]!!.jsonObject["journal_entries"]!!.jsonPrimitive.content.toInt())
        val objects = manifest["objects"]!!.jsonArray
        assertEquals(1, objects.size)
        assertEquals("originals/o1", objects[0].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(sha(body), objects[0].jsonObject["plainSha256"]!!.jsonPrimitive.content)
        val packets = manifest["packets"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("manifest must list every packet", packets.containsAll(entries.keys - "manifest.json"))
        assertEquals("YJ-EXPORT-1", manifest["header"]!!.jsonObject["format"]!!.jsonPrimitive.content)
    }

    @Test fun customRemindersAreExportedWithCataloguedFields() {
        // schema14 新增的自定义提醒必须进入明文导出；此前只进入加密备份，导出缺这一段。
        assertTrue("custom_reminders 必须在导出范围内", "custom_reminders" in ExportScope.ALL_SECTIONS)
        val reminder = cn.yunji.journal.core.database.CustomReminderEntity(
            id="r1", pregnancyId="p1", title="合成提醒", note="备注",
            startDate="2026-11-12", localTime="08:00", repeat="DAILY", endDate="2026-12-01",
            enabled=true, createdAt=1, updatedAt=2, revision=3)
        val rows = ExportRowMapper.customReminders(listOf(reminder))
        assertEquals(1, rows.size)
        val values = rows[0].values
        assertEquals(ExportFieldCatalog.keys("custom_reminders").toSet(), values.keys.toSet())
        assertEquals("合成提醒", values["title"])
        assertEquals("DAILY", values["repeat"])
        assertEquals("true", values["enabled"])
        assertEquals("2026-12-01", values["endDate"])
    }

    @Test fun nullsAreDistinctFromEmptyTextAndOnlyCataloguedFieldsAppear() {
        val (entries, _) = write(sections = listOf(ExportSection("journal_entries", listOf(
            ExportRow(linkedMapOf("id" to "e1", "text" to "", "mood" to null, "unknownColumn" to "leak"))))))
        val rows = json.parseToJsonElement(String(entries.getValue("data/journal_entries.json"))).jsonArray
        assertEquals(1, rows.size)
        val first = rows[0].jsonObject
        assertEquals("", first["text"]!!.jsonPrimitive.content)
        assertTrue("absent value must be JSON null", first["mood"] is JsonNull)
        assertFalse("uncatalogued columns must never be exported", first.containsKey("unknownColumn"))
        // Every catalogued field is present so the shape is stable for readers.
        ExportFieldCatalog.keys("journal_entries").forEach { assertTrue(it, first.containsKey(it)) }
    }

    @Test fun sectionsAreWrittenAsSeparateArraysAndEmptySectionsStillProduceAFile() {
        val (entries, summary) = write(sections = listOf(
            ExportSection("pregnancies", listOf(row("id" to "p1"))),
            ExportSection("visits", emptyList())))
        assertEquals(1, (json.parseToJsonElement(String(entries.getValue("data/pregnancies.json"))) as JsonArray).size)
        assertEquals(0, (json.parseToJsonElement(String(entries.getValue("data/visits.json"))) as JsonArray).size)
        assertEquals(0, summary.countsBySection["visits"])
    }

    @Test fun mismatchedOriginalHashAbortsInsteadOfShippingWrongBytes() {
        val body = "actual".toByteArray()
        assertTrue(runCatching {
            write(sections = emptyList(),
                originals = listOf(ExportOriginal("o1", "a.png", "image/png", "0".repeat(64)) { body.inputStream() }))
        }.isFailure)
    }

    @Test fun checkpointCancellationStopsTheWrite() {
        val body = ByteArray(300_000) { 7 }
        var calls = 0
        val error = runCatching {
            write(sections = listOf(ExportSection("pregnancies", listOf(row("id" to "p1")))),
                originals = listOf(ExportOriginal("o1", "big.bin", "application/octet-stream", sha(body)) { body.inputStream() }),
                checkpoint = { if (++calls > 6) throw java.util.concurrent.CancellationException("cancelled") })
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
    }

    @Test fun writingRejectsSectionsOutsideTheChosenScope() {
        val error = runCatching {
            write(sections = listOf(ExportSection("visits", emptyList())),
                scope = ExportScope(sections = setOf("pregnancies")))
        }.exceptionOrNull()
        assertNotNull(error)
    }

    @Test fun scopeValidatesRangeAndFiltersByDateAndTrash() {
        assertTrue(runCatching { ExportScope(from = "2026-13-01") }.isFailure)
        assertTrue(runCatching { ExportScope(from = "2026-10-20", to = "2026-10-01") }.isFailure)
        assertTrue(runCatching { ExportScope(sections = emptySet()) }.isFailure)
        assertTrue(runCatching { ExportScope(sections = setOf("unknown")) }.isFailure)

        val scope = ExportScope(from = "2026-01-01", to = "2026-12-31")
        assertTrue(scope.includesDate("2026-06-15"))
        assertTrue(scope.includesDate(null))
        assertFalse(scope.includesDate("2025-12-31"))
        assertFalse(scope.includesDate("2027-01-01"))
        assertFalse(ExportScope().includesTrash(1L))
        assertTrue(ExportScope().includesTrash(null))
        assertTrue(ExportScope(includeTrash = true).includesTrash(1L))
    }

    @Test fun fieldCatalogCoversEveryBusinessSectionAndNamesWhatIsExcluded() {
        assertEquals(ExportScope.ALL_SECTIONS, ExportFieldCatalog.sections.keys)
        ExportFieldCatalog.sections.forEach { (section, fields) ->
            assertTrue("$section must describe at least one field", fields.isNotEmpty())
            assertEquals("duplicate keys in $section", fields.size, fields.map { it.key }.distinct().size)
            fields.forEach { assertTrue("${it.key} needs a Chinese label", it.label.any { c -> c.code > 127 }) }
        }
        val exclusions = ExportFieldCatalog.exclusions.joinToString()
        listOf("草稿", "密码", "恢复密钥", "本机设置").forEach { assertTrue(it, exclusions.contains(it)) }
        assertTrue(ExportFieldCatalog.keys("unknown").isEmpty())
    }

    @Test fun largeOriginalStreamsWithoutBufferingWholeFile() {
        val file = File(temporary.newFolder(), "large.bin")
        val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
        file.outputStream().use { out -> repeat(64) { out.write(chunk) } } // 4 MiB
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val expected = digest.digest().joinToString("") { "%02x".format(it) }
        val (entries, summary) = write(sections = emptyList(),
            originals = listOf(ExportOriginal("big", "large.bin", "application/octet-stream", expected) { file.inputStream() }))
        assertEquals(file.length(), entries.getValue("originals/big").size.toLong())
        assertEquals(file.length(), summary.originalBytes)
        assertEquals(expected, sha(entries.getValue("originals/big")))
    }

    private val kotlinx.serialization.json.JsonElement.jsonObject: JsonObject get() = this as JsonObject
    private val kotlinx.serialization.json.JsonElement.jsonArray: JsonArray get() = this as JsonArray
}
