package cn.yunji.journal.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.os.StatFs
import android.provider.OpenableColumns
import cn.yunji.journal.core.database.journalWrite
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap
import androidx.work.*
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.storage.EncryptedFileStore
import cn.yunji.journal.core.storage.StoredObject
import cn.yunji.journal.core.security.VaultMaterial
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.max

data class ImportTransfer(val bytes: Long, val total: Long)
class DocumentPreview(val attachment: AttachmentEntity, val file: File, private val release: () -> Unit) : AutoCloseable {
    private var closed=false
    private val listeners=mutableListOf<()->Unit>()
    fun whenClosed(action: ()->Unit) {val already=synchronized(this){if(closed)true else {listeners+=action;false}};if(already)action()}
    override fun close() {
        val actions=synchronized(this){if(closed)return;closed=true;listeners.toList().also {listeners.clear()}}
        try {actions.forEach {runCatching {it()}}} finally {release()}
    }
}

/** All names/URIs stay encrypted. Bounded imports do not block thumbnail or viewer reads. */
class DocumentImporter constructor(
    @param:ApplicationContext private val context: Context,
    private val db: JournalDatabase,
    material: VaultMaterial,
    private val clock: Clock,
    internal val objectDirectory: File = cn.yunji.journal.core.backup.VaultLayout(File(context.noBackupFilesDir,"vault")).activeObjects(),
) {
    val trashRepository by lazy { TrashRepository(db, this, clock) }
    internal fun scheduleTrashCleanup() { TrashWorker.enqueue(context) }
    private val dao = db.journalDao()
    private val root = objectDirectory
    private val store = EncryptedFileStore(root, material)
    internal val objectProtection = cn.yunji.journal.core.backup.ObjectProtection(db,store)
    private val temporary = File(context.noBackupFilesDir, "import-preview").apply { mkdirs() }
    private val viewers = File(context.noBackupFilesDir, "document-viewers").apply { mkdirs() }
    private val locks = Array(32) { Mutex() }
    private val importSlots = Semaphore(1)
    private val readSlots = Semaphore(2)
    private val activeTemporary = ConcurrentHashMap.newKeySet<String>()
    private val leases=ConcurrentHashMap<String,DocumentPreview>()
    private val previewCleanup=Mutex()
    private val processing=mutableSetOf<Job>()
    @Volatile private var stopped=false
    suspend fun clearPreviews(): Unit=withContext(Dispatchers.IO) {
        previewCleanup.withLock { readSlots.withPermit { readSlots.withPermit {
        leases.values.toList().forEach {it.close()}
        viewers.listFiles().orEmpty().forEach {it.delete()}
        } } }
    }
    suspend fun shutdown() {
        val runs=synchronized(processing){stopped=true;processing.toList()}
        applicationScope.cancel()
        runs.forEach {it.cancel()}
        objectProtection.shutdown()
        runs.forEach {it.join()}
        foregroundRuns.values.toList().forEach {it.cancelAndJoin()}
        clearPreviews()
    }
    // Process lifetime: imports survive screen navigation; WorkManager backs up process death.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val foregroundRuns = ConcurrentHashMap<String, Job>()
    private val _transfers = MutableStateFlow<Map<String, ImportTransfer>>(emptyMap())
    val transfers = _transfers.asStateFlow()
    private fun lock(id: String) = locks[Math.floorMod(id.hashCode(), locks.size)]
    private fun dispatch(id: String) {
        ImportWorker.enqueue(context, id)
        val task = applicationScope.launch(start = CoroutineStart.LAZY) {
            try { process(id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Persistent worker retries transient storage/database failures. */ }
            finally { foregroundRuns.remove(id, coroutineContext[Job]) }
        }
        if (foregroundRuns.putIfAbsent(id, task) == null) task.start() else task.cancel()
    }
    fun jobs(visitId: String) = dao.importJobs(visitId)
    fun attachments(visitId: String) = dao.attachments(visitId)
    fun medicationJobs(id: String)=dao.medicationImports(id)
    fun medicationAttachments(id: String)=dao.medicationAttachments(id)
    fun observeAttachment(id: String) = dao.observeAttachment(id)
    fun observeVisit(id: String) = dao.observeVisit(id)
    fun recycled() = dao.recycledAttachments()
    private fun stored(attachment: AttachmentEntity) = StoredObject(attachment.objectId ?: attachment.id,
        attachment.plainSize, attachment.cipherSize, attachment.plainSha256, attachment.cipherSha256)
    private suspend fun requireOwner(visitId: String?,medicationId: String?,allowDeletedVisit: Boolean=false) {
        require((visitId==null)!=(medicationId==null)) {"资料所属记录无效"}
        val pregnancyId=if(visitId!=null) {
            val visit=requireNotNull(dao.visit(visitId)) {"产检已不存在"}
            require(allowDeletedVisit || visit.deletedAt==null) {"产检已删除"};visit.pregnancyId
        } else {
            val drug=requireNotNull(dao.medication(requireNotNull(medicationId))) {"药品已不存在"}
            require(drug.deletedAt==null) {"请先恢复所属药品"};drug.pregnancyId
        }
        require(dao.pregnancy(pregnancyId)?.let {it.deletedAt==null}==true) {"请先恢复所属孕期"}
    }

    suspend fun thumbnail(id: String): Bitmap? = withContext(Dispatchers.IO) {
        readSlots.withPermit {
            val attachment = dao.attachment(id)?.takeIf { it.deletedAt == null } ?: return@withPermit null
            val thumb = attachment.thumbnailId ?: return@withPermit null
            val destination = File(temporary, "${UUID.randomUUID()}.thumb")
            activeTemporary.add(destination.name)
            try {
                store.decryptTo(StoredObject(thumb, requireNotNull(attachment.thumbnailSize), requireNotNull(attachment.thumbnailCipherSize),
                    requireNotNull(attachment.thumbnailSha256), requireNotNull(attachment.thumbnailCipherSha256)), destination)
                BitmapFactory.decodeFile(destination.path)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            finally { destination.delete(); activeTemporary.remove(destination.name) }
        }
    }

    /** Plaintext is authenticated before it can be opened by the image renderer. */
    suspend fun openPreview(id: String): DocumentPreview {
        if(stopped)throw cn.yunji.journal.core.security.VaultLockedException()
        var lease: DocumentPreview? = null
        return try { withContext(Dispatchers.IO) {
        readSlots.withPermit {
            val attachment = requireNotNull(dao.attachment(id)) { "资料已不存在" }
            require(attachment.deletedAt == null) { "资料已移入回收站" }
            requireOwner(attachment.visitId,attachment.medicationId,allowDeletedVisit=true)
            require(attachment.mime.startsWith("image/") || attachment.mime == "application/pdf") { "此格式暂时无法预览" }
            val file = File(viewers, "$id-${UUID.randomUUID()}.preview")
            activeTemporary.add(file.name)
            try {
                store.decryptTo(stored(attachment), file)
                coroutineContext.ensureActive()
                require(dao.attachment(id)?.deletedAt == null && dao.attachment(id) != null) { "资料已移入回收站" }
                requireOwner(attachment.visitId,attachment.medicationId,allowDeletedVisit=true)
                if(stopped)throw cn.yunji.journal.core.security.VaultLockedException()
                DocumentPreview(attachment, file) { leases.remove(file.name);file.delete(); activeTemporary.remove(file.name); Unit }.also { lease = it;leases[file.name]=it }
            } catch (e: Throwable) { file.delete(); activeTemporary.remove(file.name); throw e }
        }
        } } catch (e: Throwable) { lease?.close(); throw e }
    }

    suspend fun updateMetadata(id: String, name: String, category: String, documentDate: String? = null, hospital: String? = null, note: String? = null,expectedRevision: Long? = null): Unit = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 160 && !cleanName.any { it.isISOControl() || it == '/' || it == '\\' })
        require(category in DOCUMENT_CATEGORIES)
        val parsedDate=documentDate?.trim()?.takeIf {it.isNotEmpty()}?.let {java.time.LocalDate.parse(it).toString()}
        require(hospital==null || hospital.length<=120) {"医院最多120字"}
        require(note==null || note.length<=10_000) {"备注最多10000字"}
        db.journalWrite<Unit> {
            val current = requireNotNull(dao.attachment(id))
            require(current.deletedAt == null)
            require(current.medicationId==null || category=="PACKAGE") {"包装照片分类固定为药品包装"}
            require(expectedRevision==null || current.revision==expectedRevision) {"资料已变化，请关闭后重新打开编辑"}
            requireOwner(current.visitId,current.medicationId,allowDeletedVisit=true)
            val job = requireNotNull(dao.importJob(id))
            val revision = current.revision + 1
            dao.putAttachment(current.copy(name = cleanName, category = category, revision = revision,
                documentDate=if(documentDate!=null)parsedDate else current.documentDate,hospital=hospital?.trim() ?: current.hospital,note=note ?: current.note))
            dao.putImportJob(job.copy(name = cleanName))
            dao.recordChange(ChangeEntity(entityType = "attachment", entityId = id, revision = revision, operation = "UPSERT", createdAt = clock.millis()))
        }
    }

    suspend fun enqueue(visitId: String, uris: List<Uri>, schedule: Boolean = true) = enqueueOwned(visitId,null,uris,schedule)
    suspend fun enqueueMedication(medicationId: String,uris: List<Uri>,schedule: Boolean=true)=enqueueOwned(null,medicationId,uris,schedule)
    suspend fun enqueueHandoff(ownerId: String,medication: Boolean,uris: List<Uri>,requestId: String,schedule: Boolean=true) =
        enqueueOwned(if(medication)null else ownerId,if(medication)ownerId else null,uris,schedule,requestId)
    private suspend fun enqueueOwned(visitId: String?,medicationId: String?, uris: List<Uri>, schedule: Boolean,requestId: String?=null): Unit = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty() && uris.size <= 20) { "一次最多选择20个文件" }
        val jobs = uris.mapIndexed { index,uri ->
            require(uri.scheme == "content") { "请从系统选择器选择文件" }
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()?.replace(Regex("[\\p{Cntrl}\\p{Cf}/\\\\]"), "_")?.take(160)?.ifBlank { null } ?: if(medicationId!=null)"包装照片" else "产检资料"
            val id=if(requestId==null)UUID.randomUUID().toString() else UUID.nameUUIDFromBytes("handoff/$requestId/$index".toByteArray()).toString()
            ImportJobEntity(id, visitId, uri.toString(), name, clock.millis(),medicationId=medicationId)
        }
        db.journalWrite<Unit> {
            requireOwner(visitId,medicationId)
            jobs.forEach {
                val previous=dao.importJob(it.id)
                // Completed jobs deliberately erase sourceUri; the stable request ID remains their identity.
                if(previous==null)dao.putImportJob(it) else require(previous.visitId==it.visitId && previous.medicationId==it.medicationId) {"文件选择状态已变化"}
            }
        }
        if (schedule) jobs.forEach { dispatch(it.id) }
    }

    suspend fun retry(id: String, replacement: Uri? = null, schedule: Boolean = true) = withContext(Dispatchers.IO) {
        var oldSource: String? = null
        db.journalWrite {
            val job = requireNotNull(dao.importJob(id))
            require(job.state == "FAILED") { "资料正在处理或已经导入" }
            requireOwner(job.visitId,job.medicationId)
            if (replacement != null) {
                require(replacement.scheme == "content")
                runCatching { context.contentResolver.takePersistableUriPermission(replacement, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                if (replacement.toString() != job.sourceUri) oldSource = job.sourceUri
            }
            dao.putImportJob(job.copy(state = "QUEUED", phase = "WAITING", error = null, sourceUri = replacement?.toString() ?: job.sourceUri))
        }
        oldSource?.let { cleanupCamera(it.toUri()) }
        if (schedule) dispatch(id)
    }

    /** A durable tombstone wins even when a late worker still holds the old snapshot. */
    suspend fun trash(id: String, schedule: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val recyclable = db.journalWrite {
            val job = requireNotNull(dao.importJob(id))
            require(job.state !in setOf("PURGING", "PURGED", "REMOVED")) { "资料已删除" }
            val attachment = dao.attachment(id)
            if (attachment != null) {
                if (attachment.deletedAt == null) {
                    val next = attachment.copy(deletedAt = clock.millis(), revision = attachment.revision + 1)
                    dao.putAttachment(next)
                    clearCoverReference(attachment)
                    dao.recordChange(ChangeEntity(entityType = "attachment", entityId = id, revision = next.revision, operation = "DELETE", createdAt = clock.millis()))
                }
                dao.putImportJob(job.copy(state = "DELETED", phase = "DONE", error = null))
                true
            } else {
                dao.putImportJob(job.copy(state = "CANCELLED", phase = "CANCELLED", error = null))
                false
            }
        }
        if (schedule && !recyclable) {
            foregroundRuns[id]?.cancel()
            WorkManager.getInstance(context).cancelUniqueWork("import-$id").result.get()
            dispatch(id)
        }
        recyclable
    }

    suspend fun restore(id: String) = db.journalWrite {
        val job = requireNotNull(dao.importJob(id))
        require(job.state == "DELETED") { "资料正在彻底删除或已删除，无法恢复" }
        val attachment = requireNotNull(dao.attachment(id))
        require(attachment.deletionBatchId == null) { "请从全局回收站恢复所属删除批次" }
        requireOwner(attachment.visitId,attachment.medicationId,allowDeletedVisit=true)
        val next = attachment.copy(deletedAt = null, revision = attachment.revision + 1)
        dao.putAttachment(next)
        dao.putImportJob(job.copy(state = "READY", phase = "DONE", error = null))
        dao.recordChange(ChangeEntity(entityType = "attachment", entityId = id, revision = next.revision, operation = "RESTORE", createdAt = clock.millis()))
    }

    suspend fun setCover(attachmentId: String, visitId: String): Unit = withContext(Dispatchers.IO) {
        db.journalWrite {
            val attachment = requireNotNull(dao.attachment(attachmentId)) { "资料已不存在" }
            require(attachment.deletedAt == null && attachment.medicationId == null && attachment.visitId == visitId) { "只能把本次产检资料设为封面" }
            require(dao.importJob(attachmentId)?.state == "READY") { "请等待资料导入完成" }
            val visit = requireNotNull(dao.visit(visitId)) { "产检已不存在" }
            require(visit.deletedAt == null && dao.pregnancy(visit.pregnancyId)?.let { it.deletedAt == null } == true) { "请先恢复所属孕期和产检" }
            if (visit.coverAttachmentId == attachmentId) return@journalWrite
            val revision = visit.revision + 1
            dao.putVisit(visit.copy(coverAttachmentId = attachmentId, updatedAt = clock.millis(), revision = revision))
            dao.recordChange(ChangeEntity(entityType = "visit", entityId = visitId, revision = revision, operation = "UPSERT", createdAt = clock.millis()))
        }
    }

    suspend fun clearCover(visitId: String): Unit = withContext(Dispatchers.IO) {
        db.journalWrite {
            val visit = requireNotNull(dao.visit(visitId)) { "产检已不存在" }
            require(visit.deletedAt == null) { "产检已删除" }
            if (visit.coverAttachmentId == null) return@journalWrite
            val revision = visit.revision + 1
            dao.putVisit(visit.copy(coverAttachmentId = null, updatedAt = clock.millis(), revision = revision))
            dao.recordChange(ChangeEntity(entityType = "visit", entityId = visitId, revision = revision, operation = "UPSERT", createdAt = clock.millis()))
        }
    }

    /** Relinking only moves an existing encrypted object between visits in the same pregnancy. */
    suspend fun relink(id: String, targetVisitId: String, expectedRevision: Long? = null): Unit = withContext(Dispatchers.IO) {
        db.journalWrite {
            val attachment = requireNotNull(dao.attachment(id)) { "资料已不存在" }
            require(attachment.deletedAt == null && attachment.medicationId == null && attachment.visitId != null) { "只有产检资料可以重新关联产检" }
            require(expectedRevision == null || attachment.revision == expectedRevision) { "资料已变化，请关闭后重新打开" }
            require(dao.importJob(id)?.state == "READY") { "请等待资料导入完成" }
            val source = requireNotNull(dao.visit(attachment.visitId)) { "原产检已不存在" }
            val target = requireNotNull(dao.visit(targetVisitId)) { "目标产检已不存在" }
            require(source.pregnancyId == target.pregnancyId) { "只能关联同一孕期内的产检" }
            require(target.deletedAt == null && dao.pregnancy(target.pregnancyId)?.let { it.deletedAt == null } == true) { "请先恢复目标产检" }
            if (attachment.visitId == targetVisitId) return@journalWrite
            clearCoverReference(attachment)
            val revision = attachment.revision + 1
            dao.putAttachment(attachment.copy(visitId = targetVisitId, revision = revision))
            dao.putImportJob(requireNotNull(dao.importJob(id)).copy(visitId = targetVisitId))
            dao.recordChange(ChangeEntity(entityType = "attachment", entityId = id, revision = revision, operation = "UPSERT", createdAt = clock.millis()))
        }
    }

    suspend fun exportOriginal(id: String, destination: Uri, expectedRevision: Long? = null): Unit = withContext(Dispatchers.IO) {
        require(destination.scheme == "content") { "请选择可写入的导出位置" }
        val attachment = requireNotNull(dao.attachment(id)) { "资料已不存在" }
        require(attachment.deletedAt == null) { "资料已移入回收站" }
        require(expectedRevision == null || attachment.revision == expectedRevision) { "资料已变化，请关闭后重新打开" }
        requireOwner(attachment.visitId, attachment.medicationId, allowDeletedVisit = true)
        val output = context.contentResolver.openOutputStream(destination, "w") ?: throw IOException("无法写入导出位置")
        output.use { exportOriginalTo(id, it, expectedRevision) }
    }

    internal suspend fun exportOriginalTo(id: String, output: OutputStream, expectedRevision: Long? = null): Unit = withContext(Dispatchers.IO) {
        val attachment = requireNotNull(dao.attachment(id)) { "资料已不存在" }
        require(attachment.deletedAt == null) { "资料已移入回收站" }
        require(expectedRevision == null || attachment.revision == expectedRevision) { "资料已变化，请关闭后重新打开" }
        requireOwner(attachment.visitId, attachment.medicationId, allowDeletedVisit = true)
        val file = File(temporary, "$id-${UUID.randomUUID()}.export")
        activeTemporary.add(file.name)
        try {
            store.decryptTo(stored(attachment), file)
            coroutineContext.ensureActive()
            require(dao.attachment(id)?.let { it.deletedAt == null && (expectedRevision == null || it.revision == expectedRevision) } == true) { "资料已变化，请重新打开" }
            file.inputStream().use { it.copyTo(output) }
            output.flush()
        } finally { file.delete(); activeTemporary.remove(file.name) }
    }

    /** Caller holds an ObjectProtection lease for this immutable attachment's original. */
    internal suspend fun materialisePinnedOriginal(attachment: AttachmentEntity, destination: File) {
        val task = coroutineContext
        task.ensureActive()
        store.decryptTo(stored(attachment), destination, checkpoint = { task.ensureActive() })
        task.ensureActive()
    }

    /** Authenticate a new object before switching metadata; the old object stays readable until commit. */
    suspend fun replaceOriginal(id: String, source: Uri, expectedRevision: Long? = null): Unit = withContext(Dispatchers.IO) {
        require(source.scheme == "content") { "请从系统选择器选择替换文件" }
        importSlots.withPermit { lock(id).withLock {
            val attachment = requireNotNull(dao.attachment(id)) { "资料已不存在" }
            require(attachment.deletedAt == null) { "请先恢复资料" }
            require(expectedRevision == null || attachment.revision == expectedRevision) { "资料已变化，请关闭后重新打开" }
            requireOwner(attachment.visitId, attachment.medicationId, allowDeletedVisit = true)
            require(dao.importJob(id)?.state == "READY") { "资料尚未完成导入" }
            val newObject = UUID.randomUUID().toString()
            val thumbId = UUID.randomUUID().toString()
            val plain = File(temporary, "$id-${UUID.randomUUID()}.replace")
            activeTemporary.add(plain.name)
            var committed = false
            try {
                val expectedSize = runCatching {
                    context.contentResolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                        if (it.moveToFirst() && !it.isNull(0)) maxOf(0L, it.getLong(0)) else 0L
                    }
                }.getOrNull() ?: 0L
                require(expectedSize <= EncryptedFileStore.MAX_FILE_BYTES) { "单个文件不能超过100 MiB" }
                val required = expectedSize * 2 + 2L * 1024 * 1024
                if (StatFs(root.path).availableBytes <= required) {
                    val storage = context.getSystemService(StorageManager::class.java)
                    require(storage.getAllocatableBytes(storage.getUuidForPath(root)) > required) { "存储空间不足，请清理后重试" }
                }
                var type = ""
                val stored = context.contentResolver.openInputStream(source)?.buffered()?.use { input ->
                    input.mark(32)
                    val header = ByteArray(16)
                    var count = 0
                    while (count < header.size) { val read = input.read(header, count, header.size - count); if (read < 0) break; count += read }
                    input.reset(); type = sniff(header.copyOf(count))
                    require(attachment.medicationId == null || type.startsWith("image/")) { "包装照片仅支持图片" }
                    store.put(input, objectId = newObject)
                } ?: throw IOException("无法读取替换文件")
                store.decryptTo(stored, plain)
                coroutineContext.ensureActive()
                db.journalWrite {
                    val current = requireNotNull(dao.attachment(id))
                    require(current.deletedAt == null && current.objectId == attachment.objectId && current.plainSha256 == attachment.plainSha256) { "资料已变化，请重新打开" }
                    require(expectedRevision == null || current.revision == expectedRevision) { "资料已变化，请重新打开" }
                    val revision = current.revision + 1
                    dao.putAttachment(current.copy(objectId = newObject, mime = type, plainSize = stored.plainSize,
                        cipherSize = stored.cipherSize, plainSha256 = stored.plainSha256, cipherSha256 = stored.cipherSha256,
                        thumbnailId = null, thumbnailSize = null, thumbnailCipherSize = null, thumbnailSha256 = null,
                        thumbnailCipherSha256 = null, revision = revision))
                    dao.putImportJob(requireNotNull(dao.importJob(id)).copy(name = current.name, state = "READY", phase = "THUMBNAIL", sourceUri = "", error = null))
                    dao.recordChange(ChangeEntity(entityType = "attachment", entityId = id, revision = revision, operation = "UPSERT", createdAt = clock.millis()))
                    objectProtection.retireLocked(listOfNotNull(attachment.objectId ?: id,attachment.thumbnailId))
                }
                committed = true
                runCatching {objectProtection.cleanup()}
                val thumb = runCatching { makeThumbnail(plain, type)?.let { bytes -> store.put(bytes.inputStream(), objectId = thumbId) } }
                    .onFailure { store.path(thumbId).delete() }.getOrNull()
                runCatching { db.journalWrite {
                    val current = dao.attachment(id)
                    if (current != null && current.deletedAt == null && current.objectId == newObject) {
                        if (thumb != null) dao.putAttachment(current.copy(thumbnailId = thumb.id, thumbnailSize = thumb.plainSize,
                            thumbnailCipherSize = thumb.cipherSize, thumbnailSha256 = thumb.plainSha256, thumbnailCipherSha256 = thumb.cipherSha256))
                        dao.putImportJob(requireNotNull(dao.importJob(id)).copy(phase = "DONE", error = if (thumb == null) "缩略图不可用，原件已加密保存" else null))
                    } else if (thumb != null) store.path(thumb.id).delete()
                } }
                cleanupCamera(source)
            } catch (e: Throwable) {
                // Cancellation may arrive after the transaction committed but before its caller resumes.
                // The committed pointer is authoritative; never unlink a newly published object.
                if (!committed) withContext(NonCancellable) {
                    if (dao.attachment(id)?.objectId != newObject) store.path(newObject).delete()
                }
                throw e
            } finally {
                plain.delete(); activeTemporary.remove(plain.name)
                withContext(NonCancellable) {
                    if (dao.attachment(id)?.thumbnailId != thumbId) store.path(thumbId).delete()
                }
            }
        } }
    }

    suspend fun purge(id: String, schedule: Boolean = true) {
        db.journalWrite {
            val job = requireNotNull(dao.importJob(id))
            require(job.state == "DELETED" || job.state == "PURGING") { "请先移入回收站" }
            dao.putImportJob(job.copy(state = "PURGING", phase = "PURGING", error = null))
        }
        if (schedule) dispatch(id)
    }

    suspend fun recover() {
        objectProtection.recover()
        BackupExporter.recover(context)
        LocalRestoreAccess.recover(context)
        ExportService.recover(context)
        cn.yunji.journal.core.backup.RestoreWizard.recoverTemporary(
            File(context.noBackupFilesDir, "vault/restore-validation"))
        // Keep live leases; remove only leftovers from a previous process.
        for (directory in listOf(temporary, viewers)) directory.listFiles().orEmpty().forEach { file ->
            val live = activeTemporary.any { file.name == it || file.name.startsWith("$it.") }
            if (!live) file.delete()
        }
        dao.pendingImports().forEach { ImportWorker.enqueue(context, it.id) }
    }

    private suspend fun finishRemoval(job: ImportJobEntity) {
        // Capture must enter through journalWrite too: never expose metadata after its objects disappear.
        // A failed unlink leaves PURGING metadata intact, so capture can reject and cleanup can retry.
        db.journalWrite<Unit> {
            val current = dao.importJob(job.id) ?: return@journalWrite
            if (current.state !in setOf("PURGING", "CANCELLED")) return@journalWrite
            val attachment = dao.attachment(job.id)
            if (current.state == "CANCELLED" && attachment != null) return@journalWrite
            val legacyThumb = UUID.nameUUIDFromBytes("thumbnail:${job.id}".toByteArray()).toString()
            val thumb = attachment?.thumbnailId ?: legacyThumb
            val objects = listOfNotNull(job.id,attachment?.objectId,thumb)
            objectProtection.purgeLocked(objects,job.id)
            for (file in listOf(File(root, "${job.id}.partial"), File(root, "$thumb.partial"))) {
                check(!file.exists() || file.delete()) { "暂时无法清理文件，请稍后重试" }
            }
            for (directory in listOf(temporary, viewers)) directory.listFiles().orEmpty().filter { it.name.startsWith(job.id) }.forEach { file ->
                check(!file.exists() || file.delete()) { "暂时无法清理预览文件" }
            }
            if (current.sourceUri.isNotEmpty()) cleanupCamera(current.sourceUri.toUri())
            attachment?.let { clearCoverReference(it) }
            dao.removeAttachment(job.id)
            dao.putImportJob(current.copy(state = if (current.state == "PURGING") "PURGED" else "REMOVED", name = "", sourceUri = "", phase = "DONE", error = null))
            if (attachment != null) dao.recordChange(ChangeEntity(entityType = "attachment", entityId = job.id,
                revision = attachment.revision + 1, operation = "PURGE", createdAt = clock.millis()))
        }
    }
    private suspend fun clearCoverReference(attachment: AttachmentEntity) {
        val visitId = attachment.visitId ?: return
        val visit = dao.visit(visitId) ?: return
        if (visit.coverAttachmentId != attachment.id) return
        val revision = visit.revision + 1
        dao.putVisit(visit.copy(coverAttachmentId = null, updatedAt = clock.millis(), revision = revision))
        dao.recordChange(ChangeEntity(entityType = "visit", entityId = visit.id, revision = revision, operation = "UPSERT", createdAt = clock.millis()))
    }

    suspend fun process(id: String, timing: ((String, Long) -> Unit)? = null) = withContext(Dispatchers.IO) {
        val running=coroutineContext.job
        synchronized(processing){if(stopped)throw cn.yunji.journal.core.security.VaultLockedException();processing+=running}
        try {
        importSlots.withPermit { lock(id).withLock {
            val job = dao.importJob(id) ?: return@withLock
            if (job.state in setOf("PURGING", "CANCELLED")) { finishRemoval(job); return@withLock }
            if (job.state !in setOf("QUEUED", "IMPORTING", "READY")) return@withLock
            val existing = dao.attachment(id)
            if (existing != null && (existing.deletedAt != null || job.phase != "THUMBNAIL")) return@withLock
            val plain = File(temporary, "$id.preview")
            activeTemporary.add(plain.name)
            val thumbId = UUID.randomUUID().toString()
            var committed = existing != null
            try {
                requireOwner(job.visitId,job.medicationId)
                plain.delete()
                val stored: StoredObject
                val type: String
                if (existing != null) {
                    type = existing.mime
                    stored = StoredObject(existing.objectId ?: id, existing.plainSize, existing.cipherSize, existing.plainSha256, existing.cipherSha256)
                    measured("authenticate", timing) { store.decryptTo(stored, plain) }
                } else {
                    store.path(id).delete(); File(root, "$id.partial").delete()
                    db.journalWrite {
                        val current = requireNotNull(dao.importJob(id))
                        require(current.state in setOf("QUEUED", "IMPORTING")) { "导入已取消" }
                        dao.putImportJob(current.copy(state = "IMPORTING", phase = "READING", error = null))
                    }
                    val uri = job.sourceUri.toUri()
                    val expectedSize = runCatching {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                            if (it.moveToFirst() && !it.isNull(0)) maxOf(0L, it.getLong(0)) else 0L
                        }
                    }.getOrNull() ?: 0L
                    require(expectedSize <= EncryptedFileStore.MAX_FILE_BYTES) { "单个文件不能超过100 MiB" }
                    measured("spaceCheck", timing) {
                        val required = expectedSize * 2 + 2L * 1024 * 1024
                        // Avoid a slow storage-service round trip when actual free space suffices.
                        // Consult reclaimable cache only when the fast filesystem check is short.
                        if (StatFs(root.path).availableBytes <= required) {
                            val storage = context.getSystemService(StorageManager::class.java)
                            require(storage.getAllocatableBytes(storage.getUuidForPath(root)) > required) { "存储空间不足，请清理后重试" }
                        }
                    }
                    dao.setImportPhase(id, "SAVING")
                    var mime = ""
                    var lastProgress = 0L
                    val processingJob = coroutineContext[Job]
                    stored = measured("encrypt", timing) {
                        val source = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取文件，请重新选择" }.buffered()
                        source.use {
                            it.mark(32)
                            val header = ByteArray(16)
                            var count = 0
                            while (count < header.size) { val n = it.read(header, count, header.size - count); if (n < 0) break; count += n }
                            it.reset(); mime = sniff(header.copyOf(count))
                            store.put(it, objectId = id) { bytes ->
                                processingJob?.ensureActive()
                                val now = System.nanoTime()
                                if (now - lastProgress > 100_000_000 || bytes == expectedSize) {
                                    lastProgress = now
                                    _transfers.update { previous -> previous + (id to ImportTransfer(bytes, expectedSize)) }
                                }
                            }
                        }
                    }
                    type = mime
                    ensureImportAllowed(id)
                    dao.setImportPhase(id, "VERIFYING")
                    measured("authenticate", timing) { store.decryptTo(stored, plain) }
                    val commitStart = System.nanoTime()
                    db.journalWrite {
                        ensureImportAllowed(id)
                        requireOwner(job.visitId,job.medicationId)
                        require(job.medicationId==null || type.startsWith("image/")) {"包装照片仅支持图片"}
                        dao.putAttachment(AttachmentEntity(id, job.visitId, job.name, type, stored.plainSize, stored.cipherSize,
                            stored.plainSha256, stored.cipherSha256, null, null, null, null, null, clock.millis(),
                            category=if(job.medicationId!=null)"PACKAGE" else "OTHER",medicationId=job.medicationId))
                        val current = requireNotNull(dao.importJob(id))
                        dao.putImportJob(current.copy(state = "READY", phase = "THUMBNAIL", sourceUri = "", error = null))
                        dao.recordChange(ChangeEntity(entityType = "attachment", entityId = id, revision = 1, operation = "UPSERT", createdAt = clock.millis()))
                    }
                    committed = true
                    timing?.invoke("commit", (System.nanoTime() - commitStart) / 1_000_000)
                    cleanupCamera(uri)
                }
                coroutineContext.ensureActive()
                val thumb = measured("thumbnail", timing) { makeThumbnail(plain, type)?.let { store.put(it.inputStream(), objectId = thumbId) } }
                db.journalWrite {
                    val current = requireNotNull(dao.importJob(id))
                    val attachment = requireNotNull(dao.attachment(id))
                    if (current.state != "READY" || attachment.deletedAt != null) {
                        thumb?.let { store.path(it.id).delete() }
                    } else {
                        dao.putAttachment(attachment.copy(thumbnailId = thumb?.id, thumbnailSize = thumb?.plainSize,
                            thumbnailCipherSize = thumb?.cipherSize, thumbnailSha256 = thumb?.plainSha256, thumbnailCipherSha256 = thumb?.cipherSha256))
                        attachment.thumbnailId?.takeIf { it != thumb?.id }?.let { objectProtection.retireLocked(listOf(it)) }
                        dao.putImportJob(current.copy(phase = "DONE", error = if (thumb == null) "缩略图不可用，原件已加密保存" else null))
                    }
                }
                runCatching {objectProtection.cleanup()}
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if(stopped)throw cn.yunji.journal.core.security.VaultLockedException()
                db.journalWrite {
                    val current = dao.importJob(id) ?: return@journalWrite
                    if (current.state in setOf("CANCELLED", "REMOVED", "DELETED", "PURGING", "PURGED")) return@journalWrite
                    val message = when (e) {
                        is SecurityException -> "读取授权已失效，请重新选择文件"
                        is java.io.FileNotFoundException -> "源文件已不可用，请重新选择文件"
                        is IllegalArgumentException -> e.message ?: "文件格式不支持"
                        is IOException -> "文件读取或保存失败，请检查空间后重试"
                        else -> "导入未完成，可以重试或重新选择文件"
                    }
                    dao.putImportJob(current.copy(state = if (dao.attachment(id) != null) "READY" else "FAILED", phase = "DONE", error = message.take(120)))
                }
            } finally {
                plain.delete(); activeTemporary.remove(plain.name)
                _transfers.update { it - id }
                withContext(NonCancellable) {
                    val current = dao.attachment(id)
                    if (!committed && current == null) store.path(id).delete()
                    if (current?.thumbnailId != thumbId) store.path(thumbId).delete()
                }
            }
        } }
        } finally {synchronized(processing){processing-=running}}
    }

    private suspend fun ensureImportAllowed(id: String) {
        if(stopped)throw cn.yunji.journal.core.security.VaultLockedException()
        coroutineContext.ensureActive()
        require(dao.importJob(id)?.state in setOf("QUEUED", "IMPORTING")) { "导入已取消" }
    }

    private fun cleanupCamera(uri: Uri) {
        if (uri.authority == "${context.packageName}.capture" && uri.pathSegments.size == 2 && uri.pathSegments[0] == "capture") {
            val file = File(context.cacheDir, "capture/${uri.lastPathSegment}")
            if (file.canonicalFile.parentFile == File(context.cacheDir, "capture").canonicalFile) file.delete()
        }
    }

    private fun makeThumbnail(file: File, mime: String): ByteArray? = runCatching {
        val bitmap = if (mime == "application/pdf") {
            // API 26 的 pdfium 会被一个结构不完整的 PDF 永久污染（见 PdfReadability），先挡住。
            PdfReadability.requireReadable(file)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(renderer.pageCount > 0)
                    renderer.openPage(0).use { page ->
                        val scale = 512f / max(page.width, page.height)
                        createBitmap(max(1, (page.width * scale).toInt()), max(1, (page.height * scale).toInt())).also {
                            it.eraseColor(android.graphics.Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
            requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        }
        try { ByteArrayOutputStream().use { out -> require(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)); out.toByteArray() } }
        finally { bitmap.recycle() }
    }.getOrNull()

    companion object {
        private inline fun <T> measured(stage: String, noinline timing: ((String, Long) -> Unit)?, block: () -> T): T {
            val start = System.nanoTime()
            return try { block() } finally { timing?.invoke(stage, (System.nanoTime() - start) / 1_000_000) }
        }
        internal fun sniff(header: ByteArray): String {
            fun starts(vararg bytes: Int) = header.size >= bytes.size && bytes.indices.all { (header[it].toInt() and 255) == bytes[it] }
            return when {
                starts(0x25, 0x50, 0x44, 0x46, 0x2d) -> "application/pdf"
                starts(0xff, 0xd8, 0xff) -> "image/jpeg"
                starts(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
                header.size >= 6 && header.copyOf(6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
                header.size >= 12 && header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
                header.size >= 12 && header.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" && header.copyOfRange(8, 12).toString(Charsets.US_ASCII) in setOf("heic", "heix", "mif1") -> "image/heic"
                else -> throw IllegalArgumentException("仅支持图片或PDF，请检查文件内容")
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ImportDependencies { fun importer(): DocumentImporter }

class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val importer = EntryPointAccessors.fromApplication(applicationContext, ImportDependencies::class.java).importer()
        val id = inputData.getString("job")
        if (id == null) importer.recover() else importer.process(id)
        Result.success()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { Result.retry() }
    companion object {
        fun enqueue(context: Context, id: String) {
            WorkManager.getInstance(context).enqueueUniqueWork("import-$id", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ImportWorker>().setInputData(workDataOf("job" to id)).build())
        }
        fun recover(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("import-recovery", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ImportWorker>().build())
        }
    }
}

