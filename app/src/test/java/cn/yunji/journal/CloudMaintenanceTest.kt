package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.AttachmentEntity
import cn.yunji.journal.core.database.PregnancyEntity
import cn.yunji.journal.core.database.VisitEntity
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Remote retention end to end: authenticate every listed commit, honour listing completeness, and delete
 * only what the plan names. Uses a real TLS WebDAV subset that reports last-modified metadata.
 */
class CloudMaintenanceTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = Instant.parse("2026-10-21T00:00:00Z").toEpochMilli()

    private class Fixture {
        val vault = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
        val recovery = RecoveryKey.create()
        val slot = RecoverySlots.create(vault, recovery)
        val writer = UUID.randomUUID().toString()

        /** A minimal but relationally valid snapshot: one pregnancy, one visit and one attachment per object. */
        fun snapshot(id: String, createdAt: Long, sequence: Long, objectIds: List<String>) = LogicalSnapshot(
            vaultId = vault.id, snapshotId = id, writerId = writer, writerSequence = sequence,
            capturedSequence = sequence, createdAt = createdAt,
            pregnancies = listOf(PregnancyEntity("p-$id", "合成档案", createdAt = 1, updatedAt = 1)),
            visits = listOf(VisitEntity("v-$id", "p-$id", "2026-10-21", zoneId = "UTC", createdAt = 1, updatedAt = 1)),
            attachments = objectIds.map { objectId ->
                AttachmentEntity(objectId, "v-$id", "合成原件", "image/png", 100, 132,
                    "0".repeat(64), "0".repeat(64), null, null, null, null, null, 1)
            })

        fun close() { vault.master.fill(0) }
    }

    private inner class FakeDav : Dispatcher() {
        val files = linkedMapOf<String, ByteArray>()
        val modified = linkedMapOf<String, Long>()
        val directories = linkedSetOf("/backup")
        var failListingFor: String? = null

        override fun dispatch(request: RecordedRequest): MockResponse {
            val target = (request.path ?: return MockResponse().setResponseCode(400)).substringBefore('?').trimEnd('/')
            if (!target.startsWith("/backup")) return MockResponse().setResponseCode(404)
            if (failListingFor?.let { target.endsWith(it) } == true) return MockResponse().setResponseCode(500)
            return when (request.method) {
                "PROPFIND" -> propfind(target, request.getHeader("Depth"))
                "MKCOL" -> { directories += target; MockResponse().setResponseCode(201) }
                "GET" -> files[target]?.let { MockResponse().setBody(okio.Buffer().write(it)) }
                    ?: MockResponse().setResponseCode(404)
                "PUT" -> { files[target] = request.body.readByteArray(); modified[target] = now; MockResponse().setResponseCode(201) }
                "DELETE" -> if (files.remove(target) != null) MockResponse().setResponseCode(204)
                    else MockResponse().setResponseCode(404)
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
                        modified[entry]?.let { at ->
                            val text = Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME)
                            append("<d:getlastmodified>").append(text).append("</d:getlastmodified>")
                        }
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

    /** Publishes a real commit, manifest and object files for one snapshot. */
    private fun publish(fake: FakeDav, f: Fixture, snapshot: LogicalSnapshot, objectModifiedAt: Long) {
        val root = "/backup/pregnancy-journal/${f.vault.id}"
        fake.directories += "/backup/pregnancy-journal"
        fake.directories += root
        listOf("objects", "snapshots", "commits").forEach { fake.directories += "$root/$it" }
        fake.files["$root/vault.json"] = RecoverySlots.encode(f.slot)
        val manifest = BackupManifest.encode(snapshot, f.vault)
        fake.files["$root/snapshots/${snapshot.snapshotId}.bin"] = manifest
        fake.files["$root/commits/${snapshot.snapshotId}.json"] = CloudCommit.create(manifest, f.vault)
        BackupManifest.objects(snapshot).forEach { info ->
            fake.files["$root/objects/${info.id}.bin"] = ByteArray(info.cipherSize.toInt()) { 5 }
            fake.modified["$root/objects/${info.id}.bin"] = objectModifiedAt
        }
    }

    private fun rootOf(f: Fixture) = "/backup/pregnancy-journal/${f.vault.id}"

    private fun maintenance(client: WebDavClient, f: Fixture) = CloudMaintenance(client, emptyList(), f.vault)

    private fun planned(decision: CleanupDecision): CleanupPlan {
        assertTrue("Expected a plan but cleanup stopped: ${(decision as? CleanupDecision.Stopped)?.reason}",
            decision is CleanupDecision.Plan)
        return (decision as CleanupDecision.Plan).plan
    }

    @Test fun collectAuthenticatesEveryCommitAndReportsObjectAges() = withDav { fake, client ->
        val f = Fixture()
        try {
            val old = f.snapshot(UUID.randomUUID().toString(), now - 60 * day, 1, listOf(UUID.randomUUID().toString()))
            val recent = f.snapshot(UUID.randomUUID().toString(), now - day, 2, listOf(UUID.randomUUID().toString()))
            publish(fake, f, old, now - 60 * day)
            publish(fake, f, recent, now - day)

            val collected = maintenance(client, f).collect(f.slot)
            assertTrue("Listing must be complete", collected.inventory.listingComplete)
            assertEquals(setOf(old.snapshotId, recent.snapshotId), collected.inventory.commitIds)
            assertEquals(setOf(f.writer), collected.knownWriterIds)
            assertEquals(2, collected.verifiedSnapshots.size)
            assertEquals(2, collected.inventory.objects.size)
            val oldObject = BackupManifest.objects(old).single().id
            assertEquals("Server last-modified must reach the planner", now - 60 * day,
                collected.inventory.objects.single { it.objectId == oldObject }.lastModified)
        } finally { f.close() }
    }

    @Test fun missingManifestMarksInventoryIncompleteAndStopsCleanup() = withDav { fake, client ->
        val f = Fixture()
        try {
            val old = f.snapshot(UUID.randomUUID().toString(), now - 60 * day, 1, emptyList())
            val recent = f.snapshot(UUID.randomUUID().toString(), now - day, 2, emptyList())
            publish(fake, f, old, now)
            publish(fake, f, recent, now)
            fake.files.remove("${rootOf(f)}/snapshots/${old.snapshotId}.bin")

            val collected = maintenance(client, f).collect(f.slot)
            assertFalse("An unreadable manifest must mark the listing incomplete", collected.inventory.listingComplete)
            assertTrue(CloudCleanupPlanner.plan(collected.inventory, collected.verifiedSnapshots,
                collected.knownWriterIds, f.writer, emptyMap(), now) is CleanupDecision.Stopped)
        } finally { f.close() }
    }

    @Test fun failedObjectListingStopsCleanup() = withDav { fake, client ->
        val f = Fixture()
        try {
            publish(fake, f, f.snapshot(UUID.randomUUID().toString(), now - day, 1, emptyList()), now)
            fake.failListingFor = "/objects"
            val collected = maintenance(client, f).collect(f.slot)
            assertFalse(collected.inventory.listingComplete)
            assertTrue(CloudCleanupPlanner.plan(collected.inventory, collected.verifiedSnapshots,
                collected.knownWriterIds, f.writer, emptyMap(), now) is CleanupDecision.Stopped)
        } finally { f.close() }
    }

    @Test fun foreignIdentityOrAbsentDirectoryStopsInsteadOfProvisioning() = withDav { fake, client ->
        val f = Fixture()
        try {
            val foreign = RecoverySlots.create(VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey()), RecoveryKey.create())
            fake.directories += "/backup/pregnancy-journal"
            fake.directories += rootOf(f)
            fake.files["${rootOf(f)}/vault.json"] = RecoverySlots.encode(foreign)
            assertTrue("A different vault identity must stop cleanup",
                runCatching { maintenance(client, f).collect(f.slot) }.isFailure)

            val absent = Fixture()
            try {
                assertTrue(runCatching { maintenance(client, absent).collect(absent.slot) }.isFailure)
                assertTrue("Cleanup must never provision a backup location",
                    fake.directories.none { it.contains(absent.vault.id) })
            } finally { absent.close() }
        } finally { f.close() }
    }

    @Test fun executeDeletesRetiredCommitThenOrphanOnlyAfterGrace() = withDav { fake, client ->
        val f = Fixture()
        try {
            val oldObject = UUID.randomUUID().toString()
            val keptObject = UUID.randomUUID().toString()
            val old = f.snapshot(UUID.randomUUID().toString(), now - 60 * day, 1, listOf(oldObject))
            val recent = f.snapshot(UUID.randomUUID().toString(), now - day, 2, listOf(keptObject))
            publish(fake, f, old, now - 60 * day)
            publish(fake, f, recent, now - day)
            val root = rootOf(f)
            val manager = maintenance(client, f)
            val collected = manager.collect(f.slot)

            val firstPlan = planned(CloudCleanupPlanner.plan(collected.inventory, collected.verifiedSnapshots,
                collected.knownWriterIds, f.writer, emptyMap(), now))
            assertEquals(listOf(old.snapshotId), firstPlan.commitsToDelete)
            assertEquals(mapOf(oldObject to now), firstPlan.newlyOrphaned)
            assertTrue("Objects must survive the pass that retires their commit", firstPlan.objectsToDelete.isEmpty())
            manager.execute(f.slot, firstPlan)
            assertFalse("The retired commit must be gone", fake.files.containsKey("$root/commits/${old.snapshotId}.json"))
            assertTrue("Its object must still exist during grace", fake.files.containsKey("$root/objects/$oldObject.bin"))
            assertTrue(fake.files.containsKey("$root/objects/$keptObject.bin"))

            // A later pass, after grace, removes the orphan and nothing else.
            val secondPlan = planned(CloudCleanupPlanner.plan(collected.inventory, collected.verifiedSnapshots,
                collected.knownWriterIds, f.writer, firstPlan.newlyOrphaned, now + 31 * day))
            assertEquals(listOf(oldObject), secondPlan.objectsToDelete)
            manager.execute(f.slot, secondPlan)
            assertFalse(fake.files.containsKey("$root/objects/$oldObject.bin"))
            assertTrue("A referenced object must never be deleted", fake.files.containsKey("$root/objects/$keptObject.bin"))
            assertTrue(fake.files.containsKey("$root/commits/${recent.snapshotId}.json"))
        } finally { f.close() }
    }

    @Test fun otherDevicePresenceStopsCleanupEvenWhenListingSucceeds() = withDav { fake, client ->
        val f = Fixture()
        try {
            publish(fake, f, f.snapshot(UUID.randomUUID().toString(), now - 60 * day, 1, emptyList()), now)
            val collected = maintenance(client, f).collect(f.slot)
            val decision = CloudCleanupPlanner.plan(collected.inventory, collected.verifiedSnapshots,
                collected.knownWriterIds + UUID.randomUUID().toString(), f.writer, emptyMap(), now)
            assertTrue(decision is CleanupDecision.Stopped)
            assertTrue((decision as CleanupDecision.Stopped).reason.contains("其他设备"))
        } finally { f.close() }
    }
}
