package cn.yunji.journal.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last cleanup attempt as shown in the cloud settings screen. This is display metadata only: the daily
 * throttle still lives in [CloudSchedulerStore.lastCleanupAt], and the orphan grace ledger stays in
 * [CloudCleanupStore]. Skipped attempts keep their reason so the user can tell why nothing was deleted.
 */
data class CleanupRecord(
    val attemptedAt: Long = 0,
    val ran: Boolean = false,
    val message: String = "",
    val kept: Int = 0,
    val commitsDeleted: Int = 0,
    val objectsDeleted: Int = 0,
    val orphansTracked: Int = 0,
) {
    val hasRecord get() = attemptedAt > 0

    companion object {
        fun of(outcome: CleanupOutcome, at: Long) = CleanupRecord(attemptedAt = at, ran = outcome.ran,
            message = outcome.message.take(200), kept = outcome.kept, commitsDeleted = outcome.commitsDeleted,
            objectsDeleted = outcome.objectsDeleted, orphansTracked = outcome.orphansTracked)

        fun failed(at: Long, message: String) = CleanupRecord(attemptedAt = at, ran = false,
            message = message.take(200).ifBlank { "云端清理未完成" })
    }
}

/** Narrow boundary so the controller and its JVM tests never depend on Android preferences. */
interface CleanupStatusAccess {
    fun load(): CleanupRecord
    fun record(record: CleanupRecord)
    fun clear()
}

@Singleton
class CloudCleanupStatusStore @Inject constructor(@ApplicationContext context: Context) : CleanupStatusAccess {
    private val prefs = context.applicationContext.getSharedPreferences("cloud-cleanup-status", Context.MODE_PRIVATE)

    @Synchronized override fun load(): CleanupRecord = decodeCleanupRecord(prefs.all)

    @Synchronized override fun record(record: CleanupRecord) {
        prefs.edit { clear(); applyCleanupRecord(record) }
    }

    @Synchronized override fun clear() { prefs.edit { clear() } }
}

internal fun cleanupRecordValues(record: CleanupRecord): Map<String, Any> = mapOf(
    "attemptedAt" to record.attemptedAt, "ran" to record.ran, "message" to record.message,
    "kept" to record.kept, "commitsDeleted" to record.commitsDeleted,
    "objectsDeleted" to record.objectsDeleted, "orphansTracked" to record.orphansTracked)

internal fun decodeCleanupRecord(values: Map<String, *>): CleanupRecord = CleanupRecord(
    attemptedAt = (values["attemptedAt"] as? Number)?.toLong() ?: 0,
    ran = values["ran"] as? Boolean ?: false,
    message = values["message"] as? String ?: "",
    kept = (values["kept"] as? Number)?.toInt() ?: 0,
    commitsDeleted = (values["commitsDeleted"] as? Number)?.toInt() ?: 0,
    objectsDeleted = (values["objectsDeleted"] as? Number)?.toInt() ?: 0,
    orphansTracked = (values["orphansTracked"] as? Number)?.toInt() ?: 0,
)

private fun android.content.SharedPreferences.Editor.applyCleanupRecord(record: CleanupRecord) = apply {
    putLong("attemptedAt", record.attemptedAt)
    putBoolean("ran", record.ran)
    putString("message", record.message)
    putInt("kept", record.kept)
    putInt("commitsDeleted", record.commitsDeleted)
    putInt("objectsDeleted", record.objectsDeleted)
    putInt("orphansTracked", record.orphansTracked)
}
