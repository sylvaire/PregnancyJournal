package cn.yunji.journal.data

import android.content.Context
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.core.security.VaultMaterial
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/** An on-device safety copy, not a cloud success marker or permission to replace a changing live DB. */
data class RestoreSafetyReceipt internal constructor(val file: File,val vaultId: String,val snapshotId: String,
    val capturedSequence: Long,val size: Long,val sha256: String) {
    override fun toString()="RestoreSafetyReceipt(<redacted>)"
}

class RestoreSafetyBackup(private val context: Context,private val material: VaultMaterial,
    private val importer: DocumentImporter,private val settings: RecoverySettings,private val clock: Clock) {
    private val root=File(context.noBackupFilesDir,"vault/restore-safety")
    suspend fun create(progress: (String)->Unit={}): RestoreSafetyReceipt=withContext(Dispatchers.IO) {
        val slot=requireNotNull(settings.load()) {"请先保存并确认当前资料库的恢复密钥"}
        require(root.isDirectory || root.mkdirs())
        val id=UUID.randomUUID().toString()
        val pending=File(root,"$id.pending")
        val complete=File(root,"$id.yunji")
        val check=File(root,"check-$id")
        require(!pending.exists() && !complete.exists() && check.mkdir())
        var success=false
        try {
            BackupExporter(context,material,importer,settings,clock).exportTo({FileOutputStream(pending) },progress)
            val task=currentCoroutineContext().job
            val snapshot=LocalBackupArchive.verifyWithVault(pending,material,slot,check,{task.ensureActive()}).use {verified->
                IsolatedRestore.withDatabase(context,verified){task.ensureActive()}
                verified.snapshot
            }
            task.ensureActive()
            RandomAccessFile(pending,"rw").use {it.fd.sync()}
            val size=pending.length();val hash=hash(pending)
            Files.move(pending.toPath(),complete.toPath(),StandardCopyOption.ATOMIC_MOVE)
            syncDirectoryDurably(root)
            syncDirectoryDurably(requireNotNull(root.parentFile))
            task.ensureActive()
            RestoreSafetyReceipt(complete,material.id,snapshot.snapshotId,snapshot.capturedSequence,size,hash).also {success=true}
        }finally {
            pending.delete();check.deleteRecursively()
            // Only this operation's uncommitted output is eligible for removal.
            if(!success)complete.delete()
        }
    }
    /** Recheck immediately before the final guarded switch; callers must also freeze/compare the live sequence. */
    suspend fun verify(receipt: RestoreSafetyReceipt)=withContext(Dispatchers.IO) {
        require(receipt.vaultId==material.id && receipt.file.parentFile.canonicalFile==root.canonicalFile)
        require(receipt.file.name.matches(Regex("[0-9a-f-]{36}\\.yunji")))
        require(receipt.file.isFile && receipt.file.length()==receipt.size && hash(receipt.file)==receipt.sha256)
        val slot=requireNotNull(settings.load())
        val check=File(root,"check-${UUID.randomUUID()}").apply {require(mkdir())}
        val task=currentCoroutineContext().job
        try {
            LocalBackupArchive.verifyWithVault(receipt.file,material,slot,check,{task.ensureActive()}).use {verified->
                require(verified.snapshot.snapshotId==receipt.snapshotId && verified.snapshot.capturedSequence==receipt.capturedSequence)
                IsolatedRestore.withDatabase(context,verified){task.ensureActive()}
            }
        }finally {check.deleteRecursively()}
    }
    /** Verification is followed by one atomic sequence check + mutation seal. New writes cannot slip between. */
    internal suspend fun sealCurrent(receipt: RestoreSafetyReceipt,db: JournalDatabase): RestoreWriteSeal {
        verify(receipt)
        return db.sealForRestore(receipt.capturedSequence)
    }
    private fun hash(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val buffer=ByteArray(64*1024);while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
}