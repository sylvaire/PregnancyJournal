package cn.yunji.journal

import cn.yunji.journal.core.backup.TransientWorkspaceCleanup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class TransientWorkspaceCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun deletesOnlyRecognizedInactiveJobsAndKeepsOtherFiles() {
        val root = temporary.newFolder("export-work")
        val orphan = File(root, "job-${UUID.randomUUID()}").apply { mkdirs() }
        File(orphan, "original.png").writeText("synthetic plaintext")
        val active = File(root, "job-${UUID.randomUUID()}").apply { mkdirs() }
        File(active, "in-use").writeText("synthetic")
        val unrelated = File(root, "keep-unrelated").apply { mkdirs() }
        File(unrelated, "keep").writeText("synthetic")
        val lookalike = File(root, "job-${UUID.randomUUID()}-extra").apply { mkdirs() }

        TransientWorkspaceCleanup.recover(root, setOf(TransientWorkspaceCleanup.exportJob), setOf(active.name))

        assertFalse(orphan.exists())
        assertTrue(File(active, "in-use").isFile)
        assertTrue(File(unrelated, "keep").isFile)
        assertTrue(lookalike.isDirectory)
    }

    @Test fun restoreRecoveryRemovesNestedVerificationAndLeavesUnrelatedDirectory() {
        val root = temporary.newFolder("restore-validation")
        val stale = File(root, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        val verify = File(stale, "backup-verify-${UUID.randomUUID()}").apply { mkdirs() }
        File(verify, "authenticated-original").writeText("synthetic plaintext")
        val directVerify = File(root, "backup-verify-${UUID.randomUUID()}").apply { mkdirs() }
        File(directVerify, "authenticated-original").writeText("synthetic plaintext")
        val unrelated = File(root, "keep-unrelated").apply { mkdirs() }

        val names = setOf(TransientWorkspaceCleanup.restoreStaging, TransientWorkspaceCleanup.backupVerification)
        TransientWorkspaceCleanup.recover(root, names, emptySet())
        TransientWorkspaceCleanup.recover(root, names, emptySet())

        assertFalse(stale.exists())
        assertFalse(directVerify.exists())
        assertTrue(unrelated.isDirectory)
    }
}
