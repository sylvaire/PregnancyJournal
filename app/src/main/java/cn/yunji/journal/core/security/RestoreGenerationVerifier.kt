package cn.yunji.journal.core.security

import android.content.Context
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.JournalDatabase
import cn.yunji.journal.core.storage.EncryptedFileStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Used only for an interrupted, not-yet-verified switch, before any UI/worker can edit the generation. */
object RestoreGenerationVerifier {
    fun verify(context: Context,vaultDir: File,id: String,device: VaultMaterial)=runBlocking {
        val layout=VaultLayout(vaultDir)
        val material=GenerationKeyStore(vaultDir).open(id,device)
        val directory=layout.generationDir(id)
        val temporary=File(context.cacheDir,"restore-auth-${UUID.randomUUID()}")
        try {
            val file=File(directory,"restore-manifest.enc")
            require(file.isFile && file.length() in 32..BackupManifest.MAX_BYTES.toLong()+32)
            val snapshot=BackupManifest.decode(file.readBytes(),material)
            val key=material.key("database")
            val db=try {JournalDatabase.open(context,layout.generationDatabase(id),key)}finally {key.fill(0)}
            try {SnapshotReadback.verify(db,snapshot)}finally {db.close()}
            require(temporary.mkdir())
            val store=EncryptedFileStore(File(directory,"objects"),material)
            BackupManifest.objects(snapshot).forEach {info->
                val output=File(temporary,"authenticated-object")
                // Existing originals and thumbnails both use the PJF1 document AAD purpose.
                try {store.decryptTo(info,output)}finally {output.delete()}
            }
        }finally {material.master.fill(0);temporary.deleteRecursively()}
    }
}