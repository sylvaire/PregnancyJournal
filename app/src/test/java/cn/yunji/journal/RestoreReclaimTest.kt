package cn.yunji.journal

import cn.yunji.journal.core.backup.ReclaimState
import cn.yunji.journal.core.backup.RestoreJournal
import cn.yunji.journal.core.backup.RestoreReclaim
import cn.yunji.journal.core.backup.RestoreStage
import cn.yunji.journal.core.backup.VaultLayout
import cn.yunji.journal.core.backup.VaultPointer
import cn.yunji.journal.core.security.RestoreStartup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Lifecycle of the pre-restore generation: when the recovery screen may offer it, what a reclaim is
 * allowed to delete, how an interrupted reclaim is finished, and that a later restore still works.
 * Only file-system behaviour of the real implementations is exercised (no Android APIs).
 */
class RestoreReclaimTest {
    @get:Rule val temporary = TemporaryFolder()

    private val synced = mutableListOf<File>()
    private lateinit var root: File
    private lateinit var layout: VaultLayout
    private lateinit var journal: RestoreJournal
    private lateinit var reclaim: RestoreReclaim

    private fun vault() {
        root = temporary.newFolder()
        synced.clear()
        layout = VaultLayout(root) { synced += it }
        journal = RestoreJournal(root)
        reclaim = RestoreReclaim(root) { synced += it }
    }

    private fun generation(name: String = "synthetic"): String = layout.newGenerationId().also { id ->
        layout.generationDatabase(id).apply { parentFile!!.mkdirs(); writeText(name) }
    }

    private fun pointer(active: String?, previous: String? = null) {
        layout.writePointer(VaultPointer(activeId = active, previousId = previous, updatedAt = 1))
    }

    private fun trashFiles(): List<File> = File(root, "gen").listFiles().orEmpty().filter { it.name.endsWith(".reclaim") }

    @Test fun legacyVaultWithoutAPointerHasNothingToReclaim() {
        vault()
        assertEquals(ReclaimState.NONE, reclaim.inspect().state)
        assertNull(reclaim.reclaim())
        assertNull(layout.readPointer())
    }

    @Test fun activeGenerationWithoutPreviousHasNothingToReclaim() {
        vault()
        val active = generation("active")
        pointer(active)
        assertEquals(ReclaimState.NONE, reclaim.inspect().state)
        assertNull(reclaim.reclaim())
        assertEquals(active, layout.readPointer()?.activeId)
        assertTrue(layout.generationDatabase(active).isFile)
    }

    @Test fun verifiedCompletedRestoreExposesAndThenReclaimsOnlyThePreviousGeneration() {
        vault()
        val previous = generation("previous")
        val active = generation("active")
        pointer(active, previous)
        journal.write(RestoreStage.VERIFIED, active, previous, "snapshot-1")

        val info = reclaim.inspect()
        assertEquals(ReclaimState.AVAILABLE, info.state)
        assertEquals(previous, info.previousId)

        assertEquals(previous, reclaim.reclaim())
        val after = requireNotNull(layout.readPointer())
        assertNull("the pointer must stop claiming a rollback generation", after.previousId)
        assertEquals("the active generation must never move", active, after.activeId)
        assertTrue("the active database must survive", layout.generationDatabase(active).isFile)
        assertFalse("the previous generation must be gone", layout.generationDir(previous).exists())
        assertTrue("an empty reclaim trash must not be left behind", trashFiles().isEmpty())
        assertEquals("reclaiming must not fake the reopen tail", RestoreStage.VERIFIED, journal.read())
        assertTrue("the generation directory change must be fsynced", synced.any { it.name == "gen" })
    }

    @Test fun clearedJournalAfterReopenStillAllowsReclaimAndStartupIsNormal() {
        vault()
        val previous = generation("previous")
        val active = generation("active")
        pointer(active, previous)
        journal.write(RestoreStage.VERIFIED, active, previous, "snapshot-1")
        journal.clear() // what finishReopen does after resources() opened the new generation

        assertEquals(ReclaimState.AVAILABLE, reclaim.inspect().state)
        assertEquals(previous, reclaim.reclaim())
        val outcome = RestoreStartup.reconcile(root)
        assertEquals("NONE", outcome.action)
        assertEquals(active, outcome.generationId)
        assertNull(journal.read())
    }

    @Test fun preparedRestoreBlocksReclaimAndKeepsEverything() {
        vault()
        val previous = generation("previous")
        val active = generation("active")
        val staging = generation("staging")
        pointer(active, previous)
        journal.write(RestoreStage.PREPARED, staging, previous, "snapshot-2")

        assertEquals(ReclaimState.PENDING_RESTORE, reclaim.inspect().state)
        assertTrue("a pending restore may still need the old generation", runCatching { reclaim.reclaim() }.isFailure)
        assertEquals("the journal must be kept for startup", RestoreStage.PREPARED, journal.read())
        assertTrue(layout.generationDir(previous).exists())
        assertEquals(previous, layout.readPointer()?.previousId)
        assertEquals(active, layout.readPointer()?.activeId)
    }

    @Test fun switchedRestoreBlocksReclaimUntilReopenCompletes() {
        vault()
        val previous = generation("previous")
        val active = generation("active")
        pointer(active, previous)
        journal.write(RestoreStage.SWITCHED, active, previous, "snapshot-3")

        assertEquals(ReclaimState.PENDING_RESTORE, reclaim.inspect().state)
        assertTrue(runCatching { reclaim.reclaim() }.isFailure)
        assertEquals(RestoreStage.SWITCHED, journal.read())
        assertTrue(layout.generationDir(previous).exists())
    }

    @Test fun reclaimIsIdempotentAndNeverTouchesTheActiveGeneration() {
        vault()
        val previous = generation("previous")
        val active = generation("active")
        pointer(active, previous)

        assertEquals(previous, reclaim.reclaim())
        assertEquals("a second reclaim must be a no-op", ReclaimState.NONE, reclaim.inspect().state)
        assertNull(reclaim.reclaim())
        assertEquals(active, layout.readPointer()?.activeId)
        assertTrue(layout.generationDatabase(active).isFile)
        assertTrue(trashFiles().isEmpty())
    }

    @Test fun inconsistentPointerIsReportedAndNeverDeletesAnything() {
        vault()
        val same = generation("same")
        pointer(same, same)

        assertEquals(ReclaimState.UNREADABLE, reclaim.inspect().state)
        assertTrue(runCatching { reclaim.reclaim() }.isFailure)
        assertTrue("an inconsistent pointer must not delete the only generation", layout.generationDir(same).exists())
        assertTrue(layout.generationDatabase(same).isFile)
    }

    @Test fun unusableActiveGenerationBlocksReclaim() {
        vault()
        val previous = generation("previous")
        pointer(UUID.randomUUID().toString(), previous) // pointer names a generation that was never written

        assertEquals(ReclaimState.UNREADABLE, reclaim.inspect().state)
        assertTrue(runCatching { reclaim.reclaim() }.isFailure)
        assertTrue(layout.generationDir(previous).exists())
        assertEquals(previous, layout.readPointer()?.previousId)
    }

    @Test fun corruptedPointerIsReportedInsteadOfDeleting() {
        vault()
        val previous = generation("previous")
        pointer(generation("active"), previous)
        File(root, "active-generation.json").writeText("{broken")

        assertEquals(ReclaimState.UNREADABLE, reclaim.inspect().state)
        assertTrue(runCatching { reclaim.reclaim() }.isFailure)
        assertTrue(layout.generationDir(previous).exists())
        assertTrue("a corrupt pointer must be kept for diagnostics", File(root, "active-generation.json").exists())
    }

    @Test fun interruptedRenameIsFinishedByRetry() {
        vault()
        val previous = generation("previous")
        val active = generation("active")
        pointer(active, previous)
        // Simulate a crash after the directory was renamed to trash but before the pointer switch.
        val trash = File(File(root, "gen"), "$previous.reclaim")
        assertTrue(layout.generationDir(previous).renameTo(trash))
        assertFalse(layout.generationDir(previous).exists())

        assertEquals("the stale previousId must still be offered", ReclaimState.AVAILABLE, reclaim.inspect().state)
        assertEquals(previous, reclaim.reclaim())
        assertNull(layout.readPointer()?.previousId)
        assertFalse("the leftover trash must be removed", trash.exists())
        assertTrue(layout.generationDatabase(active).isFile)
    }

    @Test fun orphanTrashFromAnEarlierInterruptedReclaimIsCleanedByTheNextReclaim() {
        vault()
        val previous = generation("previous")
        val active = generation("active")
        pointer(active, previous)
        val orphan = File(File(root, "gen"), "${UUID.randomUUID()}.reclaim").apply { mkdirs() }

        assertEquals(previous, reclaim.reclaim())
        assertFalse("an orphan reclaim trash must not leak forever", orphan.exists())
        assertTrue(trashFiles().isEmpty())
    }

    @Test fun secondRestoreAfterReclaimCanReclaimItsOwnPreviousAndReopen() {
        vault()
        val first = generation("previous")
        val active = generation("active")
        pointer(active, first)
        reclaim.reclaim()

        // A later restore: the pointer now keeps "active" as its rollback generation.
        val restored = generation("restored")
        journal.write(RestoreStage.VERIFIED, restored, active, "snapshot-4")
        pointer(restored, active)
        journal.clear() // finishReopen tail
        assertEquals(ReclaimState.AVAILABLE, reclaim.inspect().state)
        assertEquals(active, reclaim.reclaim())
        assertTrue(layout.generationDatabase(restored).isFile)
        assertNull(layout.readPointer()?.previousId)
        assertNull(RestoreJournal(root).read())
    }

    @Test fun interruptedSecondRestoreAfterReclaimRollsBackToTheActiveGeneration() {
        vault()
        val previous = generation("previous")
        val active = generation("active")
        pointer(active, previous)
        reclaim.reclaim()

        // The next restore switched the pointer but its generation was never finalized.
        val target = UUID.randomUUID().toString() // target dir does not exist yet
        journal.write(RestoreStage.SWITCHED, target, active, "snapshot-5")
        pointer(target, active)

        val outcome = RestoreStartup.reconcile(root)
        assertEquals("ROLLED_BACK", outcome.action)
        assertEquals(active, outcome.generationId)
        assertEquals(active, layout.readPointer()?.activeId)
        assertNull(journal.read())
        assertTrue("rollback must keep the reclaimed vault usable", layout.generationDatabase(active).isFile)
    }

    @Test fun startupLeavesJournalWhenRollbackCannotProveAnyUsableGeneration() {
        vault()
        val broken = UUID.randomUUID().toString()
        pointer(broken, null)
        journal.write(RestoreStage.SWITCHED, broken, null, "snapshot-6")

        assertTrue(runCatching { RestoreStartup.reconcile(root) }.isFailure)
        assertEquals("the only recovery journal must not be erased", RestoreStage.SWITCHED, journal.read())
        assertNotNull(layout.readPointer())
        assertEquals(broken, layout.readPointer()?.activeId)
    }

    @Test fun reclaimKeepsSafetyCopiesExportsAndForeignFiles() {
        vault()
        val previous = generation("previous")
        val active = generation("active")
        pointer(active, previous)
        val safety = File(root, "safety/copy-1.yunji").apply { parentFile!!.mkdirs(); writeText("synthetic safety copy") }
        val export = File(root, "exports/manual.yunji").apply { parentFile!!.mkdirs(); writeText("synthetic export") }

        reclaim.reclaim()
        assertTrue(safety.isFile)
        assertTrue(export.isFile)
        assertTrue(layout.generationDatabase(active).isFile)
    }

    // ---- Bug #5: a restore performed from the legacy layout leaves vault/journal.db and
    // vault/objects behind; they must be reclaimable so "彻底删除" can free the old objects. ----

    private fun legacyLeftovers() {
        File(root, "journal.db").writeText("legacy database")
        File(root, "journal.db.lck").writeText("legacy lock")
        File(root, "objects").mkdirs()
        File(root, "objects/legacy-object.bin").writeText("legacy object")
    }

    @Test fun legacyLayoutLeftoversAreOfferedAndReclaimedWithTheActiveGenerationIntact() {
        vault()
        val active = generation("active")
        pointer(active)
        legacyLeftovers()

        assertEquals(ReclaimState.AVAILABLE, reclaim.inspect().state)
        assertNull("a legacy reclaim has no generation id to return", reclaim.reclaim())
        assertFalse("the replaced legacy database must be gone", File(root, "journal.db").exists())
        assertFalse("the replaced legacy lock file must be gone", File(root, "journal.db.lck").exists())
        assertFalse("the replaced legacy objects must be gone", File(root, "objects").exists())
        assertFalse("no legacy trash may leak", File(root, "legacy.reclaim").exists())
        assertTrue("the active generation must survive", layout.generationDatabase(active).isFile)
        assertEquals(active, layout.readPointer()?.activeId)
        assertEquals("reclaim must stay idempotent", ReclaimState.NONE, reclaim.inspect().state)
        assertTrue("the vault directory change must be fsynced", synced.any { it == root })
    }

    @Test fun interruptedLegacyReclaimIsFinishedByRetry() {
        vault()
        val active = generation("active")
        pointer(active)
        // Simulate a crash after the legacy files were renamed into the trash but before deletion.
        val trash = File(root, "legacy.reclaim").apply { mkdirs() }
        File(trash, "journal.db").writeText("legacy database")
        File(trash, "objects").mkdirs()

        assertEquals(ReclaimState.AVAILABLE, reclaim.inspect().state)
        reclaim.reclaim()
        assertFalse(trash.exists())
        assertTrue(layout.generationDatabase(active).isFile)
        assertEquals(active, layout.readPointer()?.activeId)
    }

    @Test fun pendingRestoreStillBlocksLegacyReclaim() {
        vault()
        val previous = generation("previous")
        val active = generation("active")
        val staging = generation("staging")
        pointer(active, previous)
        journal.write(RestoreStage.PREPARED, staging, previous, "snapshot-legacy")
        legacyLeftovers()

        assertEquals(ReclaimState.PENDING_RESTORE, reclaim.inspect().state)
        assertTrue(runCatching { reclaim.reclaim() }.isFailure)
        assertTrue("a pending restore may still need the legacy data", File(root, "journal.db").exists())
        assertTrue(File(root, "objects/legacy-object.bin").isFile)
    }
}
