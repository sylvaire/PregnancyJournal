package cn.yunji.journal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.PregnancyEntity
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.EncryptedFileStore
import cn.yunji.journal.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.time.Clock
import java.util.UUID

class LocalRestoreAccessTest {
    private class Cloud: RestoreCloudControl {
        var pauses=0;var resumes=0;var failResume=false
        override suspend fun pauseForRestore(){pauses++}
        override fun resumeAfterRestoreAbort(){check(!failResume){"Synthetic resume failure"};resumes++}
    }
    private class Fixture: AutoCloseable {
        val base=ApplicationProvider.getApplicationContext<Context>();val token=UUID.randomUUID().toString()
        val root=File(base.cacheDir,"local-restore-$token").apply {mkdirs()}
        val context=object: ContextWrapper(base) {
            override fun getApplicationContext(): Context=this
            override fun getNoBackupFilesDir()=root
            override fun getSharedPreferences(name: String,mode: Int)=base.getSharedPreferences("$token-$name",mode)
        }
        val keys=DeviceKeyStore.isolated(context,token)
        val runtime=VaultRuntime(context,keys,VaultSession(),Clock.systemUTC(),ReminderStore(context),ReminderNotifier(context),AccessState(context,SecurityStore(context),keys))
        val source=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        val recovery=RecoveryKey.create()
        val cloud=Cloud()
        val archive=File(root,"source.yunji")
        val fresh=PregnancyEntity("fresh","合成导入",createdAt=1,updatedAt=1)
        init {
            val snapshot=LogicalSnapshot(vaultId=source.id,snapshotId=UUID.randomUUID().toString(),writerId=UUID.randomUUID().toString(),writerSequence=1,capturedSequence=1,createdAt=1,pregnancies=listOf(fresh))
            LocalBackupArchive.write(snapshot,source,RecoverySlots.create(source,recovery),EncryptedFileStore(File(root,"source-objects"),source),archive)
        }
        val inputRoot get()=File(root,"vault/restore-input")
        fun access(limit: Long=8L*1024*1024*1024+64L*1024*1024)=LocalRestoreAccess(context,runtime,cloud,{archive.inputStream()},limit)
        override fun close() {
            runBlocking {runtime.revoke()};source.master.fill(0)
            val alias="${base.packageName}.vault-wrap.test-$token.v1"
            KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)}
            listOf("security-state","reminder-state","cloud-scheduler","cloud-upload-state").forEach {base.deleteSharedPreferences("$token-$it")}
            root.deleteRecursively()
        }
    }
    @Test fun productionAdapterCopiesVerifiesStopsBackgroundSwitchesAndReopens()=runBlocking {
        Fixture().use {f->
            val old=f.runtime.resources();val oldId=old.repository.savePregnancy(null,"合成旧库",null,null,"","")
            val oldKey=RecoveryKey.create();RecoverySettings(f.context,old.material).save(RecoverySlots.create(old.material,oldKey))
            val access=f.access();val preview=access.prepare("content://synthetic/backup",f.recovery)
            assertTrue(f.inputRoot.listFiles().orEmpty().isEmpty());assertEquals(0,f.cloud.pauses)
            access.commit(preview)
            assertTrue(access.isCommitted(preview));assertEquals(1,f.cloud.pauses);assertEquals(1,f.cloud.resumes)
            assertEquals(listOf(f.fresh),f.runtime.resources().database.journalDao().allPregnancies())
            assertFalse(CloudSchedulerStore(f.context).load().autoActive)
            val safety=requireNotNull(access.lastSafety)
            LocalBackupArchive.verify(safety.file,oldKey,f.root).use {assertEquals(oldId,it.snapshot.pregnancies.single().id)}
            assertTrue(f.archive.isFile)
        }
    }
    @Test fun oversizedInputNeverPausesBackgroundOrLeavesCopy()=runBlocking {
        Fixture().use {f->
            f.runtime.resources()
            assertTrue(runCatching {f.access(32).prepare("content://synthetic/backup",f.recovery)}.isFailure)
            assertTrue(f.inputRoot.listFiles().orEmpty().isEmpty());assertEquals(0,f.cloud.pauses)
            assertNull(VaultLayout(File(f.root,"vault")).activeGenerationId())
        }
    }
    @Test fun wrongKeyCleansInputAndPreservesLiveGraph()=runBlocking {
        Fixture().use {f->
            val old=f.runtime.resources()
            assertTrue(runCatching {f.access().prepare("content://synthetic/backup",RecoveryKey.create())}.isFailure)
            assertTrue(f.runtime.resources()===old);assertTrue(f.inputRoot.listFiles().orEmpty().isEmpty())
            assertEquals(0,f.cloud.pauses)
        }
    }
    @Test fun recoveryOnlyRemovesRecognizedOrphanDirectories() {
        Fixture().use {f->
            val orphan=File(f.inputRoot,"job-${UUID.randomUUID()}").apply {mkdirs()};File(orphan,"partial").writeText("synthetic")
            val unrelated=File(f.inputRoot,"keep").apply {mkdirs()};File(unrelated,"keep").writeText("synthetic")
            LocalRestoreAccess.recover(f.context)
            assertFalse(orphan.exists());assertTrue(File(unrelated,"keep").isFile)
        }
    }
    @Test fun corruptCurrentRecoverySettingsStopsSwitchAndKeepsOldData()=runBlocking {
        Fixture().use {f->
            val old=f.runtime.resources();val id=old.repository.savePregnancy(null,"合成保留档案",null,null,"","")
            val access=f.access();val preview=access.prepare("content://synthetic/backup",f.recovery)
            val corrupt=File(f.root,"vault/recovery-slot.enc").apply {writeText("synthetic corrupted wrapper")}
            val before=corrupt.readBytes()
            assertTrue(runCatching {access.commit(preview)}.isFailure)
            assertArrayEquals(before,corrupt.readBytes())
            assertFalse(access.isCommitted(preview));assertTrue(f.runtime.resources()===old)
            assertEquals(id,old.database.journalDao().allPregnancies().single().id)
            assertTrue(f.cloud.pauses>=1);assertEquals(1,f.cloud.resumes)
            access.discard(preview);assertNull(RestoreJournal(File(f.root,"vault")).read())
        }
    }    @Test fun firstRestoreProtectsEmptyCurrentVaultUsingVerifiedInputKeyOnlyAfterConfirmation()=runBlocking {
        Fixture().use {f->
            val old=f.runtime.resources();val oldId=old.material.id
            val settings=RecoverySettings(f.context,old.material)
            assertNull(settings.load())
            val access=f.access();val preview=access.prepare("content://synthetic/backup",f.recovery)
            assertNull(settings.load()) // Preparing/previewing has not changed live recovery settings.
            access.commit(preview)
            val safety=requireNotNull(access.lastSafety)
            LocalBackupArchive.verify(safety.file,f.recovery,f.root).use {verified->
                assertEquals(oldId,verified.snapshot.vaultId);assertTrue(verified.snapshot.pregnancies.isEmpty())
            }
            assertEquals(listOf(f.fresh),f.runtime.resources().database.journalDao().allPregnancies())
        }
    }
    @Test fun cancellingPreviewDoesNotInstallCandidateSafetyKey()=runBlocking {
        Fixture().use {f->
            val old=f.runtime.resources();val settings=RecoverySettings(f.context,old.material)
            val access=f.access();val preview=access.prepare("content://synthetic/backup",f.recovery)
            access.discard(preview)
            assertNull(settings.load());assertNull(RestoreJournal(File(f.root,"vault")).read())
            assertTrue(f.runtime.resources()===old)
        }
    }    @Test fun reopenRetriesVerifiedCompletionAndOrdinaryOpensNeverResumeCloud()=runBlocking {
        Fixture().use {f->
            val access=f.access()
            access.finishReopen()
            assertEquals(0,f.cloud.resumes)
            val preview=access.prepare("content://synthetic/backup",f.recovery)
            access.commit(preview)
            assertEquals(1,f.cloud.resumes)
            val journal=RestoreJournal(File(f.root,"vault"))
            assertNull(journal.read())
            // Simulate a persisted pending completion after an authenticated graph is available.
            journal.write(RestoreStage.VERIFIED,preview.generationId,null,preview.snapshotId)
            f.cloud.failResume=true
            assertTrue(runCatching {access.finishReopen()}.isFailure)
            assertEquals(RestoreStage.VERIFIED,journal.read());assertEquals(1,f.cloud.resumes)
            f.cloud.failResume=false
            access.finishReopen()
            assertEquals(2,f.cloud.resumes);assertNull(journal.read())
            access.finishReopen();access.finishReopen()
            assertEquals(2,f.cloud.resumes)
        }
    }    @Test fun consecutiveRestoresRetainIndependentlyDecryptableSafetyCopiesWithTheirOwnKeys()=runBlocking {
        Fixture().use {f->
            val old=f.runtime.resources()
            val oldVault=old.material.id
            val oldId=old.repository.savePregnancy(null,"合成替换前档案",null,null,"","")
            val oldKey=RecoveryKey.create()
            RecoverySettings(f.context,old.material).save(RecoverySlots.create(old.material,oldKey))
            val access=f.access()
            access.commit(access.prepare("content://synthetic/first",f.recovery))
            val first=requireNotNull(access.lastSafety)
            val firstBytes=first.file.readBytes()
            val secondSource=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
            val secondKey=RecoveryKey.create()
            val secondPregnancy=PregnancyEntity("second","合成第二次恢复",createdAt=2,updatedAt=2)
            try {
                val snapshot=LogicalSnapshot(vaultId=secondSource.id,snapshotId=UUID.randomUUID().toString(),writerId=UUID.randomUUID().toString(),writerSequence=1,capturedSequence=1,createdAt=2,pregnancies=listOf(secondPregnancy))
                check(f.archive.delete()) // Replace only this fixture-owned input, never either safety copy.
                LocalBackupArchive.write(snapshot,secondSource,RecoverySlots.create(secondSource,secondKey),EncryptedFileStore(File(f.root,"second-objects"),secondSource),f.archive)
                access.commit(access.prepare("content://synthetic/second",secondKey))
                val second=requireNotNull(access.lastSafety)
                assertNotEquals(first.file.canonicalPath,second.file.canonicalPath)
                assertArrayEquals(firstBytes,first.file.readBytes())
                LocalBackupArchive.verify(first.file,oldKey,f.root).use {
                    assertEquals(oldVault,it.snapshot.vaultId)
                    assertEquals(listOf(oldId),it.snapshot.pregnancies.map {row->row.id})
                    IsolatedRestore.withDatabase(f.context,it){db->assertEquals(listOf(oldId),db.journalDao().allPregnancies().map {row->row.id})}
                }
                LocalBackupArchive.verify(second.file,f.recovery,f.root).use {
                    assertEquals(f.source.id,it.snapshot.vaultId)
                    assertEquals(listOf(f.fresh),it.snapshot.pregnancies)
                    IsolatedRestore.withDatabase(f.context,it){db->assertEquals(listOf(f.fresh),db.journalDao().allPregnancies())}
                }
                assertTrue(runCatching {LocalBackupArchive.verify(first.file,secondKey,f.root).use {}}.isFailure)
                assertTrue(runCatching {LocalBackupArchive.verify(second.file,secondKey,f.root).use {}}.isFailure)
                f.runtime.revoke()
                assertEquals(listOf(secondPregnancy),f.runtime.resources().database.journalDao().allPregnancies())
                assertTrue(first.file.isFile && second.file.isFile)
                assertNull(RestoreJournal(File(f.root,"vault")).read())
                assertEquals(2,f.cloud.pauses);assertEquals(2,f.cloud.resumes)
            }finally {secondSource.master.fill(0)}
        }
    }}