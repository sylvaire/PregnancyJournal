package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.network.WebDavClient
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.EncryptedFileStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Cloud snapshot download: authenticate the commit, stream every object with length/hash checks, rebuild a
 * local archive, and prove the result opens with the user's recovery key. All data is synthetic.
 */
class CloudSnapshotFetcherTest {
    @get:Rule val temporary = TemporaryFolder()
    private val day = 24L * 60 * 60 * 1000
    private val now = Instant.parse("2026-10-21T00:00:00Z").toEpochMilli()

    private inner class Fixture {
        val vault = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
        val recovery = RecoveryKey.create()
        val slot = RecoverySlots.create(vault, recovery)
        val store = EncryptedFileStore(File(temporary.newFolder(), "objects"), vault)
        val attachments = mutableListOf<cn.yunji.journal.core.database.AttachmentEntity>()
        val bodyBytes = linkedMapOf<String, ByteArray>()

        init {
            repeat(2) { index ->
                val body = ("synthetic cloud original $index").toByteArray() + ByteArray(index * 11)
                val stored = store.put(body.inputStream())
                bodyBytes[stored.id] = body
                attachments += cn.yunji.journal.core.database.AttachmentEntity(
                    stored.id, null, "合成原件${index + 1}", "image/png",
                    stored.plainSize, stored.cipherSize, stored.plainSha256, stored.cipherSha256,
                    null, null, null, null, null, 1,
                    // An attachment owned by a medication must be a package photo with an image type.
                    category = "PACKAGE", medicationId = "m1")
            }
        }

        fun snapshot(createdAt: Long = now - day, sequence: Long = 5) = LogicalSnapshot(
            vaultId = vault.id, snapshotId = UUID.randomUUID().toString(), writerId = UUID.randomUUID().toString(),
            writerSequence = sequence, capturedSequence = sequence, createdAt = createdAt,
            pregnancies = listOf(cn.yunji.journal.core.database.PregnancyEntity("p1", "合成云端档案", createdAt = 1, updatedAt = 1)),
            visits = listOf(cn.yunji.journal.core.database.VisitEntity("v1", "p1", "2026-10-01", zoneId = "UTC", createdAt = 1, updatedAt = 1)),
            attachments = attachments,
            medications = listOf(cn.yunji.journal.core.database.MedicationEntity("m1", "p1", "合成药品", "", "", 1)))

        fun close() { vault.master.fill(0) }
    }

    private inner class FakeDav : Dispatcher() {
        val files = linkedMapOf<String, ByteArray>()
        val directories = linkedSetOf("/backup")
        /** Object ids whose bytes are served with a flipped first byte. */
        val corrupted = mutableSetOf<String>()
        val requested = mutableListOf<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val target = (request.path ?: return MockResponse().setResponseCode(400)).substringBefore('?').trimEnd('/')
            requested += "${request.method} $target"
            if (!target.startsWith("/backup")) return MockResponse().setResponseCode(404)
            return when (request.method) {
                "PROPFIND" -> propfind(target, request.getHeader("Depth"))
                "GET" -> {
                    val body = files[target] ?: return MockResponse().setResponseCode(404)
                    val id = target.substringAfterLast('/').removeSuffix(".bin")
                    val served = if (id in corrupted) body.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() } else body
                    MockResponse().setBody(okio.Buffer().write(served))
                }
                else -> MockResponse().setResponseCode(405)
            }
        }

        private fun propfind(target: String, depth: String?): MockResponse {
            if (target !in directories) return MockResponse().setResponseCode(404)
            val entries = mutableListOf(target)
            if (depth == "1") {
                directories.filter { it != target && it.substringBeforeLast('/') == target }.forEach { entries += it }
                files.keys.filter { it.substringBeforeLast('/') == target }.forEach { entries += it }
            }
            val body = buildString {
                append("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">")
                entries.forEach { entry ->
                    val collection = entry in directories
                    append("<d:response><d:href>").append(if (collection) "$entry/" else entry).append("</d:href>")
                    append("<d:propstat><d:prop><d:resourcetype>")
                    if (collection) append("<d:collection/>")
                    append("</d:resourcetype>")
                    files[entry]?.let { content ->
                        append("<d:getcontentlength>").append(content.size).append("</d:getcontentlength>")
                        append("<d:getlastmodified>").append(Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)
                            .format(DateTimeFormatter.RFC_1123_DATE_TIME)).append("</d:getlastmodified>")
                    }
                    append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
                }
                append("</d:multistatus>")
            }
            return MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(body)
        }
    }

    private fun <T> withDav(block: (FakeDav, WebDavClient) -> T): T {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            val fake = FakeDav()
            server.dispatcher = fake
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            val http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()
            val endpoint = server.url("/backup/").newBuilder().host("localhost").build()
            WebDavClient(endpoint, "synthetic", "synthetic-password", http).use { return block(fake, it) }
        }
    }

    /** Publishes a real commit for one snapshot, including its manifest and object ciphertext. */
    private fun publish(fake: FakeDav, f: Fixture, snapshot: LogicalSnapshot) =
        publish(fake, f.vault, f.slot, f.store, snapshot)

    /** Publishes another vault's snapshot into the same remote container. */
    private fun publish(fake: FakeDav, vault: VaultMaterial, slot: RecoverySlot,
        store: EncryptedFileStore, snapshot: LogicalSnapshot) {
        val root = "/backup/pregnancy-journal/${vault.id}"
        fake.directories += "/backup/pregnancy-journal"
        fake.directories += root
        listOf("objects", "snapshots", "commits").forEach { fake.directories += "$root/$it" }
        fake.files["$root/vault.json"] = RecoverySlots.encode(slot)
        val manifest = BackupManifest.encode(snapshot, vault)
        fake.files["$root/snapshots/${snapshot.snapshotId}.bin"] = manifest
        fake.files["$root/commits/${snapshot.snapshotId}.json"] = CloudCommit.create(manifest, vault)
        BackupManifest.objects(snapshot).forEach { info -> fake.files["$root/objects/${info.id}.bin"] = store.path(info.id).readBytes() }
    }

    private fun fetcher(client: WebDavClient, f: Fixture) = CloudSnapshotFetcher(client, emptyList(), f.vault)

    private fun work(): File = temporary.newFolder("cloud-work")

    @Test fun downloadsVerifiedSnapshotAndRebuildsAnArchiveTheRecoveryKeyOpens() = withDav { fake, client ->
        val f = Fixture()
        try {
            val snapshot = f.snapshot()
            publish(fake, f, snapshot)
            val work = work()
            val summary = fetcher(client, f).download(f.slot, snapshot.snapshotId, f.recovery, work)

            assertEquals(snapshot.snapshotId, summary.snapshotId)
            assertEquals(snapshot.capturedSequence, summary.capturedSequence)
            assertEquals(f.attachments.size, summary.documents)
            assertTrue("the archive must exist", summary.archive.isFile)

            // The decisive check: the rebuilt archive verifies and decrypts with the user's recovery key.
            LocalBackupArchive.verify(summary.archive, f.recovery, work).use { verified ->
                assertEquals(snapshot.snapshotId, verified.snapshot.snapshotId)
                assertEquals(snapshot.attachments.map { it.id }.toSet(), verified.snapshot.attachments.map { it.id }.toSet())
                // Originals must round-trip byte for byte through download and repackaging.
                snapshot.attachments.forEach { attachment ->
                    val objectId = attachment.objectId ?: attachment.id
                    // The verifier extracts ciphertext into its private objects/ directory.
                    val store = EncryptedFileStore(File(verified.directory, "objects"), verified.material)
                    val out = File(verified.directory, "out-$objectId")
                    store.decryptTo(cn.yunji.journal.core.storage.StoredObject(objectId, attachment.plainSize,
                        attachment.cipherSize, attachment.plainSha256, attachment.cipherSha256), out)
                    assertArrayEquals(f.bodyBytes.getValue(objectId), out.readBytes())
                }
            }
        } finally { f.close() }
    }

    @Test fun corruptedRemoteObjectIsRejectedAndLeavesNoArchive() = withDav { fake, client ->
        val f = Fixture()
        try {
            val snapshot = f.snapshot()
            publish(fake, f, snapshot)
            val victim = f.attachments.first().objectId ?: f.attachments.first().id
            fake.corrupted += victim
            val work = work()
            val error = runCatching { fetcher(client, f).download(f.slot, snapshot.snapshotId, f.recovery, work) }.exceptionOrNull()
            assertNotNull("a hash mismatch must abort the download", error)
            assertTrue(requireNotNull(error).message!!.contains("校验"))
            assertFalse("no archive may be left behind",
                File(work, "cloud-${snapshot.snapshotId}.yunji").exists())
        } finally { f.close() }
    }

    @Test fun missingManifestOrObjectOrCommitIsRefusedWithoutPartialOutput() = withDav { fake, client ->
        val f = Fixture()
        try {
            val snapshot = f.snapshot()
            publish(fake, f, snapshot)
            val root = "/backup/pregnancy-journal/${f.vault.id}"
            val work = work()

            fake.files.remove("$root/snapshots/${snapshot.snapshotId}.bin")
            assertTrue(runCatching { fetcher(client, f).download(f.slot, snapshot.snapshotId, f.recovery, work) }.isFailure)

            publish(fake, f, snapshot)
            val victim = f.attachments.first().objectId ?: f.attachments.first().id
            fake.files.remove("$root/objects/$victim.bin")
            assertTrue(runCatching { fetcher(client, f).download(f.slot, snapshot.snapshotId, f.recovery, work) }.isFailure)

            publish(fake, f, snapshot)
            fake.files.remove("$root/commits/${snapshot.snapshotId}.json")
            val error = runCatching { fetcher(client, f).download(f.slot, snapshot.snapshotId, f.recovery, work) }.exceptionOrNull()
            assertTrue(requireNotNull(error).message!!.contains("提交"))
            assertTrue("staging must not accumulate", work.listFiles().orEmpty().none { it.name.endsWith(".yunji") })
        } finally { f.close() }
    }

    @Test fun wrongRecoveryKeyCannotProduceAFetchedArchive() = withDav { fake, client ->
        val f = Fixture()
        try {
            val snapshot = f.snapshot()
            publish(fake, f, snapshot)
            val work = work()
            val otherKey = RecoveryKey.create()
            val error = runCatching { fetcher(client, f).download(f.slot, snapshot.snapshotId, otherKey, work) }.exceptionOrNull()
            assertNotNull("a wrong recovery key must not yield a usable archive", error)
            assertFalse(File(work, "cloud-${snapshot.snapshotId}.yunji").exists())
        } finally { f.close() }
    }

    @Test fun listRestorableReturnsOnlyAuthenticatedSnapshotsNewestFirst() = withDav { fake, client ->
        val f = Fixture()
        try {
            val older = f.snapshot(createdAt = now - 30 * day, sequence = 2)
            val newer = f.snapshot(createdAt = now - day, sequence = 9)
            publish(fake, f, older)
            publish(fake, f, newer)
            // A commit whose manifest is gone must not be advertised as restorable.
            val broken = f.snapshot(createdAt = now - 10 * day, sequence = 5)
            publish(fake, f, broken)
            fake.files.remove("/backup/pregnancy-journal/${f.vault.id}/snapshots/${broken.snapshotId}.bin")

            val listed = fetcher(client, f).listRestorable(f.slot)
            assertEquals(listOf(newer.snapshotId, older.snapshotId), listed.map { it.snapshotId })
            assertFalse(broken.snapshotId in listed.map { it.snapshotId })
        } finally { f.close() }
    }

    @Test fun discoveryFindsOnlyTheVaultThisRecoveryKeyOpens() = withDav { fake, client ->
        val f = Fixture()
        try {
            val snapshot = f.snapshot()
            publish(fake, f, snapshot)
            // A second vault in the same remote container that this recovery key must not open.
            val otherVault = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
            try {
                val otherRecovery = RecoveryKey.create()
                val otherSlot = RecoverySlots.create(otherVault, otherRecovery)
                val otherStore = EncryptedFileStore(File(temporary.newFolder(), "other-objects"), otherVault)
                val otherSnapshot = LogicalSnapshot(vaultId = otherVault.id, snapshotId = UUID.randomUUID().toString(),
                    writerId = UUID.randomUUID().toString(), writerSequence = 1, capturedSequence = 1, createdAt = now,
                    pregnancies = listOf(cn.yunji.journal.core.database.PregnancyEntity("op", "其他档案", createdAt = 1, updatedAt = 1)))
                publish(fake, otherVault, otherSlot, otherStore, otherSnapshot)
            } finally { otherVault.master.fill(0) }

            // The local installation has a different vault id: this is the new-device case.
            val freshDevice = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
            try {
                val fetched = CloudSnapshotFetcher(client, emptyList(), freshDevice)
                val candidates = fetched.discover(f.recovery)
                assertEquals("only the vault this key opens may be offered", 1, candidates.size)
                assertEquals(f.vault.id, candidates.single().slot.vaultId)

                // And the discovered candidate can be listed and downloaded without any local vault match.
                val listed = fetched.listRestorable(candidates.single())
                assertEquals(listOf(snapshot.snapshotId), listed.map { it.snapshotId })
                val work = work()
                val summary = fetched.download(candidates.single(), snapshot.snapshotId, f.recovery, work)
                LocalBackupArchive.verify(summary.archive, f.recovery, work).use { verified ->
                    assertEquals(snapshot.snapshotId, verified.snapshot.snapshotId)
                }
            } finally { freshDevice.master.fill(0) }
        } finally { f.close() }
    }

    @Test fun discoveryWithAWrongKeyFindsNothingAndEmptyDirectoryIsNotAnError() = withDav { fake, client ->
        val f = Fixture()
        try {
            publish(fake, f, f.snapshot())
            val freshDevice = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
            try {
                val fetched = CloudSnapshotFetcher(client, emptyList(), freshDevice)
                assertTrue("a wrong recovery key must not open any vault", fetched.discover(RecoveryKey.create()).isEmpty())
                assertTrue("a malformed key is rejected", runCatching { fetched.discover("not-a-key") }.isFailure)
            } finally { freshDevice.master.fill(0) }
        } finally { f.close() }

        // With no directory at all the result is an empty list, not an exception.
        withDav { _, emptyClient ->
            val freshDevice = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
            try {
                assertTrue(CloudSnapshotFetcher(emptyClient, emptyList(), freshDevice).discover(RecoveryKey.create()).isEmpty())
            } finally { freshDevice.master.fill(0) }
        }
    }

    @Test fun downloadRefusesForeignVaultOrInvalidIdentifiers() = withDav { fake, client ->
        val f = Fixture()
        try {
            val snapshot = f.snapshot()
            publish(fake, f, snapshot)
            val work = work()
            assertTrue(runCatching { fetcher(client, f).download(f.slot, "../../etc/passwd", f.recovery, work) }.isFailure)
            assertTrue(runCatching { fetcher(client, f).download(f.slot, UUID.randomUUID().toString(), f.recovery, work) }.isFailure)

            // A different vault must not accept this directory's identity.
            val foreign = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
            try {
                val foreignSlot = RecoverySlots.create(foreign, RecoveryKey.create())
                val foreignFetcher = CloudSnapshotFetcher(client, emptyList(), foreign)
                assertTrue(runCatching { foreignFetcher.download(foreignSlot, snapshot.snapshotId, f.recovery, work) }.isFailure)
            } finally { foreign.master.fill(0) }
        } finally { f.close() }
    }
}
