package cn.yunji.journal.core.backup

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Counts shown to the user before any live data is replaced. */
data class RestorePreview(
    val generationId: String,
    val snapshotId: String,
    val createdAt: Long,
    val pregnancies: Int,
    val visits: Int,
    val documents: Int,
    val medicationPlans: Int,
    val intakes: Int,
    val measurements: Int,
    val journalEntries: Int,
    val sourceWriterId: String,
    val capturedSequence: Long,
    val customReminders: Int = 0,
) {
    override fun toString() = "RestorePreview(generation=$generationId, counts=redacted)"
}

/**
 * Validates a backup into a private staging generation and only then switches the active pointer.
 *
 * Guarantees:
 * - nothing touches live data before full validation and user confirmation;
 * - a failed or cancelled prepare leaves the current generation untouched;
 * - the switch is journaled and atomic (see [RestoreJournal] and [VaultLayout]);
 * - the previous generation is preserved for rollback after the switch.
 */
class RestoreWizard(
    private val layout: VaultLayout,
    private val journal: RestoreJournal,
    private val temporaryRoot: File,
) {
    companion object {
        private val activeStaging = ConcurrentHashMap.newKeySet<String>()
        internal fun recoverTemporary(root: File) = TransientWorkspaceCleanup.recover(root,
            setOf(TransientWorkspaceCleanup.restoreStaging, TransientWorkspaceCleanup.backupVerification), activeStaging)
    }

    /** Steps 1–6: parse, decrypt, authenticate, import into an isolated database, report counts. */
    fun prepare(archive: File, recoveryKey: String, deviceVault: cn.yunji.journal.core.security.VaultMaterial? = null, checkpoint: ()->Unit = {}, buildIsolated: (VerifiedArchiveHandle) -> File): RestorePreview {
        require(journal.read()==null) {"请先完成或取消上一次恢复"}
        require(temporaryRoot.mkdirs() || temporaryRoot.isDirectory) { "无法创建恢复暂存目录" }
        recoverTemporary(temporaryRoot)
        val generationId = layout.newGenerationId()
        val staging = File(temporaryRoot, "restore-$generationId")
        activeStaging += staging.name
        var stagingCreated = false
        try {
            require(!staging.exists()) { "恢复暂存目录已存在" }
            stagingCreated = staging.mkdirs()
            require(stagingCreated) { "无法创建恢复暂存目录" }
            // verify() decrypts with the recovery key and authenticates every entry before import.
            checkpoint()
            val verified = LocalBackupArchive.verify(archive, recoveryKey, staging,checkpoint)
            val snapshot = verified.snapshot
            val destination = layout.generationDatabase(generationId)
            destination.parentFile?.mkdirs()
            require(!destination.exists()) { "恢复目标已存在" }
            // Copy inside the verified scope: the verifier's directory (holding the staged DB) is wiped on close.
            verified.use { handle ->
                checkpoint()
                val imported = buildIsolated(VerifiedArchiveHandle(handle.snapshot, handle.material, handle.directory,handle.slot))
                checkpoint()
                require(imported.isFile) { "恢复临时库缺失" }
                imported.copyTo(destination, overwrite = false)
                require(destination.length() == imported.length()) { "恢复数据写入不完整" }
                // Each generation owns its restored ciphertexts; verifier teardown must not remove them.
                val objects=BackupManifest.objects(snapshot)
                val destinationObjects=File(destination.parentFile,"objects")
                require(destinationObjects.mkdirs() || destinationObjects.isDirectory)
                objects.forEach {info->
                    checkpoint()
                    val source=File(handle.directory,"objects/${info.id}.bin")
                    val target=File(destinationObjects,"${info.id}.bin")
                    require(source.isFile && source.length()==info.cipherSize)
                    source.copyTo(target,overwrite=false)
                    require(target.length()==info.cipherSize)
                    java.io.RandomAccessFile(target,"rw").use {it.fd.sync()}
                }
                java.io.RandomAccessFile(destination,"rw").use {it.fd.sync()}
                val expected=BackupManifest.encode(snapshot,handle.material)
                java.io.RandomAccessFile(File(destination.parentFile,"restore-manifest.enc"),"rw").use {out->out.setLength(0);out.write(expected);out.fd.sync()}
                val recoveryKey=handle.material.key("recovery-settings")
                val recoveryPlain=RecoverySlots.encode(handle.slot)
                val recoveryBytes=try {cn.yunji.journal.core.security.VaultCrypto.encrypt(recoveryKey,recoveryPlain,"recovery-settings/v1/${handle.material.id}")}
                    finally {recoveryKey.fill(0);recoveryPlain.fill(0)}
                java.io.RandomAccessFile(File(destination.parentFile,"recovery-slot.enc"),"rw").use {out->out.setLength(0);out.write(recoveryBytes);out.fd.sync()}
                deviceVault?.let {GenerationKeyStore(destination.parentFile!!.parentFile!!.parentFile!!).install(generationId,it,handle.material)}
            }
            require(destination.length() > 0) { "恢复数据为空" }
            checkpoint()
            journal.write(RestoreStage.PREPARED, generationId, layout.activeGenerationId(), snapshot.snapshotId)
            // 预览数量面向用户：已经“彻底删除”的墓碑行（清理状态 PURGED 的批次）不应再计入，
            // 否则已永久删除的记录会让用户以为备份里仍有这些数据；回收站中尚未清理的记录仍按原语义计入。
            val purgedBatches = snapshot.deletionBatches.filter { it.state == "PURGED" }
            val purgedBatchIds = purgedBatches.map { it.id }.toSet()
            val purgedRoots = purgedBatches.groupBy { it.kind }.mapValues { (_, rows) -> rows.map { it.rootId }.toSet() }
            fun purgedRoot(kind: String, id: String) = id in purgedRoots[kind].orEmpty()
            val purgedPregnancies = purgedRoots["PREGNANCY"].orEmpty()
            return RestorePreview(
                generationId = generationId,
                snapshotId = snapshot.snapshotId,
                createdAt = snapshot.createdAt,
                pregnancies = snapshot.pregnancies.count { it.deletionBatchId !in purgedBatchIds && it.id !in purgedPregnancies },
                visits = snapshot.visits.count {
                    it.deletionBatchId !in purgedBatchIds && it.pregnancyId !in purgedPregnancies && !purgedRoot("VISIT", it.id)
                },
                documents = snapshot.attachments.count { it.deletionBatchId !in purgedBatchIds },
                medicationPlans = snapshot.plans.filter {
                    it.pregnancyId !in purgedPregnancies && it.deletionBatchId !in purgedBatchIds && !purgedRoot("MEDICATION", it.medicationId)
                }.distinctBy { it.logicalPlanId }.size,
                intakes = snapshot.intakes.count { it.pregnancyId !in purgedPregnancies && !purgedRoot("INTAKE", it.id) },
                measurements = snapshot.measurements.count { it.pregnancyId !in purgedPregnancies && !purgedRoot("MEASUREMENT", it.id) },
                journalEntries = snapshot.entries.count { it.pregnancyId !in purgedPregnancies && !purgedRoot("JOURNAL", it.id) },
                customReminders = snapshot.customReminders.count { it.pregnancyId !in purgedPregnancies },
                sourceWriterId = snapshot.writerId,
                capturedSequence = snapshot.capturedSequence,
            )
        } catch (e: Throwable) {
            layout.deleteGeneration(generationId)
            journal.clear()
            throw e
        } finally {
            // Only the verifier's private workspace is transient; the imported generation was copied out.
            try { if (stagingCreated) TransientWorkspaceCleanup.deleteTree(staging) }
            finally { activeStaging -= staging.name }
        }
    }

    /**
     * Steps 7–9, after the user confirmed. Writes SWITCHED before changing the pointer so an
     * interruption between the two is detectable on next start.
     */
    fun commit(preview: RestorePreview, verifyActivated: (File)->Unit): RestoreStage {
        val target = layout.generationDatabase(preview.generationId)
        require(target.isFile) { "恢复暂存数据缺失，请重新选择备份" }
        require(journal.read() == RestoreStage.PREPARED) { "恢复已完成或已取消，请重新开始" }
        require(journal.targetId() == preview.generationId) { "恢复状态已变化，请重新开始" }
        val previous = layout.activeGenerationId()
        journal.write(RestoreStage.SWITCHED, preview.generationId, previous, preview.snapshotId)
        layout.writePointer(VaultPointer(activeId = preview.generationId, previousId = previous, updatedAt = System.currentTimeMillis()))
        try {
            // The caller must reopen/authenticate the staged database with the recovered session,
            // and validate its objects. A pointer update alone does not prove a usable restore.
            verifyActivated(target)
        } catch(failure: Throwable) {
            // Only close the journal after rollback succeeds. Otherwise startup still sees SWITCHED.
            layout.writePointer(VaultPointer(activeId=previous,updatedAt=System.currentTimeMillis()))
            journal.clear()
            throw failure
        }
        journal.write(RestoreStage.VERIFIED, preview.generationId, previous, preview.snapshotId)
        return RestoreStage.VERIFIED
    }

    /** Abandon a prepared restore; the current generation is untouched. */
    fun discard(preview: RestorePreview?) {
        if(preview==null)return
        require(journal.read()==RestoreStage.PREPARED && journal.targetId()==preview.generationId) {"恢复状态已变化，不能取消已生效的数据"}
        require(layout.activeGenerationId()!=preview.generationId) {"不能删除当前资料库"}
        layout.deleteGeneration(preview.generationId)
        journal.clear()
    }

    /**
     * After a verified restore the old generation may be reclaimed. The deletion is durable and
     * restart-safe (rename → atomic pointer switch → delete trash), idempotent, and never touches
     * the active generation: see [VaultLayout.reclaimPreviousGeneration].
     */
    fun reclaimPrevious() {
        require(journal.read()==RestoreStage.VERIFIED) {"恢复尚未验证，不能清理旧资料库"}
        val pointer = layout.readPointer() ?: return
        require(journal.targetId()==pointer.activeId) {"恢复状态已变化"}
        layout.reclaimPreviousGeneration()
    }
}

/** Read-only view of an authenticated archive handed to the importer. */
class VerifiedArchiveHandle(
    val snapshot: LogicalSnapshot,
    val material: cn.yunji.journal.core.security.VaultMaterial,
    val directory: File,
    val slot: RecoverySlot,
)
