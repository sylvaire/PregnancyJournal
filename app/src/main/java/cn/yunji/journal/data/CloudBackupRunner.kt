package cn.yunji.journal.data

import android.content.Context
import cn.yunji.journal.core.security.VaultRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock

interface RestoreCloudControl {
    suspend fun pauseForRestore()
    fun resumeAfterRestoreAbort()
}

/** Boundary used by the UI so non-blocking detail and key access stay out of Compose state. */
interface CloudBackupAccess {
    fun status(): CloudUploadStatus
    suspend fun upload(): CloudUploadOutcome
    fun isRunning(): Boolean
    fun scheduler(): CloudSchedulerState = CloudSchedulerState()
    fun scheduleAuto() {}
    fun setRole(primary: Boolean) {}
    fun setAutoEnabled(enabled: Boolean) {}
    fun setWifiOnly(enabled: Boolean) {}
    fun requestNow() {}
    fun cancelUpload() {}
}

/**
 * Runs the coordinator off the main thread. Each attempt builds a fresh resource graph so a revoked
 * or closed vault can never be reused, and only one attempt may be active at a time.
 */
class CloudBackupRunner(
    private val context: Context,
    private val runtime: VaultRuntime,
    private val clock: Clock,
    private val status: CloudUploadStatusStore,
    private val schedulerStore: CloudSchedulerStore,
) : CloudBackupAccess, RestoreCloudControl {
    private var active: CloudBackupCoordinator? = null
    private val uploadGate=cn.yunji.journal.core.backup.CloudUploadGate()

    @Synchronized override fun status(): CloudUploadStatus = status.load()

    @Synchronized override fun isRunning(): Boolean = uploadGate.running()

    override fun scheduler(): CloudSchedulerState = schedulerStore.load()

    override fun scheduleAuto() {
        val state = schedulerStore.load()
        if (state.autoActive) CloudBackupWorker.request(context) else CloudBackupWorker.cancel(context)
    }

    /** Only an explicit user choice makes this device primary; that also clears a foreign-writer block. */
    override fun setRole(primary: Boolean) {
        schedulerStore.setRole(if (primary) DeviceRole.PRIMARY else DeviceRole.SECONDARY, clock.millis())
        if (primary) schedulerStore.clearBlock()
        schedulerStore.clearFailures()
        runCatching { BackupFailureNotifier(context).clear() }
        scheduleAuto()
    }

    override fun setAutoEnabled(enabled: Boolean) {
        schedulerStore.setAutoEnabled(enabled)
        if (enabled) schedulerStore.setPendingSince(clock.millis())
        else { schedulerStore.clearFailures(); runCatching { BackupFailureNotifier(context).clear() } }
        scheduleAuto()
    }

    override fun setWifiOnly(enabled: Boolean) {
        schedulerStore.setWifiOnly(enabled)
        scheduleAuto()
    }

    override fun requestNow() { CloudBackupWorker.requestNow(context) }

    override suspend fun upload(): CloudUploadOutcome = withContext(Dispatchers.IO) {
        val ticket=uploadGate.enter()
        try {
            val resources=runtime.resources()
            val connection=loadConnection(resources.material)
            val coordinator=CloudBackupCoordinator(context,resources.material,resources.importer,connection,
                RecoverySettings(context,resources.material),status,clock,scheduler=schedulerStore)
            ticket.bind(coordinator::cancel)
            synchronized(this@CloudBackupRunner){active=coordinator}
            coordinator.run()
        }finally {
            synchronized(this@CloudBackupRunner){active=null}
            ticket.close()
        }
    }
    override fun cancelUpload() {uploadGate.cancelActive()}
    /** Stops even an upload still preparing its connection; returns only after its finally cleanup. */
    override suspend fun pauseForRestore() {CloudBackupWorker.cancel(context);uploadGate.pauseAndWait()}
    override fun resumeAfterRestoreAbort() {uploadGate.resume();scheduleAuto()}
    private suspend fun loadConnection(material: cn.yunji.journal.core.security.VaultMaterial): cn.yunji.journal.core.network.CloudConnection {
        val repository = CloudConnectionRepository(CloudConnectionStore(context, material), material.id, clock)
        val saved = repository.load() ?: throw IllegalStateException("请先配置并保存云盘连接")
        require(saved.capabilities.verified) { "当前云盘连接未通过写入与回读校验，不能上传备份" }
        return saved.connection
    }
}
