package cn.yunji.journal.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import cn.yunji.journal.domain.ReminderRules

/** Old installed notification broadcasts may only offer a new authenticated Activity action. */
class ReminderActionReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val event=ReminderContract.eventFrom(intent) ?: return
        if(intent.action !in setOf(ReminderContract.ACTION_TAKEN,ReminderContract.ACTION_SNOOZE))return
        val generation=intent.getLongExtra(ReminderContract.EXTRA_GENERATION,0)
        if(!ReminderRules.isCurrentGeneration(generation,ReminderStore(context.applicationContext).generation()))return
        EntryPointAccessors.fromApplication(context.applicationContext,ReminderDependencies::class.java).notifier().post(event,generation)
    }
}
