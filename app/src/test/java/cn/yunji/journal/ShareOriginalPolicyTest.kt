package cn.yunji.journal

import cn.yunji.journal.core.share.ShareOriginalPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security rules for the temporary single-original share; no Android platform calls are involved. */
class ShareOriginalPolicyTest {
    @Test
    fun shareAuthorityIsTheApplicationIdPlusTheDedicatedSuffix() {
        assertEquals("cn.yunji.journal.share", ShareOriginalPolicy.authority("cn.yunji.journal"))
        assertEquals("a.b.share", ShareOriginalPolicy.authority("a.b"))
        assertTrue("cn.yunji.journal.share".endsWith(ShareOriginalPolicy.AUTHORITY_SUFFIX))
    }

    @Test
    fun shareAuthorityRejectsMalformedApplicationIds() {
        listOf("", "journal", "cn/evil", "cn..journal", "cn.journal ", "-a.b", "cn.journal..x", "cn.journal.share/x").forEach { bad ->
            assertThrows("应拒绝: $bad", IllegalArgumentException::class.java) { ShareOriginalPolicy.authority(bad) }
        }
    }

    @Test
    fun shareFlagsAreReadOnly() {
        assertEquals(1, ShareOriginalPolicy.flags())
        assertTrue(ShareOriginalPolicy.isReadOnlyGrant(ShareOriginalPolicy.flags()))
    }

    @Test
    fun shareFlagsCarryNoWriteOrPersistableBits() {
        val flags = ShareOriginalPolicy.flags()
        assertEquals(0, flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, flags and android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertFalse(ShareOriginalPolicy.isReadOnlyGrant(flags or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertFalse(ShareOriginalPolicy.isReadOnlyGrant(flags or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
    }

    @Test
    fun shareGrantNeedsTheReadBit() {
        assertFalse(ShareOriginalPolicy.isReadOnlyGrant(0))
        assertFalse(ShareOriginalPolicy.isReadOnlyGrant(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }

    @Test
    fun shareTypeKeepsTheConcreteMimeAndDropsParameters() {
        assertEquals("application/pdf", ShareOriginalPolicy.shareType("application/pdf"))
        assertEquals("image/jpeg", ShareOriginalPolicy.shareType(" image/jpeg; charset=binary "))
        listOf("", "*/*", "pdf", "application/", "a b/c", "a/b c", "\n").forEach { bad ->
            assertThrows("应拒绝: $bad", IllegalArgumentException::class.java) { ShareOriginalPolicy.shareType(bad) }
        }
    }

    @Test
    fun invocationSendsExactlyOneContentStream() {
        val invocation = ShareOriginalPolicy.invocation("content://cn.yunji.journal.share/share/报告-01234567.pdf", "application/pdf")
        assertEquals("android.intent.action.SEND", invocation.action)
        assertEquals("application/pdf", invocation.type)
        assertEquals(1, invocation.flags)
        assertTrue(invocation.stream.startsWith("content://cn.yunji.journal.share/"))
    }

    @Test
    fun invocationRejectsNonContentAndUnsafeUris() {
        listOf(
            "",
            "file:///data/data/cn.yunji.journal/files/share/a.pdf",
            "content:///x",
            "content://cn.yunji.journal.share/share/a b.pdf",
            "content://cn.yunji.journal.share/share/a\n.pdf",
            "content://cn.yunji.journal.share/" + "a".repeat(9000),
        ).forEach { bad ->
            assertThrows("应拒绝: ${bad.take(40)}", IllegalArgumentException::class.java) {
                ShareOriginalPolicy.invocation(bad, "application/pdf")
            }
        }
    }

    @Test
    fun temporaryNameIsStableSafeAndUniquePerToken() {
        val token = "3f7a1c2e-0000-4000-8000-000000000001"
        val pdf = ShareOriginalPolicy.temporaryName("产检 报告.pdf", "application/pdf", token)
        assertEquals("产检 报告-$token.pdf", pdf)
        assertFalse(pdf.contains('/'))
        val heic = ShareOriginalPolicy.temporaryName("报告.heic", "image/heic", token)
        assertTrue(heic.endsWith("-$token.heic"))
        val unknown = ShareOriginalPolicy.temporaryName("附件.xyz", "video/mp4", token)
        assertTrue(unknown.endsWith("-$token.bin"))
        assertThrows(IllegalArgumentException::class.java) { ShareOriginalPolicy.temporaryName("a.pdf", "application/pdf", "../") }
        assertThrows(IllegalArgumentException::class.java) { ShareOriginalPolicy.temporaryName("a.pdf", "application/pdf", "") }
    }

    @Test
    fun safeBaseNameStripsTraversalSeparatorsAndControlCharacters() {
        val base = ShareOriginalPolicy.safeBaseName("../../加密资料/最终\u0000报告.pdf")
        assertFalse(base.contains('/'))
        assertFalse(base.contains('\\'))
        assertFalse(base.contains(".."))
        assertFalse(base.any { it.isISOControl() })
        assertEquals("原件", ShareOriginalPolicy.safeBaseName(""))
        assertEquals("原件", ShareOriginalPolicy.safeBaseName("   .pdf"))
        assertTrue(ShareOriginalPolicy.safeBaseName("a".repeat(400) + ".pdf").length <= 100)
    }
}
