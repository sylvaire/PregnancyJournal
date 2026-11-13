package cn.yunji.journal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.security.AccessState
import cn.yunji.journal.core.security.DeviceKeyStore
import cn.yunji.journal.data.SecurityStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AccessStateTest {
    private class Fixture: AutoCloseable {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val token=UUID.randomUUID().toString()
        val root=File(base.cacheDir,"access-$token").apply {mkdirs()}
        val context=object: ContextWrapper(base) {
            override fun getApplicationContext(): Context=this
            override fun getNoBackupFilesDir()=root
            override fun getSharedPreferences(name: String,mode: Int)=base.getSharedPreferences("$token-$name",mode)
        }
        val settings=SecurityStore(context).apply {setLockEnabled(true)}
        val keys=DeviceKeyStore.isolated(context,token)
        var now=1_000L
        val access=AccessState.testing(context,settings,keys){now}
        override fun close(){base.deleteSharedPreferences("$token-security-state");root.deleteRecursively()}
    }
    @Test fun immediateLockRejectsNotificationCommitAsSoonAsForegroundIsLost() {
        Fixture().use {f ->f.settings.setTimeoutMinutes(0);f.access.unlock(verified=true)
            assertTrue(f.access.canCommitNotification());f.access.stopped()
            assertFalse(f.access.canCommitNotification());f.access.resumed();assertTrue(f.access.needsAuthentication())}
    }
    @Test fun oneMinuteTimeoutPreservesShortReturnButLocksAtExactBoundary() {
        Fixture().use {f ->f.settings.setTimeoutMinutes(1);f.access.unlock();f.access.stopped()
            f.now+=59_999;f.access.resumed();assertFalse(f.access.needsAuthentication())
            f.access.stopped();f.now+=60_000;f.access.resumed();assertTrue(f.access.needsAuthentication())}
    }
    @Test fun fiveMinuteTimeoutUsesFullConfiguredDuration() {
        Fixture().use {f ->f.settings.setTimeoutMinutes(5);f.access.unlock();f.access.stopped()
            f.now+=299_999;f.access.resumed();assertFalse(f.access.needsAuthentication())
            f.access.stopped();f.now+=300_000;f.access.resumed();assertTrue(f.access.needsAuthentication())}
    }
    @Test fun viewingUnlockedUiDoesNotExtendNotificationAuthenticationLease() {
        Fixture().use {f ->f.access.unlock();assertFalse(f.access.canCommitNotification())
            f.access.unlock(verified=true);f.now+=120_000;assertTrue(f.access.canCommitNotification())
            f.now++;f.access.unlock();assertFalse(f.access.canCommitNotification())
            f.access.unlock(verified=true);f.access.lock();assertFalse(f.access.canCommitNotification())}
    }
}
