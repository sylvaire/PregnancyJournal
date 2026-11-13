package cn.yunji.journal.core.share

/**
 * Minimal release of a temporary share grant.
 *
 * Order matters: the URI grant is revoked before the plaintext file is deleted, so a failure to
 * delete can never leave a still-granted URI behind. Every step runs independently and the first
 * failure is reported without masking the other step; a second release is a no-op for the caller
 * because the pending URI is consumed by [OriginalSharer].
 */
class ShareRelease(
    private val revoke: (String) -> Unit,
    private val delete: () -> Int,
) {
    fun afterReturn(uri: String?): ShareReleaseResult {
        var revoked = false
        var error: String? = null
        if (uri != null) {
            try {
                revoke(uri)
                revoked = true
            } catch (e: Exception) {
                error = e.message?.takeIf { it.isNotBlank() } ?: "无法撤销临时读取授权"
            }
        }
        var deleted = 0
        try {
            deleted = delete()
        } catch (e: Exception) {
            if (error == null) error = e.message?.takeIf { it.isNotBlank() } ?: "无法清理临时分享文件"
        }
        return ShareReleaseResult(revoked, deleted, error)
    }
}

data class ShareReleaseResult(val revoked: Boolean, val deleted: Int, val error: String?) {
    val clean: Boolean get() = error == null
}
