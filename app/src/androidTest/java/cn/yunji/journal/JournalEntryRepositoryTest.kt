package cn.yunji.journal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.data.*
import cn.yunji.journal.domain.TimelineRules
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.*
import java.util.UUID

class JournalEntryRepositoryTest {
    private class Fixture: AutoCloseable {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val root=File(base.cacheDir,"journal-entry-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(base){override fun getNoBackupFilesDir()=root}
        val db=JournalDatabase.open(base,File(root,"test.db"),VaultCrypto.randomKey())
        val dao=db.journalDao()
        val clock=Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"),ZoneOffset.UTC)
        val repo=JournalRepository(db,clock)
        val service=repo.journalEntries
        val importer=DocumentImporter(context,db,VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey()),clock)
        suspend fun pregnancy()=repo.savePregnancy(null,"合成日常档案",null,null,"","")
        suspend fun form(p:String)=service.form(p,null).copy(date="2026-10-10",zoneId="UTC",text="合成日常记录",symptom="合成症状",mood="平静")
        suspend fun visit(p:String)=repo.saveVisit(null,p,LocalDate.parse("2026-10-10"),LocalTime.parse("09:00"),"测试医院","测试产检","")
        suspend fun document(visit:String):String {
            val id=UUID.randomUUID().toString()
            db.journalWrite {
                dao.putImportJob(ImportJobEntity(id,visit,"","合成报告",clock.millis(),"READY",phase="DONE"))
                dao.putAttachment(AttachmentEntity(id,visit,"合成报告","image/png",1,33,"plain","cipher",null,null,null,null,null,clock.millis()))
            };return id
        }
        override fun close(){db.close();root.deleteRecursively()}
    }
    @Test(timeout=30_000) fun concurrentRetriesKeepOneEntryAndUnknownTimeSurvivesRevisionEdits()=runBlocking {
        Fixture().use {f ->
            val p=f.pregnancy();val input=f.form(p)
            val ids=coroutineScope {(1..3).map {async(Dispatchers.IO){f.service.save(input)}}.awaitAll()}
            assertEquals(1,ids.distinct().size)
            assertNull(f.dao.journalEntry(ids.first())?.entryTime);assertNull(f.dao.journalEntry(ids.first())?.offsetSeconds)
            assertEquals(ids.first(),f.service.save(input.copy(text="重复请求不能覆写")))
            assertEquals(input.text,f.dao.journalEntry(ids.first())?.text)
            val edit=f.service.form(p,ids.first()).copy(text="修订后的合成备注")
            f.service.save(edit)
            try {f.service.save(edit);fail("Stale edit accepted")}catch(_:IllegalArgumentException){}
            val events=TimelineRules.build(f.service.timeline(p).first()!!,ZoneOffset.UTC)
            assertEquals("2026-10-10",events.single().date);assertNull(events.single().time)
            assertEquals("修订后的合成备注",events.single().title)
            assertNotEquals(ids.first(),f.service.save(input.copy(requestId=UUID.randomUUID().toString())))
        }
    }
    @Test(timeout=30_000) fun datesDstOffsetsBoundsAndPregnancyIsolationAreValidated()=runBlocking {
        Fixture().use {f ->
            val p=f.pregnancy();val input=f.form(p)
            suspend fun rejected(form:JournalEntryForm) {try {f.service.save(form);fail("Invalid entry accepted")}catch(_:IllegalArgumentException){}}
            rejected(input.copy(text="",symptom="",mood=""))
            rejected(input.copy(text="x".repeat(10001)))
            rejected(input.copy(date="2026-10-19"))
            rejected(input.copy(time="25:99"))
            rejected(input.copy(date="2026-10-18",time="13:00"))
            rejected(input.copy(date="2026-03-08",time="02:30",zoneId="America/New_York"))
            val ambiguous=input.copy(date="2025-11-02",time="01:30",zoneId="America/New_York",offsetSeconds=-14400)
            val first=f.service.save(ambiguous)
            val second=f.service.save(ambiguous.copy(requestId=UUID.randomUUID().toString(),offsetSeconds=-18000))
            val events=TimelineRules.build(f.service.timeline(p).first()!!,ZoneOffset.UTC)
            assertEquals(3600000L,events.first {it.id==second}.instant!!-events.first {it.id==first}.instant!!)
            val other=f.pregnancy()
            rejected(f.service.form(p,first).copy(pregnancyId=other))
            assertTrue(TimelineRules.build(f.service.timeline(other).first()!!,ZoneOffset.UTC).isEmpty())
        }
    }
    @Test(timeout=30_000) fun independentTrashOwnershipParentPurgeAndLateDraftProtectionAreConsistent()=runBlocking {
        Fixture().use {f ->
            val p=f.pregnancy();val input=f.form(p);val first=f.service.save(input)
            val second=f.service.save(input.copy(requestId=UUID.randomUUID().toString()))
            val trash=TrashRepository(f.db,f.importer,f.clock)
            val own=trash.delete("JOURNAL",first,schedule=false)
            assertEquals("JOURNAL",f.dao.trashBatches().first().single().kind)
            assertEquals(1,trash.impact("PREGNANCY",p).entries)
            val parent=trash.delete("PREGNANCY",p,schedule=false)
            assertNull(f.service.timeline(p).first())
            try {trash.restore(own);fail("Restored into deleted parent")}catch(_:IllegalArgumentException){}
            trash.restore(parent)
            assertNotNull(f.dao.journalEntry(first)?.deletedAt);assertNull(f.dao.journalEntry(second)?.deletedAt)
            trash.restore(own)
            val permanent=trash.delete("JOURNAL",first,schedule=false);trash.purge(permanent,schedule=false);trash.resume()
            val scrubbed=f.dao.journalEntry(first)!!
            assertEquals("",scrubbed.text);assertEquals("",scrubbed.symptom);assertEquals("",scrubbed.mood);assertEquals(0L,scrubbed.createdAt)
            f.repo.saveDraft("journalEntry","new:$p","{\"pregnancyId\":\"$p\"}")
            val whole=trash.delete("PREGNANCY",p,schedule=false);trash.purge(whole,schedule=false);trash.resume()
            assertTrue(f.dao.allJournalEntries().isEmpty());assertNull(f.repo.draft("journalEntry","new:$p"))
            try {f.repo.saveDraft("journalEntry","new:$p","{\"pregnancyId\":\"$p\"}");fail("Late draft accepted")}catch(_:IllegalArgumentException){}
        }
    }
    @Test(timeout=30_000) fun snapshotAggregatesAllSixSourcesAndReflectsSourceChangesAndRetainedDocuments()=runBlocking {
        Fixture().use {f ->
            val p=f.pregnancy();val v=f.visit(p);f.repo.completeVisit(v,LocalDate.parse("2026-10-11"),"合成医嘱")
            val doc=f.document(v);f.importer.updateMetadata(doc,"合成报告","LAB","2026-10-12","测试医院","复查说明")
            f.service.save(f.form(p))
            val mf=f.repo.measurements.form(p).copy(date="2026-10-13",time="08:00",zoneId="UTC",values=mapOf("value" to "60.125"))
            val measurement=f.repo.measurements.save(mf)
            val plan=f.repo.medications.save(MedicationForm(pregnancyId=p,name="合成药品",dose="0.125",unit="片",frequency="AS_NEEDED",startDate="2026-10-10",instructions="合成原文",zoneId="UTC"))
            val intake=f.repo.intakes.form(p).copy(actualDate="2026-10-14",actualTime="08:00",zoneId="UTC",dose="0.25")
            f.repo.intakes.save(intake)
            val initial=f.service.timeline(p).first()!!
            val events=TimelineRules.build(initial,ZoneOffset.UTC)
            assertEquals(setOf("VISIT","DOCUMENT","PLAN","INTAKE","MEASUREMENT","JOURNAL"),events.map {it.type}.toSet())
            assertEquals("2026-10-11",events.single {it.type=="VISIT"}.date);assertNull(events.single {it.type=="VISIT"}.time)
            assertEquals("2026-10-12",events.single {it.type=="DOCUMENT"}.date)
            assertEquals(plan,initial.intakes.single().planVersionId)
            f.repo.measurements.save(f.repo.measurements.form(p,measurement).copy(values=mapOf("value" to "61.000001")))
            val updated=f.service.timeline(p).first {it?.measurements?.singleOrNull()?.values?.singleOrNull()?.decimalValue=="61.000001"}!!
            assertTrue(TimelineRules.build(updated,ZoneOffset.UTC).single {it.type=="MEASUREMENT"}.summary.contains("61.000001"))
            TrashRepository(f.db,f.importer,f.clock).delete("VISIT",v,schedule=false)
            val after=TimelineRules.build(f.service.timeline(p).first()!!,ZoneOffset.UTC)
            assertFalse(after.any {it.type=="VISIT"});assertTrue(after.single {it.type=="DOCUMENT"}.summary.contains("原产检已删除"))
        }
    }
    @Test(timeout=30_000) fun documentMetadataKeepsOriginalIntegrityAndRejectsWritesAfterParentDeletion()=runBlocking {
        Fixture().use {f ->
            val p=f.pregnancy();val doc=f.document(f.visit(p));val original=f.dao.attachment(doc)!!
            f.importer.updateMetadata(doc,"改名合成报告","LAB","2026-10-10","测试医院","复查说明")
            val edited=f.dao.attachment(doc)!!
            assertEquals(original.plainSha256,edited.plainSha256);assertEquals(original.cipherSha256,edited.cipherSha256)
            assertEquals("2026-10-10",edited.documentDate);assertEquals("复查说明",edited.note)
            try {f.importer.updateMetadata(doc,"旧编辑","OTHER",expectedRevision=original.revision);fail("Stale metadata accepted")}catch(_:IllegalArgumentException){}
            f.importer.updateMetadata(doc,"再次改名","OTHER")
            assertEquals("2026-10-10",f.dao.attachment(doc)?.documentDate)
            f.importer.updateMetadata(doc,"清空可选字段","OTHER","","","")
            assertNull(f.dao.attachment(doc)?.documentDate)
            assertEquals("导入日期，报告日期未填写",TimelineRules.build(f.service.timeline(p).first()!!,ZoneOffset.UTC).first {it.type=="DOCUMENT"}.basis)
            try {f.importer.updateMetadata(doc,"坏日期","LAB","2026-99-99");fail("Invalid date accepted")}catch(_:java.time.format.DateTimeParseException){}
            val batch=TrashRepository(f.db,f.importer,f.clock).delete("PREGNANCY",p,schedule=false)
            try {f.importer.updateMetadata(doc,"不应保存","LAB");fail("Deleted parent accepted")}catch(_:IllegalArgumentException){}
            assertEquals("DELETED",f.dao.deletionBatch(batch)?.state)
        }
    }
}
