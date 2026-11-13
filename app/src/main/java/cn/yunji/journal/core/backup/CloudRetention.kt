package cn.yunji.journal.core.backup

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.IsoFields

/** A remote snapshot that has already been authenticated and whose object references are known. */
data class SnapshotRef(
    val snapshotId: String,
    val createdAt: Long,
    val writerId: String,
    val capturedSequence: Long,
    val objectIds: Set<String>,
)

/** What the remote directory currently contains. Age comes from the server's last-modified value. */
data class RemoteObject(val objectId: String, val lastModified: Long)

data class RemoteInventory(
    val commitIds: Set<String>,
    val objects: List<RemoteObject>,
    /** False when any listing was partial or failed; automatic cleanup must then stop. */
    val listingComplete: Boolean,
)

data class RetentionSelection(val keep: List<String>, val retire: List<String>)

data class CleanupPlan(
    val keptSnapshotIds: List<String>,
    val commitsToDelete: List<String>,
    /** Objects that just lost their last retained reference; recorded now and only removable after grace. */
    val newlyOrphaned: Map<String, Long>,
    /** Previously recorded orphans that are past the grace period and still unreferenced. */
    val objectsToDelete: List<String>,
    /** Ledger entries whose object no longer exists or became referenced again. */
    val ledgerEntriesToDrop: List<String>,
)

sealed interface CleanupDecision {
    data class Plan(val plan: CleanupPlan) : CleanupDecision
    data class Stopped(val reason: String) : CleanupDecision
}

/**
 * Remote snapshot retention and reference-preserving cleanup.
 *
 * Rules: keep the newest complete snapshot plus the newest snapshot of each of the last 7 days and each
 * of the last 4 ISO weeks; retire the rest. Never delete an object that a kept snapshot still references,
 * and never delete an object until it has been orphaned for the full grace period.
 */
object CloudRetentionPolicy {
    const val DAILY_KEEP = 7
    const val WEEKLY_KEEP = 4
    /** Orphaned objects stay at least this long before any cleanup may remove them. */
    const val ORPHAN_GRACE_MS = 30L * 24 * 60 * 60 * 1000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun dayKey(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun weekKey(epochMillis: Long): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return "${date.get(IsoFields.WEEK_BASED_YEAR)}-W${date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
    }

    /** Newest first, deterministically ordered so the same input always yields the same retention. */
    private fun newestFirst(snapshots: List<SnapshotRef>): List<SnapshotRef> =
        snapshots.sortedWith(compareByDescending<SnapshotRef> { it.capturedSequence }
            .thenByDescending { it.createdAt }.thenBy { it.snapshotId })

    /**
     * @param now reference instant; retention windows are measured back from here so an old vault cannot
     *   pin unbounded history merely because it is the newest sample of its week.
     */
    fun select(snapshots: List<SnapshotRef>, now: Long): RetentionSelection {
        require(snapshots.map { it.snapshotId }.distinct().size == snapshots.size) { "重复的快照标识" }
        if (snapshots.isEmpty()) return RetentionSelection(emptyList(), emptyList())
        val ordered = newestFirst(snapshots)
        val keep = linkedSetOf<String>()
        // The newest complete snapshot is always restorable, independent of bucket arithmetic.
        keep += ordered.first().snapshotId
        // Newest snapshot of each of the last 7 days, then of each of the last 4 ISO weeks.
        val dailyWindow = now - DAILY_KEEP * DAY_MS
        ordered.filter { it.createdAt > dailyWindow }.groupBy { dayKey(it.createdAt) }
            .entries.sortedByDescending { it.key }.take(DAILY_KEEP).forEach { keep += it.value.first().snapshotId }
        val weeklyWindow = now - WEEKLY_KEEP * 7 * DAY_MS
        ordered.filter { it.createdAt > weeklyWindow }.groupBy { weekKey(it.createdAt) }
            .entries.sortedByDescending { it.key }.take(WEEKLY_KEEP).forEach { keep += it.value.first().snapshotId }
        val kept = ordered.filter { it.snapshotId in keep }.map { it.snapshotId }
        val retired = ordered.filter { it.snapshotId !in keep }.map { it.snapshotId }
        return RetentionSelection(kept, retired)
    }
}

/**
 * Plans one cleanup pass. Deleting commits and deleting objects are separate steps: this pass retires
 * commits and records newly orphaned objects; objects become deletable only in a later pass, once they
 * have been orphaned for the whole grace period.
 */
object CloudCleanupPlanner {
    fun plan(
        inventory: RemoteInventory,
        verifiedSnapshots: List<SnapshotRef>,
        knownWriterIds: Set<String>,
        localWriterId: String,
        orphanedSince: Map<String, Long>,
        now: Long,
    ): CleanupDecision {
        if (!inventory.listingComplete) return CleanupDecision.Stopped("云端目录列表不完整，已停止自动清理")
        if (knownWriterIds.any { it != localWriterId })
            return CleanupDecision.Stopped("检测到其他设备的备份，已停止自动清理")
        // Every commit present on the remote must have been authenticated; an unverified one blocks cleanup.
        val verifiedIds = verifiedSnapshots.map { it.snapshotId }.toSet()
        val unverified = inventory.commitIds - verifiedIds
        if (unverified.isNotEmpty()) return CleanupDecision.Stopped("云端存在未通过校验的备份，已停止自动清理")

        val selection = CloudRetentionPolicy.select(verifiedSnapshots, now)
        val byId = verifiedSnapshots.associateBy { it.snapshotId }
        val keptRefs = selection.keep.map { requireNotNull(byId[it]) }.flatMap { it.objectIds }.toSet()

        val commitsToDelete = selection.retire.filter { it in inventory.commitIds }.sorted()
        val retiredRefs = selection.retire.flatMap { requireNotNull(byId[it]).objectIds }.toSet()

        val present = inventory.objects.associateBy { it.objectId }
        // Referenced again by a kept snapshot: stop tracking it as an orphan.
        val ledgerEntriesToDrop = orphanedSince.keys.filter { it in keptRefs || it !in present }.sorted()
        val tracked = orphanedSince.filterKeys { it !in ledgerEntriesToDrop }

        val newlyOrphaned = (retiredRefs - keptRefs - tracked.keys)
            .filter { it in present }
            .associateWith { now }

        // Grace runs from the later of "recorded as orphaned" and the server's last modification, so an
        // object that was rewritten after being orphaned starts its grace period again.
        val objectsToDelete = tracked.filter { (id, since) ->
            val lastModified = present[id]?.lastModified ?: 0L
            now - maxOf(since, lastModified) >= CloudRetentionPolicy.ORPHAN_GRACE_MS
        }.keys.sorted()

        return CleanupDecision.Plan(CleanupPlan(
            keptSnapshotIds = selection.keep,
            commitsToDelete = commitsToDelete,
            newlyOrphaned = newlyOrphaned,
            objectsToDelete = objectsToDelete,
            ledgerEntriesToDrop = ledgerEntriesToDrop,
        ))
    }
}
