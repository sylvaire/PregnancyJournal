package cn.yunji.journal.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device role for cloud backup. Only one device should auto-upload at a time, but this is a coordination
 * signal, not an exclusivity lock: the protocol still relies on immutable objects and per-writer branches.
 */
enum class DeviceRole { PRIMARY, SECONDARY }

data class CloudSchedulerState(
    val role: DeviceRole = DeviceRole.PRIMARY,
    val roleConfirmedAt: Long = 0,
    val autoEnabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val blockedByOtherWriter: Boolean = false,
    val blockedAt: Long = 0,
    val blockedWriterHint: String? = null,
    val failures: Int = 0,
    val failureNoticeShown: Boolean = false,
    val nextAttemptAt: Long = 0,
    val lastCaptureSequenceBackedUp: Long = 0,
    val pendingChangesSince: Long = 0,
    val lastCleanupAt: Long = 0,
    val acknowledgedForeignCommitIds: Set<String> = emptySet(),
) {
    val isSecondary get() = role == DeviceRole.SECONDARY
    val autoActive get() = autoEnabled && role == DeviceRole.PRIMARY && !blockedByOtherWriter
}

/**
 * Non-medical scheduling state. A restored installation starts as SECONDARY with automation off until the
 * user explicitly chooses this device as the primary writer.
 */
@Singleton
class CloudSchedulerStore @Inject constructor(@param:ApplicationContext context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud-scheduler", Context.MODE_PRIVATE)

    @Synchronized fun load(): CloudSchedulerState = CloudSchedulerState(
        role = if (prefs.getString("role", null) == DeviceRole.SECONDARY.name) DeviceRole.SECONDARY else DeviceRole.PRIMARY,
        roleConfirmedAt = prefs.getLong("roleConfirmedAt", 0),
        autoEnabled = prefs.getBoolean("autoEnabled", false),
        wifiOnly = prefs.getBoolean("wifiOnly", true),
        blockedByOtherWriter = prefs.getBoolean("blocked", false),
        blockedAt = prefs.getLong("blockedAt", 0),
        blockedWriterHint = prefs.getString("blockedHint", null),
        failures = prefs.getInt("failures", 0),
        failureNoticeShown = prefs.getBoolean("failureNoticeShown", false),
        nextAttemptAt = prefs.getLong("nextAttemptAt", 0),
        lastCaptureSequenceBackedUp = prefs.getLong("lastBackedUp", 0),
        pendingChangesSince = prefs.getLong("pendingSince", 0),
        lastCleanupAt = prefs.getLong("lastCleanupAt", 0),
        acknowledgedForeignCommitIds = prefs.getStringSet("acknowledgedForeignCommitIds", emptySet()).orEmpty().toSet(),
    )

    @Synchronized fun setRole(role: DeviceRole, at: Long) {
        prefs.edit { putString("role", role.name); putLong("roleConfirmedAt", at) }
    }
    @Synchronized fun setAutoEnabled(enabled: Boolean) { prefs.edit { putBoolean("autoEnabled", enabled) } }
    @Synchronized fun setWifiOnly(enabled: Boolean) { prefs.edit { putBoolean("wifiOnly", enabled) } }
    @Synchronized fun setPendingSince(at: Long) { prefs.edit { putLong("pendingSince", at) } }
    @Synchronized fun setLastBackedUp(capturedSequence: Long) { prefs.edit { putLong("lastBackedUp", capturedSequence) } }
    @Synchronized fun setNextAttemptAt(at: Long) { prefs.edit { putLong("nextAttemptAt", at) } }
    @Synchronized fun setLastCleanupAt(at: Long) { prefs.edit { putLong("lastCleanupAt", at) } }
    @Synchronized fun recordFailure() {
        val next = load().failures + 1
        prefs.edit { putInt("failures", next) }
    }
    @Synchronized fun clearFailures() { prefs.edit { putInt("failures", 0); remove("failureNoticeShown") } }
    @Synchronized fun markFailureNoticeShown() { prefs.edit { putBoolean("failureNoticeShown", true) } }
    @Synchronized fun blockForOtherWriter(at: Long, hint: String, commitIds: Set<String> = emptySet()) {
        prefs.edit {
            putBoolean("blocked", true); putLong("blockedAt", at); putString("blockedHint", hint.take(200))
            putStringSet("blockedCommitIds", commitIds.toSet())
        }
    }
    /** Only an explicit user confirmation of primary role clears the block. */
    @Synchronized fun clearBlock() {
        val accepted = prefs.getStringSet("acknowledgedForeignCommitIds", emptySet()).orEmpty().toSet() +
            prefs.getStringSet("blockedCommitIds", emptySet()).orEmpty()
        check(prefs.edit().putStringSet("acknowledgedForeignCommitIds", accepted)
            .remove("blocked").remove("blockedAt").remove("blockedHint").remove("blockedCommitIds")
            .commit()) { "主设备确认状态未保存，已停止自动备份" }
    }
    @Synchronized fun reset() { prefs.edit { clear() } }
    /** A restored vault never inherits this device's old primary role or success high-water mark. */
    @Synchronized fun afterRestore() {
        check(prefs.edit().clear().putString("role",DeviceRole.SECONDARY.name)
            .putBoolean("autoEnabled",false).putBoolean("wifiOnly",true).commit()) {"恢复后的云端设置未保存"}
    }}
