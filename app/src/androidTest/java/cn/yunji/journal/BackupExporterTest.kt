package cn.yunji.journal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.time.Clock
import java.util.UUID

class BackupExporterTest {
    private class Fixture: AutoCloseable {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val root=File(base.cacheDir,"backup-export-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object: ContextWrapper(base){override fun getNoBackupFilesDir()=root}
        val material=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        val db=material.key("database").let {key->try {JournalDatabase.open(base,File(root,"source.db"),key)}finally {key.fill(0)}}
        val settings=RecoverySettings(context,material)
        val importer=DocumentImporter(context,db,material,Clock.systemUTC())
        val exporter=BackupExporter(context,material,importer,settings,Clock.systemUTC())
        val recovery=RecoveryKey.create()
        val work get()=File(root,"vault/backup-work")
        fun configure(){settings.save(RecoverySlots.create(material,recovery))}
        override fun close(){db.close();material.master.fill(0);root.deleteRecursively()}
    }
    @Test fun selfCheckedExportCanRecoverAndDoesNotModifySourceOrExportDrafts()=runBlocking {
        Fixture().use {f->
            f.configure()
            val id=JournalRepository(f.db,Clock.systemUTC()).savePregnancy(null,"合成备份档案",null,null,"","")
            f.db.journalDao().saveDraft(DraftEntity("visit","synthetic","private-synthetic-draft",1))
            val before=f.db.journalDao().allPregnancies();val phases=mutableListOf<String>();val output=ByteArrayOutputStream()
            f.exporter.exportTo({output}){phases+=it}
            assertEquals(3,phases.size)
            val file=File(f.root,"exported.yunji");file.writeBytes(output.toByteArray())
            LocalBackupArchive.verify(file,f.recovery,f.root).use {verified->
                assertEquals(id,verified.snapshot.pregnancies.single().id)
                IsolatedRestore.withDatabase(f.base,verified){db->assertTrue(db.journalDao().allDrafts().isEmpty())}
            }
            assertEquals(before,f.db.journalDao().allPregnancies());assertEquals(1,f.db.journalDao().allDrafts().size)
            assertTrue(f.db.journalDao().objectPins().isEmpty());assertTrue(f.work.listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun missingRecoverySettingsNeverOpensDestination()=runBlocking {
        Fixture().use {f->
            var opens=0
            assertTrue(runCatching {f.exporter.exportTo({opens++;ByteArrayOutputStream()})}.isFailure)
            assertEquals(0,opens);assertTrue(f.work.listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun interruptedVerificationAndOutputFailureReleaseAllPrivateWorkspace()=runBlocking {
        Fixture().use {f->
            f.configure()
            var opens=0
            assertTrue(runCatching {f.exporter.exportTo({opens++;ByteArrayOutputStream()}){if(it.contains("校验"))throw java.util.concurrent.CancellationException()}}.isFailure)
            assertEquals(0,opens);assertTrue(f.work.listFiles().orEmpty().isEmpty())
            val failed=object: OutputStream(){override fun write(value: Int){throw IOException("synthetic full destination")}}
            assertTrue(runCatching {f.exporter.exportTo({failed})}.isFailure)
            assertTrue(f.db.journalDao().objectPins().isEmpty());assertTrue(f.work.listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun recoveryDeletesOnlyRecognizedOrphanJobDirectories() {
        Fixture().use {f->
            val orphan=File(f.work,"job-${UUID.randomUUID()}").apply {mkdirs()}
            File(orphan,"leftover-plaintext").writeText("synthetic")
            val other=File(f.work,"unrelated").apply {mkdirs()};File(other,"keep").writeText("synthetic")
            runBlocking {f.importer.recover()}
            assertFalse(orphan.exists());assertTrue(File(other,"keep").exists())
        }
    }
    @Test fun vaultShutdownCancelsRegisteredExportBeforeDestinationOpens()=runBlocking {
        Fixture().use {f->
            f.configure()
            val entered=CompletableDeferred<Unit>();val gate=java.util.concurrent.CountDownLatch(1);var opens=0
            val reader=launch {
                f.exporter.exportTo({opens++;ByteArrayOutputStream()}){phase->
                    if(phase=="正在制作加密备份"){entered.complete(Unit);check(gate.await(10,java.util.concurrent.TimeUnit.SECONDS))}
                }
            }
            try {
                withTimeout(10000){entered.await()}
                val stopping=async {f.importer.shutdown()}
                yield();gate.countDown()
                withTimeout(10000){stopping.await();reader.join()}
                assertEquals(0,opens);assertTrue(f.db.journalDao().objectPins().isEmpty())
                assertTrue(f.work.listFiles().orEmpty().isEmpty())
            }finally {gate.countDown();reader.cancelAndJoin()}
        }
    }
    @Test fun newProviderDestinationIsWrittenAndReadBackBeforeSuccess()=runBlocking {
        Fixture().use {f->
            f.configure()
            val destination=File(f.base.cacheDir,"capture/${UUID.randomUUID()}.yunji").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
            try {
                val uri=androidx.core.content.FileProvider.getUriForFile(f.base,"${f.base.packageName}.capture",destination)
                val phases=mutableListOf<String>()
                f.exporter.export(uri){phases+=it}
                assertEquals("正在核对已保存的备份",phases.last())
                LocalBackupArchive.verify(destination,f.recovery,f.root).use {verified->assertTrue(verified.snapshot.pregnancies.isEmpty())}
                assertTrue(destination.length()>0)
            }finally {destination.delete()}
        }
    }
    @Test fun failedReadbackReportsWhenProviderRefusesPartialFileDeletion()=runBlocking {
        Fixture().use {f->
            f.configure()
            val destination=File(f.base.cacheDir,"capture/${UUID.randomUUID()}.yunji").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
            try {
                val uri=androidx.core.content.FileProvider.getUriForFile(f.base,"${f.base.packageName}.capture",destination)
                var attempts=0
                val exporter=BackupExporter(f.context,f.material,f.importer,f.settings,Clock.systemUTC()) { candidate->
                    assertEquals(uri,candidate);attempts++;false
                }
                val failure=runCatching {exporter.export(uri){if(it=="正在核对已保存的备份")destination.writeText("tampered")}}.exceptionOrNull()
                assertTrue(failure is BackupDestinationCleanupException)
                assertNotNull(failure?.cause)
                assertEquals(1,attempts)
                assertEquals("tampered",destination.readText())
            }finally {destination.delete()}
        }
    }
    @Test fun failedReadbackDeletesPartialFileWhenProviderAllowsIt()=runBlocking {
        Fixture().use {f->
            f.configure()
            val destination=File(f.base.cacheDir,"capture/${UUID.randomUUID()}.yunji").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
            try {
                val uri=androidx.core.content.FileProvider.getUriForFile(f.base,"${f.base.packageName}.capture",destination)
                var attempts=0
                val exporter=BackupExporter(f.context,f.material,f.importer,f.settings,Clock.systemUTC()) { candidate->
                    assertEquals(uri,candidate);attempts++;destination.delete()
                }
                val failure=runCatching {exporter.export(uri){if(it=="正在核对已保存的备份")destination.writeText("tampered")}}.exceptionOrNull()
                assertNotNull(failure)
                assertFalse(failure is BackupDestinationCleanupException)
                assertEquals(1,attempts)
                assertFalse(destination.exists())
            }finally {destination.delete()}
        }
    }
    @Test fun occupiedOrChangedDestinationIsPreservedInsteadOfOverwritten()=runBlocking {
        Fixture().use {f->
            f.configure()
            for(changedDuringSelfCheck in listOf(false,true)) {
                val destination=File(f.base.cacheDir,"capture/${UUID.randomUUID()}.yunji").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf())}
                val existing="synthetic previously saved backup".toByteArray()
                try {
                    if(!changedDuringSelfCheck)destination.writeBytes(existing)
                    val uri=androidx.core.content.FileProvider.getUriForFile(f.base,"${f.base.packageName}.capture",destination)
                    val failure=runCatching {f.exporter.export(uri){if(changedDuringSelfCheck && it=="正在保存备份文件")destination.writeBytes(existing)}}.exceptionOrNull()
                    assertTrue(failure is BackupDestinationOccupiedException)
                    assertArrayEquals(existing,destination.readBytes())
                    assertTrue(f.db.journalDao().objectPins().isEmpty());assertTrue(f.work.listFiles().orEmpty().isEmpty())
                }finally {destination.delete()}
            }
        }
    }
    @Test fun restoreSafetyCopyCanRecoverAndIsRetainedAfterVerification()=runBlocking {
        Fixture().use {f->
            f.configure()
            val id=JournalRepository(f.db,Clock.systemUTC()).savePregnancy(null,"合成恢复前档案",null,null,"","")
            val safety=RestoreSafetyBackup(f.context,f.material,f.importer,f.settings,Clock.systemUTC())
            val receipt=safety.create()
            assertTrue(receipt.file.isFile);safety.verify(receipt)
            LocalBackupArchive.verify(receipt.file,f.recovery,f.root).use {verified->
                assertEquals(id,verified.snapshot.pregnancies.single().id)
                assertEquals(receipt.capturedSequence,verified.snapshot.capturedSequence)
            }
            assertTrue(receipt.file.isFile)
            assertEquals(id,f.db.journalDao().allPregnancies().single().id)
            assertTrue(f.db.journalDao().objectPins().isEmpty())
        }
    }
    @Test fun safetyCopyWithoutConfirmedKeyNeverCreatesArchive()=runBlocking {
        Fixture().use {f->
            val safety=RestoreSafetyBackup(f.context,f.material,f.importer,f.settings,Clock.systemUTC())
            assertTrue(runCatching {safety.create()}.isFailure)
            assertTrue(File(f.root,"vault/restore-safety").listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun cancelledSafetyCopyLeavesNoCandidateAndChangedCopyIsRejected()=runBlocking {
        Fixture().use {f->
            f.configure();val safety=RestoreSafetyBackup(f.context,f.material,f.importer,f.settings,Clock.systemUTC())
            assertTrue(runCatching {safety.create {throw java.util.concurrent.CancellationException()}}.isFailure)
            assertTrue(File(f.root,"vault/restore-safety").listFiles().orEmpty().isEmpty())
            val receipt=safety.create();val bytes=receipt.file.readBytes()
            bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();receipt.file.writeBytes(bytes)
            assertTrue(runCatching {safety.verify(receipt)}.isFailure)
            assertTrue(receipt.file.isFile)
        }
    }    @Test fun safetyCopyCannotSealAfterNewBusinessWrite()=runBlocking {
        Fixture().use {f->
            f.configure();val safety=RestoreSafetyBackup(f.context,f.material,f.importer,f.settings,Clock.systemUTC())
            val receipt=safety.create()
            val id=JournalRepository(f.db,Clock.systemUTC()).savePregnancy(null,"备份后合成修改",null,null,"","")
            assertTrue(runCatching {safety.sealCurrent(receipt,f.db)}.isFailure)
            assertEquals(id,f.db.journalDao().allPregnancies().single().id)
            // A refused seal must leave ordinary writes available.
            f.db.journalWrite {assertTrue(f.db.journalDao().currentSequence()>receipt.capturedSequence)}
        }
    }
    @Test fun safetySealRejectsNewEditsAndAbortRestoresWriting()=runBlocking {
        Fixture().use {f->
            f.configure();val safety=RestoreSafetyBackup(f.context,f.material,f.importer,f.settings,Clock.systemUTC())
            val receipt=safety.create();val seal=safety.sealCurrent(receipt,f.db)
            var executed=false
            try {
                assertTrue(runCatching {f.db.journalWrite {executed=true}}.isFailure)
                assertFalse(executed)
            }finally {seal.abort()}
            f.db.journalWrite {executed=true};assertTrue(executed)
        }
    }
    @Test fun retiredOldGraphCannotResumeStaleWrites()=runBlocking {
        Fixture().use {f->
            val seal=f.db.sealForRestore(f.db.journalDao().currentSequence())
            seal.retire();seal.abort()
            var executed=false
            assertTrue(runCatching {f.db.journalWrite {executed=true}}.isFailure)
            assertFalse(executed)
        }
    }    @Test fun concurrentBootstrapCannotReplaceAnExistingRecoverySlot()=runBlocking {
        Fixture().use {f->
            val slots=(1..6).map {RecoverySlots.create(f.material,RecoveryKey.create())}
            val results=slots.map {slot->async(kotlinx.coroutines.Dispatchers.IO){slot to f.settings.saveIfMissing(slot)}}.map {it.await()}
            assertEquals(1,results.count {it.second})
            val winner=results.single {it.second}.first
            assertEquals(winner,f.settings.load())
            assertFalse(f.settings.saveIfMissing(slots.first {it!=winner}))
            assertEquals(winner,f.settings.load())
        }
    }}
