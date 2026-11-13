package cn.yunji.journal.core.backup

import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*

/** Portable ZIP container, encrypted manifest and already-encrypted immutable objects. */
object LocalBackupArchive {
    private const val MAX_TOTAL=8L*1024*1024*1024
    private const val MAX_ENTRIES=200_002
    private fun objectName(id: String): String {
        require(runCatching {UUID.fromString(id).toString()==id}.getOrDefault(false))
        return "objects/$id.bin"
    }
    private fun copy(input: InputStream,output: OutputStream,limit: Long,checkpoint: ()->Unit): Long {
        val buffer=ByteArray(64*1024);var count=0L
        while(true){checkpoint();val n=input.read(buffer);if(n<0)break;count+=n;require(count<=limit) {"备份条目超出大小限制"};output.write(buffer,0,n)}
        return count
    }
    private fun read(input: InputStream,limit: Int,checkpoint: ()->Unit): ByteArray=ByteArrayOutputStream().use {out->copy(input,out,limit.toLong(),checkpoint);out.toByteArray()}
    private fun verifyCipher(file: File,info: StoredObject,checkpoint: ()->Unit) {
        require(file.length()==info.cipherSize) {"备份原件长度不匹配"}
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val buffer=ByteArray(64*1024);while(true){checkpoint();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        require(VaultCrypto.hex(digest.digest())==info.cipherSha256) {"备份原件密文校验失败"}
    }

    /** The caller must retain ObjectProtection.withSnapshot until this function returns. */
    fun write(snapshot: LogicalSnapshot,vault: VaultMaterial,slot: RecoverySlot,store: EncryptedFileStore,destination: File,checkpoint: ()->Unit={}) {
        require(!destination.exists() && slot.vaultId==vault.id)
        val objects=BackupManifest.objects(snapshot)
        require(objects.size+2<=MAX_ENTRIES && objects.sumOf {it.cipherSize}<=MAX_TOTAL)
        val header=RecoverySlots.encode(slot);val manifest=BackupManifest.encode(snapshot,vault)
        val partial=File(destination.parentFile,"${UUID.randomUUID()}.backup-partial")
        try {
            FileOutputStream(partial).use {file->
                val zip=ZipOutputStream(object: FilterOutputStream(file){
                    override fun write(bytes: ByteArray,offset: Int,length: Int){out.write(bytes,offset,length)}
                    override fun close(){flush()}
                })
                zip.use {
                    fun entry(name: String,body: ()->Unit){checkpoint();it.putNextEntry(ZipEntry(name).apply {time=0});body();it.closeEntry()}
                    entry("vault.json"){it.write(header)}
                    entry("manifest.enc"){it.write(manifest)}
                    objects.forEach {info->
                        val source=store.path(info.id);verifyCipher(source,info,checkpoint)
                        entry(objectName(info.id)){source.inputStream().use {input->require(copy(input,it,info.cipherSize,checkpoint)==info.cipherSize)}}
                    }
                }
                file.fd.sync()
            }
            checkpoint();require(!destination.exists() && partial.renameTo(destination)) {"备份文件无法保存"}
        } finally {partial.delete()}
    }

    /** Verification is isolated; no live database or device wrapper is touched. Close to wipe/delete. */
    fun verify(archive: File,recoveryKey: String,temporaryParent: File,checkpoint: ()->Unit={}): VerifiedArchive =
        verifyCore(archive,temporaryParent,checkpoint) {RecoverySlots.open(it,recoveryKey)}

    /** Export self-check uses the active VMK and requires the exact locally authenticated recovery slot. */
    fun verifyWithVault(archive: File,vault: VaultMaterial,slot: RecoverySlot,temporaryParent: File,checkpoint: ()->Unit={}): VerifiedArchive =
        verifyCore(archive,temporaryParent,checkpoint) {header->
            require(header==slot && header.vaultId==vault.id && vault.master.any {it!=0.toByte()})
            VaultMaterial(vault.id,vault.master.copyOf())
        }

    private fun verifyCore(archive: File,temporaryParent: File,checkpoint: ()->Unit,unwrap: (RecoverySlot)->VaultMaterial): VerifiedArchive {
        ZipPreflight.check(archive,MAX_ENTRIES,checkpoint)
        val directory=File(temporaryParent,"backup-verify-${UUID.randomUUID()}")
        require(!directory.exists() && directory.mkdirs())
        var material: VaultMaterial?=null
        try {
            ZipFile(archive).use {zip->
                val entries=linkedMapOf<String,ZipEntry>()
                val iterator=zip.entries()
                while(iterator.hasMoreElements()) {
                    checkpoint();val entry=iterator.nextElement()
                    require(!entry.isDirectory && entries.size<MAX_ENTRIES && entries.put(entry.name,entry)==null) {"备份包含重复或无效条目"}
                    require(entry.name in setOf("vault.json","manifest.enc") || Regex("objects/[0-9a-f-]{36}\\.bin").matches(entry.name)) {"备份包含不支持的路径"}
                    require(entry.method in setOf(ZipEntry.STORED,ZipEntry.DEFLATED))
                }
                val header=requireNotNull(entries["vault.json"]);val encrypted=requireNotNull(entries["manifest.enc"])
                val slot=zip.getInputStream(header).use {RecoverySlots.decode(read(it,4096,checkpoint))}
                val vault=unwrap(slot);material=vault
                val snapshot=zip.getInputStream(encrypted).use {BackupManifest.decode(read(it,BackupManifest.MAX_BYTES+32,checkpoint),vault)}
                val objects=BackupManifest.objects(snapshot)
                require(entries.keys==objects.map {objectName(it.id)}.toSet()+setOf("vault.json","manifest.enc")) {"备份原件缺失或包含多余条目"}
                require(objects.sumOf {it.cipherSize}<=MAX_TOTAL)
                val store=EncryptedFileStore(File(directory,"objects"),vault)
                objects.forEach {info->
                    val entry=requireNotNull(entries[objectName(info.id)])
                    require(entry.size==info.cipherSize) {"备份原件长度不匹配"}
                    zip.getInputStream(entry).use {input->store.path(info.id).outputStream().use {output->require(copy(input,output,info.cipherSize,checkpoint)==info.cipherSize)}}
                    verifyCipher(store.path(info.id),info,checkpoint)
                    val plain=File(directory,"authenticated-original")
                    try {checkpoint();store.decryptTo(info,plain,checkpoint=checkpoint);checkpoint()}finally {plain.delete()}
                }
                checkpoint()
                return VerifiedArchive(snapshot,vault,directory,slot)
            }
        } catch(e: Throwable){material?.master?.fill(0);directory.deleteRecursively();throw e}
    }

    class VerifiedArchive internal constructor(val snapshot: LogicalSnapshot,val material: VaultMaterial,val directory: File,val slot: RecoverySlot): AutoCloseable {
        override fun close(){material.master.fill(0);directory.deleteRecursively()}
        override fun toString()="VerifiedArchive(<redacted>)"
    }
}
