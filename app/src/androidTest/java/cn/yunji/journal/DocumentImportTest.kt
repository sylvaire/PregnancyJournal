package cn.yunji.journal

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.*
import cn.yunji.journal.data.DocumentImporter
import cn.yunji.journal.data.JournalRepository
import cn.yunji.journal.data.MedicationForm
import cn.yunji.journal.domain.TimelineRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Uses private throwaway vaults, synthetic images/PDFs and no user documents. */
class DocumentImportTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun replayedFileHandoffDoesNotDuplicateOrReviveImport() = runBlocking {
        Fixture(context).use {f ->
            val visit=f.visit();val uri=f.source(image());val request=UUID.randomUUID().toString()
            f.importer.enqueueHandoff(visit,false,listOf(uri),request,schedule=false)
            val id=f.dao.pendingImports().single().id;f.importer.process(id)
            f.importer.enqueueHandoff(visit,false,listOf(uri),request,schedule=false)
            assertEquals(1,f.importer.jobs(visit).first().size)
            f.importer.trash(id,schedule=false)
            f.importer.enqueueHandoff(visit,false,listOf(uri),request,schedule=false)
            assertNotNull(f.dao.attachment(id)?.deletedAt)
            assertEquals("DELETED",f.dao.importJob(id)?.state)
        }
    }

    @Test fun lockingImporterClosesNativePdfAndDeletesPlaintextBeforeRejectingNewPreviews() = runBlocking {
        Fixture(context).use {f ->
            val visit=f.visit()
            f.importer.enqueue(visit,listOf(f.source(pdf(),"pdf")),schedule=false)
            val id=f.dao.pendingImports().single().id;f.importer.process(id)
            val preview=f.importer.openPreview(id)
            val renderer=cn.yunji.journal.feature.PdfSession(preview)
            renderer.render(0,100).recycle()
            f.importer.shutdown()
            assertFalse(preview.file.exists())
            assertTrue(runCatching {renderer.render(0,100)}.isFailure)
            assertTrue(runCatching {f.importer.openPreview(id)}.exceptionOrNull() is VaultLockedException)
            assertNotNull(f.dao.attachment(id));renderer.close();preview.close()
        }
    }

    private class Fixture(val base: Context) : AutoCloseable {
        val root = File(base.cacheDir, "import-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = root
            override fun getPackageName() = "${base.packageName}.fixture"
        }
        val key = VaultCrypto.randomKey()
        val material = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
        val db = JournalDatabase.open(base, File(root, "test.db"), key)
        val dao = db.journalDao()
        val importer = DocumentImporter(context, db, material, Clock.systemUTC())
        val sources = mutableListOf<File>()
        suspend fun visit(): String {
            val repo = JournalRepository(db, Clock.systemUTC())
            val id = repo.savePregnancy(null, "资料测试", null, null, "", "")
            return repo.saveVisit(null, id, LocalDate.now(), null, "", "", "")
        }
        fun source(bytes: ByteArray, extension: String = "png"): android.net.Uri {
            val file = File(base.cacheDir, "capture/${UUID.randomUUID()}.$extension")
            file.parentFile!!.mkdirs(); file.writeBytes(bytes); sources.add(file)
            return FileProvider.getUriForFile(base, "${base.packageName}.capture", file)
        }
        fun store() = EncryptedFileStore(File(root, "vault/objects"), material)
        override fun close() { db.close(); sources.forEach { it.delete() }; root.deleteRecursively() }
    }

    private fun image(width: Int = 80, height: Int = 60, color: Int = android.graphics.Color.GREEN): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try { java.io.ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }

    @androidx.test.filters.LargeTest
    @Test fun syntheticImportTiming() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(arguments.getString("measureImports") == "true")
        val label = arguments.getString("measurementLabel") ?: "sample"
        val imageWidth = arguments.getString("imageWidth")?.toInt() ?: 2048
        val imageHeight = arguments.getString("imageHeight")?.toInt() ?: 3072
        android.util.Log.i("YunjiPerf", "image-generation")
        val bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(19)
        val row = IntArray(imageWidth)
        for (y in 0 until imageHeight) {
            for (x in row.indices) row[x] = random.nextInt() or (0xff shl 24)
            bitmap.setPixels(row, 0, imageWidth, 0, y, imageWidth, 1)
        }
        val bytes = try { java.io.ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() } }
        finally { bitmap.recycle() }
        val results = org.json.JSONArray()
        val output = File(context.getExternalFilesDir(null), "verification/import-timing-$label.json").apply { parentFile!!.mkdirs() }
        android.util.Log.i("YunjiPerf", "fixture-open")
        Fixture(context).use { f ->
            val visit = f.visit()
            android.util.Log.i("YunjiPerf", "fixture-ready")
            val legacy = cn.yunji.journal.data.LegacyDocumentImporter(f.context, f.db, f.material, Clock.systemUTC())
            for (i in 1..5) {
                android.util.Log.i("YunjiPerf", "single-$i-enqueue")
                f.importer.enqueue(visit, listOf(f.source(bytes, "jpg")), schedule = false)
                val job = f.dao.pendingImports().single()
                val item = JSONObject().put("iteration", i).put("bytes", bytes.size).put("width", imageWidth).put("height", imageHeight)
                val start = System.nanoTime()
                android.util.Log.i("YunjiPerf", "single-$i-process")
                if (label.startsWith("before")) legacy.process(job.id) { stage, ms -> item.put(stage, ms); android.util.Log.i("YunjiPerf", "$stage=$ms") }
                else f.importer.process(job.id) { stage, ms -> item.put(stage, ms); android.util.Log.i("YunjiPerf", "$stage=$ms") }
                item.put("total", (System.nanoTime() - start) / 1_000_000)
                assertEquals("READY", f.dao.importJob(job.id)?.state)
                results.put(item)
                output.writeText(results.toString(2))
            }
            for (i in 1..5) {
                f.importer.enqueue(visit, List(2) { f.source(bytes, "jpg") }, schedule = false)
                val jobs = f.dao.pendingImports()
                val start = System.nanoTime()
                coroutineScope { jobs.map { job -> async { if (label.startsWith("before")) legacy.process(job.id) else f.importer.process(job.id) } }.awaitAll() }
                jobs.forEach { assertEquals("READY", f.dao.importJob(it.id)?.state) }
                results.put(JSONObject().put("case", "batch-2").put("iteration", i).put("bytesPerFile", bytes.size)
                    .put("total", (System.nanoTime() - start) / 1_000_000))
                output.writeText(results.toString(2))
            }
        }
    }
    private fun pdf(): ByteArray { val doc = PdfDocument(); try {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(200, 300, 1).create())
        page.canvas.drawColor(android.graphics.Color.WHITE)
        doc.finishPage(page)
        return java.io.ByteArrayOutputStream().use { doc.writeTo(it); it.toByteArray() }
    } finally { doc.close() } }

    @Test fun imageAndPdfAreAuthenticatedAndIndependentOfSource() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            val original = image()
            f.importer.enqueue(visit, listOf(f.source(original), f.source(pdf(), "pdf")), schedule = false)
            val jobs = f.dao.pendingImports()
            jobs.forEach { f.importer.process(it.id) }
            assertEquals(listOf("READY", "READY"), f.dao.importJobs(visit).first().map { it.state })
            val attachments = f.dao.attachments(visit).first()
            assertEquals(setOf("image/png", "application/pdf"), attachments.map { it.mime }.toSet())
            assertTrue(attachments.all { it.thumbnailId != null })
            f.sources.forEach { it.delete() }
            val attachment = attachments.first { it.mime == "image/png" }
            val dest = File(f.root, "decrypted")
            f.store().decryptTo(StoredObject(attachment.id, attachment.plainSize, attachment.cipherSize, attachment.plainSha256, attachment.cipherSha256), dest)
            assertArrayEquals(original, dest.readBytes())
            assertFalse(f.store().path(attachment.id).readBytes().contentEquals(original))
            assertTrue(f.dao.importJobs(visit).first().all { it.sourceUri.isEmpty() })
            assertTrue(File(f.root, "import-preview").listFiles().orEmpty().isEmpty())
            val sequence = f.dao.currentSequence()
            jobs.forEach { f.importer.process(it.id) }
            assertEquals(sequence, f.dao.currentSequence())
            assertEquals(2, f.dao.attachments(visit).first().size)
        }
    }

    @Test fun partialFailureCanBeReselectedWithoutDuplicatingSuccessfulFiles() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            f.importer.enqueue(visit, listOf(f.source(image()), f.source("not a pdf".toByteArray(), "pdf")), schedule = false)
            f.dao.pendingImports().forEach { f.importer.process(it.id) }
            val jobs = f.dao.importJobs(visit).first()
            assertEquals(1, jobs.count { it.state == "READY" })
            val failed = jobs.single { it.state == "FAILED" }
            assertNull(f.dao.attachment(failed.id))
            assertFalse(f.store().path(failed.id).exists())
            f.importer.retry(failed.id, f.source(image()), schedule = false)
            f.importer.process(failed.id)
            assertEquals(2, f.dao.attachments(visit).first().size)
            assertTrue(f.dao.importJobs(visit).first().all { it.state == "READY" })
        }
    }

    @Test fun interruptedObjectAndMissingSourceRemainRecoverable() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            f.importer.enqueue(visit, listOf(f.source(image())), schedule = false)
            val job = f.dao.pendingImports().single()
            f.dao.putImportJob(job.copy(state = "IMPORTING"))
            f.store().path(job.id).writeText("uncommitted object")
            File(f.store().path(job.id).parentFile, "${job.id}.partial").writeText("partial")
            f.importer.process(job.id)
            assertEquals("READY", f.dao.importJob(job.id)?.state)
            assertNotNull(f.dao.attachment(job.id))
            f.importer.enqueue(visit, listOf(f.source(image())), schedule = false)
            val missing = f.dao.pendingImports().single()
            f.sources.last().delete()
            f.importer.process(missing.id)
            assertEquals("FAILED", f.dao.importJob(missing.id)?.state)
            assertNull(f.dao.attachment(missing.id))
            assertTrue(File(f.root, "import-preview").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun batchLimitAndOversizedFileDoNotCommitAttachments() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            val uri = f.source(image())
            assertTrue(runCatching { f.importer.enqueue(visit, List(21) { uri }, schedule = false) }.isFailure)
            assertTrue(f.dao.pendingImports().isEmpty())
            java.io.RandomAccessFile(f.sources.single(), "rw").use { it.setLength(EncryptedFileStore.MAX_FILE_BYTES + 1) }
            f.importer.enqueue(visit, listOf(uri), schedule = false)
            val job = f.dao.pendingImports().single()
            f.importer.process(job.id)
            assertEquals("FAILED", f.dao.importJob(job.id)?.state)
            assertTrue(f.dao.importJob(job.id)?.error.orEmpty().contains("100 MiB"))
            assertNull(f.dao.attachment(job.id))
            assertFalse(f.store().path(job.id).exists())
        }
    }

    @Test fun previewTrashRestoreAndPermanentDeleteKeepSourceUntouched() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            f.importer.enqueue(visit, listOf(f.source(image())), schedule = false)
            val id = f.dao.pendingImports().single().id
            f.importer.process(id)
            val attachment = requireNotNull(f.dao.attachment(id))
            val preview = f.importer.openPreview(id)
            assertTrue(preview.file.exists())
            preview.close()
            assertFalse(preview.file.exists())
            assertTrue(f.importer.trash(id, schedule = false))
            assertNotNull(f.dao.attachment(id)?.deletedAt)
            assertTrue(runCatching { f.importer.openPreview(id) }.isFailure)
            f.importer.process(id)
            assertEquals("DELETED", f.dao.importJob(id)?.state)
            f.importer.restore(id)
            assertNull(f.dao.attachment(id)?.deletedAt)
            f.importer.openPreview(id).close()
            f.importer.trash(id, schedule = false)
            f.importer.purge(id, schedule = false)
            assertTrue(runCatching { f.importer.restore(id) }.isFailure)
            f.importer.process(id)
            f.importer.process(id)
            assertNull(f.dao.attachment(id))
            assertEquals("PURGED", f.dao.importJob(id)?.state)
            assertFalse(f.store().path(id).exists())
            assertFalse(f.store().path(attachment.thumbnailId!!).exists())
            assertTrue(f.sources.single().exists())
        }
    }

    @Test fun lateImportCannotReviveCancelledOrDeletedItem() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            f.importer.enqueue(visit, listOf(f.source(image())), schedule = false)
            val cancelled = f.dao.pendingImports().single().id
            f.importer.process(cancelled) { stage, _ ->
                if (stage == "encrypt") runBlocking { f.importer.trash(cancelled, schedule = false) }
            }
            assertNull(f.dao.attachment(cancelled))
            f.importer.process(cancelled)
            assertEquals("REMOVED", f.dao.importJob(cancelled)?.state)
            assertFalse(f.store().path(cancelled).exists())
            f.importer.enqueue(visit, listOf(f.source(image())), schedule = false)
            val deleted = f.dao.pendingImports().single().id
            f.importer.process(deleted) { stage, _ ->
                if (stage == "commit") runBlocking { f.importer.trash(deleted, schedule = false) }
            }
            assertEquals("DELETED", f.dao.importJob(deleted)?.state)
            assertNotNull(f.dao.attachment(deleted)?.deletedAt)
            f.importer.process(deleted)
            assertEquals("DELETED", f.dao.importJob(deleted)?.state)
            f.importer.restore(deleted)
            assertEquals("READY", f.dao.importJob(deleted)?.state)
        }
    }

    @Test fun tamperedOriginalIsNeverExposedForPreview() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            f.importer.enqueue(visit, listOf(f.source(image())), schedule = false)
            val id = f.dao.pendingImports().single().id
            f.importer.process(id)
            val file = f.store().path(id)
            val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            assertTrue(runCatching { f.importer.openPreview(id) }.isFailure)
            assertTrue(File(f.root, "document-viewers").listFiles().orEmpty().isEmpty())
            assertNotNull(f.dao.attachment(id))
        }
    }

    @Test fun cameraOrientationAndRegionDecodingStayBounded() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            val bytes = try { java.io.ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() } }
            finally { bitmap.recycle() }
            val uri = f.source(bytes, "jpg")
            // API 26 的平台 ExifInterface 连真实 JPEG 也拒绝写入属性；androidx 实现覆盖 24+。
            androidx.exifinterface.media.ExifInterface(f.sources.last().path).apply {
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, "6")
                saveAttributes()
            }
            f.importer.enqueue(visit, listOf(uri), schedule = false)
            val id = f.dao.pendingImports().single().id
            f.importer.process(id)
            val preview = f.importer.openPreview(id)
            val region = cn.yunji.journal.feature.ImageRegion(preview)
            try {
                assertEquals(900, region.width)
                assertEquals(600, region.height)
                val tile = region.tile(androidx.compose.ui.unit.IntSize(1080, 1800), 8f, androidx.compose.ui.geometry.Offset.Zero)
                assertTrue(tile.bitmap.width.toLong() * tile.bitmap.height <= 4_000_000)
                assertTrue(tile.rect.width() < 600)
                tile.bitmap.recycle()
            } finally { region.close() }
            assertFalse(preview.file.exists())
        }
    }

    @Test fun foregroundQueueStartsWithoutWaitingForWorkerDispatch() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            f.importer.enqueue(visit, listOf(f.source(image())))
            val jobs = kotlinx.coroutines.withTimeout(10_000) {
                f.importer.jobs(visit).first { list -> list.size == 1 && list[0].state == "READY" && list[0].phase == "DONE" }
            }
            assertNotNull(f.dao.attachment(jobs.single().id))
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("import-${jobs.single().id}").result.get()
            Unit
        }
    }

    @Test(timeout = 150_000) fun schemaOneThroughFourteenUpgradeKeepsExistingRecordsAndDrafts() = runBlocking {
        for (schemaVersion in 1..14) {
        val root = File(context.cacheDir, "migration-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(root, "v1.db")
        val key = VaultCrypto.randomKey()
        System.loadLibrary("sqlcipher")
        try {
            val schemaText = InstrumentationRegistry.getInstrumentation().context.assets
                .open("cn.yunji.journal.core.database.JournalDatabase/$schemaVersion.json").bufferedReader().use { it.readText() }
            val schema = JSONObject(schemaText).getJSONObject("database")
            val connection = SQLCipherDriver(key.copyOf(), null, null).open(file.path)
            try {
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val name = entity.getString("tableName")
                    connection.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
                    val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for (j in 0 until indices.length()) connection.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) connection.execSQL(setup.getString(i))
                connection.execSQL("INSERT INTO pregnancies (id,title,lmpDate,confirmedEdd,hospital,note,status,createdAt,updatedAt,revision,deletedAt) VALUES ('old','保留的档案',NULL,NULL,'','','ONGOING',1,1,1,NULL)")
                connection.execSQL("INSERT INTO drafts (feature,draftId,payload,updatedAt) VALUES ('visit','new:old','encrypted-draft',1)")
                if (schemaVersion >= 2) {
                    connection.execSQL("INSERT INTO prenatal_visits (id,pregnancyId,scheduledDate,zoneId,hospital,items,preparation,advice,status,createdAt,updatedAt,revision) VALUES ('old-visit','old','2026-10-18','Asia/Shanghai','','','','','SCHEDULED',1,1,1)")
                    connection.execSQL("INSERT INTO import_jobs (id,visitId,sourceUri,name,createdAt,state) VALUES ('old-file','old-visit','','旧版报告',1,'READY')")
                    connection.execSQL("INSERT INTO attachments (id,visitId,name,mime,plainSize,cipherSize,plainSha256,cipherSha256,createdAt) VALUES ('old-file','old-visit','旧版报告','image/png',10,42,'old-plain','old-cipher',1)")
                    if (schemaVersion >= 4) connection.execSQL("UPDATE attachments SET category='LAB', revision=7 WHERE id='old-file'")
                    if (schemaVersion >= 5) {
                        connection.execSQL("INSERT INTO deletion_batches (id,kind,rootId,deletedAt,state) VALUES ('old-batch','VISIT','old-visit',1,'DELETED')")
                        connection.execSQL("UPDATE prenatal_visits SET deletedAt=1,deletionBatchId='old-batch' WHERE id='old-visit'")
                    }
                    if (schemaVersion >= 6) {
                        connection.execSQL("INSERT INTO medications (id,pregnancyId,name,specification,dosageForm,createdAt,revision) VALUES ('old-med','old','保留药品','','',1,1)")
                        if (schemaVersion >= 10) connection.execSQL("INSERT INTO medication_plans (id,pregnancyId,medicationId,logicalPlanId,version,dose,unit,frequencyType,times,weekdays,zoneId,startDate,status,instructions,source,effectiveAt,revision,medicationName,medicationSpecification,medicationDosageForm) VALUES ('old-plan','old','old-med','old-plan',1,'0.125','片','DAILY_TIMES','08:00','','UTC','2026-10-18','PAUSED','保留原文','合成来源',2,1,'保留药品','原规格','片剂')")
                        else connection.execSQL("INSERT INTO medication_plans (id,pregnancyId,medicationId,logicalPlanId,version,dose,unit,frequencyType,times,weekdays,zoneId,startDate,status,instructions,source,effectiveAt,revision) VALUES ('old-plan','old','old-med','old-plan',1,'0.125','片','DAILY_TIMES','08:00','','UTC','2026-10-18','PAUSED','保留原文','合成来源',2,1)")
                        if (schemaVersion >= 7) {
                            connection.execSQL("INSERT INTO medication_occurrences (id,pregnancyId,planVersionId,plannedDate,localTime,ordinal,scheduledAt,zoneId,state,createdAt,revision) VALUES ('old-point','old','old-plan','2026-10-18','08:00',0,123,'UTC','ACTIVE',1,1)")
                            connection.execSQL("INSERT INTO medication_intakes (id,pregnancyId,planVersionId,occurrenceId,sourceOccurrenceId,status,actualAt,eventDate,zoneId,dose,unit,note,recordedAt,updatedAt,revision) VALUES ('old-intake','old','old-plan','old-point','old-point','TAKEN',456,'2026-10-18','UTC','0.25','片','原实际记录',2,2,1)")
                        }
                        if(schemaVersion>=8) {
                            connection.execSQL("INSERT INTO metric_definitions (id,pregnancyId,logicalId,version,kind,name,unit,valueKeys,createdAt,revision) VALUES ('old-def','old','old-def',1,'WEIGHT','体重','kg','value',1,1)")
                            connection.execSQL("INSERT INTO measurements (id,pregnancyId,definitionId,measuredAt,zoneId,scenario,source,note,recordedAt,updatedAt,revision) VALUES ('old-measurement','old','old-def',123456,'UTC','NONE','合成来源','原始备注',1,1,1)")
                            connection.execSQL("INSERT INTO measurement_values (measurementId,valueKey,decimalValue) VALUES ('old-measurement','value','60.125')")
                        }
                    }
                }
                connection.execSQL("PRAGMA user_version = $schemaVersion")
            } finally { connection.close() }
            val db = JournalDatabase.open(context, file, key)
            try {
                assertEquals("保留的档案", db.journalDao().pregnancy("old")?.title)
                assertEquals("encrypted-draft", db.journalDao().draft("visit", "new:old")?.payload)
                assertTrue(db.journalDao().allCustomReminders().isEmpty())
                assertTrue(db.journalDao().pendingImports().isEmpty())
                if (schemaVersion >= 2) {
                    val attachment = requireNotNull(db.journalDao().attachment("old-file"))
                    assertEquals("旧版报告", attachment.name)
                    assertNull(attachment.deletedAt)
                    assertEquals(if (schemaVersion >= 4) 7L else 1L, attachment.revision)
                    assertEquals(if (schemaVersion >= 4) "LAB" else "OTHER", attachment.category)
                    assertNull(attachment.deletionBatchId)
                    assertTrue(db.journalDao().objectPins().isEmpty())
                    assertTrue(db.journalDao().retiredObjects().isEmpty())
                    assertNull(attachment.medicationId)
                    assertNull(attachment.objectId)
                    assertNull(db.journalDao().importJob("old-file")?.medicationId)
                    assertNull(attachment.documentDate);assertEquals("",attachment.hospital);assertEquals("",attachment.note)
                    assertEquals("WAITING", db.journalDao().importJob("old-file")?.phase)
                    if (schemaVersion >= 5) {
                        assertEquals("DELETED", db.journalDao().deletionBatch("old-batch")?.state)
                        assertEquals("old-batch", db.journalDao().visit("old-visit")?.deletionBatchId)
                        assertNull(db.journalDao().visit("old-visit")?.actualTime)
                        assertNull(db.journalDao().visit("old-visit")?.actualHospital)
                        assertNull(db.journalDao().visit("old-visit")?.actualResult)
                        assertNull(db.journalDao().visit("old-visit")?.coverAttachmentId)
                        assertNull(db.journalDao().visit("old-visit")?.reminderDate)
                        assertNull(db.journalDao().visit("old-visit")?.reminderTime)
                        assertEquals(0, db.journalDao().trashBatches().first().single().planCount)
                    }
                    if (schemaVersion >= 6) {
                        assertEquals("0.125", db.journalDao().medicationPlan("old-plan")?.dose)
                        assertEquals("PAUSED", db.journalDao().medicationPlan("old-plan")?.status)
                        assertEquals("保留药品", db.journalDao().medication("old-med")?.name)
                        assertEquals("保留药品", db.journalDao().medicationPlan("old-plan")?.medicationName)
                        assertEquals(if (schemaVersion >= 10) "原规格" else "", db.journalDao().medicationPlan("old-plan")?.medicationSpecification)
                        assertEquals(if (schemaVersion >= 10) "片剂" else "", db.journalDao().medicationPlan("old-plan")?.medicationDosageForm)
                        if (schemaVersion >= 7) {
                            assertEquals("0.25",db.journalDao().intake("old-intake")?.dose)
                            assertEquals("old-point",db.journalDao().intake("old-intake")?.sourceOccurrenceId)
                        } else assertTrue(db.journalDao().allIntakes().isEmpty())
                        if(schemaVersion>=8) {
                            assertEquals("60.125",db.journalDao().valuesForMeasurement("old-measurement").single().decimalValue)
                            assertEquals("原始备注",db.journalDao().measurement("old-measurement")?.note)
                        } else assertTrue(db.journalDao().allMeasurements().isEmpty())
                        assertTrue(db.journalDao().allJournalEntries().isEmpty())
                    }
                }
            } finally { db.close() }
        } finally { root.deleteRecursively() }
        }
    }

    @Test fun pdfPagesAreBoundedAndPreviewCloseRemovesPlaintext() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            val bytes = java.io.ByteArrayOutputStream().use { output ->
                val pdf = PdfDocument()
                try {
                    repeat(3) { index ->
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 900, index + 1).create())
                        page.canvas.drawColor(if (index == 0) android.graphics.Color.RED else android.graphics.Color.BLUE)
                        pdf.finishPage(page)
                    }
                    pdf.writeTo(output)
                } finally { pdf.close() }
                output.toByteArray()
            }
            f.importer.enqueue(visit, listOf(f.source(bytes, "pdf")), schedule = false)
            val id = f.dao.pendingImports().single().id
            f.importer.process(id)
            val preview = f.importer.openPreview(id)
            val plain = preview.file
            val session = cn.yunji.journal.feature.PdfSession(preview)
            try {
                assertEquals(3, session.pageCount)
                val first = session.render(0, 9000)
                val second = session.render(1, 9000)
                assertTrue(first.width.toLong() * first.height <= 4_000_000)
                assertFalse(first.sameAs(second))
                first.recycle(); second.recycle()
                assertTrue(plain.exists())
            } finally { session.close() }
            assertFalse(plain.exists())
            assertNotNull(f.dao.attachment(id))
        }
    }

    @Test fun malformedPdfKeepsOriginalAndDoesNotLeaveViewerPlaintext() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            f.importer.enqueue(visit, listOf(f.source("%PDF-1.7\ntruncated synthetic document".toByteArray(), "pdf")), schedule = false)
            val id = f.dao.pendingImports().single().id
            f.importer.process(id)
            assertEquals("READY", f.dao.importJob(id)?.state)
            val preview = f.importer.openPreview(id)
            try {
                try { cn.yunji.journal.feature.PdfSession(preview).use { }; fail("Broken PDF accepted") }
                catch (_: java.io.IOException) { }
            } finally { preview.close() }
            assertTrue(f.store().path(id).exists())
            assertNotNull(f.dao.attachment(id))
            assertTrue(File(f.root, "document-viewers").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun metadataEditsRemainEncryptedAndSurviveRecycleWithoutChangingOriginal() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            f.importer.enqueue(visit, listOf(f.source(image())), schedule = false)
            val id = f.dao.pendingImports().single().id
            f.importer.process(id)
            val original = requireNotNull(f.dao.attachment(id))
            f.importer.updateMetadata(id, "  超声复查 Report  ", "ULTRASOUND")
            val edited = requireNotNull(f.dao.attachment(id))
            assertEquals("超声复查 Report", edited.name)
            assertEquals("ULTRASOUND", edited.category)
            assertEquals(original.plainSha256, edited.plainSha256)
            assertEquals(original.revision + 1, edited.revision)
            assertEquals(edited.name, f.dao.importJob(id)?.name)
            assertEquals(1, cn.yunji.journal.data.queryDocuments(f.dao.importJobs(visit).first(), listOf(edited), "ｒｅｐｏｒｔ", "ULTRASOUND", 0).size)
            assertTrue(cn.yunji.journal.data.queryDocuments(f.dao.importJobs(visit).first(), listOf(edited), "", "LAB", 0).isEmpty())
            try { f.importer.updateMetadata(id, "../invalid", "LAB"); fail("Invalid name accepted") } catch (_: IllegalArgumentException) { }
            f.importer.trash(id, schedule = false)
            try { f.importer.updateMetadata(id, "should fail", "LAB"); fail("Deleted metadata edited") } catch (_: IllegalArgumentException) { }
            f.importer.restore(id)
            assertEquals("ULTRASOUND", f.dao.attachment(id)?.category)
            assertEquals("超声复查 Report", f.dao.attachment(id)?.name)
        }
    }

    @Test fun medicationPhotosAreEncryptedOwnedByDrugAndFollowTrashLifecycle() = runBlocking {
        Fixture(context).use { f ->
            val repo = JournalRepository(f.db, Clock.systemUTC())
            val pregnancy = repo.savePregnancy(null, "合成药品照片档案", null, null, "", "")
            val planId = repo.medications.save(MedicationForm(pregnancyId = pregnancy, name = "合成包装药品", specification = "10片", dose = "0.5", unit = "片", times = "08:00", startDate = LocalDate.now().toString()))
            val drugId = requireNotNull(f.dao.medicationPlan(planId)).medicationId
            val bytes = image()
            f.importer.enqueueMedication(drugId, listOf(f.source(bytes, "png")), schedule = false)
            val job = f.dao.allImportJobs().single { it.medicationId == drugId }
            assertNull(job.visitId)
            f.importer.process(job.id)
            val attachment = requireNotNull(f.dao.attachment(job.id))
            assertEquals(drugId, attachment.medicationId); assertNull(attachment.visitId)
            assertEquals("PACKAGE", attachment.category); assertEquals(bytes.size.toLong(), attachment.plainSize)
            assertEquals("READY", f.dao.importJob(job.id)?.state); assertEquals("DONE", f.dao.importJob(job.id)?.phase)
            val cipher = f.store().path(job.id).readBytes()
            assertFalse(cipher.take(8).toByteArray().contentEquals(bytes.take(8).toByteArray()))
            assertArrayEquals(byteArrayOf(0x50, 0x4a, 0x46, 0x01), cipher.take(4).toByteArray())
            val sourceFile = f.sources.last(); assertTrue(sourceFile.exists()); assertArrayEquals(bytes, sourceFile.readBytes())
            f.importer.openPreview(job.id).use { preview -> assertEquals(job.id, preview.attachment.id) }
            assertTrue(File(f.root, "document-viewers").listFiles().orEmpty().isEmpty())
            val events = TimelineRules.build(requireNotNull(f.dao.timeline(pregnancy).first()), ZoneOffset.UTC)
            assertTrue(events.any { it.type == "DOCUMENT" && it.id == job.id && it.summary.contains("药品包装") })
            try { f.importer.updateMetadata(job.id, "药盒正面", "LAB"); fail("Package photo category changed") } catch (_: IllegalArgumentException) { }
            f.importer.updateMetadata(job.id, "  药盒正面  ", "PACKAGE", note = "合成药盒信息")
            assertEquals("药盒正面", f.dao.attachment(job.id)?.name); assertEquals("合成药盒信息", f.dao.attachment(job.id)?.note)
            f.importer.enqueueMedication(drugId, listOf(f.source(pdf(), "pdf")), schedule = false)
            val pdfJob = f.dao.allImportJobs().single { it.medicationId == drugId && it.id != job.id }
            f.importer.process(pdfJob.id)
            assertEquals("FAILED", f.dao.importJob(pdfJob.id)?.state)
            assertTrue(f.dao.importJob(pdfJob.id)?.error.orEmpty().contains("包装照片仅支持图片"))
            assertNull(f.dao.attachment(pdfJob.id))
            val batch = f.importer.trashRepository.delete("MEDICATION", drugId, schedule = false)
            assertNotNull(f.dao.attachment(job.id)?.deletedAt)
            val row = f.dao.trashBatches().first().single { it.id == batch }
            assertEquals(1, row.documentCount); assertNotNull(f.importer.trashRepository.purgeReason(row))
            try { f.importer.openPreview(job.id); fail("Deleted package photo opened") } catch (_: IllegalArgumentException) { }
            f.importer.trashRepository.restore(batch)
            assertNull(f.dao.attachment(job.id)?.deletedAt); assertEquals("READY", f.dao.importJob(job.id)?.state)
            f.importer.openPreview(job.id).use { preview -> assertEquals("药盒正面", preview.attachment.name) }
            val pregnancyBatch = f.importer.trashRepository.delete("PREGNANCY", pregnancy, schedule = false)
            f.importer.trashRepository.purge(pregnancyBatch, schedule = false)
            f.importer.trashRepository.resume()
            assertNull(f.dao.attachment(job.id)); assertFalse(f.store().path(job.id).exists())
        }
    }

    @Test fun coverAndRelinkStayWithinPregnancyAndClearStaleOwners() = runBlocking {
        Fixture(context).use { f ->
            val repo = JournalRepository(f.db, Clock.systemUTC())
            val pregnancy = repo.savePregnancy(null, "合成关联档案", null, null, "", "")
            val first = repo.saveVisit(null, pregnancy, LocalDate.now(), null, "", "第一次产检", "")
            val second = repo.saveVisit(null, pregnancy, LocalDate.now().plusDays(1), null, "", "第二次产检", "")
            val otherPregnancy = repo.savePregnancy(null, "另一孕期", null, null, "", "")
            val otherVisit = repo.saveVisit(null, otherPregnancy, LocalDate.now(), null, "", "外部产检", "")
            f.importer.enqueue(first, List(2) { f.source(image()) }, schedule = false)
            val ids = f.dao.pendingImports().map { it.id }
            ids.forEach { f.importer.process(it) }
            val cover = ids[0]; val other = ids[1]
            f.importer.setCover(cover, first)
            assertEquals(cover, f.dao.visit(first)?.coverAttachmentId)
            f.importer.relink(cover, second)
            assertEquals(second, f.dao.attachment(cover)?.visitId)
            assertEquals(second, f.dao.importJob(cover)?.visitId)
            assertNull(f.dao.visit(first)?.coverAttachmentId)
            try { f.importer.relink(cover, otherVisit); fail("Cross-pregnancy relink accepted") } catch (_: IllegalArgumentException) { }
            try { f.importer.setCover(cover, first); fail("Cover accepted from another visit") } catch (_: IllegalArgumentException) { }
            f.importer.setCover(other, first)
            f.importer.trash(other, schedule = false)
            assertNull(f.dao.visit(first)?.coverAttachmentId)
            f.importer.trashRepository.delete("VISIT", second, schedule = false)
            assertNotNull(f.dao.visit(second)?.deletedAt)
            assertEquals(second, f.dao.attachment(cover)?.visitId)
            assertNull(f.dao.attachment(cover)?.deletedAt)
            val targets = f.importer.trashRepository.relinkTargets(requireNotNull(f.dao.attachment(cover)))
            assertTrue(targets.any { it.id == first }); assertTrue(targets.none { it.id == second })
            f.importer.relink(cover, first)
            assertEquals(first, f.dao.attachment(cover)?.visitId)
            assertEquals(first, f.dao.importJob(cover)?.visitId)
        }
    }

    @Test fun corruptedOriginalCanBeReplacedAndExportedWithoutLeakingPlaintext() = runBlocking {
        Fixture(context).use { f ->
            val original = image()
            val replacement = image(90, 70, android.graphics.Color.BLUE)
            val visit = f.visit()
            f.importer.enqueue(visit, listOf(f.source(original)), schedule = false)
            val id = f.dao.pendingImports().single().id
            f.importer.process(id)
            val before = requireNotNull(f.dao.attachment(id))
            assertNull(before.objectId)
            java.io.ByteArrayOutputStream().use { out -> f.importer.exportOriginalTo(id, out); assertArrayEquals(original, out.toByteArray()) }
            f.store().path(id).writeBytes(byteArrayOf(1, 2, 3, 4))
            try { f.importer.openPreview(id); fail("Corrupted original opened") } catch (_: Exception) { }
            f.importer.replaceOriginal(id, f.source(replacement))
            val after = requireNotNull(f.dao.attachment(id))
            assertNotNull(after.objectId); assertNotEquals(id, after.objectId)
            assertNotNull(before.thumbnailId); assertNotNull(after.thumbnailId)
            assertNotEquals(before.thumbnailId, after.thumbnailId)
            assertFalse(f.store().path(requireNotNull(before.thumbnailId)).exists())
            assertEquals(replacement.size.toLong(), after.plainSize)
            assertEquals("image/png", after.mime)
            assertEquals(before.name, after.name); assertEquals(before.category, after.category)
            assertFalse(f.store().path(id).exists())
            assertTrue(f.store().path(requireNotNull(after.objectId)).exists())
            f.importer.process(id)
            assertEquals("READY", f.dao.importJob(id)?.state); assertEquals("DONE", f.dao.importJob(id)?.phase)
            assertNotNull(f.dao.attachment(id)?.thumbnailId)
            f.importer.openPreview(id).use { preview -> assertEquals(id, preview.attachment.id) }
            java.io.ByteArrayOutputStream().use { out -> f.importer.exportOriginalTo(id, out); assertArrayEquals(replacement, out.toByteArray()) }
            assertTrue(File(f.root, "document-viewers").listFiles().orEmpty().isEmpty())
            assertTrue(File(f.root, "import-preview").listFiles().orEmpty().none { it.name.endsWith(".export") || it.name.endsWith(".replace") })
        }
    }

    @Test fun thumbnailRetryUsesNewImmutableObjectAndRemovesSupersededFile() = runBlocking {
        Fixture(context).use { f ->
            f.importer.enqueue(f.visit(), listOf(f.source(image())), schedule = false)
            val id=f.dao.pendingImports().single().id
            f.importer.process(id)
            val before=requireNotNull(f.dao.attachment(id))
            val oldThumb=requireNotNull(before.thumbnailId)
            val originalCipher=f.store().path(before.objectId ?: id).readBytes()
            f.dao.putImportJob(requireNotNull(f.dao.importJob(id)).copy(phase="THUMBNAIL"))
            f.importer.process(id)
            val after=requireNotNull(f.dao.attachment(id))
            val newThumb=requireNotNull(after.thumbnailId)
            assertNotEquals(oldThumb,newThumb)
            assertFalse(f.store().path(oldThumb).exists())
            assertTrue(f.store().path(newThumb).exists())
            assertEquals(before.plainSha256,after.plainSha256)
            assertArrayEquals(originalCipher,f.store().path(after.objectId ?: id).readBytes())
            assertEquals("DONE",f.dao.importJob(id)?.phase)
            assertNotNull(f.importer.thumbnail(id)?.also {it.recycle()})
        }
    }

    @Test fun twoSnapshotsKeepReplacedObjectsUntilBothRelease() = runBlocking {
        Fixture(context).use { f ->
            val original=image()
            f.importer.enqueue(f.visit(),listOf(f.source(original)),schedule=false)
            val id=f.dao.pendingImports().single().id
            f.importer.process(id)
            val old=requireNotNull(f.dao.attachment(id))
            val protection=f.importer.objectProtection
            val a=protection.capture(f.material.id,UUID.randomUUID().toString(),1,System.currentTimeMillis())
            val b=protection.capture(f.material.id,UUID.randomUUID().toString(),2,System.currentTimeMillis())
            try {
                assertEquals(old,a.snapshot.attachments.single())
                f.importer.replaceOriginal(id,f.source(image(95,75,android.graphics.Color.BLUE)))
                assertTrue(f.store().path(id).exists())
                assertTrue(f.store().path(requireNotNull(old.thumbnailId)).exists())
                protection.recover() // Recovery must not expire a still-live lease.
                a.release()
                assertTrue(f.store().path(id).exists())
                val plain=File(f.root,"pinned-original")
                f.store().decryptTo(cn.yunji.journal.core.storage.StoredObject(id,old.plainSize,old.cipherSize,old.plainSha256,old.cipherSha256),plain)
                assertArrayEquals(original,plain.readBytes());plain.delete()
                b.release()
                assertFalse(f.store().path(id).exists())
                assertFalse(f.store().path(requireNotNull(old.thumbnailId)).exists())
                assertTrue(f.dao.objectPins().isEmpty());assertTrue(f.dao.retiredObjects().isEmpty())
                assertTrue(f.store().path(requireNotNull(f.dao.attachment(id)?.objectId)).exists())
            } finally {a.release();b.release()}
        }
    }

    @Test fun scopedSnapshotReleasesPinsAndRetiredObjectsWhenCallbackFails() = runBlocking {
        Fixture(context).use { f ->
            f.importer.enqueue(f.visit(),listOf(f.source(image())),schedule=false)
            val id=f.dao.pendingImports().single().id;f.importer.process(id)
            val expected=java.io.IOException("synthetic callback failure")
            val failure=runCatching {
                f.importer.objectProtection.withSnapshot(f.material.id,UUID.randomUUID().toString(),1,System.currentTimeMillis()) {
                    f.importer.replaceOriginal(id,f.source(image(95,75,android.graphics.Color.BLUE)))
                    assertTrue(f.store().path(id).exists())
                    throw expected
                }
            }.exceptionOrNull()
            assertSame(expected,failure)
            assertTrue(f.dao.objectPins().isEmpty());assertTrue(f.dao.retiredObjects().isEmpty())
            assertFalse(f.store().path(id).exists())
            assertTrue(f.store().path(requireNotNull(f.dao.attachment(id)?.objectId)).exists())
        }
    }

    @Test fun cancelledScopedSnapshotReleasesBeforeJobFinishes() = runBlocking {
        Fixture(context).use { f ->
            f.importer.enqueue(f.visit(),listOf(f.source(image())),schedule=false)
            val id=f.dao.pendingImports().single().id;f.importer.process(id)
            val entered=CompletableDeferred<Unit>()
            val reader=launch {
                f.importer.objectProtection.withSnapshot(f.material.id,UUID.randomUUID().toString(),1,System.currentTimeMillis()) {
                    entered.complete(Unit);awaitCancellation()
                }
            }
            withTimeout(10_000){entered.await()}
            f.importer.trash(id,schedule=false);f.importer.purge(id,schedule=false);f.importer.process(id)
            assertTrue(f.store().path(id).exists())
            withTimeout(10_000){reader.cancelAndJoin()}
            assertTrue(f.dao.objectPins().isEmpty());assertTrue(f.dao.retiredObjects().isEmpty())
            assertFalse(f.store().path(id).exists())
        }
    }

    @Test fun lockingImporterCancelsSnapshotAndWaitsForReleaseBeforeDatabaseClose() = runBlocking {
        Fixture(context).use { f ->
            f.importer.enqueue(f.visit(),listOf(f.source(image())),schedule=false)
            val id=f.dao.pendingImports().single().id;f.importer.process(id)
            val entered=CompletableDeferred<Unit>();val exited=CompletableDeferred<Unit>()
            val reader=launch {
                try {f.importer.objectProtection.withSnapshot(f.material.id,UUID.randomUUID().toString(),1,System.currentTimeMillis()) {
                    entered.complete(Unit);awaitCancellation()
                }} finally {exited.complete(Unit)}
            }
            withTimeout(10_000){entered.await()}
            assertTrue(f.dao.objectPins().isNotEmpty())
            withTimeout(10_000){f.importer.shutdown()}
            assertTrue(reader.isCompleted);assertTrue(exited.isCompleted)
            assertTrue(f.dao.objectPins().isEmpty())
            assertTrue(runCatching {f.importer.objectProtection.withSnapshot(f.material.id,UUID.randomUUID().toString(),2,System.currentTimeMillis()) {}}.exceptionOrNull() is VaultLockedException)
            assertNotNull(f.dao.attachment(id));assertTrue(f.store().path(id).exists())
        }
    }

    @Test fun snapshotKeepsPermanentlyDeletedFileAndRejectsCaptureDuringPurge() = runBlocking {
        Fixture(context).use { f ->
            f.importer.enqueue(f.visit(),listOf(f.source(image())),schedule=false)
            val id=f.dao.pendingImports().single().id
            f.importer.process(id)
            val lease=f.importer.objectProtection.capture(f.material.id,UUID.randomUUID().toString(),1,System.currentTimeMillis())
            try {
                f.importer.trash(id,schedule=false);f.importer.purge(id,schedule=false)
                assertTrue(runCatching {f.importer.objectProtection.capture(f.material.id,UUID.randomUUID().toString(),2,System.currentTimeMillis())}.isFailure)
                f.importer.process(id)
                assertNull(f.dao.attachment(id));assertEquals("PURGED",f.dao.importJob(id)?.state)
                assertTrue(f.store().path(id).exists())
                assertEquals(id,lease.snapshot.attachments.single().id)
            } finally {lease.release()}
            assertFalse(f.store().path(id).exists())
            assertTrue(f.dao.retiredObjects().isEmpty())
        }
    }

    @Test fun orphanPinSurvivesDatabaseReopenUntilRecoveryThenRetiredFileIsRemoved() = runBlocking {
        Fixture(context).use { f ->
            val stored=f.store().put(image().inputStream())
            f.dao.insertObjectPins(listOf(cn.yunji.journal.core.database.BackupObjectPin(UUID.randomUUID().toString(),stored.id)))
            f.dao.retireObject(cn.yunji.journal.core.database.RetiredObject(stored.id))
            f.importer.objectProtection.cleanup()
            assertTrue(f.store().path(stored.id).exists())
            f.db.close()
            val reopened=JournalDatabase.open(context,File(f.root,"test.db"),f.key)
            try {
                assertEquals(1,reopened.journalDao().objectPins().size)
                cn.yunji.journal.core.backup.ObjectProtection(reopened,f.store()).recover()
                assertTrue(reopened.journalDao().objectPins().isEmpty())
                assertTrue(reopened.journalDao().retiredObjects().isEmpty())
                assertFalse(f.store().path(stored.id).exists())
            } finally {reopened.close()}
        }
    }

    @Test fun largeImageAndMultiPagePdfStayWithinThumbnailAndRenderBounds() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            val bitmap = Bitmap.createBitmap(3600, 2400, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.LTGRAY)
            val largeImage = try { java.io.ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it); it.toByteArray() } }
            finally { bitmap.recycle() }
            f.importer.enqueue(visit, listOf(f.source(largeImage, "jpg")), schedule = false)
            val imageJob = f.dao.pendingImports().single().id
            f.importer.process(imageJob)
            val imageAttachment = requireNotNull(f.dao.attachment(imageJob))
            assertEquals(largeImage.size.toLong(), imageAttachment.plainSize)
            val thumbnail = requireNotNull(f.importer.thumbnail(imageJob))
            assertTrue(maxOf(thumbnail.width, thumbnail.height) <= 512)
            thumbnail.recycle()
            f.importer.openPreview(imageJob).use { assertTrue(it.file.length() > 0) }
            val pdf = PdfDocument()
            try {
                repeat(20) { index ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(1200, 1600, index + 1).create())
                    page.canvas.drawColor(android.graphics.Color.WHITE)
                    page.canvas.drawText("Synthetic page ${index + 1}", 80f, 120f,
                        android.graphics.Paint().apply { textSize = 48f; color = android.graphics.Color.BLACK })
                    pdf.finishPage(page)
                }
                val pdfBytes = java.io.ByteArrayOutputStream().use { out -> pdf.writeTo(out); out.toByteArray() }
                f.importer.enqueue(visit, listOf(f.source(pdfBytes, "pdf")), schedule = false)
                val pdfJob = f.dao.pendingImports().single { it.id != imageJob }.id
                f.importer.process(pdfJob)
                assertEquals("READY", f.dao.importJob(pdfJob)?.state)
                val preview = f.importer.openPreview(pdfJob)
                cn.yunji.journal.feature.PdfSession(preview).use { session ->
                    assertEquals(20, session.pageCount)
                    listOf(0, 19).forEach { index ->
                        val rendered = session.render(index, 4000)
                        assertTrue(rendered.width.toLong() * rendered.height.toLong() <= 4_000_000L)
                        rendered.recycle()
                    }
                }
                assertFalse(preview.file.exists())
            } finally { pdf.close() }
            assertTrue(File(f.root, "document-viewers").listFiles().orEmpty().isEmpty())
        }
    }


    @Test fun pregnancyBatchRestoreDoesNotReviveIndependentlyDeletedVisitsOrFiles() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            val pregnancy = requireNotNull(f.dao.visit(visit)).pregnancyId
            val second = JournalRepository(f.db, Clock.systemUTC()).saveVisit(null, pregnancy, LocalDate.now(), null, "", "独立删除产检", "")
            for (target in listOf(visit, visit, second)) {
                f.importer.enqueue(target, listOf(f.source(image())), schedule = false)
                f.dao.pendingImports().forEach { f.importer.process(it.id) }
            }
            val independent = f.dao.attachments(visit).first().first()
            f.importer.trash(independent.id, schedule = false)
            val service = f.importer.trashRepository
            val secondBatch = service.delete("VISIT", second, schedule = false)
            val batch = service.delete("PREGNANCY", pregnancy, schedule = false)
            assertTrue(f.dao.pregnancy(pregnancy)?.deletedAt != null)
            assertEquals(2, f.dao.allAttachments().count { it.deletionBatchId == batch })
            try { service.restore(secondBatch); fail("Child restored below deleted parent") } catch (_: IllegalArgumentException) { }
            service.restore(batch)
            assertNull(f.dao.pregnancy(pregnancy)?.deletedAt)
            assertNull(f.dao.visit(visit)?.deletedAt)
            assertNotNull(f.dao.visit(second)?.deletedAt)
            assertNotNull(f.dao.attachment(independent.id)?.deletedAt)
            assertEquals(1, service.retained.first().size)
            assertEquals(2, f.dao.allAttachments().count { it.deletedAt == null })
            assertEquals("DELETED", f.dao.deletionBatch(secondBatch)?.state)
            assertTrue(f.dao.allAttachments().filter { it.deletedAt == null }.all { it.deletionBatchId == null })
        }
    }

    @Test fun permanentVisitDeletionKeepsReportsAccessibleAndRestorable() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            f.importer.enqueue(visit, listOf(f.source(image())), schedule = false)
            val id = f.dao.pendingImports().single().id
            f.importer.process(id)
            val batch = f.importer.trashRepository.delete("VISIT", visit, schedule = false)
            assertEquals(id, f.importer.trashRepository.retained.first().single().id)
            f.importer.openPreview(id).use { assertTrue(it.file.exists()) }
            f.importer.trashRepository.purge(batch, schedule = false)
            f.importer.trashRepository.resume()
            assertEquals("PURGED", f.dao.deletionBatch(batch)?.state)
            assertEquals("", f.dao.visit(visit)?.items)
            assertTrue(f.store().path(id).exists())
            f.importer.trash(id, schedule = false)
            f.importer.restore(id)
            assertNull(f.dao.attachment(id)?.deletedAt)
            assertEquals(id, f.importer.trashRepository.retained.first().single().id)
        }
    }

    @Test fun pregnancyPurgeRetriesFileFailureAndCleansIndependentTrashToo() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            val pregnancy = requireNotNull(f.dao.visit(visit)).pregnancyId
            f.importer.enqueue(visit, listOf(f.source(image())), schedule = false)
            val id = f.dao.pendingImports().single().id
            f.importer.process(id)
            val repo = JournalRepository(f.db, Clock.systemUTC())
            repo.saveDraft("pregnancy", pregnancy, "synthetic private draft")
            repo.saveDraft("visit", visit, "synthetic visit draft")
            repo.saveDraft("actualVisit", visit, "synthetic advice draft")
            repo.saveDraft("visit", "new", "{\"pregnancyId\":\"$pregnancy\",\"items\":\"synthetic legacy draft\"}")
            f.importer.trash(id, schedule = false)
            val batch = f.importer.trashRepository.delete("PREGNANCY", pregnancy, schedule = false)
            f.importer.trashRepository.purge(batch, schedule = false)
            val objectFile = f.store().path(id)
            val saved = File(f.root, "saved-object")
            assertTrue(objectFile.renameTo(saved))
            assertTrue(objectFile.mkdir())
            File(objectFile, "block-cleanup").writeText("synthetic obstruction")
            try { f.importer.trashRepository.resume(); fail("Failed file cleanup reported success") } catch (_: IllegalStateException) { }
            assertEquals("PURGING", f.dao.deletionBatch(batch)?.state)
            assertNotNull(f.dao.attachment(id))
            try { f.importer.trashRepository.restore(batch); fail("Purging batch restored") } catch (_: IllegalArgumentException) { }
            objectFile.deleteRecursively(); assertTrue(saved.renameTo(objectFile))
            // A new coordinator reads the durable state left by the interrupted cleanup.
            cn.yunji.journal.data.TrashRepository(f.db, f.importer, Clock.systemUTC()).resume()
            assertEquals("PURGED", f.dao.deletionBatch(batch)?.state)
            assertFalse(objectFile.exists())
            assertNull(f.dao.attachment(id))
            assertEquals("PURGED", f.dao.importJob(id)?.state)
            assertEquals("已删除孕期", f.dao.pregnancy(pregnancy)?.title)
            assertTrue(f.dao.allDateRevisions().isEmpty())
            assertTrue(f.dao.allDrafts().isEmpty())
            try { repo.saveDraft("visit", visit, "late draft"); fail("Late deleted visit draft accepted") } catch (_: IllegalArgumentException) { }
            try { repo.saveDraft("pregnancy", pregnancy, "late draft"); fail("Late deleted pregnancy draft accepted") } catch (_: IllegalArgumentException) { }
            try { repo.saveDraft("visit", "new", "{\"pregnancyId\":\"$pregnancy\"}"); fail("Late legacy draft accepted") } catch (_: IllegalArgumentException) { }
            assertTrue(f.dao.allDrafts().isEmpty())
        }
    }

    @Test fun deletionDuringImportCannotResurrectAfterParentRestore() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            val pregnancy = requireNotNull(f.dao.visit(visit)).pregnancyId
            f.importer.enqueue(visit, listOf(f.source(image())), schedule = false)
            val id = f.dao.pendingImports().single().id
            var deleted = false
            f.importer.process(id) { stage, _ ->
                if (stage == "encrypt" && !deleted) {
                    deleted = true
                    runBlocking {
                        val batch = f.importer.trashRepository.delete("PREGNANCY", pregnancy, schedule = false)
                        f.importer.trashRepository.restore(batch)
                    }
                }
            }
            assertTrue("Delete injection stage ran", deleted)
            f.importer.trashRepository.resume()
            assertNull(f.dao.attachment(id))
            assertEquals("REMOVED", f.dao.importJob(id)?.state)
            assertFalse(f.store().path(id).exists())
            assertNull(f.dao.pregnancy(pregnancy)?.deletedAt)
        }
    }

    @Test fun retentionBoundaryDoesNotCleanBeforeThirtyDays() = runBlocking {
        Fixture(context).use { f ->
            val visit = f.visit()
            val service = f.importer.trashRepository
            val batch = service.delete("VISIT", visit, schedule = false)
            val deletion = requireNotNull(f.dao.deletionBatch(batch)).deletedAt
            val boundary = deletion + java.time.Duration.ofDays(30).toMillis()
            cn.yunji.journal.data.TrashRepository(f.db, f.importer, Clock.fixed(java.time.Instant.ofEpochMilli(boundary - 1), java.time.ZoneOffset.UTC)).resume()
            assertEquals("DELETED", f.dao.deletionBatch(batch)?.state)
            cn.yunji.journal.data.TrashRepository(f.db, f.importer, Clock.fixed(java.time.Instant.ofEpochMilli(boundary), java.time.ZoneOffset.UTC)).resume()
            assertEquals("PURGED", f.dao.deletionBatch(batch)?.state)
        }
    }
}
