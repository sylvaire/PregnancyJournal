package cn.yunji.journal.data

import android.content.Context
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.network.CloudConnection
import cn.yunji.journal.core.network.DavBackupRemote
import cn.yunji.journal.core.network.WebDavClient
import cn.yunji.journal.core.security.VaultMaterial
import cn.yunji.journal.core.storage.EncryptedFileStore
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

/** Result of one cloud upload attempt. A success receipt exists only after an authenticated commit readback. */
data class CloudUploadOutcome(val success: Boolean, val message: String, val status: CloudUploadStatus)

/**
 * Android adapter over [CloudBackupPipeline]: supplies the leased live snapshot and the WebDAV uploader,
 * and records the device-local attempt/success status.
 */
class CloudBackupCoordinator(
    private val context: Context,
    private val material: VaultMaterial,
    private val importer: DocumentImporter,
    private val connection: CloudConnection,
    private val settings: RecoverySettings,
    private val status: CloudUploadStatusStore,
    private val clock: Clock,
    private val clientFactory: (CloudConnection) -> WebDavClient = { WebDavClient(it.endpoint.toHttpUrl(), it.username, it.password) },
    private val syncDirectory: (File) -> Unit = ::systemSyncDirectory,
    private val scheduler: CloudSchedulerStore? = null,
) {
    private val cancelled=AtomicBoolean(false)
    private val liveClient=java.util.concurrent.atomic.AtomicReference<WebDavClient?>()
    fun cancel() {cancelled.set(true);liveClient.get()?.close()}
    private fun checkpoint() {if(cancelled.get())throw CancellationException("备份已取消")}
    private val running = AtomicBoolean(false)
    val isRunning get() = running.get()
    private val writerDir = File(VaultLayout(File(context.noBackupFilesDir,"vault")).activeDirectory(),"backup-writer").apply { mkdirs() }
    private val workRoot = File(context.noBackupFilesDir, "vault/cloud-work").apply { mkdirs() }

    fun run(progress: (String) -> Unit = {}): CloudUploadOutcome = synchronized(this) {
        require(running.compareAndSet(false, true)) { "已有备份任务在进行" }
        val startedAt = clock.millis()
        status.beginAttempt(startedAt)
        try {
            checkpoint()
            val slot = requireNotNull(settings.load()) { "请先保存并核对恢复密钥" }
            val store = EncryptedFileStore(importer.objectDirectory, material)
            val pipeline = CloudBackupPipeline(writerDir, workRoot, material, store,
                object : SnapshotSource {
                    override suspend fun <T> withSnapshot(writerId: String, writerSequence: Long, createdAt: Long,
                        block: suspend (LogicalSnapshot) -> T): T =
                        importer.objectProtection.withSnapshot(material.id, writerId, writerSequence, createdAt) { block(it) }
                }, syncDirectory, clock::millis)
            val result = pipeline.run(slot, checkpoint=::checkpoint, onPhase = { phase -> status.phase(phase); progress(describe(phase)) }) { job, manifest, snapshot, staged, phase ->
                upload(job, manifest, snapshot, slot, staged, phase)
            }
            checkpoint()
            status.succeed(clock.millis(), result.receipt.snapshotId, result.receipt.writerSequence, result.receipt.capturedSequence)
            scheduler?.let {
                it.clearFailures()
                it.setLastBackedUp(result.receipt.capturedSequence)
                it.setNextAttemptAt(0)
            }
            runCatching { BackupFailureNotifier(context).clear() }
            progress("备份已提交")
            CloudUploadOutcome(true, "云端备份已提交并回读验证", status.load())
        } catch (e: CancellationException) {
            status.fail(clock.millis(), "备份已取消")
            throw e
        } catch (e: Exception) {
            if(cancelled.get()){status.fail(clock.millis(),"备份已取消");throw CancellationException("备份已取消")}
            checkpoint()
            status.fail(clock.millis(), e.message ?: "云端备份未完成")
            CloudUploadOutcome(false, e.message ?: "云端备份未完成，请稍后重试", status.load())
        } finally {
            running.set(false)
        }
    }

    private fun upload(job: File, manifest: File, snapshot: LogicalSnapshot, slot: RecoverySlot,
                       staged: (String) -> File, phase: (String) -> Unit): CloudBackupReceipt {
        checkpoint()
        val client = clientFactory(connection)
        liveClient.set(client)
        try {
            checkpoint()
            val remote = DavBackupRemote(client, connection.folder,::checkpoint)
            phase("CHECKING_DESTINATION")
            remote.prepare(slot)
            // Per-writer branches are preserved, but a second device in the same directory means this
            // device must stop auto-writing until the user confirms which one is primary.
            val writers = remote.verifiedCommitWriterIds(material)
            val unconfirmed = CloudBackupPolicy.unconfirmedForeignCommits(snapshot.writerId, writers,
                scheduler?.load()?.acknowledgedForeignCommitIds.orEmpty())
            if (unconfirmed.isNotEmpty()) {
                scheduler?.blockForOtherWriter(clock.millis(), "检测到其他设备提交的备份快照", unconfirmed)
                throw IllegalStateException("检测到其他设备的新备份，已暂停本机自动备份；请确认主设备后再上传")
            }
            return CloudBackupUpload(remote).upload(manifest, material, slot, staged,::checkpoint) { phase(it) }
        } finally { liveClient.compareAndSet(client,null);client.close() }
    }

    private fun describe(phase: String) = when (phase) {
        "SEQUENCE_RESERVED", "CAPTURED" -> "正在准备备份序号"
        "ARCHIVE_BUILDING" -> "正在制作加密快照"
        "SELF_CHECKED" -> "正在自检加密快照"
        "CHECKING_DESTINATION" -> "正在核对云端目录"
        "UPLOADING_OBJECTS" -> "正在上传原件"
        "VERIFYING" -> "正在回读校验"
        "COMMITTING" -> "正在发布提交"
        else -> "正在备份"
    }

    companion object {
        /** Directory fsync for the writer state; a failure must abort before any sequence is issued. */
        internal fun systemSyncDirectory(directory: File) {
            val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
            try {
                val stat = android.system.Os.fstat(fd)
                require(android.system.OsConstants.S_ISDIR(stat.st_mode)) { "备份状态目录不可用" }
                android.system.Os.fsync(fd)
            } finally { android.system.Os.close(fd) }
        }

        internal fun recover(context: Context) {
            File(context.noBackupFilesDir, "vault/cloud-work").listFiles().orEmpty().forEach {
                if (it.isDirectory) it.deleteRecursively()
            }
        }
    }
}
