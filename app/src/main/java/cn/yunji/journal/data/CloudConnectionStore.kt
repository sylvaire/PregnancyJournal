package cn.yunji.journal.data

import android.content.Context
import android.util.AtomicFile
import cn.yunji.journal.core.network.*
import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.core.security.VaultMaterial
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ProbeCleanup(val endpoint: String,val username: String,val folder: List<String>,val name: String) {
    override fun toString()="ProbeCleanup(<redacted>)"
    fun matches(connection: CloudConnection)=endpoint==connection.endpoint && username==connection.username && folder==connection.folder
}
@Serializable data class CloudCooldown(val origin: String,val until: Long)

interface CloudSettingsStorage {
    fun load(): SavedCloudConnection?
    fun save(value: SavedCloudConnection)
    fun pending(): ProbeCleanup?
    fun beginProbe(value: ProbeCleanup)
    fun probeCleaned()
    fun disconnect()
    fun cooldown(): CloudCooldown?
    fun saveCooldown(value: CloudCooldown)
}

/** Separate local files: credentials are excluded from logical backups and tied to this vault's VMK. */
class CloudConnectionStore(context: Context,private val material: VaultMaterial): CloudSettingsStorage {
    private val directory=cn.yunji.journal.core.backup.VaultLayout(File(context.noBackupFilesDir,"vault")).activeDirectory().apply {mkdirs()}
    private val settings=AtomicFile(File(directory,"cloud-connection.enc"))
    private val cleanup=AtomicFile(File(directory,"cloud-probe.enc"))
    private val backoff=AtomicFile(File(directory,"cloud-cooldown.enc"))
    @Synchronized override fun load(): SavedCloudConnection? = read(settings,"connection")?.let {
        Json.decodeFromString<SavedCloudConnection>(it).also {saved ->require(saved.format==1);saved.connection.normalized()}
    }
    @Synchronized override fun save(value: SavedCloudConnection) {require(value.format==1);value.connection.normalized();write(settings,"connection",Json.encodeToString(value))}
    // 探测目录名必须同时接受两种形式：现行短名 `yjp-<8 hex>`（坚果云对目录名有长度上限，
    // ≥32 字符返回 400，必须短）与历史名 `.yunji-probe-<uuid>`（旧版本已写入本机的标记仍需清理）。
    // 此处校验若与 CloudProbe 的命名不一致，会抛出无消息的 require，用户看到 "Failed requirement."。
    @Synchronized override fun pending(): ProbeCleanup? = read(cleanup,"probe")?.let {Json.decodeFromString<ProbeCleanup>(it).also {value ->require(PROBE_NAME.matches(value.name)) {"测试标记无效，请先清除本机测试标记"}}}
    @Synchronized override fun beginProbe(value: ProbeCleanup) {require(PROBE_NAME.matches(value.name)) {"测试标记无效，请先清除本机测试标记"};write(cleanup,"probe",Json.encodeToString(value))}
    @Synchronized override fun probeCleaned() {cleanup.delete()}
    @Synchronized override fun disconnect() {settings.delete();backoff.delete()}
    @Synchronized override fun cooldown(): CloudCooldown?=read(backoff,"cooldown")?.let {Json.decodeFromString<CloudCooldown>(it)}
    @Synchronized override fun saveCooldown(value: CloudCooldown) {write(backoff,"cooldown",Json.encodeToString(value))}
    private fun read(file: AtomicFile,purpose: String): String? {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())return null
        val key=material.key("cloud-settings")
        var plain: ByteArray?=null
        try {
            val bytes=file.openRead().use {input ->require(input.channel.size()<=131_072) {"连接设置文件过大"};input.readBytes()}
            plain=VaultCrypto.decrypt(key,bytes,"cloud/v1/${material.id}/$purpose")
            return plain.toString(Charsets.UTF_8)
        } finally {key.fill(0);plain?.fill(0)}
    }
    private fun write(file: AtomicFile,purpose: String,value: String) {
        require(material.master.any {it!=0.toByte()}) {"资料已锁定，请解锁后重试"}
        val plain=value.toByteArray();require(plain.size<=65_536)
        val key=material.key("cloud-settings")
        val bytes=try {VaultCrypto.encrypt(key,plain,"cloud/v1/${material.id}/$purpose")}finally {key.fill(0);plain.fill(0)}
        val output=file.startWrite()
        try {output.write(bytes);file.finishWrite(output)}catch(e: Exception){file.failWrite(output);throw e}
    }

    private companion object {
        /** 现行短名与历史名；两者都必须能被清理。 */
        val PROBE_NAME = Regex("(yjp-[0-9a-f]{8})|(\\.yunji-probe-[0-9a-f-]{36})")
    }
}
