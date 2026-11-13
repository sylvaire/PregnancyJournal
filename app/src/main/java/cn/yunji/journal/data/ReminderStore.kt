package cn.yunji.journal.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Non-medical scheduler state only: generation, enabled types and active alarm keys. */
@Singleton
class ReminderStore @Inject constructor(@param:ApplicationContext context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("reminder-state", Context.MODE_PRIVATE)

    @Synchronized fun generation(): Long = prefs.getLong("generation", 0L)
    @Synchronized fun nextGeneration(): Long {
        val next = generation() + 1
        prefs.edit { putLong("generation", next) }
        return next
    }
    @Synchronized fun visitsEnabled(): Boolean = prefs.getBoolean("visits-enabled", true)
    @Synchronized fun medicationsEnabled(): Boolean = prefs.getBoolean("medications-enabled", true)
    @Synchronized fun setVisitsEnabled(enabled: Boolean) { prefs.edit { putBoolean("visits-enabled", enabled) } }
    @Synchronized fun setMedicationsEnabled(enabled: Boolean) { prefs.edit { putBoolean("medications-enabled", enabled) } }
    @Synchronized fun notificationAsked(): Boolean = prefs.getBoolean("notification-asked", false)
    @Synchronized fun markNotificationAsked() { prefs.edit { putBoolean("notification-asked", true) } }

    @Synchronized fun scheduledKeys(): Set<String> = prefs.getStringSet("scheduled-keys", emptySet()).orEmpty().toSet()
    @Synchronized fun saveScheduledKeys(keys: Set<String>) { prefs.edit { putStringSet("scheduled-keys", keys) } }

    private fun deliveryToken(key: String, triggerAt: Long) = "$key|$triggerAt"
    @Synchronized fun wasMissed(key: String, triggerAt: Long): Boolean =
        prefs.getStringSet("missed-alarms", emptySet()).orEmpty().contains(deliveryToken(key, triggerAt))
    @Synchronized fun markMissed(key: String, triggerAt: Long) {
        val next = prefs.getStringSet("missed-alarms", emptySet()).orEmpty().toMutableSet()
        next += deliveryToken(key, triggerAt)
        check(prefs.edit().putStringSet("missed-alarms", next).commit()) {"未送达提醒状态未保存"}
    }
    @Synchronized fun clearMissed(key: String, triggerAt: Long) {
        val next = prefs.getStringSet("missed-alarms", emptySet()).orEmpty().toMutableSet()
        next -= deliveryToken(key, triggerAt)
        prefs.edit { putStringSet("missed-alarms", next) }
    }
    @Synchronized fun pruneMissed(before: Long) {
        val kept = prefs.getStringSet("missed-alarms", emptySet()).orEmpty().filterTo(mutableSetOf()) {
            it.substringAfterLast('|').toLongOrNull()?.let { at -> at >= before } == true
        }
        prefs.edit { putStringSet("missed-alarms", kept) }
    }
    @Synchronized fun wasDelivered(key: String, triggerAt: Long): Boolean =
        prefs.getStringSet("delivered-alarms", emptySet()).orEmpty().contains(deliveryToken(key, triggerAt))
    @Synchronized fun markDelivered(key: String, triggerAt: Long) {
        val next = prefs.getStringSet("delivered-alarms", emptySet()).orEmpty().toMutableSet()
        next += deliveryToken(key, triggerAt)
        check(prefs.edit().putStringSet("delivered-alarms", next).commit()) {"提醒投递状态未保存"}
    }
    @Synchronized fun pruneDelivered(before: Long) {
        val kept = prefs.getStringSet("delivered-alarms", emptySet()).orEmpty().filterTo(mutableSetOf()) {
            it.substringAfterLast('|').toLongOrNull()?.let { at -> at >= before } == true
        }
        prefs.edit { putStringSet("delivered-alarms", kept) }
    }

    @Synchronized fun snoozes(): Map<String, Long> = prefs.getStringSet("snoozes", emptySet()).orEmpty().mapNotNull { entry ->
        val index = entry.lastIndexOf('|')
        if (index <= 0) null else entry.substring(index + 1).toLongOrNull()?.let { entry.substring(0, index) to it }
    }.toMap()
    @Synchronized fun setSnooze(key: String, at: Long) {
        val next = snoozes().toMutableMap(); next[key] = at
        prefs.edit { putStringSet("snoozes", next.map { "${it.key}|${it.value}" }.toSet()) }
    }
    @Synchronized fun clearSnooze(key: String) {
        val next = snoozes().toMutableMap(); next.remove(key)
        prefs.edit { putStringSet("snoozes", next.map { "${it.key}|${it.value}" }.toSet()) }
    }
    @Synchronized fun pruneSnoozes(now: Long) {
        val next = snoozes().filterValues { it > now }
        prefs.edit { putStringSet("snoozes", next.map { "${it.key}|${it.value}" }.toSet()) }
    }
    /** Invalidate old PendingIntent actions while preserving notification consent and type preferences. */
    @Synchronized fun invalidateForRestore(): Set<String> {
        val old=scheduledKeys();val generation=generation()
        check(generation<Long.MAX_VALUE) {"提醒代际已用尽"}
        check(prefs.edit().putLong("generation",generation+1).remove("scheduled-keys").remove("snoozes")
            .remove("delivered-alarms").remove("missed-alarms").commit()) {"提醒停止状态未保存"}
        return old
    }}
