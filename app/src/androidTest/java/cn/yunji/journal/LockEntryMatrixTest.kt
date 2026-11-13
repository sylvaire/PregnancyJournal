package cn.yunji.journal

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.yunji.journal.data.SecurityStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Task-6 (A03): every entry into the app under an immediate lock must show the lock screen and never
 * reveal journal content — cold start, activity recreation, rotation, back press and a failed unlock
 * attempt. The last case also proves locking clears the private document preview cache.
 *
 * Methods are ordered by name so the unlocked preview-cache case runs after the locked entries and
 * cannot leave the process authenticated for them. UI state is read through [UiProbe] (platform
 * UiAutomation), not through a third-party UI library.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LockEntryMatrixTest {
    private val base: Context = ApplicationProvider.getApplicationContext()
    private val settings get() = SecurityStore(base)
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun forceLockAtEveryEntry() {
        settings.setLockEnabled(true)
        settings.setTimeoutMinutes(0)
    }

    @After
    fun restoreBaseline() {
        scenario?.close()
        scenario = null
        settings.setLockEnabled(false)
        settings.setTimeoutMinutes(0)
    }

    @Test
    fun t1ColdStartShowsLockScreenAndNoJournalContent() {
        launchLocked()
        assertTrue("锁屏缺少解锁按钮", UiProbe.waitForText("解锁资料", 20_000))
    }

    @Test
    fun t2ActivityRecreationKeepsLockScreen() {
        val current = launchLocked()
        current.recreate()
        assertTrue("Activity 重建后锁屏消失", UiProbe.waitForText("资料已锁定", 20_000))
        assertNoJournalContent()
    }

    @Test
    fun t3RotationKeepsLockScreen() {
        val current = launchLocked()
        try {
            current.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            assertTrue("旋转后锁屏消失", UiProbe.waitForText("资料已锁定", 20_000))
            assertNoJournalContent()
        } finally {
            current.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test
    fun t4BackPressCannotRevealJournalContent() {
        launchLocked()
        UiProbe.pressBack()
        Thread.sleep(1_000)
        assertNoJournalContent()
    }

    @Test
    fun t5FailedUnlockAttemptKeepsLockScreen() {
        launchLocked()
        assertTrue("未找到解锁按钮", UiProbe.clickText("解锁资料", 20_000))
        Thread.sleep(4_000)
        assertNoJournalContent()
        assertTrue("解锁尝试后锁屏消失且未出现系统认证", UiProbe.waitForAnyText(listOf("资料已锁定", "解锁资料"), 5_000))
    }

    @Test
    fun t6LockingClearsPrivateDocumentPreviewCache() {
        settings.setLockEnabled(false)
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("未进入可查看内容的应用会话", UiProbe.waitForAnyText(CONTENT_MARKERS, 25_000))
        val viewers = File(base.noBackupFilesDir, "document-viewers").apply { mkdirs() }
        val probe = File(viewers, "task6-probe-${System.nanoTime()}.preview").apply { writeBytes(ByteArray(64) { 7 }) }
        assertTrue("预览探针未创建", probe.isFile)
        settings.setLockEnabled(true)
        UiProbe.pressHome()
        val deadline = System.currentTimeMillis() + 20_000
        while (probe.exists() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertFalse("锁定期间私有预览缓存未清理", probe.exists())
    }

    private fun launchLocked(): ActivityScenario<MainActivity> {
        val started = ActivityScenario.launch(MainActivity::class.java)
        scenario = started
        assertTrue("锁定入口没有显示锁屏", UiProbe.waitForText("资料已锁定", 20_000))
        Thread.sleep(300)
        assertNoJournalContent()
        return started
    }

    private fun assertNoJournalContent() {
        CONTENT_MARKERS.forEach {
            assertFalse("锁定期间泄露了日记内容：$it", UiProbe.anyVisible(listOf(it)))
        }
    }

    private companion object {
        val CONTENT_MARKERS = listOf("今天", "产检", "记录", "我的", "创建孕期档案")
    }
}
