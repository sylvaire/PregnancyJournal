package cn.yunji.journal.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class VaultPointer(
    val format: Int = 1,
    /** Null means the legacy single-file layout `vault/journal.db`. */
    val activeId: String? = null,
    val previousId: String? = null,
    val updatedAt: Long = 0,
) {
    override fun toString() = "VaultPointer(active=$activeId, previous=$previousId)"
}

/**
 * Generation layout and the atomic active-generation pointer.
 *
 * Layout:
 * - legacy: `vault/journal.db` (activeId == null)
 * - generation: `vault/gen/<id>/journal.db`
 * - legacy objects live in `vault/objects`; restored generations own `gen/<id>/objects`.
 *
 * Only random identifiers appear here, and the database itself is encrypted with the vault key, so a
 * tampered pointer can at worst make the app report an unreadable vault, never expose another vault.
 */
class VaultLayout(private val vaultDir: File,
    private val syncDirectory: (File) -> Unit = { dir -> syncDirectoryDurably(dir) }) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val pointerFile = File(vaultDir, "active-generation.json")
    private val genRoot = File(vaultDir, "gen")

    fun legacyDatabase(): File = File(vaultDir, "journal.db")
    fun generationDir(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "恢复代际标识无效" }
        return File(genRoot, id)
    }
    fun generationDatabase(id: String): File = File(generationDir(id), "journal.db")
    fun newGenerationId(): String = UUID.randomUUID().toString()

    fun readPointer(): VaultPointer? {
        if (!pointerFile.exists()) return null
        require(pointerFile.length() in 1..4096) { "代际指针文件损坏" }
        val pointer = runCatching { json.decodeFromString<VaultPointer>(pointerFile.readText(Charsets.UTF_8)) }
            .getOrElse { throw IllegalStateException("代际指针文件损坏，原始资料已保留") }
        require(pointer.format == 1) { "代际指针格式不兼容" }
        pointer.activeId?.let { require(runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)) { "代际指针无效" } }
        pointer.previousId?.let { require(runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)) { "代际指针无效" } }
        return pointer
    }

    /** The database this installation must open. Throws rather than silently creating an empty vault. */
    fun activeDatabase(): File {
        val pointer = readPointer() ?: return legacyDatabase()
        val id = pointer.activeId ?: return legacyDatabase()
        val file = generationDatabase(id)
        require(file.isFile) { "当前资料代际缺失，请使用恢复向导或联系维护人员" }
        return file
    }

    /** Resolve once per resource session, alongside activeDatabase. Never mix two generations. */
    fun activeObjects(): File {
        val id=activeGenerationId() ?: return File(vaultDir,"objects")
        return File(generationDir(id),"objects").also {require(it.isDirectory) {"当前资料代际的原件目录缺失，原始资料已保留"}}
    }

    fun activeDirectory(): File=activeGenerationId()?.let(::generationDir) ?: vaultDir
    fun activeGenerationId(): String? = readPointer()?.activeId

    /** Atomic pointer switch. Callers must have persisted their journal first. */
    @Synchronized
    fun writePointer(pointer: VaultPointer) {
        pointer.activeId?.let { generationDir(it) }
        pointer.previousId?.let { generationDir(it) }
        val text = json.encodeToString(pointer)
        val temp = File(vaultDir, "active-generation.pending")
        try {
            RandomAccessFile(temp, "rw").use { out -> out.setLength(0); out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
            Files.move(temp.toPath(), pointerFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        syncDirectory(vaultDir)
    }

    fun deleteGeneration(id: String) {
        val dir = generationDir(id)
        if (dir.exists()) check(dir.deleteRecursively()) { "暂时无法清理旧代际" }
    }

    /**
     * True when a restore already switched this vault to a generation but the pre-generation
     * ("legacy") database/object files are still present. They belong to the replaced data and are
     * never referenced by the active generation, so an explicit reclaim may remove them.
     */
    fun legacyLeftovers(): Boolean {
        if (activeGenerationId() == null) return false
        if (legacyDatabase().exists()) return true
        if (File(vaultDir, LEGACY_DB_LOCK).exists()) return true
        if (legacyObjects().let { it.isDirectory && it.listFiles().orEmpty().isNotEmpty() }) return true
        return legacyReclaimDir().exists()
    }

    /**
     * Durably removes the legacy database/objects left behind by a restore from the legacy layout.
     *
     * Same ordering as [reclaimPreviousGeneration]: artifacts are first renamed into a deterministic
     * trash directory, the vault directory is fsynced, and only then is the trash removed. An
     * interrupted call leaves `legacy.reclaim` behind, and the next call finishes it. The active
     * generation pointer is never changed and the active database/objects are never touched.
     *
     * @return true when anything (files or trash) was removed.
     */
    @Synchronized
    fun reclaimLegacyLayout(): Boolean {
        if (activeGenerationId() == null) return false
        val trash = legacyReclaimDir()
        if (trash.exists()) {
            check(trash.deleteRecursively()) { "旧版资料已标记，但清理未完成，请稍后重试" }
            syncDirectory(vaultDir)
            return true
        }
        var moved = false
        try {
            for (name in listOf(LEGACY_DB, LEGACY_DB_LOCK, LEGACY_OBJECTS)) {
                val source = File(vaultDir, name)
                if (!source.exists()) continue
                check(trash.mkdirs() || trash.isDirectory) { "暂时无法标记旧版资料" }
                val target = File(trash, name)
                if (target.exists()) check(target.deleteRecursively()) { "暂时无法标记旧版资料" }
                check(source.renameTo(target)) { "暂时无法标记旧版资料，原始资料已保留" }
                moved = true
            }
            if (moved) syncDirectory(vaultDir)
        } finally {
            // Even after a failure the moved/remaining artifacts are recoverable by a retry.
        }
        if (trash.exists()) {
            check(trash.deleteRecursively()) { "旧版资料已标记，但清理未完成，请稍后重试" }
            syncDirectory(vaultDir)
            moved = true
        }
        return moved
    }

    private fun legacyObjects(): File = File(vaultDir, LEGACY_OBJECTS)
    private fun legacyReclaimDir(): File = File(vaultDir, LEGACY_RECLAIM_DIR)

    /**
     * Reclaims the pointer's previous generation after a confirmed restore has been completed.
     *
     * Ordering makes an interruption recoverable: the directory is first renamed to a deterministic
     * trash name inside `gen/`, the pointer is switched atomically and synced, and only then is the
     * trash deleted. A crash before the pointer switch leaves `previousId` pointing at a missing
     * directory, which the next call finishes; a crash after it leaves only an orphan trash
     * directory that the next call removes. The active generation is never renamed or deleted.
     *
     * @return the reclaimed generation id, or null when there is nothing to reclaim (legacy layout,
     *  no previousId, or an earlier call already cleared it). Never returns the active generation.
     */
    @Synchronized
    fun reclaimPreviousGeneration(): String? {
        val pointer = readPointer() ?: return null
        val previous = pointer.previousId ?: return null
        val active = pointer.activeId
        require(previous != active) { "资料代际指针异常，旧代际不会被清理" }
        if (active != null) require(generationDatabase(active).isFile) { "当前资料代际不可用，旧代际不会被清理" }
        val trash = reclaimTrash(previous)
        val directory = generationDir(previous)
        if (directory.exists()) {
            check(!trash.exists()) { "旧代际待清理状态异常，旧代际已保留" }
            check(directory.renameTo(trash)) { "暂时无法标记旧代际，旧代际已保留" }
            syncDirectory(genRoot)
        }
        // Persisted before any file is finally removed, so a retry can always finish the job.
        writePointer(pointer.copy(previousId = null, updatedAt = System.currentTimeMillis()))
        if (trash.exists()) check(trash.deleteRecursively()) { "旧代际已不再可回退，但清理未完成，请稍后重试" }
        cleanOrphanReclaimDirectories(keep = null)
        syncDirectory(genRoot)
        return previous
    }

    private fun reclaimTrash(id: String): File = File(genRoot, "$id.reclaim")

    /** Removes trash left by an interrupted reclaim whose pointer no longer names it. */
    private fun cleanOrphanReclaimDirectories(keep: String?) {
        val children = genRoot.listFiles().orEmpty()
        var deleted = false
        children.forEach { child ->
            val name = child.name
            if (!name.endsWith(RECLAIM_SUFFIX)) return@forEach
            val id = name.removeSuffix(RECLAIM_SUFFIX)
            if (id == keep) return@forEach
            if (!isCanonicalUuid(id)) return@forEach
            if (child.deleteRecursively()) deleted = true
        }
        if (deleted) syncDirectory(genRoot)
        else if (children.any { it.name.endsWith(RECLAIM_SUFFIX) }) syncDirectory(genRoot)
    }

    private fun isCanonicalUuid(id: String): Boolean =
        runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    private companion object {
        private const val RECLAIM_SUFFIX = ".reclaim"
        private const val LEGACY_DB = "journal.db"
        private const val LEGACY_DB_LOCK = "journal.db.lck"
        private const val LEGACY_OBJECTS = "objects"
        private const val LEGACY_RECLAIM_DIR = "legacy.reclaim"
    }

    private fun systemSyncDirectory(dir: File) = syncDirectoryDurably(dir)
}

/** Directory fsync via Android's Os; kept top-level so it can be referenced from a default argument. */
internal fun syncDirectoryDurably(dir: File) {
    val fd = android.system.Os.open(dir.path, android.system.OsConstants.O_RDONLY, 0)
    try {
        val stat = android.system.Os.fstat(fd)
        require(android.system.OsConstants.S_ISDIR(stat.st_mode))
        android.system.Os.fsync(fd)
    } finally { android.system.Os.close(fd) }
}
