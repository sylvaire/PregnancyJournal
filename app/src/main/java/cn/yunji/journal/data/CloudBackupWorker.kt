package cn.yunji.journal.data

import android.content.Context
import androidx.work.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CloudBackupDependencies {
    fun runner(): CloudBackupAccess
    fun schedulerStore(): CloudSchedulerStore
    fun cleanupRunner(): CloudCleanupAccess
}

/**
 * The only cloud backup worker. Unique work with REPLACE semantics collapses bursts of local edits into
 * one pending task; the vault-level gate in [CloudBackupRunner] prevents concurrent attempts even if a
 * second trigger slips through.
 */
class CloudBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, CloudBackupDependencies::class.java)
        val state = dependencies.schedulerStore()
        val gate = state.load()
        val runner = dependencies.runner()
        if (runner.isRunning()) return Result.retry()
        if (!gate.autoActive) return Result.success()
        if (gate.nextAttemptAt > System.currentTimeMillis()) return Result.retry()
        return try {
            val outcome = runner.upload()
            if (outcome.success) {
                state.clearFailures()
                runCatching { BackupFailureNotifier(applicationContext).clear() }
                state.setLastBackedUp(outcome.status.lastCapturedSequence)
                state.setNextAttemptAt(0)
                runRetentionCleanup(dependencies, state)
                Result.success()
            } else {
                val failures = state.load().failures + 1
                state.recordFailure()
                runCatching { BackupFailureNotifier(applicationContext).postIfRequired(state) }
                state.setNextAttemptAt(System.currentTimeMillis() + cn.yunji.journal.core.backup.CloudBackupPolicy.retryDelay(failures))
                Result.retry()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            val failures = state.load().failures + 1
            state.recordFailure()
            runCatching { BackupFailureNotifier(applicationContext).postIfRequired(state) }
            state.setNextAttemptAt(System.currentTimeMillis() + cn.yunji.journal.core.backup.CloudBackupPolicy.retryDelay(failures))
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "cloud-backup-upload"

        /**
         * Retention cleanup runs after a successful upload at most once a day. A cleanup failure must never
         * turn a completed backup into a failed one, and must never be retried in a tight loop.
         */
        private suspend fun runRetentionCleanup(dependencies: CloudBackupDependencies, state: CloudSchedulerStore) {
            val now = System.currentTimeMillis()
            val current = state.load()
            if (!cn.yunji.journal.core.backup.CloudBackupPolicy.shouldCleanup(
                    current.lastCleanupAt, now, current.blockedByOtherWriter)) return
            runCatching { dependencies.cleanupRunner().runOnce() }
                .onSuccess { state.setLastCleanupAt(now) }
        }

        /** Coalesced trigger after local edits. Replacing is intentional: only the newest request matters. */
        fun request(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (CloudSchedulerStore(context).load().wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CloudBackupWorker>()
                    .setConstraints(constraints)
                    .setInitialDelay(cn.yunji.journal.core.backup.CloudBackupPolicy.COALESCE_MS, TimeUnit.MILLISECONDS)
                    .build())
        }

        /** User-initiated upload ignores the coalescing delay but keeps the same unique name. */
        fun requestNow(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CloudBackupWorker>().setConstraints(constraints).build())
        }

        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE) }

        fun recover(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("cloud-backup-compensation", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CloudBackupWorker>(
                    cn.yunji.journal.core.backup.CloudBackupPolicy.PERIODIC_COMPENSATION_MS, TimeUnit.MILLISECONDS).build())
        }
    }
}
