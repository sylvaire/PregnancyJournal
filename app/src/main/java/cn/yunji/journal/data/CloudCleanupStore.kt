package cn.yunji.journal.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable ledger of objects whose last retained reference disappeared.
 *
 * The grace period must survive restarts, otherwise a reboot would let the next pass delete an object
 * that was orphaned moments ago. Only random object identifiers are stored here.
 */
@Singleton
class CloudCleanupStore @Inject constructor(@param:ApplicationContext context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud-cleanup-ledger", Context.MODE_PRIVATE)
    private val maxEntries = 5000

    @Synchronized fun orphanedSince(): Map<String, Long> =
        prefs.all.entries.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()

    @Synchronized fun record(entries: Map<String, Long>) {
        if (entries.isEmpty()) return
        // Existing entries win: the grace period starts when an object first became orphaned and a later
        // observation must never extend it.
        val merged = entries + orphanedSince()
        // Bound the ledger so a long-lived vault cannot grow preferences without limit.
        val bounded = if (merged.size <= maxEntries) merged
        else merged.entries.sortedByDescending { it.value }.take(maxEntries).associate { it.key to it.value }
        prefs.edit {
            clear()
            bounded.forEach { (id, at) -> putLong(id, at) }
        }
    }

    @Synchronized fun forget(ids: Collection<String>) {
        if (ids.isEmpty()) return
        prefs.edit { ids.forEach { remove(it) } }
    }

    @Synchronized fun clear() { prefs.edit { clear() } }
}
