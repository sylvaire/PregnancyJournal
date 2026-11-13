package cn.yunji.journal

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.*
import cn.yunji.journal.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Task-8 (D02): two-phase, real-process-termination matrix for the local encrypted backup.
 *
 * Every scenario is one `am instrument` run per phase. Phase 1 reaches a deliberately blocked point,
 * writes a marker under `files/task8-markers/`, then parks. The host script
 * (`docs/verification/2026-10-22-D02-process-matrix/run-task8-d02-matrix.sh`) waits for that marker
 * and force-stops the app (ActivityManager SIGKILL: no lifecycle callbacks). Phase 2 is a brand-new
 * process that asserts what survived, what was repaired by the production cold-start recovery, and
 * that a retry is safe. It is a process kill, not a power cut.
 */
@RunWith(AndroidJUnit4::class)
class D02ProcessMatrixTest {
    private val base: Context = ApplicationProvider.getApplicationContext()
    private val markers = File(base.filesDir, "task8-markers").apply { mkdirs() }
    private val clock: Clock = Clock.systemUTC()

    @After
    fun dropLockBaseline() {
        SecurityStore(base).apply {
            setLockEnabled(false)
            setTimeoutMinutes(0)
        }
    }

    private fun marker(name: String) = File(markers, "$name.marker")
    private fun phaseArg() = InstrumentationRegistry.getArguments().getString("d02Phase")
    private fun markerText(name: String): List<String> {
        val file = marker(name)
        assertTrue("缺少阶段标记：$file", file.isFile)
        return file.readLines()
    }
    /**
     * Phase-2 gate: it must only run when the host script really force-stopped phase 1. The script
     * appends `forceStoppedAt=` to the marker after `am force-stop`; without it we skip instead of
     * failing, so a plain full-suite run can never misreport a "cold start".
     */
    private fun hostDrivenLines(name: String): List<String> {
        val lines = markerText(name)
        assumeTrue(
            "phase2 必须由宿主脚本驱动：标记缺少 forceStoppedAt（脚本在 force-stop 后追加）",
            lines.any { it.startsWith("forceStoppedAt=") },
        )
        return lines
    }
    private fun field(lines: List<String>, key: String): String =
        requireNotNull(lines.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')) { "标记缺少 $key：$lines" }
    private fun guard() {
        assumeTrue("需要可解锁的资料库", !DeviceKeyStore(base).strict() && !SecurityStore(base).lockEnabled())
    }
    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sha.update(buffer, 0, n)
            }
        }
        return VaultCrypto.hex(sha.digest())
    }
    /** A context that keeps the isolated vault but uses the real app's backup-work root. */
    private fun realBackupRootContext() = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = base.noBackupFilesDir
    }
    private fun configuredExporter(vault: IsolatedVault, material: VaultMaterial): BackupExporter {
        val settings = RecoverySettings(vault.context, material)
        settings.save(RecoverySlots.create(material, RecoveryKey.create()))
        return BackupExporter(vault.context, material, vault.runtime.resources().importer, settings, clock)
    }

    // ---- scenario export-selfcheck: kill after the archive is written, before the destination opens ----

    @Test
    fun phase1ExportSelfCheckBlocked() = runBlocking {
        guard()
        assumeTrue(phaseArg() == "export-selfcheck")
        val token = "d02selfcheck"
        IsolatedVault.fresh(base, token).use { vault ->
            val resources = vault.runtime.resources()
            val material = resources.material
            val settings = RecoverySettings(vault.context, material)
            settings.save(RecoverySlots.create(material, RecoveryKey.create()))
            val exporter = BackupExporter(realBackupRootContext(), material, resources.importer, settings, clock)
            val workRoot = File(base.noBackupFilesDir, "vault/backup-work")
            File(workRoot, "keep-unrelated").apply { mkdirs(); File(this, "note.txt").writeText("synthetic unrelated") }
            val destination = File(base.cacheDir, "task8-selfcheck-destination.yunji").apply { delete() }
            marker("export-selfcheck").writeText(
                "phase=export-selfcheck pid=${Process.myPid()} token=$token\nworkRoot=${workRoot.absolutePath}\ndestination=${destination.absolutePath}",
            )
            try {
                exporter.exportTo({ destination.outputStream() }, progress = { phase ->
                    if (phase == "正在校验原件与恢复数据") {
                        marker("export-selfcheck").appendText("\nblockedAt=$phase")
                        Thread.sleep(120_000)
                    }
                })
            } finally {
                destination.delete()
            }
        }
    }

    @Test
    fun phase2ExportSelfCheckRecovered() {
        guard()
        assumeTrue(phaseArg() == "export-selfcheck")
        val lines = hostDrivenLines("export-selfcheck")
        val pid = field(lines, "pid").toInt()
        val workRoot = File(field(lines, "workRoot"))
        val destination = File(field(lines, "destination"))
        assertNotEquals("需要真正的新进程", pid, Process.myPid())
        assertTrue("被终止的导出没有留下待清理的工作目录", workRoot.listFiles().orEmpty().any { it.name.startsWith("job-") })
        // Production cold start: MainActivity -> ImportWorker.recover -> DocumentImporter.recover -> BackupExporter.recover.
        val deadline = System.currentTimeMillis() + 30_000
        ImportWorker.recover(base)
        while (System.currentTimeMillis() < deadline && workRoot.listFiles().orEmpty().any { it.name.startsWith("job-") }) {
            Thread.sleep(250)
        }
        assertFalse("生产冷启动没有清理被杀进程遗留的备份工作目录", workRoot.listFiles().orEmpty().any { it.name.startsWith("job-") })
        assertTrue("无关目录被误删", File(workRoot, "keep-unrelated/note.txt").isFile)
        assertFalse("半成品不应被发布为备份文件", destination.exists())
        assertNull("被杀进程不得留下可继续的备份状态", FileHandoff(base).read())
        File(workRoot, "keep-unrelated").deleteRecursively()
        marker("export-selfcheck").delete()
    }

    // ---- scenario export-write: kill while the destination copy has persisted only a prefix ----

    @Test
    fun phase1ExportDestinationWriteBlocked() = runBlocking {
        guard()
        assumeTrue(phaseArg() == "export-write")
        val token = "d02write"
        IsolatedVault.fresh(base, token).use { vault ->
            val resources = vault.runtime.resources()
            val material = resources.material
            val key = RecoveryKey.create()
            val settings = RecoverySettings(vault.context, material)
            settings.save(RecoverySlots.create(material, key))
            val exporter = BackupExporter(vault.context, material, resources.importer, settings, clock)
            val destination = File(vault.root, "destination-prefix.yunji").apply { delete() }
            marker("export-write").writeText(
                "phase=export-write pid=${Process.myPid()} token=$token key=$key\ndestination=${destination.absolutePath}",
            )
            try {
                exporter.exportTo({
                    val file = destination.outputStream()
                    object : OutputStream() {
                        private var written = 0
                        override fun write(b: Int) { file.write(b); written++ }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            if (written == 0) {
                                val prefix = minOf(length, 1024)
                                file.write(bytes, offset, prefix)
                                written += prefix
                                file.flush()
                                marker("export-write").appendText("\nblockedDuringDestination=true bytes=$written")
                                Thread.sleep(120_000)
                            } else {
                                file.write(bytes, offset, length)
                                written += length
                            }
                        }
                        override fun flush() { file.flush() }
                        override fun close() { file.close() }
                    }
                })
            } finally {
                destination.delete()
            }
        }
    }

    @Test
    fun phase2TruncatedDestinationIsRejectedAndRetryWorks() = runBlocking<Unit> {
        guard()
        assumeTrue(phaseArg() == "export-write")
        val lines = hostDrivenLines("export-write")
        val pid = field(lines, "pid").toInt()
        val token = field(lines, "token")
        val key = field(lines, "key")
        val destination = File(field(lines, "destination"))
        assertNotEquals("需要真正的新进程", pid, Process.myPid())
        assertTrue("被杀进程的目标前缀文件丢失", destination.isFile)
        assertTrue("杀进程必须发生在目标写入阶段", destination.length() in 1..(4L * 1024 * 1024))
        IsolatedVault.existing(base, token).use { vault ->
            val staging = File(vault.root, "verify-truncated").apply { mkdirs() }
            assertTrue(
                "被截断的目标文件不得被当作有效备份",
                runCatching { LocalBackupArchive.verify(destination, key, staging).use {} }.isFailure,
            )
            assertNull("被杀进程不得留下可继续的备份状态", FileHandoff(base).read())
            val resources = vault.runtime.resources()
            val settings = RecoverySettings(vault.context, resources.material)
            val exporter = BackupExporter(vault.context, resources.material, resources.importer, settings, clock)
            val retry = File(vault.root, "retry-after-kill.yunji").apply { delete() }
            exporter.exportTo({ retry.outputStream() })
            assertTrue("重试没有产生备份", retry.isFile)
            LocalBackupArchive.verify(retry, key, File(vault.root, "verify-retry")).use { verified ->
                assertNotNull("重试归档缺少快照", verified.snapshot)
            }
            destination.delete(); retry.delete()
        }
        marker("export-write").delete()
    }

    // ---- scenario verify: kill during archive read-back/verification ----

    @Test
    fun phase1VerifyBlocked() = runBlocking {
        guard()
        assumeTrue(phaseArg() == "verify")
        val token = "d02verify"
        IsolatedVault.fresh(base, token).use { vault ->
            val resources = vault.runtime.resources()
            val material = resources.material
            val key = RecoveryKey.create()
            val settings = RecoverySettings(vault.context, material)
            settings.save(RecoverySlots.create(material, key))
            val exporter = BackupExporter(vault.context, material, resources.importer, settings, clock)
            val archive = File(vault.root, "source.yunji").apply { delete() }
            exporter.exportTo({ archive.outputStream() })
            val staging = File(vault.root, "staging").apply { mkdirs() }
            marker("verify").writeText(
                "phase=verify pid=${Process.myPid()} token=$token key=$key\narchive=${archive.absolutePath}\narchiveDigest=${digest(archive)}",
            )
            val checkpoints = AtomicInteger()
            try {
                LocalBackupArchive.verify(archive, key, staging, checkpoint = {
                    if (checkpoints.getAndIncrement() == 4) {
                        marker("verify").appendText("\nblockedDuringVerify=true")
                        Thread.sleep(120_000)
                    }
                }).close()
                marker("verify").appendText("\nverifyCompleted=true")
            } finally {
                archive.delete()
            }
        }
    }

    @Test
    fun phase2VerifyKilledProcessKeepsArchiveAndRetryVerifies() = runBlocking<Unit> {
        guard()
        assumeTrue(phaseArg() == "verify")
        val lines = hostDrivenLines("verify")
        val pid = field(lines, "pid").toInt()
        val token = field(lines, "token")
        val key = field(lines, "key")
        val archive = File(field(lines, "archive"))
        val digestBefore = field(lines, "archiveDigest")
        assertNotEquals("需要真正的新进程", pid, Process.myPid())
        assertFalse("被杀进程不得被记为校验成功", lines.any { it.startsWith("verifyCompleted=true") })
        assertTrue("被杀进程清空了待校验归档", archive.isFile)
        assertEquals("杀进程改变了归档内容", digestBefore, digest(archive))
        IsolatedVault.existing(base, token).use { vault ->
            val retryStaging = File(vault.root, "staging-retry").apply { mkdirs() }
            LocalBackupArchive.verify(archive, key, retryStaging).use { verified ->
                assertNotNull("重试校验失败", verified.snapshot)
            }
        }
        archive.delete()
        marker("verify").delete()
    }

    // ---- scenario purge: kill after PURGING is committed, before cleanup runs ----

    @Test
    fun phase1PurgeStarted() = runBlocking {
        guard()
        assumeTrue(phaseArg() == "purge")
        val token = "d02purge"
        IsolatedVault.fresh(base, token).use { vault ->
            val resources = vault.runtime.resources()
            val material = resources.material
            val key = RecoveryKey.create()
            val settings = RecoverySettings(vault.context, material)
            settings.save(RecoverySlots.create(material, key))
            val db = resources.database
            val dao = db.journalDao()
            val pregnancy = PregnancyEntity("p", "合成清理档案", createdAt = 1, updatedAt = 1)
            val visit = VisitEntity("visit", pregnancy.id, "2026-10-19", zoneId = "UTC", createdAt = 1, updatedAt = 1, dateRevisionId = null)
            val store = EncryptedFileStore(resources.importer.objectDirectory, material)
            val stored = store.put("synthetic purge original".toByteArray().inputStream())
            val attachment = AttachmentEntity(
                stored.id, visit.id, "合成原件", "image/png",
                stored.plainSize, stored.cipherSize, stored.plainSha256, stored.cipherSha256,
                null, null, null, null, null, 1, deletedAt = 2,
            )
            val job = ImportJobEntity(stored.id, visit.id, "", "合成原件", 1, state = "DELETED", phase = "DONE")
            db.journalWrite {
                dao.putPregnancy(pregnancy)
                dao.putVisit(visit)
                dao.putAttachment(attachment)
                dao.putImportJob(job)
            }
            resources.importer.purge(stored.id, schedule = false)
            assertEquals("PURGING", dao.importJob(stored.id)?.state)
            marker("purge").writeText(
                "phase=purge pid=${Process.myPid()} token=$token key=$key attachmentId=${stored.id}\nobjectFile=${store.path(stored.id).absolutePath}",
            )
            Thread.sleep(120_000)
        }
    }

    @Test
    fun phase2PurgingIsNotCompleteAndResumeFinishesIt() = runBlocking<Unit> {
        guard()
        assumeTrue(phaseArg() == "purge")
        val lines = hostDrivenLines("purge")
        val pid = field(lines, "pid").toInt()
        val token = field(lines, "token")
        val attachmentId = field(lines, "attachmentId")
        val objectFile = File(field(lines, "objectFile"))
        assertNotEquals("需要真正的新进程", pid, Process.myPid())
        IsolatedVault.existing(base, token).use { vault ->
            val resources = vault.runtime.resources()
            val dao = resources.database.journalDao()
            assertEquals("被杀进程把 PURGING 误当成已完成", "PURGING", dao.importJob(attachmentId)?.state)
            assertNotNull("PURGING 的元数据被提前删除", dao.attachment(attachmentId))
            assertTrue("PURGING 的密文对象被提前删除", objectFile.isFile)
            val exporter = BackupExporter(vault.context, resources.material, resources.importer, RecoverySettings(vault.context, resources.material), clock)
            val refused = runCatching { exporter.exportTo({ File(vault.root, "must-not-write.yunji").outputStream() }) }
            assertTrue("PURGING 期间的备份被错误接受", refused.isFailure)
            assertEquals("资料正在清理，请稍后重试备份", refused.exceptionOrNull()?.message)
            resources.importer.process(attachmentId)
            assertEquals("清理没有完成", "PURGED", dao.importJob(attachmentId)?.state)
            assertNull("清理后仍残留附件元数据", dao.attachment(attachmentId))
            assertFalse("清理后仍残留密文对象", objectFile.exists())
            val after = File(vault.root, "after-purge.yunji").apply { delete() }
            exporter.exportTo({ after.outputStream() })
            assertTrue("清理完成后备份仍不可用", after.isFile)
            after.delete()
        }
        marker("purge").delete()
    }

    // ---- scenario orphan: unrelated process leftovers in real app directories ----

    @Test
    fun phase1OrphanArtifactsCreated() {
        guard()
        assumeTrue(phaseArg() == "orphan")
        val vaultRoot = File(base.noBackupFilesDir, "vault")
        val backupWork = File(vaultRoot, "backup-work")
        val restoreInput = File(vaultRoot, "restore-input")
        val restoreValidation = File(vaultRoot, "restore-validation")
        val exportWork = File(base.cacheDir, "export-work")
        val backupOrphan = File(backupWork, "job-${UUID.randomUUID()}").apply { mkdirs() }
        File(backupOrphan, "complete.yunji").writeText("SYNTHETIC-ORPHAN-BACKUP")
        val backupKeep = File(backupWork, "keep-unrelated").apply { mkdirs(); File(this, "note.txt").writeText("synthetic") }
        val restoreOrphan = File(restoreInput, "job-${UUID.randomUUID()}").apply { mkdirs() }
        File(restoreOrphan, "input.bin").writeText("SYNTHETIC-ORPHAN-RESTORE")
        val exportOrphan = File(exportWork, "job-${UUID.randomUUID()}").apply { mkdirs() }
        File(exportOrphan, "structured-export.zip").writeBytes("SYNTHETIC-PLAINTEXT-EXPORT".toByteArray())
        val validationOrphan = File(restoreValidation, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        val verifyOrphan = File(validationOrphan, "backup-verify-${UUID.randomUUID()}").apply { mkdirs() }
        File(verifyOrphan, "authenticated-original").writeText("SYNTHETIC-PLAINTEXT-VALIDATION")
        val exportKeep = File(exportWork, "keep-unrelated").apply { mkdirs(); File(this, "note.txt").writeText("synthetic") }
        val validationKeep = File(restoreValidation, "keep-unrelated").apply { mkdirs(); File(this, "note.txt").writeText("synthetic") }
        marker("orphan").writeText(
            "phase=orphan\npid=${Process.myPid()}\nbackupOrphan=${backupOrphan.absolutePath}\nbackupKeep=${backupKeep.absolutePath}\nrestoreOrphan=${restoreOrphan.absolutePath}\nexportOrphan=${exportOrphan.absolutePath}\nvalidationOrphan=${validationOrphan.absolutePath}\nexportKeep=${exportKeep.absolutePath}\nvalidationKeep=${validationKeep.absolutePath}\n",
        )
        Thread.sleep(120_000)
    }

    @Test
    fun phase2ColdStartCleansPrivatePlaintextWorkspaces() {
        guard()
        assumeTrue(phaseArg() == "orphan")
        val lines = hostDrivenLines("orphan")
        val pid = field(lines, "pid").toInt()
        val backupOrphan = File(field(lines, "backupOrphan"))
        val backupKeep = File(field(lines, "backupKeep"))
        val restoreOrphan = File(field(lines, "restoreOrphan"))
        val exportOrphan = File(field(lines, "exportOrphan"))
        val validationOrphan = File(field(lines, "validationOrphan"))
        val exportKeep = File(field(lines, "exportKeep"))
        val validationKeep = File(field(lines, "validationKeep"))
        assertNotEquals("需要真正的新进程", pid, Process.myPid())
        val vaultRoot = File(base.noBackupFilesDir, "vault")
        check(backupKeep.canonicalFile == File(vaultRoot, "backup-work/keep-unrelated").canonicalFile)
        check(exportKeep.canonicalFile == File(base.cacheDir, "export-work/keep-unrelated").canonicalFile)
        check(validationKeep.canonicalFile == File(vaultRoot, "restore-validation/keep-unrelated").canonicalFile)
        check(exportOrphan.parentFile.canonicalFile == File(base.cacheDir, "export-work").canonicalFile &&
            TransientWorkspaceCleanup.exportJob.matches(exportOrphan.name))
        check(validationOrphan.parentFile.canonicalFile == File(vaultRoot, "restore-validation").canonicalFile &&
            TransientWorkspaceCleanup.restoreStaging.matches(validationOrphan.name))
        try {
            // Export/validation cleanup starts at Application.onCreate; import recovery covers the older roots.
            ImportWorker.recover(base)
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline && listOf(backupOrphan, restoreOrphan, exportOrphan, validationOrphan).any(File::exists)) Thread.sleep(250)
            assertFalse("冷启动没有清理备份工作孤儿", backupOrphan.exists())
            assertFalse("冷启动没有清理恢复输入孤儿", restoreOrphan.exists())
            assertFalse("冷启动没有清理结构化导出明文孤儿", exportOrphan.exists())
            assertFalse("冷启动没有清理恢复验证明文孤儿", validationOrphan.exists())
            assertTrue("备份无关目录被误删", File(backupKeep, "note.txt").isFile)
            assertTrue("导出无关目录被误删", File(exportKeep, "note.txt").isFile)
            assertTrue("验证无关目录被误删", File(validationKeep, "note.txt").isFile)
        } finally {
            backupKeep.deleteRecursively(); exportKeep.deleteRecursively(); validationKeep.deleteRecursively()
            exportOrphan.deleteRecursively(); validationOrphan.deleteRecursively(); marker("orphan").delete()
        }
    }
}
