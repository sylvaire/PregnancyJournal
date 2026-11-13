package cn.yunji.journal.data

import android.content.Context
import androidx.work.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class TrashWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        EntryPointAccessors.fromApplication(applicationContext, ImportDependencies::class.java).importer().trashRepository.resume()
        Result.success()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { Result.retry() }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("trash-cleanup", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<TrashWorker>().build())
        }
        fun recover(context: Context) {
            enqueue(context)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("trash-retention", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TrashWorker>(24, TimeUnit.HOURS).build())
        }
    }
}
