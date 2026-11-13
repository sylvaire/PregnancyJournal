package cn.yunji.journal.core.security

import android.content.Context
import cn.yunji.journal.core.backup.VaultLayout
import cn.yunji.journal.core.database.JournalDatabase
import cn.yunji.journal.data.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** One resource graph per key session. Hilt providers never retain a closed database or VMK. */
@Singleton
class VaultRuntime @Inject constructor(@param:ApplicationContext private val context: Context,
    private val keys: DeviceKeyStore,private val session: VaultSession,private val clock: Clock,
    private val reminderStore: ReminderStore,private val notifier: ReminderNotifier,private val access: AccessState) {
    class Resources(val material: VaultMaterial,val database: JournalDatabase,val repository: JournalRepository,
        val importer: DocumentImporter,val reminders: ReminderScheduler)
    private var current: Resources?=null
    private var closing=false
    private val revocation=Mutex()
    @Synchronized fun resources(): Resources {
        if(closing || keys.strict() && session.material()==null)throw VaultLockedException()
        current?.let {return it}
        val strict=keys.strict()
        val device=if(strict)session.require() else keys.open()
        var openedMaterial: VaultMaterial?=null
        var openedDatabase: JournalDatabase?=null
        try {
            val vaultDir=File(context.noBackupFilesDir,"vault").apply {mkdirs()}
            RestoreStartup.reconcile(vaultDir,{dir->cn.yunji.journal.core.backup.syncDirectoryDurably(dir)}) {id->RestoreGenerationVerifier.verify(context,vaultDir,id,device)}
            val layout=VaultLayout(vaultDir)
            val generation=layout.activeGenerationId()
            val material=if(generation==null)device else cn.yunji.journal.core.backup.GenerationKeyStore(vaultDir).open(generation,device)
            openedMaterial=material
            val databaseFile=layout.activeDatabase()
            val objectDirectory=layout.activeObjects()
            val dbKey=material.key("database")
            val db=try {JournalDatabase.open(context,databaseFile,dbKey)}finally {dbKey.fill(0)}
            openedDatabase=db
            val resources=Resources(material,db,JournalRepository(db,clock),DocumentImporter(context,db,material,clock,objectDirectory),ReminderScheduler(context,db,clock,reminderStore,notifier,access))
            val restoreJournal=cn.yunji.journal.core.backup.RestoreJournal(vaultDir)
            if(generation!=null && restoreJournal.read()==cn.yunji.journal.core.backup.RestoreStage.VERIFIED && restoreJournal.targetId()==generation) {
                cn.yunji.journal.core.backup.RestoreCompletion(layout.generationDir(generation)).run {
                    CloudSchedulerStore(context).afterRestore()
                    CloudUploadStatusStore(context).afterRestore()
                    runCatching { cn.yunji.journal.data.BackupFailureNotifier(context).clear() }
                    kotlinx.coroutines.runBlocking {
                        resources.reminders.pauseForRestore()
                        resources.reminders.resumeAfterRestoreAbort()
                        resources.reminders.rebuild()
                    }
                }
            }
            if(material!==device && !strict)device.master.fill(0)
            current=resources
            return resources
        } catch(e: Exception) {
            try {openedDatabase?.close()}finally {
                openedMaterial?.master?.fill(0)
                if(!strict || openedMaterial===device)device.master.fill(0)
            }
            throw e
        }
    }
    fun authenticated(material: VaultMaterial) {session.set(material)}
    fun backupExporter(): BackupExporter {
        val resources=resources()
        return BackupExporter(context,resources.material,resources.importer,RecoverySettings(context,resources.material),clock)
    }
    suspend fun clearPreviews() {synchronized(this){current}?.importer?.clearPreviews()}
    suspend fun revoke(): Unit=withContext(kotlinx.coroutines.NonCancellable+Dispatchers.IO) {
        revocation.withLock {
        val old=synchronized(this@VaultRuntime){closing=true;current.also {current=null}}
        try {
            try {old?.importer?.shutdown()} finally {old?.database?.close()}
        } finally {
            old?.material?.master?.fill(0);session.clear()
            synchronized(this@VaultRuntime){closing=false}
        }
        }
    }
    internal suspend fun prepareRestore(wizard: cn.yunji.journal.core.backup.RestoreWizard,archive: File,recoveryKey: String): cn.yunji.journal.core.backup.RestorePreview {
        var prepared: cn.yunji.journal.core.backup.RestorePreview?=null
        try {
            return withContext(Dispatchers.IO) {revocation.withLock {
                resources()
                val device=if(keys.strict())session.require().let {VaultMaterial(it.id,it.master.clone())} else keys.open()
                val task=kotlinx.coroutines.currentCoroutineContext()
                try {
                    wizard.prepare(archive,recoveryKey,device,{task.ensureActive()}) {handle->
                        cn.yunji.journal.core.backup.IsolatedRestore.buildDatabase(context,handle)
                    }.also {prepared=it}
                }finally {device.master.fill(0)}
            }}
        }catch(cancelled: kotlinx.coroutines.CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable+Dispatchers.IO){prepared?.let {wizard.discard(it)}}
            throw cancelled
        }
    }
    /** Called only after preview/user confirmation; UI must remain covered until a new graph is obtained. */
    internal suspend fun commitPreparedRestore(
        wizard: cn.yunji.journal.core.backup.RestoreWizard,
        preview: cn.yunji.journal.core.backup.RestorePreview,
        quiesceBackground: suspend ()->Unit,
    ): RestoreSafetyReceipt=withContext(Dispatchers.IO) {
        revocation.withLock {
            val old=resources()
            val strict=keys.strict()
            val device=if(strict)session.require().let {VaultMaterial(it.id,it.master.clone())} else keys.open()
            synchronized(this@VaultRuntime){closing=true}
            var stopped=false
            var retired=false
            var seal: cn.yunji.journal.core.database.RestoreWriteSeal?=null
            try {
                // Mandatory caller hook: stop cloud/reminder producers before draining import readers.
                quiesceBackground()
                val safety=RestoreSafetyBackup(context,old.material,old.importer,RecoverySettings(context,old.material),clock)
                val receipt=safety.create()
                stopped=true
                old.importer.shutdown()
                seal=safety.sealCurrent(receipt,old.database)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                withContext(kotlinx.coroutines.NonCancellable) {
                    requireNotNull(seal).retire()
                    retired=true
                    // No asynchronous reader or queued old mutation may survive the graph close.
                    try {old.database.close()}finally {
                        old.material.master.fill(0);session.clear()
                        synchronized(this@VaultRuntime){current=null}
                    }
                    wizard.commit(preview) {
                        RestoreGenerationVerifier.verify(context,File(context.noBackupFilesDir,"vault"),preview.generationId,device)
                    }
                }
                receipt
            }finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        if(!retired) {
                            seal?.abort()
                            if(stopped) {
                                try {old.database.close()}finally {
                                    old.material.master.fill(0);session.clear()
                                    synchronized(this@VaultRuntime){current=null}
                                }
                            }
                        }
                    }finally {
                        device.master.fill(0)
                        synchronized(this@VaultRuntime){closing=false}
                    }
                }
            }
        }
    }
    internal fun hasResources(): Boolean=synchronized(this){current!=null}
}
