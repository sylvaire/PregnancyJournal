package cn.yunji.journal.data

import android.content.Context
import android.util.AtomicFile
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface RecoverySettingsAccess {
    val vaultId: String get()=""
    suspend fun configured(): Boolean
    suspend fun confirm(setup: RecoveryKeySetup,answers: List<String>)
}

/** Keeps only the confirmed recovery wrapper, encrypted with a separate VMK-derived purpose. */
class RecoverySettings(context: Context,private val material: VaultMaterial): RecoverySettingsAccess {
    override val vaultId get()=material.id
    private val file=AtomicFile(File(cn.yunji.journal.core.backup.VaultLayout(File(context.noBackupFilesDir,"vault")).activeDirectory(),"recovery-slot.enc").also {it.parentFile!!.mkdirs()})
    internal fun load(): RecoverySlot?=synchronized(lock){loadLocked()}
    private fun loadLocked(): RecoverySlot? {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())return null
        val key=material.key("recovery-settings")
        var plain: ByteArray?=null
        try {
            val bytes=file.openRead().use {require(it.channel.size()<=8192);it.readBytes()}
            plain=VaultCrypto.decrypt(key,bytes,"recovery-settings/v1/${material.id}")
            return RecoverySlots.decode(plain).also {require(it.vaultId==material.id)}
        } finally {key.fill(0);plain?.fill(0)}
    }
    override suspend fun configured()=withContext(Dispatchers.IO){load()!=null}
    override suspend fun confirm(setup: RecoveryKeySetup,answers: List<String>)=withContext(Dispatchers.IO) {
        val slot=setup.confirm(material,answers)
        save(slot)
    }
    internal fun save(slot: RecoverySlot)=synchronized(lock){saveLocked(slot)}
    internal fun saveIfMissing(slot: RecoverySlot): Boolean=synchronized(lock) {
        if(loadLocked()!=null)false else {saveLocked(slot);true}
    }
    private fun saveLocked(slot: RecoverySlot) {
        require(slot.vaultId==material.id && material.master.any {it!=0.toByte()})
        val key=material.key("recovery-settings");val plain=RecoverySlots.encode(slot)
        val bytes=try {VaultCrypto.encrypt(key,plain,"recovery-settings/v1/${material.id}")} finally {key.fill(0);plain.fill(0)}
        val output=file.startWrite()
        try {output.write(bytes);file.finishWrite(output)}catch(e: Throwable){file.failWrite(output);throw e}
    }
    companion object {private val lock=Any()}
}
