package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.network.DavBackupRemote
import cn.yunji.journal.core.network.WebDavClient
import cn.yunji.journal.core.security.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Combination test: real TLS WebDAV adapter + upload engine + attachments.
 * The fake server implements only the protocol subset the adapter is allowed to rely on.
 */
class CloudBackupUploadDavTest {
    private class FakeDav(private val prefix: String = "/backup/") : Dispatcher() {
        val files = linkedMapOf<String, ByteArray>()
        val directories = linkedSetOf<String>().apply { add(prefix.trimEnd('/')) }
        val methods = mutableListOf<String>()
        val paths = mutableListOf<String>()
        var dropCommitReadback = false

        private fun path(target: String) = target.trimEnd('/')
        private fun href(target: String, collection: Boolean) = if (collection) "$target/" else target
        private fun normalize(url: String) = path(url.substringBefore('?'))

        override fun dispatch(request: RecordedRequest): MockResponse {
            val target = normalize(request.path ?: return MockResponse().setResponseCode(400))
            methods += request.method ?: ""
            paths += target
            if (!target.startsWith(path(prefix))) return MockResponse().setResponseCode(404)
            return when (request.method) {
                "PROPFIND" -> propfind(target, request.getHeader("Depth"))
                "MKCOL" -> if (target in directories) MockResponse().setResponseCode(405)
                    else { directories += target; MockResponse().setResponseCode(201) }
                "GET" -> {
                    val body = files[target]
                    if (body == null) MockResponse().setResponseCode(404)
                    else if (target.endsWith(".json") && dropCommitReadback) MockResponse().setResponseCode(404)
                    else MockResponse().setBody(okio.Buffer().write(body))
                }
                "HEAD" -> files[target]?.let { MockResponse().setResponseCode(200).setHeader("Content-Length", it.size) }
                    ?: MockResponse().setResponseCode(404)
                "PUT" -> {
                    val body = request.body.readByteArray()
                    if (request.getHeader("If-None-Match") == "*" && (target in files || target in directories)) {
                        MockResponse().setResponseCode(412)
                    } else {
                        files[target] = body; MockResponse().setResponseCode(201)
                    }
                }
                "DELETE" -> if (files.remove(target) != null) MockResponse().setResponseCode(204) else MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(405)
            }
        }

        private fun propfind(target: String, depth: String?): MockResponse {
            if (target !in directories) return MockResponse().setResponseCode(404)
            val entries = mutableListOf(target)
            if (depth == "1") directories.filter { it != target && it.substringBeforeLast('/') == target }.forEach { entries += it }
            if (depth == "1") files.keys.filter { it.substringBeforeLast('/') == target }.forEach { entries += it }
            val body = buildString {
                append("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">")
                entries.forEach { entry ->
                    val collection = entry in directories
                    append("<d:response><d:href>").append(href(entry, collection)).append("</d:href><d:propstat><d:prop><d:resourcetype>")
                    if (collection) append("<d:collection/>")
                    append("</d:resourcetype>")
                    files[entry]?.let { append("<d:getcontentlength>").append(it.size).append("</d:getcontentlength>") }
                    append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
                }
                append("</d:multistatus>")
            }
            return MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml; charset=utf-8").setBody(body)
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
            WebDavClient(endpoint, "synthetic", "synthetic-password", http).use { client -> return block(fake, client) }
        }
    }

    private class Fixture {
        val directory: File = Files.createTempDirectory("dav-upload").toFile()
        val vault = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
        val recovery = RecoveryKey.create()
        val slot = RecoverySlots.create(vault, recovery)
        val pregnancy = cn.yunji.journal.core.database.PregnancyEntity("pregnancy", "合成档案", createdAt = 1, updatedAt = 1)
        val visit = cn.yunji.journal.core.database.VisitEntity("visit", pregnancy.id, "2026-10-20", zoneId = "UTC", createdAt = 1, updatedAt = 1)
        val objects = linkedMapOf<String, File>()
        val attachments = mutableListOf<cn.yunji.journal.core.database.AttachmentEntity>()
        val snapshot: LogicalSnapshot
        val manifest = File(directory, "manifest.enc")

        init {
            val store = cn.yunji.journal.core.storage.EncryptedFileStore(File(directory, "source-objects"), vault)
            repeat(2) { index ->
                val body = ("synthetic ciphertext $index, not personal data").toByteArray() + ByteArray(index)
                val stored = store.put(body.inputStream())
                val copy = File(directory, "${stored.id}.bin")
                File(stored.id.let { store.path(it).path }).copyTo(copy, overwrite = true)
                objects[stored.id] = copy
                attachments += cn.yunji.journal.core.database.AttachmentEntity(
                    id = stored.id, visitId = visit.id, name = "合成原件${index + 1}", mime = "image/png",
                    plainSize = stored.plainSize, cipherSize = stored.cipherSize,
                    plainSha256 = stored.plainSha256, cipherSha256 = stored.cipherSha256,
                    thumbnailId = null, thumbnailSize = null, thumbnailCipherSize = null, thumbnailSha256 = null,
                    thumbnailCipherSha256 = null, createdAt = 1,
                )
            }
            snapshot = LogicalSnapshot(vaultId = vault.id, snapshotId = UUID.randomUUID().toString(),
                writerId = UUID.randomUUID().toString(), writerSequence = 3, capturedSequence = 21, createdAt = 1_700_000_000_000,
                pregnancies = listOf(pregnancy), visits = listOf(visit), attachments = attachments)
            manifest.writeBytes(BackupManifest.encode(snapshot, vault))
        }

        fun close() { vault.master.fill(0); directory.deleteRecursively() }
    }

    /** Writes a second manifest for a different snapshot of the same synthetic vault. */
    private fun manifestFor(f: Fixture, snapshot: LogicalSnapshot): File =
        File(f.directory, "manifest-${snapshot.snapshotId}.enc").apply {
            writeBytes(BackupManifest.encode(snapshot, f.vault))
        }

    private fun receipt(fake: FakeDav, vaultId: String): ByteArray {
        val path = "/backup/pregnancy-journal/$vaultId/commits"
        val commit = fake.files.keys.filter { it.startsWith("$path/") }
        assertEquals(1, commit.size)
        return fake.files.getValue(commit.single())
    }

    @Test fun uploadsAttachmentsAndCommitOverTlsAndReusesVerifiedObjects() = withDav { fake, client ->
        val f = Fixture()
        try {
            val remote = DavBackupRemote(client, emptyList())
            val engine = CloudBackupUpload(remote)
            val phases = mutableListOf<String>()
            val first = engine.upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) }, phase = phases::add)
            assertEquals(f.snapshot.snapshotId, first.snapshotId)
            assertEquals(21, first.capturedSequence)
            assertEquals(listOf("CHECKING_DESTINATION", "UPLOADING_OBJECTS", "VERIFYING", "COMMITTING"), phases)

            val base = "/backup/pregnancy-journal/${f.vault.id}"
            assertTrue("directories=${fake.directories}", base in fake.directories)
            listOf("objects", "snapshots", "commits").forEach { assertTrue("$base/$it" in fake.directories) }
            f.objects.forEach { (id, file) ->
                val path = "$base/objects/$id.bin"
                assertTrue("missing $path; have ${fake.files.keys}; methods=${fake.methods}; dirs=${fake.directories}", fake.files.containsKey(path))
                assertArrayEquals(file.readBytes(), fake.files.getValue(path))
            }
            assertArrayEquals(f.manifest.readBytes(), fake.files.getValue("$base/snapshots/${f.snapshot.snapshotId}.bin"))
            assertEquals(RecoverySlots.encode(f.slot).toList(), fake.files.getValue("$base/vault.json").toList())
            CloudCommit.verify(receipt(fake, f.vault.id), f.snapshot.snapshotId, f.manifest.readBytes(), f.vault)

            val uploadsBefore = fake.methods.count { it == "PUT" }
            val second = engine.upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            assertEquals(first, second)
            assertEquals("Retry must not rewrite immutable objects or the commit", uploadsBefore, fake.methods.count { it == "PUT" })
        } finally { f.close() }
    }

    @Test fun foreignIdentityAndCorruptedExistingObjectAreNeverOverwritten() = withDav { fake, client ->
        val f = Fixture()
        try {
            val base = "/backup/pregnancy-journal/${f.vault.id}"
            val foreign = RecoverySlots.create(VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey()), RecoveryKey.create())
            fake.directories += "/backup/pregnancy-journal"
            fake.directories += base
            fake.files["$base/vault.json"] = RecoverySlots.encode(foreign)
            val remote = DavBackupRemote(client, emptyList())
            assertTrue(runCatching { CloudBackupUpload(remote).upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) }) }.isFailure)
            assertEquals(RecoverySlots.encode(foreign).toList(), fake.files.getValue("$base/vault.json").toList())
            assertTrue(fake.files.keys.none { it.contains("/objects/") })
        } finally { f.close() }
    }

    @Test fun corruptedBytesAlreadyInCloudBlockReuseInsteadOfBeingSilentlyReplaced() = withDav { fake, client ->
        val f = Fixture()
        try {
            val remote = DavBackupRemote(client, emptyList())
            val engine = CloudBackupUpload(remote)
            val (id, file) = f.objects.entries.first()
            val base = "/backup/pregnancy-journal/${f.vault.id}"
            remote.prepare(f.slot)
            fake.files["$base/objects/$id.bin"] = ByteArray(file.length().toInt()) { 9 }
            val damaged = fake.files.getValue("$base/objects/$id.bin")
            assertTrue(runCatching { engine.upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) }) }.isFailure)
            assertArrayEquals(damaged, fake.files.getValue("$base/objects/$id.bin"))
            assertTrue(fake.files.keys.none { it.startsWith("$base/commits/") })
        } finally { f.close() }
    }

    @Test fun missingSourceFailsBeforeAnyRemoteRequest() = withDav { fake, client ->
        val f = Fixture()
        try {
            val engine = CloudBackupUpload(DavBackupRemote(client, emptyList()))
            f.objects.values.first().delete()
            assertTrue(runCatching { engine.upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) }) }.isFailure)
            assertTrue("No remote write may precede local source validation", fake.methods.isEmpty())
        } finally { f.close() }
    }

    @Test fun foreignVaultSlotAndUnreadableCommitNeverReturnSuccess() = withDav { fake, client ->
        val f = Fixture()
        try {
            // A slot must belong to the same vault; the engine rejects before touching the network.
            val foreign = RecoverySlots.create(VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey()), RecoveryKey.create())
            assertTrue(runCatching { CloudBackupUpload(DavBackupRemote(client, emptyList()))
                .upload(f.manifest, f.vault, foreign, { f.objects.getValue(it) }) }.isFailure)
            assertTrue("Rejected identity must not write remotely", fake.methods.isEmpty())

            fake.dropCommitReadback = true
            val error = runCatching { CloudBackupUpload(DavBackupRemote(client, emptyList()))
                .upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) }) }.exceptionOrNull()
            assertNotNull("A missing commit readback must not be reported as success", error)
        } finally { f.close() }
    }

    @Test fun ownBackupHistoryAndForeignWriterArbitrationAreResolvedOverTls() = withDav { fake, client ->
        val f = Fixture()
        try {
            val remote = DavBackupRemote(client, emptyList())
            val engine = CloudBackupUpload(remote)
            val ownWriter = f.snapshot.writerId
            // Three consecutive automatic backups from this installation: same writer, new snapshots.
            val ownSnapshots = listOf(
                f.snapshot,
                f.snapshot.copy(snapshotId = UUID.randomUUID().toString(), writerSequence = 4,
                    capturedSequence = 22, createdAt = 1_700_000_060_000),
                f.snapshot.copy(snapshotId = UUID.randomUUID().toString(), writerSequence = 5,
                    capturedSequence = 23, createdAt = 1_700_000_120_000),
            )
            ownSnapshots.forEach { snapshot ->
                val manifest = if (snapshot === f.snapshot) f.manifest else manifestFor(f, snapshot)
                engine.upload(manifest, f.vault, f.slot, { f.objects.getValue(it) })
            }
            val ownHistory = remote.verifiedCommitWriterIds(f.vault)
            assertEquals("Every published commit must be listed", 3, ownHistory.size)
            assertTrue("Each commit must authenticate to this installation",
                ownHistory.values.all { it == ownWriter })
            assertTrue("Repeated own backups must never look like another writer",
                CloudBackupPolicy.unconfirmedForeignCommits(ownWriter, ownHistory, emptySet()).isEmpty())
            assertFalse(CloudBackupPolicy.foreignWriterDetected(ownWriter, ownHistory.values))

            // A second device in the same directory publishes a valid commit for the same vault.
            val foreignWriter = UUID.randomUUID().toString()
            val foreignSnapshot = f.snapshot.copy(snapshotId = UUID.randomUUID().toString(),
                writerId = foreignWriter, writerSequence = 1, capturedSequence = 30, createdAt = 1_700_000_180_000)
            engine.upload(manifestFor(f, foreignSnapshot), f.vault, f.slot, { f.objects.getValue(it) })
            val withForeign = remote.verifiedCommitWriterIds(f.vault)
            assertEquals(4, withForeign.size)
            val foreignCommits = withForeign.filterValues { it == foreignWriter }.keys
            assertEquals(setOf(foreignSnapshot.snapshotId), foreignCommits)
            assertEquals("A new foreign commit must stop automatic backup", foreignCommits,
                CloudBackupPolicy.unconfirmedForeignCommits(ownWriter, withForeign, emptySet()))

            // Confirming the primary device records the already observed foreign commits as baseline.
            val acknowledged = foreignCommits
            assertTrue("Acknowledged history must not keep blocking",
                CloudBackupPolicy.unconfirmedForeignCommits(ownWriter, withForeign, acknowledged).isEmpty())

            // A later commit from the acknowledged branch is still a new foreign commit.
            val newer = foreignSnapshot.copy(snapshotId = UUID.randomUUID().toString(),
                writerSequence = 2, capturedSequence = 31, createdAt = 1_700_000_240_000)
            engine.upload(manifestFor(f, newer), f.vault, f.slot, { f.objects.getValue(it) })
            val withNewer = remote.verifiedCommitWriterIds(f.vault)
            assertEquals(5, withNewer.size)
            assertEquals("Only the unseen commit may block again", setOf(newer.snapshotId),
                CloudBackupPolicy.unconfirmedForeignCommits(ownWriter, withNewer, acknowledged))
        } finally { f.close() }
    }

    @Test fun untrustedCertificateIsRejectedWithoutTrustAllFallback() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        MockWebServer().use { server ->
            server.dispatcher = FakeDav()
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            val endpoint = server.url("/backup/").newBuilder().host("localhost").build()
            WebDavClient(endpoint, "synthetic", "synthetic-password", OkHttpClient()).use { client ->
                val error = runCatching { client.probe() }.exceptionOrNull()
                assertNotNull("Certificate must not be ignored", error)
                assertTrue(error is javax.net.ssl.SSLException || error is java.io.IOException)
            }
        }
    }
}
