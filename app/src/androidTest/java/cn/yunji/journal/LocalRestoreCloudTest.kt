package cn.yunji.journal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.PregnancyEntity
import cn.yunji.journal.core.network.CloudCapabilities
import cn.yunji.journal.core.network.CloudConnection
import cn.yunji.journal.core.network.SavedCloudConnection
import cn.yunji.journal.core.network.WebDavClient
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.EncryptedFileStore
import cn.yunji.journal.data.*
import kotlinx.coroutines.runBlocking
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
import java.security.KeyStore
import java.time.Clock
import java.util.UUID

/**
 * End-to-end cloud restore through the production adapter: a real TLS WebDAV server on the device, the real
 * `CloudSnapshotFetcher`, and the real restore staging path. Synthetic data only.
 */
class LocalRestoreCloudTest {
    private class Cloud : RestoreCloudControl {
        var pauses = 0; var resumes = 0
        override suspend fun pauseForRestore() { pauses++ }
        override fun resumeAfterRestoreAbort() { resumes++ }
    }

    /** Serves only what the adapter is allowed to rely on: PROPFIND listing and byte-exact GET. */
    private class FakeDav : Dispatcher() {
        val files = linkedMapOf<String, ByteArray>()
        val directories = linkedSetOf("/backup")
        var corruptObjectId: String? = null
        val requests = mutableListOf<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val target = (request.path ?: return MockResponse().setResponseCode(400)).substringBefore('?').trimEnd('/')
            requests += "${request.method} $target"
            if (!target.startsWith("/backup")) return MockResponse().setResponseCode(404)
            return when (request.method) {
                "PROPFIND" -> propfind(target, request.getHeader("Depth"))
                "GET" -> {
                    val body = files[target] ?: return MockResponse().setResponseCode(404)
                    val served = if (corruptObjectId != null && target.endsWith("/$corruptObjectId.bin")) {
                        body.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                    } else body
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
                        append("<d:getlastmodified>").append("Tue, 20 Oct 2026 00:00:00 GMT").append("</d:getlastmodified>")
                    }
                    append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
                }
                append("</d:multistatus>")
            }
            return MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(body)
        }

        fun publish(vault: VaultMaterial, slot: RecoverySlot, store: EncryptedFileStore, snapshot: LogicalSnapshot) {
            val root = "/backup/pregnancy-journal/${vault.id}"
            directories += "/backup/pregnancy-journal"
            directories += root
            listOf("objects", "snapshots", "commits").forEach { directories += "$root/$it" }
            files["$root/vault.json"] = RecoverySlots.encode(slot)
            val manifest = BackupManifest.encode(snapshot, vault)
            files["$root/snapshots/${snapshot.snapshotId}.bin"] = manifest
            files["$root/commits/${snapshot.snapshotId}.json"] = CloudCommit.create(manifest, vault)
            BackupManifest.objects(snapshot).forEach { info -> files["$root/objects/${info.id}.bin"] = store.path(info.id).readBytes() }
        }
    }

    private class Fixture : AutoCloseable {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val token = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "cloud-restore-$token").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$token-$name", mode)
        }
        val keys = DeviceKeyStore.isolated(context, token)
        val runtime = VaultRuntime(context, keys, VaultSession(), Clock.systemUTC(), ReminderStore(context),
            ReminderNotifier(context), AccessState(context, SecurityStore(context), keys))
        /** The vault the cloud backup belongs to; deliberately different from this device's own vault. */
        val source = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
        val recovery = RecoveryKey.create()
        val slot = RecoverySlots.create(source, recovery)
        val store = EncryptedFileStore(File(root, "source-objects"), source)
        val cloud = Cloud()
        val snapshot: LogicalSnapshot

        init {
            val original = "synthetic cloud original".toByteArray()
            val stored = store.put(original.inputStream())
            snapshot = LogicalSnapshot(vaultId = source.id, snapshotId = UUID.randomUUID().toString(),
                writerId = UUID.randomUUID().toString(), writerSequence = 2, capturedSequence = 7, createdAt = 1_700_000_000_000,
                pregnancies = listOf(PregnancyEntity("cloud-pregnancy", "合成云端档案", createdAt = 1, updatedAt = 1),
                    PregnancyEntity("cloud-other", "合成第二档案", createdAt = 2, updatedAt = 2)),
                attachments = listOf(cn.yunji.journal.core.database.AttachmentEntity(stored.id, null, "合成包装照片",
                    "image/png", stored.plainSize, stored.cipherSize, stored.plainSha256, stored.cipherSha256,
                    null, null, null, null, null, 1, category = "PACKAGE", medicationId = "cloud-med"),
                ),
                medications = listOf(cn.yunji.journal.core.database.MedicationEntity("cloud-med", "cloud-pregnancy", "合成药品", "", "", 1)),
                visits = listOf(cn.yunji.journal.core.database.VisitEntity("cloud-visit", "cloud-pregnancy", "2026-10-01",
                    zoneId = "UTC", createdAt = 1, updatedAt = 1)))
        }

        val inputRoot get() = File(root, "vault/restore-input")

        fun connection(endpoint: String) = CloudConnection(endpoint = endpoint, username = "synthetic",
            password = "synthetic-password", folder = emptyList()).normalized()

        /** Stores a connection that has already passed the write/read-back check. */
        fun saveConnection(endpoint: String) {
            CloudConnectionStore(context, runtime.resources().material).save(SavedCloudConnection(
                connection = connection(endpoint), capabilities = CloudCapabilities(writable = true, verified = true), testedAt = 1))
        }

        fun access(client: OkHttpClient) = LocalRestoreAccess(context, runtime, cloud, { File(root, "unused").inputStream() },
            8L * 1024 * 1024 * 1024 + 64L * 1024 * 1024, Clock.systemUTC(),
            { connection -> WebDavClient(connection.endpoint.let { java.net.URI(it) }.let { uri ->
                okhttp3.HttpUrl.Builder().scheme(uri.scheme).host(uri.host).port(uri.port)
                    .addPathSegments(uri.path.trim('/')).addPathSegment("").build() },
                connection.username, connection.password, client) })

        override fun close() {
            runBlocking { runtime.revoke() }
            source.master.fill(0)
            val alias = "${base.packageName}.vault-wrap.test-$token.v1"
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            listOf("security-state", "reminder-state", "cloud-scheduler", "cloud-upload-state").forEach { base.deleteSharedPreferences("$token-$it") }
            root.deleteRecursively()
        }
    }

    private fun <T> withServer(block: (FakeDav, OkHttpClient, String) -> T): T {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            val fake = FakeDav()
            server.dispatcher = fake
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()
            val endpoint = server.url("/backup/").newBuilder().host("localhost").build().toString()
            return block(fake, client, endpoint)
        }
    }

    @Test fun listsAndPreparesACloudSnapshotThenCommitsItThroughTheProductionAdapter() = withServer { fake, client, endpoint ->
        Fixture().use { f ->
            fake.publish(f.source, f.slot, f.store, f.snapshot)
            f.saveConnection(endpoint)
            val access = f.access(client)

            val listed = runBlocking { access.listCloudSnapshots(f.recovery) }
            assertEquals(listOf(f.snapshot.snapshotId), listed.map { it.snapshotId })

            val preview = runBlocking { access.prepareCloud(f.snapshot.snapshotId, f.recovery) }
            assertEquals(f.snapshot.snapshotId, preview.snapshotId)
            assertEquals(2, preview.pregnancies)
            assertEquals(1, preview.documents)
            assertEquals(7, preview.capturedSequence)
            assertTrue("the download staging area must be cleaned", f.inputRoot.listFiles().orEmpty().isEmpty())
            assertEquals("nothing may be paused before the user confirms", 0, f.cloud.pauses)

            runBlocking { access.commit(preview) }
            assertTrue(access.isCommitted(preview))
            assertEquals(1, f.cloud.pauses); assertEquals(1, f.cloud.resumes)
            val restored = runBlocking { f.runtime.resources().database.journalDao().allPregnancies() }
            assertEquals(f.snapshot.pregnancies.toSet(), restored.toSet())
            assertFalse("a restored vault is not the primary device", CloudSchedulerStore(f.context).load().autoActive)
        }
    }

    @Test fun wrongKeyFindsNothingAndATamperedObjectIsRefusedWithoutStaging() = withServer { fake, client, endpoint ->
        Fixture().use { f ->
            fake.publish(f.source, f.slot, f.store, f.snapshot)
            f.saveConnection(endpoint)
            val access = f.access(client)

            assertTrue("a different key must not open the cloud vault",
                runBlocking { access.listCloudSnapshots(RecoveryKey.create()) }.isEmpty())

            val victim = f.snapshot.attachments.first().objectId ?: f.snapshot.attachments.first().id
            fake.corruptObjectId = victim
            val failure = runCatching { runBlocking { access.prepareCloud(f.snapshot.snapshotId, f.recovery) } }.exceptionOrNull()
            assertNotNull("a corrupted remote object must abort the download", failure)
            assertTrue("no generation may be staged", runBlocking { f.runtime.resources().database.journalDao().allPregnancies() }.isEmpty())
            assertTrue("the staging area must be cleaned after a failure", f.inputRoot.listFiles().orEmpty().isEmpty())

            fake.corruptObjectId = null
            val preview = runBlocking { access.prepareCloud(f.snapshot.snapshotId, f.recovery) }
            runBlocking { access.discard(preview) }
        }
    }

    @Test fun cloudRestoreRequiresASavedVerifiedConnection() = withServer { fake, client, _ ->
        Fixture().use { f ->
            fake.publish(f.source, f.slot, f.store, f.snapshot)
            val access = f.access(client)
            val failure = runCatching { runBlocking { access.listCloudSnapshots(f.recovery) } }.exceptionOrNull()
            assertTrue("without a saved connection the adapter must refuse",
                requireNotNull(failure).message!!.contains("云盘连接"))
            // An unverified connection is not enough either.
            CloudConnectionStore(f.context, f.runtime.resources().material).save(SavedCloudConnection(
                connection = f.connection("https://synthetic.invalid/"), capabilities = CloudCapabilities(), testedAt = 1))
            val second = runCatching { runBlocking { access.listCloudSnapshots(f.recovery) } }.exceptionOrNull()
            assertTrue(requireNotNull(second).message!!.contains("未通过"))
        }
    }

    /**
     * Reclaim lifecycle through the production adapter: restore → reopen → restore again (keeps the
     * first generation for rollback) → reclaim only that generation → restore once more.
     */
    @Test fun reclaimsTheConfirmedGenerationThenRestoresAgain() = withServer { fake, client, endpoint ->
        Fixture().use { f ->
            val second = f.snapshot.copy(snapshotId = UUID.randomUUID().toString(),
                writerId = UUID.randomUUID().toString(), writerSequence = 3, capturedSequence = 8)
            fake.publish(f.source, f.slot, f.store, f.snapshot)
            fake.publish(f.source, f.slot, f.store, second)
            f.saveConnection(endpoint)
            val access = f.access(client)

            val firstPreview = runBlocking { access.prepareCloud(f.snapshot.snapshotId, f.recovery) }
            runBlocking { access.commit(firstPreview) }
            runBlocking { access.finishReopen() }
            val vaultDir = File(f.context.noBackupFilesDir, "vault")
            val layout = VaultLayout(vaultDir)
            val firstGeneration = requireNotNull(layout.activeGenerationId())
            assertNull("a first restore has no generation to reclaim", layout.readPointer()?.previousId)
            assertNull("reopen must clear the restore journal", RestoreJournal(vaultDir).read())

            f.saveConnection(endpoint) // the restored vault has its own provider identity
            val secondPreview = runBlocking { access.prepareCloud(second.snapshotId, f.recovery) }
            runBlocking { access.commit(secondPreview) }
            runBlocking { access.finishReopen() }
            val secondGeneration = requireNotNull(layout.activeGenerationId())
            assertNotEquals("a second restore must build a new generation", firstGeneration, secondGeneration)
            assertEquals("the confirmed generation must be kept for rollback",
                firstGeneration, layout.readPointer()?.previousId)
            assertNull(RestoreJournal(vaultDir).read())

            val reclaim = RestoreReclaim(vaultDir)
            assertEquals(ReclaimState.AVAILABLE, reclaim.inspect().state)
            assertEquals(firstGeneration, reclaim.reclaim())
            assertNull(layout.readPointer()?.previousId)
            assertFalse("the reclaimed generation must be gone", layout.generationDir(firstGeneration).exists())
            assertTrue("the active generation must survive", layout.generationDatabase(secondGeneration).isFile)
            assertEquals("a repeated reclaim must be a no-op", ReclaimState.NONE, reclaim.inspect().state)
            assertNull(reclaim.reclaim())

            f.saveConnection(endpoint)
            val thirdPreview = runBlocking { access.prepareCloud(f.snapshot.snapshotId, f.recovery) }
            runBlocking { access.commit(thirdPreview) }
            runBlocking { access.finishReopen() }
            assertEquals("a restore after reclaim must keep its own rollback generation",
                secondGeneration, layout.readPointer()?.previousId)
            val restored = runBlocking { f.runtime.resources().database.journalDao().allPregnancies() }
            assertEquals(f.snapshot.pregnancies.toSet(), restored.toSet())
        }
    }
}
