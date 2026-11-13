package cn.yunji.journal.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.security.VaultMaterial
import cn.yunji.journal.core.storage.EncryptedFileStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.FilterOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class BackupDestinationOccupiedException: IllegalStateException("所选位置已有内容，请创建新的备份文件")
class BackupDestinationCleanupException(cause: Throwable): IOException("无法删除未完成的备份文件",cause)

/** Produces and fully self-checks the backup before opening the selected destination. */
class BackupExporter(private val context: Context,private val material: VaultMaterial,
    private val importer: DocumentImporter,private val settings: RecoverySettings,private val clock: Clock,
    private val deleteDestination: (Uri)->Boolean={DocumentsContract.deleteDocument(context.contentResolver,it)}) {
    private val serial=Mutex()
    private val writer=UUID.randomUUID().toString()
    private val sequence=AtomicLong()
    private val root=File(context.noBackupFilesDir,"vault/backup-work").apply {mkdirs()}
    companion object {
        private val active=ConcurrentHashMap.newKeySet<String>();private val jobName=Regex("job-[0-9a-f-]{36}")
        internal fun recover(context: Context)=cleanup(File(context.noBackupFilesDir,"vault/backup-work"))
        private fun cleanup(root: File) {
            root.listFiles().orEmpty().filter {jobName.matches(it.name) && it.name !in active &&
                it.canonicalFile.parentFile==root.canonicalFile && !java.nio.file.Files.isSymbolicLink(it.toPath())}.forEach {
                check(it.deleteRecursively()) {"暂时无法清理备份临时文件"}
            }
        }
    }
    internal fun recoverWorkspaces()=cleanup(root)

    suspend fun export(destination: Uri,progress: (String)->Unit={}): Unit=withContext(Dispatchers.IO) {
        require(destination.scheme=="content")
        var touched=false
        val writtenDigest=MessageDigest.getInstance("SHA-256");var written=0L
        fun requireEmptyDestination() {
            requireNotNull(context.contentResolver.openInputStream(destination)).use {if(it.read()!=-1)throw BackupDestinationOccupiedException()}
        }
        try {
            requireEmptyDestination()
            exportTo({
                requireEmptyDestination()
                touched=true
                val output=context.contentResolver.openOutputStream(destination,"wt") ?: error("无法写入备份位置")
                object: FilterOutputStream(output) {
                    override fun write(value: Int){out.write(value);writtenDigest.update(value.toByte());written++}
                    override fun write(bytes: ByteArray,offset: Int,length: Int){out.write(bytes,offset,length);writtenDigest.update(bytes,offset,length);written+=length}
                }
            },progress)
            progress("正在核对已保存的备份")
            val task=currentCoroutineContext().job
            val readDigest=MessageDigest.getInstance("SHA-256");var read=0L
            requireNotNull(context.contentResolver.openInputStream(destination)).use {input->
                val buffer=ByteArray(64*1024)
                while(true){task.ensureActive();val count=input.read(buffer);if(count<0)break;read+=count;require(read<=written);readDigest.update(buffer,0,count)}
            }
            task.ensureActive();require(read==written && MessageDigest.isEqual(readDigest.digest(),writtenDigest.digest())) {"保存位置回读校验未通过"}
        } catch(e: Throwable) {
            // Only a new CreateDocument destination may be passed here. Never delete arbitrary medical sources.
            if(touched){
                val deleted=withContext(NonCancellable+Dispatchers.IO){runCatching {deleteDestination(destination)}.getOrDefault(false)}
                if(!deleted)throw BackupDestinationCleanupException(e)
            }
            throw e
        }
    }

    internal suspend fun exportTo(openDestination: ()->OutputStream,progress: (String)->Unit={}): Unit=withContext(Dispatchers.IO) {serial.withLock {
        recoverWorkspaces()
        val slot=requireNotNull(settings.load()) {"请先保存并核对恢复密钥"}
        val name="job-${UUID.randomUUID()}";active+=name
        val work=File(root,name)
        try {
            check(work.mkdir())
            importer.objectProtection.withSnapshot(material.id,writer,sequence.incrementAndGet(),clock.millis()) {snapshot->
                // Vault shutdown cancels this registered child, not necessarily the outer Activity job.
                val task=currentCoroutineContext().job
                val checkpoint={task.ensureActive()}
                progress("正在制作加密备份")
                val archive=File(work,"complete.yunji")
                val store=EncryptedFileStore(importer.objectDirectory,material)
                LocalBackupArchive.write(snapshot,material,slot,store,archive,checkpoint)
                progress("正在校验原件与恢复数据")
                LocalBackupArchive.verifyWithVault(archive,material,slot,work,checkpoint).use {verified->
                    require(verified.snapshot==snapshot)
                    IsolatedRestore.withDatabase(context,verified){checkpoint()}
                }
                checkpoint();progress("正在保存备份文件")
                openDestination().use {output->archive.inputStream().use {input->
                    val buffer=ByteArray(64*1024)
                    while(true){checkpoint();val count=input.read(buffer);if(count<0)break;output.write(buffer,0,count)}
                };output.flush()}
                checkpoint()
            }
        } finally {
            try {check(!work.exists() || work.deleteRecursively()) {"备份临时文件清理未完成"}} finally {active-=name}
        }
    } }
}
