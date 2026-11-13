package cn.yunji.journal.core.backup

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Effects must be idempotent. A crash before the final marker retries them before exposing the graph. */
class RestoreCompletion(private val directory: File,private val syncDirectory: (File)->Unit = ::syncDirectoryDurably) {
    fun run(effects: ()->Unit) {
        val marker=File(directory,"device-settings.ready")
        if(marker.exists()) {require(marker.length() in 1..64 && marker.readText()=="YJ-RESTORE-SETTINGS-1");return}
        effects()
        val pending=File(directory,"device-settings.pending")
        try {
            RandomAccessFile(pending,"rw").use {it.setLength(0);it.write("YJ-RESTORE-SETTINGS-1".toByteArray());it.fd.sync()}
            Files.move(pending.toPath(),marker.toPath(),StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(directory)
        }finally {pending.delete()}
    }
}