package cn.yunji.journal.feature

import cn.yunji.journal.core.backup.CloudBackupPolicy
import cn.yunji.journal.data.CloudBackupAccess
import cn.yunji.journal.data.CloudSchedulerState
import cn.yunji.journal.data.CloudUploadStatus
import cn.yunji.journal.data.DeviceRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CloudBackupUiState(
    val status: CloudUploadStatus = CloudUploadStatus(),
    val running: Boolean = false,
    val progress: String? = null,
    val message: String? = null,
    val scheduler: CloudSchedulerState = CloudSchedulerState(),
    val hasConnection: Boolean = false,
) {
    val autoBlockedReason: String? get() = CloudBackupPolicy.reason(
        CloudBackupPolicy.Gate(scheduler.role == DeviceRole.PRIMARY, scheduler.autoEnabled,
            scheduler.blockedByOtherWriter, scheduler.failures, true, "synthetic"))
}

/**
 * Cloud upload UI state. Only device-local attempt metadata is exposed; the password and medical content
 * never enter this state.
 */
class CloudBackupController(private val scope: CoroutineScope, private val access: CloudBackupAccess,
    /** 上传前检查恢复密钥是否已确认；缺失时应直接提示，而不是等上传准备阶段失败。 */
    private val recoveryConfigured: (suspend () -> Boolean)? = null) {
    private val mutable = MutableStateFlow(CloudBackupUiState(status = access.status(), running = access.isRunning(),
        scheduler = runCatching { access.scheduler() }.getOrDefault(CloudSchedulerState())))
    val state = mutable.asStateFlow()
    private var task: Job? = null

    fun refresh() {
        mutable.value = mutable.value.copy(status = access.status(), running = access.isRunning(),
            scheduler = runCatching { access.scheduler() }.getOrDefault(CloudSchedulerState()))
    }

    fun setPrimary(primary: Boolean) {
        runCatching { access.setRole(primary) }
        refresh()
    }
    fun setAutoEnabled(enabled: Boolean) { runCatching { access.setAutoEnabled(enabled) }; refresh() }
    fun setWifiOnly(enabled: Boolean) { runCatching { access.setWifiOnly(enabled) }; refresh() }

    fun upload() {
        if (mutable.value.running) return
        mutable.value = mutable.value.copy(running = true, progress = "正在准备备份", message = null)
        task = scope.launch {
            try {
                // 没有恢复密钥时上传必然在准备阶段失败；提前拦下并告知去做什么。
                val configured = recoveryConfigured?.invoke()
                if (configured == false) {
                    val hint = "请先在「本地备份」生成并核对恢复密钥，再上传云端备份。"
                    // 同时写入 status.lastError：该字段在备份状态卡内固定渲染，
                    // 不依赖 message 卡片是否在视口内。
                    mutable.value = mutable.value.copy(
                        message = hint,
                        status = mutable.value.status.copy(lastError = hint),
                    )
                    return@launch
                }
                val outcome = access.upload()
                mutable.value = mutable.value.copy(status = outcome.status, message = outcome.message)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.value = mutable.value.copy(message = e.message ?: "云端备份未完成，请稍后重试", status = access.status())
            } finally {
                mutable.value = mutable.value.copy(running = false, progress = null, scheduler = runCatching { access.scheduler() }.getOrDefault(CloudSchedulerState()))
            }
        }
    }

    fun cancel() { access.cancelUpload(); task?.cancel(); task = null; mutable.value = mutable.value.copy(running = false, progress = null, message = "备份已取消") }

    fun leave() { task?.cancel(); task = null; mutable.value = CloudBackupUiState(status = access.status()) }

    override fun toString() = "CloudBackupController(<redacted>)"
}
