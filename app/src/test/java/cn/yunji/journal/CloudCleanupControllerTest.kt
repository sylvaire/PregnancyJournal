package cn.yunji.journal

import cn.yunji.journal.data.CleanupOutcome
import cn.yunji.journal.data.CleanupRecord
import cn.yunji.journal.data.CleanupStatusAccess
import cn.yunji.journal.data.CloudCleanupAccess
import cn.yunji.journal.feature.CloudCleanupController
import cn.yunji.journal.feature.formatCleanupTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CloudCleanupControllerTest {
    private class Access : CloudCleanupAccess {
        var outcome = CleanupOutcome(true, "云端备份清理完成", kept = 2, commitsDeleted = 1, objectsDeleted = 3, orphansTracked = 4)
        var error: Exception? = null
        var hold: CompletableDeferred<Unit>? = null
        var calls = 0
        override suspend fun runOnce(): CleanupOutcome {calls++;hold?.await();error?.let {throw it};return outcome}
    }
    private class Status : CleanupStatusAccess {
        var record = CleanupRecord();var writes = 0
        override fun load() = record
        override fun record(record: CleanupRecord) {this.record = record;writes++}
        override fun clear() {record = CleanupRecord()}
    }
    private val now = 1_700_000_000_000L

    @Test fun successRecordsOutcomeCountsAndClearsRunning() = runTest {
        val access = Access();val status = Status()
        val controller = CloudCleanupController(this, access, status, now = {now})
        controller.run();runCurrent()
        assertFalse(controller.state.value.running)
        assertEquals("云端备份清理完成", controller.state.value.message)
        val record = controller.state.value.record
        assertTrue(record.ran);assertTrue(record.hasRecord);assertEquals(now, record.attemptedAt)
        assertEquals(2, record.kept);assertEquals(1, record.commitsDeleted)
        assertEquals(3, record.objectsDeleted);assertEquals(4, record.orphansTracked)
        assertEquals(record, status.record)
    }

    @Test fun skippedPassStoresReasonWithoutCounts() = runTest {
        val access = Access().apply {outcome = CleanupOutcome(false, "尚未保存恢复密钥，不能清理云端备份")}
        val status = Status()
        val controller = CloudCleanupController(this, access, status, now = {now})
        controller.run();runCurrent()
        val record = controller.state.value.record
        assertFalse(record.ran);assertTrue(record.hasRecord)
        assertEquals("尚未保存恢复密钥，不能清理云端备份", record.message)
        assertEquals(0, record.kept);assertEquals(0, record.commitsDeleted)
        assertEquals(0, record.objectsDeleted);assertEquals(0, record.orphansTracked)
        assertEquals("尚未保存恢复密钥，不能清理云端备份", controller.state.value.message)
        assertFalse(controller.state.value.running)
    }

    @Test fun failedPassRecordsReasonAndLeavesUiUsable() = runTest {
        val access = Access().apply {error = IllegalStateException("请先配置并保存云盘连接")}
        val status = Status()
        val controller = CloudCleanupController(this, access, status, now = {now})
        controller.run();runCurrent()
        val record = controller.state.value.record
        assertFalse(record.ran);assertEquals("请先配置并保存云盘连接", record.message)
        assertEquals("请先配置并保存云盘连接", controller.state.value.message)
        assertFalse(controller.state.value.running);assertEquals(1, status.writes)
    }

    @Test fun duplicateClicksStartOnlyOneCleanupPass() = runTest {
        val access = Access().apply {hold = CompletableDeferred()}
        val controller = CloudCleanupController(this, access, Status(), now = {now})
        controller.run();runCurrent()
        assertTrue(controller.state.value.running)
        controller.run();runCurrent()
        assertEquals(1, access.calls)
        access.hold!!.complete(Unit);runCurrent()
        assertFalse(controller.state.value.running);assertEquals(1, access.calls)
    }

    @Test fun backupInFlightSkipsManualCleanupWithoutCallingRunner() = runTest {
        val access = Access()
        val controller = CloudCleanupController(this, access, Status(), now = {now}, backupRunning = {true})
        controller.run();runCurrent()
        assertEquals(0, access.calls)
        assertEquals("正在备份，完成后再清理", controller.state.value.message)
        assertFalse(controller.state.value.running)
    }

    @Test fun leavingPageCancelsPassWithoutRecordingFailure() = runTest {
        val access = Access().apply {hold = CompletableDeferred()}
        val status = Status()
        val controller = CloudCleanupController(this, access, status, now = {now})
        controller.run();runCurrent()
        assertTrue(controller.state.value.running)
        controller.leave();runCurrent()
        assertFalse(controller.state.value.running)
        assertNull(controller.state.value.message)
        assertEquals(0, status.writes)
        assertEquals(CleanupRecord(), controller.state.value.record)
    }

    @Test fun refreshReloadsPersistedRecord() = runTest {
        val status = Status()
        val controller = CloudCleanupController(this, Access(), status, now = {now})
        assertEquals(CleanupRecord(), controller.state.value.record)
        status.record = CleanupRecord(attemptedAt = 42, ran = true, message = "云端备份清理完成", kept = 1)
        controller.refresh()
        assertEquals(42, controller.state.value.record.attemptedAt)
        assertEquals(1, controller.state.value.record.kept)
    }

    @Test fun timeLabelUsesRequestedZone() {
        // P1-7：展示层统一中文日期格式（跨年带年份），数据层不变。
        assertEquals("2023年11月15日 06:13", formatCleanupTime(1_700_000_000_000L, java.time.ZoneId.of("Asia/Shanghai")))
    }
}
