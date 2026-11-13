package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.*
import org.junit.Assert.*
import org.junit.Test

class SnapshotContentTest {
    private fun empty()=LogicalSnapshot(vaultId="vault",snapshotId="snapshot",writerId="writer",writerSequence=1,capturedSequence=1,createdAt=1)
    private val pregnancy=PregnancyEntity("p","合成",createdAt=1,updatedAt=1)
    @Test fun customReminderRequiresValidContentAndPregnancyLink() {
        val row=CustomReminderEntity("reminder","p","合成提醒",startDate="2026-10-23",localTime="09:00",createdAt=1,updatedAt=1)
        val snapshot=empty().copy(pregnancies=listOf(pregnancy),customReminders=listOf(row))
        SnapshotContent.validate(snapshot);SnapshotRelations.validate(snapshot)
        assertTrue(runCatching {SnapshotContent.validate(snapshot.copy(customReminders=listOf(row.copy(repeat="INVALID"))))}.isFailure)
        assertTrue(runCatching {SnapshotContent.validate(snapshot.copy(customReminders=listOf(row.copy(endDate="2026-10-22"))))}.isFailure)
        assertTrue(runCatching {SnapshotRelations.validate(snapshot.copy(pregnancies=emptyList()))}.isFailure)
    }
    @Test fun invalidCalendarZoneStateAndRevisionAreRejected() {
        val visit=VisitEntity("v","p","2026-10-19",zoneId="Asia/Shanghai",createdAt=1,updatedAt=1)
        SnapshotContent.validate(empty().copy(pregnancies=listOf(pregnancy),visits=listOf(visit)))
        for(bad in listOf(visit.copy(scheduledDate="2026-02-30"),visit.copy(zoneId="invalid/zone"),visit.copy(status="UNSUPPORTED"),visit.copy(revision=Long.MAX_VALUE),visit.copy(reminderDate="2026-10-19"))) {
            assertTrue(runCatching {SnapshotContent.validate(empty().copy(visits=listOf(bad)))}.isFailure)
        }
    }
    @Test fun actualVisitFieldsAreValidatedBeforeBackupRestore() {
        val visit = VisitEntity("v", "p", "2026-10-19", zoneId = "Asia/Shanghai", createdAt = 1, updatedAt = 1,
            actualDate = "2026-10-19", actualTime = "09:30", actualHospital = "合成医院", actualResult = "合成检查结果")
        val snapshot = empty().copy(pregnancies = listOf(pregnancy), visits = listOf(visit))
        SnapshotContent.validate(snapshot)
        assertTrue(runCatching { SnapshotContent.validate(snapshot.copy(visits = listOf(visit.copy(actualTime = "25:99")))) }.isFailure)
        assertTrue(runCatching { SnapshotContent.validate(snapshot.copy(visits = listOf(visit.copy(actualHospital = "a".repeat(121))))) }.isFailure)
        assertTrue(runCatching { SnapshotContent.validate(snapshot.copy(visits = listOf(visit.copy(actualResult = "a".repeat(10001))))) }.isFailure)
    }
    @Test fun oversizedTextUnsafeNamesAndUnsupportedMimeAreRejected() {
        assertTrue(runCatching {SnapshotContent.validate(empty().copy(pregnancies=listOf(pregnancy.copy(note="a".repeat(10001)))))}.isFailure)
        assertTrue(runCatching {SnapshotContent.validate(empty().copy(pregnancies=listOf(pregnancy.copy(title="bad\u0000title"))))}.isFailure)
        val a=AttachmentEntity("file","visit","合成","image/png",0,32,"0".repeat(64),"0".repeat(64),null,null,null,null,null,1)
        SnapshotContent.validate(empty().copy(attachments=listOf(a)))
        for(bad in listOf(a.copy(name="../file"),a.copy(mime="text/html"),a.copy(category="UNKNOWN"),a.copy(medicationId="drug",visitId=null))) {
            assertTrue(runCatching {SnapshotContent.validate(empty().copy(attachments=listOf(bad)))}.isFailure)
        }
    }
    @Test fun validHistoricalDatesAndScrubbedTombstonesRemainAccepted() {
        val entry=JournalEntryEntity("e","p","1960-01-01","08:00","UTC",0,"","","历史",1,1)
        val intake=MedicationIntakeEntity("i","p","plan",null,null,"REMOVED",null,"1970-01-01","UTC",null,null,"",1,2,deletedAt=2,deletionBatchId="batch")
        SnapshotContent.validate(empty().copy(entries=listOf(entry),intakes=listOf(intake)))
        assertTrue(runCatching {SnapshotContent.validate(empty().copy(intakes=listOf(intake.copy(deletedAt=null))))}.isFailure)
        assertTrue(runCatching {SnapshotContent.validate(empty().copy(intakes=listOf(intake.copy(status="TAKEN"))))}.isFailure)
    }
}
