package cn.yunji.journal

import cn.yunji.journal.core.database.*
import cn.yunji.journal.domain.TimelineRules
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TimelineRulesTest {
    private fun snapshot(visits: List<TimelineVisit> = emptyList(),entries: List<JournalEntryEntity> = emptyList()) = TimelineSnapshot(
        PregnancyEntity("p","合成",createdAt=0,updatedAt=0),visits,emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),entries)
    private fun visit()=VisitEntity("v","p","2026-10-18","09:00","Asia/Shanghai",status="COMPLETED",createdAt=1,updatedAt=2,actualDate="2026-10-17")
    private fun doc()=AttachmentEntity("d","v","合成报告","image/png",1,33,"p","c",null,null,null,null,null,Instant.parse("2026-10-18T10:00:00Z").toEpochMilli())
    @Test fun actualVisitAndDocumentDatesNeverBorrowAppointmentOrImportTime() {
        val source=TimelineVisit(visit(),listOf(doc().copy(documentDate="2026-10-16")),listOf(ImportJobEntity("d","v","","合成",0,"READY")))
        val events=TimelineRules.build(snapshot(listOf(source)),ZoneOffset.UTC)
        assertEquals(listOf("2026-10-17","2026-10-16"),events.map {it.date})
        assertTrue(events.all {it.time==null && it.instant==null})
        assertEquals("实际就诊日期",events.first().basis)
        assertEquals("报告日期",events.last().basis)
        val imported=TimelineRules.build(snapshot(listOf(source.copy(documents=listOf(doc())))),ZoneOffset.UTC).first()
        assertEquals("DOCUMENT",imported.type);assertEquals("10:00",imported.time)
        assertTrue(imported.basis.startsWith("导入日期"))
    }
    @Test fun deletedVisitRetainsReadyDocumentsButDeletedPregnancyAndForeignSourcesAreHidden() {
        val source=TimelineVisit(visit().copy(deletedAt=1),listOf(doc(),doc().copy(id="deleted",deletedAt=1)),listOf(ImportJobEntity("d","v","","",0,"READY")))
        val events=TimelineRules.build(snapshot(listOf(source)),ZoneOffset.UTC)
        assertEquals(1,events.size);assertEquals("DOCUMENT:d",events.single().key)
        assertTrue(events.single().summary.contains("原产检已删除"))
        assertTrue(TimelineRules.build(snapshot(listOf(source.copy(visit=visit().copy(pregnancyId="other")))),ZoneOffset.UTC).isEmpty())
        assertTrue(TimelineRules.build(snapshot(listOf(source)).let {it.copy(pregnancy=it.pregnancy.copy(deletedAt=1))},ZoneOffset.UTC).isEmpty())
    }
    @Test fun dateOnlyEntriesSortStablyWithoutFakingMidnightOrUsingCreationDate() {
        val unknown=JournalEntryEntity("z","p","2026-10-16",null,"UTC",null,"","","日期记录",99,99)
        val known=unknown.copy(id="a",entryTime="08:00",offsetSeconds=0)
        val yesterday=unknown.copy(id="y",entryDate="2026-10-15",createdAt=Long.MAX_VALUE)
        val events=TimelineRules.build(snapshot(entries=listOf(unknown,yesterday,known)),ZoneOffset.UTC)
        assertEquals(listOf("a","z","y"),events.map {it.id})
        assertNull(events[1].instant);assertNull(events[1].time)
        assertEquals(events,TimelineRules.build(snapshot(entries=listOf(known,unknown,yesterday)),ZoneOffset.UTC))
    }
}
