package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.security.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CloudCommitTest {
    private fun id()=UUID.randomUUID().toString()
    private val vault=VaultMaterial(id(),ByteArray(32){it.toByte()})
    private fun snapshot()=LogicalSnapshot(vaultId=vault.id,snapshotId=id(),writerId=id(),writerSequence=1,capturedSequence=3,createdAt=10)
    private fun rejects(block: ()->Unit) {try {block();fail("Invalid commit accepted")}catch(e: Exception){/* expected */}}
    @Test fun roundtripBindsExactManifestAndHidesBusinessMetadata() {
        val snapshot=snapshot();val manifest=BackupManifest.encode(snapshot,vault);val commit=CloudCommit.create(manifest,vault)
        assertEquals(snapshot,CloudCommit.verify(commit,snapshot.snapshotId,manifest,vault))
        val text=commit.toString(Charsets.UTF_8)
        for(value in listOf("writerId","capturedSequence","createdAt",snapshot.writerId))assertFalse(text.contains(value))
    }
    @Test fun checksumEditingCannotForgeCommit() {
        val snapshot=snapshot();val manifest=BackupManifest.encode(snapshot,vault);val commit=CloudCommit.create(manifest,vault)
        val other=BackupManifest.encode(snapshot.copy(capturedSequence=4),vault)
        val originalHash=VaultCrypto.sha256(manifest).joinToString(""){"%02x".format(it)}
        val otherHash=VaultCrypto.sha256(other).joinToString(""){"%02x".format(it)}
        val forged=commit.toString(Charsets.UTF_8).replace(originalHash,otherHash).replace("\"cipherSize\":${manifest.size}","\"cipherSize\":${other.size}").toByteArray()
        rejects {CloudCommit.verify(forged,snapshot.snapshotId,other,vault)}
    }
    @Test fun wrongVaultFilenameAndTruncationRejected() {
        val snapshot=snapshot();val manifest=BackupManifest.encode(snapshot,vault);val commit=CloudCommit.create(manifest,vault)
        rejects {CloudCommit.verify(commit,id(),manifest,vault)}
        rejects {CloudCommit.verify(commit,snapshot.snapshotId,manifest,VaultMaterial(id(),vault.master.clone()))}
        rejects {CloudCommit.verify(commit.copyOf(commit.size-1),snapshot.snapshotId,manifest,vault)}
        rejects {CloudCommit.verify(commit,snapshot.snapshotId,manifest.copyOf(manifest.size-1),vault)}
    }
    @Test fun invalidSequenceAndOversizedOrDeepEntryRejected() {
        val snapshot=snapshot();val manifest=BackupManifest.encode(snapshot,vault)
        rejects {CloudCommit.verify(ByteArray(CloudCommit.MAX_BYTES+1),snapshot.snapshotId,manifest,vault)}
        rejects {CloudCommit.verify(("[".repeat(100)+"]".repeat(100)).toByteArray(),snapshot.snapshotId,manifest,vault)}
        rejects {CloudCommit.create(BackupManifest.encode(snapshot.copy(writerSequence=0),vault),vault)}
    }
}