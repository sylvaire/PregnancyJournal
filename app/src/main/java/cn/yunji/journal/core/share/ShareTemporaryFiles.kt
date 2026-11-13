package cn.yunji.journal.core.share

import java.io.File

/**
 * Owns the only directory the share provider can reach: `filesDir/share`.
 *
 * At most one original is staged at a time. Staging a new file first clears the previous residue, so
 * an interrupted/return-early path can never accumulate plaintext; [clearAll] is also invoked when the
 * provider starts and whenever the documents screen resumes.
 */
class ShareTemporaryFiles(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(directory.name == ShareOriginalPolicy.DIRECTORY_NAME) { "分享目录名称不正确" }
    }

    fun directory(): File = directory

    fun ensureDirectory(): File {
        require(directory.isDirectory || directory.mkdirs()) { "无法创建分享目录" }
        return directory
    }

    /** Clears residue and returns a not-yet-existing file inside the share directory. */
    fun prepare(name: String): File {
        require(
            name.isNotBlank() && name.length <= 160 && !name.startsWith('.') &&
                name == File(name).name && name.none { it.isISOControl() || it == '/' || it == '\\' }
        ) { "分享文件名称不正确" }
        ensureDirectory()
        clearAll()
        val file = File(directory, name)
        require(isInside(file)) { "分享文件路径越界" }
        return file
    }

    fun contains(file: File): Boolean = file.parentFile?.canonicalFile == directory.canonicalFile && isInside(file)

    fun delete(file: File): Boolean = contains(file) && file.delete()

    /** Deletes every staged file (and any unexpected nested entry); returns the number of removed entries. */
    fun clearAll(): Int {
        var removed = 0
        directory.listFiles().orEmpty().forEach { entry ->
            val gone = if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
            if (gone) removed++
        }
        return removed
    }

    /** Startup safety net: removes anything older than [maxAgeMillis] without touching a fresh share. */
    fun clearStale(now: Long = clock(), maxAgeMillis: Long = ShareOriginalPolicy.MAX_TEMP_MILLIS): Int {
        require(maxAgeMillis >= 0) { "保留时长不正确" }
        return directory.listFiles().orEmpty().count { entry ->
            val cutoff = now - maxAgeMillis
            when {
                entry.isDirectory -> entry.deleteRecursively()
                entry.lastModified() in 0 until cutoff -> entry.delete()
                entry.lastModified() == 0L -> entry.delete()
                else -> false
            }
        }
    }

    fun files(): List<File> = directory.listFiles().orEmpty().filter { it.isFile }

    private fun isInside(file: File): Boolean = runCatching {
        val root = directory.canonicalPath + File.separator
        file.canonicalPath.startsWith(root) && file.canonicalPath != directory.canonicalPath
    }.getOrDefault(false)
}
