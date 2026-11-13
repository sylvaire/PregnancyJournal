package cn.yunji.journal.data

import android.content.Context
import android.net.Uri
import cn.yunji.journal.core.backup.*
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest

/** Copies existing ciphertext without changing its recovery key; never removes the source or destination. */
class SafetyCopyExporter(context: Context) {
    private val resolver=context.applicationContext.contentResolver
    private val store=SafetyCopyStore(File(context.applicationContext.noBackupFilesDir,"vault"))
    suspend fun export(copy: SafetyCopy,destination: Uri): Unit=withContext(Dispatchers.IO) {
        require(destination.scheme=="content")
        val task=currentCoroutineContext().job
        // Source validation precedes any destination mutation.
        require(store.list().contains(copy)) {"安全副本已变化，请刷新列表"}
        requireNotNull(resolver.openInputStream(destination)).use {if(it.read()!=-1)throw BackupDestinationOccupiedException()}
        task.ensureActive()
        val expected=requireNotNull(resolver.openOutputStream(destination,"wt")).use {output->store.copyTo(copy,output){task.ensureActive()}}
        val digest=MessageDigest.getInstance("SHA-256");var size=0L
        requireNotNull(resolver.openInputStream(destination)).use {input->
            val buffer=ByteArray(64*1024)
            while(true){task.ensureActive();val count=input.read(buffer);if(count<0)break
                size+=count;require(size<=expected.size);digest.update(buffer,0,count)}
        }
        task.ensureActive()
        require(size==expected.size && digest.digest().joinToString(""){"%02x".format(it)}==expected.sha256) {"导出文件回读校验失败"}
    }
}
