package cn.yunji.journal.core.storage

import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.core.security.VaultMaterial
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

data class StoredObject(val id: String, val plainSize: Long, val cipherSize: Long, val plainSha256: String, val cipherSha256: String)

class EncryptedFileStore(private val directory: File, private val vault: VaultMaterial) {
    companion object { const val MAX_FILE_BYTES = 100L * 1024 * 1024 }
    init { require(directory.mkdirs() || directory.isDirectory) }

    fun put(input: InputStream, kind: String = "document", objectId: String = UUID.randomUUID().toString(), progress: (Long) -> Unit = {}): StoredObject {
        require(kind == "document" || kind == "snapshot")
        val id = objectId
        require(!path(id).exists()) { "对象已存在" }
        val temp = File(directory, "$id.partial")
        val target = path(id)
        val plainDigest = MessageDigest.getInstance("SHA-256")
        val cipherDigest = MessageDigest.getInstance("SHA-256")
        val nonce = VaultCrypto.nonce()
        var total = 0L
        try {
            DigestInputStream(input, plainDigest).use { source ->
                val fileOutput = FileOutputStream(temp)
                DigestOutputStream(fileOutput, cipherDigest).use { destination ->
                    destination.write(byteArrayOf(0x50, 0x4a, 0x46, 0x01)); destination.write(nonce)
                    val key=vault.key("object")
                    val cipher=try {FileGcm(true,key,nonce,"object/v1/${vault.id}/$id/$kind")}finally {key.fill(0)}
                    cipher.use { encrypted ->
                        val buffer = ByteArray(64 * 1024)
                        try {while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_FILE_BYTES) { "单个文件不能超过100 MiB" }
                            encrypted.update(buffer,count,destination)
                            progress(total)
                        };encrypted.finish(destination)}finally {buffer.fill(0)}
                    }
                    fileOutput.fd.sync()
                }
            }
            require(!target.exists() && temp.renameTo(target)) { "文件归档失败" }
            return StoredObject(id, total, target.length(), VaultCrypto.hex(plainDigest.digest()), VaultCrypto.hex(cipherDigest.digest()))
        } catch (e: Throwable) { temp.delete(); throw e }
    }

    /** The temporary destination is not returned until GCM authentication and the hash both pass. */
    fun decryptTo(objectInfo: StoredObject, destination: File, kind: String = "document",checkpoint: ()->Unit = {}) {
        require(!destination.exists()) { "预览文件已存在" }
        val temp = File(destination.parentFile, "${destination.name}.${UUID.randomUUID()}.partial")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            var bytes = 0L
            path(objectInfo.id).inputStream().use { source ->
                require(source.readExactly(4).contentEquals(byteArrayOf(0x50, 0x4a, 0x46, 0x01))) { "附件格式不正确" }
                val nonce = source.readExactly(12)
                val key=vault.key("object")
                val decryptor = try {FileGcm(false,key,nonce,
                    "object/v1/${vault.id}/${objectInfo.id}/$kind")}finally {key.fill(0)}
                // Feed GCM sizeable chunks instead of CipherInputStream's tiny internal reads.
                // Only the private .partial exists until doFinal authenticates the complete object.
                decryptor.use {
                    DigestOutputStream(temp.outputStream(), digest).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        val checked=object: FilterOutputStream(out) {
                            override fun write(value: Int){bytes++;require(bytes<=MAX_FILE_BYTES);out.write(value)}
                            override fun write(plain: ByteArray,offset: Int,length: Int){bytes+=length;require(bytes<=MAX_FILE_BYTES);out.write(plain,offset,length)}
                        }
                        try {while (true) {
                            checkpoint()
                            val count = source.read(buffer)
                            if (count < 0) break
                            decryptor.update(buffer,count,checked)
                        }
                        checkpoint();decryptor.finish(checked);checkpoint()
                        }finally {buffer.fill(0)}
                    }
                }
            }
            require(bytes == objectInfo.plainSize && VaultCrypto.hex(digest.digest()) == objectInfo.plainSha256) { "附件完整性校验失败" }
            checkpoint();require(temp.renameTo(destination)) { "无法创建预览文件" }
        } catch (e: Throwable) { temp.delete(); throw e }
    }

    fun path(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "对象编号不正确" }
        return File(directory, "$id.bin")
    }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(result, offset, count - offset)
            require(read > 0) { "附件被截断" }
            offset += read
        }
        return result
    }
}
