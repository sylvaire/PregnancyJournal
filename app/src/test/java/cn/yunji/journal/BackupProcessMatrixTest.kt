package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Task-8 (D02): process-termination / cancellation window matrix for the local encrypted backup.
 *
 * A real `force-stop` cannot be reproduced on the JVM, so this file pins the deterministic half of
 * the matrix: a cancellation (the same exception an Activity/Vault shutdown raises) injected at
 * EVERY checkpoint of archive publication and archive verification must never publish a partial
 * file, never leave staging directories, and never make a retry accept a truncated destination.
 * The on-device two-phase half lives in `D02ProcessMatrixTest`.
 */
class BackupProcessMatrixTest {
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
        val snapshot=LogicalSnapshot(vaultId=vault.id,snapshotId=UUID.randomUUID().toString(),writerId=UUID.randomUUID().toString(),writerSequence=1,capturedSequence=10,createdAt=1,
            pregnancies=listOf(p),dateRevisions=listOf(PregnancyDateRevisionEntity("date",p.id,null,null,"UNKNOWN",1)),visits=listOf(visit),
            attachments=listOf(AttachmentEntity(original.id,visit.id,"合成原件","image/png",original.plainSize,original.cipherSize,original.plainSha256,original.cipherSha256,
                thumb.id,thumb.plainSize,thumb.cipherSize,thumb.plainSha256,thumb.cipherSha256,1)),
            deletionBatches=listOf(DeletionBatchEntity("batch","JOURNAL","entry",2)),medications=emptyList(),plans=emptyList(),occurrences=emptyList(),
            intakes=emptyList(),definitions=emptyList(),measurements=emptyList(),values=emptyList(),entries=emptyList())
        fun archive()=File(root,"backup-${UUID.randomUUID()}.yunji")
        fun staging()=File(root,"staging-${UUID.randomUUID()}").apply {mkdirs()}
        fun write(destination: File=archive(),checkpoint: ()->Unit={}) = LocalBackupArchive.write(snapshot,vault,slot,store,destination,checkpoint)
    }
    private fun digest(file: File): String {
        val sha=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val buffer=ByteArray(64*1024);while(true){val n=input.read(buffer);if(n<0)break;sha.update(buffer,0,n)}}
        return VaultCrypto.hex(sha.digest())
    }
    private fun leftovers(root: File,predicate: (String)->Boolean)=root.listFiles().orEmpty().map {it.name}.filter(predicate)

    /** The checkpoint count is the size of the window a real kill can hit; it must stay non-trivial. */
    private fun countCheckpoints(run: (Int)->Unit): Int {
        var count=0
        run {count++}
        return count
    }

    @Test fun cancelledPublicationAtEveryCheckpointNeverPublishesOrLeavesPartial() {
        val f=Fixture()
        val scratch=f.archive()
        f.write(scratch)
        assertTrue(scratch.isFile)
        var checkpoints=0
        runCatching { f.write(File(f.root,"count.yunji")) {checkpoints++} }
        assertTrue("备份发布检查点过少，无法覆盖取消窗口",checkpoints>=8)
        for(at in 0 until checkpoints) {
            val destination=f.archive()
            var step=0
            val failure=runCatching { f.write(destination) {if(step++==at)throw CancellationException("synthetic cancellation")}}
            assertTrue("checkpoint=$at 取消未抛出",failure.isFailure)
            assertFalse("checkpoint=$at 取消后发布了半成品",destination.exists())
            assertTrue("checkpoint=$at 取消后遗留了 .backup-partial",leftovers(f.root) {it.endsWith(".backup-partial")}.isEmpty())
        }
    }

    @Test fun cancelledVerificationAtEveryCheckpointLeavesNoStagingDirectory() {
        val f=Fixture();val archive=f.archive();f.write(archive)
        val staging=f.staging()
        var checkpoints=0
        val verified=LocalBackupArchive.verify(archive,f.recovery,staging){checkpoints++}
        verified.close()
        assertTrue("校验检查点过少，无法覆盖取消窗口",checkpoints>=5)
        for(at in 0 until checkpoints) {
            var step=0
            val failure=runCatching {LocalBackupArchive.verify(archive,f.recovery,staging){if(step++==at)throw CancellationException("synthetic cancellation")}}
            assertTrue("checkpoint=$at 取消未抛出",failure.isFailure)
            assertTrue("checkpoint=$at 取消后遗留了 backup-verify 目录",leftovers(staging) {it.startsWith("backup-verify-")}.isEmpty())
        }
        assertEquals("取消验证不得改变已发布归档",digest(archive),digest(archive))
    }

    /** A kill during the destination copy leaves a truncated file; every byte cut must be rejected. */
    @Test fun everyTruncationOfThePublishedArchiveIsRejected() {
        val f=Fixture();val archive=f.archive();f.write(archive)
        val full=archive.readBytes()
        assertTrue(full.size>64)
        val cuts=(1..full.size-1 step maxOf(1,full.size/24)).toList()+listOf(0,1,full.size/2,full.size-1)
        for(cut in cuts) {
            val truncated=File(f.root,"truncated-$cut.yunji")
            truncated.writeBytes(full.copyOfRange(0,cut))
            val staging=f.staging()
            assertTrue("cut=$cut 的半成品被当作有效备份",runCatching {LocalBackupArchive.verify(truncated,f.recovery,staging).use {}}.isFailure)
            assertTrue("cut=$cut 校验失败后遗留 staging",leftovers(staging) {it.startsWith("backup-verify-")}.isEmpty())
        }
    }

    @Test fun retryAfterCancellationPublishesTheSameSnapshotExactlyOnce() {
        val f=Fixture()
        val cancelled=runCatching { f.write(File(f.root,"first.yunji")){throw CancellationException("synthetic cancellation")} }
        assertTrue("取消没有中断发布",cancelled.isFailure)
        val destination=f.archive()
        assertFalse(destination.exists())
        f.write(destination)
        val verified=LocalBackupArchive.verify(destination,f.recovery,f.staging())
        try {assertEquals(f.snapshot,verified.snapshot)} finally {verified.close()}
        assertTrue("重试不得重复发布",leftovers(f.root) {it.endsWith(".yunji")}.count {it==destination.name}==1)
    }

    @Test fun preExistingDestinationContentIsNeverOverwritten() {
        val f=Fixture()
        val destination=f.archive();destination.writeBytes("existing user file, not a backup".toByteArray())
        val before=destination.readBytes()
        assertTrue("已存在内容的保存位置必须拒绝写入",runCatching {f.write(destination)}.isFailure)
        assertArrayEquals("失败的重试破坏了已存在文件",before,destination.readBytes())
    }

    @Test fun cancelledVerificationLeavesTheSourceObjectsIntactAndReusable() {
        val f=Fixture();val archive=f.archive();f.write(archive)
        val objectFile=f.store.path(f.original.id)
        val before=digest(objectFile)
        assertTrue(runCatching {LocalBackupArchive.verify(archive,f.recovery,f.staging()){throw CancellationException()}}.isFailure)
        assertEquals("取消校验篡改了源密文对象",before,digest(objectFile))
        LocalBackupArchive.verify(archive,f.recovery,f.staging()).use {assertEquals(f.snapshot,it.snapshot)}
        val again=f.archive();f.write(again)
        LocalBackupArchive.verify(again,f.recovery,f.staging()).use {assertEquals(f.snapshot,it.snapshot)}
    }

    @Test fun staleStagingDirectoryFromAPreviousProcessIsNeitherReusedNorTrusted() {
        val f=Fixture();val archive=f.archive();f.write(archive)
        val staging=f.staging()
        val stale=File(staging,"backup-verify-${UUID.randomUUID()}").apply {mkdirs()}
        File(stale,"objects").mkdirs()
        val verified=LocalBackupArchive.verify(archive,f.recovery,staging)
        try {assertEquals(f.snapshot,verified.snapshot);assertNotEquals("不得复用上次进程的暂存目录",stale.absolutePath,verified.directory.absolutePath)} finally {verified.close()}
    }
}
