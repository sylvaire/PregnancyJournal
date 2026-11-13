package cn.yunji.journal

import cn.yunji.journal.data.CleanupOutcome
import cn.yunji.journal.data.CleanupRecord
import cn.yunji.journal.data.cleanupRecordValues
import cn.yunji.journal.data.decodeCleanupRecord
import org.junit.Assert.*
import org.junit.Test

class CloudCleanupStatusStoreTest {
    @Test fun recordRoundTripsThroughStoredValues() {
        val record = CleanupRecord(attemptedAt = 1_700_000_000_000, ran = true, message = "云端备份清理完成",
            kept = 7, commitsDeleted = 2, objectsDeleted = 5, orphansTracked = 3)
        assertEquals(record, decodeCleanupRecord(cleanupRecordValues(record)))
    }

    @Test fun emptyPreferencesDecodeToNoRecord() {
        val record = decodeCleanupRecord(emptyMap<String, Any>())
        assertEquals(CleanupRecord(), record)
        assertFalse(record.hasRecord)
    }

    @Test fun malformedValuesFallBackToDefaults() {
        val record = decodeCleanupRecord(mapOf("attemptedAt" to "not-a-time", "ran" to "maybe",
            "message" to 3, "kept" to listOf(1), "commitsDeleted" to ""))
        assertEquals(CleanupRecord(), record)
    }

    @Test fun skipAttemptKeepsReasonAndIsNotSuccess() {
        val record = CleanupRecord.failed(1_700_000_000_000, "检测到其他设备的备份，已暂停自动清理")
        assertTrue(record.hasRecord)
        assertFalse(record.ran)
        assertEquals("检测到其他设备的备份，已暂停自动清理", record.message)
    }

    @Test fun outcomeMapsEveryCountAndTruncatesMessage() {
        val outcome = CleanupOutcome(true, "x".repeat(500), kept = 4, commitsDeleted = 3, objectsDeleted = 2, orphansTracked = 1)
        val record = CleanupRecord.of(outcome, at = 9)
        assertEquals(9, record.attemptedAt)
        assertTrue(record.ran)
        assertEquals(4, record.kept);assertEquals(3, record.commitsDeleted)
        assertEquals(2, record.objectsDeleted);assertEquals(1, record.orphansTracked)
        assertEquals(200, record.message.length)
    }
}
