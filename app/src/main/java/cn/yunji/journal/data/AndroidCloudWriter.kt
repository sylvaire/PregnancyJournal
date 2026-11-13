package cn.yunji.journal.data

import android.content.Context
import android.system.Os
import android.system.OsConstants
import cn.yunji.journal.core.backup.CloudWriterStore
import cn.yunji.journal.core.security.VaultMaterial
import java.io.File

/** Production writer state is device-local, excluded from OS backup and logical restore. Call on IO. */
object AndroidCloudWriter {
    fun open(context: Context,vault: VaultMaterial): CloudWriterStore {
        val root=context.noBackupFilesDir
        val directory=cn.yunji.journal.core.backup.VaultLayout(File(root,"vault")).activeDirectory()
        require(directory.isDirectory || directory.mkdir())
        // Also sync the parent, including when a prior mkdir succeeded but its fsync failed.
        syncDirectory(root)
        return CloudWriterStore(directory,vault,::syncDirectory)
    }
    internal fun syncDirectory(directory: File) {
        val fd=Os.open(directory.absolutePath,OsConstants.O_RDONLY,0)
        try {require(OsConstants.S_ISDIR(Os.fstat(fd).st_mode));Os.fsync(fd)}finally {Os.close(fd)}
    }
}