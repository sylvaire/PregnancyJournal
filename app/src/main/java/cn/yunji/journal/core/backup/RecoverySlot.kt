package cn.yunji.journal.core.backup

import cn.yunji.journal.core.security.RecoveryKey
import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.core.security.VaultMaterial
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** Public recovery metadata contains only an authenticated wrapper, never the recovery key. */
@Serializable
data class RecoverySlot(val format: String="YJ-RECOVERY-1",val vaultId: String,val slotId: String,
    val algorithm: String="HKDF-SHA256-AES256-GCM",val wrappedMaster: String) {
    override fun toString()="RecoverySlot(<redacted>)"
}

object RecoverySlots {
    private val json=Json {ignoreUnknownKeys=false;encodeDefaults=true}
    private const val MAX_BYTES=4096
    private fun validate(slot: RecoverySlot) {
        require(slot.format=="YJ-RECOVERY-1" && slot.algorithm=="HKDF-SHA256-AES256-GCM") {"不支持的恢复封装"}
        listOf(slot.vaultId,slot.slotId).forEach {require(runCatching {UUID.fromString(it).toString()==it}.getOrDefault(false)) {"恢复封装标识无效"}}
        require(slot.wrappedMaster.length==86 && slot.wrappedMaster.all {it.isLetterOrDigit() && it.code<128 || it=='-' || it=='_'}) {"恢复封装长度无效"}
        require(VaultCrypto.decode(slot.wrappedMaster).size==64) {"恢复封装长度无效"}
    }
    private fun aad(slot: RecoverySlot)="recovery/v1/${slot.vaultId}/${slot.slotId}/${slot.algorithm}/master"
    private fun key(raw: ByteArray,slot: RecoverySlot)=VaultCrypto.derive(raw,slot.vaultId,"recovery-slot/${slot.slotId}")

    fun create(vault: VaultMaterial,recoveryKey: String): RecoverySlot {
        require(vault.master.size==32)
        val raw=RecoveryKey.parse(recoveryKey)
        var derived: ByteArray?=null
        try {
            val slot=RecoverySlot(vaultId=vault.id,slotId=UUID.randomUUID().toString(),wrappedMaster="")
            derived=key(raw,slot)
            return slot.copy(wrappedMaster=VaultCrypto.encode(VaultCrypto.encrypt(derived,vault.master,aad(slot)))).also(::validate)
        } finally {raw.fill(0);derived?.fill(0)}
    }

    /** Caller owns and must wipe the returned master key when the recovery session ends. */
    fun open(slot: RecoverySlot,recoveryKey: String): VaultMaterial {
        validate(slot)
        val raw=RecoveryKey.parse(recoveryKey)
        var derived: ByteArray?=null
        try {
            derived=key(raw,slot)
            val master=VaultCrypto.decrypt(derived,VaultCrypto.decode(slot.wrappedMaster),aad(slot))
            if(master.size!=32) {master.fill(0);throw IllegalArgumentException("恢复主密钥长度无效")}
            return VaultMaterial(slot.vaultId,master)
        } finally {raw.fill(0);derived?.fill(0)}
    }

    fun encode(slot: RecoverySlot): ByteArray {validate(slot);return json.encodeToString(slot).toByteArray(Charsets.UTF_8).also {require(it.size<=MAX_BYTES)}}
    fun decode(bytes: ByteArray): RecoverySlot {
        require(bytes.size in 1..MAX_BYTES) {"恢复封装大小无效"}
        val text=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        return json.decodeFromString<RecoverySlot>(text).also(::validate)
    }
}
