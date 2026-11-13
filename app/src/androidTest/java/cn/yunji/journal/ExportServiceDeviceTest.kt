package cn.yunji.journal

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.export.ExportScope
import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.core.security.VaultMaterial
import cn.yunji.journal.data.DocumentImporter
import cn.yunji.journal.data.ExportRequest
import cn.yunji.journal.data.ExportService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Clock
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Real database to package: section filtering, JSON shape, empty-value distinction and the rule that an
 * unreadable original fails the whole export instead of shipping a shorter package.
 */
class ExportServiceDeviceTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var database: JournalDatabase
    private lateinit var vault: VaultMaterial
    private lateinit var importer: DocumentImporter

    @Before fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = root
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        root = File(base.cacheDir, "export-service-${UUID.randomUUID()}").apply { mkdirs() }
        vault = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
        database = JournalDatabase.open(context, File(root, "journal.db"), vault.key("database"))
        importer = DocumentImporter(context, database, vault, Clock.systemUTC())
    }

    @After fun tearDown() {
        runCatching { database.close() }
        runCatching { vault.master.fill(0) }
        runCatching { root.deleteRecursively() }
    }

    private fun destination(suffix: String) = TestDestinations.empty(context, "Yunji-Export-${UUID.randomUUID()}.$suffix")

    /**
     * @param attachmentId real attachments always carry UUID identifiers, so a fixture that expects the
     *   export to reach the original must use one; a plain name fails earlier for an unrelated reason.
     * @param objectId optional separate ciphertext object pointer.
     */
    private suspend fun seed(attachmentId: String = "a1", objectId: String? = null) {
        val pregnancy = PregnancyEntity("p1", "合成孕期档案", lmpDate = "2026-03-01", hospital = "合成医院",
            note = "", createdAt = 100, updatedAt = 200)
        val other = PregnancyEntity("p2", "另一个档案", createdAt = 300, updatedAt = 300)
        database.journalWrite {
            database.journalDao().putPregnancy(pregnancy)
            database.journalDao().putPregnancy(other)
            database.journalDao().putVisit(VisitEntity("v1", "p1", "2026-05-01", zoneId = "UTC", hospital = "合成医院",
                items = "合成检查", createdAt = 1, updatedAt = 1, advice = ""))
            database.journalDao().putVisit(VisitEntity("v2", "p1", "2026-09-01", zoneId = "UTC", createdAt = 1, updatedAt = 1))
            database.journalDao().insertJournalEntry(JournalEntryEntity("e1", "p1", "2026-05-02", null, "UTC", null,
                "", "平静", "合成正文", 1, 1))
            // An attachment whose ciphertext object does not exist: exporting originals must fail.
            database.journalDao().putAttachment(AttachmentEntity(attachmentId, "v1", "合成原件", "image/png",
                10, 42, "0".repeat(64), "1".repeat(64), null, null, null, null, null, 1, objectId = objectId))
            database.journalDao().putImportJob(ImportJobEntity(attachmentId, "v1", "", "合成原件", 1, state = "READY", phase = "DONE"))
        }
    }

    private fun service() = ExportService(context, database, importer, vault.id, Clock.systemUTC())

    private fun readEntries(uri: android.net.Uri): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            ZipInputStream(input.buffered(64 * 1024)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }
        }
        return entries
    }

    @Test fun packageContainsRealRowsAndDistinguishesNullFromEmptyText() = runBlocking {
        seed()
        val uri = destination("zip")
        try {
            val result = service().exportDataPackage(uri, ExportRequest(includeOriginals = false))
            assertTrue(result.message, result.success)
            val entries = readEntries(uri)
            val pregnancies = String(entries.getValue("data/pregnancies.json"))
            assertTrue("both pregnancies must be exported", pregnancies.contains("合成孕期档案") && pregnancies.contains("另一个档案"))
            // note is an empty string while lmpDate has a value: the two must stay distinguishable.
            assertTrue(pregnancies.contains("\"note\":\"\""))
            assertTrue(pregnancies.contains("\"lmpDate\":\"2026-03-01\""))

            val visits = String(entries.getValue("data/visits.json"))
            assertTrue(visits.contains("2026-05-01") && visits.contains("2026-09-01"))
            // A nullable column that was never set stays null, while a non-null text column keeps its empty string.
            assertTrue("an unset scheduledTime must be null", Regex("\"scheduledTime\":null").containsMatchIn(visits))
            assertTrue("an unset actualDate must be null", Regex("\"actualDate\":null").containsMatchIn(visits))
            assertTrue("a non-null advice field keeps its empty string", Regex("\"advice\":\"\"").containsMatchIn(visits))
            // The same distinction holds for the pregnancy profile.
            assertTrue("an unset confirmedEdd must be null", Regex("\"confirmedEdd\":null").containsMatchIn(pregnancies))

            val fields = String(entries.getValue("fields.json"))
            assertTrue(fields.contains("末次月经日期"))
            assertTrue(fields.contains("恢复密钥"))
            assertTrue("excluded items must be stated", fields.contains("excluded"))

            val manifest = String(entries.getValue("manifest.json"))
            assertTrue(manifest.contains("YJ-EXPORT-1"))
            assertFalse("no originals were requested", entries.keys.any { it.startsWith("originals/") })
        } finally { TestDestinations.delete(context, uri) }
    }

    @Test fun dateRangeAndSectionSelectionLimitWhatIsExported() = runBlocking {
        seed()
        val uri = destination("zip")
        try {
            val result = service().exportDataPackage(uri, ExportRequest(from = "2026-06-01", to = "2026-12-31",
                includeOriginals = false, sections = setOf("visits", "journal_entries")))
            assertTrue(result.message, result.success)
            val entries = readEntries(uri)
            assertFalse("unselected sections must not appear", entries.containsKey("data/pregnancies.json"))
            val visits = String(entries.getValue("data/visits.json"))
            assertTrue("the in-range visit must be present", visits.contains("2026-09-01"))
            assertFalse("the out-of-range visit must be absent", visits.contains("2026-05-01"))
            // The May entry is outside the range, so the file exists but is empty.
            assertEquals("[]", String(entries.getValue("data/journal_entries.json")).trim())
        } finally { TestDestinations.delete(context, uri) }
    }

    @Test fun trashIsExcludedByDefaultAndIncludedOnRequest() = runBlocking {
        seed()
        database.journalWrite {
            database.journalDao().putVisit(VisitEntity("v3", "p1", "2026-07-01", zoneId = "UTC",
                createdAt = 1, updatedAt = 2, deletedAt = 500))
        }
        val uri = destination("zip")
        try {
            assertTrue(service().exportDataPackage(uri, ExportRequest(includeOriginals = false)).success)
            assertFalse("a deleted visit must not be exported by default",
                String(readEntries(uri).getValue("data/visits.json")).contains("2026-07-01"))
            TestDestinations.delete(context, uri)

            val included = destination("zip")
            try {
                assertTrue(service().exportDataPackage(included, ExportRequest(includeOriginals = false, includeTrash = true)).success)
                assertTrue(String(readEntries(included).getValue("data/visits.json")).contains("2026-07-01"))
            } finally { TestDestinations.delete(context, included) }
        } finally { TestDestinations.delete(context, uri) }
    }

    @Test fun unreadableOriginalFailsTheWholeExportInsteadOfShippingAShorterPackage() = runBlocking {
        seed(attachmentId = UUID.randomUUID().toString())
        val uri = destination("zip")
        try {
            val result = service().exportDataPackage(uri, ExportRequest(includeOriginals = true))
            assertFalse("an unreadable original must fail the export, got: ${result.message} / ${result.detail}", result.success)
            assertTrue("the failure must be reported as a missing/incomplete original: ${result.message}",
                requireNotNull(result.message).contains("原件"))
        } finally { TestDestinations.delete(context, uri) }
    }

    @Test fun previewCountsMatchWhatThePackageWillContain() = runBlocking {
        seed()
        val preview = service().preview(ExportRequest(includeOriginals = false))
        assertEquals(2, preview["pregnancies"])
        assertEquals(2, preview["visits"])
        assertEquals(1, preview["journal_entries"])
        val limited = service().preview(ExportRequest(from = "2026-06-01", includeOriginals = false))
        assertEquals(1, limited["visits"])
    }
    @Test fun excludedUnreadableOriginalAndUnselectedSourceMetadataNeverEnterPackage()=runBlocking {
        seed() // Missing ciphertext belongs to the May visit, outside the requested interval.
        val request=ExportRequest(from="2026-06-01",to="2026-12-31",includeOriginals=true,sections=setOf("visits","attachments"))
        val uri=destination("zip")
        try {
            val result=service().exportDataPackage(uri,request)
            assertTrue(result.message,result.success)
            val entries=readEntries(uri)
            assertEquals("[]",String(entries.getValue("data/attachments.json")).trim())
            assertFalse(entries.keys.any {it.startsWith("originals/")})
            val sources=String(entries.getValue("sources.json"))
            assertTrue(sources.contains("v2"));assertFalse(sources.contains("v1"))
            assertFalse(sources.contains("合成孕期档案"));assertFalse(sources.contains("另一个档案"))
            assertEquals(0,service().preview(request)["attachments"])
        }finally {TestDestinations.delete(context, uri)}
    }

    @Test fun measurementDateUsesRecordedZoneAndValuesFollowSelectedMeasurements()=runBlocking {
        seed()
        database.journalWrite {
            val dao=database.journalDao()
            dao.insertMetricDefinition(MetricDefinitionEntity("def","p1","logical",1,"WEIGHT","合成体重","kg","value",1))
            val instant=java.time.Instant.parse("2026-06-01T00:30:00Z").toEpochMilli()
            dao.insertMeasurement(MeasurementEntity("inside","p1","def",instant,"Asia/Shanghai","",null,"MANUAL","",1,1))
            dao.insertMeasurement(MeasurementEntity("outside","p1","def",instant,"America/Los_Angeles","",null,"MANUAL","",1,1))
            dao.insertMeasurementValues(listOf(MeasurementValueEntity("inside","value","60"),MeasurementValueEntity("outside","value","61")))
        }
        val request=ExportRequest(from="2026-06-01",to="2026-06-01",includeOriginals=false,sections=setOf("measurements","measurement_values"))
        val uri=destination("zip")
        try {
            assertTrue(service().exportDataPackage(uri,request).success)
            val entries=readEntries(uri)
            for(section in request.sections) {
                val rows=String(entries.getValue("data/$section.json"))
                assertTrue(rows.contains("inside"));assertFalse(rows.contains("outside"))
            }
            assertEquals(1,service().preview(request)["measurements"])
            assertEquals("[]",String(entries.getValue("sources.json")).trim())
        }finally {TestDestinations.delete(context, uri)}
    }
    @Test fun attachmentOfDeletedPregnancyCannotLeakThroughNonDeletedChild()=runBlocking {
        seed()
        database.journalWrite {database.journalDao().putPregnancy(PregnancyEntity("p1","已删除合成档案",createdAt=1,updatedAt=2,deletedAt=3))}
        val uri=destination("zip")
        try {
            val result=service().exportDataPackage(uri,ExportRequest(includeOriginals=true,sections=setOf("attachments")))
            assertTrue(result.message,result.success)
            val entries=readEntries(uri)
            assertEquals("[]",String(entries.getValue("data/attachments.json")).trim())
            assertFalse(entries.keys.any {it.startsWith("originals/")})
        }finally {TestDestinations.delete(context, uri)}
    }

    @Test fun exportWaitsForAtomicRelatedRecordEdit() = runBlocking {
        seed()
        val uri = destination("zip")
        val editedFirstRow = CompletableDeferred<Unit>()
        val finishEdit = CompletableDeferred<Unit>()
        val edit = async(Dispatchers.IO) {
            database.journalWrite {
                val dao = database.journalDao()
                dao.putPregnancy(requireNotNull(dao.pregnancy("p1")).copy(title = "一致性新档案"))
                editedFirstRow.complete(Unit)
                finishEdit.await()
                dao.putVisit(requireNotNull(dao.visit("v1")).copy(hospital = "一致性新医院"))
            }
        }
        try {
            editedFirstRow.await()
            val exported = async(Dispatchers.IO) {
                service().exportDataPackage(uri, ExportRequest(includeOriginals = false))
            }
            finishEdit.complete(Unit)
            edit.await()
            val result = exported.await()
            assertTrue(result.message, result.success)
            val contents = readEntries(uri)
            assertTrue(String(contents.getValue("data/pregnancies.json"), Charsets.UTF_8).contains("一致性新档案"))
            assertTrue(String(contents.getValue("data/visits.json"), Charsets.UTF_8).contains("一致性新医院"))
        } finally {
            finishEdit.complete(Unit)
            edit.await()
            TestDestinations.delete(context, uri)
        }
    }

    private suspend fun realOriginal(deleted: Boolean = false): Pair<AttachmentEntity, ByteArray> {
        seed()
        val bytes = "synthetic selected original".toByteArray()
        val store = cn.yunji.journal.core.storage.EncryptedFileStore(importer.objectDirectory, vault)
        val stored = store.put(bytes.inputStream())
        val row = requireNotNull(database.journalDao().attachment("a1")).copy(objectId = stored.id,
            plainSize = stored.plainSize, cipherSize = stored.cipherSize, plainSha256 = stored.plainSha256,
            cipherSha256 = stored.cipherSha256, deletedAt = if (deleted) 5L else null)
        database.journalWrite { database.journalDao().putAttachment(row) }
        return row to bytes
    }

    @Test fun trashedOriginalMetadataIsExportedButItsPlaintextOriginalIsOmittedAndReported() = runBlocking {
        val (row, _) = realOriginal(deleted = true)
        val uri = destination("zip")
        try {
            val result = service().exportDataPackage(uri, ExportRequest(includeTrash = true))
            assertTrue(result.message, result.success)
            val entries = readEntries(uri)
            // The trashed attachment stays visible as a record, including its object reference.
            val attachments = String(entries.getValue("data/attachments.json"), Charsets.UTF_8)
            assertTrue("the trashed row must still be exported as metadata", attachments.contains(row.id))
            // Its plaintext is deliberately not exported: the importer refuses trashed originals, and the
            // export must report that instead of silently shipping a shorter package.
            assertFalse("a trashed original must not be decrypted into the package",
                entries.keys.any { it.startsWith("originals/") })
            assertTrue("the omission must be stated to the user", requireNotNull(result.detail).contains("回收站"))
            assertTrue(database.journalDao().objectPins().isEmpty())
            assertTrue(File(context.cacheDir, "export-work").listFiles().orEmpty().isEmpty())
        } finally { TestDestinations.delete(context, uri) }
    }

    @Test fun selectedLeasePreservesOldOriginalAcrossReplacementThenReclaimsIt() = runBlocking {
        val (row, bytes) = realOriginal()
        val store = cn.yunji.journal.core.storage.EncryptedFileStore(importer.objectDirectory, vault)
        val out = File(root, "pinned-output")
        importer.objectProtection.withSelected(capture = { row to listOf(requireNotNull(row.objectId)) }) { frozen ->
            val replacement = store.put("replacement".byteInputStream())
            database.journalWrite {
                database.journalDao().putAttachment(row.copy(objectId = replacement.id, revision = row.revision + 1))
                importer.objectProtection.retireLocked(listOf(requireNotNull(row.objectId)))
            }
            importer.objectProtection.cleanup()
            assertTrue(store.path(requireNotNull(row.objectId)).exists())
            importer.materialisePinnedOriginal(frozen, out)
            assertArrayEquals(bytes, out.readBytes())
        }
        assertTrue(database.journalDao().objectPins().isEmpty())
        assertFalse(store.path(requireNotNull(row.objectId)).exists())
    }

    @Test fun shutdownCancelsSelectedReaderAndWaitsForItsCleanup() = runBlocking {
        val (row, _) = realOriginal()
        val entered = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val reader = async(Dispatchers.IO) {
            importer.objectProtection.withSelected(capture = { row to listOf(requireNotNull(row.objectId)) }) {
                try { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
                finally { cleaned.complete(Unit) }
            }
        }
        kotlinx.coroutines.withTimeout(10_000) { entered.await() }
        kotlinx.coroutines.withTimeout(10_000) { importer.shutdown() }
        assertTrue(reader.isCompleted)
        assertTrue(cleaned.isCompleted)
        assertTrue(database.journalDao().objectPins().isEmpty())
    }
}
