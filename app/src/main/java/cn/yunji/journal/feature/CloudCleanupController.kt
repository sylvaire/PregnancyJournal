package cn.yunji.journal.feature

import cn.yunji.journal.data.CleanupRecord
import cn.yunji.journal.data.CleanupStatusAccess
import cn.yunji.journal.data.CloudCleanupAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Display-only cleanup state. Credentials and remote object identifiers never enter this state. */
data class CloudCleanupUiState(
    val record: CleanupRecord = CleanupRecord(),
    val running: Boolean = false,
    val message: String? = null,
) {
    val hasRecord get() = record.hasRecord
}

/**
 * Manual cleanup entry. The durable record is written by the runner for both automatic and manual passes;
 * this controller also records failures it surfaces itself so the screen keeps the reason of the last try.
 * A cleanup never starts while a backup upload owns the cloud connection (the screen disables the button
 * and this check covers the small race window).
 */
class CloudCleanupController(
    private val scope: CoroutineScope,
    private val access: CloudCleanupAccess,
    private val status: CleanupStatusAccess,
    private val now: () -> Long = System::currentTimeMillis,
    private val backupRunning: () -> Boolean = { false },
) {
    private val mutable = MutableStateFlow(CloudCleanupUiState(record = status.load()))
    val state = mutable.asStateFlow()
    private var task: Job? = null

    fun refresh() { if (!mutable.value.running) mutable.value = mutable.value.copy(record = status.load()) }

    fun run() {
        if (mutable.value.running) return
        if (backupRunning()) { mutable.value = mutable.value.copy(message = "正在备份，完成后再清理"); return }
        mutable.value = mutable.value.copy(running = true, message = null)
        task = scope.launch {
            try {
                val outcome = access.runOnce()
                status.record(CleanupRecord.of(outcome, now()))
                mutable.value = mutable.value.copy(record = status.load(), message = outcome.message.ifBlank { null })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val reason = e.message?.takeIf { it.isNotBlank() } ?: "云端清理未完成，请稍后重试"
                status.record(CleanupRecord.failed(now(), reason))
                mutable.value = mutable.value.copy(record = status.load(), message = reason)
            } finally { mutable.value = mutable.value.copy(running = false) }
        }
    }

    /** Leaving the page cancels an in-flight pass like [CloudBackupController.leave]; no failure is recorded. */
    fun leave() {
        task?.cancel()
        task = null
        mutable.value = CloudCleanupUiState(record = status.load())
    }

    override fun toString() = "CloudCleanupController(<redacted>)"
}

internal fun formatCleanupTime(value: Long, zone: ZoneId = ZoneId.systemDefault()): String = displayInstant(value, zone.id)
