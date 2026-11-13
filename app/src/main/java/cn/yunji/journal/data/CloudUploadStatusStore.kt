package cn.yunji.journal.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Device-local upload outcome only. No medical content, no credentials, no remote identifiers beyond random IDs. */
data class CloudUploadStatus(
    val lastAttemptAt: Long = 0,
    val lastSuccessAt: Long = 0,
    val lastSnapshotId: String? = null,
    val lastWriterSequence: Long = 0,
    val lastCapturedSequence: Long = 0,
    val lastPhase: String = "",
    val lastError: String? = null,
) {
    val hasSuccess get() = lastSuccessAt > 0 && lastSnapshotId != null
}

@Singleton
class CloudUploadStatusStore @Inject constructor(@param:ApplicationContext context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud-upload-state", Context.MODE_PRIVATE)

    @Synchronized fun load(): CloudUploadStatus = CloudUploadStatus(
        lastAttemptAt = prefs.getLong("attemptAt", 0), lastSuccessAt = prefs.getLong("successAt", 0),
        lastSnapshotId = prefs.getString("snapshotId", null), lastWriterSequence = prefs.getLong("writerSequence", 0),
        lastCapturedSequence = prefs.getLong("capturedSequence", 0), lastPhase = prefs.getString("phase", "").orEmpty(),
        lastError = prefs.getString("error", null),
    )

    @Synchronized fun beginAttempt(at: Long) {
        prefs.edit { putLong("attemptAt", at); remove("error"); putString("phase", "STARTED") }
    }
    @Synchronized fun phase(value: String) { prefs.edit { putString("phase", value) } }
    @Synchronized fun fail(at: Long, message: String) {
        prefs.edit { putLong("attemptAt", at); putString("phase", "FAILED"); putString("error", message.take(400)) }
    }
    @Synchronized fun succeed(at: Long, snapshotId: String, writerSequence: Long, capturedSequence: Long) {
        prefs.edit {
            putLong("attemptAt", at); putLong("successAt", at); putString("snapshotId", snapshotId)
            putLong("writerSequence", writerSequence); putLong("capturedSequence", capturedSequence)
            putString("phase", "COMMITTED"); remove("error")
        }
    }
    /** Used when the local connection is removed; the remote copy is deliberately left untouched. */
    @Synchronized fun clear() { prefs.edit { clear() } }
    @Synchronized fun afterRestore() {check(prefs.edit().clear().commit()) {"恢复后的备份状态未保存"}}
}
