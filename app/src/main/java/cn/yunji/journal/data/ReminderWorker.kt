package cn.yunji.journal.data

import android.content.Context
import androidx.work.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        EntryPointAccessors.fromApplication(applicationContext, ReminderDependencies::class.java).scheduler().rebuild()
        Result.success()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { Result.retry() }

    companion object {
        fun enqueue(context: Context): Operation =
            WorkManager.getInstance(context).enqueueUniqueWork("reminder-rebuild", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderWorker>().build())
        fun recover(context: Context) {
            enqueue(context)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("reminder-refresh", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS).build())
        }
    }
}
