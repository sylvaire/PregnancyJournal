package cn.yunji.journal.core.backup

import cn.yunji.journal.core.database.PregnancyDateRevisionEntity
import cn.yunji.journal.core.database.PregnancyEntity
import cn.yunji.journal.core.database.VisitEntity
import cn.yunji.journal.core.security.RecoveryKey
import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.core.security.VaultMaterial
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** P0-only logical snapshot. Production backup format is not frozen until all business tables exist. */
@Serializable
data class ProbeSnapshot(
    val formatVersion: Int = 1,
    val vaultId: String,
    val capturedSequence: Long,
    val pregnancies: List<PregnancyEntity>,
    val dateRevisions: List<PregnancyDateRevisionEntity>,
    val visits: List<VisitEntity>,
)

@Serializable
private data class SnapshotEnvelope(val format: String = "YJ-P0-1", val vaultId: String, val wrappedMaster: String, val payload: String)

object SnapshotCodec {
    private val json = Json { ignoreUnknownKeys = false }
    const val MAX_PROBE_BYTES = 8 * 1024 * 1024

    fun encode(snapshot: ProbeSnapshot, vault: VaultMaterial, recoveryKey: String): ByteArray {
        require(snapshot.vaultId == vault.id)
        val plain = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_PROBE_BYTES)
        val recovery = RecoveryKey.parse(recoveryKey)
        try {
            val wrapped = VaultCrypto.encrypt(VaultCrypto.derive(recovery, vault.id, "recovery"), vault.master,
                "probe/v1/${vault.id}/master")
            val payload = VaultCrypto.encrypt(vault.key("snapshot"), plain, "probe/v1/${vault.id}/data")
            return json.encodeToString(SnapshotEnvelope(vaultId = vault.id, wrappedMaster = VaultCrypto.encode(wrapped),
                payload = VaultCrypto.encode(payload))).toByteArray(Charsets.UTF_8)
        } finally { recovery.fill(0); plain.fill(0) }
    }

    fun decode(bytes: ByteArray, recoveryKey: String): ProbeSnapshot {
        require(bytes.size <= MAX_PROBE_BYTES * 2) { "技术验证快照过大" }
        val envelope = json.decodeFromString<SnapshotEnvelope>(bytes.toString(Charsets.UTF_8))
        require(envelope.format == "YJ-P0-1") { "不支持的快照格式" }
        val recovery = RecoveryKey.parse(recoveryKey)
        var master: ByteArray? = null
        var plain: ByteArray? = null
        try {
            master = VaultCrypto.decrypt(VaultCrypto.derive(recovery, envelope.vaultId, "recovery"),
                VaultCrypto.decode(envelope.wrappedMaster), "probe/v1/${envelope.vaultId}/master")
            plain = VaultCrypto.decrypt(VaultCrypto.derive(master, envelope.vaultId, "snapshot"),
                VaultCrypto.decode(envelope.payload), "probe/v1/${envelope.vaultId}/data")
            val snapshot = json.decodeFromString<ProbeSnapshot>(plain.toString(Charsets.UTF_8))
            require(snapshot.formatVersion == 1 && snapshot.vaultId == envelope.vaultId)
            require(snapshot.pregnancies.map { it.id }.distinct().size == snapshot.pregnancies.size)
            val ids = snapshot.pregnancies.map { it.id }.toSet()
            require(snapshot.visits.all { it.pregnancyId in ids }) { "快照存在无关联产检" }
            require(snapshot.dateRevisions.all { it.pregnancyId in ids }) { "日期依据缺少档案" }
            return snapshot
        } finally { recovery.fill(0); master?.fill(0); plain?.fill(0) }
    }
}
