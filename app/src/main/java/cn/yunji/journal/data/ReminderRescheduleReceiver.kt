package cn.yunji.journal.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cn.yunji.journal.BuildConfig
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/** Rebuilds the 7-day projection after reboot, clock/time-zone changes or package update. */
class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_USER_UNLOCKED)) {
            val pending=goAsync()
            val application=context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Keep the broadcast alive until the alarm projection is persisted.
                    // A boot-time process can be reclaimed immediately after enqueueing a Worker.
                    withTimeout(8_000) {
                        EntryPointAccessors.fromApplication(application,ReminderDependencies::class.java)
                            .scheduler().rebuild()
                    }
                } catch(error:Exception) {
                    if(BuildConfig.DEBUG)Log.w("ReminderReschedule","direct rebuild failed: ${error::class.java.simpleName}")
                    runCatching {ReminderWorker.enqueue(application).result.get(1,TimeUnit.SECONDS)}
                } finally {pending.finish()}
            }
        }
    }
}
