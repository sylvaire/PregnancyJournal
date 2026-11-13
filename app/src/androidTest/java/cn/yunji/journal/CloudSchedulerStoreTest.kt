package cn.yunji.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.data.CloudSchedulerStore
import cn.yunji.journal.data.DeviceRole
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Device-local scheduling state. Uses synthetic values only and clears its own preferences. */
class CloudSchedulerStoreTest {
    private lateinit var context: Context
    private lateinit var store: CloudSchedulerStore

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("cloud-scheduler", Context.MODE_PRIVATE).edit().clear().commit()
        store = CloudSchedulerStore(context)
    }

    @After fun tearDown() {
        context.getSharedPreferences("cloud-scheduler", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun automationIsOffByDefaultAndSecondaryIsNotActive() {
        val initial = store.load()
        assertEquals(DeviceRole.PRIMARY, initial.role)
        assertFalse("Auto backup must be opt-in", initial.autoEnabled)
        assertFalse(initial.autoActive)
        assertTrue("Wi-Fi only is the safe default", initial.wifiOnly)
    }

    @Test fun secondaryDeviceNeverBecomesAutoActiveEvenWhenEnabled() {
        store.setRole(DeviceRole.SECONDARY, 100)
        store.setAutoEnabled(true)
        val state = store.load()
        assertTrue(state.autoEnabled)
        assertFalse("A non-primary device must not auto-upload", state.autoActive)
    }

    @Test fun foreignWriterBlockStopsAutomationUntilPrimaryIsConfirmed() {
        store.setAutoEnabled(true)
        assertTrue(store.load().autoActive)
        store.blockForOtherWriter(200, "其他设备提交")
        val blocked = store.load()
        assertTrue(blocked.blockedByOtherWriter)
        assertFalse(blocked.autoActive)
        assertEquals(200, blocked.blockedAt)
        // Selecting primary is the only path that clears the block.
        store.setRole(DeviceRole.PRIMARY, 300)
        store.clearBlock()
        assertFalse(store.load().blockedByOtherWriter)
        assertTrue(store.load().autoActive)
    }

    @Test fun confirmationPersistsOnlyTheObservedForeignCommitsAndRestoreClearsIt() {
        store.blockForOtherWriter(200, "其他设备提交", setOf("foreign-old"))
        assertTrue(store.load().acknowledgedForeignCommitIds.isEmpty())
        store.setRole(DeviceRole.PRIMARY, 300)
        store.clearBlock()
        assertEquals(setOf("foreign-old"), CloudSchedulerStore(context).load().acknowledgedForeignCommitIds)
        store.markFailureNoticeShown()
        store.blockForOtherWriter(400, "又有新提交", setOf("foreign-new"))
        assertEquals(setOf("foreign-old"), store.load().acknowledgedForeignCommitIds)
        store.afterRestore()
        assertTrue(store.load().acknowledgedForeignCommitIds.isEmpty())
        assertEquals(DeviceRole.SECONDARY, store.load().role)
        assertFalse(store.load().failureNoticeShown)
    }

    @Test fun failureCountAndBackoffStatePersistAcrossInstances() {
        val other = CloudSchedulerStore(context)
        other.recordFailure(); other.recordFailure()
        other.markFailureNoticeShown()
        other.setNextAttemptAt(999); other.setLastBackedUp(42)
        val reloaded = CloudSchedulerStore(context).load()
        assertEquals(2, reloaded.failures)
        assertTrue(reloaded.failureNoticeShown)
        assertEquals(999, reloaded.nextAttemptAt)
        assertEquals(42, reloaded.lastCaptureSequenceBackedUp)
        other.clearFailures()
        assertEquals(0, CloudSchedulerStore(context).load().failures)
        assertFalse(CloudSchedulerStore(context).load().failureNoticeShown)
    }

    @Test fun resetRestoresDefaultsWithoutTouchingUploadStatus() {
        store.setRole(DeviceRole.SECONDARY, 1); store.setAutoEnabled(true); store.setWifiOnly(false)
        store.reset()
        val state = store.load()
        assertEquals(DeviceRole.PRIMARY, state.role)
        assertFalse(state.autoEnabled)
        assertTrue(state.wifiOnly)
    }
}
