package cn.yunji.journal

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.yunji.journal.core.share.OriginalSharer
import cn.yunji.journal.core.share.ShareInvocation
import cn.yunji.journal.core.share.ShareLauncher
import cn.yunji.journal.core.share.chooser
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device verification of the temporary original share: the provider is non-exported, only
 * `filesDir/share` is reachable, exactly one read-only stream is published, and returning revokes
 * the grant before deleting the plaintext copy.
 */
@RunWith(AndroidJUnit4::class)
class OriginalShareDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val launched = mutableListOf<ShareInvocation>()
    private lateinit var sharer: OriginalSharer
    private val shareDir: File get() = File(context.filesDir, "share")

    @Before
    fun setUp() {
        shareDir.deleteRecursively()
        launched.clear()
        sharer = OriginalSharer(context, ShareLauncher { launched += it; true })
    }

    @After
    fun tearDown() {
        sharer.releaseAfterReturn()
        sharer.clearResidual()
        shareDir.deleteRecursively()
    }

    @Test
    fun providerIsRegisteredNonExportedWithGrantOnly() {
        val provider = context.packageManager.resolveContentProvider(sharer.authority(), 0)
        assertNotNull("分享 provider 未注册", provider)
        assertEquals(context.packageName + ".share", provider!!.authority)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertNull(provider.readPermission)
        assertNull(provider.writePermission)
    }

    @Test
    fun onlyTheShareDirectoryIsReachableThroughTheProvider() {
        listOf(
            File(context.filesDir, "outside-original.bin").apply { writeBytes(byteArrayOf(1)) },
            File(context.noBackupFilesDir, "vault/gen/active/journal.db"),
            File(context.cacheDir, "capture/stolen.jpg"),
        ).forEach { file ->
            assertThrows("不得通过分享 provider 暴露 ${file.path}", IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, sharer.authority(), file)
            }
        }
    }

    @Test
    fun shareIntentCarriesExactlyOneReadOnlyStream() {
        val file = sharer.prepare("报告.pdf", "application/pdf").apply { writeBytes(byteArrayOf(37, 80, 68, 70)) }
        val invocation = sharer.publish(file, "application/pdf")
        assertEquals(Intent.ACTION_SEND, invocation.action)
        assertEquals("application/pdf", invocation.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, invocation.flags)
        assertEquals(0, invocation.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, invocation.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertTrue(invocation.stream.startsWith("content://${context.packageName}.share/"))
        assertTrue(invocation.stream.endsWith(".pdf"))

        val chooser = invocation.chooser()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(invocation.stream, IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java).toString())
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertEquals(invocation.stream, send.clipData!!.getItemAt(0).uri.toString())

        context.contentResolver.openInputStream(invocation.stream.toUri())!!.use {
            assertArrayEquals(byteArrayOf(37, 80, 68, 70), it.readBytes())
        }
        assertTrue("publish 不应自行启动", launched.isEmpty())
    }

    @Test
    fun launchPassesExactlyThePublishedInvocation() {
        val file = sharer.prepare("照片.jpg", "image/jpeg").apply { writeBytes(byteArrayOf(1, 2)) }
        val invocation = sharer.publish(file, "image/jpeg")
        assertTrue(sharer.launch(invocation))
        assertEquals(listOf(invocation), launched)
    }

    @Test
    fun returningRevokesThenDeletesTheTemporaryCopy() {
        val file = sharer.prepare("报告.pdf", "application/pdf").apply { writeBytes(byteArrayOf(9)) }
        val invocation = sharer.publish(file, "application/pdf")
        assertTrue(sharer.launch(invocation))
        val result = sharer.releaseAfterReturn()
        assertTrue(result.revoked)
        assertEquals(1, result.deleted)
        assertNull(result.error)
        assertFalse(file.exists())
        assertTrue(shareDir.listFiles().orEmpty().isEmpty())
        assertThrows(Exception::class.java) {
            context.contentResolver.openInputStream(invocation.stream.toUri())!!.close()
        }
    }

    @Test
    fun residualPlaintextIsClearedWhenTheScreenStarts() {
        shareDir.mkdirs()
        val stale = File(shareDir, "leftover.pdf").apply { writeBytes(byteArrayOf(1)) }
        val result = sharer.clearResidual()
        assertNull(result.error)
        assertFalse(stale.exists())
        assertTrue(shareDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun shareNamesNeverEscapeTheShareDirectory() {
        val file = sharer.prepare("../../vault.bin", "application/octet-stream")
        assertEquals(shareDir.canonicalFile, file.canonicalFile.parentFile)
        assertFalse(file.name.contains('/'))
        assertFalse(file.name.contains('\\'))
        assertFalse(file.name.contains(".."))
        assertTrue(file.name.startsWith("_vault-"))
    }
}
