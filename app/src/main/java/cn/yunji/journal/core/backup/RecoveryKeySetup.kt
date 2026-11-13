package cn.yunji.journal.core.backup

import cn.yunji.journal.core.security.RecoveryKey
import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.core.security.VaultMaterial
import java.security.MessageDigest
import java.security.SecureRandom

/** Ephemeral setup state. Do not serialize or put it in SavedStateHandle, Bundle or preferences. */
class RecoveryKeySetup : AutoCloseable {
    private val raw=VaultCrypto.randomKey()
    private var closed=false
    /** One-based six-character groups; the UI asks for these only after hiding the full key. */
    val requestedGroups: List<Int>
    init {
        val random=SecureRandom()
        val first=random.nextInt(8)
        val second=random.nextInt(7).let {if(it>=first)it+1 else it}
        requestedGroups=listOf(first+1,second+1)
    }
    @Synchronized fun keyText(): String {check(!closed);return RecoveryKey.format(raw)}
    @Synchronized fun isActive(): Boolean = !closed
    @Synchronized fun confirm(vault: VaultMaterial,answers: List<String>): RecoverySlot {
        check(!closed)
        require(answers.size==2 && answers.all {it.length<=32}) {"请填写要求的两个密钥片段"}
        val groups=RecoveryKey.format(raw).substring(4).chunked(6)
        val matches=requestedGroups.indices.all {index->
            MessageDigest.isEqual(groups[requestedGroups[index]-1].toByteArray(Charsets.US_ASCII),answers[index].trim().toByteArray(Charsets.UTF_8))
        }
        require(matches) {"密钥片段不匹配，请核对保存的恢复密钥"}
        return try {RecoverySlots.create(vault,RecoveryKey.format(raw))} finally {close()}
    }
    @Synchronized override fun close() {raw.fill(0);closed=true}
    override fun toString()="RecoveryKeySetup(<redacted>)"
}
