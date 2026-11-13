package cn.yunji.journal.core.network

import cn.yunji.journal.core.backup.*
import java.io.File

/** Scoped to a selected folder. Only canonical random identities form paths beneath it. */
class DavBackupRemote(private val client: WebDavClient,private val folder: List<String>,
    private val checkpoint: ()->Unit={}): CloudBackupRemote {
    private var root: List<String>?=null
    override fun prepare(slot: RecoverySlot) {
        val header=RecoverySlots.encode(slot)
        val container=folder+"pregnancy-journal"
        ensureDirectory(folder,container)
        val vaultRoot=container+slot.vaultId
        ensureDirectory(container,vaultRoot)
        val entries=client.list(vaultRoot)
        require(entries.all {it.status in 200..299}) {"云端目录不完整，已停止备份"}
        val stored=optional(vaultRoot+"vault.json",4096)
        if(stored==null) {
            require(entries.all {it.segments==vaultRoot}) {"云端目录缺少身份文件且已有内容，已停止备份"}
            try {client.putNew(vaultRoot+"vault.json",header)}catch(e: DavException){if(e.status!=412)throw e}
        }
        require(RecoverySlots.decode(requireNotNull(optional(vaultRoot+"vault.json",4096)))==slot) {"云端身份或恢复封装不一致，已停止备份"}
        listOf("objects","snapshots","commits").forEach {ensureDirectory(vaultRoot,vaultRoot+it)}
        root=vaultRoot
    }
    private fun ensureDirectory(parent: List<String>,target: List<String>) {
        checkpoint()
        fun found()=client.list(parent).also {require(it.all {entry->entry.status in 200..299})}.firstOrNull {it.segments==target}
        var entry=found()
        if(entry==null) {
            try {client.createDirectory(target)}catch(e: DavException){if(e.status!=405)throw e}
            entry=found()
        }
        require(entry?.collection==true) {"云端路径不是目录"}
    }
    private fun optional(path: List<String>,limit: Int): ByteArray?=try {checkpoint();client.get(path,limit)}catch(e: DavException){if(e.status==404)null else throw e}
    override fun uploadVerified(path: List<String>,file: File,size: Long,sha256: String) {
        val actual=requireNotNull(root)+path
        checkpoint()
        try {client.verifyDownloaded(actual,size,sha256,checkpoint);return}
        catch(e: DavException){if(e.status!=404)throw e}
        try {client.putFile(actual,file,size,sha256,checkpoint)}catch(e: DavException){if(e.status!=412)throw e}
        client.verifyDownloaded(actual,size,sha256,checkpoint)
    }
    override fun readCommit(snapshotId: String)=optional(requireNotNull(root)+listOf("commits","$snapshotId.json"),CloudCommit.MAX_BYTES)
    /** Every listed commit is authenticated with its manifest before writer arbitration. */
    fun verifiedCommitWriterIds(vault: cn.yunji.journal.core.security.VaultMaterial): Map<String, String> =
        listCommitIds().associateWith { id ->
            checkpoint()
            val commit = requireNotNull(readCommit(id)) { "云端提交列表与内容不一致，已停止备份" }
            // 传输层单次读取上限为 16MiB；清单是加密元数据，按传输层上限请求，不叠加 GCM 封装开销。
            val manifest = requireNotNull(optional(requireNotNull(root)+listOf("snapshots","$id.bin"),
                BackupManifest.MAX_BYTES)) { "云端提交缺少快照，已停止备份" }
            CloudCommit.writerIdentity(commit, id, manifest, vault).writerId
        }
    /** Only well-formed commit entry names are returned; unexpected names are ignored by the caller. */
    override fun listCommitIds(): List<String> {
        val entries=client.list(requireNotNull(root)+"commits")
        require(entries.all {it.status in 200..299}) {"云端提交目录不完整，已停止自动备份"}
        return entries.filter {!it.collection}.mapNotNull {it.segments.lastOrNull()}
            .mapNotNull {name->name.takeIf {it.endsWith(".json")}?.removeSuffix(".json")}
            .filter {runCatching {java.util.UUID.fromString(it).toString()==it}.getOrDefault(false)}
    }
    override fun publishCommit(snapshotId: String,bytes: ByteArray) {
        checkpoint()
        try {client.putNew(requireNotNull(root)+listOf("commits","$snapshotId.json"),bytes)}catch(e: DavException){if(e.status!=412)throw e}
    }
}
