package cn.yunji.journal.core.backup

import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.storage.EncryptedFileStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import cn.yunji.journal.core.security.VaultLockedException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** All database/file transitions use the same journalWrite gate as business edits. */
class ObjectProtection(private val db: JournalDatabase,private val store: EncryptedFileStore) {
    private val dao=db.journalDao()
    private val consumers=mutableSetOf<Job>()
    private var stopped=false
    companion object {
        // Registration precedes inserting durable pins. No wall-clock expiry can revoke a live export.
        private val liveLeases=ConcurrentHashMap.newKeySet<String>()
    }

    /** Caller must hold journalWrite. Queuing is atomic with replacing/removing metadata. */
    internal suspend fun retireLocked(ids: Collection<String>) {
        ids.distinct().forEach {store.path(it);dao.retireObject(RetiredObject(it))}
    }

    /** Delete before scrubbing a PURGING row; pinned/shared files are durably deferred instead. */
    internal suspend fun purgeLocked(ids: Collection<String>,attachmentId: String) {
        val pins=dao.objectPins().map {it.objectId}.toHashSet()
        val otherReferences=dao.allAttachments().filter {it.id!=attachmentId}.flatMap {listOfNotNull(it.objectId ?: it.id,it.thumbnailId)}.toHashSet()
        for(id in ids.distinct()) {
            val file=store.path(id)
            if(id in pins || id in otherReferences)dao.retireObject(RetiredObject(id))
            else check(!file.exists() || file.delete()) {"暂时无法清理文件，请稍后重试"}
        }
    }

    suspend fun cleanup()=db.journalWrite {drainLocked()}

    private suspend fun drainLocked() {
        val pins=dao.objectPins().map {it.objectId}.toHashSet()
        val referenced=dao.allAttachments().flatMap {listOfNotNull(it.objectId ?: it.id,it.thumbnailId)}.toHashSet()
        for(row in dao.retiredObjects()) {
            if(row.objectId in pins || row.objectId in referenced)continue
            val file=store.path(row.objectId)
            check(!file.exists() || file.delete()) {"暂时无法清理文件，请稍后重试"}
            dao.removeRetiredObject(row.objectId)
        }
    }

    /** A restarted process has no live leases. Same-process recovery preserves active snapshots. */
    suspend fun recover()=db.journalWrite {
        dao.objectPins().map {it.leaseId}.distinct().filter {it !in liveLeases}.forEach {dao.removeObjectPins(it)}
        drainLocked()
    }

    /** The callback owns the file-read lifetime. Cancellation also waits for durable pin release. */
    suspend fun <T> withSnapshot(vaultId: String,writerId: String,writerSequence: Long,createdAt: Long,
        block: suspend (LogicalSnapshot)->T): T = coroutineScope {
        val job=currentCoroutineContext().job
        synchronized(consumers) {if(stopped)throw VaultLockedException();consumers+=job}
        try {
            val pinned=capture(vaultId,writerId,writerSequence,createdAt)
            try {block(pinned.snapshot)} finally {pinned.release()}
        } finally {synchronized(consumers){consumers-=job}}
    }

    /** Capture selected rows and pin only their originals atomically with business mutations. */
    internal suspend fun <T, R> withSelected(
        capture: suspend () -> Pair<T, List<String>>,
        block: suspend (T) -> R,
    ): R = coroutineScope {
        val job = currentCoroutineContext().job
        synchronized(consumers) { if (stopped) throw VaultLockedException(); consumers += job }
        val leaseId = UUID.randomUUID().toString()
        liveLeases += leaseId
        try {
            val selected = db.journalWrite {
                val (rows, ids) = capture()
                val objects = ids.distinct()
                check(objects.all { store.path(it).isFile }) { "所选原件不完整，请检查资料" }
                dao.insertObjectPins(objects.map { BackupObjectPin(leaseId, it) })
                rows
            }
            block(selected)
        } finally {
            try { release(leaseId) } finally { synchronized(consumers) { consumers -= job } }
        }
    }

    /** VaultRuntime must wait for this before closing the database or wiping the master key. */
    suspend fun shutdown() {
        val jobs=synchronized(consumers) {stopped=true;consumers.toList()}
        check(currentCoroutineContext().job !in jobs) {"不能在备份读取回调中关闭资料库"}
        jobs.forEach {it.cancel()};jobs.forEach {it.join()}
    }

    internal suspend fun capture(vaultId: String,writerId: String,writerSequence: Long,createdAt: Long): PinnedSnapshot {
        val leaseId=UUID.randomUUID().toString()
        liveLeases+=leaseId
        try {
            return db.journalWrite {
                check(dao.allImportJobs().none {it.state=="PURGING"}) {"资料正在清理，请稍后重试备份"}
                val snapshot=LogicalSnapshot(vaultId=vaultId,snapshotId=UUID.randomUUID().toString(),writerId=writerId,
                    writerSequence=writerSequence,capturedSequence=dao.currentSequence(),createdAt=createdAt,
                    pregnancies=dao.allPregnancies(),dateRevisions=dao.allDateRevisions(),visits=dao.allVisits(),
                    attachments=dao.allAttachments(),deletionBatches=dao.allDeletionBatches(),medications=dao.allMedications(),
                    plans=dao.allMedicationPlans(),occurrences=dao.allOccurrences(),intakes=dao.allIntakes(),
                    definitions=dao.allMetricDefinitions(),measurements=dao.allMeasurements(),values=dao.allMeasurementValues(),entries=dao.allJournalEntries(),
                    customReminders=dao.allCustomReminders())
                SnapshotRelations.validate(snapshot)
                val objects=snapshot.attachments.flatMap {listOfNotNull(it.objectId ?: it.id,it.thumbnailId)}.distinct()
                check(objects.all {store.path(it).isFile}) {"备份原件不完整，请先检查资料"}
                dao.insertObjectPins(objects.map {BackupObjectPin(leaseId,it)})
                PinnedSnapshot(snapshot,leaseId,this@ObjectProtection)
            }
        } catch(e: Throwable) {
            // A cancellation can be delivered just after commit, before the lease reaches its owner.
            try {release(leaseId)} catch(cleanup: Throwable) {e.addSuppressed(cleanup)}
            throw e
        }
    }

    private suspend fun release(leaseId: String)=withContext(NonCancellable) {
        try {db.journalWrite {dao.removeObjectPins(leaseId);drainLocked()}}
        finally {liveLeases-=leaseId}
    }

    class PinnedSnapshot internal constructor(val snapshot: LogicalSnapshot,private val leaseId: String,private val owner: ObjectProtection) {
        /** Release from finally, after the final byte is copied or the export is cancelled. Idempotent. */
        suspend fun release()=owner.release(leaseId)
        override fun toString()="PinnedSnapshot(<redacted>)"
    }
}
