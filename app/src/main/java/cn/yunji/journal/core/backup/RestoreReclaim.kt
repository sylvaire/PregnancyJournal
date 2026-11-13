package cn.yunji.journal.core.backup

import java.io.File

/** Whether the UI may offer to reclaim the generation kept for rollback after a restore. */
enum class ReclaimState {
    /** Legacy layout or the previous generation was already reclaimed. */
    NONE,

    /** A restore was verified and completed; the pre-restore generation can be reclaimed. */
    AVAILABLE,

    /** A restore is still prepared/switched, so the old generation may still be needed. */
    PENDING_RESTORE,

    /** The pointer or the active generation cannot be read; nothing is ever deleted in this state. */
    UNREADABLE,
}

/** Read-only snapshot of the reclaimable state, safe to expose to the recovery screen. */
data class ReclaimInfo(
    val state: ReclaimState,
    val previousId: String? = null,
    val message: String? = null,
) {
    companion object {
        val none = ReclaimInfo(ReclaimState.NONE)
        val pending = ReclaimInfo(ReclaimState.PENDING_RESTORE, message = "上次恢复尚未完成，现在不能清理旧资料库。")
        fun available(previousId: String) = ReclaimInfo(ReclaimState.AVAILABLE, previousId)
        /** Legacy-layout leftovers have no generation id; the UI only needs the AVAILABLE state. */
        val availableLegacy = ReclaimInfo(ReclaimState.AVAILABLE,
            message = "恢复前的旧版资料仍保留在本机，可回收以释放空间。")
        fun unreadable(message: String) = ReclaimInfo(ReclaimState.UNREADABLE, message = message)
    }
}

/**
 * Deletes the pre-restore generation ("previous") after the user confirmed a restore and the new
 * generation reopened successfully.
 *
 * Contract:
 * - only runs when no restore is pending: the journal is either cleared (the normal completed
 *   state written by [RestoreWizard.commit] + the reopen tail) or VERIFIED for the active
 *   generation, i.e. the user already confirmed the switch;
 * - only the pointer's previousId is ever removed; the active generation is validated before the
 *   pointer changes and is never touched;
 * - the deletion is durable and restart-safe: see [VaultLayout.reclaimPreviousGeneration], which
 *   renames first, switches the pointer atomically, and then removes the trash;
 * - calling it twice is safe: the second call returns null and changes nothing;
 * - a failed attempt leaves either the previous generation or its trash in place, never a
 *   half-deleted directory while the pointer still claims it is usable.
 */
class RestoreReclaim(
    vaultDir: File,
    private val syncDirectory: (File) -> Unit = ::syncDirectoryDurably,
) {
    private val layout = VaultLayout(vaultDir, syncDirectory)
    private val journal = RestoreJournal(vaultDir)

    /** Read-only view for the UI. Never mutates the pointer, the journal or any generation. */
    fun inspect(): ReclaimInfo {
        val stage = try {
            journal.read()
        } catch (_: Exception) {
            return ReclaimInfo.unreadable("恢复收尾状态无法读取，旧代际不会被清理。")
        }
        if (stage == RestoreStage.PREPARED || stage == RestoreStage.SWITCHED) return ReclaimInfo.pending
        val pointer = try {
            layout.readPointer()
        } catch (_: Exception) {
            return ReclaimInfo.unreadable("资料代际指针无法读取，旧代际不会被清理。")
        } ?: return ReclaimInfo.none
        val active = pointer.activeId ?: return ReclaimInfo.none
        val legacy = runCatching { layout.legacyLeftovers() }.getOrDefault(false)
        val previous = pointer.previousId
        if (previous == null && !legacy) return ReclaimInfo.none
        if (previous != null && previous == active) return ReclaimInfo.unreadable("资料代际指针异常，旧代际不会被清理。")
        if (!runCatching { layout.generationDatabase(active).isFile }.getOrDefault(false)) {
            return ReclaimInfo.unreadable("当前资料代际不可用，旧资料不会被清理。")
        }
        if (stage == RestoreStage.VERIFIED && journal.targetId() != active) {
            return ReclaimInfo.unreadable("恢复状态已变化，旧资料不会被清理。")
        }
        return if (previous != null) ReclaimInfo.available(previous) else ReclaimInfo.availableLegacy
    }

    /**
     * Reclaims the previous generation. Returns its id, or null when there is nothing to reclaim
     * (legacy layout, already reclaimed, or an inconsistent pointer). Throws, leaving all files and
     * the pointer intact, when a restore is pending or the active generation is unusable.
     */
    @Synchronized
    fun reclaim(): String? {
        val stage = journal.read()
        val pointer = layout.readPointer() ?: return null
        val active = pointer.activeId
        require(stage == null || (stage == RestoreStage.VERIFIED && journal.targetId() == active)) {
            "上次恢复尚未完成，现在不能清理旧资料库"
        }
        if (active != null) {
            require(layout.generationDatabase(active).isFile) { "当前资料代际不可用，旧代际不会被清理" }
        }
        val previous = layout.reclaimPreviousGeneration()
        // A restore performed from the legacy layout leaves `vault/journal.db` and `vault/objects`
        // behind, which are never referenced by the active generation; reclaim them in the same
        // user-confirmed pass so "彻底删除" can actually free those files.
        if (active != null && layout.legacyLeftovers()) layout.reclaimLegacyLayout()
        return previous
    }
}
