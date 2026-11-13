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
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream


class LegacyEncryptedFileStore(private val directory: File, private val vault: VaultMaterial) {
    companion object { const val MAX_FILE_BYTES = 100L * 1024 * 1024 }
    init { require(directory.mkdirs() || directory.isDirectory) }

    fun put(input: InputStream, kind: String = "document", objectId: String = UUID.randomUUID().toString()): StoredObject {
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
                    val nonClosing = object : FilterOutputStream(destination) { override fun close() { flush() } }
                    CipherOutputStream(nonClosing, VaultCrypto.cipher(Cipher.ENCRYPT_MODE, vault.key("object"), nonce,
                        "object/v1/${vault.id}/$id/$kind")).use { encrypted ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_FILE_BYTES) { "单个文件不能超过100 MiB" }
                            encrypted.write(buffer, 0, count)
                        }
                    }
                    fileOutput.fd.sync()
                }
            }
            require(!target.exists() && temp.renameTo(target)) { "文件归档失败" }
            return StoredObject(id, total, target.length(), VaultCrypto.hex(plainDigest.digest()), VaultCrypto.hex(cipherDigest.digest()))
        } catch (e: Exception) { temp.delete(); throw e }
    }

    /** The temporary destination is not returned until GCM authentication and the hash both pass. */
    fun decryptTo(objectInfo: StoredObject, destination: File, kind: String = "document") {
        require(!destination.exists()) { "预览文件已存在" }
        val temp = File(destination.parentFile, "${destination.name}.${UUID.randomUUID()}.partial")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            var bytes = 0L
            path(objectInfo.id).inputStream().use { source ->
                require(source.readExactly(4).contentEquals(byteArrayOf(0x50, 0x4a, 0x46, 0x01))) { "附件格式不正确" }
                val nonce = source.readExactly(12)
                CipherInputStream(source, VaultCrypto.cipher(Cipher.DECRYPT_MODE, vault.key("object"), nonce,
                    "object/v1/${vault.id}/${objectInfo.id}/$kind")).use { decrypted ->
                    DigestOutputStream(temp.outputStream(), digest).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = decrypted.read(buffer)
                            if (count < 0) break
                            bytes += count
                            require(bytes <= MAX_FILE_BYTES) { "附件超过大小限制" }
                            out.write(buffer, 0, count)
                        }
                    }
                }
            }
            require(bytes == objectInfo.plainSize && VaultCrypto.hex(digest.digest()) == objectInfo.plainSha256) { "附件完整性校验失败" }
            require(temp.renameTo(destination)) { "无法创建预览文件" }
        } catch (e: Exception) { temp.delete(); throw e }
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
