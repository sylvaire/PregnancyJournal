package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.EncryptedFileStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Restore wizard: validate into staging, preview counts, then switch. Failure at any step must leave the
 * current generation untouched and must never write to live data.
 */
class RestoreWizardTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Fixture {
        val vaultRoot = temporary.newFolder("vault")
        val workRoot = temporary.newFolder("work")
        val layout = VaultLayout(vaultRoot, { })
        val journal = RestoreJournal(vaultRoot)
        val wizard = RestoreWizard(layout, journal, workRoot)

        val currentVault = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
        val recovery = RecoveryKey.create()
        val slot = RecoverySlots.create(currentVault, recovery)

        /** Builds a real encrypted archive of one pregnancy, one visit and one attachment. */
        fun archive(): File {
            val storeDir = temporary.newFolder("objects")
            val store = EncryptedFileStore(storeDir, currentVault)
            val body = "synthetic restore payload".toByteArray()
            val stored = store.put(body.inputStream())
            val pregnancy = PregnancyEntity("pregnancy-restore", "合成恢复档案", createdAt = 1, updatedAt = 1)
            val visit = VisitEntity("visit-restore", pregnancy.id, "2026-10-21", zoneId = "UTC", createdAt = 1, updatedAt = 1)
            val attachment = AttachmentEntity(stored.id, visit.id, "合成原件", "image/png",
                stored.plainSize, stored.cipherSize, stored.plainSha256, stored.cipherSha256,
                null, null, null, null, null, 1)
            val drug = MedicationEntity("drug-restore", pregnancy.id, "合成药品", "", "", 1)
            val plan = MedicationPlanEntity("plan-restore", pregnancy.id, drug.id, "logical-restore", 1, "0.125", "片",
                "DAILY_TIMES", "08:00", "", "UTC", "2026-10-21", null, "ACTIVE", "", "合成来源", 1)
            val snapshot = LogicalSnapshot(vaultId = currentVault.id, snapshotId = UUID.randomUUID().toString(),
                writerId = UUID.randomUUID().toString(), writerSequence = 3, capturedSequence = 21, createdAt = 1_700_000_000_000,
                pregnancies = listOf(pregnancy), visits = listOf(visit), attachments = listOf(attachment),
                medications = listOf(drug), plans = listOf(plan),
                entries = listOf(JournalEntryEntity("entry-restore", pregnancy.id, "2026-10-21", null, "UTC", null, "", "", "合成备注", 1, 1)))
            val target = File(temporary.newFolder("archive"), "restore.yunji")
            LocalBackupArchive.write(snapshot, currentVault, slot, store, target)
            this.expected = snapshot
            return target
        }

        var expected: LogicalSnapshot? = null

        /** Stand-in for the isolated import: writes a non-empty file the wizard can copy. */
        fun isolated(handle: VerifiedArchiveHandle): File {
            require(handle.snapshot.pregnancies.isNotEmpty())
            val file = File(handle.directory, "isolated.db")
            file.writeText("synthetic-isolated-${handle.snapshot.pregnancies.size}")
            return file
        }

        /** Marks a "current" generation so rollback and previous-generation retention can be asserted. */
        fun installCurrentGeneration(): String {
            val id = layout.newGenerationId()
            layout.generationDatabase(id).apply { parentFile!!.mkdirs(); writeText("current") }
            layout.writePointer(VaultPointer(activeId = id, updatedAt = 1))
            return id
        }

        fun close() { currentVault.master.fill(0) }
    }

    @Test fun prepareReportsCountsWithoutTouchingLiveGeneration() {
        val f = Fixture()
        try {
            val current = f.installCurrentGeneration()
            val archive = f.archive()
            val preview = f.wizard.prepare(archive, f.recovery) { f.isolated(it) }
            assertEquals(current, "A prepared restore must not switch the active generation".let { f.layout.activeGenerationId() })
            assertEquals(1, preview.pregnancies)
            assertEquals(1, preview.visits)
            assertEquals(1, preview.documents)
            assertEquals(1, preview.medicationPlans)
            assertEquals(1, preview.journalEntries)
            assertEquals(21, preview.capturedSequence)
            assertEquals(f.expected!!.snapshotId, preview.snapshotId)
            assertEquals(RestoreStage.PREPARED, f.journal.read())
            assertTrue(f.layout.generationDatabase(preview.generationId).isFile)
        } finally { f.close() }
    }

    @Test fun confirmationSwitchesAtomicallyAndRetainsPreviousGeneration() {
        val f = Fixture()
        try {
            val current = f.installCurrentGeneration()
            val preview = f.wizard.prepare(f.archive(), f.recovery) { f.isolated(it) }
            assertEquals(RestoreStage.VERIFIED, f.wizard.commit(preview) {file->require(file.readText().startsWith("synthetic-isolated-"))})
            assertEquals(preview.generationId, f.layout.activeGenerationId())
            assertEquals(preview.generationId, f.layout.activeDatabase().parentFile!!.name)
            assertEquals("Previous generation must stay for rollback", current, f.layout.readPointer()?.previousId)
            assertTrue(f.layout.generationDir(current).isDirectory)
            assertEquals(RestoreStage.VERIFIED, f.journal.read())

            f.wizard.reclaimPrevious()
            assertNull(f.layout.readPointer()?.previousId)
            assertFalse("Reclaiming must only remove the old generation", f.layout.generationDir(current).exists())
            assertTrue(f.layout.generationDatabase(preview.generationId).isFile)
        } finally { f.close() }
    }

    @Test fun wrongRecoveryKeyFailsBeforeAnyGenerationOrJournalIsCreated() {
        val f = Fixture()
        try {
            val current = f.installCurrentGeneration()
            val archive = f.archive()
            val otherKey = RecoveryKey.create()
            val error = runCatching { f.wizard.prepare(archive, otherKey) { f.isolated(it) } }.exceptionOrNull()
            assertNotNull("A wrong recovery key must not be accepted", error)
            assertEquals(current, f.layout.activeGenerationId())
            assertNull("No journal may survive a failed prepare", f.journal.read())
            val genRoot = File(f.vaultRoot, "gen")
            assertEquals("No staging generation may remain", 1, genRoot.listFiles().orEmpty().size)
        } finally { f.close() }
    }

    @Test fun failedImportLeavesNoStagingAndNoJournal() {
        val f = Fixture()
        try {
            val current = f.installCurrentGeneration()
            val archive = f.archive()
            val error = runCatching { f.wizard.prepare(archive, f.recovery) { throw IllegalStateException("injected import failure") } }.exceptionOrNull()
            assertNotNull(error)
            assertEquals(current, f.layout.activeGenerationId())
            assertNull(f.journal.read())
            assertEquals(1, File(f.vaultRoot, "gen").listFiles().orEmpty().size)
        } finally { f.close() }
    }

    @Test fun discardAfterPreviewLeavesCurrentDataUsable() {
        val f = Fixture()
        try {
            val current = f.installCurrentGeneration()
            val preview = f.wizard.prepare(f.archive(), f.recovery) { f.isolated(it) }
            f.wizard.discard(preview)
            assertEquals(current, f.layout.activeGenerationId())
            assertNull(f.journal.read())
            assertFalse(f.layout.generationDir(preview.generationId).exists())
        } finally { f.close() }
    }

    @Test fun commitRefusesWhenStagingDataIsMissingOrStateChanged() {
        val f = Fixture()
        try {
            val preview = f.wizard.prepare(f.archive(), f.recovery) { f.isolated(it) }
            // Losing the staging database must abort instead of switching to nothing.
            f.layout.deleteGeneration(preview.generationId)
            assertTrue(runCatching { f.wizard.commit(preview) {file->require(file.readText().startsWith("synthetic-isolated-"))} }.isFailure)
            assertNull("A refused commit must not leave a switched pointer", f.layout.activeGenerationId())
        } finally { f.close() }
    }

    @Test fun stalePreviewCannotCommitTwice() {
        val f = Fixture()
        try {
            val preview = f.wizard.prepare(f.archive(), f.recovery) { f.isolated(it) }
            f.wizard.commit(preview) {file->require(file.readText().startsWith("synthetic-isolated-"))}
            val second = runCatching { f.wizard.commit(preview) {file->require(file.readText().startsWith("synthetic-isolated-"))} }
            assertTrue("A second commit of the same preview must be rejected", second.isFailure)
        } finally { f.close() }
    }
    @Test fun discardCannotDeleteActivatedGeneration() {
        val f=Fixture()
        try {
            f.installCurrentGeneration()
            val preview=f.wizard.prepare(f.archive(),f.recovery){f.isolated(it)}
            f.wizard.commit(preview){file->require(file.isFile)}
            assertTrue(runCatching {f.wizard.discard(preview)}.isFailure)
            assertEquals(preview.generationId,f.layout.activeGenerationId())
            assertTrue(f.layout.activeDatabase().isFile)
        }finally {f.close()}
    }
    @Test fun actualReopenFailureRollsBackWithoutMarkingVerified() {
        val f=Fixture()
        try {
            val old=f.installCurrentGeneration()
            val preview=f.wizard.prepare(f.archive(),f.recovery){f.isolated(it)}
            var called=false
            assertTrue(runCatching {f.wizard.commit(preview){called=true;throw java.io.IOException("Synthetic reopen failed")}}.isFailure)
            assertTrue(called);assertEquals(old,f.layout.activeGenerationId())
            assertTrue(f.layout.generationDatabase(old).isFile);assertNull(f.journal.read())
        }finally {f.close()}
    }
    @Test fun secondPrepareCannotDestroyPendingRestoreJournal() {
        val f=Fixture()
        try {
            val archive=f.archive();val preview=f.wizard.prepare(archive,f.recovery){f.isolated(it)}
            assertTrue(runCatching {f.wizard.prepare(archive,f.recovery){f.isolated(it)}}.isFailure)
            assertEquals(preview.generationId,f.journal.targetId());assertEquals(RestoreStage.PREPARED,f.journal.read())
        }finally {f.close()}
    }
    @Test fun oldGenerationCannotBeReclaimedBeforeValidation() {
        val f=Fixture()
        try {
            val old=f.installCurrentGeneration();f.wizard.prepare(f.archive(),f.recovery){f.isolated(it)}
            assertTrue(runCatching {f.wizard.reclaimPrevious()}.isFailure)
            assertTrue(f.layout.generationDatabase(old).isFile)
        }finally {f.close()}
    }    @Test fun cancelledPrepareKeepsOldPointerAndNoPreparedJournal() {
        val f=Fixture()
        try {
            val old=f.installCurrentGeneration();val archive=f.archive()
            val error=runCatching {f.wizard.prepare(archive,f.recovery,checkpoint={throw kotlinx.coroutines.CancellationException("Synthetic cancellation")}){f.isolated(it)}}.exceptionOrNull()
            assertTrue(error is kotlinx.coroutines.CancellationException)
            assertEquals(old,f.layout.activeGenerationId());assertNull(f.journal.read())
            assertTrue(f.layout.generationDatabase(old).isFile)
        }finally {f.close()}
    }}
