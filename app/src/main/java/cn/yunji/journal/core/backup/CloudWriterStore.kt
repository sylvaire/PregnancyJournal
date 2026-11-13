package cn.yunji.journal.core.backup

import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.core.security.VaultMaterial
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
private data class WriterState(val format: Int=1,val vaultId: String,val writerId: String,val issued: Long)
data class WriterReservation(val writerId: String,val sequence: Long)

/** Device-local only. Never exported/restored; a restored installation starts a separate writer branch. */
class CloudWriterStore(private val directory: File,private val vault: VaultMaterial,
    private val syncDirectory: (File)->Unit) {
    private val json=Json {encodeDefaults=true;ignoreUnknownKeys=false}
    private val state get()=File(directory,"cloud-writer.enc")
    private fun aad()="cloud-writer/v1/${vault.id}"
    private fun validate(value: WriterState) {
        require(value.format==1 && value.vaultId==vault.id && value.issued>=0)
        require(UUID.fromString(value.writerId).toString()==value.writerId)
    }
    /** Reserve durably before capture/upload. Failed/cancelled jobs intentionally leave sequence gaps. */
    fun reserve(): WriterReservation=synchronized(processMonitor) {
        require(vault.master.size==32 && vault.master.any {it!=0.toByte()}) {"密钥不可用，请重新解锁"}
        require(UUID.fromString(vault.id).toString()==vault.id)
        require(directory.isDirectory) {"备份状态目录不可用"}
        RandomAccessFile(File(directory,"cloud-writer.lock"),"rw").use {lockFile->
            lockFile.channel.lock().use {
                val old=load() ?: WriterState(vaultId=vault.id,writerId=UUID.randomUUID().toString(),issued=0)
                check(old.issued<Long.MAX_VALUE) {"备份序号已用尽，请创建新的写入分支"}
                val next=old.copy(issued=old.issued+1)
                save(next)
                WriterReservation(next.writerId,next.issued)
            }
        }
    }
    /** Current writer identity without consuming a sequence, or null when this installation has none yet. */
    fun currentWriterId(): String? = synchronized(processMonitor) { runCatching { load()?.writerId }.getOrNull() }

    private fun load(): WriterState? {        if(!state.exists())return null
        require(state.length() in 32..4096) {"备份写入状态损坏，已停止上传"}
        val key=vault.key("cloud-writer")
        val plain=try {VaultCrypto.decrypt(key,state.readBytes(),aad())}finally {key.fill(0)}
        try {
            return json.decodeFromString<WriterState>(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain)).toString()).also(::validate)
        }finally {plain.fill(0)}
    }
    private fun save(value: WriterState) {
        validate(value)
        val key=vault.key("cloud-writer");val plain=json.encodeToString(value).toByteArray(Charsets.UTF_8)
        val bytes=try {VaultCrypto.encrypt(key,plain,aad())}finally {key.fill(0);plain.fill(0)}
        val temp=File(directory,"cloud-writer.pending")
        try {
            RandomAccessFile(temp,"rw").use {out->out.setLength(0);out.write(bytes);out.fd.sync()}
            // No non-atomic fallback: a failed persistence must never issue a reservation.
            Files.move(temp.toPath(),state.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(directory)
        } finally {temp.delete()}
    }
    companion object {private val processMonitor=Any()}
}