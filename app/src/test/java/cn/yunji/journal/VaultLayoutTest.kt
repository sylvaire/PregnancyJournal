package cn.yunji.journal

import cn.yunji.journal.core.backup.RestoreJournal
import cn.yunji.journal.core.backup.RestoreStage
import cn.yunji.journal.core.backup.VaultLayout
import cn.yunji.journal.core.backup.VaultPointer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/** Generation layout, atomic pointer and crash-recovery decisions. No Android APIs are exercised. */
class VaultLayoutTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun layout(): Pair<VaultLayout, File> {
        val root = temporary.newFolder()
        return VaultLayout(root, { }) to root
    }

    @Test fun legacyInstallationKeepsWorkingWhenNoPointerExists() {
        val (layout, root) = layout()
        val legacy = File(root, "journal.db").apply { writeText("synthetic") }
        assertEquals(legacy, layout.activeDatabase())
        assertNull(layout.activeGenerationId())
        assertNull(layout.readPointer())
    }

    @Test fun pointerSelectsGenerationAndSurvivesReread() {
        val (layout, _) = layout()
        val first = layout.newGenerationId()
        val second = layout.newGenerationId()
        File(layout.generationDir(first), "x").let { it.parentFile.mkdirs() }
        layout.generationDatabase(first).writeText("gen-1")
        layout.generationDatabase(second).apply { parentFile!!.mkdirs(); writeText("gen-2") }

        layout.writePointer(VaultPointer(activeId = first, updatedAt = 1))
        assertEquals(layout.generationDatabase(first).canonicalPath, layout.activeDatabase().canonicalPath)

        layout.writePointer(VaultPointer(activeId = second, previousId = first, updatedAt = 2))
        assertEquals(layout.generationDatabase(second).canonicalPath, layout.activeDatabase().canonicalPath)
        assertEquals(second, layout.readPointer()?.activeId)
        assertEquals(first, layout.readPointer()?.previousId)
    }

    @Test fun missingActiveGenerationFailsInsteadOfCreatingAnEmptyVault() {
        val (layout, _) = layout()
        val id = layout.newGenerationId()
        layout.writePointer(VaultPointer(activeId = id, updatedAt = 1))
        val error = runCatching { layout.activeDatabase() }.exceptionOrNull()
        assertNotNull("A missing generation must never silently become a new empty vault", error)
        assertTrue(error is IllegalArgumentException || error is IllegalStateException)
    }

    @Test fun corruptedPointerIsReportedAndOriginalFileIsKept() {
        val (layout, root) = layout()
        val pointer = File(root, "active-generation.json").apply { writeText("{not json") }
        assertTrue(runCatching { layout.readPointer() }.isFailure)
        assertTrue("Corrupt pointer must not be silently deleted", pointer.exists())
    }

    @Test fun pointerRejectsTraversalAndNonUuidIdentifiers() {
        val (layout, _) = layout()
        assertTrue(runCatching { layout.generationDatabase("../../etc") }.isFailure)
        assertTrue(runCatching { layout.generationDir("not-a-uuid") }.isFailure)
        assertTrue(runCatching { layout.writePointer(VaultPointer(activeId = "../escape")) }.isFailure)
    }

    @Test fun deletingAGenerationRemovesOnlyThatDirectory() {
        val (layout, _) = layout()
        val keep = layout.newGenerationId()
        val drop = layout.newGenerationId()
        layout.generationDatabase(keep).apply { parentFile!!.mkdirs(); writeText("keep") }
        layout.generationDatabase(drop).apply { parentFile!!.mkdirs(); writeText("drop") }
        layout.writePointer(VaultPointer(activeId = keep, previousId = drop, updatedAt = 1))
        layout.deleteGeneration(drop)
        assertTrue(layout.generationDatabase(keep).isFile)
        assertFalse(layout.generationDir(drop).exists())
        assertEquals(keep, layout.readPointer()?.activeId)
    }

    @Test fun preparedStageIsDiscardedWhenThePointerNeverSwitched() {
        val target = UUID.randomUUID().toString()
        val decision = RestoreJournal.decide(RestoreStage.PREPARED, target, pointerActiveId = null, targetExists = true, activeExists = true)
        assertEquals(RestoreJournal.Recovery.DiscardStaging(target), decision)
    }

    @Test fun switchedStageRequiresVerificationOrRollback() {
        val target = UUID.randomUUID().toString()
        assertEquals(RestoreJournal.Recovery.VerifyActive(target),
            RestoreJournal.decide(RestoreStage.SWITCHED, target, target, targetExists = true, activeExists = true))
        assertEquals(RestoreJournal.Recovery.RollBack(null),
            RestoreJournal.decide(RestoreStage.SWITCHED, target, target, targetExists = true, activeExists = false))
        assertEquals(RestoreJournal.Recovery.RollBack(null),
            RestoreJournal.decide(RestoreStage.SWITCHED, target, target, targetExists = false, activeExists = false))
    }

    @Test fun noJournalMeansNormalStartupAndVerifiedJournalStopsNudging() {
        assertEquals(RestoreJournal.Recovery.Continue,
            RestoreJournal.decide(null, null, null, targetExists = false, activeExists = false))
        val target = UUID.randomUUID().toString()
        assertEquals(RestoreJournal.Recovery.Continue,
            RestoreJournal.decide(RestoreStage.VERIFIED, target, target, targetExists = true, activeExists = true))
    }

    @Test fun journalRoundTripsAndClearsAndRejectsCorruption() {
        val (_, root) = layout()
        val journal = RestoreJournal(root)
        val target = UUID.randomUUID().toString()
        assertNull(journal.read())
        journal.write(RestoreStage.PREPARED, target, null, "snapshot-1")
        assertEquals(RestoreStage.PREPARED, journal.read())
        assertEquals(target, journal.targetId())
        assertEquals("snapshot-1", journal.snapshotId())
        journal.write(RestoreStage.SWITCHED, target, null, "snapshot-1")
        assertEquals(RestoreStage.SWITCHED, journal.read())
        journal.clear()
        assertNull(journal.read())

        File(root, "restore-journal.json").writeText("{broken")
        assertNull("A corrupt journal must not block startup forever", journal.read())
    }
    @Test fun objectDirectoryFollowsGenerationAndMissingDirectoryDoesNotBecomeEmptyVault() {
        val (layout,root)=layout()
        assertEquals(File(root,"objects"),layout.activeObjects())
        val id=layout.newGenerationId()
        layout.generationDatabase(id).apply {parentFile!!.mkdirs();writeText("synthetic")}
        layout.writePointer(VaultPointer(activeId=id))
        assertTrue(runCatching {layout.activeObjects()}.isFailure)
        val objects=File(layout.generationDir(id),"objects").apply {mkdirs()}
        assertEquals(objects,layout.activeObjects())
    }}
