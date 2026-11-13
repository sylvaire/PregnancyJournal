package cn.yunji.journal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.data.*
import cn.yunji.journal.domain.MeasurementRules
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.*
import java.util.UUID

class MeasurementRepositoryTest {
    private class Fixture: AutoCloseable {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val root=File(base.cacheDir,"measurement-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(base){override fun getNoBackupFilesDir()=root}
        val db=JournalDatabase.open(base,File(root,"test.db"),VaultCrypto.randomKey())
        val dao=db.journalDao()
        val clock=Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"),ZoneOffset.UTC)
        val repo=JournalRepository(db,clock)
        val service=repo.measurements
        val importer=DocumentImporter(context,db,VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey()),clock)
        suspend fun pregnancy()=repo.savePregnancy(null,"合成健康档案",null,null,"","")
        suspend fun form(id:String)=service.form(id).copy(date="2026-10-18",time="10:00:00",zoneId="UTC",values=mapOf("value" to "60.125000"))
        override fun close(){db.close();root.deleteRecursively()}
    }
    @Test(timeout=30_000) fun sameTimeReadingsAreDistinctButRetriesAreIdempotentAndEditsGuardRevision()=runBlocking {
        Fixture().use {f ->
            val p=f.pregnancy(); val input=f.form(p)
            val ids=coroutineScope {(1..3).map {async(Dispatchers.IO){f.service.save(input)}}.awaitAll()}
            assertEquals(1,ids.distinct().size)
            val other=f.service.save(input.copy(requestId=UUID.randomUUID().toString()))
            assertNotEquals(ids.first(),other); assertEquals(2,f.dao.allMeasurements().size)
            assertEquals("60.125",f.dao.valuesForMeasurement(other).single().decimalValue)
            val edit=f.service.form(p,other).copy(values=mapOf("value" to "61.000001"))
            f.service.save(edit)
            try {f.service.save(edit);fail("Stale edit accepted")}catch(_:IllegalArgumentException){}
            assertEquals("61.000001",f.dao.valuesForMeasurement(other).single().decimalValue)
            assertEquals(Instant.parse("2026-10-18T10:00:00Z").toEpochMilli(),f.dao.measurement(other)?.measuredAt)
            val precise=f.dao.measurement(other)!!
            f.dao.updateMeasurement(precise.copy(measuredAt=precise.measuredAt+123))
            f.service.save(f.service.form(p,other).copy(note="只修改备注"))
            assertEquals(precise.measuredAt+123,f.dao.measurement(other)?.measuredAt)
        }
    }
    @Test(timeout=30_000) fun definitionVersionsKeepOriginalUnitAndGlucoseContextsDoNotMix()=runBlocking {
        Fixture().use {f ->
            val p=f.pregnancy();val input=f.form(p)
            val d1=f.service.saveDefinition(p,"合成数值","单位一")
            val r1=f.service.save(input.copy(definitionId=d1))
            val d2=f.service.saveDefinition(p,"合成数值","单位二",d1)
            f.service.save(input.copy(requestId=UUID.randomUUID().toString(),definitionId=d2))
            assertEquals(d1,f.dao.measurement(r1)?.definitionId)
            try {f.service.save(f.service.form(p,r1).copy(definitionId=d2));fail("Rewrote historical unit")}catch(_:IllegalArgumentException){}
            val glucose=f.dao.allMetricDefinitions().single {it.kind=="GLUCOSE"}
            listOf("FASTING" to "", "POST_MEAL" to "60", "POST_MEAL" to "120").forEach {(scenario,minutes)-> f.service.save(input.copy(requestId=UUID.randomUUID().toString(),definitionId=glucose.id,scenario=scenario,minutes=minutes)) }
            val groups=MeasurementRules.groups(f.dao.allMetricDefinitions(),f.dao.allMeasurements(),f.service.values(p).first())
            assertEquals(5,groups.size)
            val pressure=f.dao.allMetricDefinitions().single {it.kind=="PRESSURE"}
            try {f.service.save(input.copy(requestId=UUID.randomUUID().toString(),definitionId=pressure.id,values=mapOf("systolic" to "120")));fail("Incomplete pressure accepted")}catch(_:IllegalArgumentException){}
            val bp=f.service.save(input.copy(requestId=UUID.randomUUID().toString(),definitionId=pressure.id,values=mapOf("systolic" to "120","diastolic" to "80","pulse" to "72")))
            assertEquals(3,f.dao.valuesForMeasurement(bp).size)
        }
    }
    @Test(timeout=30_000) fun deleteRestoreAndParentPurgeRetainIndependentOwnershipAndClearDrafts()=runBlocking {
        Fixture().use {f ->
            val p=f.pregnancy();val input=f.form(p);val first=f.service.save(input)
            val second=f.service.save(input.copy(requestId=UUID.randomUUID().toString()))
            val trash=TrashRepository(f.db,f.importer,f.clock)
            val own=trash.delete("MEASUREMENT",first,schedule=false)
            assertEquals("MEASUREMENT",f.dao.trashBatches().first().single().kind)
            assertEquals(1,trash.impact("PREGNANCY",p).measurements)
            val parent=trash.delete("PREGNANCY",p,schedule=false)
            try {trash.restore(own);fail("Restored into deleted parent")}catch(_:IllegalArgumentException){}
            trash.restore(parent)
            assertNotNull(f.dao.measurement(first)?.deletedAt);assertNull(f.dao.measurement(second)?.deletedAt)
            trash.restore(own);assertNull(f.dao.measurement(first)?.deletedAt)
            val batch=trash.delete("MEASUREMENT",first,schedule=false);trash.purge(batch,schedule=false);trash.resume()
            assertTrue(f.dao.valuesForMeasurement(first).isEmpty());assertEquals("",f.dao.measurement(first)?.note)
            f.repo.saveDraft("measurement","new:$p", "{\"pregnancyId\":\"$p\"}")
            val parent2=trash.delete("PREGNANCY",p,schedule=false);trash.purge(parent2,schedule=false);trash.resume()
            assertTrue(f.dao.allMeasurements().isEmpty());assertTrue(f.dao.allMetricDefinitions().isEmpty())
            assertNull(f.repo.draft("measurement","new:$p"))
            try {f.repo.saveDraft("measurement","new:$p","{\"pregnancyId\":\"$p\"}");fail("Late draft accepted")}catch(_:IllegalArgumentException){}
        }
    }
    @Test(timeout=30_000) fun actualDstOffsetsFutureTimeAndCrossPregnancyAreValidated()=runBlocking {
        Fixture().use {f ->
            val p=f.pregnancy(); val input=f.form(p).copy(zoneId="America/New_York",date="2026-03-08",time="02:30:00")
            try {f.service.save(input);fail("DST gap accepted")}catch(_:IllegalArgumentException){}
            val overlap=input.copy(date="2025-11-02",time="01:30:00",offsetSeconds=-14400)
            val first=f.service.save(overlap)
            val second=f.service.save(overlap.copy(requestId=UUID.randomUUID().toString(),offsetSeconds=-18000))
            assertEquals(3600000,f.dao.measurement(second)!!.measuredAt-f.dao.measurement(first)!!.measuredAt)
            assertEquals(first,f.service.save(overlap.copy(date="2026-10-19",time="10:00")))
            try {f.service.save(input.copy(requestId=UUID.randomUUID().toString(),date="2026-10-19",time="10:00"));fail("Future reading accepted")}catch(_:IllegalArgumentException){}
            val another=f.pregnancy()
            try {f.service.save(f.form(another).copy(definitionId=input.definitionId));fail("Cross pregnancy accepted")}catch(_:IllegalArgumentException){}
        }
    }
}
