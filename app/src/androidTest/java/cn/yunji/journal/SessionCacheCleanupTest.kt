package cn.yunji.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Task-6 (A03): locking must clear the decrypted document preview cache without touching the
 * committed vault. [cn.yunji.journal.core.security.VaultRuntime.clearPreviews] is the exact call
 * MainActivity.coverSession() makes when the app leaves the foreground; this exercises it against a
 * live isolated session so the cleanup contract is asserted deterministically on the JVM-managed
 * device (UI probing of the real vault is covered by LockEntryMatrixTest).
 */
@RunWith(AndroidJUnit4::class)
class SessionCacheCleanupTest {
    private val base: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun clearingPreviewsDeletesPrivateDocumentPreviews() = runBlocking {
        IsolatedVault.fresh(base, "cache").use { vault ->
            vault.runtime.resources()
            val viewers = File(vault.root, "document-viewers").apply { mkdirs() }
            val probe = File(viewers, "probe-${UUID.randomUUID()}.preview").apply { writeBytes(ByteArray(64) { 1 }) }
            assertTrue("预览探针未创建", probe.isFile)
            vault.runtime.clearPreviews()
            assertFalse("锁定清理后仍残留私有预览", probe.exists())
            assertEquals(0, viewers.listFiles().orEmpty().size)
        }
    }

    @Test
    fun clearingPreviewsKeepsCommittedRecords() = runBlocking {
        IsolatedVault.fresh(base, "cache2").use { vault ->
            val repository = vault.repository()
            repository.savePregnancy(null, "缓存清理保留档案", null, null, "", "")
            val viewers = File(vault.root, "document-viewers").apply { mkdirs() }
            File(viewers, "probe-${UUID.randomUUID()}.preview").writeBytes(ByteArray(32) { 2 })
            vault.runtime.clearPreviews()
            assertEquals(1, vault.runtime.resources().database.journalDao().allPregnancies().size)
        }
    }
}
