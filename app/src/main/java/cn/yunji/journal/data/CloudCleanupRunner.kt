package cn.yunji.journal.data

import android.content.Context
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.network.WebDavClient
import cn.yunji.journal.core.security.VaultRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.time.Clock

/** Outcome of one remote cleanup pass. A skip is a normal result, not a failure. */
data class CleanupOutcome(
    val ran: Boolean,
    val message: String,
    val kept: Int = 0,
    val commitsDeleted: Int = 0,
    val objectsDeleted: Int = 0,
    val orphansTracked: Int = 0,
)

interface CloudCleanupAccess {
    suspend fun runOnce(): CleanupOutcome
}

/**
 * Runs one retention cleanup pass: gather a verified inventory, plan, then delete only what the plan names.
 *
 * The ledger is updated only after the remote mutation succeeded, so an interruption can never lose the
 * grace period of an object that is still present.
 */
class CloudCleanupRunner(
    private val context: Context,
    private val runtime: VaultRuntime,
    private val clock: Clock,
    private val scheduler: CloudSchedulerStore,
    private val ledger: CloudCleanupStore,
    private val clientFactory: (cn.yunji.journal.core.network.CloudConnection) -> WebDavClient =
        { WebDavClient(it.endpoint.toHttpUrl(), it.username, it.password) },
    private val statusStore: CloudCleanupStatusStore = CloudCleanupStatusStore(context),
) : CloudCleanupAccess {
    /** One cleanup pass at a time: the automatic worker and the manual entry share this runner. */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    override suspend fun runOnce(): CleanupOutcome = withContext(Dispatchers.IO) {
        if (!inFlight.compareAndSet(false, true)) return@withContext CleanupOutcome(false, "已有云端清理正在进行")
        try {
            val outcome = runPass()
            statusStore.record(CleanupRecord.of(outcome, clock.millis()))
            outcome
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            statusStore.record(CleanupRecord.failed(clock.millis(), e.message ?: "云端清理未完成"))
            throw e
        } finally { inFlight.set(false) }
    }

    private suspend fun runPass(): CleanupOutcome {
        val state = scheduler.load()
        if (state.blockedByOtherWriter) return CleanupOutcome(false, "检测到其他设备的备份，已暂停自动清理")
        val resources = runtime.resources()
        val connection = loadConnection(resources.material)
        val slot = RecoverySettings(context, resources.material).load()
            ?: return CleanupOutcome(false, "尚未保存恢复密钥，不能清理云端备份")
        val writerId = writerStore(resources.material).currentWriterId()
            ?: return CleanupOutcome(false, "本机尚未建立备份身份，不能清理云端备份")

        val client = clientFactory(connection)
        try {
            val maintenance = CloudMaintenance(client, connection.folder, resources.material)
            val collected = maintenance.collect(slot)
            val decision = CloudCleanupPlanner.plan(collected.inventory, collected.verifiedSnapshots,
                collected.knownWriterIds, writerId, ledger.orphanedSince(), clock.millis())
            if (decision is CleanupDecision.Stopped) return CleanupOutcome(false, decision.reason)
            val plan = (decision as CleanupDecision.Plan).plan

            maintenance.execute(slot, plan)
            // Only after the remote mutation: record new orphans and drop resolved ledger entries.
            ledger.record(plan.newlyOrphaned)
            ledger.forget(plan.objectsToDelete + plan.ledgerEntriesToDrop)
            return CleanupOutcome(true, "云端备份清理完成", kept = plan.keptSnapshotIds.size,
                commitsDeleted = plan.commitsToDelete.size, objectsDeleted = plan.objectsToDelete.size,
                orphansTracked = plan.newlyOrphaned.size)
        } finally { client.close() }
    }

    private fun writerStore(material: cn.yunji.journal.core.security.VaultMaterial) =
        CloudWriterStore(File(context.noBackupFilesDir, "vault/backup-writer"), material, { })

    private suspend fun loadConnection(material: cn.yunji.journal.core.security.VaultMaterial): cn.yunji.journal.core.network.CloudConnection {
        val repository = CloudConnectionRepository(CloudConnectionStore(context, material), material.id, clock)
        val saved = repository.load() ?: throw IllegalStateException("请先配置并保存云盘连接")
        require(saved.capabilities.verified) { "当前云盘连接未通过写入与回读校验，不能清理云端备份" }
        return saved.connection
    }
}
