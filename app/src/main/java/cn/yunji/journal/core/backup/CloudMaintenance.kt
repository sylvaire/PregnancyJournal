package cn.yunji.journal.core.backup

import cn.yunji.journal.core.network.DavException
import cn.yunji.journal.core.network.WebDavClient
import cn.yunji.journal.core.security.VaultMaterial
import java.util.UUID

/** Verified remote inventory plus everything the planner needs to decide safely. */
data class CleanupInventory(
    val inventory: RemoteInventory,
    val verifiedSnapshots: List<SnapshotRef>,
    val knownWriterIds: Set<String>,
)

/**
 * Reads and mutates the remote backup directory for retention cleanup.
 *
 * Safety rules:
 * - cleanup never provisions a new backup location: identity and directories must already exist;
 * - every listed commit must authenticate against its manifest before any deletion is planned;
 * - an unreadable commit or an incomplete listing marks the inventory incomplete, which stops cleanup;
 * - deletion touches only canonical `commits/<uuid>.json` and `objects/<uuid>.bin` paths inside the vault root.
 */
class CloudMaintenance(
    private val client: WebDavClient,
    private val folder: List<String>,
    private val vault: VaultMaterial,
    private val checkpoint: () -> Unit = {},
) {
    private fun isUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    /** Resolves the vault root and requires the existing layout. Missing directories stop cleanup. */
    private fun vaultRoot(slot: RecoverySlot): List<String> = resolveRoot(slot)

    /** Shared with the download path so both agree on identity and layout requirements. */
    internal fun resolveRoot(slot: RecoverySlot): List<String> {
        checkpoint()
        val container = folder + "pregnancy-journal"
        val root = container + slot.vaultId
        val entries = client.list(container)
        require(entries.all { it.status in 200..299 }) { "云端目录不完整，已停止自动清理" }
        require(entries.any { it.segments == root && it.collection }) { "云端备份目录不存在，已停止自动清理" }
        val stored = try { client.get(root + "vault.json", 4096) } catch (e: DavException) { if (e.status == 404) null else throw e }
        requireNotNull(stored) { "云端备份目录缺少身份文件，已停止自动清理" }
        require(RecoverySlots.decode(stored) == slot) { "云端身份或恢复封装不一致，已停止自动清理" }
        val inner = client.list(root)
        require(inner.all { it.status in 200..299 }) { "云端目录不完整，已停止自动清理" }
        listOf("objects", "snapshots", "commits").forEach { name ->
            require(inner.any { it.segments == root + name && it.collection }) { "云端目录结构不完整，已停止自动清理" }
        }
        return root
    }

    fun collect(slot: RecoverySlot): CleanupInventory {
        val root = vaultRoot(slot)
        var complete = true
        val commitIds = linkedSetOf<String>()
        runCatching { listNames(root + "commits", ".json") }
            .onSuccess { commitIds += it }
            .onFailure { complete = false }

        val verified = mutableListOf<SnapshotRef>()
        val writers = mutableSetOf<String>()
        commitIds.forEach { id ->
            checkpoint()
            val snapshot = readVerified(root, id) ?: run { complete = false; null } ?: return@forEach
            writers += snapshot.writerId
            verified += SnapshotRef(snapshot.snapshotId, snapshot.createdAt, snapshot.writerId,
                snapshot.capturedSequence, BackupManifest.objects(snapshot).map { it.id }.toSet())
        }

        val objects = runCatching {
            client.list(root + "objects").also { require(it.all { entry -> entry.status in 200..299 }) }
                .filter { !it.collection }
                .mapNotNull { entry ->
                    val name = entry.segments.lastOrNull() ?: return@mapNotNull null
                    if (!name.endsWith(".bin")) return@mapNotNull null
                    val id = name.removeSuffix(".bin")
                    if (!isUuid(id)) return@mapNotNull null
                    // Unknown age is represented as 0 so age-based rules never treat it as old.
                    RemoteObject(id, entry.lastModified ?: 0L)
                }
        }.getOrElse { complete = false; emptyList() }

        return CleanupInventory(RemoteInventory(commitIds, objects, complete), verified, writers)
    }

    private fun listNames(directory: List<String>, suffix: String): Set<String> {
        val entries = client.list(directory)
        require(entries.all { it.status in 200..299 }) { "云端目录不完整，已停止自动清理" }
        return entries.filter { !it.collection }.mapNotNull { entry ->
            val name = entry.segments.lastOrNull() ?: return@mapNotNull null
            if (!name.endsWith(suffix)) return@mapNotNull null
            name.removeSuffix(suffix).takeIf(::isUuid)
        }.toSet()
    }

    private fun readVerified(root: List<String>, snapshotId: String): LogicalSnapshot? {
        val commit = try { client.get(root + listOf("commits", "$snapshotId.json"), CloudCommit.MAX_BYTES) }
        catch (e: DavException) { if (e.status == 404) return null else throw e }
        // The client caps a single buffered read at 16 MiB; a manifest is encrypted metadata and is far smaller.
        val manifest = try { client.get(root + listOf("snapshots", "$snapshotId.bin"), MAX_MANIFEST_READ) }
        catch (e: DavException) { if (e.status == 404) return null else throw e }
        return runCatching { CloudCommit.verify(commit, snapshotId, manifest, vault) }.getOrNull()
    }

    /** Deletes commits first, then only the objects named by a plan (already past their grace period). */
    fun execute(slot: RecoverySlot, plan: CleanupPlan) {
        val root = vaultRoot(slot)
        plan.commitsToDelete.forEach { id ->
            require(isUuid(id)) { "拒绝删除非规范提交路径" }
            deleteOne(root + listOf("commits", "$id.json"))
        }
        plan.objectsToDelete.forEach { id ->
            require(isUuid(id)) { "拒绝删除非规范原件路径" }
            deleteOne(root + listOf("objects", "$id.bin"))
        }
    }

    private fun deleteOne(path: List<String>) {
        checkpoint()
        try { client.delete(path) } catch (e: DavException) { if (e.status != 404) throw e }
    }

    private companion object {
        /** Matches the transport's single-read ceiling; manifests are metadata and far smaller in practice. */
        const val MAX_MANIFEST_READ = 16 * 1024 * 1024
    }
}
