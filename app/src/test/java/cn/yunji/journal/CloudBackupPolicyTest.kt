package cn.yunji.journal

import cn.yunji.journal.core.backup.CloudBackupPolicy
import org.junit.Assert.*
import org.junit.Test

/** Pure scheduling rules: gating, backoff, duplicate suppression and pending-change detection. */
class CloudBackupPolicyTest {
    private fun gate(primary: Boolean = true, auto: Boolean = true, blocked: Boolean = false,
                     failures: Int = 0, pending: Boolean = true) =
        CloudBackupPolicy.Gate(primary, auto, blocked, failures, pending, "synthetic")

    @Test fun automaticUploadRequiresPrimaryAutoPendingAndNoBlock() {
        assertTrue(CloudBackupPolicy.allowAutomatic(gate()))
        assertFalse("Secondary device must not auto-write", CloudBackupPolicy.allowAutomatic(gate(primary = false)))
        assertFalse(CloudBackupPolicy.allowAutomatic(gate(auto = false)))
        assertFalse(CloudBackupPolicy.allowAutomatic(gate(blocked = true)))
        assertFalse(CloudBackupPolicy.allowAutomatic(gate(pending = false)))
        assertFalse(CloudBackupPolicy.allowAutomatic(gate(failures = CloudBackupPolicy.MAX_FAILURES_BEFORE_MANUAL)))
    }

    @Test fun eachRefusalHasAnExplicitReason() {
        assertTrue(requireNotNull(CloudBackupPolicy.reason(gate(primary = false))).contains("主设备"))
        assertTrue(requireNotNull(CloudBackupPolicy.reason(gate(blocked = true))).contains("其他设备"))
        assertTrue(requireNotNull(CloudBackupPolicy.reason(gate(auto = false))).contains("自动备份未开启"))
        assertTrue(requireNotNull(CloudBackupPolicy.reason(gate(pending = false))).contains("没有待备份"))
        assertNull(CloudBackupPolicy.reason(gate()))
    }

    @Test fun retryBackoffGrowsAndIsCapped() {
        assertEquals(0L, CloudBackupPolicy.retryDelay(0))
        assertEquals(CloudBackupPolicy.BASE_RETRY_MS, CloudBackupPolicy.retryDelay(1))
        assertEquals(CloudBackupPolicy.BASE_RETRY_MS * 2, CloudBackupPolicy.retryDelay(2))
        assertEquals(CloudBackupPolicy.BASE_RETRY_MS * 4, CloudBackupPolicy.retryDelay(3))
        val huge = CloudBackupPolicy.retryDelay(40)
        assertEquals(CloudBackupPolicy.MAX_RETRY_MS, huge)
        assertTrue("Backoff must never collapse to immediate retry", CloudBackupPolicy.retryDelay(9) > 0)
    }

    @Test fun pendingChangesCompareAgainstLastBackedUpSequence() {
        assertTrue("First ever backup with data is pending", CloudBackupPolicy.hasPending(0, 0, 5, false))
        assertFalse("Empty vault has nothing to back up", CloudBackupPolicy.hasPending(0, 0, 0, false))
        assertFalse("No new records since last snapshot", CloudBackupPolicy.hasPending(100, 42, 42, true))
        assertTrue("New records after last snapshot", CloudBackupPolicy.hasPending(100, 42, 43, true))
    }

    @Test fun foreignWriterAndDuplicateTriggersAreDetected() {
        assertFalse(CloudBackupPolicy.foreignWriterDetected("writer-a", listOf("writer-a")))
        assertTrue(CloudBackupPolicy.foreignWriterDetected("writer-a", listOf("writer-a", "writer-b")))
        assertFalse(CloudBackupPolicy.foreignWriterDetected("writer-a", emptyList()))
        assertTrue(CloudBackupPolicy.shouldStart(alreadyRunning = false, alreadyQueued = false))
        assertFalse("A running upload must not be duplicated", CloudBackupPolicy.shouldStart(true, false))
        assertFalse("An already queued upload must not be duplicated", CloudBackupPolicy.shouldStart(false, true))
    }

    @Test fun repeatedOwnBackupsAndAcknowledgedForeignHistoryDoNotMaskNewCommits() {
        val own = "own-writer"
        val remote = linkedMapOf("own-1" to own)
        assertTrue(CloudBackupPolicy.unconfirmedForeignCommits(own, remote, emptySet()).isEmpty())
        remote["own-2"] = own
        remote["own-3"] = own
        assertTrue("three consecutive commits from this installation are its own history",
            CloudBackupPolicy.unconfirmedForeignCommits(own, remote, emptySet()).isEmpty())
        remote["foreign-old"] = "other-writer"
        assertEquals(setOf("foreign-old"), CloudBackupPolicy.unconfirmedForeignCommits(own, remote, emptySet()))
        assertTrue(CloudBackupPolicy.unconfirmedForeignCommits(own, remote, setOf("foreign-old")).isEmpty())
        remote["foreign-new"] = "other-writer"
        assertEquals(setOf("foreign-new"),
            CloudBackupPolicy.unconfirmedForeignCommits(own, remote, setOf("foreign-old")))
    }

    @Test fun retentionCleanupIsDailyAndPausesForOtherDevices() {
        val now = 1_700_000_000_000
        assertTrue("A vault that never cleaned may clean", CloudBackupPolicy.shouldCleanup(0, now, false))
        assertTrue("Cleanup is due after the interval",
            CloudBackupPolicy.shouldCleanup(now - CloudBackupPolicy.CLEANUP_INTERVAL_MS, now, false))
        assertFalse("Cleanup must not run twice within the interval",
            CloudBackupPolicy.shouldCleanup(now - CloudBackupPolicy.CLEANUP_INTERVAL_MS + 1, now, false))
        assertFalse("A second device must pause automatic cleanup too", CloudBackupPolicy.shouldCleanup(0, now, true))
    }
}
