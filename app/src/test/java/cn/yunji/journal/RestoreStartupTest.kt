package cn.yunji.journal

import cn.yunji.journal.core.backup.RestoreJournal
import cn.yunji.journal.core.backup.RestoreStage
import cn.yunji.journal.core.backup.VaultLayout
import cn.yunji.journal.core.backup.VaultPointer
import cn.yunji.journal.core.security.RestoreStartup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/** Startup reconciliation of an interrupted restore. Only file layout is exercised. */
class RestoreStartupTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(): Triple<File, VaultLayout, RestoreJournal> {
        val root = temporary.newFolder("vault")
        return Triple(root, VaultLayout(root, { }), RestoreJournal(root))
    }

    private fun createGeneration(layout: VaultLayout): String {
        val id = layout.newGenerationId()
        layout.generationDatabase(id).apply { parentFile!!.mkdirs(); writeText("gen") }
        return id
    }

    @Test fun noJournalIsANormalStartup() {
        val (root, layout, _) = fixture()
        File(root, "journal.db").writeText("legacy")
        assertEquals("NONE", RestoreStartup.reconcile(root).action)
        assertEquals(layout.legacyDatabase().canonicalPath, layout.activeDatabase().canonicalPath)
    }

    @Test fun preparedButNeverSwitchedStagingIsDiscarded() {
        val (root, layout, journal) = fixture()
        val current = createGeneration(layout)
        layout.writePointer(VaultPointer(activeId = current, updatedAt = 1))
        val staging = createGeneration(layout)
        journal.write(RestoreStage.PREPARED, staging, current, "snapshot")
        val outcome = RestoreStartup.reconcile(root)
        assertEquals("DISCARDED_STAGING", outcome.action)
        assertEquals(current, layout.activeGenerationId())
        assertFalse("Unactivated staging must not be kept", layout.generationDir(staging).exists())
        assertNull("Journal must be cleared after discarding", journal.read())
    }

    @Test fun switchedAndReadableStaysOnTheNewGeneration() {
        val (root, layout, journal) = fixture()
        val previous = createGeneration(layout)
        val active = createGeneration(layout)
        layout.writePointer(VaultPointer(activeId = active, previousId = previous, updatedAt = 1))
        journal.write(RestoreStage.SWITCHED, active, previous, "snapshot")
        val outcome = RestoreStartup.reconcile(root)
        assertEquals("VERIFY_ACTIVE", outcome.action)
        assertEquals(active, layout.activeGenerationId())
        assertEquals(active, outcome.generationId)
    }

    @Test fun switchedButUnreadableRollsBackToPreviousGeneration() {
        val (root, layout, journal) = fixture()
        val previous = createGeneration(layout)
        val broken = layout.newGenerationId() // pointer names it, but the database file is absent
        layout.writePointer(VaultPointer(activeId = broken, previousId = previous, updatedAt = 1))
        journal.write(RestoreStage.SWITCHED, broken, previous, "snapshot")
        val outcome = RestoreStartup.reconcile(root)
        assertEquals("ROLLED_BACK", outcome.action)
        assertEquals("Rollback must restore the previous generation", previous, layout.activeGenerationId())
        assertTrue(layout.activeDatabase().isFile)
    }

    @Test fun failedFirstRestoreFallsBackToLegacyLayout() {
        val (root, layout, journal) = fixture()
        File(root, "journal.db").writeText("legacy")
        val broken = layout.newGenerationId()
        layout.writePointer(VaultPointer(activeId = broken, updatedAt = 1))
        journal.write(RestoreStage.SWITCHED, broken, null, "snapshot")
        val outcome = RestoreStartup.reconcile(root)
        assertEquals("ROLLED_BACK_TO_LEGACY", outcome.action)
        assertNull(layout.activeGenerationId())
        assertEquals(layout.legacyDatabase().canonicalPath, layout.activeDatabase().canonicalPath)
    }

    @Test fun verifiedJournalDoesNotDisturbTheActiveGeneration() {
        val (root, layout, journal) = fixture()
        val active = createGeneration(layout)
        layout.writePointer(VaultPointer(activeId = active, updatedAt = 1))
        journal.write(RestoreStage.VERIFIED, active, null, "snapshot")
        assertEquals("CONTINUE", RestoreStartup.reconcile(root).action)
        assertEquals(active, layout.activeGenerationId())
    }

    @Test fun reconciliationIsIdempotent() {
        val (root, layout, journal) = fixture()
        val previous = createGeneration(layout)
        val broken = layout.newGenerationId()
        layout.writePointer(VaultPointer(activeId = broken, previousId = previous, updatedAt = 1))
        journal.write(RestoreStage.SWITCHED, broken, previous, "snapshot")
        assertEquals("ROLLED_BACK", RestoreStartup.reconcile(root).action)
        val second = RestoreStartup.reconcile(root)
        assertEquals(previous, layout.activeGenerationId())
        assertEquals("After rollback the journal is closed, so a second startup is a plain start", "NONE", second.action)
    }
    @Test fun verifierSuccessMarksVerifiedOnlyAfterCallback() {
        val (root,layout,journal)=fixture();val id=createGeneration(layout)
        layout.writePointer(VaultPointer(activeId=id));journal.write(RestoreStage.SWITCHED,id,null,"snapshot")
        var called=false
        val outcome=RestoreStartup.reconcile(root,{}, {target->assertEquals(id,target);assertEquals(RestoreStage.SWITCHED,journal.read());called=true})
        assertTrue(called);assertEquals("VERIFIED_ACTIVE",outcome.action);assertEquals(RestoreStage.VERIFIED,journal.read())
    }
    @Test fun verifierFailureRollsBackAndPreservesFailedGeneration() {
        val (root,layout,journal)=fixture();val old=createGeneration(layout);val id=createGeneration(layout)
        layout.writePointer(VaultPointer(activeId=id,previousId=old));journal.write(RestoreStage.SWITCHED,id,old,"snapshot")
        val outcome=RestoreStartup.reconcile(root,{}, {throw java.io.IOException("Synthetic auth failure")})
        assertEquals("ROLLED_BACK",outcome.action);assertEquals(old,layout.activeGenerationId())
        assertTrue(layout.generationDatabase(id).isFile);assertNull(journal.read())
    }
    @Test fun missingOldDataDoesNotCreateEmptyFallbackOrEraseJournal() {
        val (root,layout,journal)=fixture();val id=createGeneration(layout)
        layout.writePointer(VaultPointer(activeId=id));journal.write(RestoreStage.SWITCHED,id,null,"snapshot")
        assertTrue(runCatching {RestoreStartup.reconcile(root,{}, {throw java.io.IOException("Synthetic failure")})}.isFailure)
        assertEquals(id,layout.activeGenerationId());assertEquals(RestoreStage.SWITCHED,journal.read());assertFalse(layout.legacyDatabase().exists())
    }}
