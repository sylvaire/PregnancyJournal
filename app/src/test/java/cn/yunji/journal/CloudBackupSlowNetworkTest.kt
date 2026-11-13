package cn.yunji.journal

import cn.yunji.journal.core.backup.BackupManifest
import cn.yunji.journal.core.backup.CloudBackupUpload
import cn.yunji.journal.core.backup.CloudCommit
import cn.yunji.journal.core.backup.CloudSnapshotFetcher
import cn.yunji.journal.core.backup.CloudWriterStore
import cn.yunji.journal.core.backup.LogicalSnapshot
import cn.yunji.journal.core.backup.RecoverySlots
import cn.yunji.journal.core.network.DavBackupRemote
import cn.yunji.journal.core.network.DavException
import cn.yunji.journal.core.network.WebDavClient
import cn.yunji.journal.core.security.RecoveryKey
import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.core.security.VaultMaterial
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Network-robustness tests for the cloud upload/restore transport (D03/D05 leftover dimension).
 *
 * These run entirely on the JVM against an in-process TLS WebDAV subset. The slow path is modelled with
 * the production [cn.yunji.journal.core.network.WebDavClient] checkpoints (the same hook a real user cancel
 * and the WorkManager timeout eventually use), so no real provider or device is required:
 *
 *  1. slow response / chunk pacing: upload and restore progress is checkpointed, and cancelling leaves no
 *     discoverable commit, no half-written archive and no leftover staging objects;
 *  2. large streamed transfers: download is chunked (checkpoint per 64 KiB) and the heap stays bounded;
 *  3. local source edited or deleted after preflight: the engine refuses to publish anything inconsistent;
 *  4. cancel windows: before COMMIT (objects/snapshot verified, no commit) and after COMMIT (retry must
 *     reuse the already published commit instead of writing a second one);
 *  5. process termination: writer identity/sequence are rebuilt from durable state, no reuse, no deadlock;
 *  6. remote status combinations: 5xx at prepare/PUT, 409 on commit, identical 412 on commit, interrupted
 *     verify download, and the retry boundary of each.
 */
class CloudBackupSlowNetworkTest {
    private class SlowDav(private val prefix: String = "/backup/") : Dispatcher() {
        val files = ConcurrentHashMap<String, ByteArray>()
        val directories = ConcurrentHashMap.newKeySet<String>().apply { add(prefix.trimEnd('/')) }
        val methods: MutableList<String> = CopyOnWriteArrayList()
        val putPaths: MutableList<String> = CopyOnWriteArrayList()
        val putCount = AtomicInteger()

        /** Per-request fault injection, evaluated before the default WebDAV subset. */
        @Volatile var interceptor: ((RecordedRequest, String) -> MockResponse?)? = null

        private fun path(target: String) = target.trimEnd('/')
        private fun href(target: String, collection: Boolean) = if (collection) "$target/" else target
        private fun normalize(url: String) = path(url.substringBefore('?'))

        override fun dispatch(request: RecordedRequest): MockResponse {
            val target = normalize(request.path ?: return MockResponse().setResponseCode(400))
            methods += request.method ?: ""
            if (!target.startsWith(path(prefix))) return MockResponse().setResponseCode(404)
            interceptor?.invoke(request, target)?.let { return it }
            return when (request.method) {
                "PROPFIND" -> propfind(target, request.getHeader("Depth"))
                "MKCOL" -> if (target in directories) MockResponse().setResponseCode(405)
                    else { directories += target; MockResponse().setResponseCode(201) }
                "GET" -> files[target]?.let { MockResponse().setBody(Buffer().write(it)) }
                    ?: MockResponse().setResponseCode(404)
                "HEAD" -> files[target]?.let {
                    MockResponse().setResponseCode(200).setHeader("Content-Length", it.size)
                } ?: MockResponse().setResponseCode(404)
                "PUT" -> {
                    // A real WebDAV server never exposes a body it received only partially: a client
                    // cancel mid-stream leaves no object behind. Enforce the same rule here.
                    val declared = request.getHeader("Content-Length")?.toLongOrNull()
                    val body = request.body.readByteArray()
                    if (declared != null && declared != body.size.toLong()) {
                        MockResponse().setResponseCode(400)
                    } else {
                        putCount.incrementAndGet()
                        putPaths += target
                        if (request.getHeader("If-None-Match") == "*" &&
                            (files.containsKey(target) || target in directories)) {
                            MockResponse().setResponseCode(412)
                        } else {
                            files[target] = body
                            MockResponse().setResponseCode(201)
                        }
                    }
                }
                "DELETE" -> if (files.remove(target) != null) MockResponse().setResponseCode(204)
                    else MockResponse().setResponseCode(404)
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
            return MockResponse().setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8").setBody(body)
        }
    }

    private fun withDav(config: (SlowDav) -> Unit = {}, block: (SlowDav, WebDavClient) -> Unit) {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            val fake = SlowDav()
            server.dispatcher = fake
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            config(fake)
            val http = OkHttpClient.Builder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()
            val endpoint = server.url("/backup/").newBuilder().host("localhost").build()
            WebDavClient(endpoint, "synthetic", "synthetic-password", http).use { client -> block(fake, client) }
        }
    }

    /**
     * Synthetic vault with the same shape as the existing TLS upload fixture. Object sizes are configurable
     * so a test can drive multi-chunk streaming without touching real patient data.
     */
    private inner class Fixture(private val objectSizes: List<Int> = listOf(32, 33)) {
        val directory: File = Files.createTempDirectory("dav-slow").toFile()
        val vault = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
        val recovery = RecoveryKey.create()
        val slot = RecoverySlots.create(vault, recovery)
        val pregnancy = cn.yunji.journal.core.database.PregnancyEntity(
            "pregnancy", "合成档案", createdAt = 1, updatedAt = 1)
        val visit = cn.yunji.journal.core.database.VisitEntity(
            "visit", pregnancy.id, "2026-10-20", zoneId = "UTC", createdAt = 1, updatedAt = 1)
        val objects = linkedMapOf<String, File>()
        val attachments = mutableListOf<cn.yunji.journal.core.database.AttachmentEntity>()
        val snapshot: LogicalSnapshot
        val manifest = File(directory, "manifest.enc")

        init {
            val store = cn.yunji.journal.core.storage.EncryptedFileStore(File(directory, "source-objects"), vault)
            objectSizes.forEachIndexed { index, size ->
                val stored = store.put(patternStream(size))
                val copy = File(directory, "${stored.id}.bin")
                store.path(stored.id).copyTo(copy, overwrite = true)
                objects[stored.id] = copy
                attachments += cn.yunji.journal.core.database.AttachmentEntity(
                    id = stored.id, visitId = visit.id, name = "合成原件${index + 1}", mime = "image/png",
                    plainSize = stored.plainSize, cipherSize = stored.cipherSize,
                    plainSha256 = stored.plainSha256, cipherSha256 = stored.cipherSha256,
                    thumbnailId = null, thumbnailSize = null, thumbnailCipherSize = null,
                    thumbnailSha256 = null, thumbnailCipherSha256 = null, createdAt = 1,
                )
            }
            snapshot = LogicalSnapshot(
                vaultId = vault.id, snapshotId = UUID.randomUUID().toString(),
                writerId = UUID.randomUUID().toString(), writerSequence = 3, capturedSequence = 21,
                createdAt = 1_700_000_000_000, pregnancies = listOf(pregnancy), visits = listOf(visit),
                attachments = attachments)
            manifest.writeBytes(BackupManifest.encode(snapshot, vault))
        }

        fun close() {
            vault.master.fill(0)
            directory.deleteRecursively()
        }
    }

    private fun patternStream(size: Int): InputStream = object : InputStream() {
        private var remaining = size
        override fun read(): Int = if (remaining <= 0) -1 else { remaining--; (remaining % 251) }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val count = minOf(len, remaining)
            for (i in 0 until count) b[off + i] = (i % 251).toByte()
            remaining -= count
            return count
        }
    }

    private fun manifestFor(f: Fixture, snapshot: LogicalSnapshot): File =
        File(f.directory, "manifest-${snapshot.snapshotId}.enc").apply {
            writeBytes(BackupManifest.encode(snapshot, f.vault))
        }

    private fun base(f: Fixture) = "/backup/pregnancy-journal/${f.vault.id}"

    private fun objectPaths(fake: SlowDav, f: Fixture) =
        fake.files.keys.filter { it.startsWith("${base(f)}/objects/") }

    private fun commitPaths(fake: SlowDav, f: Fixture) =
        fake.files.keys.filter { it.startsWith("${base(f)}/commits/") }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // 1. Slow network + cancel during the streaming PUT of the first large object.
    @Test(timeout = 120_000) fun slowUploadCancelMidStreamPublishesNothingAndRetrySucceeds() = withDav { fake, client ->
        val f = Fixture(listOf(3 * 1024 * 1024))
        try {
            val calls = AtomicInteger()
            val error = runCatching {
                CloudBackupUpload(DavBackupRemote(client, emptyList(), checkpoint = {
                    // 1 ms per 64 KiB chunk models a throttled link while still finishing quickly.
                    Thread.sleep(1)
                    if (calls.incrementAndGet() >= 20) throw CancellationException("synthetic slow-network cancel")
                })).upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            }.exceptionOrNull()
            assertTrue("a cancel must surface, got: $error", error is CancellationException)
            assertTrue("the cancel must fire after progress started, calls=${calls.get()}", calls.get() >= 20)
            // Let the server side of the aborted PUT settle before asserting on the remote state.
            Thread.sleep(200)
            assertEquals("only the identity file may have been written before cancel",
                1, fake.putCount.get())
            assertTrue("no object body may be left behind", objectPaths(fake, f).isEmpty())
            assertTrue("no commit may be discoverable", commitPaths(fake, f).isEmpty())

            // Retry after the user reconnects: exactly one commit, all objects byte-identical.
            val retryCheckpoints = AtomicInteger()
            val receipt = CloudBackupUpload(DavBackupRemote(client, emptyList(), checkpoint = {
                Thread.sleep(1)
                retryCheckpoints.incrementAndGet()
            })).upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            assertEquals(f.snapshot.snapshotId, receipt.snapshotId)
            assertEquals(1, commitPaths(fake, f).size)
            assertTrue("retry must stream in 64 KiB chunks, saw ${retryCheckpoints.get()} checkpoints",
                retryCheckpoints.get() >= 3 * 1024 * 1024 / (64 * 1024))
            f.objects.forEach { (id, file) ->
                assertArrayEquals(file.readBytes(), fake.files.getValue("${base(f)}/objects/$id.bin"))
            }
        } finally { f.close() }
    }

    // 2. Restore side: cancel during a slow object download removes staging and the partial object.
    @Test(timeout = 120_000) fun restoreCancelMidStreamRemovesStagingAndPartialObjects() = withDav { fake, client ->
        val f = Fixture(listOf(2 * 1024 * 1024))
        val work = Files.createTempDirectory("restore-cancel").toFile()
        try {
            CloudBackupUpload(DavBackupRemote(client, emptyList()))
                .upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            val staging = File(work, "cloud-${f.snapshot.snapshotId}")
            val stagingObjects = File(staging, "objects")
            val error = runCatching {
                CloudSnapshotFetcher(client, emptyList(), f.vault) {
                    if (stagingObjects.listFiles()?.any { it.length() > 0 } == true) {
                        throw CancellationException("synthetic restore cancel")
                    }
                }.download(f.slot, f.snapshot.snapshotId, f.recovery, work)
            }.exceptionOrNull()
            assertTrue("a restore cancel must surface, got: $error", error is CancellationException)
            assertFalse("staging objects must be removed", stagingObjects.exists())
            assertTrue("no archive may remain", work.listFiles()?.none { it.isFile } ?: true)
            assertTrue("no partial file may remain",
                work.walkTopDown().none { it.isFile })
        } finally { work.deleteRecursively(); f.close() }
    }

    // 3. Large streamed download stays chunked and the heap does not grow by the object size.
    @Test(timeout = 120_000) fun largeDownloadStreamsInBoundedMemory() {
        val size = 8 * 1024 * 1024
        val payload = ByteArray(size) { (it % 251).toByte() }
        val body = MockResponse().setBody(Buffer().write(payload))
        withDav(config = { fake -> fake.interceptor = { request, _ -> if (request.method == "GET") body else null } }) { _, client ->
            val root = Files.createTempDirectory("restore-memory").toFile()
            try {
                val destination = File(root, "object.bin")
                val runtime = Runtime.getRuntime()
                System.gc(); Thread.sleep(50)
                val baseline = runtime.totalMemory() - runtime.freeMemory()
                var peak = baseline
                var checkpoints = 0
                client.downloadTo(listOf("bench", "big.bin"), destination, size.toLong(), sha256(payload)) {
                    checkpoints++
                    val used = runtime.totalMemory() - runtime.freeMemory()
                    if (used > peak) peak = used
                }
                assertEquals(size.toLong(), destination.length())
                assertEquals(sha256(payload), sha256(destination.readBytes()))
                assertTrue("download must stream in 64 KiB chunks, saw $checkpoints", checkpoints >= size / (64 * 1024))
                val grown = peak - baseline
                assertTrue("heap must stay bounded while streaming, grew ${grown / 1024} KiB", grown <= 6L * 1024 * 1024)
            } finally { root.deleteRecursively() }
        }
    }

    // 4a. Local source edited after preflight validation: no inconsistent object is published, no commit.
    @Test(timeout = 120_000) fun mutatedSourceAfterPreflightFailsBeforeCommit() = withDav { fake, client ->
        val f = Fixture()
        try {
            val victim = f.objects.entries.first()
            var mutated = false
            val error = runCatching {
                CloudBackupUpload(DavBackupRemote(client, emptyList(), checkpoint = {
                    // The first remote checkpoint happens inside prepare(), i.e. after the engine already
                    // validated local sizes, so this models "the user replaced the attachment while uploading".
                    if (!mutated) {
                        mutated = true
                        val bytes = victim.value.readBytes()
                        bytes[0] = (bytes[0].toInt() xor 0x5a).toByte()
                        victim.value.writeBytes(bytes)
                    }
                })).upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            }.exceptionOrNull()
            assertNotNull("a changed source must fail closed", error)
            assertTrue("must fail on the digest, got: ${error?.message}",
                error?.message?.contains("备份源文件校验失败") == true)
            assertTrue("no object body may be stored", objectPaths(fake, f).isEmpty())
            assertTrue("no commit may be stored", commitPaths(fake, f).isEmpty())
        } finally { f.close() }
    }

    // 4b. Local source deleted after preflight: the engine stops before any object/commit write.
    @Test(timeout = 120_000) fun deletedSourceAfterPreflightFailsClosedWithoutCommit() = withDav { fake, client ->
        val f = Fixture()
        try {
            val victim = f.objects.entries.first()
            val deleted = AtomicBoolean(false)
            val error = runCatching {
                CloudBackupUpload(DavBackupRemote(client, emptyList(), checkpoint = {
                    // First remote checkpoint = after preflight validation, before the source is reopened.
                    if (deleted.compareAndSet(false, true)) victim.value.delete()
                })).upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            }.exceptionOrNull()
            assertNotNull("a deleted source must fail closed", error)
            assertTrue("deletion must actually happen mid-upload", deleted.get())
            assertTrue("no object body may be stored", objectPaths(fake, f).isEmpty())
            assertTrue("no commit may be stored", commitPaths(fake, f).isEmpty())
        } finally { f.close() }
    }

    // 5a. Cancel window A: PUT finished, snapshot verified, COMMIT not yet published.
    @Test(timeout = 120_000) fun cancelBeforeCommitLeavesVerifiedObjectsAndRetryOnlyPublishesCommit() = withDav { fake, client ->
        val f = Fixture()
        try {
            val phases = mutableListOf<String>()
            val error = runCatching {
                CloudBackupUpload(DavBackupRemote(client, emptyList())).upload(
                    f.manifest, f.vault, f.slot, { f.objects.getValue(it) },
                    phase = { phase ->
                        phases += phase
                        if (phase == "COMMITTING") throw CancellationException("synthetic cancel before commit")
                    })
            }.exceptionOrNull()
            assertTrue("commit-window cancel must surface, got: $error", error is CancellationException)
            assertEquals(listOf("CHECKING_DESTINATION", "UPLOADING_OBJECTS", "VERIFYING", "COMMITTING"), phases)
            assertEquals("objects must already be verified", f.objects.size, objectPaths(fake, f).size)
            assertArrayEquals("snapshot must already be verified", f.manifest.readBytes(),
                fake.files.getValue("${base(f)}/snapshots/${f.snapshot.snapshotId}.bin"))
            assertTrue("cancel before COMMIT must leave no commit", commitPaths(fake, f).isEmpty())

            val putsBefore = fake.putPaths.size
            val receipt = CloudBackupUpload(DavBackupRemote(client, emptyList()))
                .upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            assertEquals(f.snapshot.snapshotId, receipt.snapshotId)
            assertEquals(1, commitPaths(fake, f).size)
            assertEquals("retry must publish only the commit, not rewrite verified objects",
                putsBefore + 1, fake.putPaths.size)
        } finally { f.close() }
    }

    // 5b. Cancel window B: COMMIT published, readback not completed. Retry reuses the same commit.
    @Test(timeout = 120_000) fun readbackFailureAfterCommitLeavesExactlyOneCommitAndRetrySucceeds() = withDav { fake, client ->
        val f = Fixture()
        try {
            val failed = AtomicBoolean(false)
            fake.interceptor = { request, target ->
                if (request.method == "GET" && target.contains("/commits/") &&
                    fake.files.containsKey(target) && failed.compareAndSet(false, true)) {
                    MockResponse().setResponseCode(500)
                } else null
            }
            val error = runCatching {
                CloudBackupUpload(DavBackupRemote(client, emptyList()))
                    .upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            }.exceptionOrNull()
            assertTrue("the readback failure must surface, got: $error", error is DavException)
            assertEquals("the commit itself must already be durable", 1, commitPaths(fake, f).size)
            assertEquals(500, (error as DavException).status)
            val stored = fake.files.getValue(commitPaths(fake, f).single())
            val putsBefore = fake.putPaths.size

            fake.interceptor = null
            val receipt = CloudBackupUpload(DavBackupRemote(client, emptyList()))
                .upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            assertEquals(f.snapshot.snapshotId, receipt.snapshotId)
            assertEquals("retry must not publish a second commit", putsBefore, fake.putPaths.size)
            assertEquals(1, commitPaths(fake, f).size)
            assertArrayEquals("the durable commit must not be rewritten", stored,
                fake.files.getValue(commitPaths(fake, f).single()))
        } finally { f.close() }
    }

    // 6. Process termination: durable writer state is rebuilt, identity kept, sequence never reused.
    @Test(timeout = 120_000) fun writerStateRebuiltAfterTerminationKeepsIdentityAndAdvancesSequence() = withDav { fake, client ->
        val f = Fixture()
        val writerDirectory = Files.createTempDirectory("writer-state").toFile()
        try {
            val first = CloudWriterStore(writerDirectory, f.vault) { }
            val r1 = first.reserve()
            assertEquals(1L, r1.sequence)
            val snapshot1 = f.snapshot.copy(snapshotId = UUID.randomUUID().toString(),
                writerId = r1.writerId, writerSequence = r1.sequence)
            val manifest1 = manifestFor(f, snapshot1)
            val killed = runCatching {
                CloudBackupUpload(DavBackupRemote(client, emptyList())).upload(
                    manifest1, f.vault, f.slot, { f.objects.getValue(it) },
                    phase = { if (it == "UPLOADING_OBJECTS") throw CancellationException("synthetic process death") })
            }.exceptionOrNull()
            assertTrue("the simulated termination must abort the run, got: $killed", killed is CancellationException)
            assertTrue(commitPaths(fake, f).isEmpty())

            // A fresh process opens the same durable directory: same writer, next sequence, no deadlock.
            val restarted = CloudWriterStore(writerDirectory, f.vault) { }
            val r2 = restarted.reserve()
            assertEquals("the writer identity must survive a restart", r1.writerId, r2.writerId)
            assertEquals("the cancelled sequence must never be reused", r1.sequence + 1, r2.sequence)
            assertEquals(r1.writerId, restarted.currentWriterId())

            val snapshot2 = snapshot1.copy(snapshotId = UUID.randomUUID().toString(),
                writerSequence = r2.sequence, capturedSequence = 22, createdAt = snapshot1.createdAt + 60_000)
            val manifest2 = manifestFor(f, snapshot2)
            val receipt = CloudBackupUpload(DavBackupRemote(client, emptyList()))
                .upload(manifest2, f.vault, f.slot, { f.objects.getValue(it) })
            assertEquals(snapshot2.snapshotId, receipt.snapshotId)
            assertEquals(r2.writerId, receipt.writerId)
            assertEquals(r2.sequence, receipt.writerSequence)
            assertEquals(1, commitPaths(fake, f).size)
            val storedCommit = fake.files.getValue(commitPaths(fake, f).single())
            val identity = CloudCommit.writerIdentity(storedCommit, snapshot2.snapshotId, manifest2.readBytes(), f.vault)
            assertEquals(r1.writerId, identity.writerId)
            assertEquals(r2.sequence, identity.writerSequence)

            // Replaying the same job after the restart must not upload anything again.
            val putsBefore = fake.putPaths.size
            val replay = CloudBackupUpload(DavBackupRemote(client, emptyList()))
                .upload(manifest2, f.vault, f.slot, { f.objects.getValue(it) })
            assertEquals(receipt, replay)
            assertEquals("replay must not duplicate remote writes", putsBefore, fake.putPaths.size)
        } finally { writerDirectory.deleteRecursively(); f.close() }
    }

    // 7a. 5xx at prepare: fail fast, no partial layout, retry works.
    @Test(timeout = 120_000) fun prepareServerErrorFailsWithoutPartialLayoutAndRetrySucceeds() = withDav { fake, client ->
        val f = Fixture()
        try {
            val failed = AtomicBoolean(false)
            fake.interceptor = { request, _ ->
                if (request.method == "PROPFIND" && failed.compareAndSet(false, true)) {
                    MockResponse().setResponseCode(500)
                } else null
            }
            val error = runCatching {
                CloudBackupUpload(DavBackupRemote(client, emptyList()))
                    .upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            }.exceptionOrNull()
            assertTrue("a 500 at prepare must surface, got: $error", error is DavException)
            assertEquals(500, (error as DavException).status)
            assertTrue("no identity file may be written", fake.files.keys.none { it.startsWith("/backup/pregnancy-journal") })
            assertEquals("no vault directory may be created", 1, fake.directories.size)

            fake.interceptor = null
            val receipt = CloudBackupUpload(DavBackupRemote(client, emptyList()))
                .upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            assertEquals(f.snapshot.snapshotId, receipt.snapshotId)
            assertEquals(1, commitPaths(fake, f).size)
        } finally { f.close() }
    }

    // 7b. 500 on an object PUT: no commit, retry after the transient provider error completes once.
    @Test(timeout = 120_000) fun objectPutServerErrorFailsBeforeCommitAndRetrySucceeds() = withDav { fake, client ->
        val f = Fixture()
        try {
            val failed = AtomicBoolean(false)
            fake.interceptor = { request, target ->
                if (request.method == "PUT" && target.contains("/objects/") && failed.compareAndSet(false, true)) {
                    MockResponse().setResponseCode(500)
                } else null
            }
            val error = runCatching {
                CloudBackupUpload(DavBackupRemote(client, emptyList()))
                    .upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            }.exceptionOrNull()
            assertTrue("an object PUT 500 must surface, got: $error", error is DavException)
            assertTrue("no commit may be stored", commitPaths(fake, f).isEmpty())
            assertTrue("the failed object must not be stored", objectPaths(fake, f).size < f.objects.size)

            fake.interceptor = null
            val receipt = CloudBackupUpload(DavBackupRemote(client, emptyList()))
                .upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            assertEquals(f.snapshot.snapshotId, receipt.snapshotId)
            assertEquals(1, commitPaths(fake, f).size)
            assertEquals(f.objects.size, objectPaths(fake, f).size)
        } finally { f.close() }
    }

    // 7c. 409 on the commit PUT is never silently overwritten; retry republishes cleanly.
    @Test(timeout = 120_000) fun commitConflictFailsClosedAndRetryPublishes() = withDav { fake, client ->
        val f = Fixture()
        try {
            val failed = AtomicBoolean(false)
            fake.interceptor = { request, target ->
                if (request.method == "PUT" && target.contains("/commits/") && failed.compareAndSet(false, true)) {
                    MockResponse().setResponseCode(409)
                } else null
            }
            val error = runCatching {
                CloudBackupUpload(DavBackupRemote(client, emptyList()))
                    .upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            }.exceptionOrNull()
            assertTrue("a commit 409 must surface, got: $error", error is DavException)
            assertEquals(409, (error as DavException).status)
            assertTrue("a conflicting commit must not create a local one", commitPaths(fake, f).isEmpty())
            assertEquals("verified objects may already exist", f.objects.size, objectPaths(fake, f).size)

            fake.interceptor = null
            val receipt = CloudBackupUpload(DavBackupRemote(client, emptyList()))
                .upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            assertEquals(f.snapshot.snapshotId, receipt.snapshotId)
            assertEquals(1, commitPaths(fake, f).size)
        } finally { f.close() }
    }

    // 7d. Identical peer commit + 412: treated as our own durable commit, no second write.
    @Test(timeout = 120_000) fun identicalCommitConflictIsReusedNotDuplicated() = withDav { fake, client ->
        val f = Fixture()
        try {
            val expected = CloudCommit.create(f.manifest.readBytes(), f.vault)
            val failed = AtomicBoolean(false)
            fake.interceptor = { request, target ->
                if (request.method == "PUT" && target.contains("/commits/") && failed.compareAndSet(false, true)) {
                    fake.files[target] = expected
                    MockResponse().setResponseCode(412)
                } else null
            }
            val receipt = CloudBackupUpload(DavBackupRemote(client, emptyList()))
                .upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            assertEquals(f.snapshot.snapshotId, receipt.snapshotId)
            assertEquals(1, commitPaths(fake, f).size)
            assertArrayEquals("the peer-identical commit must be reused unchanged", expected,
                fake.files.getValue(commitPaths(fake, f).single()))
            CloudCommit.verify(expected, f.snapshot.snapshotId, f.manifest.readBytes(), f.vault)
        } finally { f.close() }
    }

    // 7e. Connection dropped while verifying the just-uploaded object: fail closed, retry once.
    @Test(timeout = 120_000) fun interruptedVerifyDownloadFailsClosedAndRetryPublishesOnce() = withDav { fake, client ->
        val f = Fixture()
        try {
            val interrupted = AtomicBoolean(false)
            fake.interceptor = { request, target ->
                if (request.method == "GET" && target.contains("/objects/") &&
                    fake.files.containsKey(target) && interrupted.compareAndSet(false, true)) {
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                } else null
            }
            val error = runCatching {
                CloudBackupUpload(DavBackupRemote(client, emptyList()))
                    .upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            }.exceptionOrNull()
            assertNotNull("an interrupted readback must not be reported as success", error)
            assertTrue("a dropped connection must not publish a commit", commitPaths(fake, f).isEmpty())

            fake.interceptor = null
            val receipt = CloudBackupUpload(DavBackupRemote(client, emptyList()))
                .upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            assertEquals(f.snapshot.snapshotId, receipt.snapshotId)
            assertEquals(1, commitPaths(fake, f).size)
            assertEquals(f.objects.size, objectPaths(fake, f).size)
        } finally { f.close() }
    }
}
