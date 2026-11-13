package cn.yunji.journal.core.backup

/**
 * Pure decision rules for the single cloud backup task: when to run, when to wait, and how retries back off.
 * Keeping this free of Android lets the scheduling matrix be tested without a device.
 */
object CloudBackupPolicy {
    /** Short coalescing delay after a local edit so bursts collapse into one upload. */
    const val COALESCE_MS = 30_000L
    /** Periodic compensation; the system may run it later than requested. */
    const val PERIODIC_COMPENSATION_MS = 6 * 60 * 60 * 1000L
    const val BASE_RETRY_MS = 60_000L
    const val MAX_RETRY_MS = 6 * 60 * 60 * 1000L
    const val MAX_FAILURES_BEFORE_MANUAL = 5

    data class Gate(
        val primary: Boolean,
        val autoEnabled: Boolean,
        val blockedByOtherWriter: Boolean,
        val failures: Int,
        val pendingChanges: Boolean,
        val question: String,
    )

    /** @return whether an automatic attempt may start now, and why not. */
    fun allowAutomatic(state: Gate): Boolean =
        state.primary && state.autoEnabled && !state.blockedByOtherWriter && state.pendingChanges &&
            state.failures < MAX_FAILURES_BEFORE_MANUAL

    fun reason(state: Gate): String? = when {
        !state.primary -> "本机不是主设备，需先确认主设备后才能自动备份"
        state.blockedByOtherWriter -> "检测到其他设备的新备份，已暂停自动备份，请确认主设备"
        !state.autoEnabled -> "自动备份未开启"
        !state.pendingChanges -> "没有待备份的新记录"
        state.failures >= MAX_FAILURES_BEFORE_MANUAL -> "多次备份失败，请手动重试或检查连接"
        else -> null
    }

    /** Exponential backoff with a hard ceiling; never zero so a broken connection cannot hot-loop. */
    fun retryDelay(failures: Int): Long {
        require(failures >= 0)
        if (failures <= 0) return 0
        var delay = BASE_RETRY_MS
        repeat((failures - 1).coerceAtMost(16)) {
            delay = if (delay >= MAX_RETRY_MS / 2) MAX_RETRY_MS else delay * 2
        }
        return delay.coerceIn(BASE_RETRY_MS, MAX_RETRY_MS)
    }

    data class Attempt(val pendingSince: Long, val lastBackupAt: Long, val capturedSequence: Long)

    /** True when new local changes exist after the last successful snapshot. */
    fun hasPending(changedAt: Long, lastBackedUpCapturedSequence: Long, currentCapturedSequence: Long, hasPriorSuccess: Boolean): Boolean =
        if (!hasPriorSuccess) currentCapturedSequence > 0 || changedAt > 0
        else currentCapturedSequence > lastBackedUpCapturedSequence

    /** Two devices observed in the same backup directory cannot both auto-write. */
    fun foreignWriterDetected(ownWriterId: String, commitWriterIds: Collection<String>): Boolean =
        commitWriterIds.any { it != ownWriterId }

    /** Acknowledging a branch permits its existing commits, never future commits from that branch. */
    fun unconfirmedForeignCommits(ownWriterId: String, writersByCommit: Map<String, String>,
                                  acknowledgedCommitIds: Set<String>): Set<String> =
        writersByCommit.filter { (id, writer) -> writer != ownWriterId && id !in acknowledgedCommitIds }.keys

    /** A late/duplicate trigger must not start a second concurrent upload. */
    fun shouldStart(alreadyRunning: Boolean, alreadyQueued: Boolean): Boolean = !alreadyRunning && !alreadyQueued

    /** Remote cleanup is infrequent by design; it deletes history and must not run on every upload. */
    const val CLEANUP_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Cleanup pauses whenever a second writer is present, matching the automatic-backup rule. */
    fun shouldCleanup(lastCleanupAt: Long, now: Long, blockedByOtherWriter: Boolean): Boolean =
        !blockedByOtherWriter && (lastCleanupAt <= 0 || now - lastCleanupAt >= CLEANUP_INTERVAL_MS)
}
