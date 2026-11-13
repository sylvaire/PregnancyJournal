package cn.yunji.journal.core.backup

import cn.yunji.journal.core.security.VaultMaterial
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Per-generation VMK wrapper, anchored in the unchanged device-unlock vault. */
class GenerationKeyStore(private val vaultDir: File,private val syncDirectory: (File)->Unit = ::syncDirectoryDurably) {
    private val layout=VaultLayout(vaultDir,syncDirectory)
    fun install(generationId: String,device: VaultMaterial,restored: VaultMaterial) {
        val directory=layout.generationDir(generationId)
        require(directory.isDirectory) {"恢复代际缺失"}
        val file=File(directory,"material.enc")
        require(!file.exists()) {"恢复代际密钥已存在"}
        val bytes=GenerationKeyEnvelope.wrap(device,generationId,restored)
        val pending=File(directory,"material.pending")
        try {
            RandomAccessFile(pending,"rw").use {it.setLength(0);it.write(bytes);it.fd.sync()}
            Files.move(pending.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(directory)
            // Persist the generation's name before any active pointer can reference it.
            syncDirectory(requireNotNull(directory.parentFile))
            syncDirectory(vaultDir)
        }finally {pending.delete()}
    }
    fun open(generationId: String,device: VaultMaterial): VaultMaterial {
        val file=File(layout.generationDir(generationId),"material.enc")
        require(file.isFile && file.length() in 32..512) {"恢复密钥封装缺失或损坏，原始资料已保留"}
        return GenerationKeyEnvelope.open(device,generationId,file.readBytes())
    }
}