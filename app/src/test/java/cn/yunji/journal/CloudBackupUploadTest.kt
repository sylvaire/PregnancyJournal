package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.security.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class CloudBackupUploadTest {
    private class Remote: CloudBackupRemote {
        var prepared=false;var manifestVerified=false;var commit: ByteArray?=null
        var failUpload=false;var corruptReadback=false;var publications=0
        override fun prepare(slot: RecoverySlot){prepared=true}
        override fun uploadVerified(path: List<String>,file: File,size: Long,sha256: String) {
            check(prepared);if(failUpload)throw java.io.IOException("Injected upload failure")
            assertEquals(size,file.length());assertEquals(sha256,VaultCrypto.sha256(file.readBytes()).joinToString(""){"%02x".format(it)})
            manifestVerified=true
        }
        override fun readCommit(snapshotId: String)=commit?.let {if(corruptReadback)it.copyOf(it.size-1) else it.clone()}
        override fun listCommitIds(): List<String> = commit?.let { listOf("published") } ?: emptyList()
        override fun publishCommit(snapshotId: String,bytes: ByteArray){check(manifestVerified);check(commit==null);publications++;commit=bytes.clone()}
    }
    private fun fixture(block: (File,VaultMaterial,RecoverySlot,LogicalSnapshot)->Unit) {
        val directory=Files.createTempDirectory("cloud-upload").toFile()
        val vault=VaultMaterial(UUID.randomUUID().toString(),ByteArray(32){(it+1).toByte()})
        val snapshot=LogicalSnapshot(vaultId=vault.id,snapshotId=UUID.randomUUID().toString(),writerId=UUID.randomUUID().toString(),writerSequence=7,capturedSequence=12,createdAt=100)
        val manifest=File(directory,"manifest.enc");manifest.writeBytes(BackupManifest.encode(snapshot,vault))
        try {block(manifest,vault,RecoverySlots.create(vault,RecoveryKey.create()),snapshot)}finally {vault.master.fill(0);directory.deleteRecursively()}
    }
    @Test fun successfulReceiptUsesCapturedSequenceAndCommitIsLast()=fixture {file,vault,slot,snapshot->
        val remote=Remote();val phases=mutableListOf<String>()
        val receipt=CloudBackupUpload(remote).upload(file,vault,slot,{error("No objects expected")},phase=phases::add)
        assertEquals(snapshot.snapshotId,receipt.snapshotId);assertEquals(12,receipt.capturedSequence)
        assertEquals(listOf("CHECKING_DESTINATION","UPLOADING_OBJECTS","VERIFYING","COMMITTING"),phases)
        assertEquals(1,remote.publications)
    }
    @Test fun failedManifestUploadNeverPublishesCommit()=fixture {file,vault,slot,_->
        val remote=Remote().apply {failUpload=true}
        assertTrue(runCatching {CloudBackupUpload(remote).upload(file,vault,slot,{error("none")})}.isFailure)
        assertEquals(0,remote.publications);assertNull(remote.commit)
    }
    @Test fun corruptCommitReadbackCannotProduceSuccessReceipt()=fixture {file,vault,slot,_->
        val remote=Remote().apply {corruptReadback=true}
        assertTrue(runCatching {CloudBackupUpload(remote).upload(file,vault,slot,{error("none")})}.isFailure)
        assertEquals(1,remote.publications)
    }
    @Test fun retryValidCommitIsReusedAndInvalidExistingCommitIsPreserved()=fixture {file,vault,slot,_->
        val remote=Remote();val engine=CloudBackupUpload(remote)
        val first=engine.upload(file,vault,slot,{error("none")})
        assertEquals(first,engine.upload(file,vault,slot,{error("none")}));assertEquals(1,remote.publications)
        remote.commit=byteArrayOf(1,2,3);val before=remote.commit!!.clone()
        assertTrue(runCatching {engine.upload(file,vault,slot,{error("none")})}.isFailure)
        assertArrayEquals(before,remote.commit);assertEquals(1,remote.publications)
    }
    @Test fun cancellationBeforeCommitLeavesNoDiscoverableCommit()=fixture {file,vault,slot,_->
        val remote=Remote();var committing=false
        assertTrue(runCatching {CloudBackupUpload(remote).upload(file,vault,slot,{error("none")},
            checkpoint={if(committing)throw java.util.concurrent.CancellationException()},phase={if(it=="VERIFYING")committing=true})}.isFailure)
        assertEquals(0,remote.publications)
    }
}