package cn.yunji.journal

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.data.BackupFailureNotifier
import cn.yunji.journal.data.CloudSchedulerStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class BackupFailureNotifierTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val prefsName = "backup-failure-notice-test-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefsName, mode)
    }
    private val store = CloudSchedulerStore(context)
    private val notifier = BackupFailureNotifier(context)
    private val manager = base.getSystemService(NotificationManager::class.java)

    @Before fun requirePermission() {
        assumeTrue(base.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        notifier.clear()
        awaitCleared()
    }

    @After fun clean() {
        notifier.clear()
        base.deleteSharedPreferences(prefsName)
    }

    @Test fun threeFailuresPostOnePrivateNoticeUntilSuccessResetsTheEpisode() {
        repeat(2) {
            store.recordFailure()
            assertFalse(notifier.postIfRequired(store))
            assertNull(active())
        }
        store.recordFailure()
        assertTrue(notifier.postIfRequired(store))
        assertTrue(store.load().failureNoticeShown)
        val notice=awaitPosted()
        assertEquals(BackupFailureNotifier.CHANNEL,notice.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE,notice.visibility)
        assertEquals("自动备份需要检查",notice.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertFalse(notice.extras.toString().contains("password",ignoreCase=true))
        manager.cancel(BackupFailureNotifier.ID)
        assertFalse("同一失败轮次不可重复提醒",notifier.postIfRequired(store))
        awaitCleared()
        store.clearFailures()
        assertFalse(store.load().failureNoticeShown)
        repeat(3) { store.recordFailure() }
        assertTrue("成功重置后新一轮失败可再次提醒",notifier.postIfRequired(store))
    }

    @Test fun foreignWriterBlockAlertsImmediatelyWithoutExposingDetails() {
        store.blockForOtherWriter(123,"敏感的远端地址与凭据",setOf("secret-commit"))
        store.recordFailure()
        assertTrue(notifier.postIfRequired(store))
        val notice=awaitPosted()
        val visible=listOfNotNull(notice.extras.getCharSequence(Notification.EXTRA_TITLE),
            notice.extras.getCharSequence(Notification.EXTRA_TEXT)).joinToString(" ")
        assertFalse(visible.contains("敏感"))
        assertFalse(visible.contains("secret"))
        assertTrue(store.load().failureNoticeShown)
    }

    private fun active() = manager.activeNotifications.firstOrNull { it.id == BackupFailureNotifier.ID }

    /** 系统通知列表由 NotificationManagerService 异步更新，post 返回后立即读取可能暂为空。 */
    private fun awaitPosted(): Notification {
        val deadline=android.os.SystemClock.uptimeMillis()+5_000
        while(true) {
            active()?.let { return it.notification }
            if(android.os.SystemClock.uptimeMillis()>deadline) fail("通知未在 5s 内出现在系统通知列表")
            android.os.SystemClock.sleep(20)
        }
    }

    private fun awaitCleared() {
        val deadline=android.os.SystemClock.uptimeMillis()+2_000
        while(active()!=null && android.os.SystemClock.uptimeMillis()<deadline) android.os.SystemClock.sleep(20)
        assertNull(active())
    }
}
