package cn.yunji.journal.core.backup

import cn.yunji.journal.core.security.VaultMaterial
import cn.yunji.journal.core.security.VaultCrypto
import java.io.File

interface CloudBackupRemote {
    fun prepare(slot: RecoverySlot)
    fun uploadVerified(path: List<String>,file: File,size: Long,sha256: String)
    fun readCommit(snapshotId: String): ByteArray?
    fun publishCommit(snapshotId: String,bytes: ByteArray)
    /** Discovery of already published commits. Empty means "cannot enumerate", never "nothing exists". */
    fun listCommitIds(): List<String>
}
data class CloudBackupReceipt(val snapshotId: String,val writerId: String,val writerSequence: Long,val capturedSequence: Long)

/** Synchronous IO engine. Caller must keep the captured object lease and vault session alive throughout. */
class CloudBackupUpload(private val remote: CloudBackupRemote) {
    fun upload(manifestFile: File,vault: VaultMaterial,slot: RecoverySlot,objectFile: (String)->File,
        checkpoint: ()->Unit={},phase: (String)->Unit={}): CloudBackupReceipt {
        checkpoint();require(slot.vaultId==vault.id)
        require(manifestFile.length() in 32..BackupManifest.MAX_BYTES.toLong()+32)
        val manifest=manifestFile.readBytes()
        val commit=CloudCommit.create(manifest,vault)
        val snapshot=CloudCommit.verify(commit,BackupManifest.decode(manifest,vault).snapshotId,manifest,vault)
        val objects=BackupManifest.objects(snapshot)
        // Reject missing sources before creating any remote data; caller owns prior GCM/self-restore validation.
        val sources=objects.associate {item->item.id to objectFile(item.id).also {require(it.isFile && it.length()==item.cipherSize)}}
        phase("CHECKING_DESTINATION");remote.prepare(slot);checkpoint()
        phase("UPLOADING_OBJECTS")
        objects.forEach {item->
            checkpoint();remote.uploadVerified(listOf("objects","${item.id}.bin"),requireNotNull(sources[item.id]),item.cipherSize,item.cipherSha256)
        }
        checkpoint();phase("VERIFYING")
        val snapshotPath=listOf("snapshots","${snapshot.snapshotId}.bin")
        val sha=VaultCrypto.sha256(manifest).joinToString(""){"%02x".format(it)}
        remote.uploadVerified(snapshotPath,manifestFile,manifest.size.toLong(),sha)
        checkpoint();phase("COMMITTING")
        // Retries reuse a valid existing commit. A damaged/conflicting entry is never overwritten.
        val existing=remote.readCommit(snapshot.snapshotId)
        if(existing==null)remote.publishCommit(snapshot.snapshotId,commit)
        checkpoint()
        val readback=requireNotNull(remote.readCommit(snapshot.snapshotId)) {"云端提交回读缺失"}
        val verified=CloudCommit.verify(readback,snapshot.snapshotId,manifest,vault)
        checkpoint()
        return CloudBackupReceipt(verified.snapshotId,verified.writerId,verified.writerSequence,verified.capturedSequence)
    }
}