package cn.yunji.journal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.*
import java.util.UUID

class IntakeRepositoryTest {
    private class TestClock(var value: Instant = Instant.parse("2026-10-18T01:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(value, zone)
        override fun instant(): Instant = value
    }
    private class Fixture : AutoCloseable {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "intake-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
        val db = JournalDatabase.open(base, File(root, "test.db"), VaultCrypto.randomKey())
        val dao = db.journalDao()
        val clock = TestClock()
        val repo = JournalRepository(db, clock)
        val service = repo.intakes
        val importer = DocumentImporter(context, db, VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey()), clock)
        lateinit var pregnancy: String
        suspend fun create(frequency: String = "DAILY_TIMES"): String {
            pregnancy = repo.savePregnancy(null, "合成服药档案", null, null, "", "")
            return repo.medications.save(MedicationForm(pregnancyId = pregnancy, name = "合成测试药品", dose = "0.125", unit = "片", frequency = frequency, times = "08:00,20:00", startDate = "2026-10-18", zoneId = "UTC"))
        }
        suspend fun day(zone: ZoneId = ZoneOffset.UTC) { service.ensureDay(pregnancy, LocalDate.parse("2026-10-18"), zone) }
        suspend fun morning() = dao.occurrencesForDate(pregnancy, "2026-10-18").first { it.localTime == "08:00" }
        suspend fun form(point: MedicationOccurrenceEntity) = service.form(pregnancy, occurrenceId = point.id).copy(zoneId = "UTC", actualDate = "2026-10-18", actualTime = "09:00")
        override fun close() { db.close(); root.deleteRecursively() }
    }

    @Test(timeout = 30_000) fun parallelActionsAndStaleCallbacksCannotCreateDuplicatesOrOverwriteTaken() = runBlocking {
        Fixture().use { f ->
            f.create(); f.clock.value = Instant.parse("2026-10-18T09:00:00Z"); f.day()
            val point = f.morning(); val input = f.form(point)
            val ids = coroutineScope { (1..3).map { async(Dispatchers.IO) { f.service.save(input.copy(requestId = UUID.randomUUID().toString())) } }.awaitAll() }
            assertEquals(1, ids.distinct().size); assertEquals(1, f.dao.allIntakes().size)
            val original = requireNotNull(f.dao.intake(ids.first()))
            assertEquals("TAKEN", original.status)
            assertEquals(ids.first(), f.service.save(input.copy(requestId = UUID.randomUUID().toString(), status = "SKIPPED")))
            assertEquals(original, f.dao.intake(original.id))
            val update = f.service.form(f.pregnancy, point.id, original.id).copy(zoneId = "UTC", actualTime = "08:30", dose = "0.25", note = "修改合成记录")
            f.service.save(update)
            assertEquals(2L, f.dao.intake(original.id)?.revision)
            assertEquals("0.25", f.dao.intake(original.id)?.dose)
            try { f.service.save(update.copy(note = "旧编辑覆盖")); fail("Stale edit accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun versionsCancelOnlyFutureUnrecordedPointsAndOldDuePointRemainsRecordable() = runBlocking {
        Fixture().use { f ->
            val first = f.create(); f.clock.value = Instant.parse("2026-10-18T09:00:00Z"); f.day()
            val evening = f.dao.occurrencesForDate(f.pregnancy, "2026-10-18").first { it.localTime == "20:00" }
            f.clock.value = Instant.parse("2026-10-18T10:00:00Z"); f.repo.medications.changeStatus(first, "PAUSED"); f.day()
            assertEquals("CANCELLED", f.dao.occurrence(evening.id)?.state)
            try { f.service.save(f.form(evening)); fail("Old future callback accepted") } catch (_: IllegalArgumentException) { }
            val morning = f.morning(); val record = f.service.save(f.form(morning))
            assertEquals(first, f.dao.intake(record)?.planVersionId)
            val paused = f.repo.medications.plans(f.pregnancy).first().single()
            f.clock.value = Instant.parse("2026-10-18T15:00:00Z"); f.repo.medications.changeStatus(paused.id, "ACTIVE"); f.day()
            val active = f.dao.occurrencesForDate(f.pregnancy, "2026-10-18").filter { it.state == "ACTIVE" }
            assertEquals(2, active.size)
            assertEquals(first, f.dao.intake(record)?.planVersionId)
            assertTrue(active.first { it.localTime == "20:00" }.planVersionId != first)
        }
    }

    @Test fun timeZoneChangePreservesPastBasisAndRecalculatesOnlyFuture() = runBlocking {
        Fixture().use { f ->
            f.create(); f.clock.value = Instant.parse("2026-10-18T09:00:00Z"); f.day()
            val before = f.morning()
            f.day(ZoneId.of("Asia/Tokyo"))
            assertEquals(before, f.dao.occurrence(before.id))
            val evening = f.dao.occurrencesForDate(f.pregnancy, "2026-10-18").first { it.localTime == "20:00" }
            assertEquals("Asia/Tokyo", evening.zoneId)
            assertEquals(Instant.parse("2026-10-18T11:00:00Z").toEpochMilli(), evening.scheduledAt)
            f.day(ZoneId.of("Asia/Tokyo")); assertEquals(2, f.dao.allOccurrences().size)
        }
    }

    @Test fun alreadyRecordedOldVersionRemainsButDoesNotSuppressNewVersionRule() = runBlocking {
        Fixture().use { f ->
            val first = f.create(); f.clock.value = Instant.parse("2026-10-18T09:00:00Z"); f.day()
            val oldEvening = f.dao.occurrencesForDate(f.pregnancy, "2026-10-18").first { it.localTime == "20:00" }
            val recorded = f.service.save(f.form(oldEvening))
            val before = f.dao.intake(recorded)
            f.clock.value = Instant.parse("2026-10-18T10:00:00Z")
            val updated = f.repo.medications.save(f.repo.medications.form(f.pregnancy, first).copy(dose = "0.25"))
            f.day()
            val evening = f.dao.occurrencesForDate(f.pregnancy, "2026-10-18").filter { it.localTime == "20:00" && it.state == "ACTIVE" }
            assertEquals(setOf(first, updated), evening.map { it.planVersionId }.toSet())
            assertEquals(before, f.dao.intake(recorded))
            assertNull(f.dao.intakeByOccurrence(evening.first { it.planVersionId == updated }.id))
        }
    }

    @Test fun manualPastRecordAllowsAsNeededButRejectsFutureAndKeepsActionTokenIdempotent() = runBlocking {
        Fixture().use { f ->
            f.create("AS_NEEDED"); f.clock.value = Instant.parse("2026-10-18T09:00:00Z"); f.day()
            assertTrue(f.dao.allOccurrences().isEmpty())
            val form = f.service.form(f.pregnancy).copy(zoneId = "UTC", actualDate = "2026-10-17", actualTime = "22:30", dose = "0.125", unit = "片")
            val id = f.service.save(form)
            assertEquals("2026-10-17", f.dao.intake(id)?.eventDate)
            assertNull(f.dao.intake(id)?.sourceOccurrenceId)
            assertEquals(id, f.service.save(form.copy(note = "重复回调不能覆写")))
            assertEquals("", f.dao.intake(id)?.note)
            try { f.service.save(form.copy(requestId = UUID.randomUUID().toString(), actualDate = "2026-10-19")); fail("Future actual time accepted") } catch (_: IllegalArgumentException) { }
            try { f.service.save(form.copy(requestId = UUID.randomUUID().toString(), status = "SKIPPED")); fail("Unscheduled skip accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun independentDeleteRestoreConflictAndParentPurgeKeepRecordsConsistent() = runBlocking {
        Fixture().use { f ->
            f.create(); f.clock.value = Instant.parse("2026-10-18T09:00:00Z"); f.day()
            val point = f.morning(); val original = f.service.save(f.form(point))
            val deleted = f.importer.trashRepository.delete("INTAKE", original, schedule = false)
            assertNull(f.dao.intake(original)?.occurrenceId)
            val replacement = f.service.save(f.form(point).copy(requestId = UUID.randomUUID().toString()))
            try { f.importer.trashRepository.restore(deleted); fail("Conflicting record restored") } catch (_: IllegalArgumentException) { }
            val secondBatch = f.importer.trashRepository.delete("INTAKE", replacement, schedule = false)
            f.importer.trashRepository.restore(deleted)
            assertEquals(original, f.dao.intakeByOccurrence(point.id)?.id)
            val batch = f.importer.trashRepository.delete("PREGNANCY", f.pregnancy, schedule = false)
            f.importer.trashRepository.restore(batch)
            assertNull(f.dao.intake(original)?.deletedAt)
            assertNotNull(f.dao.intake(replacement)?.deletedAt)
            assertEquals("DELETED", f.dao.deletionBatch(secondBatch)?.state)
            f.repo.saveDraft("intake", "new:${f.pregnancy}:manual", Json.encodeToString(f.service.form(f.pregnancy)))
            val purge = f.importer.trashRepository.delete("PREGNANCY", f.pregnancy, schedule = false)
            f.importer.trashRepository.purge(purge, schedule = false); f.importer.trashRepository.resume()
            assertTrue(f.dao.allIntakes().isEmpty()); assertTrue(f.dao.allOccurrences().isEmpty()); assertTrue(f.dao.allMedicationPlans().isEmpty())
            assertTrue(f.dao.allDrafts().isEmpty())
            assertEquals("PURGED", f.dao.deletionBatch(secondBatch)?.state)
        }
    }

    @Test fun actualDstGapIsRejectedAndOverlapOffsetCanBeSelectedAndPreserved() = runBlocking {
        Fixture().use { f ->
            f.create("AS_NEEDED"); f.clock.value = Instant.parse("2026-11-02T09:00:00Z")
            val form = f.service.form(f.pregnancy).copy(zoneId = "America/New_York", actualDate = "2026-11-01", actualTime = "01:30", offsetSeconds = -5 * 3600)
            val id = f.service.save(form)
            assertEquals(Instant.parse("2026-11-01T06:30:00Z").toEpochMilli(), f.dao.intake(id)?.actualAt)
            f.service.save(f.service.form(f.pregnancy, recordId = id).copy(note = "只修改合成备注"))
            assertEquals(Instant.parse("2026-11-01T06:30:00Z").toEpochMilli(), f.dao.intake(id)?.actualAt)
            f.service.save(f.service.form(f.pregnancy, recordId = id).copy(offsetSeconds = -4 * 3600))
            assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), f.dao.intake(id)?.actualAt)
            try { f.service.save(form.copy(requestId = UUID.randomUUID().toString(), actualDate = "2026-03-08", actualTime = "02:30")); fail("Nonexistent actual time normalized silently") } catch (_: IllegalArgumentException) { }
            val other = f.repo.savePregnancy(null, "另一合成孕期", null, null, "", "")
            try { f.service.save(form.copy(requestId = UUID.randomUUID().toString(), pregnancyId = other)); fail("Cross-pregnancy intake accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
