package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.zip.*

class LocalBackupArchiveTest {
    @get:Rule val temporary=TemporaryFolder()
    private inner class Fixture {
        val root=temporary.newFolder()
        val vault=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        val recovery=RecoveryKey.create()
        val slot=RecoverySlots.create(vault,recovery)
        val store=EncryptedFileStore(File(root,"source"),vault)
        val bytes="synthetic original, not personal data".toByteArray()
        val original=store.put(bytes.inputStream());val thumb=store.put(byteArrayOf(1,2,3).inputStream())
        val p=PregnancyEntity("p","合成档案",createdAt=1,updatedAt=1)
        val visit=VisitEntity("visit",p.id,"2026-10-19",zoneId="UTC",createdAt=1,updatedAt=1,dateRevisionId="date")
        val drug=MedicationEntity("drug",p.id,"合成药品","","",1)
        val plan=MedicationPlanEntity("plan",p.id,drug.id,"logical",1,"0.12500","片","DAILY_TIMES","08:00","","UTC","2026-10-19",null,"ACTIVE","","",1)
        val point=MedicationOccurrenceEntity("point",p.id,plan.id,"2026-10-19","08:00",0,1,"UTC",createdAt=1)
        val snapshot=LogicalSnapshot(vaultId=vault.id,snapshotId=UUID.randomUUID().toString(),writerId=UUID.randomUUID().toString(),writerSequence=1,capturedSequence=10,createdAt=1,
            pregnancies=listOf(p),dateRevisions=listOf(PregnancyDateRevisionEntity("date",p.id,null,null,"UNKNOWN",1)),visits=listOf(visit),
            attachments=listOf(AttachmentEntity(original.id,visit.id,"合成原件","image/png",original.plainSize,original.cipherSize,original.plainSha256,original.cipherSha256,
                thumb.id,thumb.plainSize,thumb.cipherSize,thumb.plainSha256,thumb.cipherSha256,1)),
            deletionBatches=listOf(DeletionBatchEntity("batch","JOURNAL","entry",2)),medications=listOf(drug),plans=listOf(plan),occurrences=listOf(point),
            intakes=listOf(MedicationIntakeEntity("intake",p.id,plan.id,point.id,point.id,"TAKEN",1,"2026-10-19","UTC","0.12500","片","",1,1)),
            definitions=listOf(MetricDefinitionEntity("definition",p.id,"metric",1,"CUSTOM","合成指标","单位","value",1)),
            measurements=listOf(MeasurementEntity("measurement",p.id,"definition",1,"UTC","NONE",null,"MANUAL","",1,1)),
            values=listOf(MeasurementValueEntity("measurement","value","0.12500")),
            entries=listOf(JournalEntryEntity("entry",p.id,"2026-10-19",null,"UTC",null,"","","合成回收站备注",1,1,deletedAt=2,deletionBatchId="batch")))
        val archive=File(root,"backup.yunji")
        fun write()=LocalBackupArchive.write(snapshot,vault,slot,store,archive)
        fun rewrite(target: File,transform: (String,ByteArray)->Pair<String,ByteArray>?) {
            ZipFile(archive).use {input->ZipOutputStream(target.outputStream()).use {output->
                val entries=input.entries()
                while(entries.hasMoreElements()) {
                    val entry=entries.nextElement();val result=transform(entry.name,input.getInputStream(entry).use {it.readBytes()}) ?: continue
                    output.putNextEntry(ZipEntry(result.first));output.write(result.second);output.closeEntry()
                }
            }}
        }
    }
    @Test fun allThirteenBusinessListsAndOriginalsRoundTripAndCloseWipesStaging() {
        val f=Fixture();f.write()
        ZipFile(f.archive).use {zip->
            assertEquals(4,zip.size());assertNull(zip.getEntry("合成原件"))
            val manifest=zip.getInputStream(zip.getEntry("manifest.enc")).use {it.readBytes()}
            assertFalse(manifest.toString(Charsets.ISO_8859_1).contains("合成档案"))
        }
        val verified=LocalBackupArchive.verify(f.archive,f.recovery,f.root)
        val directory=verified.directory
        try {
            assertEquals(f.snapshot,verified.snapshot);assertArrayEquals(f.vault.master,verified.material.master)
            val store=EncryptedFileStore(File(directory,"objects"),verified.material)
            val plain=File(directory,"test-original");store.decryptTo(f.original,plain)
            assertArrayEquals(f.bytes,plain.readBytes());plain.delete()
            assertTrue(directory.listFiles().orEmpty().none {it.name=="authenticated-original"})
        } finally {verified.close()}
        assertFalse(directory.exists());assertTrue(verified.material.master.all {it==0.toByte()})
    }
    @Test fun wrongKeyMissingObjectAndTraversalAreRejectedWithoutStagingLeftovers() {
        val f=Fixture();f.write()
        assertTrue(runCatching {LocalBackupArchive.verify(f.archive,RecoveryKey.create(),f.root)}.isFailure)
        val missing=File(f.root,"missing.zip")
        f.rewrite(missing){name,bytes->if(name=="objects/${f.original.id}.bin")null else name to bytes}
        assertTrue(runCatching {LocalBackupArchive.verify(missing,f.recovery,f.root)}.isFailure)
        val traversal=File(f.root,"traversal.zip")
        f.rewrite(traversal){name,bytes->(if(name=="objects/${f.original.id}.bin")"../escape" else name) to bytes}
        assertTrue(runCatching {LocalBackupArchive.verify(traversal,f.recovery,f.root)}.isFailure)
        assertTrue(f.root.listFiles().orEmpty().none {it.name.startsWith("backup-verify-")})
    }
    @Test fun modifiedManifestAndCiphertextAreRejected() {
        val f=Fixture();f.write()
        for(targetName in listOf("manifest.enc","objects/${f.original.id}.bin")) {
            val target=File(f.root,UUID.randomUUID().toString()+".zip")
            f.rewrite(target){name,bytes->if(name==targetName)bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();name to bytes}
            assertTrue(runCatching {LocalBackupArchive.verify(target,f.recovery,f.root)}.isFailure)
        }
        assertTrue(f.root.listFiles().orEmpty().none {it.name.startsWith("backup-verify-")})
    }
    @Test fun interruptedWriteOrVerificationLeavesNoPublishedOrTemporaryBackup() {
        val f=Fixture()
        assertTrue(runCatching {LocalBackupArchive.write(f.snapshot,f.vault,f.slot,f.store,f.archive){throw java.util.concurrent.CancellationException()}}.isFailure)
        assertFalse(f.archive.exists());assertTrue(f.root.listFiles().orEmpty().none {it.name.endsWith("backup-partial")})
        f.write()
        assertTrue(runCatching {LocalBackupArchive.verify(f.archive,f.recovery,f.root){throw java.util.concurrent.CancellationException()}}.isFailure)
        assertTrue(f.root.listFiles().orEmpty().none {it.name.startsWith("backup-verify-")})
    }
    @Test fun duplicateAndExtraEntriesAndOversizedInflatedManifestReject() {
        val f=Fixture();f.write()
        val duplicate=File(f.root,"duplicate.zip")
        duplicate.writeBytes(f.archive.readBytes().toString(Charsets.ISO_8859_1).replace(f.original.id,f.thumb.id).toByteArray(Charsets.ISO_8859_1))
        assertTrue(runCatching {LocalBackupArchive.verify(duplicate,f.recovery,f.root)}.isFailure)
        for(bomb in listOf(false,true)) {
            val target=File(f.root,if(bomb)"inflated.zip" else "extra.zip")
            ZipFile(f.archive).use {input->ZipOutputStream(target.outputStream()).use {output->
                val entries=input.entries()
                while(entries.hasMoreElements()) {
                    val entry=entries.nextElement();output.putNextEntry(ZipEntry(entry.name))
                    if(bomb && entry.name=="manifest.enc") {val zeros=ByteArray(65536);repeat(257){output.write(zeros)}}
                    else input.getInputStream(entry).use {it.copyTo(output)}
                    output.closeEntry()
                }
                if(!bomb){output.putNextEntry(ZipEntry("objects/${UUID.randomUUID()}.bin"));output.write(0);output.closeEntry()}
            }}
            assertTrue(runCatching {LocalBackupArchive.verify(target,f.recovery,f.root)}.isFailure)
        }
        assertTrue(f.root.listFiles().orEmpty().none {it.name.startsWith("backup-verify-")})
    }
}
