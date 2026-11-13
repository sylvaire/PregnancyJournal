package cn.yunji.journal.core.backup

import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.core.security.VaultMaterial
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.util.UUID

/** A restored VMK is wrapped by the local device vault, never persisted as plaintext. */
object GenerationKeyEnvelope {
    @Serializable private data class Payload(val format: Int=1,val vaultId: String,val master: String) {
        override fun toString()="GenerationKeyPayload(<redacted>)"
    }
    private val json=Json {encodeDefaults=true;ignoreUnknownKeys=false}
    private fun uuid(value: String) {require(UUID.fromString(value).toString()==value)}
    private fun aad(device: VaultMaterial,generation: String): String {
        uuid(device.id);uuid(generation)
        require(device.master.size==32 && device.master.any {it!=0.toByte()})
        return "generation-key/v1/${device.id}/$generation"
    }
    fun wrap(device: VaultMaterial,generationId: String,restored: VaultMaterial): ByteArray {
        val aad=aad(device,generationId);uuid(restored.id)
        require(restored.master.size==32 && restored.master.any {it!=0.toByte()})
        val key=device.key("generation-key")
        val plain=json.encodeToString(Payload(vaultId=restored.id,master=VaultCrypto.encode(restored.master))).toByteArray(Charsets.UTF_8)
        try {return VaultCrypto.encrypt(key,plain,aad)}finally {key.fill(0);plain.fill(0)}
    }
    /** Caller must clear the returned session when the generation closes. */
    fun open(device: VaultMaterial,generationId: String,bytes: ByteArray): VaultMaterial {
        val aad=aad(device,generationId);require(bytes.size in 32..512)
        val key=device.key("generation-key")
        val plain=try {VaultCrypto.decrypt(key,bytes,aad)}finally {key.fill(0)}
        try {
            val payload=json.decodeFromString<Payload>(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain)).toString())
            require(payload.format==1);uuid(payload.vaultId)
            require(payload.master.length==43 && payload.master.matches(Regex("[A-Za-z0-9_-]{43}")))
            val master=VaultCrypto.decode(payload.master)
            if(master.size!=32 || master.all {it==0.toByte()}){master.fill(0);throw IllegalArgumentException("恢复密钥封装无效")}
            return VaultMaterial(payload.vaultId,master)
        }finally {plain.fill(0)}
    }
}