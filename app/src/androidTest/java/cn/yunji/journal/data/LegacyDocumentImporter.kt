package cn.yunji.journal.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import androidx.room3.withWriteTransaction
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap
import androidx.work.*
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.VaultMaterial
import cn.yunji.journal.core.storage.EncryptedFileStore
import cn.yunji.journal.core.storage.LegacyEncryptedFileStore
import cn.yunji.journal.core.storage.StoredObject
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/** URI references and names live only in the encrypted database, never in WorkManager input. */
@Singleton
class LegacyDocumentImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: JournalDatabase,
    material: VaultMaterial,
    private val clock: Clock,
) {
    private val dao = db.journalDao()
    private val root = File(context.noBackupFilesDir, "vault/objects")
    private val store = LegacyEncryptedFileStore(root, material)
    private val temporary = File(context.noBackupFilesDir, "import-preview").apply { mkdirs() }
    private val mutex = Mutex()
    fun jobs(visitId: String) = dao.importJobs(visitId)
    fun attachments(visitId: String) = dao.attachments(visitId)

    suspend fun thumbnail(id: String): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val attachment = dao.attachment(id) ?: return@withLock null
            val thumb = attachment.thumbnailId ?: return@withLock null
            val destination = File(temporary, "${UUID.randomUUID()}.thumb")
            try {
                store.decryptTo(StoredObject(thumb, requireNotNull(attachment.thumbnailSize), requireNotNull(attachment.thumbnailCipherSize),
                    requireNotNull(attachment.thumbnailSha256), requireNotNull(attachment.thumbnailCipherSha256)), destination)
                BitmapFactory.decodeFile(destination.path)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            finally { destination.delete() }
        }
    }

    suspend fun enqueue(visitId: String, uris: List<Uri>, schedule: Boolean = true): Unit = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty() && uris.size <= 20) { "一次最多选择20个文件" }
        val jobs = uris.map { uri ->
            require(uri.scheme == "content") { "请从系统选择器选择文件" }
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()?.replace(Regex("[\\p{Cntrl}/\\\\]"), "_")?.take(160)?.ifBlank { null } ?: "产检资料"
            ImportJobEntity(UUID.randomUUID().toString(), visitId, uri.toString(), name, clock.millis())
        }
        db.withWriteTransaction {
            val visit = requireNotNull(dao.visit(visitId)) { "预约不存在" }
            require(visit.deletedAt == null) { "预约已删除" }
            jobs.forEach { dao.putImportJob(it) }
        }
        if (schedule) jobs.forEach { ImportWorker.enqueue(context, it.id) }
    }

    suspend fun retry(id: String, replacement: Uri? = null, schedule: Boolean = true) = withContext(Dispatchers.IO) {
        var oldSource: String? = null
        db.withWriteTransaction {
            val job = requireNotNull(dao.importJob(id))
            require(job.state == "FAILED") { "资料正在处理或已经导入" }
            if (replacement != null) {
                require(replacement.scheme == "content")
                runCatching { context.contentResolver.takePersistableUriPermission(replacement, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                if (replacement.toString() != job.sourceUri) oldSource = job.sourceUri
            }
            dao.putImportJob(job.copy(state = "QUEUED", error = null, sourceUri = replacement?.toString() ?: job.sourceUri))
        }
        oldSource?.let { cleanupCamera(it.toUri()) }
        if (schedule) ImportWorker.enqueue(context, id)
    }

    suspend fun recover() {
        mutex.withLock { temporary.listFiles().orEmpty().forEach { it.delete() } }
        dao.pendingImports().forEach { ImportWorker.enqueue(context, it.id) }
    }

    /** A stable object ID makes a replay after process death safe; committed objects are never rewritten. */
    suspend fun process(id: String, timing: ((String, Long) -> Unit)? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val job = dao.importJob(id) ?: return@withLock
            if (job.state !in setOf("QUEUED", "IMPORTING")) return@withLock
            if (dao.attachment(id) != null) {
                dao.putImportJob(job.copy(state = "READY", sourceUri = "", error = null))
                return@withLock
            }
            val plain = File(temporary, "$id.preview")
            val thumbId = UUID.nameUUIDFromBytes("thumbnail:$id".toByteArray()).toString()
            var thumbnail: StoredObject? = null
            var committed = false
            try {
                dao.putImportJob(job.copy(state = "IMPORTING", error = null))
                // Only this serialized importer owns these uncommitted object paths.
                store.path(id).delete()
                store.path(thumbId).delete()
                File(root, "$id.partial").delete()
                File(root, "$thumbId.partial").delete()
                plain.delete()
                val uri = job.sourceUri.toUri()
                val expectedSize = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                        if (it.moveToFirst() && !it.isNull(0)) maxOf(0L, it.getLong(0)) else 0L
                    }
                }.getOrNull() ?: 0L
                require(expectedSize <= EncryptedFileStore.MAX_FILE_BYTES) { "单个文件不能超过100 MiB" }
                val storage = context.getSystemService(StorageManager::class.java)
                val available = storage.getAllocatableBytes(storage.getUuidForPath(root))
                require(available > expectedSize * 2 + 2L * 1024 * 1024) { "存储空间不足，请清理后重试" }
                val source = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取文件，请重新选择" }.buffered()
                val mime = source.use {
                    it.mark(32)
                    val header = ByteArray(16)
                    var count = 0
                    while (count < header.size) { val n = it.read(header, count, header.size - count); if (n < 0) break; count += n }
                    it.reset()
                    val type = sniff(header.copyOf(count))
                    val stored = measured("encrypt", timing) { store.put(it, objectId = id) }
                    coroutineContext.ensureActive()
                    // Finish authentication before linking any metadata or exposing plaintext.
                    measured("authenticate", timing) { store.decryptTo(stored, plain) }
                    thumbnail = measured("thumbnail", timing) { makeThumbnail(plain, type)?.let { bytes -> store.put(bytes.inputStream(), objectId = thumbId) } }
                    coroutineContext.ensureActive()
                    db.withWriteTransaction {
                        val visit = requireNotNull(dao.visit(requireNotNull(job.visitId)))
                        require(visit.deletedAt == null) { "预约已删除" }
                        val thumb = thumbnail
                        dao.putAttachment(AttachmentEntity(id, job.visitId, job.name, type,
                            stored.plainSize, stored.cipherSize, stored.plainSha256, stored.cipherSha256,
                            thumb?.id, thumb?.plainSize, thumb?.cipherSize, thumb?.plainSha256, thumb?.cipherSha256, clock.millis()))
                        dao.putImportJob(job.copy(state = "READY", sourceUri = "", error = if (thumb == null) "缩略图不可用，原件已加密保存" else null))
                        dao.recordChange(ChangeEntity(entityType = "attachment", entityId = id, revision = 1, operation = "UPSERT", createdAt = clock.millis()))
                    }
                    committed = true
                    type
                }
                if (mime.isNotEmpty()) cleanupCamera(uri)
            } catch (e: CancellationException) {
                // IMPORTING is intentionally recoverable by the next run.
                throw e
            } catch (e: Exception) {
                val message = when (e) {
                    is SecurityException -> "读取授权已失效，请重新选择文件"
                    is java.io.FileNotFoundException -> "源文件已不可用，请重新选择文件"
                    is IllegalArgumentException -> e.message ?: "文件格式不支持"
                    is IOException -> "文件读取或保存失败，请检查空间后重试"
                    else -> "导入未完成，可以重试或重新选择文件"
                }
                dao.putImportJob(job.copy(state = "FAILED", error = message.take(120)))
            } finally {
                plain.delete()
                // Cancellation can arrive just as Room commits; never delete a committed object.
                withContext(NonCancellable) {
                    if (!committed && dao.attachment(id) == null) { store.path(id).delete(); store.path(thumbId).delete() }
                }
            }
        }
    }

    private fun cleanupCamera(uri: Uri) {
        if (uri.authority == "${context.packageName}.capture" && uri.pathSegments.size == 2 && uri.pathSegments[0] == "capture") {
            val file = File(context.cacheDir, "capture/${uri.lastPathSegment}")
            if (file.canonicalFile.parentFile == File(context.cacheDir, "capture").canonicalFile) file.delete()
        }
    }

    private fun makeThumbnail(file: File, mime: String): ByteArray? = runCatching {
        val bitmap = if (mime == "application/pdf") {
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

