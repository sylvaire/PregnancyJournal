package cn.yunji.journal

import cn.yunji.journal.core.share.ShareRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Revoke-then-delete ordering and failure isolation for temporary share cleanup. */
class ShareReleaseTest {
    private val events = mutableListOf<String>()

    @Test
    fun releaseRevokesBeforeDeleting() {
        val release = ShareRelease(revoke = { events += "revoke:$it" }, delete = { events += "delete"; 2 })
        val result = release.afterReturn("content://cn.yunji.journal.share/share/a.pdf")
        assertEquals(listOf("revoke:content://cn.yunji.journal.share/share/a.pdf", "delete"), events)
        assertTrue(result.revoked)
        assertEquals(2, result.deleted)
        assertNull(result.error)
        assertTrue(result.clean)
    }

    @Test
    fun releaseWithoutUriStillDeletesResidue() {
        val release = ShareRelease(revoke = { events += "revoke" }, delete = { events += "delete"; 1 })
        val result = release.afterReturn(null)
        assertEquals(listOf("delete"), events)
        assertFalse(result.revoked)
        assertEquals(1, result.deleted)
        assertNull(result.error)
    }

    @Test
    fun revokeFailureStillDeletesAndIsReported() {
        val release = ShareRelease(revoke = { events += "revoke"; throw IllegalStateException("撤销失败") }, delete = { events += "delete"; 1 })
        val result = release.afterReturn("content://x/y")
        assertEquals(listOf("revoke", "delete"), events)
        assertFalse(result.revoked)
        assertEquals(1, result.deleted)
        assertEquals("撤销失败", result.error)
        assertFalse(result.clean)
    }

    @Test
    fun deleteFailureKeepsTheRevokedGrantReported() {
        val release = ShareRelease(revoke = { events += "revoke" }, delete = { events += "delete"; throw IllegalStateException("清理失败") })
        val result = release.afterReturn("content://x/y")
        assertEquals(listOf("revoke", "delete"), events)
        assertTrue(result.revoked)
        assertEquals(0, result.deleted)
        assertEquals("清理失败", result.error)
    }

    @Test
    fun failuresWithoutMessageGetReadableFallbacks() {
        val revoke = ShareRelease(revoke = { throw IllegalStateException() }, delete = { 0 })
        assertEquals("无法撤销临时读取授权", revoke.afterReturn("content://x/y").error)
        val delete = ShareRelease(revoke = {}, delete = { throw IllegalStateException() })
        assertEquals("无法清理临时分享文件", delete.afterReturn("content://x/y").error)
    }

    @Test
    fun secondReleaseIsANoOpForTheSameUri() {
        var deletes = 0
        val release = ShareRelease(revoke = {}, delete = { deletes++; 0 })
        release.afterReturn("content://x/y")
        val second = release.afterReturn("content://x/y")
        assertEquals(0, second.deleted)
        assertEquals(2, deletes)
    }
}
