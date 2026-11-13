package cn.yunji.journal.core.backup

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Removes only named, inactive workspaces beneath an app-owned directory, without following links. */
internal object TransientWorkspaceCleanup {
    private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    val exportJob = Regex("job-$UUID")
    val restoreStaging = Regex("restore-$UUID")
    val backupVerification = Regex("backup-verify-$UUID")

    fun recover(root: File, acceptedNames: Set<Regex>, activeNames: Set<String>) {
        if (!root.exists()) return
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "临时目录不可用" }
        val canonicalRoot = root.canonicalFile
        for (child in requireNotNull(root.listFiles()) { "无法扫描临时目录" }) {
            if (child.name in activeNames || acceptedNames.none { it.matches(child.name) }) continue
            if (Files.isSymbolicLink(child.toPath()) || child.canonicalFile.parentFile != canonicalRoot) continue
            deleteTree(child)
        }
    }

    fun deleteTree(target: File) {
        if (!target.exists() && !Files.isSymbolicLink(target.toPath())) return
        Files.walkFileTree(target.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
