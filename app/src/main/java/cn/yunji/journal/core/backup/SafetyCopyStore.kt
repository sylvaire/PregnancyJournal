package cn.yunji.journal.core.backup

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID

/** Metadata only; no recovered medical content or keys are needed to manage encrypted copies. */
data class SafetyCopy(val id: String,val size: Long,val modifiedAt: Long)
data class SafetyCopyDigest(val size: Long,val sha256: String)

/** Synchronous disk operations: callers must use an IO dispatcher and explicitly confirm deletion. */
class SafetyCopyStore(vault: File,private val syncDirectory: (File)->Unit = ::syncDirectoryDurably) {
    private val requestedVault=vault
    private val vault=vault.canonicalFile
    private val root=File(this.vault,"restore-safety")
    private fun available() {
        check(!File(vault,"restore-journal.json").exists()) {"恢复尚未完成，请重新打开资料后再管理安全副本"}
        require(!Files.isSymbolicLink(requestedVault.toPath()) && requestedVault.canonicalFile==vault && !Files.isSymbolicLink(vault.toPath())) {"资料目录无效"}
        require(!Files.isSymbolicLink(root.toPath())) {"安全副本目录无效"}
        require(root.canonicalFile.parentFile==vault.canonicalFile) {"安全副本目录无效"}
    }
    private fun file(id: String): File {
        require(runCatching {UUID.fromString(id).toString()==id}.getOrDefault(false)) {"安全副本标识无效"}
        return File(root,"$id.yunji")
    }
    private fun metadata(file: File): SafetyCopy? {
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS) || file.length()<=0)return null
        val id=file.name.removeSuffix(".yunji")
        if(file.name!="$id.yunji" || runCatching {UUID.fromString(id).toString()==id}.getOrDefault(false).not())return null
        require(file.canonicalFile.parentFile==root.canonicalFile)
        return SafetyCopy(id,file.length(),file.lastModified())
    }
    fun list(): List<SafetyCopy> = synchronized(lock) {
        available()
        if(!root.exists())return@synchronized emptyList()
        require(root.isDirectory) {"安全副本目录无效"}
        requireNotNull(root.listFiles()) {"暂时无法读取安全副本"}.mapNotNull(::metadata)
            .sortedWith(compareByDescending<SafetyCopy> {it.modifiedAt}.thenBy {it.id})
    }
    private fun selected(copy: SafetyCopy): File {
        available()
        return file(copy.id).also {require(metadata(it)==copy) {"安全副本已变化，请刷新列表"}}
    }
    fun delete(copy: SafetyCopy)=synchronized(lock) {
        val source=selected(copy)
        check(source.delete()) {"安全副本未删除，请稍后重试"}
        syncDirectory(root)
    }
    /** Keeps source locked against management deletion throughout copying; destination is owned by caller. */
    fun copyTo(copy: SafetyCopy,output: OutputStream,checkpoint: ()->Unit={}): SafetyCopyDigest=synchronized(lock) {
        val source=selected(copy)
        val digest=MessageDigest.getInstance("SHA-256");var size=0L
        source.inputStream().use {input->
            val buffer=ByteArray(64*1024)
            while(true){checkpoint();val count=input.read(buffer);if(count<0)break
                size+=count;require(size<=copy.size) {"安全副本已变化"}
                output.write(buffer,0,count);digest.update(buffer,0,count)}
        }
        checkpoint();require(size==copy.size && metadata(source)==copy) {"安全副本已变化"}
        output.flush()
        SafetyCopyDigest(size,digest.digest().joinToString(""){"%02x".format(it)})
    }
    private companion object {val lock=Any()}
}
