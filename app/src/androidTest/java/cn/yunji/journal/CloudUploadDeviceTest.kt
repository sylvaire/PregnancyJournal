package cn.yunji.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.AttachmentEntity
import cn.yunji.journal.core.database.PregnancyEntity
import cn.yunji.journal.core.database.VisitEntity
import cn.yunji.journal.core.network.DavBackupRemote
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
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * 设备级云端上传 E2E（非 UI）：设备内 TLS 假 WebDAV 服务 + 生产上传管线
 * `CloudBackupUpload` → `DavBackupRemote` → `WebDavClient`，在真实 Android TLS/文件系统上验证
 * JVM 组合测试 `CloudBackupUploadDavTest` 的覆盖点：阶段顺序、不可变对象与 commit 的幂等复用、
 * 远端身份/对象损坏/源文件缺失/回读缺失的失败路径（不返回成功、不静默覆盖）。
 *
 * 假服务只实现适配器允许依赖的协议子集（PROPFIND/MKCOL/PUT/GET/HEAD），全部为合成数据。
 */
class CloudUploadDeviceTest {
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
                    else if (target.contains("/commits/") && target.endsWith(".json") && dropCommitReadback) {
                        MockResponse().setResponseCode(404)
                    } else MockResponse().setBody(okio.Buffer().write(body))
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

    /** A real TLS WebDAV server inside the device process; the client trusts exactly one held certificate. */
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

    private class Fixture : AutoCloseable {
        private val base = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        val directory = File(base.cacheDir, "cloud-upload-$token").apply { mkdirs() }
        val vault = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
        val recovery = RecoveryKey.create()
        val slot = RecoverySlots.create(vault, recovery)
        val pregnancy = PregnancyEntity("pregnancy", "合成档案", createdAt = 1, updatedAt = 1)
        val visit = VisitEntity("visit", pregnancy.id, "2026-10-20", zoneId = "UTC", createdAt = 1, updatedAt = 1)
        val objects = linkedMapOf<String, File>()
        val attachments = mutableListOf<AttachmentEntity>()
        val snapshot: LogicalSnapshot
        val manifest = File(directory, "manifest.enc")

        init {
            val store = EncryptedFileStore(File(directory, "source-objects"), vault)
            repeat(2) { index ->
                val body = ("synthetic ciphertext $index, not personal data").toByteArray() + ByteArray(index)
                val stored = store.put(body.inputStream())
                val copy = File(directory, "${stored.id}.bin")
                store.path(stored.id).copyTo(copy, overwrite = true)
                objects[stored.id] = copy
                attachments += AttachmentEntity(
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

        fun engine(client: WebDavClient) = CloudBackupUpload(DavBackupRemote(client, emptyList()))

        override fun close() { vault.master.fill(0); directory.deleteRecursively() }
    }

    private fun receipt(fake: FakeDav, vaultId: String): ByteArray {
        val path = "/backup/pregnancy-journal/$vaultId/commits"
        val commit = fake.files.keys.filter { it.startsWith("$path/") }
        assertEquals(1, commit.size)
        return fake.files.getValue(commit.single())
    }

    @Test fun firstUploadPublishesObjectsSnapshotIdentityAndCommitInPhaseOrder() = withDav { fake, client ->
        Fixture().use { f ->
            val phases = mutableListOf<String>()
            val first = f.engine(client).upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) }, phase = phases::add)
            assertEquals(f.snapshot.snapshotId, first.snapshotId)
            assertEquals(f.snapshot.writerId, first.writerId)
            assertEquals(f.snapshot.writerSequence, first.writerSequence)
            assertEquals(21, first.capturedSequence)
            assertEquals(listOf("CHECKING_DESTINATION", "UPLOADING_OBJECTS", "VERIFYING", "COMMITTING"), phases)

            val base = "/backup/pregnancy-journal/${f.vault.id}"
            assertTrue("directories=${fake.directories}", base in fake.directories)
            listOf("objects", "snapshots", "commits").forEach { assertTrue("$base/$it in ${fake.directories}", "$base/$it" in fake.directories) }
            f.objects.forEach { (id, file) ->
                val path = "$base/objects/$id.bin"
                assertTrue("missing $path; have ${fake.files.keys}; methods=${fake.methods}; dirs=${fake.directories}",
                    fake.files.containsKey(path))
                assertArrayEquals(file.readBytes(), fake.files.getValue(path))
            }
            assertArrayEquals(f.manifest.readBytes(), fake.files.getValue("$base/snapshots/${f.snapshot.snapshotId}.bin"))
            assertArrayEquals(RecoverySlots.encode(f.slot), fake.files.getValue("$base/vault.json"))
            CloudCommit.verify(receipt(fake, f.vault.id), f.snapshot.snapshotId, f.manifest.readBytes(), f.vault)
            // The commit is published last: every object/snapshot PUT precedes the commits PUT.
            val lastPut = fake.methods.indexOfLast { it == "PUT" }
            assertTrue("a commit must follow every object/snapshot upload", lastPut >= 0)
            assertTrue("commit PUT must target the commits collection",
                fake.paths[lastPut].startsWith("$base/commits/"))
            assertTrue("objects and snapshots must be uploaded before the commit",
                fake.paths.take(lastPut).any { it.startsWith("$base/objects/") } &&
                    fake.paths.take(lastPut).any { it.startsWith("$base/snapshots/") })
        }
    }

    @Test fun repeatedUploadReusesImmutableObjectsAndCommitWithoutRewriting() = withDav { fake, client ->
        Fixture().use { f ->
            val engine = f.engine(client)
            val first = engine.upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            val commit = receipt(fake, f.vault.id)
            val fileSnapshots = fake.files.mapValues { it.value.copyOf() }
            val uploadsBefore = fake.methods.count { it == "PUT" }

            val second = engine.upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            assertEquals(first, second)
            assertEquals("Retry must not rewrite immutable objects or the commit", uploadsBefore, fake.methods.count { it == "PUT" })
            assertArrayEquals("The already published commit must be byte-identical", commit, receipt(fake, f.vault.id))
            fileSnapshots.forEach { (path, bytes) -> assertArrayEquals("Retry rewrote $path", bytes, fake.files.getValue(path)) }
            assertEquals("Retry must not create extra remote entries", fileSnapshots.size, fake.files.size)
        }
    }

    @Test fun foreignIdentityAtDestinationIsNeverOverwritten() = withDav { fake, client ->
        Fixture().use { f ->
            val base = "/backup/pregnancy-journal/${f.vault.id}"
            val foreign = RecoverySlots.create(VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey()), RecoveryKey.create())
            fake.directories += "/backup/pregnancy-journal"
            fake.directories += base
            fake.files["$base/vault.json"] = RecoverySlots.encode(foreign)
            val error = runCatching { f.engine(client).upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) }) }.exceptionOrNull()
            assertNotNull("A foreign destination identity must fail the upload", error)
            assertArrayEquals(RecoverySlots.encode(foreign), fake.files.getValue("$base/vault.json"))
            assertTrue("no object may be written for a foreign identity",
                fake.files.keys.none { it.contains("/objects/") })
            assertTrue("no commit may be published for a foreign identity",
                fake.files.keys.none { it.contains("/commits/") })
        }
    }

    @Test fun corruptedRemoteObjectBlocksReuseInsteadOfBeingSilentlyReplaced() = withDav { fake, client ->
        Fixture().use { f ->
            val base = "/backup/pregnancy-journal/${f.vault.id}"
            val remote = DavBackupRemote(client, emptyList())
            remote.prepare(f.slot)
            val (id, file) = f.objects.entries.first()
            fake.files["$base/objects/$id.bin"] = ByteArray(file.length().toInt()) { 9 }
            val damaged = fake.files.getValue("$base/objects/$id.bin")

            val error = runCatching {
                CloudBackupUpload(remote).upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) })
            }.exceptionOrNull()
            assertNotNull("A corrupted object already in the cloud must fail the upload", error)
            assertArrayEquals("damaged bytes must not be silently replaced", damaged, fake.files.getValue("$base/objects/$id.bin"))
            assertTrue("no commit may be published over a corrupted object",
                fake.files.keys.none { it.startsWith("$base/commits/") })
        }
    }

    @Test fun localValidationFailuresNeverWriteRemotely() = withDav { fake, client ->
        Fixture().use { f ->
            val engine = f.engine(client)
            // A recovery slot must belong to the same vault; the engine rejects it before touching the network.
            val foreign = RecoverySlots.create(VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey()), RecoveryKey.create())
            assertTrue(runCatching { engine.upload(f.manifest, f.vault, foreign, { f.objects.getValue(it) }) }.isFailure)
            assertTrue("Rejected identity must not write remotely", fake.methods.isEmpty())

            // A missing local source must be detected before any remote data is created.
            f.objects.values.first().delete()
            assertTrue(runCatching { engine.upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) }) }.isFailure)
            assertTrue("No remote write may precede local source validation", fake.methods.isEmpty())
        }
    }

    @Test fun missingCommitReadbackNeverReportsSuccess() = withDav { fake, client ->
        Fixture().use { f ->
            fake.dropCommitReadback = true
            val error = runCatching { f.engine(client).upload(f.manifest, f.vault, f.slot, { f.objects.getValue(it) }) }
                .exceptionOrNull()
            assertNotNull("A missing commit readback must not be reported as success", error)
            // The failure must be the mandatory commit read-back, not an earlier stage.
            assertTrue("unexpected failure: ${error?.message}", error?.message.orEmpty().contains("云端提交回读缺失"))
            assertTrue("the commit write itself happened before the failed read-back",
                fake.files.keys.any { it.contains("/commits/") })
        }
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
                assertTrue("unexpected failure: $error", error is javax.net.ssl.SSLException || error is java.io.IOException)
            }
        }
    }
}
