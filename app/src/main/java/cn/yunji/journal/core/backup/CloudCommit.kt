package cn.yunji.journal.core.backup

import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.core.security.VaultMaterial
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.util.UUID

/** Public discovery entry. Business metadata remains inside the authenticated encrypted body. */
@Serializable
private data class CommitEnvelope(val format: String="YJ-COMMIT-1",val snapshotId: String,
    val cipherSize: Long,val cipherSha256: String,val authenticated: String)
@Serializable
private data class CommitBody(val vaultId: String,val snapshotId: String,val writerId: String,
    val writerSequence: Long,val capturedSequence: Long,val createdAt: Long,
    val cipherSize: Long,val cipherSha256: String)

/** A commit authenticates a particular immutable manifest, not merely its public checksum. */
object CloudCommit {
    const val MAX_BYTES=8192
    private val json=Json {encodeDefaults=true;ignoreUnknownKeys=false}
    private fun uuid(value: String) {require(runCatching {UUID.fromString(value).toString()==value}.getOrDefault(false))}
    private fun aad(vault: String,id: String)="cloud-commit/v1/$vault/$id"
    private fun hash(bytes: ByteArray)=VaultCrypto.sha256(bytes).joinToString(""){"%02x".format(it)}
    private fun validate(envelope: CommitEnvelope) {
        require(envelope.format=="YJ-COMMIT-1");uuid(envelope.snapshotId)
        require(envelope.cipherSize in 32..BackupManifest.MAX_BYTES.toLong()+32)
        require(envelope.cipherSha256.matches(Regex("[0-9a-f]{64}")))
        require(envelope.authenticated.length in 44..4096 && envelope.authenticated.all {it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it=='-' || it=='_'})
    }
    private fun validate(snapshot: LogicalSnapshot) {
        uuid(snapshot.vaultId);uuid(snapshot.snapshotId);uuid(snapshot.writerId)
        require(snapshot.writerSequence>0 && snapshot.capturedSequence>=0 && snapshot.createdAt>=0)
    }
    private fun body(snapshot: LogicalSnapshot,bytes: ByteArray)=CommitBody(snapshot.vaultId,snapshot.snapshotId,
        snapshot.writerId,snapshot.writerSequence,snapshot.capturedSequence,snapshot.createdAt,bytes.size.toLong(),hash(bytes))
    fun create(manifest: ByteArray,vault: VaultMaterial): ByteArray {
        // Do not publish a commit for unvalidated or differently authenticated bytes.
        val snapshot=BackupManifest.decode(manifest,vault);validate(snapshot)
        val body=body(snapshot,manifest);val key=vault.key("cloud-commit")
        val plain=json.encodeToString(body).toByteArray(Charsets.UTF_8)
        try {
            val envelope=CommitEnvelope(snapshotId=snapshot.snapshotId,cipherSize=body.cipherSize,
                cipherSha256=body.cipherSha256,authenticated=VaultCrypto.encode(VaultCrypto.encrypt(key,plain,aad(vault.id,snapshot.snapshotId))))
            validate(envelope)
            return json.encodeToString(envelope).toByteArray(Charsets.UTF_8).also {require(it.size<=MAX_BYTES)}
        } finally {key.fill(0);plain.fill(0)}
    }
    /** Filename identity is mandatory: a valid entry copied under a different name is not a new snapshot. */
    fun verify(commit: ByteArray,expectedSnapshotId: String,manifest: ByteArray,vault: VaultMaterial): LogicalSnapshot {
        uuid(expectedSnapshotId);require(commit.size in 1..MAX_BYTES)
        val text=Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(commit)).toString()
        var depth=0;var quoted=false;var escaped=false
        text.forEach {c->if(quoted){if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='"')quoted=false}
            else when(c){'"'->quoted=true;'[','{'->{depth++;require(depth<=4)};']','}'->{depth--;require(depth>=0)}}}
        require(!quoted && depth==0)
        val envelope=json.decodeFromString<CommitEnvelope>(text);validate(envelope)
        require(envelope.snapshotId==expectedSnapshotId)
        require(manifest.size.toLong()==envelope.cipherSize && hash(manifest)==envelope.cipherSha256)
        val key=vault.key("cloud-commit")
        val plain=try {VaultCrypto.decrypt(key,VaultCrypto.decode(envelope.authenticated),aad(vault.id,expectedSnapshotId))}finally {key.fill(0)}
        try {
            val authenticated=json.decodeFromString<CommitBody>(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain)).toString())
            val snapshot=BackupManifest.decode(manifest,vault);validate(snapshot)
            require(authenticated==body(snapshot,manifest))
            return snapshot
        } finally {plain.fill(0)}
    }

    /**
     * Public discovery fields only, for listing existing commits without possessing the manifest.
     * Never trusted for restore; the caller must still [verify] with the manifest.
     */
    data class PublicEnvelope(val snapshotId: String, val cipherSize: Long, val cipherSha256: String)

    fun publicEnvelope(commit: ByteArray): PublicEnvelope {
        require(commit.size in 1..MAX_BYTES)
        val text=Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(commit)).toString()
        require(!text.contains("\u0000")) {"云端提交格式无效"}
        val envelope=runCatching {json.decodeFromString<CommitEnvelope>(text)}.getOrElse {throw IllegalArgumentException("云端提交格式无效")}
        validate(envelope)
        return PublicEnvelope(envelope.snapshotId,envelope.cipherSize,envelope.cipherSha256)
    }

    /** Pure representation of the commit body, used by multi-writer inspection after authentication. */
    data class WriterIdentity(val writerId: String, val writerSequence: Long, val createdAt: Long)

    /** Reports which writer produced a commit. Requires the manifest so the body can be authenticated. */
    fun writerIdentity(commit: ByteArray, snapshotId: String, manifest: ByteArray, vault: VaultMaterial): WriterIdentity {
        val snapshot = verify(commit, snapshotId, manifest, vault)
        return WriterIdentity(snapshot.writerId, snapshot.writerSequence, snapshot.createdAt)
    }
}