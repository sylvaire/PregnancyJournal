package cn.yunji.journal

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class RestoreGenerationDatabaseTest {
    @Test fun realSqlCipherGenerationReopensAndItsOriginalSurvivesVerifierTeardown()=runBlocking {
        assumeTrue(Build.MODEL=="sdk_gphone64_x86_64" && Build.VERSION.SDK_INT==34)
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=File(context.cacheDir,"generation-sqlcipher-${UUID.randomUUID()}").apply {mkdirs()}
        val source=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        val device=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        try {
            val recovery=RecoveryKey.create();val slot=RecoverySlots.create(source,recovery)
            val originalBytes="Synthetic generation original, no personal data".toByteArray()
            val store=EncryptedFileStore(File(root,"source-objects"),source)
            val original=store.put(originalBytes.inputStream())
            val pregnancy=PregnancyEntity("p","合成恢复验证",createdAt=1,updatedAt=1)
            val visit=VisitEntity("v",pregnancy.id,"2026-10-21",zoneId="UTC",createdAt=1,updatedAt=1)
            val attachment=AttachmentEntity(original.id,visit.id,"合成资料","image/png",original.plainSize,original.cipherSize,original.plainSha256,original.cipherSha256,null,null,null,null,null,1)
            val snapshot=LogicalSnapshot(vaultId=source.id,snapshotId=UUID.randomUUID().toString(),writerId=UUID.randomUUID().toString(),writerSequence=1,capturedSequence=9,createdAt=1,
                pregnancies=listOf(pregnancy),visits=listOf(visit),attachments=listOf(attachment))
            val archive=File(root,"complete.yunji");LocalBackupArchive.write(snapshot,source,slot,store,archive)
            val vaultRoot=File(root,"vault").apply {mkdirs()}
            val layout=VaultLayout(vaultRoot);val journal=RestoreJournal(vaultRoot)
            val wizard=RestoreWizard(layout,journal,File(root,"temporary"))
            val preview=wizard.prepare(archive,recovery,device){handle->IsolatedRestore.buildDatabase(context,handle)}
            assertNull(layout.activeGenerationId())
            assertTrue(File(layout.generationDir(preview.generationId),"objects/${original.id}.bin").isFile)
            val reopened=GenerationKeyStore(vaultRoot).open(preview.generationId,device)
            assertEquals(source.id,reopened.id)
            assertNotEquals(device.id,reopened.id)
            RestoreGenerationVerifier.verify(context,vaultRoot,preview.generationId,device)
            val restoredObject=File(layout.generationDir(preview.generationId),"objects/${original.id}.bin")
            val cipherBefore=restoredObject.readBytes()
            val damaged=cipherBefore.clone();damaged[damaged.lastIndex]=(damaged.last().toInt() xor 1).toByte();restoredObject.writeBytes(damaged)
            assertTrue(runCatching {RestoreGenerationVerifier.verify(context,vaultRoot,preview.generationId,device)}.isFailure)
            restoredObject.writeBytes(cipherBefore)
            var verified=false
            try {
            wizard.commit(preview){file->
                val key=reopened.key("database")
                val db=try {JournalDatabase.open(context,file,key)}finally {key.fill(0)}
                try {runBlocking {
                    assertEquals(listOf(pregnancy),db.journalDao().allPregnancies())
                    assertEquals(listOf(attachment),db.journalDao().allAttachments())
                    assertEquals(9L,db.journalDao().currentSequence())
                    val importer=cn.yunji.journal.data.DocumentImporter(context,db,reopened,java.time.Clock.systemUTC(),File(file.parentFile,"objects"))
                    try {importer.openPreview(original.id).use {preview->assertArrayEquals(originalBytes,preview.file.readBytes())}}
                    finally {importer.shutdown()}
                }}finally {db.close()}
                val output=File(root,"authenticated-check")
                EncryptedFileStore(File(file.parentFile,"objects"),reopened).decryptTo(original,output)
                assertArrayEquals(originalBytes,output.readBytes());output.delete();verified=true
            }
            assertTrue(verified);assertEquals(RestoreStage.VERIFIED,journal.read())
            assertTrue(runCatching {wizard.discard(preview)}.isFailure)
            assertTrue(layout.activeDatabase().isFile)
            }finally {reopened.master.fill(0)}
        }finally {source.master.fill(0);device.master.fill(0);root.deleteRecursively()}
    }
}