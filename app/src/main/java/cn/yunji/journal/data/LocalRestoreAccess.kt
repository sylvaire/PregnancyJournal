package cn.yunji.journal.data

import android.content.Context
import android.net.Uri
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.security.VaultRuntime
import cn.yunji.journal.feature.RestoreAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Application-context adapter. The Activity alone owns selection grants and replacing its old ViewModel. */
internal class LocalRestoreAccess(
    context: Context,private val runtime: VaultRuntime,private val cloud: RestoreCloudControl,
    private val openDocument: (Uri)->InputStream = restoreDocumentOpener(context),
    private val inputLimit: Long = MAX_INPUT,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    private val webDav: (cn.yunji.journal.core.network.CloudConnection)->cn.yunji.journal.core.network.WebDavClient =
        { cn.yunji.journal.core.network.WebDavClient(it.endpoint.toHttpUrl(),it.username,it.password) },
): RestoreAccess {
    private val context=context.applicationContext
    private val serial=Mutex()
    private val completion=Mutex()
    private val vault=File(context.noBackupFilesDir,"vault")
    private val layout=VaultLayout(vault)
    private val journal=RestoreJournal(vault)
    private val wizard=RestoreWizard(layout,journal,File(vault,"restore-validation"))
    private var preparedSafetySlot: Pair<String,RecoverySlot>?=null
    internal var lastSafety: RestoreSafetyReceipt?=null
        private set
    init {require(inputLimit in 1..MAX_INPUT)}
    override suspend fun prepare(document: String,recoveryKey: String): RestorePreview {
        var prepared: RestorePreview?=null
        try {
            return withContext(Dispatchers.IO) {serial.withLock {
                val uri=Uri.parse(document)
                require(uri.scheme=="content" && document.length<=8192 && document.none(Char::isISOControl))
                recover(context)
                val root=File(vault,"restore-input").apply {require(mkdirs() || isDirectory)}
                val work=File(root,"job-${UUID.randomUUID()}")
                active.add(work.absolutePath)
                try {
                    require(work.mkdir())
                    val file=File(work,"backup.yunji")
                    val task=currentCoroutineContext()
                    openDocument(uri).use {input->file.outputStream().use {output->
                        var total=0L;val buffer=ByteArray(64*1024)
                        while(true){task.ensureActive();val count=input.read(buffer);if(count<0)break
                            require(count.toLong()<=inputLimit-total) {"备份文件超过大小限制"}
                            output.write(buffer,0,count);total+=count
                        }
                    }}
                    task.ensureActive()
                    val preview=registerPrepared(file,recoveryKey)
                    prepared=preview
                    preview                }finally {
                    try {check(!work.exists() || work.deleteRecursively()) {"恢复文件暂存清理失败"}}
                    finally {active.remove(work.absolutePath)}
                }
            }}
        }catch(failure: Throwable) {
            // Also covers prompt cancellation at the outer withContext return boundary.
            withContext(NonCancellable+Dispatchers.IO){prepared?.let {runCatching {discard(it)}}}
            throw failure
        }
    }    /**
     * Shared tail for both restore sources: keep a RAM-only safety-copy key when the vault has none, then
     * validate the archive and stage a generation.
     */
    private suspend fun registerPrepared(file: File,recoveryKey: String): RestorePreview {
        val current=runtime.resources()
        val candidate=if(RecoverySettings(context,current.material).load()==null)
            RecoverySlots.create(current.material,recoveryKey) else null
        val preview=runtime.prepareRestore(wizard,file,recoveryKey)
        preparedSafetySlot=candidate?.let {preview.generationId to it}
        return preview
    }

    /** Lists cloud snapshots the recovery key opens. Requires a saved, verified cloud connection. */
    override suspend fun listCloudSnapshots(recoveryKey: String): List<SnapshotRef> = withContext(Dispatchers.IO) {
        val current=runtime.resources()
        val connection=savedConnection(current.material)
        val client=webDav(connection)
        try { CloudSnapshotFetcher(client,connection.folder,current.material).let { fetcher->
            fetcher.discover(recoveryKey).flatMap { fetcher.listRestorable(it) }
                .sortedWith(compareByDescending<SnapshotRef> {it.capturedSequence}.thenByDescending {it.createdAt})
        } } finally { client.close() }
    }

    /**
     * Downloads the chosen snapshot into the same private staging area the local flow uses, then runs the
     * identical validation and staging. Nothing is written to live data before the user confirms.
     */
    override suspend fun prepareCloud(snapshotId: String,recoveryKey: String): RestorePreview {
        var prepared: RestorePreview?=null
        try {
            return withContext(Dispatchers.IO) {serial.withLock {
                recover(context)
                val root=File(vault,"restore-input").apply {require(mkdirs() || isDirectory)}
                val work=File(root,"job-${UUID.randomUUID()}")
                active.add(work.absolutePath)
                try {
                    require(work.mkdir())
                    val file=File(work,"backup.yunji")
                    val current=runtime.resources()
                    val connection=savedConnection(current.material)
                    val client=webDav(connection)
                    try {
                        val fetcher=CloudSnapshotFetcher(client,connection.folder,current.material)
                        val candidate=fetcher.discover(recoveryKey)
                            .firstOrNull {it.slot.vaultId.isNotEmpty() && fetcher.listRestorable(it).any {ref->ref.snapshotId==snapshotId}}
                            ?: throw IllegalStateException("云端备份已变化，请重新查找")
                        val fetched=fetcher.download(candidate,snapshotId,recoveryKey,work)
                        // The fetcher verifies the archive; staging copies it so the work dir stays disposable.
                        java.nio.file.Files.move(fetched.archive.toPath(),file.toPath())
                    } finally { client.close() }
                    val preview=registerPrepared(file,recoveryKey)
                    prepared=preview
                    preview
                } finally {
                    try {check(!work.exists() || work.deleteRecursively()) {"恢复文件暂存清理失败"}}
                    finally {active.remove(work.absolutePath)}
                }
            }}
        } catch(failure: Throwable) {
            withContext(NonCancellable+Dispatchers.IO){prepared?.let {runCatching {discard(it)}}}
            throw failure
        }
    }

    /** The cloud adapter never invents a connection: cleanup and restore both require a verified one. */
    private suspend fun savedConnection(material: cn.yunji.journal.core.security.VaultMaterial):
        cn.yunji.journal.core.network.CloudConnection {
        val repository=CloudConnectionRepository(CloudConnectionStore(context,material),material.id,clock)
        val saved=repository.load() ?: throw IllegalStateException("请先配置并保存云盘连接")
        require(saved.capabilities.verified) {"当前云盘连接未通过写入与回读校验，不能从云端恢复"}
        return saved.connection
    }

    override suspend fun commit(preview: RestorePreview)=withContext(Dispatchers.IO) {serial.withLock {
        val current=runtime.resources()
        val previous=current.reminders
        var cloudPaused=false;var remindersPaused=false
        try {
            lastSafety=runtime.commitPreparedRestore(wizard,preview) {
                cloudPaused=true;cloud.pauseForRestore()
                remindersPaused=true;previous.pauseForRestore()
                val settings=RecoverySettings(context,current.material)
                if(settings.load()==null) {
                    val candidate=requireNotNull(preparedSafetySlot?.takeIf {it.first==preview.generationId}?.second) {"请重新校验备份以配置安全副本密钥"}
                    require(candidate.vaultId==current.material.id) {"当前资料库已变化，请重新校验备份"}
                    settings.saveIfMissing(candidate)
                }
            }
            preparedSafetySlot=null
            // Strict mode requires fresh system authentication; keep uploads paused until that reopen.
            try {finishReopen()}catch(_:cn.yunji.journal.core.security.VaultLockedException){ }
        }catch(failure: Throwable) {
            if(!isCommitted(preview))withContext(NonCancellable) {
                if(remindersPaused)runCatching {previous.resumeAfterRestoreAbort()}
                if(cloudPaused)runCatching {
                    cloud.pauseForRestore()
                    val resources=runtime.resources()
                    resources.reminders.rebuild()
                    cloud.resumeAfterRestoreAbort()
                }
            }
            throw failure
        }
        Unit
    }}
    /** Call after Main has replaced its old model and, in strict mode, obtained a fresh authenticated session. */
    override suspend fun finishReopen()=withContext(Dispatchers.IO) {completion.withLock {
        // Every successful Main reopen checks this, including fresh authentication and recreation.
        // Ordinary opens must never resume a gate paused by another operation.
        if(journal.read()!=RestoreStage.VERIFIED || layout.activeGenerationId()!=journal.targetId())return@withLock
        runtime.resources() // Completion effects are persisted before this graph becomes accessible.
        cloud.resumeAfterRestoreAbort()
        journal.clear()
        check(journal.read()==null) {"恢复收尾待办尚未清理"}
        syncDirectoryDurably(vault)
    }}
    override suspend fun discard(preview: RestorePreview)=withContext(Dispatchers.IO) {serial.withLock {
        if(preparedSafetySlot?.first==preview.generationId)preparedSafetySlot=null
        if(layout.activeGenerationId()==preview.generationId)return@withLock
        if(journal.read()==RestoreStage.PREPARED && journal.targetId()==preview.generationId)wizard.discard(preview)
        else require(!layout.generationDir(preview.generationId).exists()) {"恢复状态已变化，暂存已保留"}
    }}
    override fun isCommitted(preview: RestorePreview): Boolean =
        layout.activeGenerationId()==preview.generationId &&
            (journal.read()==RestoreStage.VERIFIED || File(layout.generationDir(preview.generationId),"device-settings.ready").isFile)
    companion object {
        private const val MAX_INPUT=8L*1024*1024*1024+64L*1024*1024
        private val active=ConcurrentHashMap.newKeySet<String>()
        internal fun recover(context: Context) {
            val root=File(context.noBackupFilesDir,"vault/restore-input")
            root.listFiles().orEmpty().filter {it.name.matches(Regex("job-[0-9a-f-]{36}")) && it.absolutePath !in active &&
                it.canonicalFile.parentFile==root.canonicalFile && !java.nio.file.Files.isSymbolicLink(it.toPath())}.forEach {
                check(it.deleteRecursively()) {"恢复暂存清理失败"}
            }
        }
    }
}
private fun restoreDocumentOpener(context: Context): (Uri)->InputStream {
    val application=context.applicationContext
    return {uri->requireNotNull(application.contentResolver.openInputStream(uri))}
}