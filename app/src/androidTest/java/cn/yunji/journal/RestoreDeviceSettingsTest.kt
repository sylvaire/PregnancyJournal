package cn.yunji.journal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.data.*
import cn.yunji.journal.core.database.JournalDatabase
import cn.yunji.journal.core.security.VaultCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Clock
import java.util.UUID

class RestoreDeviceSettingsTest {
    private fun fixture(block: (Context)->Unit) {
        val base=ApplicationProvider.getApplicationContext<Context>();val token="restore-settings-${UUID.randomUUID()}"
        val context=object: ContextWrapper(base) {
            override fun getApplicationContext(): Context=this
            override fun getSharedPreferences(name: String,mode: Int)=base.getSharedPreferences("$token-$name",mode)
        }
        try {block(context)}finally {listOf("cloud-scheduler","reminder-state").forEach {base.deleteSharedPreferences("$token-$it")}}
    }
    @Test fun restoredCloudStateIsSecondaryWithAutomationDisabled()=fixture {context->
        val store=CloudSchedulerStore(context)
        store.setRole(DeviceRole.PRIMARY,1);store.setAutoEnabled(true);store.setLastBackedUp(99);store.recordFailure();store.setWifiOnly(false)
        store.afterRestore();val state=store.load()
        assertEquals(DeviceRole.SECONDARY,state.role);assertFalse(state.autoEnabled);assertFalse(state.autoActive)
        assertTrue(state.wifiOnly);assertEquals(0,state.lastCaptureSequenceBackedUp);assertEquals(0,state.failures)
    }
    @Test fun oldReminderGenerationAndSnoozeAreInvalidatedWithoutLosingConsent()=fixture {context->
        val store=ReminderStore(context)
        store.markNotificationAsked();store.setVisitsEnabled(false);store.nextGeneration()
        store.saveScheduledKeys(setOf("SYNTHETIC"));store.setSnooze("SYNTHETIC",1000)
        val before=store.generation();assertEquals(setOf("SYNTHETIC"),store.invalidateForRestore())
        assertEquals(before+1,store.generation());assertTrue(store.scheduledKeys().isEmpty());assertTrue(store.snoozes().isEmpty())
        assertTrue(store.notificationAsked());assertFalse(store.visitsEnabled())
    }
    @Test fun pausedSchedulerRejectsRebuildUntilAbortResume()=fixture {context->runBlocking {
        val root=File(context.cacheDir,"reminder-stop-${UUID.randomUUID()}").apply {mkdirs()}
        val key=VaultCrypto.randomKey();val db=try {JournalDatabase.open(context,File(root,"test.db"),key)}finally {key.fill(0)}
        try {
            val store=ReminderStore(context)
            val scheduler=ReminderScheduler(context,db,Clock.systemUTC(),store,ReminderNotifier(context))
            scheduler.requestRebuildEnabled=false
            scheduler.rebuild();val before=store.generation()
            scheduler.pauseForRestore();assertTrue(store.generation()>before)
            assertTrue(runCatching {scheduler.rebuild()}.isFailure)
            scheduler.resumeAfterRestoreAbort();scheduler.requestRebuildEnabled=false
            scheduler.rebuild()
        }finally {db.close();root.deleteRecursively()}
    }}
}