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

/** Alarm callback validates generation and business state before showing anything. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = ReminderContract.eventFrom(intent) ?: return
        val generation = intent.getLongExtra(ReminderContract.EXTRA_GENERATION, 0L)
        val pending = goAsync()
        val application = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dependencies = EntryPointAccessors.fromApplication(application, ReminderDependencies::class.java)
                val scheduler = dependencies.scheduler()
                val posted=scheduler.deliverAlarm(event,generation)
                if(BuildConfig.DEBUG)Log.d("ReminderAlarmReceiver","delivery attempted posted=$posted")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: cn.yunji.journal.core.security.VaultLockedException) {
                if(BuildConfig.DEBUG)Log.d("ReminderAlarmReceiver","vault locked; attempting private fallback")
                val store=ReminderStore(application)
                if(cn.yunji.journal.domain.ReminderRules.isCurrentGeneration(generation,store.generation()) &&
                    !store.wasDelivered(event.key,event.triggerAt)) {
                    val posted=EntryPointAccessors.fromApplication(application,ReminderDependencies::class.java)
                        .notifier().post(event,generation)
                    if(posted)store.markDelivered(event.key,event.triggerAt)
                }
            }
            catch (error: Exception) {
                if(BuildConfig.DEBUG)Log.w("ReminderAlarmReceiver","cold delivery failed: ${error::class.java.simpleName}")
                /* Keep private encrypted data intact when the key is unavailable. */
            }
            finally { pending.finish() }
        }
    }
}
