package cn.yunji.journal

import cn.yunji.journal.core.share.ShareOriginalPolicy
import cn.yunji.journal.core.share.ShareTemporaryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The only directory the share provider can reach: `filesDir/share`, one staged original at a time. */
class ShareTemporaryFilesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun directory(): File = File(temporary.newFolder("data"), ShareOriginalPolicy.DIRECTORY_NAME)

    @Test
    fun prepareCreatesNamesOnlyInsideTheShareDirectory() {
        val store = ShareTemporaryFiles(directory())
        val file = store.prepare("报告-01234567.pdf")
        assertTrue(file.parentFile!!.isDirectory)
        assertEquals(ShareOriginalPolicy.DIRECTORY_NAME, file.parentFile!!.name)
        assertFalse(file.exists())
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(listOf(file.canonicalFile), store.files().map { it.canonicalFile })
        assertTrue(store.contains(file))
    }

    @Test
    fun prepareRejectsTraversalSeparatorsAndHiddenNames() {
        val store = ShareTemporaryFiles(directory())
        listOf("../escape.pdf", "a/b.pdf", "a\\b.pdf", "..", "", "  ", ".hidden.pdf", "/abs.pdf").forEach { bad ->
            assertThrows("应拒绝: $bad", IllegalArgumentException::class.java) { store.prepare(bad) }
        }
    }

    @Test
    fun prepareClearsThePreviousStagedOriginal() {
        val store = ShareTemporaryFiles(directory())
        val first = store.prepare("first.pdf").apply { writeBytes(byteArrayOf(1)) }
        val second = store.prepare("second.pdf")
        assertFalse(first.exists())
        second.writeBytes(byteArrayOf(2))
        assertEquals(1, store.files().size)
    }

    @Test
    fun clearAllRemovesResidueAndKeepsTheDirectory() {
        val store = ShareTemporaryFiles(directory())
        val dir = store.ensureDirectory()
        File(dir, "a.pdf").writeBytes(byteArrayOf(1))
        File(dir, "nested").mkdirs()
        File(File(dir, "nested"), "b.pdf").writeBytes(byteArrayOf(2))
        assertEquals(2, store.clearAll())
        assertTrue(dir.isDirectory)
        assertTrue(store.files().isEmpty())
        assertEquals(0, store.clearAll())
    }

    @Test
    fun clearStaleRemovesOldFilesAndKeepsFreshOnes() {
        var now = 1_700_000_000_000L
        val store = ShareTemporaryFiles(directory(), clock = { now })
        val dir = store.ensureDirectory()
        val old = File(dir, "old.pdf").apply { writeBytes(byteArrayOf(1)); setLastModified(now - ShareOriginalPolicy.MAX_TEMP_MILLIS - 1) }
        val fresh = File(dir, "fresh.pdf").apply { writeBytes(byteArrayOf(2)); setLastModified(now - 1_000) }
        assertEquals(1, store.clearStale(now))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun clearStaleRemovesEntriesWithUnknownTimestamps() {
        val now = 5_000_000L
        val store = ShareTemporaryFiles(directory(), clock = { now })
        val dir = store.ensureDirectory()
        val unknown = File(dir, "zero.pdf").apply { writeBytes(byteArrayOf(1)); setLastModified(0L) }
        assertEquals(1, store.clearStale(now))
        assertFalse(unknown.exists())
    }

    @Test
    fun containsAndDeleteRejectFilesOutsideTheShareDirectory() {
        val root = temporary.newFolder("data-2")
        val store = ShareTemporaryFiles(File(root, ShareOriginalPolicy.DIRECTORY_NAME))
        val outside = File(root, "vault.db").apply { writeBytes(byteArrayOf(1)) }
        assertFalse(store.contains(outside))
        assertFalse(store.delete(outside))
        assertTrue(outside.exists())
        val inside = store.prepare("inside.pdf").apply { writeBytes(byteArrayOf(3)) }
        assertTrue(store.delete(inside))
        assertFalse(inside.exists())
    }

    @Test
    fun directoryNameMustBeTheDedicatedShareDirectory() {
        assertThrows(IllegalArgumentException::class.java) {
            ShareTemporaryFiles(File(temporary.newFolder("data-3"), "files"))
        }
    }
}
