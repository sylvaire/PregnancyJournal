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

/** Isolated encrypted databases and synthetic names only. No medical advice or real user data. */
class MedicationRepositoryTest {
    private class Fixture : AutoCloseable {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "medication-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
        val db = JournalDatabase.open(base, File(root, "test.db"), VaultCrypto.randomKey())
        val dao = db.journalDao()
        val clock = Clock.fixed(Instant.parse("2026-10-18T01:00:00Z"), ZoneOffset.UTC)
        val repo = JournalRepository(db, clock)
        val meds = repo.medications
        val importer = DocumentImporter(context, db, VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey()), clock)
        suspend fun pregnancy() = repo.savePregnancy(null, "合成用药档案", null, null, "", "")
        fun form(id: String) = MedicationForm(pregnancyId = id, name = "合成测试药品", specification = "测试规格", dose = "0.125", unit = "片", times = "08:00", startDate = "2026-10-18", source = "合成来源", instructions = "测试原文")
        override fun close() { db.close(); root.deleteRecursively() }
    }

    @Test fun editingAndStatusChangesAppendVersionsAndPreserveOriginals() = runBlocking {
        Fixture().use { f ->
            val pregnancy = f.pregnancy()
            val id = f.meds.save(f.form(pregnancy))
            val original = requireNotNull(f.dao.medicationPlan(id))
            val second = f.meds.save(f.meds.form(pregnancy, id).copy(dose = "0.25", instructions = "修改原文"))
            assertEquals(original, f.dao.medicationPlan(id))
            assertEquals(2, f.dao.medicationPlan(second)?.version)
            assertTrue(requireNotNull(f.dao.medicationPlan(second)).effectiveAt > original.effectiveAt)
            try { f.meds.save(f.meds.form(pregnancy, id)); fail("Stale edit accepted") } catch (_: IllegalArgumentException) { }
            f.meds.changeStatus(second, "PAUSED")
            val paused = f.meds.plans(pregnancy).first().single()
            assertEquals(3, paused.version); assertEquals("PAUSED", paused.status)
            f.meds.changeStatus(paused.id, "ACTIVE")
            val active = f.meds.plans(pregnancy).first().single()
            f.meds.changeStatus(active.id, "ENDED")
            val ended = f.meds.plans(pregnancy).first().single()
            assertEquals(5, ended.version)
            assertEquals(5, f.meds.history(original.logicalPlanId).first().size)
            try { f.meds.changeStatus(ended.id, "ACTIVE"); fail("Ended plan resumed") } catch (_: IllegalArgumentException) { }
            assertEquals(original, f.dao.medicationPlan(id))
        }
    }

    @Test(timeout = 30_000) fun concurrentEditsUseOneSuccessAndPregnanciesCannotShareDrugs() = runBlocking {
        Fixture().use { f ->
            val pregnancy = f.pregnancy()
            val id = f.meds.save(f.form(pregnancy))
            val input = f.meds.form(pregnancy, id)
            val successes = coroutineScope { listOf("0.25", "0.5").map { amount -> async(Dispatchers.IO) { runCatching { f.meds.save(input.copy(dose = amount)) }.isSuccess } }.awaitAll() }
            assertEquals(1, successes.count { it })
            assertEquals(2, f.dao.allMedicationPlans().size)
            val other = f.pregnancy()
            try { f.meds.save(f.form(other).copy(medicationId = input.medicationId)); fail("Cross-pregnancy drug accepted") } catch (_: IllegalArgumentException) { }
            assertTrue(f.meds.plans(other).first().isEmpty())
        }
    }

    @Test fun drugInfoEditsKeepOldPlanSnapshotsAndRejectStaleEditorsAndForms() = runBlocking {
        Fixture().use { f ->
            val pregnancy = f.pregnancy()
            val planId = f.meds.save(f.form(pregnancy).copy(specification = "原规格", dosageForm = "片剂"))
            val original = requireNotNull(f.dao.medicationPlan(planId))
            val drugId = original.medicationId
            assertEquals("合成测试药品", original.medicationName)
            assertEquals("原规格", original.medicationSpecification)
            assertEquals("片剂", original.medicationDosageForm)
            val stalePlanForm = f.meds.form(pregnancy, planId)
            val staleDrugForm = f.meds.drugForm(pregnancy, drugId)
            assertEquals(drugId, f.meds.saveDrug(staleDrugForm.copy(name = " 合成改名药品 ", specification = "新规格", dosageForm = "胶囊")))
            assertEquals("合成改名药品", f.dao.medication(drugId)?.name)
            assertEquals(2L, f.dao.medication(drugId)?.revision)
            assertEquals(original, f.dao.medicationPlan(planId))
            try { f.meds.saveDrug(staleDrugForm.copy(name = "旧编辑覆盖")); fail("Stale drug edit accepted") } catch (_: IllegalArgumentException) { }
            try { f.meds.save(stalePlanForm.copy(dose = "0.25")); fail("Stale plan form accepted after drug edit") } catch (_: IllegalArgumentException) { }
            assertEquals(1, f.dao.allMedicationPlans().size)
            val next = f.meds.save(f.meds.form(pregnancy, planId).copy(dose = "0.25"))
            val second = requireNotNull(f.dao.medicationPlan(next))
            assertEquals("合成改名药品", second.medicationName)
            assertEquals("新规格", second.medicationSpecification)
            assertEquals("胶囊", second.medicationDosageForm)
            assertEquals(original, f.dao.medicationPlan(planId))
            assertEquals(2, f.meds.history(original.logicalPlanId).first().size)
        }
    }

    @Test fun medicationBatchRestoresItsOwnRecordsAndStaysProtectedWhileReferenced() = runBlocking {
        Fixture().use { f ->
            val pregnancy = f.pregnancy()
            val planId = f.meds.save(f.form(pregnancy).copy(specification = "原规格"))
            val drugId = requireNotNull(f.dao.medicationPlan(planId)).medicationId
            f.repo.saveDraft("drug", drugId, Json.encodeToString(f.meds.drugForm(pregnancy, drugId).copy(name = "尚未保存的改名")))
            val batch = f.importer.trashRepository.delete("MEDICATION", drugId, schedule = false)
            assertNotNull(f.dao.medication(drugId)?.deletedAt)
            assertNotNull(f.dao.medicationPlan(planId)?.deletedAt)
            assertTrue(f.meds.medications(pregnancy).first().isEmpty())
            try { f.repo.saveDraft("drug", drugId, Json.encodeToString(DrugForm(id = drugId, pregnancyId = pregnancy, name = "迟到草稿"))); fail("Deleted drug accepted a late draft") } catch (_: IllegalArgumentException) { }
            val row = f.dao.trashBatches().first().single { it.id == batch }
            assertEquals("MEDICATION", row.kind); assertEquals(1, row.planCount)
            assertNotNull(f.importer.trashRepository.purgeReason(row))
            f.importer.trashRepository.restore(batch)
            assertNull(f.dao.medication(drugId)?.deletedAt)
            assertNull(f.dao.medicationPlan(planId)?.deletedAt)
            assertEquals("合成测试药品", f.dao.medicationPlan(planId)?.medicationName)
            val again = f.importer.trashRepository.delete("MEDICATION", drugId, schedule = false)
            val againRow = f.dao.trashBatches().first().single { it.id == again }
            try { f.importer.trashRepository.purge(again, schedule = false); fail("Referenced drug was purged independently") } catch (_: IllegalArgumentException) { }
            assertEquals("DELETED", f.dao.deletionBatch(again)?.state)
            assertNotNull(f.importer.trashRepository.purgeReason(againRow))
            val pregnancyBatch = f.importer.trashRepository.delete("PREGNANCY", pregnancy, schedule = false)
            f.importer.trashRepository.purge(pregnancyBatch, schedule = false)
            f.importer.trashRepository.resume()
            assertTrue(f.dao.allMedications().isEmpty()); assertTrue(f.dao.allMedicationPlans().isEmpty())
            assertNull(f.dao.draft("drug", drugId))
            assertEquals("PURGED", f.dao.deletionBatch(again)?.state)
        }
    }

    @Test(timeout = 30_000) fun medicationImpactCountsItsOwnRecords() = runBlocking {
        Fixture().use { f ->
            val pregnancy = f.pregnancy()
            val planId = f.meds.save(f.form(pregnancy))
            val drugId = requireNotNull(f.dao.medicationPlan(planId)).medicationId
            val impact = f.importer.trashRepository.impact("MEDICATION", drugId)
            assertEquals(1, impact.plans); assertEquals(0, impact.intakes); assertEquals(1, impact.medications)
        }
    }

    @Test fun batchRestoreKeepsPlanVersionsAndPurgeClearsAllMedicationDataAndDrafts() = runBlocking {
        Fixture().use { f ->
            val pregnancy = f.pregnancy()
            val id = f.meds.save(f.form(pregnancy))
            val second = f.meds.save(f.meds.form(pregnancy, id).copy(dose = "0.25"))
            val before = f.dao.allMedicationPlans().map { it.copy(deletedAt = null, deletionBatchId = null, revision = 1) }.toSet()
            f.repo.saveDraft("medicationPlan", second, Json.encodeToString(f.meds.form(pregnancy, second)))
            val batch = f.importer.trashRepository.delete("PREGNANCY", pregnancy, schedule = false)
            assertEquals(1, f.dao.allMedications().count { it.deletionBatchId == batch })
            assertEquals(2, f.dao.allMedicationPlans().count { it.deletionBatchId == batch })
            assertTrue(f.meds.plans(pregnancy).first().isEmpty())
            try { f.meds.save(f.form(pregnancy)); fail("Deleted parent accepted new plan") } catch (_: IllegalArgumentException) { }
            f.importer.trashRepository.restore(batch)
            assertEquals(before, f.dao.allMedicationPlans().map { it.copy(deletedAt = null, deletionBatchId = null, revision = 1) }.toSet())
            assertEquals(2, f.meds.plans(pregnancy).first().single().version)
            val again = f.importer.trashRepository.delete("PREGNANCY", pregnancy, schedule = false)
            f.importer.trashRepository.purge(again, schedule = false); f.importer.trashRepository.resume()
            assertTrue(f.dao.allMedicationPlans().isEmpty()); assertTrue(f.dao.allMedications().isEmpty())
            assertNull(f.dao.draft("medicationPlan", second))
            try { f.repo.saveDraft("medicationPlan", second, Json.encodeToString(f.form(pregnancy))); fail("Purged parent draft accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
