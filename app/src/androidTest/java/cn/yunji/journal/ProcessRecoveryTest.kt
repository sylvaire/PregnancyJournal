package cn.yunji.journal

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.yunji.journal.data.CloudUploadStatusStore
import cn.yunji.journal.data.SecurityStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task-6 (A03 / D05): real process-restart recovery, split into one instrumentation run per phase.
 *
 * Each `phaseN…` method is executed as its own `am instrument` invocation:
 *   1) pregnancy + draft saved under an immediate lock -> process exits -> cold start keeps them,
 *      the journal UI stays locked;
 *   2) pending-backup marker -> cold start reads it back without uploading or clearing it;
 *   3) vault opened and written -> host kills the process (kill -9, or ActivityManager force-stop
 *      as the documented fallback) -> restart reads durable data once, no duplicates.
 *
 * The host script (docs/verification/2026-10-22-A03-lock-process-matrix/run-task6-lock-matrix.sh)
 * drives force-stop, marker parsing and the kill. Markers live under files/task6-markers so a later
 * instrumentation process can read them back without app-internal APIs.
 */
@RunWith(AndroidJUnit4::class)
class ProcessRecoveryTest {
    private val base: Context = ApplicationProvider.getApplicationContext()

    @After
    fun restoreLockBaseline() {
        SecurityStore(base).apply {
            setLockEnabled(false)
            setTimeoutMinutes(0)
        }
    }

    @Test
    fun phase1DraftSurvivesLockedGracefulRestart() = runBlocking {
        SecurityStore(base).apply {
            setLockEnabled(true)
            setTimeoutMinutes(0)
        }
        val pregnancyId: String
        IsolatedVault.fresh(base, DRAFT_TOKEN).use { vault ->
            val repository = vault.repository()
            pregnancyId = repository.savePregnancy(null, DRAFT_TITLE, null, null, "", "")
            repository.saveDraft("pregnancy", pregnancyId, DRAFT_PAYLOAD)
            assertEquals("草稿在同进程内未保存", DRAFT_PAYLOAD, repository.draft("pregnancy", pregnancyId))
        }
        IsolatedVault.marker(base, DRAFT_TOKEN).writeText(
            "phase=draft.phase1 pid=${android.os.Process.myPid()} pregnancyId=$pregnancyId",
        )
        Thread.sleep(1_500)
    }

    @Test
    fun phase2LockedColdStartKeepsDraftAndNeverShowsJournal() = runBlocking {
        SecurityStore(base).apply {
            setLockEnabled(true)
            setTimeoutMinutes(0)
        }
        val marker = restartedPhaseMarker(DRAFT_TOKEN)
        val pregnancyId = requireNotNull(Regex("pregnancyId=(\\S+)").find(marker)?.groupValues?.get(1)) {
            "phase1 未写出 pregnancyId：$marker"
        }
        IsolatedVault.existing(base, DRAFT_TOKEN).use { vault ->
            val repository = vault.repository()
            assertEquals("锁屏前的档案在进程重启后丢失", DRAFT_TITLE, repository.pregnancy(pregnancyId)?.title)
            assertEquals("锁屏前的草稿在进程重启后丢失", DRAFT_PAYLOAD, repository.draft("pregnancy", pregnancyId))
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue("锁定入口没有在冷启动时显示锁屏", UiProbe.waitForText("资料已锁定", 20_000))
            Thread.sleep(500)
            listOf("今天", "产检", "记录", "创建孕期档案").forEach {
                assertFalse("锁定期间泄露了日记内容：$it", UiProbe.anyVisible(listOf(it)))
            }
        } finally {
            scenario.close()
        }
        UiProbe.pressHome()
    }

    @Test
    fun phase1PendingBackupMarkerSurvivesRestart() = runBlocking {
        SecurityStore(base).apply {
            setLockEnabled(false)
            setTimeoutMinutes(0)
        }
        IsolatedVault.fresh(base, BACKUP_TOKEN).use { vault ->
            val repository = vault.repository()
            val pregnancyId = repository.savePregnancy(null, "待重传档案", null, null, "", "")
            repository.saveDraft("pregnancy", pregnancyId, DRAFT_PAYLOAD)
        }
        val status = CloudUploadStatusStore(base)
        status.beginAttempt(STATUS_ATTEMPT_AT)
        status.fail(STATUS_ATTEMPT_AT, "synthetic slow network")
        assertEquals("FAILED", status.load().lastPhase)
        IsolatedVault.marker(base, BACKUP_TOKEN).writeText(
            "phase=backup.phase1 pid=${android.os.Process.myPid()} phase=${status.load().lastPhase}",
        )
        Thread.sleep(1_500)
    }

    @Test
    fun phase2ColdStartKeepsPendingBackupMarkerWithoutUpload() = runBlocking {
        SecurityStore(base).apply {
            setLockEnabled(false)
            setTimeoutMinutes(0)
        }
        restartedPhaseMarker(BACKUP_TOKEN)
        val status = CloudUploadStatusStore(base)
        val before = status.load()
        assertEquals("待重传标记在进程重启后丢失", "FAILED", before.lastPhase)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            Thread.sleep(3_000)
            assertEquals("冷启动清空或触发了待重传标记", before, status.load())
            assertEquals("FAILED", status.load().lastPhase)
        } finally {
            scenario.close()
        }
        UiProbe.pressHome()
    }

    @Test
    fun phase1CrashWithOpenVaultKeepsDurableData() {
        val pregnancyId: String
        IsolatedVault.fresh(base, CRASH_TOKEN).let { vault ->
            pregnancyId = runBlocking {
                val repository = vault.repository()
                val id = repository.savePregnancy(null, CRASH_TITLE, null, null, "", "")
                repository.saveDraft("pregnancy", id, DRAFT_PAYLOAD)
                assertEquals(1, vault.runtime.resources().database.journalDao().allPregnancies().size)
                id
            }
        }
        // Vault stays open on purpose: the host kills this process while SQLite/Room is live.
        IsolatedVault.marker(base, CRASH_TOKEN).writeText(
            "phase=crash.phase1 pid=${android.os.Process.myPid()} pregnancyId=$pregnancyId",
        )
        // 宿主脚本在标记出现后 1-2 秒内就会 kill；30 秒足够，整类同跑时也不至于白等两分钟。
        Thread.sleep(30_000)
    }

    @Test
    fun phase2CrashRestartReadsDurableDataWithoutDuplicates() = runBlocking {
        val marker = restartedPhaseMarker(CRASH_TOKEN)
        val pregnancyId = requireNotNull(Regex("pregnancyId=(\\S+)").find(marker)?.groupValues?.get(1)) {
            "phase1 未写出 pregnancyId：$marker"
        }
        IsolatedVault.existing(base, CRASH_TOKEN).use { vault ->
            val dao = vault.runtime.resources().database.journalDao()
            assertEquals("重启后档案丢失或重复", 1, dao.allPregnancies().count { it.title == CRASH_TITLE })
            assertEquals("重启后草稿丢失", DRAFT_PAYLOAD, vault.repository().draft("pregnancy", pregnancyId))
        }
    }

    private fun markerText(token: String): String {
        val marker = IsolatedVault.marker(base, token)
        assertTrue("缺少阶段标记文件：$marker", marker.isFile)
        return marker.readText()
    }

    /**
     * phase2 由宿主脚本（run-task6-lock-matrix.sh）驱动：phase1 写标记后脚本 force-stop 或 kill -9，
     * 再用一个新的 instrumentation 进程跑 phase2。整类一起跑时没有这个重启点，因此跳过而不是误报失败；
     * 标记里的 pid 与当前进程相同说明进程没重启，同样跳过。
     */
    private fun restartedPhaseMarker(token: String): String {
        val marker = IsolatedVault.marker(base, token)
        val text = marker.takeIf { it.isFile }?.readText().orEmpty()
        org.junit.Assume.assumeTrue("需要宿主脚本在 phase1 之后重启进程：缺少 $token 标记", text.isNotEmpty())
        val pid = Regex("pid=(\\d+)").find(text)?.groupValues?.get(1)
        org.junit.Assume.assumeTrue("需要宿主脚本在 phase1 之后重启进程：进程没有重启", pid != android.os.Process.myPid().toString())
        marker.delete()
        return text
    }

    private companion object {
        const val DRAFT_TOKEN = "draft"
        const val BACKUP_TOKEN = "backup"
        const val CRASH_TOKEN = "crash"
        const val DRAFT_TITLE = "锁屏前档案"
        const val CRASH_TITLE = "合成崩溃前档案"
        const val DRAFT_PAYLOAD = "{\"note\":\"合成未提交草稿，进程恢复后仍在\"}"
        const val STATUS_ATTEMPT_AT = 1_700_000_000_000L
    }
}
