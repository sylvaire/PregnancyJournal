package cn.yunji.journal.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import cn.yunji.journal.MainActivity
import cn.yunji.journal.domain.ReminderEvent

/** Explicit alarm/action intents; no medical text is exposed outside the app sandbox. */
object ReminderContract {
    const val ACTION_ALARM = "cn.yunji.journal.REMINDER_ALARM"
    const val ACTION_TAKEN = "cn.yunji.journal.REMINDER_TAKEN"
    const val ACTION_SNOOZE = "cn.yunji.journal.REMINDER_SNOOZE"
    const val EXTRA_KEY = "reminder-key"
    const val EXTRA_GENERATION = "reminder-generation"
    const val EXTRA_PREGNANCY = "reminder-pregnancy"
    const val EXTRA_TYPE = "reminder-type"
    const val EXTRA_TARGET = "reminder-target"
    const val EXTRA_OCCURRENCE = "reminder-occurrence"
    const val EXTRA_PLAN = "reminder-plan"
    const val EXTRA_TRIGGER = "reminder-trigger"
    const val EXTRA_TITLE = "reminder-title"
    const val EXTRA_BODY = "reminder-body"
    const val EXTRA_PUBLIC = "reminder-public"

    fun notificationId(key: String): Int = (key.hashCode() and 0x7fffffff)

    fun alarmIntent(context: Context, event: ReminderEvent, generation: Long): Intent =
        Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_ALARM).putExtras(event, generation)

    fun eventFrom(intent: Intent): ReminderEvent? {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return null
        val type = intent.getStringExtra(EXTRA_TYPE) ?: return null
        val target = intent.getStringExtra(EXTRA_TARGET) ?: return null
        val pregnancy = intent.getStringExtra(EXTRA_PREGNANCY) ?: return null
        return ReminderEvent(
            key = key, pregnancyId = pregnancy, targetType = type, targetId = target,
            occurrenceId = intent.getStringExtra(EXTRA_OCCURRENCE), planVersionId = intent.getStringExtra(EXTRA_PLAN),
            triggerAt = intent.getLongExtra(EXTRA_TRIGGER, 0L),
            title = intent.getStringExtra(EXTRA_TITLE) ?: "孕迹提醒",
            body = intent.getStringExtra(EXTRA_BODY).orEmpty(),
            publicBody = intent.getStringExtra(EXTRA_PUBLIC) ?: "孕迹提醒：有一条待确认记录",
        )
    }

    fun Intent.putExtras(event: ReminderEvent, generation: Long): Intent = apply {
        putExtra(EXTRA_KEY, event.key); putExtra(EXTRA_GENERATION, generation)
        putExtra(EXTRA_PREGNANCY, event.pregnancyId); putExtra(EXTRA_TYPE, event.targetType)
        putExtra(EXTRA_TARGET, event.targetId); putExtra(EXTRA_OCCURRENCE, event.occurrenceId)
        putExtra(EXTRA_PLAN, event.planVersionId); putExtra(EXTRA_TRIGGER, event.triggerAt)
    }

    fun contentIntent(context: Context, event: ReminderEvent): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_KEY, event.key).putExtra(EXTRA_TYPE, event.targetType).putExtra(EXTRA_TARGET, event.targetId)
        return PendingIntent.getActivity(context, notificationId(event.key), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
