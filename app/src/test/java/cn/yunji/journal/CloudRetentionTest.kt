package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Retention buckets and reference-preserving cleanup decisions. Pure rules, no network or device. */
class CloudRetentionTest {
    private val day = 24L * 60 * 60 * 1000
    /** 2026-10-21T00:00:00Z as a stable reference point. */
    private val base = Instant.parse("2026-10-21T00:00:00Z").toEpochMilli()

    private fun ref(id: String, at: Long, objects: Set<String> = emptySet(), writer: String = "writer-a",
                    sequence: Long = 1) =
        SnapshotRef(snapshotId = id, createdAt = at, writerId = writer, capturedSequence = sequence, objectIds = objects)

    private fun inventory(commits: Set<String>, objects: List<RemoteObject>, complete: Boolean = true) =
        RemoteInventory(commitIds = commits, objects = objects, listingComplete = complete)

    private fun plan(decision: CleanupDecision): CleanupPlan {
        assertTrue("Expected a plan but cleanup stopped: ${(decision as? CleanupDecision.Stopped)?.reason}",
            decision is CleanupDecision.Plan)
        return (decision as CleanupDecision.Plan).plan
    }

    @Test fun newestSnapshotIsAlwaysKept() {
        val snapshots = listOf(ref("a", base - 400 * day), ref("b", base - 300 * day))
        val selection = CloudRetentionPolicy.select(snapshots, base)
        assertTrue("The newest complete snapshot must always be restorable", "b" in selection.keep)
        assertTrue("a" in selection.retire)
    }

    @Test fun keepsNewestOfEachOfTheLastSevenDaysAndFourWeeks() {
        // One snapshot per day for 40 consecutive days, ending yesterday.
        val snapshots = (1..40).map { ref("s$it", base - it * day, sequence = (41 - it).toLong()) }
        val selection = CloudRetentionPolicy.select(snapshots, base)
        // Everything inside the last 6 days is retained by the daily rule.
        (1..6).forEach { assertTrue("day $it must be kept", "s$it" in selection.keep) }
        // Weekly retention must reach past the daily window.
        assertTrue("Weekly retention must keep history older than the daily window",
            selection.keep.map { it.removePrefix("s").toInt() }.any { it > 6 })
        selection.keep.forEach { id ->
            val index = id.removePrefix("s").toInt()
            assertTrue("A kept snapshot must be inside the 28-day window, not $index days old", index <= 28)
        }
        assertTrue("Retention must stay bounded by 7 daily + 4 weekly", selection.keep.size <= 12)
        // Anything outside the 28-day weekly window is retired; only the newest snapshot is exempt.
        (29..40).forEach { assertTrue("s$it must be retired", "s$it" in selection.retire) }
    }

    @Test fun selectionIsDeterministicAndRejectsDuplicates() {
        val snapshots = listOf(ref("a", base - day, sequence = 1), ref("b", base - 2 * day, sequence = 2))
        assertEquals(CloudRetentionPolicy.select(snapshots, base), CloudRetentionPolicy.select(snapshots.reversed(), base))
        assertTrue(runCatching { CloudRetentionPolicy.select(listOf(ref("a", base, sequence = 1), ref("a", base - day, sequence = 2)), base) }.isFailure)
        assertEquals(RetentionSelection(emptyList(), emptyList()), CloudRetentionPolicy.select(emptyList(), base))
    }

    @Test fun incompleteListingStopsCleanup() {
        val decision = CloudCleanupPlanner.plan(inventory(setOf("a"), emptyList(), complete = false),
            listOf(ref("a", base)), setOf("writer-a"), "writer-a", emptyMap(), base)
        assertTrue(decision is CleanupDecision.Stopped)
        assertTrue((decision as CleanupDecision.Stopped).reason.contains("列表不完整"))
    }

    @Test fun otherDeviceStopsCleanup() {
        val decision = CloudCleanupPlanner.plan(inventory(setOf("a"), emptyList()),
            listOf(ref("a", base)), setOf("writer-a", "writer-b"), "writer-a", emptyMap(), base)
        assertTrue(decision is CleanupDecision.Stopped)
        assertTrue((decision as CleanupDecision.Stopped).reason.contains("其他设备"))
    }

    @Test fun unverifiedCommitStopsCleanup() {
        // The remote has a commit this device never authenticated; deleting around it would be unsafe.
        val decision = CloudCleanupPlanner.plan(inventory(setOf("a", "unknown"), emptyList()),
            listOf(ref("a", base)), setOf("writer-a"), "writer-a", emptyMap(), base)
        assertTrue(decision is CleanupDecision.Stopped)
        assertTrue((decision as CleanupDecision.Stopped).reason.contains("未通过校验"))
    }

    @Test fun retiredCommitOrphansItsObjectsButNothingIsDeletedInTheSamePass() {
        val old = ref("old", base - 60 * day, objects = setOf("object-1"), sequence = 1)
        val recent = ref("recent", base - day, objects = setOf("object-2"), sequence = 2)
        val result = plan(CloudCleanupPlanner.plan(
            inventory(setOf("old", "recent"), listOf(RemoteObject("object-1", base - 60 * day), RemoteObject("object-2", base - day))),
            listOf(old, recent), setOf("writer-a"), "writer-a", emptyMap(), base))
        assertEquals(listOf("old"), result.commitsToDelete)
        assertEquals(mapOf("object-1" to base), result.newlyOrphaned)
        assertTrue("Objects must not be deleted in the pass that retires their commit", result.objectsToDelete.isEmpty())
        assertTrue("recent" in result.keptSnapshotIds)
    }

    @Test fun sharedObjectSurvivesWhileAnyKeptSnapshotReferencesIt() {
        val shared = "shared-object"
        val old = ref("old", base - 60 * day, objects = setOf(shared, "only-old"), sequence = 1)
        val recent = ref("recent", base - day, objects = setOf(shared), sequence = 2)
        val result = plan(CloudCleanupPlanner.plan(
            inventory(setOf("old", "recent"), listOf(RemoteObject(shared, base - 60 * day), RemoteObject("only-old", base - 60 * day))),
            listOf(old, recent), setOf("writer-a"), "writer-a", emptyMap(), base))
        assertFalse("A referenced object must never be orphaned", shared in result.newlyOrphaned)
        assertTrue("only-old" in result.newlyOrphaned)
    }

    @Test fun orphanIsDeletedOnlyAfterTheFullGracePeriod() {
        val old = ref("old", base - 90 * day, objects = setOf("orphan"), sequence = 1)
        val recent = ref("recent", base - day, objects = setOf("kept"), sequence = 2)
        val objects = listOf(RemoteObject("orphan", base - 90 * day), RemoteObject("kept", base - day))

        val tooEarly = plan(CloudCleanupPlanner.plan(
            inventory(setOf("old", "recent"), objects), listOf(old, recent), setOf("writer-a"), "writer-a",
            mapOf("orphan" to base - 10 * day), base))
        assertTrue("An orphan inside the grace period must be kept", tooEarly.objectsToDelete.isEmpty())

        val due = plan(CloudCleanupPlanner.plan(
            inventory(setOf("old", "recent"), objects), listOf(old, recent), setOf("writer-a"), "writer-a",
            mapOf("orphan" to base - 31 * day), base))
        assertEquals(listOf("orphan"), due.objectsToDelete)

        val exactlyAtGrace = plan(CloudCleanupPlanner.plan(
            inventory(setOf("old", "recent"), objects), listOf(old, recent), setOf("writer-a"), "writer-a",
            mapOf("orphan" to base - CloudRetentionPolicy.ORPHAN_GRACE_MS), base))
        assertEquals("The grace boundary itself is already due", listOf("orphan"), exactlyAtGrace.objectsToDelete)
    }

    @Test fun ledgerDropsEntriesThatBecameReferencedAgainOrNoLongerExist() {
        val recent = ref("recent", base - day, objects = setOf("revived"), sequence = 2)
        val result = plan(CloudCleanupPlanner.plan(
            inventory(setOf("recent"), listOf(RemoteObject("revived", base - 40 * day))),
            listOf(recent), setOf("writer-a"), "writer-a",
            mapOf("revived" to base - 40 * day, "vanished" to base - 40 * day), base))
        assertTrue("revived" in result.ledgerEntriesToDrop)
        assertTrue("vanished" in result.ledgerEntriesToDrop)
        assertTrue(result.objectsToDelete.isEmpty())
    }

    @Test fun commitNotPresentRemotelyIsNotPlannedForDeletion() {
        // A commit already removed on the remote must not appear as a deletion target.
        val old = ref("old", base - 60 * day, objects = emptySet(), sequence = 1)
        val recent = ref("recent", base - day, objects = emptySet(), sequence = 2)
        val result = plan(CloudCleanupPlanner.plan(
            inventory(setOf("recent"), emptyList()), listOf(old, recent), setOf("writer-a"), "writer-a", emptyMap(), base))
        assertTrue(result.commitsToDelete.isEmpty())
    }

    @Test fun rewrittenObjectRestartsItsGracePeriod() {
        val old = ref("old", base - 90 * day, objects = setOf("orphan"), sequence = 1)
        val recent = ref("recent", base - day, objects = setOf("kept"), sequence = 2)
        // Orphaned long ago, but the server reports the object was modified yesterday.
        val objects = listOf(RemoteObject("orphan", base - day), RemoteObject("kept", base - day))
        val result = plan(CloudCleanupPlanner.plan(
            inventory(setOf("old", "recent"), objects), listOf(old, recent), setOf("writer-a"), "writer-a",
            mapOf("orphan" to base - 40 * day), base))
        assertTrue("A rewritten object must not be deleted on the old grace period", result.objectsToDelete.isEmpty())
    }

    @Test fun longRunningVaultKeepsWithinDailyAndWeeklyBudgets() {
        // 400 daily snapshots: retention must stay bounded instead of growing forever.
        val snapshots = (0..399).map { ref("s$it", base - it * day, sequence = (400 - it).toLong()) }
        val selection = CloudRetentionPolicy.select(snapshots, base)
        assertTrue("Retention must stay bounded", selection.keep.size <= 7 + 4 + 1)
        assertTrue("Most snapshots must be retired", selection.retire.size >= 400 - 12)
    }
}
