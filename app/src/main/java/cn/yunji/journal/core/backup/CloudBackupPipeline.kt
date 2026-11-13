package cn.yunji.journal.core.backup

import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.core.security.VaultMaterial
import cn.yunji.journal.core.storage.EncryptedFileStore
import java.io.File
import java.util.UUID

/** Supplies a consistent, leased snapshot. The lease must span archive production and upload. */
interface SnapshotSource {
    suspend fun <T> withSnapshot(writerId: String, writerSequence: Long, createdAt: Long, block: suspend (LogicalSnapshot) -> T): T
}

data class CloudPipelineResult(
    val receipt: CloudBackupReceipt,
    val manifestFile: File,
    val writerSequence: Long,
)

/**
 * Ordered cloud backup pipeline, independent of Android: reserve a durable writer sequence, capture a
 * leased snapshot, build and self-check the archive locally, then upload immutable objects and publish
 * the authenticated commit. A local self-check failure never reaches the network.
 */
class CloudBackupPipeline(
    private val writerDir: File,
    private val workRoot: File,
    private val material: VaultMaterial,
    private val store: EncryptedFileStore,
    private val source: SnapshotSource,
    private val syncDirectory: (File) -> Unit,
    private val clockMillis: () -> Long,
) {
    /**
     * @param uploader performs the remote work; it receives the job directory and the manifest plus a
     * provider of staged ciphertext files, and must return a verified receipt.
     */
    fun run(
        slot: RecoverySlot,
        onPhase: (String) -> Unit = {},
        checkpoint: ()->Unit = {},
        uploader: (job: File, manifest: File, snapshot: LogicalSnapshot, staged: (String) -> File, phase: (String) -> Unit) -> CloudBackupReceipt,
    ): CloudPipelineResult = kotlinx.coroutines.runBlocking {
        checkpoint()
        require(slot.vaultId == material.id) { "恢复封装与资料库不匹配" }
        val writer = CloudWriterStore(writerDir, material, syncDirectory)
        val reservation = writer.reserve()
        onPhase("SEQUENCE_RESERVED")
        val job = File(workRoot, "job-${UUID.randomUUID()}")
        check(job.mkdirs()) { "无法创建备份临时目录" }
        try {
            var result: CloudPipelineResult? = null
            source.withSnapshot(reservation.writerId, reservation.sequence, clockMillis()) { snapshot ->
                require(snapshot.vaultId == material.id)
                require(snapshot.writerSequence == reservation.sequence) { "快照序号与预留不一致" }
                val manifestFile = File(job, "manifest.enc")
                manifestFile.writeBytes(BackupManifest.encode(snapshot, material))
                checkpoint();onPhase("ARCHIVE_BUILDING")
                val archive = File(job, "selfcheck.yunji")
                LocalBackupArchive.write(snapshot, material, slot, store, archive,checkpoint)
                LocalBackupArchive.verifyWithVault(archive, material, slot, job,checkpoint).use { verified ->
                    require(verified.snapshot == snapshot) { "自检结果与快照不一致" }
                }
                onPhase("SELF_CHECKED")
                archive.delete()
                val objects = BackupManifest.objects(snapshot)
                require(objects.all { store.path(it.id).isFile }) { "备份原件不完整，请先检查资料" }
                val staged = objects.associate { info ->
                    val copy = java.io.File(job, "${info.id}.bin")
                    store.path(info.id).inputStream().use {input->copy.outputStream().use {output->
                        val buffer=ByteArray(64*1024)
                        while(true){checkpoint();val count=input.read(buffer);if(count<0)break;output.write(buffer,0,count)}
                    }}
                    require(copy.length() == info.cipherSize) { "备份原件长度不匹配" }
                    var digest: String? = null
                    java.security.DigestInputStream(copy.inputStream(), java.security.MessageDigest.getInstance("SHA-256")).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (input.read(buffer) >= 0) checkpoint()
                        digest = VaultCrypto.hex(input.messageDigest.digest())
                    }
                    require(digest == info.cipherSha256) { "备份原件密文校验失败" }
                    info.id to copy
                }
                checkpoint()
                val receipt = uploader(job, manifestFile, snapshot, { id -> requireNotNull(staged[id]) { "缺少待上传原件" } }, onPhase)
                checkpoint()
                result = CloudPipelineResult(receipt, manifestFile, reservation.sequence)
            }
            requireNotNull(result)
        } finally {
            job.deleteRecursively()
        }
    }
}
