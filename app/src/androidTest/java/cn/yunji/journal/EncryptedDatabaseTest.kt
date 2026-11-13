package cn.yunji.journal

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.yunji.journal.core.database.JournalDatabase
import cn.yunji.journal.core.database.PregnancyEntity
import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.data.JournalRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun databaseSurvivesReopenAndWrongKeyCannotRead() = runBlocking {
        val file = File(context.cacheDir, "probe-${UUID.randomUUID()}.db")
        val key = VaultCrypto.randomKey()
        var db = JournalDatabase.open(context, file, key)
        try {
            db.journalDao().putPregnancy(PregnancyEntity("probe", "私密孕期", createdAt = 1, updatedAt = 1))
            db.close()
            assertFalse(file.readBytes().take(16).toByteArray().toString(Charsets.US_ASCII).startsWith("SQLite format 3"))
            db = JournalDatabase.open(context, file, VaultCrypto.randomKey())
            assertTrue(runCatching { db.journalDao().allPregnancies() }.isFailure)
            db.close()
            db = JournalDatabase.open(context, file, key)
            assertEquals("私密孕期", db.journalDao().pregnancy("probe")?.title)
        } finally { db.close(); listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() } }
    }

    @Test fun businessWritesAndHistoryAreRecordedInOneTransaction() = runBlocking {
        val file = File(context.cacheDir, "repository-${UUID.randomUUID()}.db")
        val db = JournalDatabase.open(context, file, VaultCrypto.randomKey())
        try {
            val repo = JournalRepository(db, Clock.systemUTC())
            val id = repo.savePregnancy(null, "我的孕期", LocalDate.of(2026, 6, 10), null, "", "")
            assertEquals(1L, db.journalDao().currentSequence())
            repo.savePregnancy(id, "修改名称", LocalDate.of(2026, 6, 10), LocalDate.of(2027, 3, 17), "", "")
            assertEquals(2, db.journalDao().allDateRevisions().size)
            val visitId = repo.saveVisit(null, id, LocalDate.of(2026, 10, 21), null, "测试医院", "常规产检", "")
            val before = repo.visit(visitId)!!
            repo.savePregnancy(id, "修改名称", LocalDate.of(2026, 6, 10), LocalDate.of(2027, 3, 20), "", "")
            assertEquals(before.dateRevisionId, repo.visit(visitId)?.dateRevisionId)
            assertEquals(before.gestationalDays, repo.visit(visitId)?.gestationalDays)
            val sequence = db.journalDao().currentSequence()
            assertTrue(runCatching { repo.saveVisit(null, "missing", LocalDate.now(), null, "", "", "") }.isFailure)
            assertEquals(sequence, db.journalDao().currentSequence())
        } finally { db.close(); listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() } }
    }

    @Test fun cancellationIsReversibleAndDoesNotEraseOriginalAppointment() = withRepository { repo, db ->
        val pregnancy = repo.savePregnancy(null, "测试档案", null, null, "", "")
        val visit = repo.saveVisit(null, pregnancy, LocalDate.of(2026, 10, 18), null, "测试医院", "测试项目", "准备事项")
        val original = repo.visit(visit)!!
        repo.setVisitCancelled(visit, true)
        val cancelled = repo.visit(visit)!!
        assertEquals("CANCELLED", cancelled.status)
        assertEquals(original.scheduledDate, cancelled.scheduledDate)
        assertEquals(original.items, cancelled.items)
        val sequence = db.journalDao().currentSequence()
        repo.setVisitCancelled(visit, true)
        assertEquals(sequence, db.journalDao().currentSequence())
        assertTrue(runCatching { repo.completeVisit(visit, LocalDate.of(2026, 10, 18), "") }.isFailure)
        assertEquals(sequence, db.journalDao().currentSequence())
        repo.setVisitCancelled(visit, false)
        assertEquals("SCHEDULED", repo.visit(visit)?.status)
        assertEquals(original.preparation, repo.visit(visit)?.preparation)
    }

    @Test fun actualVisitDetailsStaySeparateFromAppointmentAfterEdit() = withRepository { repo, _ ->
        val pregnancy = repo.savePregnancy(null, "测试档案", null, null, "", "")
        val visitId = repo.saveVisit(null, pregnancy, LocalDate.of(2026, 10, 18), LocalTime.of(8, 0), "预约医院", "常规产检", "准备事项")
        repo.completeVisit(visitId, LocalDate.of(2026, 10, 18), "复诊备注", LocalTime.of(9, 30), "实际医院", "合成检查结果")
        val completed = requireNotNull(repo.visit(visitId))
        assertEquals("08:00", completed.scheduledTime)
        assertEquals("预约医院", completed.hospital)
        assertEquals("09:30", completed.actualTime)
        assertEquals("实际医院", completed.actualHospital)
        assertEquals("合成检查结果", completed.actualResult)
        repo.saveVisit(visitId, pregnancy, LocalDate.of(2026, 10, 18), LocalTime.of(8, 30), "修改预约医院", "常规产检", "准备事项")
        val edited = requireNotNull(repo.visit(visitId))
        assertEquals("08:30", edited.scheduledTime)
        assertEquals("修改预约医院", edited.hospital)
        assertEquals("09:30", edited.actualTime)
        assertEquals("实际医院", edited.actualHospital)
        assertEquals("合成检查结果", edited.actualResult)
    }

    @Test fun actualVisitPreservesBookingAndRejectsFutureDatesAtomically() = withRepository { repo, db ->
        val pregnancy = repo.savePregnancy(null, "测试档案", null, null, "", "")
        val booking = LocalDate.of(2026, 10, 16)
        val visit = repo.saveVisit(null, pregnancy, booking, null, "", "", "")
        val sequence = db.journalDao().currentSequence()
        assertTrue(runCatching { repo.completeVisit(visit, LocalDate.of(2026, 10, 19), "未来不应保存") }.isFailure)
        assertEquals(sequence, db.journalDao().currentSequence())
        repo.saveDraft("visit", visit, "预约草稿保留")
        repo.saveDraft("actualVisit", visit, "就诊草稿")
        repo.completeVisit(visit, LocalDate.of(2026, 10, 18), "测试备注")
        assertEquals(booking.toString(), repo.visit(visit)?.scheduledDate)
        assertEquals("2026-10-18", repo.visit(visit)?.actualDate)
        assertEquals("测试备注", repo.visit(visit)?.advice)
        assertNull(repo.draft("actualVisit", visit))
        assertEquals("预约草稿保留", repo.draft("visit", visit))
        assertTrue(runCatching { repo.setVisitCancelled(visit, true) }.isFailure)
        assertEquals("COMPLETED", repo.visit(visit)?.status)
    }

    @Test fun sameTimestampHistoryAndArchivingPreserveSeparatePregnancies() = withRepository { repo, db ->
        val first = repo.savePregnancy(null, "第一份测试", null, LocalDate.of(2027, 3, 1), "", "")
        val second = repo.savePregnancy(null, "第二份测试", null, null, "", "")
        repo.savePregnancy(first, "第一份测试", null, LocalDate.of(2027, 3, 2), "", "")
        assertEquals("2027-03-02", db.journalDao().latestDateRevision(first)?.confirmedEdd)
        val visit = repo.saveVisit(null, first, LocalDate.of(2026, 10, 18), null, "", "", "")
        assertEquals(db.journalDao().latestDateRevision(first)?.id, repo.visit(visit)?.dateRevisionId)
        repo.setPregnancyStatus(first, "ENDED")
        repo.setPregnancyStatus(first, "ARCHIVED")
        assertEquals("ARCHIVED", repo.pregnancy(first)?.status)
        assertEquals("ONGOING", repo.pregnancy(second)?.status)
        assertEquals(first, repo.visit(visit)?.pregnancyId)
        repo.setPregnancyStatus(first, "ENDED")
        assertEquals("ENDED", repo.pregnancy(first)?.status)
    }

    @Test fun legacyDraftMoveIsAtomicAndDoesNotOverwriteANewerDraft() = withRepository { repo, _ ->
        repo.saveDraft("visit", "new", "旧版测试草稿")
        repo.moveDraft("visit", "new", "new:pregnancy-a", "旧版测试草稿")
        assertNull(repo.draft("visit", "new"))
        assertEquals("旧版测试草稿", repo.draft("visit", "new:pregnancy-a"))
        repo.saveDraft("visit", "new", "另一份旧草稿")
        repo.moveDraft("visit", "new", "new:pregnancy-a", "另一份旧草稿")
        assertEquals("旧版测试草稿", repo.draft("visit", "new:pregnancy-a"))
        assertEquals("另一份旧草稿", repo.draft("visit", "new"))
        repo.saveDraft("visit", "new:pregnancy-b", "独立档案草稿")
        assertEquals("独立档案草稿", repo.draft("visit", "new:pregnancy-b"))
    }

    private fun withRepository(block: suspend (JournalRepository, JournalDatabase) -> Unit) = runBlocking {
        val file = File(context.cacheDir, "m2-business-${UUID.randomUUID()}.db")
        val db = JournalDatabase.open(context, file, VaultCrypto.randomKey())
        try { block(JournalRepository(db, Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC)), db) }
        finally { db.close(); listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() } }
    }
}
