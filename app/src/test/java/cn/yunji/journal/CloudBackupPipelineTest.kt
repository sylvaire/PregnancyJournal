package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.EncryptedFileStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Ordered cloud pipeline: durable sequence reservation, leased snapshot, local self-check, then upload.
 * The uploader is faked here so ordering rules can be asserted directly.
 */
class CloudBackupPipelineTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Fixture {
        val root = temporary.newFolder()
        val vault = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
        val recovery = RecoveryKey.create()
        val slot = RecoverySlots.create(vault, recovery)
        val store = EncryptedFileStore(File(root, "objects"), vault)
        val bodies = mutableListOf<ByteArray>()
        val attachments = mutableListOf<AttachmentEntity>()
        val writerDir = File(root, "writer").apply { mkdirs() }
        val workRoot = File(root, "work").apply { mkdirs() }
        var failBuild: Throwable? = null

        init {
            repeat(2) { index ->
                val body = ("synthetic cloud payload $index").toByteArray() + ByteArray(index * 7)
                bodies += body
                val stored = store.put(body.inputStream())
                attachments += AttachmentEntity(stored.id, "visit", "合成原件${index + 1}", "image/png",
                    stored.plainSize, stored.cipherSize, stored.plainSha256, stored.cipherSha256,
                    null, null, null, null, null, 1)
            }
        }

        val pregnancy = PregnancyEntity("pregnancy", "合成云端档案", createdAt = 1, updatedAt = 1)
        val visit = VisitEntity("visit", pregnancy.id, "2026-10-21", zoneId = "UTC", createdAt = 1, updatedAt = 1)

        fun snapshot(writerId: String, sequence: Long, createdAt: Long = 1000) = LogicalSnapshot(
            vaultId = vault.id, snapshotId = UUID.randomUUID().toString(), writerId = writerId,
            writerSequence = sequence, capturedSequence = 42, createdAt = createdAt,
            pregnancies = listOf(pregnancy), visits = listOf(visit), attachments = attachments)

        fun pipeline(clock: () -> Long = { 1000 }) = CloudBackupPipeline(writerDir, workRoot, vault, store,
            object : SnapshotSource {
                override suspend fun <T> withSnapshot(writerId: String, writerSequence: Long, createdAt: Long,
                    block: suspend (LogicalSnapshot) -> T): T {
                    failBuild?.let { throw it }
                    return block(snapshot(writerId, writerSequence, createdAt))
                }
            }, { }, clock)

        fun close() { vault.master.fill(0); root.deleteRecursively() }
    }

    private class Uploader(
        val publish: Boolean = true,
        val corruptReadback: Boolean = false,
        val failOnObject: Boolean = false,
    ) {
        val phases = mutableListOf<String>()
        val uploaded = mutableListOf<String>()
        var manifestBytes: ByteArray? = null
        var stagedBytes: MutableList<ByteArray> = mutableListOf()
        var commit: ByteArray? = null

        fun invoke(job: File, manifest: File, snapshot: LogicalSnapshot, staged: (String) -> File, phase: (String) -> Unit): CloudBackupReceipt {
            val objects = BackupManifest.objects(snapshot)
            objects.forEach { info ->
                val file = staged(info.id)
                assertTrue("Staged object must exist for ${info.id}", file.isFile)
                stagedBytes += file.readBytes()
                uploaded += info.id
            }
            if (failOnObject) throw java.io.IOException("Injected upload failure before commit")
            if (!publish) throw java.io.IOException("Injected failure before commit")
            val bytes = manifest.readBytes()
            manifestBytes = bytes
            val commitBytes = CloudCommit.create(bytes, requireNotNull(vaultRef))
            commit = commitBytes
            val readback = if (corruptReadback) commitBytes.copyOf(commitBytes.size - 1) else commitBytes
            val verified = CloudCommit.verify(readback, snapshot.snapshotId, bytes, requireNotNull(vaultRef))
            return CloudBackupReceipt(verified.snapshotId, verified.writerId, verified.writerSequence, verified.capturedSequence)
        }

        var vaultRef: VaultMaterial? = null
    }

    @Test fun happyPathStagesCiphertextAndCommitsAfterSelfCheck() {
        val f = Fixture()
        try {
            val uploader = Uploader().also { it.vaultRef = f.vault }
            val phases = mutableListOf<String>()
            val result = f.pipeline().run(f.slot, onPhase = phases::add, uploader = uploader::invoke)
            assertEquals(42, result.receipt.capturedSequence)
            assertEquals(1, result.writerSequence)
            assertEquals(f.attachments.map { it.id }.toSet(), uploader.uploaded.toSet())
            // Staged bytes are the exact stored ciphertext, never plaintext.
            f.attachments.indices.forEach { index ->
                val info = BackupManifest.objects(f.snapshot("w", 1)).first { it.id == f.attachments[index].id }
                assertArrayEquals(f.store.path(info.id).readBytes(), uploader.stagedBytes[index])
                assertEquals(info.cipherSize, uploader.stagedBytes[index].size.toLong())
            }
            assertTrue(phases.containsAll(listOf("SEQUENCE_RESERVED", "ARCHIVE_BUILDING", "SELF_CHECKED")))
            assertTrue("Job directory must be cleaned", f.workRoot.listFiles().orEmpty().isEmpty())
            // Read the manifest while the uploader ran; the job directory is deleted afterwards by design.
            val manifestBytes = requireNotNull(uploader.manifestBytes)
            assertEquals(BackupManifest.decode(manifestBytes, f.vault).snapshotId, result.receipt.snapshotId)
        } finally { f.close() }
    }

    @Test fun sequencesAdvanceAcrossRunsAndAreNeverReusedAfterFailure() {
        val f = Fixture()
        try {
            val uploader = Uploader().also { it.vaultRef = f.vault }
            assertEquals(1, f.pipeline().run(f.slot, uploader = uploader::invoke).writerSequence)
            assertEquals(2, f.pipeline().run(f.slot, uploader = uploader::invoke).writerSequence)
            assertTrue(runCatching { f.pipeline().run(f.slot, uploader = uploader.let { u -> { j, m, s, st, p -> Uploader(false).also { it.vaultRef = f.vault }.invoke(j, m, s, st, p) } }) }.isFailure)
            assertEquals("A failed attempt consumes its sequence", 4, f.pipeline().run(f.slot, uploader = uploader::invoke).writerSequence)
        } finally { f.close() }
    }

    @Test fun localSelfCheckAndSnapshotFailuresNeverReachTheUploader() {
        val f = Fixture()
        try {
            var called = false
            f.failBuild = IllegalStateException("Injected snapshot failure")
            val failure = runCatching { f.pipeline().run(f.slot) { _, _, _, _, _ -> called = true; error("unreachable") } }
            assertTrue(failure.isFailure); assertFalse(called)
            f.failBuild = null

            // Wrong recovery slot must be rejected before building anything.
            val foreign = RecoverySlots.create(VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey()), RecoveryKey.create())
            assertTrue(runCatching { f.pipeline().run(foreign) { _, _, _, _, _ -> called = true; error("unreachable") } }.isFailure)
            assertFalse(called)

            // A mismatched writer sequence inside the snapshot must abort before upload.
            val mismatched = CloudBackupPipeline(f.writerDir, f.workRoot, f.vault, f.store,
                object : SnapshotSource {
                    override suspend fun <T> withSnapshot(writerId: String, writerSequence: Long, createdAt: Long,
                        block: suspend (LogicalSnapshot) -> T): T = block(f.snapshot(writerId, writerSequence + 99))
                }, { }, { 1000 })
            assertTrue(runCatching { mismatched.run(f.slot) { _, _, _, _, _ -> called = true; error("unreachable") } }.isFailure)
            assertFalse(called)
        } finally { f.close() }
    }

    @Test fun corruptStoredObjectIsDetectedLocallyWithoutUpload() {
        val f = Fixture()
        try {
            var called = false
            val first = requireNotNull(f.attachments.firstOrNull())
            f.store.path(first.id).writeBytes(ByteArray(first.cipherSize.toInt()) { 3 })
            val failure = runCatching { f.pipeline().run(f.slot) { _, _, _, _, _ -> called = true; error("unreachable") } }
            assertTrue(failure.isFailure)
            assertFalse("Corruption must be caught before any upload", called)
        } finally { f.close() }
    }

    @Test fun unreadableCommitCannotProduceAReceipt() {
        val f = Fixture()
        try {
            val uploader = Uploader(corruptReadback = true).also { it.vaultRef = f.vault }
            assertTrue(runCatching { f.pipeline().run(f.slot, uploader = uploader::invoke) }.isFailure)
        } finally { f.close() }
    }

    @Test fun manifestIsBoundToVaultAndSlotIdentity() {
        val f = Fixture()
        try {
            val uploader = Uploader().also { it.vaultRef = f.vault }
            f.pipeline().run(f.slot, uploader = uploader::invoke)
            val manifest = requireNotNull(uploader.manifestBytes)
            // A different vault must not decode this manifest.
            val other = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
            try {
                assertTrue(runCatching { BackupManifest.decode(manifest, other) }.isFailure)
            } finally { other.master.fill(0) }
            assertFalse(manifest.toString(Charsets.ISO_8859_1).contains("合成云端档案"))
        } finally { f.close() }
    }
    @Test fun cancellationDuringArchiveBuildNeverUploadsAndCleansWorkspace() {
        val f=Fixture()
        try {
            var cancelled=false;var uploaded=false
            val error=runCatching {f.pipeline().run(f.slot,
                onPhase={if(it=="ARCHIVE_BUILDING")cancelled=true},
                checkpoint={if(cancelled)throw kotlinx.coroutines.CancellationException("Synthetic restore stop")},
                uploader={_,_,_,_,_->uploaded=true;error("Cancelled build reached network")})}.exceptionOrNull()
            assertTrue(error is kotlinx.coroutines.CancellationException)
            assertFalse(uploaded);assertTrue(f.workRoot.listFiles().orEmpty().isEmpty())
            assertTrue(f.attachments.all {f.store.path(it.id).isFile})
        }finally {f.close()}
    }}
