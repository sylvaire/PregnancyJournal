package cn.yunji.journal.core.backup

import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.StoredObject
import kotlinx.serialization.json.Json

/** Encrypted business dictionary; public ZIP names never contain names, dates or business counts. */
object BackupManifest {
    const val MAX_BYTES=16*1024*1024
    private val json=Json {encodeDefaults=true;ignoreUnknownKeys=false}
    private fun aad(vaultId: String)="backup/v1/$vaultId/manifest"
    fun encode(snapshot: LogicalSnapshot,vault: VaultMaterial): ByteArray {
        require(snapshot.vaultId==vault.id);SnapshotRelations.validate(snapshot)
        SnapshotContent.validate(snapshot)
        val plain=json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        val key=vault.key("backup-manifest")
        try {require(plain.size<=MAX_BYTES);return VaultCrypto.encrypt(key,plain,aad(vault.id))}
        finally {plain.fill(0);key.fill(0)}
    }
    fun decode(bytes: ByteArray,vault: VaultMaterial): LogicalSnapshot {
        require(bytes.size in 32..MAX_BYTES+32)
        val key=vault.key("backup-manifest")
        val plain=try {VaultCrypto.decrypt(key,bytes,aad(vault.id))}finally {key.fill(0)}
        try {
            val text=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(plain)).toString()
            // Reject excessive nesting before the serializer can allocate a recursive object graph.
            var depth=0;var quoted=false;var escaped=false
            text.forEach {c->
                if(quoted){if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='"')quoted=false}
                else when(c){'"'->quoted=true;'[','{'->{depth++;require(depth<=32)};']','}'->{depth--;require(depth>=0)}}
            }
            require(!quoted && depth==0)
            return json.decodeFromString<LogicalSnapshot>(text).also {require(it.vaultId==vault.id);SnapshotRelations.validate(it);SnapshotContent.validate(it)}
        } finally {plain.fill(0)}
    }
    fun objects(snapshot: LogicalSnapshot): List<StoredObject> {
        SnapshotRelations.validate(snapshot)
        return snapshot.attachments.flatMap {a->
            listOfNotNull(StoredObject(a.objectId ?: a.id,a.plainSize,a.cipherSize,a.plainSha256,a.cipherSha256),
                a.thumbnailId?.let {StoredObject(it,requireNotNull(a.thumbnailSize),requireNotNull(a.thumbnailCipherSize),requireNotNull(a.thumbnailSha256),requireNotNull(a.thumbnailCipherSha256))})
        }.distinctBy {it.id}
    }
}
