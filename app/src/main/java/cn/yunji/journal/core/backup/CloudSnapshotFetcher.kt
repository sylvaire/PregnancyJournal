package cn.yunji.journal.core.backup

import cn.yunji.journal.core.network.DavException
import cn.yunji.journal.core.network.WebDavClient
import cn.yunji.journal.core.security.VaultMaterial
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A snapshot pulled from the cloud, materialised as a local `.yunji` archive ready for the restore wizard. */
class FetchedSnapshot(
    val archive: File,
    val snapshotId: String,
    val createdAt: Long,
    val capturedSequence: Long,
    val documents: Int,
) {
    override fun toString() = "FetchedSnapshot(snapshot=$snapshotId, archive=<redacted>)"
}

/**
 * Downloads a committed remote snapshot and rebuilds it as a local encrypted archive.
 *
 * Why rebuild an archive instead of restoring directly: the existing restore path (verify → isolated import
 * → journaled switch) already validates every entry, and feeding it the same container keeps one set of
 * guarantees. Nothing is trusted from the network: the commit is authenticated against the manifest, every
 * object is length- and hash-checked while streaming, and the assembled archive is verified with the user's
 * recovery key before it is handed on.
 */
class CloudSnapshotFetcher(
    private val client: WebDavClient,
    private val folder: List<String>,
    private val vault: VaultMaterial,
    private val checkpoint: () -> Unit = {},
) {
    private val maintenance = CloudMaintenance(client, folder, vault, checkpoint)

    private fun isUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    /** Snapshots the cloud holds that this device can authenticate, newest first. */
    fun listRestorable(slot: RecoverySlot): List<SnapshotRef> =
        maintenance.collect(slot).verifiedSnapshots.sortedWith(
            compareByDescending<SnapshotRef> { it.capturedSequence }.thenByDescending { it.createdAt })

    /**
     * A remote vault whose recovery wrapper the supplied recovery key opens. This is what makes restoring
     * onto a *new* device possible: the local installation has a different vault id, so the remote slot —
     * not any local state — is what identifies the backup to restore.
     */
    class CloudVaultCandidate internal constructor(
        val slot: RecoverySlot,
        val material: VaultMaterial,
        val directoryName: String,
    ) {
        override fun toString() = "CloudVaultCandidate(vault=${slot.vaultId}, directory=$directoryName)"
    }

    /**
     * Lists remote vault directories and returns those the recovery key can open. A candidate holds a
     * decrypted master key, so the caller must keep it only for the duration of the restore.
     */
    fun discover(recoveryKey: String): List<CloudVaultCandidate> {
        require(recoveryKey.isNotBlank()) { "请填写恢复密钥" }
        // Validate the key format up front: a mistyped key must say so, not report "no backups found".
        cn.yunji.journal.core.security.RecoveryKey.parse(recoveryKey).fill(0)
        checkpoint()
        val container = folder + "pregnancy-journal"
        val entries = try { client.list(container) } catch (e: DavException) {
            if (e.status == 404) return emptyList() else throw e
        }
        require(entries.all { it.status in 200..299 }) { "云端目录不完整，已停止恢复" }
        val directories = entries.filter { it.collection }.mapNotNull { entry ->
            val name = entry.segments.lastOrNull() ?: return@mapNotNull null
            name.takeIf(::isUuid)
        }.distinct()
        val candidates = mutableListOf<CloudVaultCandidate>()
        directories.forEach { name ->
            checkpoint()
            val stored = try { client.get(container + listOf(name, "vault.json"), 4096) }
            catch (e: DavException) { if (e.status == 404) null else throw e } ?: return@forEach
            val slot = runCatching { RecoverySlots.decode(stored) }.getOrNull() ?: return@forEach
            if (slot.vaultId != name) return@forEach
            val material = runCatching { RecoverySlots.open(slot, recoveryKey) }.getOrNull() ?: return@forEach
            candidates += CloudVaultCandidate(slot, material, name)
        }
        // Deterministic order so the wizard shows a stable list.
        return candidates.sortedBy { it.slot.vaultId }
    }

    fun listRestorable(candidate: CloudVaultCandidate): List<SnapshotRef> = maintenanceFor(candidate)
        .collect(candidate.slot).verifiedSnapshots
        .sortedWith(compareByDescending<SnapshotRef> { it.capturedSequence }.thenByDescending { it.createdAt })

    private fun maintenanceFor(candidate: CloudVaultCandidate) =
        CloudMaintenance(client, folder, candidate.material, checkpoint)

    /** Restores flow for a remote vault discovered with the recovery key (new device). */
    fun download(candidate: CloudVaultCandidate, snapshotId: String, recoveryKey: String, workDirectory: File): FetchedSnapshot =
        downloadAt(candidate, maintenanceFor(candidate), candidate.slot, snapshotId, recoveryKey, workDirectory)

    /**
     * @param workDirectory private staging area; the caller removes it after the restore finishes.
     * @return the rebuilt archive plus the counts needed for a preview.
     */
    fun download(slot: RecoverySlot, snapshotId: String, recoveryKey: String, workDirectory: File): FetchedSnapshot {
        val service = maintenance
        return downloadAt(null, service, slot, snapshotId, recoveryKey, workDirectory)
    }

    private fun downloadAt(candidate: CloudVaultCandidate?, service: CloudMaintenance, slot: RecoverySlot,
        snapshotId: String, recoveryKey: String, workDirectory: File): FetchedSnapshot {
        require(isUuid(snapshotId)) { "云端口令无效" }
        require(recoveryKey.isNotBlank()) { "请填写恢复密钥" }
        val material = candidate?.material ?: vault
        val root = service.resolveRoot(slot)
        val commit = read(root, listOf("commits", "$snapshotId.json"), CloudCommit.MAX_BYTES)
            ?: throw IllegalStateException("云端提交不存在，可能已被清理")
        val manifest = read(root, listOf("snapshots", "$snapshotId.bin"), MAX_MANIFEST_BYTES)
            ?: throw IllegalStateException("云端快照内容缺失，无法恢复")
        // Authenticates the manifest with the vault key and binds it to this exact file name.
        val snapshot = CloudCommit.verify(commit, snapshotId, manifest, material)
        require(snapshot.vaultId == slot.vaultId) { "云端快照属于其他资料库" }

        val staging = File(workDirectory, "cloud-$snapshotId").apply {
            require(!exists() && mkdirs()) { "无法创建下载暂存目录" }
        }
        val objectsDirectory = File(staging, "objects").apply { mkdirs() }
        val objects = BackupManifest.objects(snapshot)
        try {
            objects.forEach { info ->
                checkpoint()
                val target = File(objectsDirectory, "${info.id}.bin")
                client.downloadTo(root + listOf("objects", "${info.id}.bin"), target, info.cipherSize, info.cipherSha256, checkpoint)
            }
            val archive = File(workDirectory, "cloud-$snapshotId.yunji")
            require(!archive.exists()) { "下载目标已存在" }
            writeArchive(archive, slot, manifest, objectsDirectory, objects)
            // Verify locally with the user's key before the restore wizard ever sees it.
            LocalBackupArchive.verify(archive, recoveryKey, staging).use { verified ->
                require(verified.snapshot.snapshotId == snapshot.snapshotId) { "下载内容与云端提交不一致" }
            }
            return FetchedSnapshot(archive, snapshot.snapshotId, snapshot.createdAt,
                snapshot.capturedSequence, snapshot.attachments.size)
        } catch (e: Throwable) {
            File(workDirectory, "cloud-$snapshotId.yunji").delete()
            throw e
        } finally {
            objectsDirectory.deleteRecursively()
        }
    }

    private fun read(root: List<String>, path: List<String>, limit: Int): ByteArray? =
        try { client.get(root + path, limit) } catch (e: DavException) { if (e.status == 404) null else throw e }

    private fun writeArchive(archive: File, slot: RecoverySlot, manifest: ByteArray,
        objectsDirectory: File, objects: List<cn.yunji.journal.core.storage.StoredObject>) {
        java.io.FileOutputStream(archive).use { file ->
            // The ZIP stream must not close the file: durability is enforced below with fd.sync().
            val wrapping = object : java.io.FilterOutputStream(file) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
                override fun close() { flush() }
            }
            ZipOutputStream(wrapping).use { zip ->
                fun entry(name: String, body: (java.io.OutputStream) -> Unit) {
                    checkpoint()
                    zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                    body(zip)
                    zip.closeEntry()
                }
                entry("vault.json") { it.write(RecoverySlots.encode(slot)) }
                entry("manifest.enc") { it.write(manifest) }
                objects.forEach { info ->
                    val source = File(objectsDirectory, "${info.id}.bin")
                    require(source.isFile && source.length() == info.cipherSize) { "下载原件不完整" }
                    entry("objects/${info.id}.bin") { out -> source.inputStream().use { it.copyTo(out) } }
                }
            }
            file.flush()
            file.fd.sync()
        }
    }

    private companion object {
        /** Matches the transport's single-read ceiling; a manifest is metadata and far smaller. */
        const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
    }
}
